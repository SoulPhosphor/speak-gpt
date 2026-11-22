package org.teslasoft.assistant.ui.chat.parallel

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.teslasoft.assistant.R
import org.teslasoft.assistant.conversation.parallel.*
import org.teslasoft.assistant.conversation.responsegroups.*
import org.teslasoft.assistant.conversation.setup.*
import org.teslasoft.assistant.imagegen.CreateImageTool
import org.teslasoft.assistant.imagegen.ToolCapability
import org.teslasoft.assistant.imagegen.ToolCapabilityScope
import org.teslasoft.assistant.imagegen.ToolCapabilityStore
import org.teslasoft.assistant.preferences.*
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.preferences.includes.*
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.providers.*
import org.teslasoft.assistant.reasoning.*
import org.teslasoft.assistant.util.*
import java.io.File
import java.util.Base64

/** Shared expensive preparation has already run once. Only model-specific encoding/settings follow. */
internal data class ParallelTurnBase(
    val payload: FrozenChatPayload,
    val nativePdfs: List<NativePdfPayload>,
    val includes: List<ChatInclude>,
    val stableSystemMessages: Int,
    val attribution: Map<String, String>,
    val commitContext: () -> Unit
)
internal data class PreparedParallelTurn(val slots: List<ParallelSlotInput>,
    val context: List<ParallelInputPart>, val current: List<ParallelInputPart>, val commitContext: () -> Unit)

internal class ParallelPreparationRefused(val text: String) : Exception(text)

internal object ParallelTurnPreparation {
    suspend fun prepare(context: Context, chatId: String, setup: ExperimentalSetup,
        base: ParallelTurnBase, confirm: suspend (String) -> Boolean): PreparedParallelTurn = withContext(Dispatchers.IO) {
        val prefs = Preferences.getPreferences(context, chatId)
        val global = Preferences.getPreferences(context, "")
        val endpoints = ApiEndpointPreferences.getApiEndpointPreferences(context).getApiEndpointsList(context).associateBy { it.id }
        val images = LinkedHashMap<String, ResponseAttachment>()
        val unknown = ArrayList<String>()
        val bodies = ArrayList<Pair<ResponseModelAssignment, List<ParallelInputPart>>>()
        val slotInputs = ArrayList<ParallelSlotInput>()
        for (selection in setup.models) {
            val endpoint = endpoints.getValue(selection.endpointId)
            val settings = selection.settings()
            val imageRouting = ImageRoutingConfig.from(endpoint, settings)
            val hasImages = base.payload.messages.any { message -> message.frozenParts?.any { it is FrozenImagePayloadPart } == true }
            if (hasImages) {
                val live = ImageCapabilityMetadataClient.resolve(endpoint, selection.modelId, imageRouting)
                val capability = ImageCapabilityResolver.resolve(endpoint, selection.modelId, imageRouting, live.model, live.pinnedProvider)
                when (capability.capability) {
                    ImageCapability.UNSUPPORTED -> throw ParallelPreparationRefused(context.getString(R.string.parallel_attachment_unsupported, selection.modelId))
                    ImageCapability.UNKNOWN -> if (!ImageCapabilityWarningStore.isSuppressed(context, endpoint, selection.modelId, imageRouting)) unknown += selection.modelId
                    else -> Unit
                }
            }
            val root = JsonObject().apply {
                addProperty("model", selection.modelId)
                addProperty("max_tokens", base.payload.maximumResponseTokens)
                addProperty("stream", settings.streaming ?: true)
                if (settings.streaming != false) add("stream_options", JsonObject().apply { addProperty("include_usage", true) })
                val restricted = selection.modelId.let { it.contains("gpt-5") || it.contains("o1") || it.contains("o3") }
                (if (restricted) 1.0 else settings.temperature?.toDouble()?.takeUnless { it == 0.7 })?.let { addProperty("temperature", it) }
                settings.topP?.toDouble()?.takeUnless { it == 1.0 }?.let { addProperty("top_p", it) }
                settings.frequencyPenalty?.toDouble()?.takeUnless { it == 0.0 }?.let { addProperty("frequency_penalty", it) }
                settings.presencePenalty?.toDouble()?.takeUnless { it == 0.0 }?.let { addProperty("presence_penalty", it) }
                base.payload.seed?.let { addProperty("seed", it) }
                if (!restricted) base.payload.logitBias?.takeIf { it.isNotEmpty() }?.let { bias ->
                    add("logit_bias", JsonObject().apply { bias.forEach { (key, value) -> addProperty(key, value) } })
                }
            }
            val capability = EndpointReasoningCapability.resolveWithLearnedRejections(endpoint.reasoningCapabilityByModel,
                endpoint.reasoningRejectedLevelsByModel, selection.modelId,
                providerPath = ReasoningProviderPath.forEndpoint(endpoint.host, endpoint.isOpenRouterRouting()))
            val reasoning = ReasoningSettingsResolver.resolve(null, ReasoningEffort.fromSerialized(settings.reasoningEffort), settings.showReasoning, capability)
            ReasoningRequestSerializer.requestFields(reasoning, endpoint.isOpenRouterRouting(), capability.isReasoningCapable)
                ?.entrySet()?.forEach { (key, value) -> root.add(key, value) }
            val routing = ProviderRoutingResolver.resolve(endpoint.isOpenRouterRouting(), settings)
            if (routing.block != RoutingBlock.NONE) throw ParallelPreparationRefused(context.getString(R.string.experimental_setup_invalid))
            routing.providerJson?.let { root.add("provider", it) }
            val toolScope = ToolCapabilityScope.key(selection.modelId, endpoint.isOpenRouterRouting(), settings.routingType,
                settings.selectedProvider, settings.allowFallbacks, settings.providerOrder, settings.ignoredProviders)
            if (setup.allowImages && ToolCapabilityStore.get(endpoint.toolCapabilityByModel, toolScope) != ToolCapability.UNSUPPORTED && CreateImageTool.shouldOfferTool(global.getAiCreateImagesEnabled(),
                    global.getImageGeneratorEndpointId(), global.getImageGeneratorModel())) {
                // The existing tool definition is serialized by its authoritative kotlinx serializer.
                root.add("tools", JsonParser.parseString(kotlinx.serialization.json.Json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(com.aallam.openai.api.chat.Tool.serializer()), listOf(CreateImageTool.definition()))))
            }
            val attachmentParts = ArrayList<ParallelInputPart>()
            val pdfRouting = PdfRoutingConfig.from(endpoint, settings)
            var pdfCapability = PdfCapabilityResolver.resolve(endpoint, selection.modelId, pdfRouting)
            if (pdfCapability == PdfCapability.UNKNOWN && pdfRouting.provider in setOf(PdfCapabilityProvider.OPENROUTER, PdfCapabilityProvider.NANOGPT)) {
                val live = PdfCapabilityMetadataClient.resolve(endpoint, selection.modelId, pdfRouting)
                pdfCapability = PdfCapabilityResolver.resolve(endpoint, selection.modelId, pdfRouting, live.model, live.pinnedProvider)
            }
            val messages = base.payload.messages.map { message -> message(message, context, chatId, images, attachmentParts) }.toMutableList()
            // Each model's rules remain a distinct layer after the stable companion/system prefix.
            if (prefs.getChatApplyModelRules() && MemoryStore.isProvisioned(context)) {
                val rules = MemoryStore.getInstance(context).getActiveModelRulesForModel(selection.endpointId, selection.modelId)
                if (rules.isNotEmpty()) messages.add(base.stableSystemMessages, JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content", rules.joinToString("\n", context.getString(R.string.model_rules_injection_header) + "\n") { "- " + it.text })
                })
            }
            for (pdf in base.nativePdfs) {
                val include = base.includes.first { it.id == pdf.includeId }
                val file = PdfAttachmentStore.pdfFile(context, chatId, include)?.takeIf { it.isFile }
                    ?: throw ParallelPreparationRefused(context.getString(R.string.parallel_prepare_failed))
                val ref = descriptor("pdf", file, "application/pdf")
                val useNative = PdfDeliveryPolicy.useNative(pdfRouting.provider, pdfCapability, file.length())
                val replacement = if (useNative) {
                    attachmentParts += ParallelInputPart.Attachment(ref)
                    val filePart = if (PdfDeliveryPolicy.usesUploadedFile(pdfRouting.provider)) {
                        ParallelChatSnapshot.uploadedFile(XaiPdfFiles.fileIdFor(context, chatId, endpoint, include, file), ref)
                    } else JsonObject().apply {
                        addProperty("filename", pdf.fileName)
                        addProperty("file_data", ParallelChatSnapshot.attachmentReference(ref))
                    }
                    JsonObject().apply { addProperty("type", "file"); add("file", filePart) }
                } else JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", IncludeRenderer.renderLocalPdf(include, PdfFallbackExtractor.extract(context, chatId, include).text))
                }
                var found = false
                messages.forEach { msg -> msg.get("content")?.takeIf { it.isJsonArray }?.asJsonArray?.let { parts ->
                    for (index in 0 until parts.size()) {
                        val part = parts[index].asJsonObject
                        if (part.get("type")?.asString == "text" && part.get("text")?.asString == StableAttachmentReference.serialize(include)) {
                            parts.set(index, replacement); found = true
                        }
                    }
                } }
                check(found) { "missing_pdf_slot" }
                if (useNative && pdfRouting.provider == PdfCapabilityProvider.OPENROUTER) root.add("plugins", JsonArray().apply {
                    add(JsonObject().apply { addProperty("id", "file-parser"); add("pdf", JsonObject().apply { addProperty("engine", "native") }) })
                })
            }
            // Keep current words in one shared frame. Attachment parts follow in their original order.
            splitCurrentWords(messages)
            root.add("messages", JsonArray().apply { messages.forEach(::add) })
            val body = if (endpoint.isOpenRouterRouting()) PromptCachingRequestSerializer.augmentOpenRouterChatBody(root.toString(), chatId, false) else root.toString()
            val assignment = ParallelChatSnapshot.assignment(ResponseModelAssignment(selection.modelId, selection.modelId,
                endpoint.id, endpoint.host, "chat_completions", mapOf("settings" to selection.settingsJson), emptyMap(), emptyMap(), emptyMap()),
                endpoint, body, setup.allowImages, settings.showReasoning)
            val frames = JsonParser.parseString(body).asJsonObject.getAsJsonArray("messages").map { ParallelChatSnapshot.message(it.asJsonObject) }
            val prompt = frames + attachmentParts.distinct()
            bodies += assignment to prompt
            val indicator = ReasoningIndicator.forGeneration(capability, reasoning.effort)?.token
            slotInputs += ParallelSlotInput(assignment, prompt, base.attribution +
                mapOf("responseModel" to selection.modelId) + (indicator?.let { mapOf("reasoningLevel" to it) } ?: emptyMap()))
            val capacity = ModelContextCapacity.decide(endpoint.contextWindowTokens.takeIf { endpoint.contextWindowModelId == selection.modelId },
                RequestCapacity.approximateInputTokens(base.payload), base.payload.maximumResponseTokens)
            when (capacity) {
                is ModelContextDecision.Block -> throw ParallelPreparationRefused(context.getString(R.string.parallel_context_block, selection.modelId))
                is ModelContextDecision.WarnRange, is ModelContextDecision.WarnApproximate -> if (!confirm(context.getString(R.string.parallel_context_warning, selection.modelId))) throw kotlinx.coroutines.CancellationException()
                else -> Unit
            }
        }
        if (unknown.isNotEmpty() && !confirm(context.getString(R.string.parallel_attachment_unknown, unknown.distinct().joinToString())))
            throw kotlinx.coroutines.CancellationException()
        val common = bodies.first().second.filter { part -> bodies.all { part in it.second } }
        val lastUser = common.filterIsInstance<ParallelInputPart.Text>().last { JsonParser.parseString(it.value).asJsonObject.get("role").asString == "user" }
        // Pool stores every exact per-model frame. Shared eligibility is their common ordered prefix/current words.
        val prefix = bodies.first().second.takeWhile { it != lastUser }.filter { it in common }
        PreparedParallelTurn(slotInputs, prefix, listOf(lastUser), base.commitContext)
    }

    private fun descriptor(store: String, file: File, mime: String): ResponseAttachment =
        ResponseAttachment(store, file.name, mime, file.length(), ResponseAttachment.digest(file.readBytes()))

    private fun message(message: FrozenPayloadMessage, context: Context, chatId: String,
        images: MutableMap<String, ResponseAttachment>, attachments: MutableList<ParallelInputPart>): JsonObject = JsonObject().apply {
        addProperty("role", message.role)
        if (message.frozenParts == null) addProperty("content", message.content)
        else add("content", JsonArray().apply { message.frozenParts.forEach { part ->
            add(when (part) {
                is FrozenTextPayloadPart -> JsonObject().apply { addProperty("type", "text"); addProperty("text", part.text) }
                is FrozenImagePayloadPart -> {
                    val ref = images.getOrPut(part.url) {
                        val mime = part.url.substringAfter("data:").substringBefore(';')
                        val bytes = Base64.getDecoder().decode(part.url.substringAfter(','))
                        val digest = ResponseAttachment.digest(bytes)
                        val file = File(ImageImporter.chatImagesDir(context, chatId), "$digest.${if (mime == "image/png") "png" else "jpg"}")
                        if (!file.isFile) file.writeBytes(bytes)
                        ResponseAttachment("image", file.name, mime, bytes.size.toLong(), digest)
                    }
                    attachments += ParallelInputPart.Attachment(ref)
                    JsonObject().apply { addProperty("type", "image_url"); add("image_url", JsonObject().apply {
                        addProperty("url", ParallelChatSnapshot.attachmentReference(ref)); part.detail?.let { addProperty("detail", it) }
                    }) }
                }
            })
        } })
    }

    private fun splitCurrentWords(messages: MutableList<JsonObject>) {
        val last = messages.last()
        val parts = last.get("content")?.takeIf { it.isJsonArray }?.asJsonArray ?: return
        val first = parts.firstOrNull()?.asJsonObject ?: return
        if (first.get("type")?.asString != "text") return
        val words = JsonObject().apply { addProperty("role", "user"); addProperty("content", first.get("text").asString) }
        val tail = JsonArray().apply { parts.drop(1).forEach(::add) }
        messages.removeAt(messages.lastIndex)
        messages.add(words)
        if (!tail.isEmpty) messages.add(JsonObject().apply { addProperty("role", "user"); add("content", tail) })
    }
}
