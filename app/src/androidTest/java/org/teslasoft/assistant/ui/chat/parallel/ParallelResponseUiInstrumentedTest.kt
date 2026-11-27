package org.teslasoft.assistant.ui.chat.parallel

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.teslasoft.assistant.R
import org.teslasoft.assistant.conversation.parallel.*
import org.teslasoft.assistant.conversation.responsegroups.*
import org.teslasoft.assistant.preferences.MessageCompletionState
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ParallelResponseUiInstrumentedTest {
    private fun id() = UUID.randomUUID().toString()
    private fun row(): HashMap<String, Any> {
        val current = ParallelChatSnapshot.text("user", "fixture user message")
        val group = ParallelInput.freeze(id(), id(), "full_history", emptyList(), listOf(current), (0..3).map { index ->
            ParallelSlotInput(ResponseModelAssignment("fixture-$index", "Long recognizable model $index", "endpoint-$index", "https://fixture.invalid/v1",
                "chat_completions", emptyMap(), emptyMap(), emptyMap(), emptyMap()), listOf(current))
        }, 0, 100L)
        return ResponseGroupEdits.install(mapOf("message_id" to group.assistantMessageId, "isBot" to true), group)
    }

    @Test fun tabsBrowseIndependentlyAndStopControlsKeepTheirExactSlotOwner() = onMain {
        val context = themed()
        val root = LayoutInflater.from(context).inflate(R.layout.view_parallel_response_controls, null)
        val row = row()
        val group = (ResponseGroupCodec.read(row) as ResponseGroupRead.Valid).group
        var chosen: Pair<String, String>? = null
        val stops = ArrayList<String?>()
        assertTrue(ParallelResponseTabs.bind(root, row, true, { slot, version -> chosen = slot to version }, { stops += it }))
        val tabs = root.findViewById<LinearLayout>(R.id.parallel_response_tabs)
        assertEquals(4, tabs.childCount)
        assertTrue(tabs.getChildAt(0).isSelected)
        assertTrue((tabs.getChildAt(0) as TextView).compoundDrawablesRelative[0] != null)
        tabs.getChildAt(2).performClick()
        assertEquals(group.slots[2].slotId to group.slots[2].foregroundVersionId, chosen)
        assertEquals(group.primarySlotId, (ResponseGroupCodec.read(row) as ResponseGroupRead.Valid).group.primarySlotId)
        root.findViewById<View>(R.id.parallel_stop_slot).performClick()
        root.findViewById<View>(R.id.parallel_stop_all).performClick()
        assertEquals(listOf(group.primarySlotId, null), stops)
    }

    @Test fun failureAndPendingStatesRemainIndependentAfterRebindAndThemeChange() = onMain {
        val row = row()
        val original = (ResponseGroupCodec.read(row) as ResponseGroupRead.Valid).group
        val failedSlot = original.slots[1].let { slot -> slot.copy(versions = slot.versions.map { version ->
            version.copy(fields = version.fields + ("state" to MessageCompletionState.FAILED),
                attempts = version.attempts.map { it.copy(state = MessageCompletionState.FAILED, endedAtMs = 150L) })
        }) }
        val changed = ResponseGroupEdits.install(row, original.copy(slots = original.slots.map { if (it.slotId == failedSlot.slotId) failedSlot else it }))
        for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
            val root = LayoutInflater.from(themed(night)).inflate(R.layout.view_parallel_response_controls, null)
            ParallelResponseTabs.bind(root, changed, true, { _, _ -> }, {})
            val tabs = root.findViewById<LinearLayout>(R.id.parallel_response_tabs)
            assertTrue((tabs.getChildAt(1) as TextView).text.contains("Failed"))
            assertTrue((tabs.getChildAt(0) as TextView).text.contains("Generating"))
            assertTrue(tabs.getChildAt(1).contentDescription.toString().contains("endpoint-1"))
            root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            assertTrue(root.measuredHeight > 0)
            ParallelResponseTabs.bind(root, mapOf("isBot" to true, "message" to "ordinary"), true, { _, _ -> }, {})
            assertEquals(View.GONE, root.visibility)
        }
    }

    @Test fun unreadableAlternativesAndSaveFailuresStayVisibleWithoutChangingStoredData() = onMain {
        val root = LayoutInflater.from(themed()).inflate(R.layout.view_parallel_response_controls, null)
        val unsupported = mapOf<String, Any>("isBot" to true, "message" to "preserved canonical", "responseGroup" to "{\"schemaVersion\":2}")
        assertTrue(ParallelResponseTabs.bind(root, unsupported, true, { _, _ -> fail("No unknown slot may be selected") }, {}))
        assertTrue(root.findViewById<TextView>(R.id.parallel_response_status).text.contains("cannot open"))
        assertEquals(0, root.findViewById<LinearLayout>(R.id.parallel_response_tabs).childCount)
        assertEquals("preserved canonical", unsupported["message"])
        var retries = 0
        assertTrue(ParallelResponseTabs.bind(root, row(), true, { _, _ -> }, {}, true, { retries++ }))
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.parallel_retry_save).visibility)
        assertTrue(root.findViewById<TextView>(R.id.parallel_response_status).text.contains("could not be saved"))
        root.findViewById<View>(R.id.parallel_retry_save).performClick()
        assertEquals(1, retries)
    }

    @Test fun generatingComposerShowsAnUnpressablePaperAirplane() = onMain {
        val send = ImageButton(themed())
        val progress = View(themed())
        ParallelChatController.bindBusy(send, progress, true)
        assertFalse(send.isEnabled)
        assertEquals(View.VISIBLE, send.visibility)
        assertEquals(View.GONE, progress.visibility)
        assertNotNull(send.drawable)
        assertNotNull(send.colorFilter)
    }

    @Test fun summarizerTitleSwitchPersistsAboveUsageAlertInChatBehaviors() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = org.teslasoft.assistant.preferences.Preferences.getPreferences(context, "")
        val previous = prefs.getUseSummarizerForTitles()
        val newChats = prefs.getSummarizerOnForNewChats()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var activity: android.app.Activity? = null
        try {
            prefs.setUseSummarizerForTitles(false)
            repeat(2) { visit ->
                val opened = instrumentation.startActivitySync(android.content.Intent(context,
                    org.teslasoft.assistant.ui.activities.ChatBehaviorsActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as org.teslasoft.assistant.ui.activities.ChatBehaviorsActivity
                activity = opened
                onMain {
                    val title = opened.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_summarizer_titles)
                    val usage = opened.findViewById<View>(R.id.switch_chat_usage_alert)
                    assertEquals(visit == 1, title.isChecked)
                    val titleRow = title.parent as android.view.ViewGroup
                    val usageRow = usage.parent as View
                    assertSame(titleRow.parent, usageRow.parent)
                    val parent = titleRow.parent as android.view.ViewGroup
                    assertEquals(parent.indexOfChild(titleRow) + 1, parent.indexOfChild(usageRow))
                    val textColumn = titleRow.getChildAt(0) as android.view.ViewGroup
                    assertEquals("Use Summarizer to Title Chats", (textColumn.getChildAt(0) as TextView).text.toString())
                    if (visit == 0) {
                        title.performClick()
                        assertTrue(prefs.getUseSummarizerForTitles())
                    }
                    assertEquals(newChats, prefs.getSummarizerOnForNewChats())
                    opened.finish()
                }
                activity = null
            }
            val oldScreen = instrumentation.startActivitySync(android.content.Intent(context,
                org.teslasoft.assistant.ui.activities.SummarizerSettingsActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as org.teslasoft.assistant.ui.activities.SummarizerSettingsActivity
            activity = oldScreen
            onMain {
                assertNull(oldScreen.findViewById<View>(R.id.switch_summarizer_titles))
                val newChat = oldScreen.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_summarizer_new_chats)
                assertEquals(newChats, newChat.isChecked)
                oldScreen.finish()
            }
            activity = null
        } finally {
            onMain { activity?.finish() }
            prefs.setUseSummarizerForTitles(previous)
        }
    }

    private fun themed(night: Int = Configuration.UI_MODE_NIGHT_NO): Context {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(context.resources.configuration).apply { uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night }
        return ContextThemeWrapper(context.createConfigurationContext(config), R.style.Theme_App).apply {
            theme.applyStyle(R.style.ThemeOverlay_Phosphor_Violet, true)
        }
    }
    private fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
}
