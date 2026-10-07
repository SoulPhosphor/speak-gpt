package org.teslasoft.assistant.imagegen

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import java.util.Base64

/** Native Gemini generateContent supports image output; its OpenAI chat compatibility route does not. */
object GeminiImageAdapter : ImageProviderAdapter {
    override val providerName = "Google"

    fun buildRequestBodyJson(request: ImageGenerationRequest): String = JSONObject().apply {
        put("contents", JSONArray().put(JSONObject().put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", request.prompt)))))
        put("generationConfig", JSONObject().apply {
            put("responseModalities", JSONArray().put("TEXT").put("IMAGE"))
            if (request.parameters.isNotEmpty()) put("imageConfig", JSONObject().apply {
                request.parameters["aspect_ratio"]?.let { put("aspectRatio", it) }
                request.parameters["resolution"]?.let { put("imageSize", it) }
            })
        })
    }.toString()

    override fun buildHttpRequest(request: ImageGenerationRequest, endpoint: ApiEndpointObject): Request {
        val id = request.modelId.removePrefix("models/")
        val encoded = java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")
        return Request.Builder().url(ImageApiRoutes.base(endpoint) + "models/$encoded:generateContent")
            .header("x-goog-api-key", endpoint.apiKey)
            .post(buildRequestBodyJson(request).toRequestBody("application/json".toMediaType())).build()
    }

    override fun parseResponse(body: String): AdapterImageResponse {
        val root = imageJson(body) ?: throw ImageGenerationException(ImageErrorCause.NO_USABLE_IMAGE, "the response was not valid JSON")
        val candidates = root.imageArray("candidates")
        candidates?.forEach { candidate ->
            val parts = candidate.imageObject()?.get("content").imageObject()?.imageArray("parts")
            parts?.forEach partLoop@{ element ->
                val part = element.imageObject() ?: return@partLoop
                if (part.get("thought")?.asBoolean == true) return@partLoop
                val data = (part.get("inlineData") ?: part.get("inline_data")).imageObject() ?: return@partLoop
                val mime = data.imageText("mimeType") ?: data.imageText("mime_type")
                val encoded = data.imageText("data")
                if (mime?.startsWith("image/") == true && encoded != null) {
                    val bytes = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) {
                        throw ImageGenerationException(ImageErrorCause.NO_USABLE_IMAGE, "the Base64 image data could not be decoded")
                    }
                    return AdapterImageResponse(ImagePayload.Bytes(bytes), root.get("usageMetadata")?.toString()?.take(200))
                }
            }
        }
        val blocked = root.get("promptFeedback").imageObject()?.imageText("blockReason") != null ||
            candidates?.any { it.imageObject()?.imageText("finishReason") in setOf("SAFETY", "IMAGE_SAFETY", "PROHIBITED_CONTENT") } == true
        throw ImageGenerationException(if (blocked) ImageErrorCause.PROMPT_REFUSED else ImageErrorCause.NO_USABLE_IMAGE,
            if (blocked) "the provider refused the image request" else "the model reply contained no image")
    }

    override fun classifyHttpError(status: Int, body: String): ImageErrorCause = when (status) {
        401, 403 -> ImageErrorCause.AUTHENTICATION_FAILED
        404 -> ImageErrorCause.GENERATOR_MODEL_REJECTED
        400 -> if (body.contains("imageConfig", true) || body.contains("image_size", true))
            ImageErrorCause.UNSUPPORTED_OPTION else ImageErrorCause.PROVIDER_ERROR
        else -> ImageErrorCause.PROVIDER_ERROR
    }
}
