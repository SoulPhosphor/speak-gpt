package org.teslasoft.assistant.conversation.parallel

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.teslasoft.assistant.imagegen.*
import org.teslasoft.assistant.preferences.Preferences

internal fun interface ParallelToolExecutor {
    suspend fun execute(owner: ParallelRequest, assistantMessage: String,
        reserveImage: suspend () -> String,
        recordImage: suspend (String, String) -> Unit): List<String>
}

/** Existing global rules, evaluated when the tool executes. No session-specific image rules. */
internal data class ParallelImageRules(
    val enabled: Boolean,
    val askBefore: Boolean,
    val endpointId: String,
    val modelId: String,
    val shape: ImageShape,
    val quality: ImageQuality,
    val parameters: Map<String, String>
) {
    companion object {
        fun global(context: Context): ParallelImageRules = Preferences.getPreferences(context, "").let { p ->
            ParallelImageRules(p.getAiCreateImagesEnabled(), p.getAskBeforeAiImages(), p.getImageGeneratorEndpointId(),
                p.getImageGeneratorModel(), p.getImageGeneratorShape(), p.getImageGeneratorQuality(), p.getImageGeneratorParameters().toMap())
        }
    }
}

/** Confirmation callbacks carry their exact slot/request owner. Only confirmation presentation is queued;
 * generating an approved image never holds the confirmation mutex or blocks a sibling text stream.
 */
internal class ParallelImageTools(
    private val rules: () -> ParallelImageRules,
    private val confirm: suspend (ParallelRequest, String) -> Boolean,
    private val generate: suspend (ParallelRequest, String, ImageGenerationRequest, suspend (String, String) -> Unit) -> String
) : ParallelToolExecutor {
    private val confirmations = Mutex()

    override suspend fun execute(owner: ParallelRequest, assistantMessage: String,
        reserveImage: suspend () -> String, recordImage: suspend (String, String) -> Unit): List<String> {
        var usedImage = false
        return JsonParser.parseString(assistantMessage).asJsonObject.getAsJsonArray("tool_calls").map { el ->
            val call = el.asJsonObject
            val function = call.getAsJsonObject("function")
            val result = when {
                function.get("name").asString != CreateImageTool.NAME -> CreateImageTool.errorResult("unknown tool — it was not executed")
                usedImage -> CreateImageTool.errorResult("only one image may be created per model per user turn")
                owner.assignment.parameters[ParallelChatSnapshot.ALLOW_IMAGES] == "false" -> CreateImageTool.errorResult("image generation is disabled for this model")
                else -> {
                    usedImage = true
                    val validation = CreateImageTool.validate(function.get("arguments").asString)
                    if (validation is CreateImageTool.Validation.Invalid) CreateImageTool.errorResult(validation.toolError)
                    else {
                        val valid = validation as CreateImageTool.Validation.Valid
                        val global = rules()
                        when {
                            !CreateImageTool.shouldOfferTool(global.enabled, global.endpointId, global.modelId) ->
                                CreateImageTool.errorResult("image generation is not enabled or no image generator is configured")
                            global.askBefore && !confirmations.withLock { confirm(owner, valid.prompt) } -> CreateImageTool.cancelledResult()
                            else -> {
                                // Reserve the image identity durably before invoking the paid image pipeline.
                                val imageId = reserveImage()
                                val request = ImageGenerationRequest(valid.prompt, valid.shapeOverride ?: ImageShape.AUTOMATIC,
                                    ImageQuality.AUTOMATIC, global.endpointId, global.modelId, valid.description,
                                    parameters = global.parameters, defaultShape = global.shape, defaultQuality = global.quality)
                                try { generate(owner, imageId, request, recordImage) }
                                catch (e: CancellationException) { throw e }
                                catch (_: Exception) { CreateImageTool.errorResult("image generation failed") }
                            }
                        }
                    }
                }
            }
            JsonObject().apply { addProperty("role", "tool"); addProperty("tool_call_id", call.get("id").asString)
                addProperty("content", result) }.toString()
        }
    }
}
