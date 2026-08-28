package org.teslasoft.assistant.tts.api

import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.preferences.tts.TtsRoutingMode
import org.teslasoft.assistant.preferences.tts.TtsRoutingSettings
import org.teslasoft.assistant.providers.ProviderFilterState
import org.teslasoft.assistant.providers.SortDirection
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

class TtsPickerStateTest {
    private val target = TtsTarget("speech-endpoint", "vendor/speech:exact")
    private fun state(routing: TtsRoutingSettings = TtsRoutingSettings()) =
        TtsProviderPickerState(TtsPickerRequest(target.copy(routing = routing)))
    private fun provider(id: String, name: String = id, amount: String = "1", unit: String = "characters") =
        TtsProvider(id, name, TtsPrice(listOf(TtsCharge("input", amount.toBigDecimal(), "USD", unit)), true),
            null, null, null, null)

    @Test fun canceledEditsCannotMutateCallerOrItsMutableOrderList() {
        val order = mutableListOf("b", "a")
        val original = target.copy(routing = TtsRoutingSettings(TtsRoutingMode.PREFERRED, "b", order, false))
        val picker = TtsProviderPickerState(TtsPickerRequest(original))
        picker.move(1, 0); picker.remove("b"); picker.fallbacks(true); picker.mode(TtsRoutingMode.ONLY)
        picker.select("other")
        assertEquals(listOf("b", "a"), original.routing.providerOrder)
        assertEquals("b", original.routing.selectedProvider)
        assertFalse(original.routing.allowFallbacks)
        assertEquals(TtsRoutingMode.PREFERRED, original.routing.mode)
    }

    @Test fun saveAndReopenPreservesEveryChoiceAndExactSavedIdentity() {
        val request = TtsPickerRequest(target.copy(sourceId = "api-tts:stable-row", routing =
            TtsRoutingSettings(TtsRoutingMode.PREFERRED, "Provider/Only", listOf("Provider/B", "Provider/A"), false)))
        val picker = TtsProviderPickerState(request)
        picker.move(1, 0)
        val result = TtsPickerCodec.decode(TtsPickerCodec.encode(picker.result()))
        assertTrue(request.acceptsProviderResult(result))
        assertEquals(listOf("Provider/A", "Provider/B"), result.routing.providerOrder)
        assertEquals("Provider/Only", result.routing.selectedProvider)
        assertFalse(result.routing.allowFallbacks)
        assertEquals(result, TtsProviderPickerState(TtsPickerRequest(result)).result())
    }

    @Test fun onlyRequiresExplicitSelectionWithoutChangingMode() {
        val picker = state(TtsRoutingSettings(TtsRoutingMode.ONLY))
        val error = assertThrows(TtsException::class.java) { picker.result() }
        assertEquals(TtsFailureKind.PROVIDER_REQUIRED, error.failure.kind)
        assertEquals(TtsRoutingMode.ONLY, picker.routing.mode)
        assertEquals("Select a provider to use Only.", TtsFailures.message(error.failure).explanation)
        picker.select("Exact/Provider")
        assertEquals("Exact/Provider", picker.result().routing.selectedProvider)
    }

    @Test fun emptyPreferredRespectsFallbackChoice() {
        val picker = state(TtsRoutingSettings(TtsRoutingMode.PREFERRED, allowFallbacks = false))
        assertThrows(TtsException::class.java) { picker.result() }
        picker.fallbacks(true)
        assertEquals(TtsRoutingMode.PREFERRED, picker.result().routing.mode)
    }

    @Test fun removingLegacyPreferredSelectionCannotLeaveAnInvisibleRoute() {
        val picker = state(TtsRoutingSettings(TtsRoutingMode.PREFERRED, "a", allowFallbacks = false))
        assertEquals(listOf("a"), picker.routing.providerOrder)
        picker.remove("a")
        assertTrue(picker.routing.selectedProvider.isEmpty())
        assertThrows(TtsException::class.java) { picker.result() }
    }

    @Test fun preferredCheckboxesAppendRemoveAndReorderWithoutDuplicates() {
        val picker = state(TtsRoutingSettings(TtsRoutingMode.PREFERRED))
        picker.select("b"); picker.select("a"); picker.select("c"); picker.move(2, 0)
        assertEquals(listOf("c", "b", "a"), picker.result().routing.providerOrder)
        picker.select("b")
        assertEquals(listOf("c", "a"), picker.result().routing.providerOrder)
        picker.move(0, -1)
        assertEquals(listOf("c", "a"), picker.result().routing.providerOrder)
    }

    @Test fun savedRowAndEndpointCannotBeChangedByReturnedResult() {
        val request = TtsPickerRequest(target.copy(sourceId = "api-tts:one"))
        assertFalse(request.acceptsProviderResult(target.copy(sourceId = "api-tts:two")))
        assertFalse(request.acceptsProviderResult(request.target.copy(endpointId = "chat")))
        assertFalse(request.acceptsProviderResult(request.target.copy(modelId = "another")))
        assertFalse(request.acceptsModelResult(target))
        assertFalse(TtsPickerRequest(target).acceptsModelResult(target.copy(endpointId = "other")))
    }

    @Test fun newPickerAlwaysStartsAlphabeticallyAndFilterRoundtripDoesNotChangeRouting() {
        val picker = state(TtsRoutingSettings(TtsRoutingMode.PREFERRED, providerOrder = listOf("z", "a")))
        val rows = listOf(provider("z"), provider("a"))
        assertEquals(listOf("a", "z"), picker.sort.apply(rows).map { it.id })
        val before = picker.result()
        picker.sort = TtsProviderSort(false, SortDirection.HIGH_TO_LOW, SortDirection.LOW_TO_HIGH, SortDirection.HIGH_TO_LOW)
        picker.sort = TtsPickerCodec.decodeSort(TtsPickerCodec.encodeSort(picker.sort))
        assertEquals(listOf("z", "a"), picker.sort.apply(rows).map { it.id })
        assertEquals(before, picker.result())
        assertEquals(TtsProviderSort(), state().sort)
    }

    @Test fun textAndTtsFiltersDoNotAffectEachOther() {
        ProviderFilterState.reset()
        try {
            ProviderFilterState.alphaAToZ = false
            val picker = state()
            assertTrue(picker.sort.alphaAToZ)
            picker.sort = TtsProviderSort(price = SortDirection.LOW_TO_HIGH)
            assertEquals(SortDirection.NONE, ProviderFilterState.sortInputPrice)
            assertFalse(ProviderFilterState.alphaAToZ)
            ProviderFilterState.reset()
            assertEquals(SortDirection.LOW_TO_HIGH, picker.sort.price)
        } finally { ProviderFilterState.reset() }
    }

    @Test fun priceDisplayOrderingKeepsUnitsAndRoutingSeparate() {
        val picker = state(TtsRoutingSettings(TtsRoutingMode.PREFERRED, providerOrder = listOf("tokens", "cheap")))
        val rows = listOf(provider("tokens", unit = "tokens"), provider("costly", amount = "2"),
            provider("cheap", amount = "1"), provider("free", amount = "0"))
        picker.sort = TtsProviderSort(price = SortDirection.HIGH_TO_LOW)
        assertEquals(listOf("free", "costly", "cheap", "tokens"), picker.sort.apply(rows).map { it.id })
        picker.sort = picker.sort.copy(price = SortDirection.LOW_TO_HIGH)
        assertEquals(listOf("free", "cheap", "costly", "tokens"), picker.sort.apply(rows).map { it.id })
        assertEquals(listOf("tokens", "cheap"), picker.result().routing.providerOrder)
        assertTrue(rows[0].price.display().contains("tokens"))
    }

    @Test fun informationalBooleanMarksAreLiteral() {
        assertEquals(listOf("X", "", "?"), listOf(true, false, null).map(TtsPickerPresentation::mark))
    }

    @Test fun modelSearchUsesOnlySpeechEvidenceAndTheExplicitEndpoint() {
        val resolver = TtsSourceResolver({ Result.success(emptyList()) }, {
            listOf(ApiEndpointObject("A", "https://a.example", "", id = "a"),
                ApiEndpointObject("B", "https://b.example", "", id = "b"))
        })
        val http = object : TtsHttpExecutor {
            override fun execute(endpoint: TtsEndpoint, target: TtsTarget, operation: TtsOperation,
                request: okhttp3.Request, token: TtsRequestToken): TtsHttpResponse {
                assertEquals(endpoint.id + ".example", request.url.host)
                return TtsHttpResponse(200, """{"data":[
                    {"id":"${endpoint.id}/talk:exact","name":"Speech Name","task":"tts"},
                    {"id":"${endpoint.id}/chat","architecture":{"output_modalities":["text"]}},
                    {"id":"${endpoint.id}/listen","task":"transcription"}]}""".toByteArray(), "application/json")
            }
        }
        for (id in listOf("a", "b")) {
            val catalog = TtsDiscoveryClient(http).models(resolver.resolve(TtsTarget(id)), TtsRequestGate().begin())
            assertEquals(listOf("$id/talk:exact"), TtsPickerPresentation.models(catalog, "speech name").map { it.id })
            assertTrue(TtsPickerPresentation.models(catalog, "no match").isEmpty())
        }
    }

    @Test fun blankIdentityIsNotReplacedByChatDefaults() {
        assertEquals(TtsFailureKind.ENDPOINT_REQUIRED, assertThrows(TtsException::class.java) {
            TtsProviderPickerState(TtsPickerRequest(TtsTarget(""))).result()
        }.failure.kind)
        assertEquals(TtsFailureKind.MODEL_REQUIRED, assertThrows(TtsException::class.java) {
            TtsProviderPickerState(TtsPickerRequest(TtsTarget("endpoint"))).result()
        }.failure.kind)
    }

    @Test fun incompleteDraftAndCaseSensitiveIdsSurviveRecreation() {
        val draft = target.copy(routing = TtsRoutingSettings(TtsRoutingMode.ONLY), sourceId = "api-tts:Case")
        assertEquals(draft, TtsPickerCodec.decode(TtsPickerCodec.encode(draft)))
    }

    @Test fun pickerWiringHasNoChatWritesOrFavoritesLanding() {
        val files = listOf("TtsPickerActivity", "TtsModelPickerActivity", "TtsProviderPickerActivity", "TtsProviderFiltersActivity")
            .map { source("java/org/teslasoft/assistant/ui/activities/$it.kt").readText() }
        for (text in files) {
            assertFalse(text.contains("FavoriteModelsPreferences"))
            assertFalse(text.contains("import org.teslasoft.assistant.preferences.Preferences"))
            assertFalse(text.contains("setModel("))
            assertFalse(text.contains("ProviderFilterState"))
            assertFalse(text.contains("persistDirectly"))
        }
        assertTrue(files[1].contains("R.layout.fragment_model_selector"))
        assertTrue(files[1].contains("R.id.btn_view_all).visibility = View.GONE"))
        assertTrue(files[1].contains("R.id.btn_action"))
        assertFalse(files[2].contains("AdvancedModelSelector"))
        assertFalse(files[2].contains("field_choose_model"))
    }

    @Test fun tableHasExactlySixApprovedDataHeadingsAndNoPrivacyFilters() {
        val provider = source("java/org/teslasoft/assistant/ui/activities/TtsProviderPickerActivity.kt").readText()
        val block = provider.substringAfter("private val columns = listOf(").substringBefore("\n    )")
        val names = Regex("R.string.([a-z_]+) to").findAll(block).map { it.groupValues[1] }.toList()
        val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(source("res/values/strings.xml"))
        val elements = strings.getElementsByTagName("string")
        val labels = (0 until elements.length).associate { i ->
            val e = elements.item(i) as Element; e.getAttribute("name") to e.textContent
        }
        assertEquals(listOf("Provider", "Price", "Latency", "Uptime", "ZDR", "Training/Data Use"), names.map { labels[it] })
        val filters = source("res/layout/activity_tts_provider_filters.xml").readText()
        for (excluded in listOf("zdr", "training", "quantization", "throughput", "tool_support", "caching"))
            assertFalse(filters.contains(excluded))
        assertFalse(provider.contains("buildIgnoreControl"))
    }

    @Test fun managerKeepsOneVerticalFlowAndTheApprovedInlineProviderEntryPoint() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(source("res/layout/activity_api_voice_models.xml"))
        val scrolls = document.getElementsByTagName("ScrollView")
        assertEquals(1, scrolls.length)
        val elements = document.getElementsByTagName("*")
        val byId = (0 until elements.length).map { elements.item(it) as Element }
            .associateBy { it.getAttribute("android:id").substringAfter("/") }
        val form = byId.getValue("tts_add_model")
        val table = byId.getValue("tts_saved_table")
        assertSame(form.parentNode, table.parentNode.parentNode)
        val providerRow = byId.getValue("tts_provider_row")
        assertSame(providerRow, byId.getValue("tts_provider_value").parentNode)
        assertSame(providerRow, byId.getValue("tts_routing_mode").parentNode)
        assertFalse(byId.containsKey("btn_save"))
        assertEquals("@style/Widget.App.Row.Selector.Value", byId.getValue("tts_provider_value").getAttribute("style"))
        val activity = source("java/org/teslasoft/assistant/ui/activities/ApiVoiceModelsActivity.kt").readText()
        assertTrue(activity.contains("model.openProvider()?.let(providerPicker::launch)"))
        assertTrue(activity.contains("model.openProvider(source)?.let(providerPicker::launch)"))
        assertFalse(activity.contains("hasOpenRouterCatalogAuthority"))
        assertFalse(activity.contains("setTtsEngine"))
        assertFalse(activity.contains("FavoriteModelsPreferences"))
        // Widget.Material3.Button supplies appearance, not required LayoutParams. Check
        // the local family chain so a missing width/height cannot pass XML parsing and crash.
        val theme = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(source("res/values/themes.xml"))
        val styleNodes = theme.getElementsByTagName("style")
        val styles = (0 until styleNodes.length).map { styleNodes.item(it) as Element }
            .associateBy { it.getAttribute("name") }
        fun hasDimension(element: Element, key: String): Boolean {
            if (element.hasAttribute(key)) return true
            var style = element.getAttribute("style").removePrefix("@style/")
            while (style.isNotBlank()) {
                val definition = styles[style] ?: return false
                val items = definition.getElementsByTagName("item")
                if ((0 until items.length).any { (items.item(it) as Element).getAttribute("name") == key }) return true
                style = if (definition.hasAttribute("parent")) definition.getAttribute("parent").removePrefix("@style/")
                    else style.substringBeforeLast('.', "")
            }
            return false
        }
        for (i in 0 until elements.length) {
            val element = elements.item(i) as Element
            for (key in listOf("android:layout_width", "android:layout_height"))
                assertTrue("${element.tagName} must resolve $key", hasDimension(element, key))
        }
        val settings = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(source("res/layout/activity_voice_settings.xml"))
        val rows = settings.getElementsByTagName("LinearLayout")
        val ids = (0 until rows.length).map { (rows.item(it) as Element).getAttribute("android:id") }
            .filter { it.isNotBlank() }
        assertEquals(ids.indexOf("@+id/row_api_voice_models") + 1, ids.indexOf("@+id/tile_voice_advanced"))
    }

    @Test fun selectedVoiceIsTheOnlySpeechEngineControlAndPermanentDialogOpensVoiceBrowser() {
        val settings = source("res/layout/activity_voice_settings.xml").readText()
        assertFalse(settings.contains("@+id/tile_tts"))
        val activity = source("java/org/teslasoft/assistant/ui/activities/VoiceSettingsActivity.kt").readText()
        assertFalse(activity.contains("tileTTS"))
        assertFalse(activity.contains("setTtsEngine"))
        val chat = source("java/org/teslasoft/assistant/ui/activities/ChatActivity.kt").readText()
        assertFalse(chat.contains("openAIAI!!.speech"))
        assertTrue(chat.contains("playback.play(selected.sourceId, selected.voiceId"))
        val browser = source("java/org/teslasoft/assistant/ui/activities/VoiceBrowserActivity.kt").readText()
        assertTrue(browser.contains("selections.activate(next)"))
        assertTrue(browser.contains("SavedTtsSourcesPreferences.getPreferences"))
        val dialogs = source("java/org/teslasoft/assistant/tts/api/TtsVoiceDialogs.kt").readText()
        assertTrue(dialogs.contains("VoiceBrowserActivity::class.java"))
        assertFalse(dialogs.contains("ApiVoiceModelsActivity::class.java"))
        assertTrue(dialogs.contains("if (permanent) R.string.btn_ok else R.string.btn_cancel"))
        assertTrue(dialogs.contains("if (permanent) R.string.tts_select_new_voice else R.string.health_btn_retry"))
    }

    private fun source(relative: String): File = listOf(File("src/main/$relative"), File("app/src/main/$relative"))
        .first { it.isFile }
}
