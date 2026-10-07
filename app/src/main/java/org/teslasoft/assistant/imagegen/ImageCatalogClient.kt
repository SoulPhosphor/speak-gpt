package org.teslasoft.assistant.imagegen

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Public document addresses and API routes are protocol; their contents remain runtime data. */
object ImageApiRoutes {
    fun base(endpoint: ApiEndpointObject): String {
        val url = endpoint.host.trim().toHttpUrl()
        return when (ImageProviderKind.forEndpoint(endpoint)) {
            ImageProviderKind.GEMINI -> url.newBuilder().encodedPath("/v1beta/").query(null).build().toString()
            ImageProviderKind.OPENAI -> if (url.encodedPath == "/")
                url.newBuilder().encodedPath("/v1/").query(null).build().toString()
                else endpoint.host.trim().trimEnd('/') + "/"
            ImageProviderKind.OPENROUTER -> if (url.encodedPath == "/")
                url.newBuilder().encodedPath("/api/v1/").query(null).build().toString()
                else endpoint.host.trim().trimEnd('/') + "/"
            ImageProviderKind.NANOGPT -> url.newBuilder().encodedPath("/api/v1/").query(null).build().toString()
            else -> endpoint.host.trim().trimEnd('/') + "/"
        }
    }

    fun headers(endpoint: ApiEndpointObject): Map<String, String> =
        if (ImageProviderKind.forEndpoint(endpoint) == ImageProviderKind.GEMINI)
            mapOf("x-goog-api-key" to endpoint.apiKey)
        else ImageEndpointAuth.headers(endpoint)

    fun modelEndpoints(endpoint: ApiEndpointObject, id: String): String =
        (base(endpoint) + "images/models/" + id.split('/').joinToString("/") {
            java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        } + "/endpoints")
}

/** Bounded GETs. No API key is ever attached to public documentation requests. */
class ImageMetadataHttp(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
    .callTimeout(15, TimeUnit.SECONDS).followRedirects(false).build()) {
    data class Result(val status: Int, val body: String, val retryAfter: String?)

    fun get(url: String, endpoint: ApiEndpointObject? = null): String? =
        result(url, endpoint)?.takeIf { it.status in 200..299 }?.body

    fun result(url: String, endpoint: ApiEndpointObject? = null): Result? {
        val target = url.toHttpUrlOrNull() ?: return null
        val request = Request.Builder().url(target).header("Accept", "application/json, text/markdown, text/html").get()
        if (endpoint != null) {
            val origin = endpoint.host.toHttpUrlOrNull() ?: return null
            if (origin.scheme != target.scheme || origin.host != target.host || origin.port != target.port) return null
            ImageApiRoutes.headers(endpoint).forEach { (name, value) -> request.header(name, value) }
        }
        return client.newCall(request.build()).execute().use { response ->
            val input = response.body?.byteStream() ?: return@use null
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            input.use streamUse@{ stream ->
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (out.size() + count > 4 * 1024 * 1024) return null
                    out.write(buffer, 0, count)
                }
            }
            Result(response.code, out.toString(Charsets.UTF_8.name()), response.header("Retry-After"))
        }
    }
}

/** Model catalogs are reusable; billing metadata is fetched afresh before a paid request. */
object ImageCatalogClient {
    private val http = ImageMetadataHttp()
    private data class Cached(val time: Long, val models: List<ImageModelMetadata>)
    private val cache = ConcurrentHashMap<String, Cached>()
    private fun key(endpoint: ApiEndpointObject) = endpoint.host + "|" + endpoint.id + "|" + endpoint.authType + "|" + ImageProviderKind.forEndpoint(endpoint) + "|" + java.security.MessageDigest.getInstance("SHA-256").digest(endpoint.apiKey.toByteArray()).joinToString("") { "%02x".format(it) }

    fun cachedModel(endpoint: ApiEndpointObject, model: String): ImageModelMetadata? =
        cache[key(endpoint)]?.models?.firstOrNull { it.id == model }

    fun models(endpoint: ApiEndpointObject, fresh: Boolean = false): List<ImageModelMetadata> {
        val cached = cache[key(endpoint)]
        if (!fresh && cached != null && System.currentTimeMillis() - cached.time < 10 * 60 * 1000) return cached.models
        val base = ImageApiRoutes.base(endpoint)
        val models = when (ImageProviderKind.forEndpoint(endpoint)) {
            ImageProviderKind.OPENROUTER, ImageProviderKind.NANOGPT -> {
                val url = base + "images/models"
                val body = http.get(url, endpoint) ?: throw IllegalStateException("The provider's image model list could not be read")
                ImageMetadataParser.catalog(body, url)
            }
            ImageProviderKind.OPENAI -> {
                val indexUrl = "https://developers.openai.com/api/docs/models.md"
                val index = http.get(indexUrl) ?: throw IllegalStateException("The published image model catalog could not be read")
                val advertised = http.get(base + "models", endpoint)?.let { imageJson(it)?.imageArray("data") }
                    ?.mapNotNull { it.imageObject()?.imageText("id") }?.toSet()
                OpenAiImageMetadataParser.candidates(index).mapNotNull { id ->
                    val url = openAiModelUrl(id)
                    val document = http.get(url) ?: return@mapNotNull null
                    OpenAiImageMetadataParser.model(document, id, url)
                }.flatMap { published ->
                    published.resolvedIds.filter { alias -> if (advertised == null) alias == published.id else alias in advertised }
                        .map { alias -> published.copy(id = alias) }
                }
            }
            ImageProviderKind.GEMINI -> {
                val guide = http.get(GeminiImageMetadataParser.GUIDE_URL)
                    ?: throw IllegalStateException("The published Gemini image model catalog could not be read")
                val schema = http.get(GeminiImageMetadataParser.SCHEMA_URL)
                val available = nativeGeminiModels(endpoint)
                GeminiImageMetadataParser.models(guide, schema).flatMap { published ->
                    available.filter { native -> native.imageText("baseModelId") == published.id ||
                        native.imageText("name")?.removePrefix("models/") == published.id }.map { native ->
                        val id = native.imageText("name")!!.removePrefix("models/")
                        published.copy(id = id, resolvedIds = setOf(id, published.id))
                    }
                }
            }
            ImageProviderKind.COMPATIBLE -> {
                val url = base + "models"
                val body = http.get(url, endpoint) ?: throw IllegalStateException("The provider's model list could not be read")
                ImageMetadataParser.catalog(body, url).map { it.copy(knownImageOutput = false) }
            }
        }
        if (models.isEmpty()) throw IllegalStateException("The provider published no available image models")
        cache[key(endpoint)] = Cached(System.currentTimeMillis(), models)
        return models
    }

    fun model(endpoint: ApiEndpointObject, id: String, fresh: Boolean = false): ImageModelMetadata? {
        val kind = ImageProviderKind.forEndpoint(endpoint)
        if (kind == ImageProviderKind.OPENAI && fresh) {
            val url = openAiModelUrl(id)
            var source = url
            val document = http.get(url) ?: cachedModel(endpoint, id)?.sourceUrl?.substringBefore(" | ")?.let { fallback ->
                source = fallback
                http.get(fallback)
            }
            val metadata = document?.let { OpenAiImageMetadataParser.model(it, id, source) } ?: return null
            return http.get(OpenAiImageReferenceParser.URL)?.let { OpenAiImageReferenceParser.enrich(metadata, it) } ?: metadata
        }
        val model = if (fresh) models(endpoint, fresh = true).firstOrNull { it.id == id }
            else cachedModel(endpoint, id) ?: models(endpoint).firstOrNull { it.id == id }
        if (model == null) return null
        if (kind == ImageProviderKind.OPENROUTER || kind == ImageProviderKind.NANOGPT) {
            val url = ImageApiRoutes.modelEndpoints(endpoint, id)
            return http.get(url, endpoint)?.let { ImageMetadataParser.endpoints(it, model).copy(sourceUrl = url) }
                ?: model.copy(parameters = emptyList(), tariffs = emptyList())
        }
        if (kind == ImageProviderKind.GEMINI) return http.get(GeminiImagePricingParser.URL)
            ?.let { GeminiImagePricingParser.enrich(model, it) } ?: model.copy(tariffsComplete = false)
        if (kind == ImageProviderKind.OPENAI) return http.get(OpenAiImageReferenceParser.URL)
            ?.let { OpenAiImageReferenceParser.enrich(model, it) } ?: model
        return model
    }

    private fun nativeGeminiModels(endpoint: ApiEndpointObject): List<com.google.gson.JsonObject> {
        val models = mutableListOf<com.google.gson.JsonObject>()
        var page: String? = null
        val visited = mutableSetOf<String?>()
        do {
            if (!visited.add(page)) break
            val url = (ImageApiRoutes.base(endpoint) + "models").toHttpUrl().newBuilder()
                .apply { page?.let { addQueryParameter("pageToken", it) } }.build().toString()
            val root = http.get(url, endpoint)?.let(::imageJson)
                ?: throw IllegalStateException("The native Gemini model list could not be read")
            models += root.imageArray("models")?.mapNotNull { it.imageObject() }
                ?.filter { "generateContent" in it.imageStrings("supportedGenerationMethods") }.orEmpty()
            page = root.imageText("nextPageToken")
        } while (page != null)
        return models
    }

    private fun openAiModelUrl(id: String) = "https://developers.openai.com/api/docs/models/" +
        java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20") + ".md"
}

/** Extracts Google's own model-selection list and per-model size tables, never model-name heuristics. */
object GeminiImageMetadataParser {
    const val GUIDE_URL = "https://ai.google.dev/gemini-api/docs/image-generation"
    const val SCHEMA_URL = "https://generativelanguage.googleapis.com/\$discovery/rest?version=v1beta"
    private fun text(html: String): String = html.replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").trim().replace(Regex("\\s+"), " ")

    fun models(guide: String, schema: String?): List<ImageModelMetadata> {
        val headings = Regex("<h[23][^>]*>(.*?)</h[23]>", RegexOption.DOT_MATCHES_ALL).findAll(guide).toList()
        val start = headings.firstOrNull { text(it.groupValues[1]).equals("Model selection", true) } ?: return emptyList()
        val end = headings.firstOrNull { it.range.first > start.range.last }?.range?.first ?: guide.length
        val selection = guide.substring(start.range.last + 1, end)
        val items = Regex("<li[^>]*>(.*?)</li>", RegexOption.DOT_MATCHES_ALL).findAll(selection).toList()
        val schemaProperties = schema?.let(::imageJson)?.get("schemas").imageObject()
            ?.get("ImageConfig").imageObject()?.get("properties").imageObject()
        fun schemaValues(key: String) = schemaProperties?.get(key).imageObject()?.let { field ->
            field.imageStrings("enum").ifEmpty { Regex("`([^`]+)`").findAll(field.imageText("description").orEmpty())
                .map { it.groupValues[1] }.distinct().toList() }
        }.orEmpty()
        val tables = Regex("<table[^>]*>.*?</table>", RegexOption.DOT_MATCHES_ALL).findAll(guide).toList()
            .filter { text(it.value).contains("Aspect ratio") }
        val guideUrl = GUIDE_URL.toHttpUrl()
        return items.flatMap { item ->
            val links = Regex("<a[^>]*href=[\"']([^\"']+)[\"'][^>]*>").findAll(item.value).mapNotNull { anchor ->
                val url = guideUrl.resolve(anchor.groupValues[1].replace("&amp;", "&")) ?: return@mapNotNull null
                url.pathSegments.takeIf { url.host == guideUrl.host && url.scheme == guideUrl.scheme && url.port == guideUrl.port &&
                    it.size == 4 && it.take(3) == listOf("gemini-api", "docs", "models") }?.last()
            }.toList()
            val ids = (links + Regex("<code[^>]*>([^<]+)</code>").findAll(item.value)
                .map { text(it.groupValues[1]) }.toList()).distinct()
            val labels = Regex("<a[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(item.value)
                .map { text(it.groupValues[1]).removePrefix("Gemini ") }.toList() + text(item.value).substringBefore('(').trim()
            val selectedTable = tables.firstOrNull { table ->
                val preceding = guide.substring(0, table.range.first)
                val lastHeading = Regex("<h[3-5][^>]*>(.*?)</h[3-5]>", RegexOption.DOT_MATCHES_ALL)
                    .findAll(preceding).lastOrNull()?.groupValues?.get(1)?.let(::text)?.removePrefix("Gemini ")
                lastHeading != null && lastHeading in labels
            }
            val ratioSets = (selectedTable?.let { listOf(it) } ?: tables).map { table ->
                Regex("<tr[^>]*>(.*?)</tr>", RegexOption.DOT_MATCHES_ALL).findAll(table.value).mapNotNull { row ->
                    Regex("<td[^>]*>(.*?)</td>", RegexOption.DOT_MATCHES_ALL).find(row.groupValues[1])
                        ?.groupValues?.get(1)?.let(::text)?.takeIf { it.matches(Regex("[0-9]+:[0-9]+")) }
                }.toSet()
            }
            val ratios = schemaValues("aspectRatio").filter { value -> ratioSets.isNotEmpty() && ratioSets.all { value in it } }
            val sizes = selectedTable?.let { table ->
                val headers = Regex("<th[^>]*>(.*?)</th>", RegexOption.DOT_MATCHES_ALL).findAll(table.value).map { text(it.groupValues[1]) }.toList()
                schemaValues("imageSize").filter { size -> headers.any { it.startsWith("$size resolution") || it.startsWith("${size}px resolution") } }
            }.orEmpty()
            ids.map { id -> ImageModelMetadata(id, buildList {
                if (sizes.isNotEmpty()) add(ImageParameter("resolution", ImageParameterType.ENUM, sizes))
                if (ratios.isNotEmpty()) add(ImageParameter("aspect_ratio", ImageParameterType.ENUM, ratios))
            }, sourceUrl = GUIDE_URL + " | " + SCHEMA_URL, nativeSizes = schemaValues("imageSize")) }
        }.distinctBy { it.id }
    }
}
