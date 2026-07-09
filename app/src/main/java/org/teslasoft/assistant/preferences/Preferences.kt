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
import org.teslasoft.assistant.util.Hash
import androidx.core.content.edit

class Preferences private constructor(private var preferences: SharedPreferences, private var gp: SharedPreferences, private var chatId: String) {
    companion object {
        fun getPreferences(context: Context, xchatId: String) : Preferences {
            return Preferences(SecurePrefs.get(context, "settings.$xchatId"), context.getSharedPreferences("settings", Context.MODE_PRIVATE), xchatId)
        }
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
    * Show chat errors
    *
    * @return show chat errors
    * */
    fun showChatErrors() : Boolean {
        return getGlobalBoolean("show_chat_errors", true)
    }

    /**
     * Set show chat errors
     *
     * @param state show chat errors
     * */
    fun setShowChatErrors(state: Boolean) {
        putGlobalBoolean("show_chat_errors", state, true)
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

    /**
     * Retrieves the image model name from the shared preferences.
     *
     * Dalle 2 and 3 are shutdown on May 12, 2026
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
     * Retrieves the silence mode status from the shared preferences.
     *
     * @return The silence mode status, true if enabled or false otherwise.
     */
    fun getSilence() : Boolean {
        return getBoolean("silence_mode", false)
    }

    /**
     * Sets silence mode.
     *
     * @param mode mode.
     */
    fun setSilence(mode: Boolean) {
        putBoolean("silence_mode", mode)
    }

    /**
     * Retrieves the always speak mode status from the shared preferences.
     *
     * @return The always speak mode status, true if enabled or false otherwise.
     */
    fun getNotSilence() : Boolean {
        return getBoolean("always_speak_mode", false)
    }

    /**
     * Sets always speak mode.
     *
     * @param mode mode.
     */
    fun setNotSilence(mode: Boolean) {
        putBoolean("always_speak_mode", mode)
    }

    /**
     * Retrieves the function calling status from the shared preferences.
     *
     * @return The function calling status, true if enabled or false otherwise.
     */
    fun getFunctionCalling() : Boolean {
        return getBoolean("function_calling", false)
    }

    /**
     * Sets function calling mode.
     *
     * @param mode mode.
     */
    fun setFunctionCalling(mode: Boolean) {
        putBoolean("function_calling", mode)
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
     * Retrieves the imagine command status from the shared preferences.
     *
     * @return The imagine command status, true if enabled or false otherwise.
     */
    fun getImagineCommand() : Boolean {
        return getBoolean("imagine_command", true)
    }

    /**
     * Enable/disable imagine command.
     *
     * @param mode mode.
     */
    fun setImagineCommand(mode: Boolean) {
        putBoolean("imagine_command", mode)
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

    /**
     * Retrieves the monochrome background for chat list status from the shared preferences.
     *
     * @return The monochrome background for chat list status, true if enabled or false otherwise.
     */
    fun getMonochromeBackgroundForChatList() : Boolean {
        return getGlobalBoolean("monochrome_background_for_chat_list", false)
    }

    /**
     * Enable/disable monochrome background for chat list.
     *
     * @param state mode.
     */
    fun setMonochromeBackgroundForChatList(state: Boolean) {
        putGlobalBoolean("monochrome_background_for_chat_list", state)
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
     * Retrieves the audio model from the shared preferences.
     *
     * Recognized values:
     *  - "google"        — Android on-device dictation (default)
     *  - "whisper"       — paid OpenAI Whisper cloud API
     *  - "whisper-local" — on-device whisper.cpp (user must download a model)
     *
     * @return The audio model value or "google" if not found.
     */
    fun getAudioModel() : String {
        return getString("audio", "google")
    }

    /**
     * Sets the audio model in the shared preferences.
     *
     * @param model The audio model value to be stored.
     */
    fun setAudioModel(model: String) {
        putString("audio", model)
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
        return getString("assistant_name", "SpeakGPT")
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
     * Retrieves the layout mode from the shared preferences.
     *
     * @return The layout mode or "classic" if not found.
     */
    fun getLayout() : String {
        return getString("layout", "classic")
    }

    /**
     * Sets the layout mode in the shared preferences.
     *
     * @param layout The layout mode to be stored.
     */
    fun setLayout(layout: String) {
        putString("layout", layout)
    }

    /**
     * Retrieves the voice model.
     *
     * @return voice model.
     */
    fun getVoice() : String {
        return getString("voice", "en-us-x-iom-network")
    }

    /**
     * Sets the voice model.
     *
     * @param model voice model.
     */
    fun setVoice(model: String) {
        putString("voice", model)
    }

    /**
     * Set TTS engine
     *
     * @param engine - TTS engine (google or openai)
     * */
    fun setTtsEngine(engine: String) {
        putString("tts_engine", engine)
    }

    /**
     * Get TTS engine
     *
     * @return TTS engine (google or openai)
     * */
    fun getTtsEngine() : String {
        return getString("tts_engine", "google")
    }

    /**
     * Set OpenAI voice
     *
     * @param voice - voice name
     * */
    fun setOpenAIVoice(voice: String) {
        putString("openai_voice", voice)
    }

    /**
     * Get OpenAI voice
     *
     * @return voice name
     * */
    fun getOpenAIVoice() : String {
        return getString("openai_voice", "alloy")
    }

    /**
     * Get Chats autosave
     * */
    fun getChatsAutosave() : Boolean {
        return getGlobalBoolean("chats_autosave", false)
    }

    /**
     * Set Chats autosave
     * */
    fun setChatsAutosave(state: Boolean) {
        putGlobalBoolean("chats_autosave", state)
    }

    /**
     * Get dalle version (2 or 3, 3 is default)
     *
     * @return dalle version
     * */
    fun getDalleVersion() : String {
        return getString("dalle_version", "3")
    }

    /**
     * Set dalle version (2 or 3, 2 is default)
     *
     * @param version dalle version
     * */
    fun setDalleVersion(version: String) {
        putString("dalle_version", version)
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
     * Defaults to Energy: it is dependency-free and reliable across devices,
     * whereas WebRTC depends on a native lib that isn't available everywhere
     * (and silently falls back to energy when it isn't). Users who want
     * noise-rejection can opt into WebRTC in settings.
     *
     * @return VAD method id
     * */
    fun getVadMethod() : String {
        return getGlobalString("vad_method", "energy")
    }

    /**
     * Set the hands-free voice-activity-detection method.
     * */
    fun setVadMethod(method: String) {
        putGlobalString("vad_method", method, "energy")
    }

    /**
     * WebRTC VAD aggressiveness (libfvad mode) used when [getVadMethod] is
     * "webrtc". 0 = most sensitive (hears the most speech, default), 3 = most
     * aggressive (rejects the most noise, may miss quiet/distant speech). See
     * [org.teslasoft.assistant.stt.VadMethods].
     *
     * @return fvad mode 0..3 (default 0)
     * */
    fun getVadWebRtcMode() : Int {
        return (getGlobalString("vad_webrtc_mode", "0").toIntOrNull() ?: 0).coerceIn(0, 3)
    }

    /**
     * Set the WebRTC VAD aggressiveness (clamped to 0..3).
     * */
    fun setVadWebRtcMode(mode: Int) {
        putGlobalString("vad_webrtc_mode", mode.coerceIn(0, 3).toString(), "0")
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
     * The persona last applied in any chat (global, not per-chat). A brand-new
     * chat seeds its per-chat persona from this once, so a fresh chat continues
     * with the persona you were last using instead of resetting to none.
     *
     * @return Persona ID, or an empty String when the last selection was none.
     * */
    fun getLastUsedPersonaId() : String {
        return getGlobalString("last_used_persona_id", "")
    }

    /**
     * Record the persona just applied so future new chats default to it.
     *
     * @param id Persona ID, or an empty String for none.
     * */
    fun setLastUsedPersonaId(id: String) {
        putGlobalString("last_used_persona_id", id)
    }

    /**
     * The activation prompt last applied in any chat (global, not per-chat),
     * seeded into new chats the same way as [getLastUsedPersonaId].
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
     * One-shot guard (per chat) so a new chat seeds its persona/activation from
     * the last-used global defaults exactly once. After that the chat's own
     * selection always wins — including an explicit "none" the user picks later.
     * */
    fun isPersonaActivationSeeded() : Boolean {
        return getBoolean("persona_activation_seeded", false)
    }

    fun setPersonaActivationSeeded(seeded: Boolean) {
        putBoolean("persona_activation_seeded", seeded)
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
     * Memory kill switch (companion memory system, integration plan D5/Phase 2):
     * with memory OFF for a chat, nothing from the memory store is injected
     * (enforcer phase) and the chat's transcript is auto-marked excluded —
     * though its content is still captured, so flipping exclusion back to
     * pending later can recover an experiment that turned out to matter.
     * Per-chat value; a chat that has never set it follows the global default.
     * Stored as a string tri-state ("" = follow global) so the auto-naming
     * copy block can move an unset value without pinning it.
     * */
    fun getChatMemoryEnabled() : Boolean {
        return when (getString("memory_enabled", "")) {
            "true" -> true
            "false" -> false
            else -> getDefaultMemoryEnabled()
        }
    }

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
     * User exclusion (do-not-review): while excluded, NO further messages are
     * captured into the transcript queue and the chat's existing transcript
     * rows are marked excluded so the Archivist never reads them. Reversible:
     * flipping back re-queues the rows and resumes capture from that point
     * (the excluded span is not retroactively captured). Distinct from the
     * kill switch above — exclusion stops recording, the kill switch stops
     * memory *use* (and capture continues under an excluded marker).
     * */
    fun isChatExcludedFromMemory() : Boolean {
        return getBoolean("memory_excluded", false)
    }

    fun setChatExcludedFromMemory(excluded: Boolean) {
        putBoolean("memory_excluded", excluded)
    }

    /**
     * Memory engine tier (integration plan Phase 4, global): "none" = character
     * config + activation prompts only; "lorebooks" = the classic trigger-based
     * lorebook tier (today's behavior, the default); "full" = the complete
     * companion memory system (enforcer assembly; requires an installed
     * embedding model to be selectable, though it degrades to keyword retrieval
     * if the model later breaks — the tier never blocks generation).
     */
    fun getMemoryEngine() : String {
        return getGlobalString("memory_engine", "lorebooks")
    }

    fun setMemoryEngine(engine: String) {
        putGlobalString("memory_engine", engine)
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

    /** Analysis temperature, 0.0–2.0. Recommended/default 0.3. */
    fun getArchivistTemperature(): Float =
        getGlobalString("archivist_temperature", "0.3").toFloatOrNull()?.coerceIn(0.0f, 2.0f) ?: 0.3f

    fun setArchivistTemperature(value: Float) {
        putGlobalString("archivist_temperature", value.coerceIn(0.0f, 2.0f).toString())
    }

    /** Minimum Importance (1–5) a draft must reach to be filed. Default 1
     *  (Low — everything comes through). */
    fun getArchivistMinImportance(): Int =
        getGlobalString("archivist_min_importance", "1").toIntOrNull()?.coerceIn(1, 5) ?: 1

    fun setArchivistMinImportance(value: Int) {
        putGlobalString("archivist_min_importance", value.coerceIn(1, 5).toString())
    }

    /** Custom extraction prompt; "" = use the built-in ArchivistPrompt.SYSTEM
     *  (the Reset Prompt action clears back to ""). */
    fun getArchivistCustomPrompt(): String =
        getGlobalString("archivist_custom_prompt", "")

    fun setArchivistCustomPrompt(value: String) {
        putGlobalString("archivist_custom_prompt", value)
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
     * Model rules (owner_approved_rules §11, Revision 5). Rules apply
     * automatically to any chat whose model string matches, ON by default.
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
