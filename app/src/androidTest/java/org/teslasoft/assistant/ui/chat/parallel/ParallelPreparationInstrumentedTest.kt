package org.teslasoft.assistant.ui.chat.parallel

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.teslasoft.assistant.conversation.parallel.*
import org.teslasoft.assistant.conversation.responsegroups.*
import org.teslasoft.assistant.conversation.setup.*
import org.teslasoft.assistant.preferences.*
import org.teslasoft.assistant.preferences.dto.*
import org.teslasoft.assistant.usage.ProviderUsageAttempt
import org.teslasoft.assistant.util.*
import java.util.UUID

/** Exercises the actual model-specific preparation. No provider requests or catalog lookups. */
@RunWith(AndroidJUnit4::class)
class ParallelPreparationInstrumentedTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val chatId = UUID.randomUUID().toString()
    private val endpointIds = ArrayList<String>()

    @After fun cleanup() {
        val store = ApiEndpointPreferences.getApiEndpointPreferences(context)
        endpointIds.forEach { store.deleteApiEndpoint(context, it) }
        SecurePrefs.get(context, "settings.$chatId").edit().clear().commit()
    }

    @Test fun commonPromptLayersFreezeOnceWithIndependentRoutingSamplingAndOriginalRegenerationInput() = runBlocking {
        Preferences.getPreferences(context, chatId).setChatApplyModelRules(false)
        val models = (0 until 4).map { index ->
            val endpoint = ApiEndpointObject("Fixture $index", "https://fixture-$index.invalid/v1", "synthetic-not-a-credential",
                id = "stage4-${UUID.randomUUID()}", identity = ApiEndpointObject.IDENTITY_OPENROUTER)
            ApiEndpointPreferences.getApiEndpointPreferences(context).setApiEndpoint(context, endpoint)
            endpointIds += endpoint.id
            val settings = FavoriteModelObject("same-name", endpoint.id, routingType = "only", selectedProvider = "fixture-provider-$index",
                streaming = index % 2 == 0, temperature = 0.2f + index / 10f, showReasoning = index % 2 == 0)
            SetupModel(UUID.randomUUID().toString(), endpoint.id, settings.modelId, endpoint.host, endpoint.chatEndpoint,
                endpoint.authType, endpoint.identity, Gson().toJson(settings), endpoint.label)
        }
        val setup = ExperimentalSetup(ExperimentalSetup.Mode.PARALLEL, false, models, models[2].id, false)
        val base = ParallelTurnBase(FrozenChatPayload("same-name", listOf(
            FrozenPayloadMessage("system", "persona and system instructions"),
            FrozenPayloadMessage("system", "summary"),
            FrozenPayloadMessage("user", "earlier user"),
            FrozenPayloadMessage("assistant", "only the canonical earlier answer"),
            FrozenPayloadMessage("system", "manual and automatic memories"),
            FrozenPayloadMessage("system", "lorebook"),
            FrozenPayloadMessage("user", "current message")
        ), 0, null, null, null, null, 41, null), emptyList(), emptyList(), 1, emptyMap()) {}
        val prepared = ParallelTurnPreparation.prepare(context, chatId, setup, base) { fail("No capability warning expected"); false }
        assertEquals(4, prepared.slots.size)
        val group = ParallelInput.freeze(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "full_history",
            prepared.context, prepared.current, prepared.slots, 2, 100L)
        group.slots.forEachIndexed { index, slot ->
            val requestId = slot.versions.single().attempts.single().requestId
            val request = ParallelRequest(ResponseGroupTarget(chatId, group.assistantMessageId, group.groupId), slot.slotId,
                slot.canonicalVersionId, requestId, slot.assignment, slot.promptPartIds.map { id -> group.input.parts.single { it.partId == id } },
                emptyMap(), ProviderUsageAttempt(slot.assignment.modelId, "fixture", slot.assignment.endpoint, requestId))
            val body = JsonParser.parseString(ParallelChatSnapshot.body(request)).asJsonObject
            assertEquals("fixture-provider-$index", body.getAsJsonObject("provider").getAsJsonArray("only").single().asString)
            assertEquals(index % 2 == 0, body.get("stream").asBoolean)
            assertEquals(41, body.get("seed").asInt)
            assertFalse(body.has("tools"))
            assertEquals(base.payload.messages.map { it.content }, body.getAsJsonArray("messages").map { it.asJsonObject.get("content").asString })
            assertFalse(body.toString().contains("synthetic-not-a-credential"))
            assertEquals(slot.assignment, group.slots[index].assignment)
        }
        assertEquals(group.slots[2].slotId, group.primarySlotId)
    }
}
