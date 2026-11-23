package org.teslasoft.assistant.conversation.parallel

import android.content.Context
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.teslasoft.assistant.conversation.responsegroups.*
import org.teslasoft.assistant.preferences.*
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.preferences.includes.ImageImporter
import org.teslasoft.assistant.preferences.includes.PdfAttachmentStore
import org.teslasoft.assistant.reasoning.*
import org.teslasoft.assistant.usage.*
import org.teslasoft.assistant.util.ChatTitleExtractor
import java.io.File
import java.util.UUID

/** One auxiliary title request on the first saved model's route. Never runs through the reply coordinator. */
internal object ParallelChatTitles {
    suspend fun name(context: Context, chatId: String, group: ResponseGroup, maxAttempts: Int,
        alive: () -> Boolean, summarize: suspend (List<ChatMessage>) -> String?,
        apply: suspend (String, String) -> Boolean, retainUsage: (UsageLogEntry) -> Unit) {
        val prefs = Preferences.getPreferences(context, chatId)
        val placeholder = ChatPreferences.getChatPreferences().getChatName(context, chatId)
        val messages by lazy { textMessages(group) }
        run(prefs, placeholder, maxAttempts, alive,
            { org.teslasoft.assistant.util.summarizer.SummarizerController.isConfigured(context) },
            { generate(context, chatId, group, retainUsage) }, { summarize(messages) }, apply)
    }

    /** Shared persisted attempt budget; a changed source choice invalidates a result after the wait. */
    suspend fun run(prefs: Preferences, placeholder: String, maxAttempts: Int, alive: () -> Boolean,
        summarizerConfigured: () -> Boolean, chatTitle: suspend () -> String?,
        summarize: suspend () -> String?, apply: suspend (String, String) -> Boolean) {
        if (prefs.getAutoNameAttempts() >= maxAttempts || !alive() || !placeholder.trim().contains("_autoname_")) return
        val useSummarizer = prefs.getUseSummarizerForTitles()
        if (useSummarizer && !summarizerConfigured()) return
        prefs.setAutoNameAttempts(prefs.getAutoNameAttempts() + 1)
        val title = if (useSummarizer) summarize() else try {
            chatTitle()
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        if (!alive() || prefs.getUseSummarizerForTitles() != useSummarizer) return
        val renamed = !title.isNullOrBlank() && apply(title, placeholder)
        if (!renamed && !useSummarizer && prefs.getAutoNameAttempts() >= maxAttempts) {
            val backup = summarize()
            if (alive() && !prefs.getUseSummarizerForTitles() && !backup.isNullOrBlank() && backup != title)
                apply(backup, placeholder)
        }
    }

    private fun frames(group: ResponseGroup): List<JsonObject> {
        val parts = group.input.parts.associateBy { it.partId }
        val messages = group.slots.first().promptPartIds.mapNotNull { parts.getValue(it).text }
            .map { JsonParser.parseString(it).asJsonObject.deepCopy() }.toMutableList()
        val boundary = messages.indexOfFirst { it.get("role").asString !in setOf("system", "developer") }
            .let { if (it < 0) messages.size else it }
        messages.add(boundary, JsonObject().apply {
            addProperty("role", "system")
            addProperty("content", ChatTitleExtractor.TITLE_INSTRUCTION)
        })
        return messages
    }

    /** The existing summarizer title route is text-only and filters the system prefix itself. */
    fun textMessages(group: ResponseGroup): List<ChatMessage> = frames(group).mapNotNull { frame ->
        val content = frame.get("content")
        val text = when {
            content == null || content.isJsonNull -> ""
            content.isJsonPrimitive -> content.asString
            content.isJsonArray -> content.asJsonArray.mapNotNull { part ->
                part.asJsonObject.takeIf { it.get("type")?.asString == "text" }?.get("text")?.asString
            }.joinToString("\n")
            else -> ""
        }
        if (text.isBlank()) return@mapNotNull null
        val role = when (frame.get("role").asString) {
            "user" -> ChatRole.User
            "assistant" -> ChatRole.Assistant
            "system", "developer" -> ChatRole.System
            else -> return@mapNotNull null
        }
        ChatMessage(role = role, content = text)
    }

    fun prepare(chatId: String, group: ResponseGroup, read: (ResponseAttachment) -> ByteArray?,
        reasoningOff: JsonObject? = null): ParallelRequest {
        val slot = group.slots.first()
        val original = slot.assignment
        val options = JsonParser.parseString(original.parameters.getValue(ParallelChatSnapshot.OPTIONS)).asJsonObject.deepCopy()
        options.addProperty("stream", false)
        options.addProperty("max_tokens", 128)
        listOf("stream_options", "tools", "tool_choice", "parallel_tool_calls").forEach { options.remove(it) }
        reasoningOff?.entrySet()?.forEach { (key, value) -> options.add(key, value) }
        val assignment = original.copy(parameters = original.parameters +
            (ParallelChatSnapshot.OPTIONS to options.toString()))
        val parts = group.input.parts.associateBy { it.partId }
        val binaries = slot.promptPartIds.map { parts.getValue(it) }.filter { it.attachment != null }
        val bytes = binaries.associate { part ->
            val ref = part.attachment!!
            val value = read(ref)
            require(value != null && ref.matches(value)) { "snapshot_attachment_unavailable" }
            part.partId to value
        }
        val prompt = frames(group).map { ResponseSnapshotPart(UUID.randomUUID().toString(), text = it.toString()) } + binaries
        val requestId = UUID.randomUUID().toString()
        return ParallelRequest(ResponseGroupTarget(chatId, group.assistantMessageId, group.groupId), slot.slotId,
            slot.canonicalVersionId, requestId, assignment, prompt, bytes,
            ProviderUsageAttempt(assignment.modelId, TokenUsageAccounting.PROVIDER_NOT_REPORTED, assignment.endpoint, requestId))
    }

    fun endpointMatches(assignment: ResponseModelAssignment, endpoint: ApiEndpointObject): Boolean =
        endpoint.id == assignment.endpointProfileId && endpoint.host == assignment.endpoint &&
            endpoint.chatEndpoint == assignment.parameters["parallel_chat_path"] &&
            endpoint.authType == assignment.capabilities["parallel_auth_type"] &&
            endpoint.identity == assignment.capabilities["parallel_endpoint_identity"] && endpoint.apiKey.isNotBlank()

    suspend fun generate(context: Context, chatId: String, group: ResponseGroup,
        retainUsage: (UsageLogEntry) -> Unit): String? = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val assignment = group.slots.first().assignment
        fun endpoint(): ApiEndpointObject {
            val current = ApiEndpointPreferences.getApiEndpointPreferences(app).getApiEndpoint(app, assignment.endpointProfileId)
            if (!endpointMatches(assignment, current)) throw ParallelTransportFailure("endpoint_configuration_changed")
            return current
        }
        val selected = endpoint()
        val capability = EndpointReasoningCapability.resolveWithLearnedRejections(selected.reasoningCapabilityByModel,
            selected.reasoningRejectedLevelsByModel, assignment.modelId,
            providerPath = ReasoningProviderPath.forEndpoint(selected.host, selected.isOpenRouterRouting()))
        val off = if (capability.isReasoningCapable && capability.canDisableReasoning)
            ReasoningRequestSerializer.requestFields(ResolvedReasoning(ReasoningEffort.OFF, false,
                ResolvedReasoning.Source.DEFAULT_AUTO), selected.isOpenRouterRouting(), true) else null
        val request = prepare(chatId, group, { ref ->
            val dir = when (ref.store) {
                "image" -> ImageImporter.chatImagesDir(app, chatId)
                "pdf" -> PdfAttachmentStore.chatPdfsDir(app, chatId)
                else -> error("snapshot_store")
            }
            File(dir, ref.fileName).takeIf { it.isFile && it.length() == ref.sizeBytes }?.readBytes()
        }, off)
        val prefs = Preferences.getPreferences(app, chatId)
        val started = System.currentTimeMillis()
        val unknown = TurnUsageRecord(model = assignment.modelId, provider = TokenUsageAccounting.PROVIDER_NOT_REPORTED,
            apiEndpoint = assignment.endpoint, requestId = request.requestId,
            requestedModel = assignment.modelId, requestStartedAtMs = started)
        fun entry(record: TurnUsageRecord) = UsageLogEntry(request.requestId, UsageCategory.SUMMARIZING, null,
            started, record, UsageFunction.SUMMARIZING)
        check(UsageLogStore.putRequest(prefs, entry(unknown))) { "title_usage_commit_failed" }
        val pricing = async { TokenPricingCatalogClient.load(selected, assignment.modelId) }
        val response = StringBuilder()
        var succeeded = false
        try {
            ParallelChatTransport { endpoint().apiKey }.execute(request, object : ParallelOutput {
                override suspend fun text(delta: String) { response.append(delta) }
                override suspend fun reasoning(delta: String) {}
                override suspend fun warning(value: String) {}
                override suspend fun tools(assistantMessage: String) {}
            })
            succeeded = true
            ChatTitleExtractor.extract(response.toString())
        } finally {
            withContext(NonCancellable) {
                val receipt = AuxiliaryUsage.record(request.usage, pricing, succeeded) { reported ->
                    TokenPricingCatalogClient.load(selected, reported)
                }
                if (receipt != null) {
                    val completed = entry(receipt.copy(requestId = request.requestId,
                        requestedModel = assignment.modelId, requestStartedAtMs = started))
                    val saved = try { UsageLogStore.putRequest(prefs, completed) } catch (_: Exception) { false }
                    if (!saved) withContext(Dispatchers.Main.immediate) { retainUsage(completed) }
                }
            }
        }
    }
}
