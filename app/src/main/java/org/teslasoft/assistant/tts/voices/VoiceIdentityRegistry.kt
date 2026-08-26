package org.teslasoft.assistant.tts.voices

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject
import java.util.Locale

/** Persistent, presentation-only user names and genders for provider voices. */
class VoiceIdentityRegistry(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun apply(voice: BrowserVoice): BrowserVoice {
        val override = overrides()[key(voice.providerId, voice.providerVoiceId)] ?: return voice
        return applyOverride(voice, override)
    }

    fun displayNameFor(providerId: String, providerVoiceId: String, originalDisplayName: String): String =
        overrides()[key(providerId, providerVoiceId)]?.displayName
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: originalDisplayName

    fun save(voice: BrowserVoice, displayName: String, genderId: String?): BrowserVoice = synchronized(LOCK) {
        val values = overrides().toMutableMap()
        val normalizedName = displayName.trim().takeIf { it.isNotEmpty() } ?: voice.originalDisplayName
        val normalizedGender = genderId?.lowercase(Locale.ROOT)?.takeIf(VALID_GENDERS::contains)
        values[key(voice.providerId, voice.providerVoiceId)] = VoiceIdentityOverride(
            displayName = normalizedName,
            genderId = normalizedGender
        )
        preferences.edit { putString(KEY, encode(values)) }
        applyOverride(voice, values.getValue(key(voice.providerId, voice.providerVoiceId)))
    }

    private fun overrides(): Map<String, VoiceIdentityOverride> = synchronized(LOCK) {
        decode(preferences.getString(KEY, null))
    }

    data class VoiceIdentityOverride(val displayName: String?, val genderId: String?)

    companion object {
        private const val KEY = "voice_identity_overrides"
        private const val SEPARATOR = "\u0000"
        private val VALID_GENDERS = setOf("female", "male", "neutral")
        private val LOCK = Any()

        fun key(providerId: String, providerVoiceId: String): String =
            "$providerId$SEPARATOR$providerVoiceId"

        /** Pure application function used by both runtime code and unit tests. */
        fun applyOverride(voice: BrowserVoice, override: VoiceIdentityOverride): BrowserVoice {
            val userGender = override.genderId
                ?.lowercase(Locale.ROOT)
                ?.takeIf(VALID_GENDERS::contains)
                ?.let { VoiceFacetValue(it, it.replaceFirstChar { char -> char.titlecase(Locale.getDefault()) }) }
            return voice.copy(
                displayName = override.displayName?.trim()?.takeIf(String::isNotEmpty) ?: voice.originalDisplayName,
                gender = userGender ?: voice.providerGender,
                userAssignedGender = userGender
            )
        }

        internal fun decode(raw: String?): Map<String, VoiceIdentityOverride> = try {
            val root = JSONObject(raw.orEmpty())
            buildMap {
                root.keys().forEach { id ->
                    val value = root.optJSONObject(id) ?: return@forEach
                    put(
                        id,
                        VoiceIdentityOverride(
                            displayName = value.optString("name").takeIf(String::isNotBlank),
                            genderId = value.optString("gender")
                                .lowercase(Locale.ROOT)
                                .takeIf(VALID_GENDERS::contains)
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            emptyMap()
        }

        internal fun encode(values: Map<String, VoiceIdentityOverride>): String {
            val root = JSONObject()
            values.forEach { (id, value) ->
                root.put(id, JSONObject().apply {
                    value.displayName?.let { put("name", it) }
                    value.genderId?.let { put("gender", it) }
                })
            }
            return root.toString()
        }
    }
}
