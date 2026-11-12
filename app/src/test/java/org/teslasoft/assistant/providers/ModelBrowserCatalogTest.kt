package org.teslasoft.assistant.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ModelBrowserCatalogTest {
    @Test
    fun zeroPriceMetadataFindsFreeModelsWithoutTrustingTheName() {
        val models = ModelBrowserCatalog.parse("""
            {"data":[
                {"id":"zero","pricing":{"prompt":"0.00","completion":0,"request":0}},
                {"id":"paid:free","pricing":{"prompt":0,"completion":0,"request":"0.01"}},
                {"id":"unknown:free"},
                {"id":"auto-model","owned_by":"nanogpt","pricing":{"prompt":0,"completion":0}},
                {"id":"tiered","pricing":{"prompt":0,"completion":0,"overrides":[{"prompt":"0.01"}]}}
            ]}
        """.trimIndent()).entries
        assertEquals(listOf("zero"), models.filter { it.isFree }.map { it.id })
    }

    @Test
    fun detailedCatalogUsesHostingProvidersAndKeepsModelSearchSeparate() {
        val model = ModelBrowserCatalog.parse("""
            {"data":[{"id":"maker/slug","name":"Readable name","owned_by":"system",
             "providers":["fireworks-ai",{"slug":"openai","name":"OpenAI"}],
             "capabilities":{"reasoning":true}}]}
        """.trimIndent()).entries.single()
        assertEquals(listOf("fireworks-ai", "openai"), model.providers.map { it.id })
        assertTrue(model.providers.first().matches("Fireworks AI"))
        assertTrue(model.matchesModel("readable"))
        assertFalse(model.matchesModel("fireworks"))
        assertTrue(model.advertisesReasoning)
        val direct = ModelBrowserCatalog.parse("""{"data":[{"id":"plain","owned_by":"system"}]}""")
        assertTrue(direct.entries.single().providers.isEmpty())
    }

    @Test
    fun dateWindowExcludesOlderAndFutureModels() {
        val now = Instant.parse("2026-11-07T00:00:00Z")
        val models = ModelBrowserCatalog.parse("""
            {"data":[{"id":"new","created":"2026-11-01T00:00:00Z"},
             {"id":"old","created":${Instant.parse("2026-09-01T00:00:00Z").epochSecond}},
             {"id":"future","created":"2026-12-01T00:00:00Z"},{"id":"undated"}]}
        """.trimIndent()).entries
        assertEquals(listOf("new"), models.filter { it.isNew(now) }.map { it.id })
    }

    @Test
    fun paginationUsesResponseEvidenceAndExcludesNonChatEndpoints() {
        val body = """
            {"data":[{"id":"chat","endpoints":["/api/v1/chat/completions"]},
             {"id":"batch-only","endpoints":["/api/v1/batches"]},
             {"id":"image-only","architecture":{"output_modalities":["image"]}}],
             "pagination":{"current_page":2,"total_pages":4}}
        """.trimIndent()
        val page = ModelBrowserCatalog.parse(body, requestedPage = 2)
        assertEquals(3, page.nextPage)
        assertEquals(listOf("chat"), page.entries.map { it.id })
        assertNull(ModelBrowserCatalog.parse(body, requestedPage = 3).nextPage)
    }
    @Test
    fun rawRuleCatalogKeepsExactCustomIdsThatChatNameFilteringWouldHide() {
        val body = """
            {"data":[{"id":"plain"},{"id":"custom-tts-chat"},{"id":"custom-dall-chat"},
             {"id":"custom-whisper-chat"},{"id":"custom-embedding-chat"}]}
        """.trimIndent()
        assertEquals(listOf("plain"), ModelBrowserCatalog.parse(body).entries.map { it.id })
        assertEquals(
            listOf("plain", "custom-tts-chat", "custom-dall-chat", "custom-whisper-chat", "custom-embedding-chat"),
            ModelBrowserCatalog.parse(body, includeAllModels = true).entries.map { it.id }
        )
    }

    @Test
    fun rawRuleCatalogPreservesPaginationAndMetadataWithoutFilteringModalities() {
        val body = """
            {"data":[{"id":"custom","name":"Custom name","endpoints":["/other"],
             "architecture":{"output_modalities":["image"]},"providers":["host"],
             "capabilities":{"reasoning":true},"created":"2026-11-01T00:00:00Z"},
             {"id":""},{"name":"missing id"},{"id":"custom","endpoints":["/other"]}],
             "pagination":{"current_page":2,"total_pages":3}}
        """.trimIndent()
        val page = ModelBrowserCatalog.parse(body, requestedPage = 2, includeAllModels = true)
        assertEquals(3, page.nextPage)
        val model = page.entries.single()
        assertEquals("custom", model.id)
        assertEquals("Custom name", model.name)
        assertEquals(Instant.parse("2026-11-01T00:00:00Z"), model.created)
        assertEquals(listOf("host"), model.providers.map { it.id })
        assertTrue(model.advertisesReasoning)
        assertTrue(ModelBrowserCatalog.parse(body).entries.isEmpty())
    }
}
