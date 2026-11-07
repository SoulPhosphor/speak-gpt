package org.teslasoft.assistant.providers

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.preferences.includes.ImageCapabilityProvider
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Catalog transport. Only documented provider-specific request parameters live here. */
internal class ModelBrowserCatalogClient(private val endpoint: ApiEndpointObject) {
    private val base = endpoint.host.toHttpUrl()
    private val kind = ImageCapabilityProvider.forEndpoint(endpoint)
    private val client = OkHttpClient.Builder()
        .connectTimeout(
            ApiEndpointObject.coerceConnectTimeoutSeconds(endpoint.connectTimeoutSeconds).toLong(),
            TimeUnit.SECONDS
        )
        .readTimeout(45, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val url = request.url
            val redirected = url.host != base.host || url.scheme != base.scheme || url.port != base.port
            chain.proceed(if (!redirected) request else request.newBuilder()
                .removeHeader("Authorization")
                .removeHeader("x-api-key")
                .removeHeader("api-key")
                .build())
        }
        .build()

    val supportsServerSearch = kind == ImageCapabilityProvider.FEATHERLESS
    val supportsProviderDirectory = endpoint.hasOpenRouterCatalogAuthority()

    suspend fun models(
        page: Int,
        query: String,
        newestFirst: Boolean,
        providers: List<ModelBrowserProvider> = emptyList()
    ): String {
        val url = base.newBuilder().addPathSegment("models").apply {
            when (kind) {
                ImageCapabilityProvider.NANOGPT -> setQueryParameter("detailed", "true")
                ImageCapabilityProvider.VENICE -> setQueryParameter("type", "text")
                ImageCapabilityProvider.FEATHERLESS -> {
                    setQueryParameter("page", page.toString())
                    if (query.isNotBlank()) setQueryParameter("q", query)
                    if (newestFirst) setQueryParameter("sort", "-created_at")
                }
                else -> if (page > 1) setQueryParameter("page", page.toString())
            }
            if (providers.isNotEmpty()) {
                setQueryParameter("providers", providers.joinToString(",") { it.name })
            }
        }.build()
        return get(url)
    }

    suspend fun providers(): List<ModelBrowserProvider> = ModelBrowserCatalog.parseProviderDirectory(
        get(base.newBuilder().addPathSegment("providers").build())
    )

    private suspend fun get(url: okhttp3.HttpUrl): String = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url).header("Accept", "application/json").apply {
            if (endpoint.apiKey.isNotBlank()) {
                when (endpoint.authType) {
                    ApiEndpointObject.AUTH_X_API_KEY -> header("x-api-key", endpoint.apiKey)
                    ApiEndpointObject.AUTH_API_KEY -> header("api-key", endpoint.apiKey)
                    else -> header("Authorization", "Bearer ${endpoint.apiKey}")
                }
            }
        }.build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        val body = it.body?.string().orEmpty()
                        if (!it.isSuccessful) throw IOException(body.ifBlank { "HTTP ${it.code}" })
                        continuation.resume(body)
                    }
                } catch (e: IOException) {
                    continuation.resumeWithException(e)
                }
            }
        })
    }
}
