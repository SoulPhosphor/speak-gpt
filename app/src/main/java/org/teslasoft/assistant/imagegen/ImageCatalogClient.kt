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
        val kind = ImageProviderKind.forEndpoint(endpoint)
        val models = when (kind) {
            ImageProviderKind.OPENROUTER, ImageProviderKind.NANOGPT -> {
                val url = base + "images/models"
                val body = http.get(url, endpoint) ?: throw IllegalStateException("The provider's image model list could not be read")
                ImageMetadataParser.catalog(body, url, compressionRule(kind))
            }
            ImageProviderKind.OPENAI -> {
                val indexUrl = OpenAiImageMetadataParser.INDEX_URL
                val index = http.get(indexUrl) ?: throw IllegalStateException("The published image model catalog could not be read")
                val advertised = http.get(base + "models", endpoint)?.let { imageJson(it)?.imageArray("data") }
                    ?.mapNotNull { it.imageObject()?.imageText("id") }?.toSet()
                OpenAiImageMetadataParser.candidates(index).mapNotNull { id ->
                    val url = OpenAiImageMetadataParser.modelUrl(id)
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
                val interactionsSchema = http.get(GeminiImageMetadataParser.INTERACTIONS_SCHEMA_URL)
                // Interactions models need not appear in the generateContent model list.
                val available = runCatching { nativeGeminiModels(endpoint) }.getOrDefault(emptyList())
                GeminiImageMetadataParser.models(guide, schema, interactionsSchema).flatMap { published ->
                    GeminiImageMetadataParser.withNativeIds(published, available)
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
            val metadata = OpenAiImageMetadataParser.resolve(id, read = { http.get(it) },
                canonicalHint = cachedModel(endpoint, id)?.sourceUrl?.substringBefore(" | ")) ?: return null
            return openAiDetails(metadata)
        }
        val model = if (fresh) models(endpoint, fresh = true).firstOrNull { it.id == id }
            else cachedModel(endpoint, id) ?: models(endpoint).firstOrNull { it.id == id }
        if (model == null) return null
        if (kind == ImageProviderKind.OPENROUTER || kind == ImageProviderKind.NANOGPT) {
            val url = ImageApiRoutes.modelEndpoints(endpoint, id)
            val compression = compressionRule(kind)
            return http.get(url, endpoint)?.let { ImageMetadataParser.endpoints(it, model, compression).copy(
                sourceUrl = url + if (compression != null) " | " + OpenRouterImageConfigurationParser.URL else "") }
                ?: model.withoutEndpointEvidence()
        }
        if (kind == ImageProviderKind.GEMINI) return http.get(GeminiImagePricingParser.URL)
            ?.let { GeminiImagePricingParser.enrich(model, it) } ?: model.copy(tariffsComplete = false)
        if (kind == ImageProviderKind.OPENAI) return openAiDetails(model)
        return model
    }

    private fun openAiDetails(model: ImageModelMetadata): ImageModelMetadata {
        val settings = http.get(OpenAiImageReferenceParser.URL)?.let { OpenAiImageReferenceParser.enrich(model, it) }
            ?: model.copy(settingsVerified = false)
        return http.get(OpenAiImageCachePolicyParser.URL)?.let { OpenAiImageCachePolicyParser.enrich(settings, it) } ?: settings
    }

    private fun compressionRule(kind: ImageProviderKind): ImageParameter? = if (kind == ImageProviderKind.OPENROUTER)
        http.get(OpenRouterImageConfigurationParser.URL)?.let(OpenRouterImageConfigurationParser::compression) else null

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
                .orEmpty()
            page = root.imageText("nextPageToken")
        } while (page != null)
        return models
    }

}

/** Extracts Google's own model-selection list and per-model size tables, never model-name heuristics. */
object GeminiImageMetadataParser {
    const val GUIDE_URL = "https://ai.google.dev/gemini-api/docs/image-generation"
    const val SCHEMA_URL = "https://generativelanguage.googleapis.com/\$discovery/rest?version=v1beta"
    const val INTERACTIONS_SCHEMA_URL = "https://ai.google.dev/static/api/interactions.openapi.json"
    private fun text(html: String): String = html.replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").trim().replace(Regex("\\s+"), " ")

    fun withNativeIds(published: ImageModelMetadata, available: List<com.google.gson.JsonObject>): List<ImageModelMetadata> {
        if (published.geminiTransport == null) return emptyList()
        val matching = available.filter { native ->
            (native.imageText("baseModelId") == published.id || native.imageText("name")?.removePrefix("models/") == published.id) &&
                (published.geminiTransport == GeminiImageTransport.INTERACTIONS || "generateContent" in native.imageStrings("supportedGenerationMethods"))
        }.mapNotNull { native -> native.imageText("name")?.removePrefix("models/")?.let { id ->
            published.copy(id = id, resolvedIds = setOf(id, published.id))
        } }
        // The image guide itself publishes Interactions support for these exact IDs.
        return matching.ifEmpty { if (published.geminiTransport == GeminiImageTransport.INTERACTIONS) listOf(published) else emptyList() }
    }

    fun models(guide: String, schema: String?, interactionsSchema: String? = null): List<ImageModelMetadata> {
        val headings = Regex("<h[23][^>]*>(.*?)</h[23]>", RegexOption.DOT_MATCHES_ALL).findAll(guide).toList()
        val start = headings.firstOrNull { text(it.groupValues[1]).equals("Model selection", true) } ?: return emptyList()
        val end = headings.firstOrNull { it.range.first > start.range.last }?.range?.first ?: guide.length
        val selection = guide.substring(start.range.last + 1, end)
        val items = Regex("<li[^>]*>(.*?)</li>", RegexOption.DOT_MATCHES_ALL).findAll(selection).toList()
        val legacyProperties = schema?.let(::imageJson)?.get("schemas").imageObject()
            ?.get("ImageConfig").imageObject()?.get("properties").imageObject()
        val interactionProperties = interactionsSchema?.let(::imageJson)?.get("components").imageObject()
            ?.get("schemas").imageObject()?.get("ImageResponseFormat").imageObject()?.get("properties").imageObject()
        fun schemaValues(properties: com.google.gson.JsonObject?, key: String) = properties?.get(key).imageObject()?.let { field ->
            field.imageStrings("enum").ifEmpty { Regex("`([^`]+)`").findAll(field.imageText("description").orEmpty())
                .map { it.groupValues[1] }.distinct().toList() }
        }.orEmpty()
        val tables = Regex("<table[^>]*>.*?</table>", RegexOption.DOT_MATCHES_ALL).findAll(guide).toList()
            .filter { text(it.value).contains("Aspect ratio") }
        val examples = Regex("<pre[^>]*>(.*?)</pre>", RegexOption.DOT_MATCHES_ALL).findAll(guide).map { pre ->
            pre.groupValues[1].replace(Regex("<[^>]+>"), "").replace("&quot;", "\"").replace("&#34;", "\"").replace("&amp;", "&")
        }.toList()
        fun transport(example: String): GeminiImageTransport? = when {
            example.contains("https://generativelanguage.googleapis.com/v1beta/interactions") -> GeminiImageTransport.INTERACTIONS
            example.contains("https://generativelanguage.googleapis.com/v1beta/models/") &&
                example.contains(":generateContent") -> GeminiImageTransport.GENERATE_CONTENT
            else -> null
        }
        val guideTransport = examples.mapNotNull(::transport).distinct().singleOrNull()
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
            ids.map { id ->
                val protocol = examples.filter { example ->
                    Regex("[\"']model[\"']\\s*:\\s*[\"']" + Regex.escape(id) + "[\"']").containsMatchIn(example) ||
                        example.contains("/models/$id:generateContent")
                }.mapNotNull(::transport).distinct().singleOrNull() ?: guideTransport
                val interactions = protocol == GeminiImageTransport.INTERACTIONS
                val properties = if (interactions) interactionProperties else legacyProperties
                val nativeSizes = schemaValues(properties, if (interactions) "image_size" else "imageSize")
                val ratios = schemaValues(properties, if (interactions) "aspect_ratio" else "aspectRatio")
                    .filter { value -> ratioSets.isNotEmpty() && ratioSets.all { value in it } }
                val sizes = selectedTable?.let { table ->
                    val headers = Regex("<th[^>]*>(.*?)</th>", RegexOption.DOT_MATCHES_ALL).findAll(table.value).map { text(it.groupValues[1]) }.toList()
                    nativeSizes.filter { size -> headers.any { it.startsWith("$size resolution") || it.startsWith("${size}px resolution") } }
                }.orEmpty()
                ImageModelMetadata(id, buildList {
                    if (sizes.isNotEmpty()) add(ImageParameter("resolution", ImageParameterType.ENUM, sizes))
                    if (ratios.isNotEmpty()) add(ImageParameter("aspect_ratio", ImageParameterType.ENUM, ratios))
                    if (interactions) {
                        val formats = schemaValues(properties, "mime_type")
                        if (formats.isNotEmpty()) add(ImageParameter("output_format", ImageParameterType.ENUM, formats))
                    }
                }, sourceUrl = GUIDE_URL + " | " + if (interactions) INTERACTIONS_SCHEMA_URL else SCHEMA_URL,
                    nativeSizes = nativeSizes, geminiTransport = protocol, settingsVerified = properties != null)
            }
        }.distinctBy { it.id }
    }
}
