package org.teslasoft.assistant.tts.api

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.util.Base64

/** Which documented speech contract an endpoint speaks. Recognized by exact official host only. */
enum class TtsEndpointKind { OPENROUTER, OPENAI, ELEVENLABS, GENERIC }

/** How one speech request is put on the wire and read back. */
enum class TtsWireFormat {
    /** OpenAI-compatible `/audio/speech` JSON returning audio bytes (OpenRouter and generic). */
    OPENAI_COMPATIBLE,
    /** Official OpenAI `/audio/speech` with `stream_format: "sse"`, which reports usage. */
    OPENAI_SSE,
    /** Official ElevenLabs `POST /v1/text-to-speech/{voice_id}`. */
    ELEVENLABS
}

object TtsServices {
    const val OPENAI_HOST = "api.openai.com"

    /** ElevenLabs' global API host and its documented data-residency hosts. */
    val ELEVENLABS_HOSTS = setOf("api.elevenlabs.io", "api.us.elevenlabs.io",
        "api.eu.residency.elevenlabs.io", "api.in.residency.elevenlabs.io")

    /**
     * Official OpenAI speech models for `/audio/speech`. OpenAI's model list does not mark
     * speech output, so on the official host an exact ID from this list is the synthesis
     * evidence. https://developers.openai.com/api/docs/models (verified October 2026).
     */
    val OPENAI_SPEECH_MODELS = setOf("tts-1", "tts-1-1106", "tts-1-hd", "tts-1-hd-1106",
        "gpt-4o-mini-tts", "gpt-4o-mini-tts-2025-03-20", "gpt-4o-mini-tts-2025-12-15")

    /**
     * Models for which OpenAI documents `stream_format: "sse"`, whose final
     * `speech.audio.done` event reports token usage. OpenAI documents that SSE is not
     * supported for tts-1 or tts-1-hd. Exact IDs only.
     */
    val OPENAI_SSE_MODELS = setOf("gpt-4o-mini-tts", "gpt-4o-mini-tts-2025-03-20",
        "gpt-4o-mini-tts-2025-12-15")

    /** ElevenLabs' documented default output format: MP3, 44.1 kHz, 128 kbps. */
    const val ELEVENLABS_OUTPUT_FORMAT = "mp3_44100_128"

    fun kind(baseUrl: String, openRouter: Boolean): TtsEndpointKind {
        if (openRouter) return TtsEndpointKind.OPENROUTER
        val url = baseUrl.trim().toHttpUrlOrNull() ?: return TtsEndpointKind.GENERIC
        if (url.scheme != "https") return TtsEndpointKind.GENERIC
        val host = url.host.lowercase()
        return when {
            host == OPENAI_HOST -> TtsEndpointKind.OPENAI
            host in ELEVENLABS_HOSTS -> TtsEndpointKind.ELEVENLABS
            else -> TtsEndpointKind.GENERIC
        }
    }

    fun wireFormat(endpoint: TtsEndpoint, modelId: String): TtsWireFormat = when (endpoint.kind) {
        TtsEndpointKind.ELEVENLABS -> TtsWireFormat.ELEVENLABS
        TtsEndpointKind.OPENAI -> if (modelId in OPENAI_SSE_MODELS) TtsWireFormat.OPENAI_SSE
            else TtsWireFormat.OPENAI_COMPATIBLE
        else -> TtsWireFormat.OPENAI_COMPATIBLE
    }
}

/** Token usage from OpenAI's terminal `speech.audio.done` event. */
data class TtsReportedTokens(val inputTokens: Long?, val outputTokens: Long?, val totalTokens: Long?)

/**
 * What the serving API reported about one synthesis, kept with its audio. Nothing here
 * is estimated: an absent header or event stays null.
 */
data class TtsMetering(
    /** OpenRouter's `X-Generation-Id`. */
    val generationId: String? = null,
    /** ElevenLabs' `character-cost` response header. */
    val characterCost: Long? = null,
    /** ElevenLabs' `request-id` response header. */
    val requestId: String? = null,
    /** OpenAI SSE usage; null when no `speech.audio.done` usage arrived. */
    val tokens: TtsReportedTokens? = null
)

/** The decoded result of an OpenAI speech SSE response. */
class TtsSseAudio(val audio: ByteArray, val tokens: TtsReportedTokens?, val error: String?)

object TtsSpeechSse {
    /**
     * Reads `speech.audio.delta` events in order, Base64-decoding each one on its own and
     * joining the audio bytes, and the terminal `speech.audio.done` usage. An `error`
     * payload is returned as its JSON text so the caller can classify it.
     */
    fun decode(body: String): TtsSseAudio {
        val audio = ByteArrayOutputStream()
        var tokens: TtsReportedTokens? = null
        var error: String? = null
        val data = StringBuilder()
        fun dispatch() {
            if (data.isEmpty()) return
            val payload = data.toString()
            data.setLength(0)
            if (payload == "[DONE]") return
            val json = try { JsonParser.parseString(payload).objectOrNull() } catch (_: Exception) { null }
                ?: return
            if (json.get("error")?.let { !it.isJsonNull } == true || json.text("type") == "error") {
                if (error == null) error = payload
                return
            }
            when (json.text("type")) {
                "speech.audio.delta" -> json.text("audio")?.let { audio.write(Base64.getDecoder().decode(it)) }
                "speech.audio.done" -> json.get("usage").objectOrNull()?.let { usage ->
                    tokens = TtsReportedTokens(usage.long("input_tokens"), usage.long("output_tokens"),
                        usage.long("total_tokens"))
                }
            }
        }
        body.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { line ->
            when {
                line.isEmpty() -> dispatch()
                line.startsWith(":") -> Unit
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").removePrefix(" "))
                }
            }
        }
        dispatch()
        return TtsSseAudio(audio.toByteArray(), tokens, error)
    }

    private fun JsonObject.long(key: String): Long? = get(key)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
        ?.asNumber?.toDouble()?.takeIf { it.isFinite() && it >= 0 && it == Math.floor(it) }?.toLong()
}

/**
 * Exact playing time of MPEG audio, read from its frame headers (after any ID3v2 tag).
 * A Xing/Info/VBRI header frame carries no audio and is not counted; a truncated last
 * frame is not counted. Null when the data is not readable MPEG audio. Nothing is
 * estimated from text length or file size.
 */
object Mp3Duration {
    private val bitratesV1 = arrayOf(
        intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448),
        intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384),
        intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320))
    private val bitratesV2 = arrayOf(
        intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256),
        intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160),
        intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160))
    private val sampleRates = mapOf(3 to intArrayOf(44100, 48000, 32000),
        2 to intArrayOf(22050, 24000, 16000), 0 to intArrayOf(11025, 12000, 8000))

    fun seconds(bytes: ByteArray): Double? {
        var offset = 0
        if (bytes.size >= 10 && bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() &&
            bytes[2] == '3'.code.toByte()) {
            val size = (bytes[6].toInt() and 0x7f shl 21) or (bytes[7].toInt() and 0x7f shl 14) or
                (bytes[8].toInt() and 0x7f shl 7) or (bytes[9].toInt() and 0x7f)
            offset = 10 + size + if (bytes[5].toInt() and 0x10 != 0) 10 else 0
        }
        var seconds = 0.0
        var frames = 0
        while (offset + 4 <= bytes.size) {
            val h = ((bytes[offset].toInt() and 0xff) shl 24) or ((bytes[offset + 1].toInt() and 0xff) shl 16) or
                ((bytes[offset + 2].toInt() and 0xff) shl 8) or (bytes[offset + 3].toInt() and 0xff)
            if (h ushr 21 != 0x7ff) break
            val version = (h ushr 19) and 3
            val layer = (h ushr 17) and 3
            val bitrateIndex = (h ushr 12) and 15
            val rateIndex = (h ushr 10) and 3
            if (version == 1 || layer == 0 || bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) break
            val padding = (h ushr 9) and 1
            val layerIndex = 3 - layer // 0 = Layer I, 1 = Layer II, 2 = Layer III
            val kbps = (if (version == 3) bitratesV1 else bitratesV2)[layerIndex][bitrateIndex]
            val rate = sampleRates.getValue(version)[rateIndex]
            val samples = when {
                layerIndex == 0 -> 384
                layerIndex == 1 || version == 3 -> 1152
                else -> 576
            }
            val length = if (layerIndex == 0) (12 * kbps * 1000 / rate + padding) * 4
                else samples / 8 * kbps * 1000 / rate + padding
            if (length < 4 || offset + length > bytes.size) break
            if (!(frames == 0 && infoFrame(bytes, offset, version, h))) seconds += samples.toDouble() / rate
            frames++
            offset += length
        }
        return if (frames == 0) null else seconds
    }

    private fun infoFrame(bytes: ByteArray, offset: Int, version: Int, header: Int): Boolean {
        val mono = (header ushr 6) and 3 == 3
        val side = if (version == 3) (if (mono) 17 else 32) else (if (mono) 9 else 17)
        fun tag(at: Int, text: String) = at + 4 <= bytes.size &&
            text.indices.all { bytes[at + it] == text[it].code.toByte() }
        val at = offset + 4 + side
        return tag(at, "Xing") || tag(at, "Info") || tag(offset + 36, "VBRI")
    }
}
