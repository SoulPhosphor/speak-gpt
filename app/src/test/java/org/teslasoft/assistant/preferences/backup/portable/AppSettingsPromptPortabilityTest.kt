/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.preferences.backup.portable

import android.app.Application
import android.content.Context
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Prompt-portability contract for the replace-only Settings and Preferences
 * category (multi-prompt-backup-restore-plan.md §5): the real production
 * Summarizer and Memory Assistant prompt keys survive capture, encoding,
 * decoding, replacement and rollback exactly, while summarizer runtime state
 * stays out. Synthetic values only.
 *
 * Any future prompt collection stored in global settings must add its
 * production keys to [PROMPT_KEYS] in the same change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class AppSettingsPromptPortabilityTest {

    private companion object {
        val PROMPT_KEYS: List<String> = listOf("summarizer_selected_slot") +
            (0..4).map { "summarizer_slot_name_$it" } +
            (0..4).map { "summarizer_slot_prompt_$it" } +
            listOf(
                "summarizer_slot_recency",
                "image_summary_prompt",
                "archivist_custom_prompt",
                "archivist_lorebook_prompt"
            )

        /** Operational summarizer state that must never ride in a backup. */
        val RUNTIME_KEYS = listOf(
            "summarizer_summary_chat-1",
            "summarizer_folded_chat-1",
            "summarizer_episode_chat-1",
            "summarizer_errors",
            "summarizer_errors_unseen",
            "summarizer_catch_up_pending",
            "summarizer_projection_version",
            "pending_summarizer_work"
        )

        val LONG_PROMPT = buildString {
            repeat(4000) { append("Line ").append(it).append(": keep every word ✦ 要約.\n") }
        }
    }

    /** User-edited values for every production prompt key. */
    private fun edited(): Map<String, String> = linkedMapOf(
        "summarizer_selected_slot" to "3",
        "summarizer_slot_name_0" to "Brief",
        "summarizer_slot_name_1" to "Détaillé ✦",
        "summarizer_slot_name_2" to "",
        "summarizer_slot_name_3" to "Story beats 📖",
        "summarizer_slot_name_4" to "Long",
        "summarizer_slot_prompt_0" to "Summarize briefly.",
        "summarizer_slot_prompt_1" to "Résumé détaillé:\n- faits\n- émotions",
        "summarizer_slot_prompt_2" to "",
        "summarizer_slot_prompt_3" to "Keep the story beats.\r\nTabs\tstay.",
        "summarizer_slot_prompt_4" to LONG_PROMPT,
        "summarizer_slot_recency" to "3,0,4,1,2",
        "image_summary_prompt" to "Describe the image 🖼️ in two lines.\nSecond line.",
        "archivist_custom_prompt" to "Associative analysis:\n\"quoted\" \\ backslash",
        // Intentionally empty: use the shipped Lorebook prompt.
        "archivist_lorebook_prompt" to ""
    )

    /** Different prior values, so rollback proves it restores them exactly. */
    private fun prior(): Map<String, String> = PROMPT_KEYS.associateWith { "prior value for $it" }

    private fun settings(context: Context) =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun seed(context: Context, values: Map<String, String>, runtime: Boolean) {
        val editor = settings(context).edit().clear()
        values.forEach { (key, value) -> editor.putString(key, value) }
        if (runtime) RUNTIME_KEYS.forEach { editor.putString(it, "runtime state") }
        assertTrue(editor.commit())
    }

    private fun promptValues(context: Context): Map<String, String?> =
        PROMPT_KEYS.associateWith { settings(context).getString(it, null) }

    @Test
    fun everyProductionPromptKeyIsPortableAndRuntimeStateIsNot() {
        for (key in PROMPT_KEYS) {
            assertTrue(
                key,
                AppSettingsPortabilityPolicy.isPortable(AppSettingsPortabilityPolicy.Store.GLOBAL_SETTINGS, key)
            )
        }
        for (key in RUNTIME_KEYS) {
            assertFalse(
                key,
                AppSettingsPortabilityPolicy.isPortable(AppSettingsPortabilityPolicy.Store.GLOBAL_SETTINGS, key)
            )
        }
    }

    @Test
    fun promptKeysSurviveCaptureEncodeDecodeReplaceAndRollbackExactly() {
        val context = RuntimeEnvironment.getApplication()

        // Capture the user's edited prompts from the live settings store.
        seed(context, edited(), runtime = true)
        val captured = AppSettingsPortableStore.capture(context).getOrThrow()
        for ((key, value) in edited()) {
            assertEquals(key, PortableSettingValue("string", value), captured.globalSettings[key])
        }
        for (key in RUNTIME_KEYS) assertFalse(key, captured.globalSettings.containsKey(key))

        // Encode / decode as the package artifact does.
        val encoded = AppSettingsPortableCodec.encode(captured)
        assertTrue(encoded.toByteArray(Charsets.UTF_8).size < PortableRecoveryLimits.APP_SETTINGS_BYTES)
        val decoded = (AppSettingsPortableCodec.parse(encoded) as AppSettingsPortableCodec.Result.Ok).data
        assertEquals(captured.globalSettings, decoded.globalSettings)

        // A device holding different prompts restores the backup by replacement.
        seed(context, prior(), runtime = true)
        val current = AppSettingsPortableStore.capture(context).getOrThrow()
        val root = Files.createTempDirectory("settings-prompt-restore").toFile()
        try {
            val participant = AppSettingsRestoreParticipant(
                context,
                root,
                AppSettingsRestoreParticipant.PreparedPlan(current = current, desired = decoded)
            )
            assertTrue(participant.validate())
            assertTrue(participant.stage())
            assertTrue(participant.apply())
            assertEquals(edited(), promptValues(context))
            // Runtime state is device-local: replacement neither restores nor removes it.
            for (key in RUNTIME_KEYS) assertEquals(key, "runtime state", settings(context).getString(key, null))

            assertTrue(participant.rollback())
            assertEquals(prior(), promptValues(context))
            participant.cleanup()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun untouchedShippedDefaultsAreNotFrozenIntoTheBackup() {
        val context = RuntimeEnvironment.getApplication()
        seed(context, emptyMap(), runtime = false)
        val captured = AppSettingsPortableStore.capture(context).getOrThrow()
        for (key in PROMPT_KEYS) assertFalse(key, captured.globalSettings.containsKey(key))
    }
}
