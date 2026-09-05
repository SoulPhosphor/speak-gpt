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

package org.teslasoft.assistant.preferences

import android.content.Context
import android.content.SharedPreferences
import org.teslasoft.assistant.preferences.includes.SummarizerProjectionContract
import org.teslasoft.assistant.util.Hash
import androidx.core.content.edit
import org.teslasoft.assistant.preferences.tts.AppTtsVoicePreferences
import java.util.Locale

class Preferences internal constructor(
    private var preferences: SharedPreferences,
    private var gp: SharedPreferences,
    private var chatId: String,
    private var defaultPreferences: SharedPreferences? = preferences,
    private val ttsVoicePreferences: AppTtsVoicePreferences = AppTtsVoicePreferences(gp)
) {
    companion object {
        fun getPreferences(context: Context, xchatId: String) : Preferences {
            val globalPreferences =
                context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            return Preferences(
                SecurePrefs.get(context, "settings.$xchatId"),
                globalPreferences,
                xchatId,
                if (globalPreferences.contains("always_speak_mode") &&
                    globalPreferences.contains("audio")) {
                    null
                } else {
                    // The legacy default profile is the migration source for
                    // settings that moved from per-chat to the global store
                    // (Always Speak Responses, and the speech-to-text engine).
                    // Keep it available until every such value has migrated.
                    SecurePrefs.get(context, "settings.")
                },
                AppTtsVoicePreferences.getPreferences(context)
            )
        }

        /**
         * Per-log retention bounds for the three user-configurable diagnostic
         * logs (Memory Diagnostics, Whisper Performance, Memory Usage). The
         * owner set these ceilings (July 23 2026): a log keeps entries until it
         * exceeds the entry-count cap OR the age cap, whichever comes first
         * (the existing [Logger.trimByEntries] behaviour). The Error and Voice
         * logs are deliberately NOT configurable and keep their own constants
         * in Logger. Floors are 1 (a value of 0 would erase the log on the next
         * write). Defaults match the diagnostics' pre-split behaviour (the old
         * shared Performance Log's 2000-entry default exceeded the cap).
         * The entry ceiling was lowered to 50 (owner ruling, July 31 2026):
         * a hundreds/thousands-deep diagnostic log is unnecessary, so every
         * configurable log — the three diagnostics plus the Provider Failure
         * Log — keeps at most 50 entries.
         */
        const val LOG_MAX_ENTRIES_LIMIT = 50
        const val LOG_MAX_DAYS_LIMIT = 30
        const val LOG_DEFAULT_MAX_ENTRIES = 50
        const val LOG_DEFAULT_MAX_DAYS = 7
        const val CONDENSED_KIND_SUMMARY = "summary"
        const val CONDENSED_KIND_COMPACTION = "compaction"

        private const val LAST_SUCCESS_ENDPOINT_ID = "last_success_endpoint_id"
        private const val LAST_SUCCESS_MODEL = "last_success_model"
        private const val LAST_SUCCESS_ROUTING = "last_success_routing"
        private const val LAST_SUCCESS_PERSONA_ID = "last_success_persona_id"
        private const val LAST_CHAT_ARCHIVE_ENABLED = "last_chat_archive_enabled"

        /** Clamp a max-entries value to [1, LOG_MAX_ENTRIES_LIMIT]. Pure, unit-tested. */
        fun coerceLogMaxEntries(value: Int): Int = value.coerceIn(1, LOG_MAX_ENTRIES_LIMIT)

        /** Clamp a max-days value to [1, LOG_MAX_DAYS_LIMIT]. Pure, unit-tested. */
        fun coerceLogMaxDays(value: Int): Int = value.coerceIn(1, LOG_MAX_DAYS_LIMIT)

        /** The Image Generation Log's retention clamp
         *  (image-generation-rebuild-plan.md §13): identical ceilings to the
         *  error-log fields, but the floor is ZERO because zero means
         *  unlimited — only for these two fields. Pure, unit-tested. */
        fun coerceImageGenRetention(value: Int, cap: Int): Int = value.coerceIn(0, cap)
    }

    /**
     * Sets the shared preferences file for the given chat ID in the context provided.
     *
     * @param chatId The chat ID for which the settings are to be set.
     * @param context The context in which the shared preferences will be accessed.
     */
    fun setPreferences(chatId: String, context: Context) {
        this.chatId = chatId
        this.preferences = SecurePrefs.get(context, "settings.$chatId")
    }

    /**
     * Retrieves a global String value from the shared preferences.
     *
     * @param param The key of the value to retrieve.
     * @param default The default value to return if the key is not found.
     * @return The value associated with the specified key or the default value if the key is not found.
     */
    private fun getGlobalString(param: String?, default: String?) : String {
        return gp.getString(param, default).toString()
    }

    /**
     * Puts a global String value in the shared preferences.
     *
     * @param param The key with which the value is to be associated.
     * @param value The value to be stored.
     */
    private fun putGlobalString(param: String, value: String, default: String = "") {
        val oldValue = getGlobalString(param, default)

        if (oldValue != value) {
            gp.edit {
                putString(param, value)
            }
        }
    }

    /**
     * Get global boolean
     *
     * @param param The key of the value to retrieve.
     * @param default The default value to return if the key is not found.
     * */
    private fun getGlobalBoolean(param: String?, default: Boolean) : Boolean {
        return gp.getBoolean(param, default)
    }

    /**
     * Set global boolean
     *
     * @param param The key with which the value is to be associated.
     * @param value The value to be stored.
     * */
    private fun putGlobalBoolean(param: String, value: Boolean, default: Boolean = false) {
        val oldValue = getGlobalBoolean(param, default)

        if (oldValue != value) {
            gp.edit {
                putBoolean(param, value)
            }
        }
    }

    /**
     * Get global int
     *
     * @param param The key of the value to retrieve.
     * @param default The default value to return if the key is not found.
     * */
    private fun getGlobalInt(param: String?, default: Int) : Int {
        return gp.getInt(param, default)
    }

    /**
     * Set global int
     *
     * @param param The key with which the value is to be associated.
     * @param value The value to be stored.
     * */
    private fun putGlobalInt(param: String, value: Int, default: Int = 0) {
        val oldValue = getGlobalInt(param, default)

        if (oldValue != value) {
            gp.edit {
                putInt(param, value)
            }
        }
    }

    /**
     * Retrieves a String value from the shared preferences.
     *
     * @param param The key of the value to retrieve.
     * @param default The default value to return if the key is not found.
     * @return The value associated with the specified key or the default value if the key is not found.
     */
    private fun getString(param: String?, default: String?) : String {
        return preferences.getString(param, default).toString()
    }

    /**
     * Puts a String value in the shared preferences.
     *
     * @param param The key with which the value is to be associated.
     * @param value The value to be stored.
     */
    private fun putString(param: String, value: String, default: String = "") {
        val oldValue = getString(param, default)

        if (oldValue != value) {
            preferences.edit { putString(param, value) }
        }
    }

    /**
     * Retrieves a Boolean value from the shared preferences.
     *
     * @param param The key of the value to retrieve.
     * @param default The default value to return if the key is not found.
     * @return The value associated with the specified key or the default value if the key is not found.
     */
    private fun getBoolean(param: String?, default: Boolean) : Boolean {
        return preferences.getBoolean(param, default)
    }

    /**
     * Puts a Boolean value in the shared preferences.
     *
     * @param param The key with which the value is to be associated.
     * @param value The value to be stored.
     */
    private fun putBoolean(param: String, value: Boolean, default: Boolean = false) {
        val oldValue = getBoolean(param, default)

        if (oldValue != value) {
            preferences.edit { putBoolean(param, value) }
        }
    }

    /**
     * Whether to play an audible alert when a response fails (e.g. the model is
     * overloaded or the connection drops), so the user knows a reply isn't coming.
     *
     * @return whether the error sound is enabled
     * */
    fun getErrorSound() : Boolean {
        return getGlobalBoolean("error_sound", true)
    }

    /**
     * Enable or disable the audible alert played when a response fails.
     *
     * @param state whether the error sound is enabled
     * */
    fun setErrorSound(state: Boolean) {
        putGlobalBoolean("error_sound", state, true)
    }

    /**
     * Whether to play a short, pleasant ascending tone once the user's speech has
     * been transcribed, so they know dictation finished (and a reply is on the
     * way) without watching the screen. The counterpart to [getErrorSound]: same
     * alarm-stream routing so it stays audible on silent, opposite cadence (rising
     * instead of falling). Off by default — it's an opt-in convenience.
     *
     * @return whether the transcription-complete tone is enabled
     * */
    fun getTranscriptionDoneSound() : Boolean {
        return getGlobalBoolean("transcription_done_sound", false)
    }

    /**
     * Enable or disable the tone played once speech has been transcribed.
     *
     * @param state whether the transcription-complete tone is enabled
     * */
    fun setTranscriptionDoneSound(state: Boolean) {
        putGlobalBoolean("transcription_done_sound", state, false)
    }

    /**
     * Retrieves the model name from the shared preferences.
     *
     * @return The model name or "gpt-4o" if not found. GPT4-o is now much more capable than gpt 3.5 and 15x cheaper.
     */
    fun getModel() : String {
        var model = getString("model", "gpt-4o")

        // Migrate from legacy dated model
        if (model == "gpt-4-1106-preview") model = "gpt-4-turbo-preview"
        return model
    }

    /**
     * Sets the model name in the shared preferences.
     *
     * @param model The model name to be stored.
     */
    fun setModel(model: String) {
        // Migrate from legacy dated model
        if (model == "gpt-4-1106-preview") {
            putString("model", "gpt-4-turbo-preview")
        } else {
            putString("model", model)
        }
    }

    /**
     * Retrieves the max tokens value from the shared preferences.
     *
     * @return The maximum token value or 1500 if not found.
     */
    fun getMaxTokens() : Int {
        return getString("max_tokens", "1500").toInt()
    }

    /**
     * Sets the max tokens value in the shared preferences.
     *
     * @param tokens The maximum token value to be stored.
     */
    fun setMaxTokens(tokens: Int) {
        putString("max_tokens", tokens.toString())
    }

    /** Whether the user permanently dismissed the destructive Condense hint. */
    fun getNeverShowCondenseHint(): Boolean {
        return getGlobalBoolean("never_show_condense_hint", false)
    }

    fun setNeverShowCondenseHint(value: Boolean) {
        putGlobalBoolean("never_show_condense_hint", value)
    }

    /** Whether the user permanently dismissed the Reduce (image) hint. Kept
     *  separate from the Condense hint on purpose: reducing an image is a
     *  different action from condensing a document, and hiding one must not
     *  silently hide the other. */
    fun getNeverShowReduceHint(): Boolean {
        return getGlobalBoolean("never_show_reduce_hint", false)
    }

    fun setNeverShowReduceHint(value: Boolean) {
        putGlobalBoolean("never_show_reduce_hint", value)
    }

    /**
     * Retrieves the image model name from the shared preferences.
     *
     * @return The imageModel value or "gpt-image-1" if not found.
     */
    fun getImageModel() : String {
        return getString("imageModel", "gpt-image-1")
    }

    /**
     * Sets the image model name in the shared preferences.
     *
     * @param imageModel The resolution value to be stored.
     */
    fun setImageModel(imageModel: String) {
        putString("imageModel", imageModel)
    }

    /**
     * Retrieves the resolution from the shared preferences.
     *
     * @return The resolution value or "1024x1024" if not found.
     */
    fun getResolution() : String {
        return getString("resolution", "1024x1024")
    }

    /**
     * Sets the resolution in the shared preferences.
     *
     * @param resolution The resolution value to be stored.
     */
    fun setResolution(resolution: String) {
        putString("resolution", resolution)
    }

    /**
     * Retrieves the app-wide Always Speak Responses preference.
     *
     * Older releases stored this inside settings.<chatId>. Until the user changes
     * the switch again, fall back to that legacy value so the upgrade preserves
     * their existing choice. Every new write uses the durable global store.
     */
    fun getNotSilence() : Boolean {
        if (!gp.contains("always_speak_mode")) {
            val legacyDefault = try {
                defaultPreferences?.getBoolean("always_speak_mode", false) ?: false
            } catch (_: Exception) {
                false
            }
            gp.edit().putBoolean("always_speak_mode", legacyDefault).commit()
        }
        return getGlobalBoolean("always_speak_mode", false)
    }

    /**
     * Sets the app-wide Always Speak Responses preference.
     *
     * @param mode mode.
     * @param commitImmediately true for a direct user toggle, making the choice
     * durable before returning; false only for non-interactive initialization.
     */
    fun setNotSilence(mode: Boolean, commitImmediately: Boolean = true) {
        if (!gp.contains("always_speak_mode") ||
            getGlobalBoolean("always_speak_mode", false) != mode
        ) {
            val editor = gp.edit().putBoolean("always_speak_mode", mode)
            if (commitImmediately) editor.commit() else editor.apply()
        }
    }

    /**
     * Migration-only reader for the removed Function Calling feature's
     * stored value (image-generation-rebuild-plan.md §15 removed the
     * feature; §14 seeds Let the AI Create Images from this value once).
     * Nothing writes it any more; the stored key is erased with the other
     * legacy fields after the rebuild is verified.
     */
    fun getLegacyFunctionCallingForMigration() : Boolean {
        return getBoolean("function_calling", false)
    }

    /**
     * Set amoled pitch black mode
     *
     * @param mode amoled pitch black mode
     * */
    fun setAmoledPitchBlack(mode: Boolean) {
        putGlobalBoolean("amoled_pitch_black", mode)
    }

    /**
     * Get amoled pitch black mode
     *
     * @return amoled pitch black mode
     * */
    fun getAmoledPitchBlack() : Boolean {
        return getGlobalBoolean("amoled_pitch_black", false)
    }

    /**
     * Retrieves the auto language detection status in the shared preferences.
     *
     * @return uto language detection status to be stored (true for enabled, false otherwise).
     */
    fun getAutoLangDetect() : Boolean {
        return getBoolean("autoLangDetect", false)
    }

    /**
     * Sets the auto language detection status in the shared preferences.
     *
     * @param mode - The auto language detection status to be stored (true for enabled, false otherwise).
     */
    fun setAutoLangDetect(mode: Boolean) {
        putBoolean("autoLangDetect", mode)
    }

    /**
     * Desktop mode - automatically focus message input once chat is opened, press enter to send message, shift+enter to add new line
     *
     * This param is global and applies across all chats and activities in the app
     *
     * @return desktop mode status
     * */
    fun getDesktopMode() : Boolean {
        return getGlobalBoolean("desktopMode", false)
    }

    /**
     * Set desktop mode
     *
     * This param is global and applies across all chats and activities in the app
     *
     * @param mode desktop mode status
     * */
    fun setDesktopMode(mode: Boolean) {
        putGlobalBoolean("desktopMode", mode)
    }

    /**
     * Retrieves the hide model names status from the shared preferences.
     *
     * @return The hide model names status, true if enabled or false otherwise.
     */
    fun getHideModelNames() : Boolean {
        try {
            return getGlobalString("hide_model_names", "true") == "true"
        } catch (_: Exception) {
            val hideModelNames = getGlobalBoolean("hide_model_names", false)
            gp.edit()?.remove("hide_model_names")?.apply()
            putGlobalString("hide_model_names", if (hideModelNames) "true" else "false")
            return hideModelNames
        }
    }

    /**
     * Enable/disable hide model names.
     *
     * @param state mode.
     */
    fun setHideModelNames(state: Boolean) {
        try {
            putGlobalString("hide_model_names", if (state) "true" else "false")
        } catch (_: Exception) {
            gp.edit()?.remove("hide_model_names")?.apply()
            putGlobalString("hide_model_names", if (state) "true" else "false")
        }
    }

    /** Appearance controls for the adaptable chat message shell. */
    fun getStaggeredResponses(): Boolean =
        getGlobalBoolean("chat_staggered_responses", true)

    fun setStaggeredResponses(state: Boolean) {
        putGlobalBoolean("chat_staggered_responses", state, true)
    }

    fun getShowChatProfileImages(): Boolean =
        getGlobalBoolean("chat_show_profile_images", true)

    fun setShowChatProfileImages(state: Boolean) {
        putGlobalBoolean("chat_show_profile_images", state, true)
    }

    fun getShowCompanionImagesInChatList(): Boolean =
        getGlobalBoolean("chat_list_companion_images", false)

    fun setShowCompanionImagesInChatList(state: Boolean) {
        putGlobalBoolean("chat_list_companion_images", state, false)
    }

    fun getShowChatNames(): Boolean = getGlobalBoolean("chat_show_names", true)

    fun setShowChatNames(state: Boolean) {
        putGlobalBoolean("chat_show_names", state, true)
    }

    fun getBoldUserChatName(): Boolean = getGlobalBoolean("chat_bold_user_name", false)

    fun setBoldUserChatName(state: Boolean) {
        putGlobalBoolean("chat_bold_user_name", state)
    }

    fun getBoldAiChatName(): Boolean = getGlobalBoolean("chat_bold_ai_name", false)

    fun setBoldAiChatName(state: Boolean) {
        putGlobalBoolean("chat_bold_ai_name", state)
    }

    fun getShowAiBubble(): Boolean = getGlobalBoolean("chat_show_ai_bubble", true)

    fun setShowAiBubble(state: Boolean) {
        putGlobalBoolean("chat_show_ai_bubble", state, true)
    }

    fun getShowUserBubble(): Boolean = getGlobalBoolean("chat_show_user_bubble", true)

    fun setShowUserBubble(state: Boolean) {
        putGlobalBoolean("chat_show_user_bubble", state, true)
    }

    /**
     * Positive UI for the existing negative preference. Keeping one source of
     * truth preserves every stored value without a destructive key rewrite.
     */
    fun getShowModelNames(): Boolean = !getHideModelNames()

    fun setShowModelNames(state: Boolean) {
        setHideModelNames(!state)
    }

    fun getShowTokenUsage(): Boolean = getGlobalBoolean("chat_show_token_usage", false)

    fun setShowTokenUsage(state: Boolean) {
        putGlobalBoolean("chat_show_token_usage", state)
    }

    /** Whether the per-message reasoning-effort glyph is shown on AI replies
     *  (Chat Settings → Thinking Indicator). Default on. Hiding it never
     *  touches the per-message stored level, so turning it back on restores
     *  every glyph exactly. */
    fun getShowThinkingIndicator(): Boolean = getGlobalBoolean("chat_show_thinking_indicator", true)

    fun setShowThinkingIndicator(state: Boolean) {
        putGlobalBoolean("chat_show_thinking_indicator", state, true)
    }

    /** Whether the Thinking disclosure (the collapsible reasoning block under
     *  an AI reply) is shown at all, on current and past chats alike (Chat
     *  Settings → Show Thinking). Default on. This gates display only — the
     *  reasoning text itself is still requested and stored exactly as before,
     *  so turning this back on restores it everywhere unchanged. */
    fun getShowThinking(): Boolean = getGlobalBoolean("chat_show_thinking", true)

    fun setShowThinking(state: Boolean) {
        putGlobalBoolean("chat_show_thinking", state, true)
    }

    /** Places the per-response read-aloud control beside the assistant name.
     * Default on. Off preserves the original action-row placement. */
    fun getTopPositionedAudioControl(): Boolean =
        getGlobalBoolean("chat_top_positioned_audio_control", true)

    fun setTopPositionedAudioControl(state: Boolean) {
        putGlobalBoolean("chat_top_positioned_audio_control", state, true)
    }

    /** Renamed presentation of Desktop Mode; the stored key and behavior stay intact. */
    fun getHardwareKeyboardShortcuts(): Boolean = getDesktopMode()

    fun setHardwareKeyboardShortcuts(state: Boolean) {
        setDesktopMode(state)
    }

    fun getUserChatNameFont(): String = getGlobalString("chat_user_name_font", "roboto")

    fun setUserChatNameFont(fontId: String) {
        putGlobalString("chat_user_name_font", fontId, "roboto")
    }

    fun getUserChatNameSizeSp(): Int = getGlobalInt("chat_user_name_size_sp", 18)

    fun setUserChatNameSizeSp(sizeSp: Int) {
        putGlobalInt("chat_user_name_size_sp", sizeSp, 18)
    }

    fun getAiChatNameFont(): String = getGlobalString("chat_ai_name_font", "roboto")

    fun setAiChatNameFont(fontId: String) {
        putGlobalString("chat_ai_name_font", fontId, "roboto")
    }

    fun getAiChatNameSizeSp(): Int = getGlobalInt("chat_ai_name_size_sp", 18)

    fun setAiChatNameSizeSp(sizeSp: Int) {
        putGlobalInt("chat_ai_name_size_sp", sizeSp, 18)
    }

    /**
     * Retrieves the custom host URL for API requests.
     *
     * @return The custom host URL as a string. If no custom host URL is set, it returns the default value "https://api.openai.com".
     */
    @Deprecated("Use ApiEndpointPreferences instead")
    fun getCustomHost() : String {
        return getGlobalString("custom_host", "https://api.openai.com/v1/")
    }

    /**
     * Sets the custom host URL for API requests.
     *
     * @param host The custom host URL to be set.
     */
    @Deprecated("Use ApiEndpointPreferences instead")
    fun setCustomHost(host: String) {
        putGlobalString("custom_host", host)
    }

    /**
     * Get debug mode
     * */
    fun getDebugMode() : Boolean {
        return getGlobalBoolean("debug_mode", false)
    }

    /**
     * Set debug mode
     * */
    fun setDebugMode(state: Boolean) {
        putGlobalBoolean("debug_mode", state)
    }

    /**
     * Retrieves system message. System messages allow you to make ChatGPT more reliable.
     *
     * @return System message.
     */
    fun getSystemMessage() : String {
        return getGlobalString("system_message", "")
    }

    /**
     * Sets system message. System messages allow you to make ChatGPT more reliable.
     *
     * @param message The system message.
     */
    fun setSystemMessage(message: String) {
        putGlobalString("system_message", message)
    }

    /**
     * Retrieves the end separator from the shared preferences.
     *
     * @return The end separator value or an empty String if not found.
     */
    fun getEndSeparator() : String {
        return getString("end", "")
    }

    /**
     * Sets the end separator in the shared preferences.
     *
     * @param separator The end separator value to be stored.
     */
    fun setEndSeparator(separator: String) {
        putString("end", separator)
    }

    /**
     * Retrieves the prefix from the shared preferences.
     *
     * @return The prefix value or an empty String if not found.
     */
    fun getPrefix() : String {
        return getString("prefix", "")
    }

    /**
     * Sets the prefix in the shared preferences.
     *
     * @param prefix The prefix value to be stored.
     */
    fun setPrefix(prefix: String) {
        putString("prefix", prefix)
    }

    /**
     * Retrieves the speech-to-text engine.
     *
     * This is a single app-wide choice — one microphone engine for the whole
     * app, the way a keyboard is chosen once for the device — so it lives in
     * the global store, not per chat.
     *
     * Recognized values:
     *  - "google"        — Android on-device dictation (default)
     *  - "whisper"       — paid OpenAI Whisper cloud API
     *  - "whisper-local" — on-device whisper.cpp (user must download a model)
     *
     * Older releases stored this inside settings.<chatId> and seeded each new
     * chat from the default profile. Until the global value exists, fall back
     * once to that legacy default so the upgrade preserves the user's existing
     * choice; every new write uses the durable global store.
     *
     * @return The speech-to-text engine or "google" if not found.
     */
    fun getAudioModel() : String {
        if (!gp.contains("audio")) {
            val legacyDefault = try {
                defaultPreferences?.getString("audio", null)
            } catch (_: Exception) {
                null
            }
            gp.edit().putString("audio", legacyDefault ?: "google").commit()
        }
        return getGlobalString("audio", "google")
    }

    /**
     * Sets the app-wide speech-to-text engine. The choice applies in every
     * conversation and on the assistant screen, and survives restarts.
     *
     * @param model The speech-to-text engine value to be stored.
     */
    fun setAudioModel(model: String) {
        putGlobalString("audio", model)
    }

    /**
     * Engine the runtime should actually use. Identical to [getAudioModel]
     * now that on-device Whisper is wired up; kept as a separate function
     * so the dispatch sites in ChatActivity / AssistantFragment route
     * through one well-named accessor. The fallback for the
     * "whisper-local selected but no model installed" case lives in the
     * dispatchers themselves, not here, because choosing the fallback
     * surfaces UI (a snackbar).
     */
    fun getEffectiveAudioModel() : String = getAudioModel()

    /**
     * Active on-device Whisper model name (e.g. "base.en"). Empty string when
     * no model has been picked yet. Independent of which models are installed
     * on disk — see LocalWhisperStorage for that.
     *
     * Stored globally rather than per-chat: the downloaded model file is a
     * device-level resource, so it makes more sense for the "active model"
     * choice to follow the device than each conversation.
     */
    fun getActiveLocalWhisperModel() : String {
        return getGlobalString("audio_local_model", "")
    }

    fun setActiveLocalWhisperModel(name: String) {
        putGlobalString("audio_local_model", name)
    }

    /**
     * Retrieves the prompt from the shared preferences.
     *
     * @return The prompt value or an empty String if not found.
     */
    fun getPrompt() : String {
        return getString("prompt", "")
    }

    /**
     * Sets the prompt in the shared preferences.
     *
     * @param prompt The prompt value to be stored.
     */
    fun setPrompt(prompt: String) {
        putString("prompt", prompt)
    }


    /**
     * Sets the assistant name in the shared preferences.
     *
     * @param name The assistant name value to be stored.
     */
    fun setAssistantName(name: String) {
        putString("assistant_name", name)
    }

    /**
     * Retrieves the assistant name from the shared preferences.
     *
     * @return The assistant name value or "Assistant" if not found.
     */
    fun getAssistantName() : String {
        return getString("assistant_name", "Assistant")
    }

    /**
     * Sets the avatar type in the shared preferences.
     *
     * @param type The avatar value (file/builtin/url) to be stored.
     */
    fun setAvatarType(type: String) {
        putString("avatar_type", type)
    }

    /**
     * Retrieves the avatar type from the shared preferences.
     *
     * @return The avatar type value or "Assistant" if not found.
     */
    fun getAvatarType() : String {
        return getString("avatar_type", "builtin")
    }

    fun getAvatarTypeByChatId(chatId: String, context: Context) : String {
        val sharedPreferences = SecurePrefs.get(context, "settings.$chatId")
        return sharedPreferences.getString("avatar_type", "builtin").toString()
    }

    /**
     * Sets the avatar Id in the shared preferences.
     *
     * @param id The avatar Id value to be stored.
     */
    fun setAvatarId(id: String) {
        putString("avatar_id", id)
    }

    /**
     * Retrieves the avatar Id from the shared preferences.
     *
     * @return The avatar Id value or "speakgpt" if not found.
     */
    fun getAvatarId() : String {
        return getString("avatar_id", "gpt")
    }

    fun getAvatarIdByChatId(chatId: String, context: Context) : String {
        val sharedPreferences = SecurePrefs.get(context, "settings.$chatId")
        return sharedPreferences.getString("avatar_id", "gpt").toString()
    }

    /**
     * Retrieves the language from the shared preferences.
     *
     * @return The language value or an english if not found.
     */
    fun getLanguage() : String {
        return getGlobalString("lang", "en")
    }

    /**
     * Sets the language in the shared preferences.
     *
     * @param lang The language value to be stored.
     */
    fun setLanguage(lang: String) {
        putGlobalString("lang", lang)
    }

    /**
     * The language used for Google speech-to-text dictation, kept separate
     * from the spoken-voice language. When the user has not chosen one, it
     * falls back to the device's primary language if that language is one the
     * picker supports, otherwise English. The stored value persists across
     * dictation-engine changes.
     */
    fun getDictationLanguage(): String {
        val stored = getGlobalString("dictation_lang", "")
        if (stored.isNotEmpty()) return stored
        val device = Locale.getDefault().language
        return if (supportedDictationLanguages.contains(device)) device else "en"
    }

    fun setDictationLanguage(lang: String) {
        putGlobalString("dictation_lang", lang)
    }

    // Language codes the Select Language picker offers; used to decide whether
    // the device's primary language is a usable default for Google dictation.
    private val supportedDictationLanguages = setOf(
        "en", "fr", "de", "it", "ja", "ko", "zh_CN", "zh_TW", "es", "uk", "ru", "pl", "tr"
    )

    // Voice identity is app-wide even when this Preferences instance belongs to a chat.
    fun getVoice(): String = ttsVoicePreferences.getVoice()
    fun setVoice(model: String) = ttsVoicePreferences.setVoice(model)
    fun getTtsEngine(): String = ttsVoicePreferences.getTtsEngine()
    fun setTtsEngine(engine: String) = ttsVoicePreferences.setTtsEngine(engine)
    fun getOpenAIVoice(): String = ttsVoicePreferences.getOpenAIVoice()
    fun setOpenAIVoice(voice: String) = ttsVoicePreferences.setOpenAIVoice(voice)
    fun getOpenAITtsModel(): String = ttsVoicePreferences.getOpenAITtsModel()
    fun setOpenAITtsModel(model: String) = ttsVoicePreferences.setOpenAITtsModel(model)
    fun getSelectedTtsVoice() = ttsVoicePreferences.getSelectedTtsVoice()
    fun saveSelectedTtsVoice(selection: org.teslasoft.assistant.preferences.tts.TtsVoiceSelection): Boolean =
        ttsVoicePreferences.saveSelectedTtsVoice(selection)
    fun isTtsVoicePermanentlyUnavailable(selection: org.teslasoft.assistant.preferences.tts.TtsVoiceSelection): Boolean =
        ttsVoicePreferences.isTtsVoicePermanentlyUnavailable(selection)
    fun markTtsVoicePermanentlyUnavailable(selection: org.teslasoft.assistant.preferences.tts.TtsVoiceSelection) =
        ttsVoicePreferences.markTtsVoicePermanentlyUnavailable(selection)

    /** Voice dialogs always open the app-wide selector. */
    fun ttsPreferenceScope(): String = ""

    fun getVoiceBrowserFilters(providerId: String): String =
        getGlobalString("voice_browser_filters_$providerId", "")

    fun setVoiceBrowserFilters(providerId: String, encoded: String) {
        putGlobalString("voice_browser_filters_$providerId", encoded, "")
    }

    /**
     * Set temperature. Min value 0, max 2
     *
     * @param temperature temperature
     * */
    fun setTemperature(temperature: Float) {
        putString("temperature", temperature.toString())
    }

    /**
     * Set frequency penalty. Min value -2, max 2
     *
     * @param frequencyPenalty frequency penalty
     * */
    fun setFrequencyPenalty(frequencyPenalty: Float) {
        putString("frequency_penalty", frequencyPenalty.toString())
    }

    /**
     * Get frequency penalty. Min value -2, max 2
     *
     * @return frequency penalty
     * */
    fun getFrequencyPenalty() : Float {
        return getString("frequency_penalty", "0.0").toFloat()
    }

    /**
     * Set presence penalty. Min value -2, max 2
     *
     * @param presencePenalty presence penalty
     * */
    fun setPresencePenalty(presencePenalty: Float) {
        putString("presence_penalty", presencePenalty.toString())
    }

    /**
     * Get presence penalty. Min value -2, max 2
     *
     * @return presence penalty
     * */
    fun getPresencePenalty() : Float {
        return getString("presence_penalty", "0.0").toFloat()
    }

    /**
     * Get temperature. Min value 0, max 2
     *
     * @return temperature
     * */
    fun getTemperature() : Float {
        return getString("temperature", "0.7").toFloat()
    }

    /**
     * Whether replies for this settings file use the streaming Chat Completions
     * path. The empty-chat settings file is the default copied into new chats;
     * an existing chat always reads its own stored value. Streaming has always
     * been the app's behavior, so missing values remain enabled.
     */
    fun setStreaming(streaming: Boolean) {
        // Persist even the ON value so every newly-created chat has an
        // explicit value and can never fall back to a later global change.
        preferences.edit { putBoolean("streaming", streaming) }
    }

    fun getStreaming(): Boolean {
        return getBoolean("streaming", true)
    }

    /**
     * Set top P. Min value 0 max 1
     *
     * @param topP top P
     * */
    fun setTopP(topP: Float) {
        putString("topP", topP.toString())
    }

    /**
     * Get top P. Min value 0 max 1
     *
     * @return top P
     * */
    fun getTopP() : Float {
        return getString("topP", "1").toFloat()
    }

    /**
     * Set seed
     *
     * @param seed seed
     * */
    fun setSeed(seed: String) {
        putString("seed", seed)
    }

    /**
     * Get seed
     *
     * @return seed
     * */
    fun getSeed() : String {
        return getString("seed", "")
    }

    /**
     * This conversation's own reasoning-effort override (chat-redesign-plan.md
     * §7.5/§7.9), as a ReasoningEffort serialized value.
     *
     * Tri-state by design:
     *  - empty string  → the conversation has NO override yet; it inherits the
     *    favorite's saved default (and Auto beneath that).
     *  - any value, INCLUDING "auto" → the conversation owns and persists its
     *    own choice. "auto" is a real persisted decision ("send no explicit
     *    effort"), not an alias for inherit and not an alias for a middle level.
     *
     * The override is never cleared automatically: if the conversation switches
     * to a non-reasoning model the control is hidden, but this value is
     * preserved so switching back does not erase the user's last choice.
     *
     * @return the serialized override, or "" when the conversation inherits.
     */
    fun getReasoningEffortOverride() : String {
        return getString("reasoning_effort", "")
    }

    /**
     * Persist this conversation's reasoning-effort override. Pass a
     * ReasoningEffort serialized value ("auto" included) to record an explicit
     * per-conversation choice.
     */
    fun setReasoningEffortOverride(effort: String) {
        putString("reasoning_effort", effort)
    }

    /**
     * Automatically send messages after voice input is complete
     *
     * @return auto send
     * */
    fun autoSend() : Boolean {
        return getGlobalBoolean("auto_send", true)
    }

    /**
     * Automatically send messages after voice input is complete
     *
     * @param state auto send
     * */
    fun setAutoSend(state: Boolean) {
        putGlobalBoolean("auto_send", state, true)
    }

    /**
     * Hands-free conversation mode. When enabled, after the assistant finishes
     * speaking the microphone automatically restarts so the conversation can
     * continue without tapping. Works with Google speech recognition and spoken
     * (auto-send) responses.
     *
     * @return hands-free mode status
     * */
    fun getHandsFreeMode() : Boolean {
        return getGlobalBoolean("hands_free_mode", false)
    }

    /**
     * Enable/disable hands-free conversation mode.
     *
     * @param state hands-free mode status
     * */
    fun setHandsFreeMode(state: Boolean) {
        putGlobalBoolean("hands_free_mode", state, false)
    }

    /**
     * Seconds of silence tolerated after you start speaking before your turn is
     * treated as finished. Gives you time to think mid-sentence.
     *
     * @return silence seconds (default 5)
     * */
    fun getHandsFreeSilenceSeconds() : Int {
        return getGlobalString("hands_free_silence_seconds", "5").toIntOrNull() ?: 5
    }

    /**
     * Set the hands-free silence tolerance in seconds.
     * */
    fun setHandsFreeSilenceSeconds(seconds: Int) {
        putGlobalString("hands_free_silence_seconds", seconds.toString(), "5")
    }

    /**
     * Seconds to wait for you to start speaking after the mic opens before the
     * hands-free loop gives up and stops listening.
     *
     * @return no-speech seconds (default 10)
     * */
    fun getHandsFreeNoSpeechSeconds() : Int {
        return getGlobalString("hands_free_no_speech_seconds", "10").toIntOrNull() ?: 10
    }

    /**
     * Set the hands-free no-speech timeout in seconds.
     * */
    fun setHandsFreeNoSpeechSeconds(seconds: Int) {
        putGlobalString("hands_free_no_speech_seconds", seconds.toString(), "10")
    }

    /**
     * Voice-activity-detection method used to decide when a hands-free turn
     * has ended when on-device Whisper is the STT engine. One of the ids in
     * [org.teslasoft.assistant.stt.VadMethods] ("energy", "webrtc", ...).
     * Defaults to Silero: a neural detector that is the most accurate at
     * telling speech from background noise. When its runtime can't load on a
     * device it transparently falls back to Energy, so hands-free always works.
     *
     * @return VAD method id
     * */
    fun getVadMethod() : String {
        return getGlobalString("vad_method", "silero")
    }

    /**
     * Set the hands-free voice-activity-detection method.
     * */
    fun setVadMethod(method: String) {
        putGlobalString("vad_method", method, "silero")
    }

    /**
     * WebRTC VAD aggressiveness (libfvad mode) used when [getVadMethod] is
     * "webrtc". 0 = most sensitive (hears the most speech), 3 = most
     * aggressive (rejects the most noise, may miss quiet/distant speech).
     * Defaults to 1 (medium-high). See
     * [org.teslasoft.assistant.stt.VadMethods].
     *
     * @return fvad mode 0..3 (default 1)
     * */
    fun getVadWebRtcMode() : Int {
        return (getGlobalString("vad_webrtc_mode", "1").toIntOrNull() ?: 1).coerceIn(0, 3)
    }

    /**
     * Set the WebRTC VAD aggressiveness (clamped to 0..3).
     * */
    fun setVadWebRtcMode(mode: Int) {
        putGlobalString("vad_webrtc_mode", mode.coerceIn(0, 3).toString(), "1")
    }

    fun getVadLoggingEnergy() : Boolean {
        return getGlobalBoolean("vad_logging_energy", false)
    }

    fun setVadLoggingEnergy(state: Boolean) {
        putGlobalBoolean("vad_logging_energy", state, false)
    }

    fun getVadLoggingWebrtc() : Boolean {
        return getGlobalBoolean("vad_logging_webrtc", false)
    }

    fun setVadLoggingWebrtc(state: Boolean) {
        putGlobalBoolean("vad_logging_webrtc", state, false)
    }

    fun getVadLoggingSilero() : Boolean {
        return getGlobalBoolean("vad_logging_silero", false)
    }

    fun setVadLoggingSilero(state: Boolean) {
        putGlobalBoolean("vad_logging_silero", state, false)
    }

    // Audio Health is a separate diagnostic from the VAD logging toggles above:
    // it answers "did the microphone deliver usable audio?" (dead/muted mic,
    // clipping, route changes) rather than "was there speech?". Independent
    // toggle so it can be turned on without the per-frame VAD spam.
    fun getAudioHealthLogging() : Boolean {
        return getGlobalBoolean("audio_health_logging", false)
    }

    fun setAudioHealthLogging(state: Boolean) {
        putGlobalBoolean("audio_health_logging", state, false)
    }

    // ---- Advanced VAD tuning (on-device Whisper hands-free only) ----------
    // These exist because the field showed the one-size-fits-all energy gate
    // failing real users: the gate (min 600 RMS) was tuned against a desk fan
    // on one device, but a quiet voice / distant mic never clears it — WebRTC
    // hears the speech and the gate throws it away ("voiced N, gated 0").
    // Defaults preserve the long-standing behaviour; the advanced settings
    // screen explains how to read the diagnostics and adjust.

    /** Energy gate over the WebRTC vote: on (default) rejects steady noise
     *  (fan/AC) the GMM mislabels as voice; off trusts the WebRTC vote alone.
     *  Ignored by the Energy method (energy IS its detector). */
    fun getVadEnergyGateEnabled() : Boolean {
        return getGlobalBoolean("vad_energy_gate", true)
    }

    fun setVadEnergyGateEnabled(state: Boolean) {
        putGlobalBoolean("vad_energy_gate", state, true)
    }

    /** Absolute minimum frame RMS to count as speech (default 600). Lower it
     *  when diagnostics show "speech heard but below the energy gate". */
    fun getVadMinSpeechRms() : Int {
        return (getGlobalString("vad_min_speech_rms", "600").toIntOrNull() ?: 600).coerceIn(50, 5000)
    }

    fun setVadMinSpeechRms(value: Int) {
        putGlobalString("vad_min_speech_rms", value.coerceIn(50, 5000).toString(), "600")
    }

    /** Multiplier over the adaptive noise floor (default 2.5). */
    fun getVadFloorFactor() : Float {
        return (getGlobalString("vad_floor_factor", "2.5").toFloatOrNull() ?: 2.5f).coerceIn(1.0f, 8.0f)
    }

    fun setVadFloorFactor(value: Float) {
        putGlobalString("vad_floor_factor", value.coerceIn(1.0f, 8.0f).toString(), "2.5")
    }

    /** Cap on the adaptive speech threshold so a loud opening frame can't pin
     *  the gate above the user's own voice (default 1400). */
    fun getVadEnergyCeiling() : Int {
        return (getGlobalString("vad_energy_ceiling", "1400").toIntOrNull() ?: 1400).coerceIn(200, 8000)
    }

    fun setVadEnergyCeiling(value: Int) {
        putGlobalString("vad_energy_ceiling", value.coerceIn(200, 8000).toString(), "1400")
    }

    /** Milliseconds of detected speech required before a turn counts as
     *  started (default 0 = first speech frame starts the turn). Raising it
     *  stops a door slam / cough from starting a turn. */
    fun getVadMinSpeechMs() : Int {
        return (getGlobalString("vad_min_speech_ms", "0").toIntOrNull() ?: 0).coerceIn(0, 2000)
    }

    fun setVadMinSpeechMs(value: Int) {
        putGlobalString("vad_min_speech_ms", value.coerceIn(0, 2000).toString(), "0")
    }

    /** Hysteresis (two-level gate): once speech starts, the gate drops to
     *  [getVadHysteresisExitPercent] of itself so the quieter words of the
     *  same sentence keep counting as speech. Default on — built for rooms
     *  whose loudness keeps changing. */
    fun getVadHysteresisEnabled() : Boolean {
        return getGlobalBoolean("vad_hysteresis", true)
    }

    fun setVadHysteresisEnabled(state: Boolean) {
        putGlobalBoolean("vad_hysteresis", state, true)
    }

    /** Exit level of the hysteresis gate, as a percentage of the entry gate
     *  (default 50). Lower = harder to be cut off mid-sentence, but steady
     *  noise can keep a turn alive longer once one has started. */
    fun getVadHysteresisExitPercent() : Int {
        return (getGlobalString("vad_hysteresis_exit", "50").toIntOrNull() ?: 50).coerceIn(20, 95)
    }

    fun setVadHysteresisExitPercent(value: Int) {
        putGlobalString("vad_hysteresis_exit", value.coerceIn(20, 95).toString(), "50")
    }

    /** Speech-hold (hangover): after speech, dips up to this long still count
     *  as speech (default 0 = off). Effectively adds to the pause time before
     *  a turn ends. */
    fun getVadHangoverMs() : Int {
        return (getGlobalString("vad_hangover_ms", "0").toIntOrNull() ?: 0).coerceIn(0, 2000)
    }

    fun setVadHangoverMs(value: Int) {
        putGlobalString("vad_hangover_ms", value.coerceIn(0, 2000).toString(), "0")
    }

    /** Silero-only: speech probability (percent) required to call a moment
     *  speech (default 50). The energy-gate settings don't apply to the
     *  neural detector. */
    fun getVadSileroThreshold() : Int {
        return (getGlobalString("vad_silero_threshold", "50").toIntOrNull() ?: 50).coerceIn(5, 95)
    }

    fun setVadSileroThreshold(value: Int) {
        putGlobalString("vad_silero_threshold", value.coerceIn(5, 95).toString(), "50")
    }

    // ---- Advanced on-device Whisper decoding -------------------------------
    // Mapped 1:1 onto whisper.cpp's whisper_full_params; defaults match what
    // the JNI layer always hardcoded, so leaving these alone changes nothing.

    /** "beam" (default, better punctuation/structure) or "greedy" (faster). */
    fun getWhisperDecoder() : String {
        return getGlobalString("whisper_decoder", "beam")
    }

    fun setWhisperDecoder(value: String) {
        putGlobalString("whisper_decoder", if (value == "greedy") "greedy" else "beam", "beam")
    }

    fun getWhisperBeamSize() : Int {
        return (getGlobalString("whisper_beam_size", "5").toIntOrNull() ?: 5).coerceIn(1, 8)
    }

    fun setWhisperBeamSize(value: Int) {
        putGlobalString("whisper_beam_size", value.coerceIn(1, 8).toString(), "5")
    }

    /** Sampling temperature; 0 = deterministic (whisper.cpp default). */
    fun getWhisperTemperature() : Float {
        return (getGlobalString("whisper_temperature", "0").toFloatOrNull() ?: 0f).coerceIn(0f, 1f)
    }

    fun setWhisperTemperature(value: Float) {
        putGlobalString("whisper_temperature", value.coerceIn(0f, 1f).toString(), "0")
    }

    fun getWhisperSuppressBlank() : Boolean {
        return getGlobalBoolean("whisper_suppress_blank", true)
    }

    fun setWhisperSuppressBlank(state: Boolean) {
        putGlobalBoolean("whisper_suppress_blank", state, true)
    }

    fun getWhisperSingleSegment() : Boolean {
        return getGlobalBoolean("whisper_single_segment", false)
    }

    fun setWhisperSingleSegment(state: Boolean) {
        putGlobalBoolean("whisper_single_segment", state, false)
    }

    /** Optional text whispered to the decoder as context/style priming. */
    fun getWhisperInitialPrompt() : String {
        return getGlobalString("whisper_initial_prompt", "")
    }

    fun setWhisperInitialPrompt(value: String) {
        putGlobalString("whisper_initial_prompt", value, "")
    }

    /** false (default) = no_context: each clip decoded fresh. true lets the
     *  decoder condition on text from earlier clips in the same session. */
    fun getWhisperUsePrevContext() : Boolean {
        return getGlobalBoolean("whisper_use_prev_context", false)
    }

    fun setWhisperUsePrevContext(state: Boolean) {
        putGlobalBoolean("whisper_use_prev_context", state, false)
    }

    /** Strip "[Music]"/"(applause)"-style non-speech annotations (default on). */
    fun getWhisperCleanupTranscript() : Boolean {
        return getGlobalBoolean("whisper_cleanup_transcript", true)
    }

    fun setWhisperCleanupTranscript(state: Boolean) {
        putGlobalBoolean("whisper_cleanup_transcript", state, true)
    }

    /** Log the exact decode parameters + timing of every transcription to the
     *  Event log, for tuning sessions. */
    fun getWhisperDebugParams() : Boolean {
        return getGlobalBoolean("whisper_debug_params", false)
    }

    fun setWhisperDebugParams(state: Boolean) {
        putGlobalBoolean("whisper_debug_params", state, false)
    }

    // ---- Device TTS delivery -------------------------------------------------

    /** Speech rate for the device (Google) TTS engine; 1.0 = normal. */
    fun getTtsSpeechRate() : Float {
        return (getGlobalString("tts_speech_rate", "1.0").toFloatOrNull() ?: 1.0f).coerceIn(0.5f, 2.5f)
    }

    fun setTtsSpeechRate(value: Float) {
        putGlobalString("tts_speech_rate", value.coerceIn(0.5f, 2.5f).toString(), "1.0")
    }

    /** Voice pitch for the device (Google) TTS engine; 1.0 = normal. */
    fun getTtsPitch() : Float {
        return (getGlobalString("tts_pitch", "1.0").toFloatOrNull() ?: 1.0f).coerceIn(0.5f, 2.0f)
    }

    fun setTtsPitch(value: Float) {
        putGlobalString("tts_pitch", value.coerceIn(0.5f, 2.0f).toString(), "1.0")
    }

    /**
     * Retrieves the encrypted API key from the shared preferences.
     *
     * @param context The context to access the encrypted shared preferences.
     * @return The decrypted API key or an empty String if not found.
     */
    @Deprecated("Use ApiEndpointPreferences instead")
    fun getApiKey(context: Context) : String {
        return EncryptedPreferences.getEncryptedPreference(context, "api", "api_key")
    }

    /**
     * Sets the encrypted API key in the shared preferences.
     *
     * @param key The API key to be stored in an encrypted form.
     * @param context The context to access the encrypted shared preferences.
     */
    @Deprecated("Use ApiEndpointPreferences instead")
    fun setApiKey(key: String, context: Context) {
        EncryptedPreferences.setEncryptedPreference(context, "api", "api_key", key)
    }

    /**
     * Now users can set API endpoints per chat
     *
     * @return API endpoint ID
     * */
    fun getApiEndpointId() : String {
        return getString("api_endpoint_id", Hash.hash("Default"))
    }

    /**
     * Now users can set API endpoints per chat
     *
     * @param id API endpoint ID
     * */
    fun setApiEndpointId(id: String) {
        putString("api_endpoint_id", id)
    }

    /**
     * Users can set a persona per chat. The persona prompt is merged before the
     * system message when building requests.
     *
     * @return Persona ID, or an empty String when no persona is selected
     * */
    fun getPersonaId() : String {
        return getString("persona_id", "")
    }

    /**
     * Set the active persona for this chat.
     *
     * @param id Persona ID, or an empty String to clear the selection
     * */
    fun setPersonaId(id: String) {
        putString("persona_id", id)
    }

    /**
     * Get the active activation prompt for this chat.
     *
     * @return Activation prompt ID, or an empty String when none is selected
     * */
    fun getActivationPromptId() : String {
        return getString("activation_prompt_id", "")
    }

    /**
     * Set the active activation prompt for this chat. The prompt text itself is
     * stored separately via [setPrompt] so the existing chat-activation flow
     * keeps working; this id only tracks which library entry is selected so the
     * UI can show its label and highlight it.
     *
     * @param id Activation prompt ID, or an empty String to clear the selection
     * */
    fun setActivationPromptId(id: String) {
        putString("activation_prompt_id", id)
    }

    /**
     * The companion from the most recent chat whose assistant response
     * completed successfully. A new chat may inherit only this qualified
     * companion, never a selection from an unopened or unsuccessful chat.
     */
    fun getLastSuccessfulPersonaId() : String {
        return getGlobalString(LAST_SUCCESS_PERSONA_ID, "")
    }

    /**
     * The activation prompt last applied in any chat (global, not per-chat),
     * seeded into new chats independently of the successful-chat snapshot.
     *
     * @return Activation prompt ID, or an empty String when the last selection
     *         was none (or none was ever set).
     * */
    fun getLastUsedActivationPromptId() : String {
        return getGlobalString("last_used_activation_prompt_id", "")
    }

    /**
     * Record the activation prompt just applied so future new chats default to
     * it.
     *
     * @param id Activation prompt ID, or an empty String for none.
     * */
    fun setLastUsedActivationPromptId(id: String) {
        putGlobalString("last_used_activation_prompt_id", id)
    }

    /**
     * One-shot guard (per chat) so a new chat seeds its companion from the last
     * successful chat and its activation prompt from the existing global default
     * exactly once. After that the chat's own selections always win.
     * */
    fun isPersonaActivationSeeded() : Boolean {
        return getBoolean("persona_activation_seeded", false)
    }

    fun setPersonaActivationSeeded(seeded: Boolean) {
        putBoolean("persona_activation_seeded", seeded)
    }

    /**
     * The provider (API endpoint), model, routing, and companion that the LAST
     * conversation to receive a successful reply used. A brand-new chat restores
     * this snapshot once, so a chat that was merely opened or failed can never
     * become the source. Routing defaults to "automatic" (mirrors
     * FavoriteModelObject.ROUTING_AUTOMATIC).
     */
    fun getLastSuccessfulEndpointId() : String {
        return getGlobalString(LAST_SUCCESS_ENDPOINT_ID, "")
    }

    fun getLastSuccessfulModel() : String {
        return getGlobalString(LAST_SUCCESS_MODEL, "")
    }

    fun getLastSuccessfulRouting() : String {
        return getGlobalString(LAST_SUCCESS_ROUTING, "automatic")
    }

    fun setLastSuccessfulConfig(
        endpointId: String,
        model: String,
        routing: String,
        personaId: String
    ): Boolean {
        if (endpointId.isBlank() || model.isBlank() || personaId.isBlank()) return false
        return gp.edit()
            .putString(LAST_SUCCESS_ENDPOINT_ID, endpointId)
            .putString(LAST_SUCCESS_MODEL, model)
            .putString(LAST_SUCCESS_ROUTING, routing)
            .putString(LAST_SUCCESS_PERSONA_ID, personaId)
            .commit()
    }

    /**
     * One-shot per chat: a brand-new chat restores its provider/model from the
     * last successful config exactly once. After that the chat's own choice
     * always wins, so re-opening an untouched empty chat never overwrites a
     * selection the user just made in the Summoning Circle.
     */
    fun isProviderSeeded() : Boolean {
        return getBoolean("provider_seeded", false)
    }

    fun setProviderSeeded(seeded: Boolean) {
        putBoolean("provider_seeded", seeded)
    }

    /**
     * A deleted chat can leave its hashed settings file behind. Clear only the
     * model/companion inheritance values for the exact new chat ID so its startup
     * cannot be suppressed by stale one-shot guards. apply() updates process
     * memory before returning and writes to disk in the background.
     */
    fun resetNewChatInheritance() {
        preferences.edit()
            .remove("persona_id")
            .remove("persona_activation_seeded")
            .remove("provider_seeded")
            .apply()
    }

    /**
     * Resolves the three Quick Settings values whose new-chat behavior is not
     * inherited from another conversation. Each resolved value is written to
     * the new chat immediately, including when it equals the global default,
     * so later global changes cannot alter an existing chat. Direct writes
     * also replace stale values left behind when a deleted chat ID is reused.
     */
    fun initializeNewChatQuickSettings() {
        val memoryEnabled = resolveDefaultChatMemoryEnabled()
        val applyModelRules = getAutoApplyModelRules()
        val archiveEnabled = getLastChatArchiveEnabled()

        preferences.edit()
            .putString("memory_enabled", if (memoryEnabled) "true" else "false")
            .putBoolean("apply_model_rules", applyModelRules)
            .putBoolean("memory_excluded", !archiveEnabled)
            .apply()
    }

    /**
     * The additional lorebooks currently checked for this chat. Memories from
     * these books (plus the persona's always-on core book) are matched against
     * messages and injected into the prompt. Stored comma-separated.
     *
     * Falls back to the legacy single-book "lorebook_id" key from the beta so
     * a chat that had one active book before the multi-select keeps it.
     *
     * @return List of lorebook IDs (possibly empty)
     * */
    fun getActiveLoreBookIds() : ArrayList<String> {
        val joined = getString("active_lorebook_ids", "")
        if (joined.isNotEmpty()) {
            return ArrayList(joined.split(",").map { it.trim() }.filter { it.isNotEmpty() })
        }
        val legacy = getString("lorebook_id", "")
        return if (legacy != "") arrayListOf(legacy) else arrayListOf()
    }

    /**
     * Set the additional lorebooks checked for this chat.
     *
     * @param ids Lorebook IDs; an empty list means no additional lorebooks
     * */
    fun setActiveLoreBookIds(ids: List<String>) {
        putString("active_lorebook_ids", ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(","))
        // The legacy key must not resurrect an old selection once the
        // multi-select has been written (including writing an empty list).
        putString("lorebook_id", "")
    }

    /**
     * One-shot guard (per chat) so a new chat seeds its checked lorebooks from
     * the persona's last-used set exactly once (when the persona opts in via
     * autoLoadLastLoreBooks). After that the chat's own selection always wins.
     * */
    fun isLoreBooksSeeded() : Boolean {
        return getBoolean("lorebooks_seeded", false)
    }

    fun setLoreBooksSeeded(seeded: Boolean) {
        putBoolean("lorebooks_seeded", seeded)
    }

    /**
     * Memory kill switch (companion memory system): with memory OFF for a
     * chat, nothing from the memory store is injected (enforcer phase).
     * INJECTION ONLY — since Step 1.1 of the external-memory counterplan
     * (§4(f), storage/injection independence) this switch no longer affects
     * transcript capture or review eligibility; "Archive this chat" (the
     * exclusion pref) is the only capture consent. Rows excluded under the
     * old coupling keep their stored state.
     * Per-chat value; a chat that has never set it follows the global default.
     * Stored as a string tri-state ("" = follow global) so the auto-naming
     * copy block can move an unset value without pinning it.
     *
     * QUICK SETTINGS IS AUTHORITATIVE (owner ruling, July 10 2026): an
     * explicit per-chat value always wins, even over the global Memory
     * engine picker — a chat switched ON injects memory regardless of the
     * engine tier, a chat switched OFF never does. Only an UNSET chat
     * follows the globals, and its default is derived from the engine
     * picker (memory injects by default when the engine includes
     * associative search) combined with the Memory settings default toggle.
     * */
    fun getChatMemoryEnabled() : Boolean {
        return when (getString("memory_enabled", "")) {
            "true" -> true
            "false" -> false
            else -> resolveDefaultChatMemoryEnabled()
        }
    }

    private fun resolveDefaultChatMemoryEnabled(): Boolean =
        getMemoryEngine() in setOf("associative", "both") && getDefaultMemoryEnabled()

    fun getChatMemoryEnabledRaw() : String {
        return getString("memory_enabled", "")
    }

    fun setChatMemoryEnabledRaw(value: String) {
        putString("memory_enabled", value)
    }

    fun setChatMemoryEnabled(enabled: Boolean) {
        putString("memory_enabled", if (enabled) "true" else "false")
    }

    /** Global default for the memory kill switch (Settings → Memory). */
    fun getDefaultMemoryEnabled() : Boolean {
        return getGlobalBoolean("default_memory_enabled", true)
    }

    fun setDefaultMemoryEnabled(enabled: Boolean) {
        putGlobalBoolean("default_memory_enabled", enabled, true)
    }

    /**
     * The persistent "Use Importance Ratings" master toggle (canonical recovery
     * plan §7.1). On by default: while Off, importance controls are hidden,
     * retrieval ignores every importance value, and new memories store the
     * neutral 0 — but stored values are never erased, so turning it back On
     * restores them. Enforcement lands in later phases; Phase 1 only persists
     * the setting.
     */
    fun getUseImportanceRatings() : Boolean {
        return getGlobalBoolean("use_importance_ratings", true)
    }

    fun setUseImportanceRatings(enabled: Boolean) {
        putGlobalBoolean("use_importance_ratings", enabled, true)
    }

    /**
     * Display-only preference for the review/archive status line beneath chat
     * rows. It does not enable, disable, or modify any memory processing.
     */
    fun getShowMemoryStatusOnChatList(): Boolean {
        return getGlobalBoolean("show_memory_status_on_chat_list", true)
    }

    fun setShowMemoryStatusOnChatList(enabled: Boolean) {
        putGlobalBoolean("show_memory_status_on_chat_list", enabled, true)
    }

    /**
     * Lore books per-chat switch (Quick Settings). Same tri-state pattern
     * and the same authority rule as [getChatMemoryEnabled]: Quick Settings
     * is God (owner ruling, July 10 2026) — an explicit per-chat value wins
     * over the global Memory engine picker; an unset chat follows the
     * engine-derived default (lore books are on when the engine includes
     * lorebooks). Independent of the memory switch, so any combination —
     * both, either one alone, or neither — works per chat.
     * */
    fun getChatLoreBooksEnabled() : Boolean {
        return when (getString("lorebooks_enabled", "")) {
            "true" -> true
            "false" -> false
            else -> getMemoryEngine() in setOf("lorebooks", "both")
        }
    }

    fun getChatLoreBooksEnabledRaw() : String {
        return getString("lorebooks_enabled", "")
    }

    fun setChatLoreBooksEnabledRaw(value: String) {
        putString("lorebooks_enabled", value)
    }

    fun setChatLoreBooksEnabled(enabled: Boolean) {
        putString("lorebooks_enabled", if (enabled) "true" else "false")
    }

    /**
     * Memory diagnostics logging (Alerts, Errors & Logs). Off by default —
     * when on, the memory system writes what it's doing (transcript capture
     * decisions, librarian/model events, migrations) to the Event log, the
     * same opt-in pattern as the VAD logging toggles. Nothing memory-related
     * logs while this is off.
     */
    fun getMemoryDebugLogging() : Boolean {
        return getGlobalBoolean("memory_debug_logging", false)
    }

    fun setMemoryDebugLogging(enabled: Boolean) {
        putGlobalBoolean("memory_debug_logging", enabled, false)
    }

    /**
     * Whisper performance logging (Alerts, Errors & Logs). Off by default —
     * when on, one line per on-device transcription is written to the
     * Performance Log: how long the audio was, how long the model load took (0
     * when already warm), how long the actual decode ran, plus a compact memory
     * snapshot at that instant. This is the direct diagnostic for "Whisper
     * suddenly takes forever to transcribe": it separates a longer recording
     * from a cold model load from a genuinely slower decode, and correlates the
     * slow turn with the memory pressure at that moment.
     */
    fun getWhisperPerfLogging() : Boolean {
        return getGlobalBoolean("whisper_perf_logging", false)
    }

    fun setWhisperPerfLogging(enabled: Boolean) {
        putGlobalBoolean("whisper_perf_logging", enabled, false)
    }

    /**
     * Memory usage logging (Alerts, Errors & Logs). Off by default — when on, a
     * lightweight app-wide heartbeat writes the process's memory footprint
     * (Java heap, native heap, total PSS, thread count, and the system's
     * available/low-memory state) to the Performance Log every ~60s, plus a
     * line whenever Android asks the app to trim memory. Runs process-wide (not
     * only in a chat) so a leak that grows while the app sits idle is still
     * captured. Left on across a long session, a steadily-climbing PSS or
     * thread count is what a leak looks like.
     */
    fun getMemoryUsageLogging() : Boolean {
        return getGlobalBoolean("memory_usage_logging", false)
    }

    fun setMemoryUsageLogging(enabled: Boolean) {
        putGlobalBoolean("memory_usage_logging", enabled, false)
    }

    /**
     * Text to Speech lifecycle logging (Alerts, Errors & Logs). Off by
     * default — when on, every assistant turn where readback is expected
     * writes its TTS lifecycle (requested / onStart / onDone / onError /
     * skipped, with the turn/utterance id) to its own log, so a reply that
     * completed but was never read aloud can be diagnosed: never reached
     * pronounce(), requested but never started, started then failed, or
     * completed normally (pointing at audio routing/output instead). Written
     * independent of the VAD logging toggles, which govern the separate
     * per-frame voice-pipeline trail.
     */
    fun getTtsLifecycleLogging() : Boolean {
        return getGlobalBoolean("tts_lifecycle_logging", false)
    }

    fun setTtsLifecycleLogging(enabled: Boolean) {
        putGlobalBoolean("tts_lifecycle_logging", enabled, false)
    }

    /**
     * Log entry ordering for the Logs viewer. On (the default) shows each log
     * newest entry first; off shows oldest first. Remembered app-wide so a
     * person's chosen order carries across every log and across sessions until
     * they flip it again.
     */
    fun getLogsNewestFirst() : Boolean {
        return getGlobalBoolean("logs_newest_first", true)
    }

    fun setLogsNewestFirst(enabled: Boolean) {
        putGlobalBoolean("logs_newest_first", enabled, true)
    }

    /**
     * Per-log retention settings (owner spec, July 23 2026). Each of the three
     * user-configurable diagnostic logs — Memory Diagnostics, Whisper
     * Performance, Memory Usage — carries its own independent "Maximum Logs
     * Saved" (entry count) and "Maximum Days Saved" (age) value, edited on the
     * Alerts, Errors & Logs screen and consumed by [Logger.log]. Reads and
     * writes are clamped through [coerceLogMaxEntries]/[coerceLogMaxDays] so a
     * value out of bounds (e.g. left over from a future edit, or an
     * over-ceiling entry the UI catches) can never reach the trimmer. Values
     * absent on first open fall back to the shared defaults, never overwritten
     * with a default once the user has set one (the getters only read).
     */
    fun getMemoryLogMaxEntries() : Int {
        return coerceLogMaxEntries(getGlobalInt("memory_log_max_entries", LOG_DEFAULT_MAX_ENTRIES))
    }

    fun setMemoryLogMaxEntries(value: Int) {
        putGlobalInt("memory_log_max_entries", coerceLogMaxEntries(value), LOG_DEFAULT_MAX_ENTRIES)
    }

    fun getMemoryLogMaxDays() : Int {
        return coerceLogMaxDays(getGlobalInt("memory_log_max_days", LOG_DEFAULT_MAX_DAYS))
    }

    fun setMemoryLogMaxDays(value: Int) {
        putGlobalInt("memory_log_max_days", coerceLogMaxDays(value), LOG_DEFAULT_MAX_DAYS)
    }

    fun getWhisperPerfLogMaxEntries() : Int {
        return coerceLogMaxEntries(getGlobalInt("whisper_perf_log_max_entries", LOG_DEFAULT_MAX_ENTRIES))
    }

    fun setWhisperPerfLogMaxEntries(value: Int) {
        putGlobalInt("whisper_perf_log_max_entries", coerceLogMaxEntries(value), LOG_DEFAULT_MAX_ENTRIES)
    }

    fun getWhisperPerfLogMaxDays() : Int {
        return coerceLogMaxDays(getGlobalInt("whisper_perf_log_max_days", LOG_DEFAULT_MAX_DAYS))
    }

    fun setWhisperPerfLogMaxDays(value: Int) {
        putGlobalInt("whisper_perf_log_max_days", coerceLogMaxDays(value), LOG_DEFAULT_MAX_DAYS)
    }

    fun getMemoryUsageLogMaxEntries() : Int {
        return coerceLogMaxEntries(getGlobalInt("memory_usage_log_max_entries", LOG_DEFAULT_MAX_ENTRIES))
    }

    fun setMemoryUsageLogMaxEntries(value: Int) {
        putGlobalInt("memory_usage_log_max_entries", coerceLogMaxEntries(value), LOG_DEFAULT_MAX_ENTRIES)
    }

    fun getMemoryUsageLogMaxDays() : Int {
        return coerceLogMaxDays(getGlobalInt("memory_usage_log_max_days", LOG_DEFAULT_MAX_DAYS))
    }

    fun setMemoryUsageLogMaxDays(value: Int) {
        putGlobalInt("memory_usage_log_max_days", coerceLogMaxDays(value), LOG_DEFAULT_MAX_DAYS)
    }

    fun getTtsLifecycleLogMaxEntries() : Int {
        return coerceLogMaxEntries(getGlobalInt("tts_lifecycle_log_max_entries", LOG_DEFAULT_MAX_ENTRIES))
    }

    fun setTtsLifecycleLogMaxEntries(value: Int) {
        putGlobalInt("tts_lifecycle_log_max_entries", coerceLogMaxEntries(value), LOG_DEFAULT_MAX_ENTRIES)
    }

    fun getTtsLifecycleLogMaxDays() : Int {
        return coerceLogMaxDays(getGlobalInt("tts_lifecycle_log_max_days", LOG_DEFAULT_MAX_DAYS))
    }

    fun setTtsLifecycleLogMaxDays(value: Int) {
        putGlobalInt("tts_lifecycle_log_max_days", coerceLogMaxDays(value), LOG_DEFAULT_MAX_DAYS)
    }

    // Provider Failure Log (owner ruling, July 31 2026): records the raw
    // provider name and server error of failed replies so a consistently bad
    // provider can be spotted. Same configurable retention as the diagnostics.
    fun getProviderFailLogMaxEntries() : Int {
        return coerceLogMaxEntries(getGlobalInt("provider_fail_log_max_entries", LOG_DEFAULT_MAX_ENTRIES))
    }

    fun setProviderFailLogMaxEntries(value: Int) {
        putGlobalInt("provider_fail_log_max_entries", coerceLogMaxEntries(value), LOG_DEFAULT_MAX_ENTRIES)
    }

    fun getProviderFailLogMaxDays() : Int {
        return coerceLogMaxDays(getGlobalInt("provider_fail_log_max_days", LOG_DEFAULT_MAX_DAYS))
    }

    fun setProviderFailLogMaxDays(value: Int) {
        putGlobalInt("provider_fail_log_max_days", coerceLogMaxDays(value), LOG_DEFAULT_MAX_DAYS)
    }

    /** Whether failed replies are recorded to the Provider Failure Log. Off by
     *  default; independent of the always-on in-chat error display. */
    fun getLogChatFailures() : Boolean {
        return getGlobalBoolean("log_chat_failures", false)
    }

    fun setLogChatFailures(state: Boolean) {
        putGlobalBoolean("log_chat_failures", state, false)
    }

    // Response Lifecycle Log: the temporary, opt-in record of how each
    // user-visible AI reply ended (completion status, token usage, request
    // limits, termination). Off by default; same configurable retention as the
    // other diagnostic logs (default 50 entries / 7 days, clamped to the shared
    // ceilings). Each lifecycle block is trimmed as a whole entry.
    fun getResponseLifecycleLogMaxEntries() : Int {
        return coerceLogMaxEntries(getGlobalInt("response_lifecycle_log_max_entries", LOG_DEFAULT_MAX_ENTRIES))
    }

    fun setResponseLifecycleLogMaxEntries(value: Int) {
        putGlobalInt("response_lifecycle_log_max_entries", coerceLogMaxEntries(value), LOG_DEFAULT_MAX_ENTRIES)
    }

    fun getResponseLifecycleLogMaxDays() : Int {
        return coerceLogMaxDays(getGlobalInt("response_lifecycle_log_max_days", LOG_DEFAULT_MAX_DAYS))
    }

    fun setResponseLifecycleLogMaxDays(value: Int) {
        putGlobalInt("response_lifecycle_log_max_days", coerceLogMaxDays(value), LOG_DEFAULT_MAX_DAYS)
    }

    /** Whether each user-visible reply's lifecycle is recorded to the Response
     *  Lifecycle Log. Off by default; a temporary diagnostic that logs both
     *  completed and cut-off streams so they can be compared. */
    fun getResponseLifecycleLogging() : Boolean {
        return getGlobalBoolean("response_lifecycle_logging", false)
    }

    fun setResponseLifecycleLogging(state: Boolean) {
        putGlobalBoolean("response_lifecycle_logging", state, false)
    }

    /**
     * Reversible per-chat Archive pause. New turns continue accumulating in
     * the private transcript queue while paused, but the durable analysis
     * bookmark keeps the chat out of review. Resuming makes every turn after
     * the last successfully archived boundary eligible, including the whole
     * paused span. Distinct from the injection switch above, which controls
     * whether saved memory is used in this chat.
     * */
    fun isChatExcludedFromMemory() : Boolean {
        return getBoolean("memory_excluded", false)
    }

    fun setChatExcludedFromMemory(excluded: Boolean) {
        putBoolean("memory_excluded", excluded)
    }

    /** User-facing Archive choice. It remains per-chat and also seeds only the
     * next chat's initial Archive value; it is not a separate global toggle. */
    fun setChatArchiveEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("memory_excluded", !enabled).apply()
        putGlobalBoolean(LAST_CHAT_ARCHIVE_ENABLED, enabled, true)
    }

    private fun getLastChatArchiveEnabled(): Boolean =
        getGlobalBoolean(LAST_CHAT_ARCHIVE_ENABLED, true)

    /* ---------------------------------------------------------------------- *
     * Conversation-level memory policy (canonical recovery plan §4.4/§4.5,
     * Phase 1 item 12). This is ordinary conversation metadata, stored in the
     * chat's own settings file — separate from memories and never memory
     * provenance (item 13); it is never copied into a Pending or Active memory.
     *
     * Phase 1 only PERSISTS these. Retrieval and analysis do not yet read them,
     * so defaults resolve to "use the app/batch default" and current behavior
     * is unchanged. Enforcement lands in the retrieval and archiver phases.
     *
     * The access/extraction values are tri-states like [getChatMemoryEnabled]:
     * "" (unset → follow the effective default), "true", or "false". The empty
     * default is what keeps behavior unchanged until a later phase reads them.
     * ---------------------------------------------------------------------- */

    /** "Memories Used in This Conversation" — General pool On/Off (§4.4). */
    fun getChatMemoryGeneralAccessRaw() : String = getString("memory_general_access", "")

    fun setChatMemoryGeneralAccessRaw(value: String) { putString("memory_general_access", value) }

    /** "Memories Used in This Conversation" — current-companion pool On/Off
     *  (§4.4), meaningful only while a valid companion is assigned. */
    fun getChatMemoryCompanionAccessRaw() : String = getString("memory_companion_access", "")

    fun setChatMemoryCompanionAccessRaw(value: String) { putString("memory_companion_access", value) }

    /** "Create From This Conversation" — General Memories extraction (§4.5). */
    fun getChatAnalyzeGeneralRaw() : String = getString("analyze_general", "")

    fun setChatAnalyzeGeneralRaw(value: String) { putString("analyze_general", value) }

    /** "Create From This Conversation" — Companion Memories extraction (§4.5). */
    fun getChatAnalyzeCompanionRaw() : String = getString("analyze_companion", "")

    fun setChatAnalyzeCompanionRaw(value: String) { putString("analyze_companion", value) }

    /** "Create From This Conversation" — Model Rules extraction (§4.5). */
    fun getChatAnalyzeModelRulesRaw() : String = getString("analyze_model_rules", "")

    fun setChatAnalyzeModelRulesRaw(value: String) { putString("analyze_model_rules", value) }

    /** "Do Not Analyze This Conversation" (§4.5): prevents single or batch
     *  analysis until changed. Distinct from the capture-time exclusion above
     *  ([isChatExcludedFromMemory]); stored separately per the plan's list. */
    fun isChatDoNotAnalyze() : Boolean = getBoolean("analysis_do_not", false)

    fun setChatDoNotAnalyze(value: Boolean) { putBoolean("analysis_do_not", value) }

    /** Optional Analysis Note (§4.5): extraction-only guidance for an
     *  exceptional conversation. Sent only to the analyzer, never injected into
     *  chat, never saved as memory or displayed as provenance. Empty = none. */
    fun getChatAnalysisNote() : String = getString("analysis_note", "")

    fun setChatAnalysisNote(value: String) { putString("analysis_note", value) }

    /** Use Default vs Custom for this conversation's analysis policy (§4.5):
     *  "" (or "default") inherits the current app/batch defaults; "custom"
     *  stores this conversation's own selected streams. */
    fun getChatConversationPolicyMode() : String = getString("conversation_policy_mode", "")

    fun setChatConversationPolicyMode(value: String) { putString("conversation_policy_mode", value) }

    /** Preferred processing method for this conversation's analysis where
     *  approved (§8.2.2): "" (unset → ask/app default), "api", "computer", or
     *  "ask". Stored only; the run UI resolves it in a later phase. */
    fun getChatProcessingMethod() : String = getString("analysis_processing_method", "")

    fun setChatProcessingMethod(value: String) { putString("analysis_processing_method", value) }

    /**
     * Memory engine tier (global): "none" = character config + activation
     * prompts only; "lorebooks" = trigger-based lorebook tier (the default);
     * "associative" = associative search retrieval only (requires an
     * embedding model); "both" = lorebooks and associative search together.
     * The former "full" value is migrated to "both" on read.
     */
    fun getMemoryEngine() : String {
        val stored = getGlobalString("memory_engine", "lorebooks")
        if (stored == "full") return "both"
        return stored
    }

    fun setMemoryEngine(engine: String) {
        putGlobalString("memory_engine", engine)
    }

    /**
     * Memory Analysis Type (owner ruling, plan_one_page.md): which memory
     * system one Memory Assistant run creates suggestions for. "associative"
     * (default) files saved-memory drafts exactly as before; "lorebook" files
     * keyword-triggered lore book entry suggestions into the Lorebooks Pending
     * area instead. One run only ever creates one kind — there is no "both".
     * Global and sticky, defaulting to associative.
     */
    fun getMemoryAnalysisType() : String {
        val stored = getGlobalString("memory_analysis_type", "associative")
        return if (stored == "lorebook") "lorebook" else "associative"
    }

    fun setMemoryAnalysisType(type: String) {
        putGlobalString("memory_analysis_type", if (type == "lorebook") "lorebook" else "associative")
    }

    /**
     * Archivist model (global, decision D7): an endpoint profile id from
     * ApiEndpointPreferences plus a model name. Phase 4 uses it for the
     * standing-packet compressor; Phase 6's Archivist runs use the same
     * setting. Empty = not configured (the packet falls back to raw records).
     */
    fun getArchivistEndpointId() : String {
        return getGlobalString("archivist_endpoint_id", "")
    }

    fun setArchivistEndpointId(id: String) {
        putGlobalString("archivist_endpoint_id", id)
    }

    fun getArchivistModel() : String {
        return getGlobalString("archivist_model", "")
    }

    fun setArchivistModel(model: String) {
        putGlobalString("archivist_model", model)
    }

    /** Memory Assistant-only provider-routing override. Provider details still
     * live on the selected model favorite; this value chooses which of those
     * saved details the Memory Assistant uses without changing normal chat. */
    fun getArchivistRoutingType(): String {
        val stored = getGlobalString("archivist_routing_type", "automatic")
        return when (stored) {
            "preferred", "only" -> stored
            else -> "automatic"
        }
    }

    fun setArchivistRoutingType(type: String) {
        putGlobalString(
            "archivist_routing_type",
            when (type) {
                "preferred", "only" -> type
                else -> "automatic"
            }
        )
    }

    /* Memory Assistant tuning (owner spec, July 9 2026 —
     * `Memory System/memory_settings_reorg_spec.md`). All global. The cap and
     * minimum importance are ENFORCED IN CODE in the Archivist runner, never
     * only in the extraction prompt. User-facing name: "Memory Assistant"
     * (Archivist stays the internal name only). */

    /** Maximum Suggestions Per Conversation: 0 = off (no cap). */
    fun getArchivistMaxSuggestions(): Int =
        getGlobalString("archivist_max_suggestions", "0").toIntOrNull()?.coerceAtLeast(0) ?: 0

    fun setArchivistMaxSuggestions(value: Int) {
        putGlobalString("archivist_max_suggestions", value.coerceAtLeast(0).toString())
    }

    /** Conversation Amount Per Request. Values are transcript-token targets,
     * not total request sizes; request headroom is applied by the runner. */
    fun getArchivistConversationAmount(): String =
        org.teslasoft.assistant.preferences.memory.archivist.ArchivistRequestBudget
            .normalizeChoice(getGlobalString("archivist_conversation_amount", "auto"))

    fun setArchivistConversationAmount(value: String) {
        putGlobalString(
            "archivist_conversation_amount",
            org.teslasoft.assistant.preferences.memory.archivist.ArchivistRequestBudget
                .normalizeChoice(value)
        )
    }

    /** Custom Conversation Tokens Per Request. Invalid legacy values fall back
     * to the approved suggested value; the UI rejects new values below 1,000. */
    fun getArchivistCustomConversationTokens(): Int {
        val budget = org.teslasoft.assistant.preferences.memory.archivist.ArchivistRequestBudget
        return budget.validateCustomTarget(
            getGlobalString("archivist_custom_conversation_tokens", "8000").toIntOrNull()
        ) ?: budget.CUSTOM_SUGGESTED_TOKENS
    }

    fun setArchivistCustomConversationTokens(value: Int) {
        val budget = org.teslasoft.assistant.preferences.memory.archivist.ArchivistRequestBudget
        val valid = budget.validateCustomTarget(value) ?: budget.CUSTOM_SUGGESTED_TOKENS
        putGlobalString("archivist_custom_conversation_tokens", valid.toString())
    }

    /** Analysis temperature, 0.0–2.0. Recommended/default 0.3. */
    fun getArchivistTemperature(): Float =
        getGlobalString("archivist_temperature", "0.3").toFloatOrNull()?.coerceIn(0.0f, 2.0f) ?: 0.3f

    fun setArchivistTemperature(value: Float) {
        putGlobalString("archivist_temperature", value.coerceIn(0.0f, 2.0f).toString())
    }

    /** Minimum Importance a draft must reach to be filed. 0 = No Minimum (the
     *  default): the Memory Assistant suggests whatever it judges worth
     *  suggesting, with no importance floor. 1–5 keep the older numeric floor. */
    fun getArchivistMinImportance(): Int =
        getGlobalString("archivist_min_importance", "0").toIntOrNull()?.coerceIn(0, 5) ?: 0

    fun setArchivistMinImportance(value: Int) {
        putGlobalString("archivist_min_importance", value.coerceIn(0, 5).toString())
    }

    // The former "archivist_card_suggestions" toggle is retired (Phase 2 review):
    // analyzer card-placement suggestions were removed from the response contract,
    // the candidate, the filer, and the UI. The persisted key is left as dormant
    // compatibility baggage until the Phase 10 cleanup — it has no UI, and no
    // runtime reader or writer.

    /** Custom Associative Memory (extraction) prompt; "" = use the built-in
     *  ArchivistPrompt.SYSTEM (that type's Reset action clears back to ""). */
    fun getArchivistCustomPrompt(): String =
        getGlobalString("archivist_custom_prompt", "")

    fun setArchivistCustomPrompt(value: String) {
        putGlobalString("archivist_custom_prompt", value)
    }

    /** Custom Lorebook Memory prompt; "" = use the built-in
     *  ArchivistPrompt.LOREBOOK_SYSTEM (that type's Reset action clears back to
     *  ""). Stored separately from the Associative prompt because the two
     *  analysis types require different output schemas — the Associative prompt
     *  is never used for a Lorebook run. */
    fun getArchivistLorebookPrompt(): String =
        getGlobalString("archivist_lorebook_prompt", "")

    fun setArchivistLorebookPrompt(value: String) {
        putGlobalString("archivist_lorebook_prompt", value)
    }

    /**
     * Per-chat scene selection (Phase 4, D8): the active world, roleplay
     * character and user persona for this chat, as memory-store ids; "" =
     * none. Prefs are the source of truth — the enforcer mirrors them into
     * the store's app_state at generation time. All three are in the
     * auto-naming copy block (they'd silently vanish on rename otherwise).
     */
    fun getChatWorldId() : String {
        return getString("memory_world_id", "")
    }

    fun setChatWorldId(id: String) {
        putString("memory_world_id", id)
    }

    fun getChatRoleplayCharacterId() : String {
        return getString("memory_roleplay_character_id", "")
    }

    fun setChatRoleplayCharacterId(id: String) {
        putString("memory_roleplay_character_id", id)
    }

    fun getChatUserPersonaId() : String {
        return getString("memory_user_persona_id", "")
    }

    fun setChatUserPersonaId(id: String) {
        putString("memory_user_persona_id", id)
    }

    /**
     * Per-chat Project scope selector (owner_approved_rules §4, Revision 3).
     * Empty = no project. Like the scene selectors it lives in the auto-naming
     * copy block. Selection is a ranking BOOST, never an eligibility gate:
     * project memories retrieve on relevance even with none selected (Stage 3.5).
     */
    fun getChatProjectId() : String {
        return getString("memory_project_id", "")
    }

    fun setChatProjectId(id: String) {
        putString("memory_project_id", id)
    }

    /**
     * Documents the user has attached but not yet sent (the Includes strip
     * above the message box), as the JSON produced by
     * `ChatInclude.listToJson`. Empty = nothing attached.
     *
     * Only PENDING attachments live here. Once a message is sent its
     * attachments move into that message's own record inside the chat history
     * blob, so they are saved atomically with the text they belong to and are
     * carried by a rename with the rest of the history. This key exists purely
     * so an attachment picked before the app was closed is still waiting when
     * it reopens.
     */
    fun getPendingIncludes() : String {
        return getString("pending_includes", "")
    }

    fun setPendingIncludes(json: String, synchronous: Boolean = false) {
        if (synchronous) {
            preferences.edit(commit = true) {
                putString("pending_includes", json)
            }
        } else {
            putString("pending_includes", json)
        }
    }

    /**
     * Per-chat Campaign selector (owner_approved_rules §3/§12 rev 3, Stage 3.0).
     * Empty = none. Selecting a campaign is the owner-chosen explicit signal
     * that a chat is inside that playthrough: it makes campaign-scoped memories
     * eligible, and it defines the narrator/GM path — the campaign's GM
     * companion being the chat's active companion is what lets companion
     * memories into roleplay. In the auto-naming copy block like the rest.
     */
    fun getChatCampaignId() : String {
        return getString("memory_campaign_id", "")
    }

    fun setChatCampaignId(id: String) {
        putString("memory_campaign_id", id)
    }

    /**
     * Model rules (owner_approved_rules §11, Revision 6). Rules apply
     * automatically to any chat whose endpoint id and exact model id match,
     * ON by default. Preserved legacy strings use their compatibility path.
     * Two toggles gate that, mirroring the "Use memory" pattern:
     *  - a GLOBAL default ("Automatically Apply Model Rules", AI System
     *    Settings) — default on;
     *  - a PER-CHAT override ("Apply Model Rules", Quick Settings) that starts
     *    from the global default and can turn rules off (or on) for one chat.
     * The per-chat value is in the auto-naming copy block like every other
     * per-chat setting. No profiles — the model string on each rule decides
     * what matches (see ModelRuleMatcher / MemoryStore).
     */
    fun getAutoApplyModelRules() : Boolean {
        return getGlobalBoolean("auto_apply_model_rules", true)
    }

    fun setAutoApplyModelRules(state: Boolean) {
        putGlobalBoolean("auto_apply_model_rules", state, true)
    }

    fun getChatApplyModelRules() : Boolean {
        return getBoolean("apply_model_rules", getAutoApplyModelRules())
    }

    fun setChatApplyModelRules(state: Boolean) {
        putBoolean("apply_model_rules", state, getAutoApplyModelRules())
    }

    /**
     * "Allow active companion memories in roleplay" (owner_approved_rules §3,
     * rev 3 — owner-added toggle, global, default OFF). OFF: companion memories
     * do not enter RP/campaign mode beyond the narrator/GM path. ON: the active
     * chat companion's approved active memories may participate in retrieval
     * during RP/campaign mode (normal scope/status/relevance/cooldown rules
     * still apply — participation, never forced injection).
     */
    fun getAllowCompanionMemoriesInRoleplay() : Boolean {
        return getGlobalBoolean("memory_companion_in_roleplay", false)
    }

    fun setAllowCompanionMemoriesInRoleplay(allowed: Boolean) {
        putGlobalBoolean("memory_companion_in_roleplay", allowed)
    }

    /* ------------------------------------------------------------------
     * Image generation (image-generation-rebuild-plan.md §5/§14). Every
     * image-generation setting is app-wide (owner ruling, 2026-07-29),
     * like the Summarizer settings: one configuration for the whole app.
     * ImageGenerationMigration seeds these once from the default settings
     * profile; the legacy per-chat copies (imageModel, resolution,
     * function_calling) stop being read as the rebuild rewires each path,
     * and are removed only after migration tests plus a stable release
     * (§14). The legacy imagine_command copy was removed outright — it had
     * no remaining reader anywhere in the app.
     * ------------------------------------------------------------------ */

    /** Let the AI Create Images: whether the create_image tool is offered
     *  to the conversation model. Independent from `/imagine`. */
    fun getAiCreateImagesEnabled(): Boolean =
        getGlobalBoolean("image_gen_let_ai_create", false)

    fun setAiCreateImagesEnabled(value: Boolean) {
        putGlobalBoolean("image_gen_let_ai_create", value)
    }

    /** Ask Before Creating (default on): the confirmation card shown before
     *  a model-initiated image is generated. */
    fun getAskBeforeAiImages(): Boolean =
        getGlobalBoolean("image_gen_ask_before_creating", true)

    fun setAskBeforeAiImages(value: Boolean) {
        putGlobalBoolean("image_gen_ask_before_creating", value, true)
    }

    /** Image Service endpoint profile id (global). "" = not configured. It
     *  may differ from any conversation endpoint (§3). */
    fun getImageGeneratorEndpointId(): String =
        getGlobalString("image_gen_endpoint_id", "")

    fun setImageGeneratorEndpointId(id: String) {
        putGlobalString("image_gen_endpoint_id", id)
    }

    /** Image Model on that endpoint. "" = not configured. */
    fun getImageGeneratorModel(): String =
        getGlobalString("image_gen_model", "")

    fun setImageGeneratorModel(model: String) {
        putGlobalString("image_gen_model", model)
    }

    /** Default Shape (§5/§11). Unknown stored values read as AUTOMATIC. */
    fun getImageGeneratorShape(): org.teslasoft.assistant.imagegen.ImageShape =
        org.teslasoft.assistant.imagegen.ImageShape.fromStored(
            getGlobalString("image_gen_default_shape", "automatic")
        )

    fun setImageGeneratorShape(shape: org.teslasoft.assistant.imagegen.ImageShape) {
        putGlobalString("image_gen_default_shape", shape.storedValue, "automatic")
    }

    /** Default Quality (§5/§11). Unknown stored values read as AUTOMATIC. */
    fun getImageGeneratorQuality(): org.teslasoft.assistant.imagegen.ImageQuality =
        org.teslasoft.assistant.imagegen.ImageQuality.fromStored(
            getGlobalString("image_gen_default_quality", "automatic")
        )

    fun setImageGeneratorQuality(quality: org.teslasoft.assistant.imagegen.ImageQuality) {
        putGlobalString("image_gen_default_quality", quality.storedValue, "automatic")
    }

    /** App-wide Enable `/imagine` (default on). The only reader of this
     *  feature's on/off state; the old per-chat copy is gone. */
    fun getImagineCommandGlobal(): Boolean =
        getGlobalBoolean("image_gen_imagine_command", true)

    fun setImagineCommandGlobal(value: Boolean) {
        putGlobalBoolean("image_gen_imagine_command", value, true)
    }

    /** Image Gallery spec section 9. Off is the fail-safe default. Turning it
     * on only offers Delete All in a later confirmation; it never deletes by
     * itself. */
    fun getDeleteImagesWithChat(): Boolean =
        getGlobalBoolean("image_gen_delete_images_with_chat", false)

    fun setDeleteImagesWithChat(value: Boolean) {
        putGlobalBoolean("image_gen_delete_images_with_chat", value)
    }

    /** §14 seeding marker: stamped only after every global value above has
     *  been written by ImageGenerationMigration. */
    fun getImageGenerationSeeded(): Boolean =
        getGlobalBoolean("image_gen_settings_seeded", false)

    fun setImageGenerationSeeded() {
        putGlobalBoolean("image_gen_settings_seeded", true)
    }

    /** §13 Image Generation Error Recording: off until the user enables it.
     *  Actionable errors appear in chat either way; this gates only the
     *  Image Generation Errors log entries. */
    fun getImageGenErrorLogging(): Boolean =
        getGlobalBoolean("image_gen_error_logging", false)

    fun setImageGenErrorLogging(value: Boolean) {
        putGlobalBoolean("image_gen_error_logging", value)
    }

    /** §13 Successful Image Tracking: off until the user enables it —
     *  successes are never recorded automatically. */
    fun getSuccessfulImageTracking(): Boolean =
        getGlobalBoolean("image_gen_success_tracking", false)

    fun setSuccessfulImageTracking(value: Boolean) {
        putGlobalBoolean("image_gen_success_tracking", value)
    }

    /** §13 Image Generation Log retention: Maximum Image Information Saved.
     *  Same logic as the error-log retention fields with one difference —
     *  ZERO means unlimited (only here, never on the error logs). */
    fun getImageGenLogMaxEntries(): Int =
        coerceImageGenRetention(
            getGlobalInt("image_gen_log_max_entries", LOG_DEFAULT_MAX_ENTRIES),
            LOG_MAX_ENTRIES_LIMIT
        )

    fun setImageGenLogMaxEntries(value: Int) {
        putGlobalInt(
            "image_gen_log_max_entries",
            coerceImageGenRetention(value, LOG_MAX_ENTRIES_LIMIT),
            LOG_DEFAULT_MAX_ENTRIES
        )
    }

    /** §13 Image Generation Log retention: Maximum Days Saved; zero means
     *  unlimited. */
    fun getImageGenLogMaxDays(): Int =
        coerceImageGenRetention(
            getGlobalInt("image_gen_log_max_days", LOG_DEFAULT_MAX_DAYS),
            LOG_MAX_DAYS_LIMIT
        )

    fun setImageGenLogMaxDays(value: Int) {
        putGlobalInt(
            "image_gen_log_max_days",
            coerceImageGenRetention(value, LOG_MAX_DAYS_LIMIT),
            LOG_DEFAULT_MAX_DAYS
        )
    }

    /* ------------------------------------------------------------------
     * Conversation summarizer (conversation-summary-plan.md §5 +
     * conversation-summary-errors.md). Global settings mirror the Memory
     * Assistant pattern (endpoint profile id + model + tuning values);
     * per-chat state lives in this chat's settings file so it is encrypted
     * like the messages, copied wholesale by a rename, and carried by both
     * backup formats. Deletion clears it via
     * ChatPreferences.clearSummarizerState (decision 9).
     * ------------------------------------------------------------------ */

    /** Summarizer endpoint profile id (global). "" = not configured. */
    fun getSummarizerEndpointId(): String =
        getGlobalString("summarizer_endpoint_id", "")

    fun setSummarizerEndpointId(id: String) {
        putGlobalString("summarizer_endpoint_id", id)
    }

    /** Explicit Summary Model name on that endpoint. "" = not configured. */
    fun getSummarizerModel(): String =
        getGlobalString("summarizer_model", "")

    fun setSummarizerModel(model: String) {
        putGlobalString("summarizer_model", model)
    }

    /** Summarizer-only provider-routing override. Provider details remain on
     * the selected model favorite and ordinary chat routing is untouched. */
    fun getSummarizerRoutingType(): String {
        val stored = getGlobalString("summarizer_routing_type", "automatic")
        return when (stored) {
            "preferred", "only" -> stored
            else -> "automatic"
        }
    }

    fun setSummarizerRoutingType(type: String) {
        putGlobalString(
            "summarizer_routing_type",
            when (type) {
                "preferred", "only" -> type
                else -> "automatic"
            }
        )
    }

    /** Complete Messages default (owner-approved default: 20) — the
     *  recent-window count new chats start with. */
    fun getSummarizerDefaultWindow(): Int =
        getGlobalString("summarizer_default_window", "20").toIntOrNull()?.coerceAtLeast(1) ?: 20

    fun setSummarizerDefaultWindow(value: Int) {
        putGlobalString("summarizer_default_window", value.coerceAtLeast(1).toString())
    }

    /** Summary Length in words (decision 13; default 300). */
    fun getSummarizerLength(): Int =
        getGlobalString("summarizer_length", "300").toIntOrNull()?.coerceAtLeast(10) ?: 300

    fun setSummarizerLength(value: Int) {
        putGlobalString("summarizer_length", value.coerceAtLeast(10).toString())
    }

    /** Whether new chats start with the summarizer on (decision 2 toggle). */
    fun getSummarizerOnForNewChats(): Boolean =
        getGlobalString("summarizer_on_new_chats", "false") == "true"

    fun setSummarizerOnForNewChats(value: Boolean) {
        putGlobalString("summarizer_on_new_chats", value.toString())
    }

    /** Selected prompt slot, 0–4 (decision 6). */
    fun getSummarizerSelectedSlot(): Int =
        getGlobalString("summarizer_selected_slot", "0").toIntOrNull()?.coerceIn(0, 4) ?: 0

    fun setSummarizerSelectedSlot(slot: Int) {
        putGlobalString("summarizer_selected_slot", slot.coerceIn(0, 4).toString())
    }

    /** A slot's display name; shipped names for slots one and two, "Slot N"
     *  supplied by the caller for the empty slots. "" = never renamed. */
    fun getSummarizerSlotName(slot: Int): String =
        getGlobalString("summarizer_slot_name_$slot", "")

    fun setSummarizerSlotName(slot: Int, name: String) {
        putGlobalString("summarizer_slot_name_$slot", name)
    }

    /** A slot's stored prompt text. "" = never written (slots one and two
     *  then read as their shipped prompts at the call site). */
    fun getSummarizerSlotPrompt(slot: Int): String =
        getGlobalString("summarizer_slot_prompt_$slot", "")

    fun setSummarizerSlotPrompt(slot: Int, prompt: String) {
        putGlobalString("summarizer_slot_prompt_$slot", prompt)
    }

    /** Slot-selection recency (newest first, CSV of slot indexes) — backs the
     *  empty-prompt fallback rule of decision 7. */
    fun getSummarizerSlotRecency(): String =
        getGlobalString("summarizer_slot_recency", "")

    fun setSummarizerSlotRecency(csv: String) {
        putGlobalString("summarizer_slot_recency", csv)
    }

    /** The single Image Summary Prompt (global). "" = never written; the call
     *  site then reads the shipped SummarizerPrompts.IMAGE_SUMMARY default so a
     *  summary can never run on empty instructions. */
    fun getImageSummaryPrompt(): String =
        getGlobalString("image_summary_prompt", "")

    fun setImageSummaryPrompt(prompt: String) {
        putGlobalString("image_summary_prompt", prompt)
    }

    /** Manual compaction cancellation policy. False is the conservative,
     * atomic default: cancelling discards every result from that operation. */
    fun getSavePartialCompactionOnCancel(): Boolean =
        getGlobalString("save_partial_compaction_on_cancel", "false") == "true"

    fun setSavePartialCompactionOnCancel(value: Boolean) {
        putGlobalString("save_partial_compaction_on_cancel", value.toString())
    }

    /** Per-chat Use Summarizer state: "" = never stamped, else "true"/"false".
     *  Stamped once per chat (see ChatActivity) so flipping the new-chats
     *  default later never silently changes what an existing chat sends. */
    fun getChatUseSummarizerRaw(): String =
        getString("use_summarizer", "")

    fun setChatUseSummarizerRaw(value: String) {
        putString("use_summarizer", value)
    }

    fun getChatUseSummarizer(): Boolean = getChatUseSummarizerRaw() == "true"

    fun setChatUseSummarizer(enabled: Boolean) {
        putString("use_summarizer", if (enabled) "true" else "false")
    }

    /** Whether regular requests currently use the persisted summary/compacted
     * projection. Turning this off sends the complete canonical transcript but
     * deliberately preserves the summary, bookmark, and compaction marker. */
    fun getUseSummarizedConversationProjection(): Boolean =
        getString("use_summarized_conversation_projection", "true") != "false"

    fun setUseSummarizedConversationProjection(enabled: Boolean) {
        putString("use_summarized_conversation_projection", enabled.toString())
    }

    fun getSummarizerCatchUpPending(): Boolean =
        getString("summarizer_catch_up_pending", "false") == "true"

    fun setSummarizerCatchUpPending(value: Boolean) {
        putString("summarizer_catch_up_pending", value.toString())
    }

    /** Last condensed form written for the summary-window action label. */
    fun getCondensedConversationKind(): String =
        getString("condensed_conversation_kind", CONDENSED_KIND_SUMMARY)

    fun setCondensedConversationKind(kind: String) {
        putString(
            "condensed_conversation_kind",
            if (kind == CONDENSED_KIND_COMPACTION) kind else CONDENSED_KIND_SUMMARY
        )
    }

    /** Per-chat Complete Messages window; "" = follow the global default. */
    fun getChatSummarizerWindow(): Int {
        val raw = getString("summarizer_window", "")
        return raw.toIntOrNull()?.coerceAtLeast(1) ?: getSummarizerDefaultWindow()
    }

    fun setChatSummarizerWindow(value: Int) {
        putString("summarizer_window", value.coerceAtLeast(1).toString())
    }

    /** The chat's rolling summary text ("" = none yet). */
    fun getSummarizerSummary(): String =
        getString("summarizer_summary", "")

    /** Projection contract that produced the persisted rolling summary. */
    fun getSummarizerProjectionVersion(): Int =
        getString("summarizer_projection_version", "0").toIntOrNull() ?: 0

    /**
     * One-time bridge for conversations condensed before regeneration locks
     * existed. Capture the live summary/compaction bookmarks before a future
     * projection migration is allowed to clear them.
     */
    private fun ensureCondensedRegenerationLockMigration(): Boolean {
        if (getString("condensed_regeneration_lock_migrated", "false") == "true") {
            return true
        }
        val summaryBoundary = maxOf(
            getString("summary_regeneration_lock_boundary", "0").toIntOrNull() ?: 0,
            getSummarizerFoldedCount()
        )
        val compactionBoundary = maxOf(
            getString("compaction_regeneration_lock_boundary", "0").toIntOrNull() ?: 0,
            getManualCompactionBoundary()
        )
        return try {
            preferences.edit()
                .putString("summary_regeneration_lock_boundary", summaryBoundary.toString())
                .putString("compaction_regeneration_lock_boundary", compactionBoundary.toString())
                .putString("condensed_regeneration_lock_migrated", "true")
                .commit()
        } catch (_: Exception) {
            false
        }
    }

    fun getSummaryRegenerationLockBoundary(): Int {
        ensureCondensedRegenerationLockMigration()
        return getString("summary_regeneration_lock_boundary", "0")
            .toIntOrNull()?.coerceAtLeast(0) ?: 0
    }

    fun getCompactionRegenerationLockBoundary(): Int {
        ensureCondensedRegenerationLockMigration()
        return getString("compaction_regeneration_lock_boundary", "0")
            .toIntOrNull()?.coerceAtLeast(0) ?: 0
    }

    /** A usable batch permanently fixes its processed prefix into history. */
    fun advanceSummaryRegenerationLockBoundary(value: Int): Boolean =
        advanceCondensedRegenerationLockBoundary(
            "summary_regeneration_lock_boundary", value
        )

    /** A usable manual batch permanently fixes its processed prefix. */
    fun advanceCompactionRegenerationLockBoundary(value: Int): Boolean =
        advanceCondensedRegenerationLockBoundary(
            "compaction_regeneration_lock_boundary", value
        )

    private fun advanceCondensedRegenerationLockBoundary(key: String, value: Int): Boolean {
        if (!ensureCondensedRegenerationLockMigration()) return false
        val current = getString(key, "0").toIntOrNull()?.coerceAtLeast(0) ?: 0
        val next = maxOf(current, value.coerceAtLeast(0))
        if (next == current) return true
        return try {
            preferences.edit().putString(key, next.toString()).commit()
        } catch (_: Exception) {
            false
        }
    }

    /** Direct realignment after canonical messages inside a locked prefix are deleted. */
    fun setSummaryRegenerationLockBoundary(value: Int) {
        ensureCondensedRegenerationLockMigration()
        putString("summary_regeneration_lock_boundary", value.coerceAtLeast(0).toString())
    }

    fun setCompactionRegenerationLockBoundary(value: Int) {
        ensureCondensedRegenerationLockMigration()
        putString("compaction_regeneration_lock_boundary", value.coerceAtLeast(0).toString())
    }

    /**
     * Invalidates only incompatible derived Summarizer state. Canonical chat
     * history and its Include ownership are untouched, so the next cycle
     * safely rebuilds from conversation text alone. The independently projected
     * Include layer and its user-selected forms remain untouched.
     * A failed commit is reported to the caller, which must omit the stale
     * summary and use a zero bookmark rather than risk duplicate payloads.
     */
    fun ensureSummarizerProjectionCompatibility(): Boolean {
        // VERSION 3 keeps attachment payloads out of summary/compaction. Lock
        // the already-condensed history before invalidating older derived text.
        if (!ensureCondensedRegenerationLockMigration()) return false
        if (getSummarizerProjectionVersion() == SummarizerProjectionContract.VERSION) {
            return true
        }
        return try {
            preferences.edit()
                .putString("summarizer_summary", "")
                .putString("summarizer_folded", "0")
                .putString("summarizer_over_length", "false")
                .putString("summarizer_episode", "")
                .putString("manual_compaction_boundary", "0")
                .putString(
                    "summarizer_projection_version",
                    SummarizerProjectionContract.VERSION.toString()
                )
                .commit()
        } catch (_: Exception) {
            false
        }
    }

    /** The fold-in bookmark: how many of the chat's oldest stored messages
     *  are already folded into the summary. */
    fun getSummarizerFoldedCount(): Int =
        getString("summarizer_folded", "0").toIntOrNull()?.coerceAtLeast(0) ?: 0

    /** Over-length marker (owner ruling): the saved summary exceeded the
     *  configured length + 10%, so the next fold-in must compress it. */
    fun getSummarizerOverLength(): Boolean =
        getString("summarizer_over_length", "") == "true"

    /** The ongoing failure-episode category name ("" = last fold-in
     *  succeeded); drives error dedup and the once-per-episode sound. */
    fun getSummarizerEpisode(): String =
        getString("summarizer_episode", "")

    fun setSummarizerEpisode(value: String) {
        putString("summarizer_episode", value)
    }

    /** The chat's Summarizer Errors log (JSON via SummarizerErrorLog). */
    fun getSummarizerErrors(): String =
        getString("summarizer_errors", "")

    fun setSummarizerErrors(json: String) {
        putString("summarizer_errors", json)
    }

    /**
     * Whether the chat has a summarizer/compaction failure the user has NOT yet
     * opened. Drives the top-bar error badge's look: a new failure sets it so
     * the badge shows as an alert (red number on white, theme-independent),
     * opening the errors list clears it so the badge relaxes to its neutral
     * reminder look while the log still has entries (owner ruling, Aug 31 2026).
     */
    fun getSummarizerErrorsUnseen(): Boolean =
        getString("summarizer_errors_unseen", "false") == "true"

    fun setSummarizerErrorsUnseen(value: Boolean) {
        putString("summarizer_errors_unseen", if (value) "true" else "false")
    }

    /**
     * Commits a successful fold-in atomically: the updated summary, the
     * advanced bookmark, the over-length marker, and the episode reset are
     * one synchronous commit, so the summary and bookmark can never be saved
     * without each other (errors doc §2.13).
     *
     * @return false when the commit failed — the caller must treat the
     *         fold-in as unsaved and leave its in-memory state unchanged.
     */
    fun commitSummarizerFoldIn(summary: String, foldedCount: Int, overLength: Boolean): Boolean {
        return try {
            preferences.edit()
                .putString("summarizer_summary", summary)
                .putString("summarizer_folded", foldedCount.coerceAtLeast(0).toString())
                .putString("summarizer_over_length", if (overLength) "true" else "false")
                .putString("summarizer_episode", "")
                .putString("condensed_conversation_kind", CONDENSED_KIND_SUMMARY)
                .putString(
                    "summarizer_projection_version",
                    SummarizerProjectionContract.VERSION.toString()
                )
                .commit()
        } catch (_: Exception) {
            false
        }
    }

    /** Restores the exact pre-operation derived state after an atomic cancel. */
    fun restoreSummarizerState(
        summary: String,
        foldedCount: Int,
        overLength: Boolean,
        episode: String,
        condensedKind: String
    ): Boolean = try {
        preferences.edit()
            .putString("summarizer_summary", summary)
            .putString("summarizer_folded", foldedCount.coerceAtLeast(0).toString())
            .putString("summarizer_over_length", overLength.toString())
            .putString("summarizer_episode", episode)
            .putString("condensed_conversation_kind", condensedKind)
            .putString(
                "summarizer_projection_version",
                SummarizerProjectionContract.VERSION.toString()
            )
            .commit()
    } catch (_: Exception) {
        false
    }

    /**
     * Commits a user-requested Compact operation as one unit. The rolling
     * summary, fold bookmark, and visible manual boundary must never describe
     * different snapshots after a failure or cancellation.
     */
    fun commitManualCompaction(
        summary: String,
        foldedCount: Int,
        overLength: Boolean,
        boundaryCount: Int
    ): Boolean {
        return try {
            preferences.edit()
                .putString("summarizer_summary", summary)
                .putString("summarizer_folded", foldedCount.coerceAtLeast(0).toString())
                .putString("summarizer_over_length", if (overLength) "true" else "false")
                .putString("summarizer_episode", "")
                .putString("manual_compaction_boundary", boundaryCount.coerceAtLeast(0).toString())
                .putString("condensed_conversation_kind", CONDENSED_KIND_COMPACTION)
                .putString(
                    "summarizer_projection_version",
                    SummarizerProjectionContract.VERSION.toString()
                )
                .commit()
        } catch (_: Exception) {
            false
        }
    }

    /** Number of oldest canonical messages through the latest manual marker. */
    fun getManualCompactionBoundary(): Int =
        getString("manual_compaction_boundary", "0")
            .toIntOrNull()?.coerceAtLeast(0) ?: 0

    /** Direct boundary realignment after a canonical-history deletion. */
    fun setManualCompactionBoundary(value: Int) {
        putString("manual_compaction_boundary", value.coerceAtLeast(0).toString())
    }

    /** User edit of the summary text (bookmark untouched), committed
     *  synchronously so a hand correction is never lost to a process kill. */
    fun commitSummarizerSummaryEdit(summary: String): Boolean {
        // Never let text loaded from a pre-6.2 summary editor session bless
        // stale attachment-derived material as current. The UI establishes
        // compatibility before loading the field; any other caller that did
        // not do so gets a safe invalidation and must retry from fresh text.
        if (getSummarizerProjectionVersion() != SummarizerProjectionContract.VERSION) {
            ensureSummarizerProjectionCompatibility()
            return false
        }
        return try {
            preferences.edit()
                .putString("summarizer_summary", summary)
                .putString("summarizer_over_length", "false")
                .putString(
                    "summarizer_projection_version",
                    SummarizerProjectionContract.VERSION.toString()
                )
                .commit()
        } catch (_: Exception) {
            false
        }
    }

    /** Adjusts the fold-in bookmark when a stored message before it is
     *  deleted, so the folded prefix stays aligned with the stored list. */
    fun decrementSummarizerFoldedCount() {
        val current = getSummarizerFoldedCount()
        if (current > 0) {
            putString("summarizer_folded", (current - 1).toString())
        }
    }

    /** Direct bookmark realignment for bulk deletions. */
    fun setSummarizerFoldedCount(value: Int) {
        putString("summarizer_folded", value.coerceAtLeast(0).toString())
    }

    /**
     * Get logit biases config ID
     *
     * @return logit biases config ID
     * */
    fun getLogitBiasesConfigId() : String {
        return getString("logit_biases_config_id", "")
    }

    /**
     * Set logit biases config ID
     *
     * @param id logit biases config ID
     * */
    fun setLogitBiasesConfigId(id: String) {
        putString("logit_biases_config_id", id)
    }

    /**
     * Retrieves the old (non-encrypted) API key from the shared preferences.
     *
     * @return The old API key or an empty String if not found.
     */
    @Deprecated("Should be removed in future releases")
    fun getOldApiKey() : String {
        return getString("api_key", "")
    }

    /**
     * Sets the API key to the value of the old API key, if it exists.
     * This method is used to migrate to a new API key storage system.
     * It retrieves the old API key from the preferences, sets it to the new API key storage system,
     * and removes it from the old storage system.
     *
     * @param context The context used to access the preferences.
     */
    fun secureApiKey(context: Context) {
        if (getOldApiKey() != "") {
            setApiKey(getOldApiKey(), context)
            preferences.edit { remove("api_key") }
        }
    }
}
