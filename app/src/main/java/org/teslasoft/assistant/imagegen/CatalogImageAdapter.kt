package org.teslasoft.assistant.imagegen

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject

/** OpenRouter and NanoGPT's dedicated Images API. Settings come from its model descriptors. */
object CatalogImageAdapter : ImageProviderAdapter {
    override val providerName = "Image API"

    fun buildRequestBodyJson(request: ImageGenerationRequest): String = JSONObject().apply {
        put("model", request.modelId)
        put("prompt", request.prompt)
        put("n", 1)
        putImageParameters(this, request)
    }.toString()

    override fun buildHttpRequest(request: ImageGenerationRequest, endpoint: ApiEndpointObject): Request =
        Request.Builder().url(ImageApiRoutes.base(endpoint) + "images")
            .post(buildRequestBodyJson(request).toRequestBody("application/json".toMediaType()))
            .apply { ImageApiRoutes.headers(endpoint).forEach { (name, value) -> header(name, value) } }.build()

    override fun parseResponse(body: String) = OpenAiImageAdapter.parseResponse(body)

    override fun classifyHttpError(status: Int, body: String): ImageErrorCause {
        val root = imageJson(body)
        val error = root?.get("error").imageObject()
        val parameter = error?.imageText("parameter") ?: error?.imageText("param")
        return if (status == 400 && parameter != null && parameter !in setOf("model", "prompt"))
            ImageErrorCause.UNSUPPORTED_OPTION else OpenAiImageAdapter.classifyHttpError(status, body)
    }
}
