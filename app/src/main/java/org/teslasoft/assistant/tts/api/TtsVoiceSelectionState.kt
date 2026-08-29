package org.teslasoft.assistant.tts.api

import org.json.JSONObject
import org.teslasoft.assistant.preferences.tts.TtsVoiceKind
import org.teslasoft.assistant.preferences.tts.TtsVoiceSelection

/** One complete selection. The legacy engine flag is only a compatibility projection. */
object TtsVoiceSelectionCodec {
    fun encode(selection: TtsVoiceSelection): String = JSONObject()
        .put("kind", selection.kind.name).put("sourceId", selection.sourceId)
        .put("voiceId", selection.voiceId).put("modelId", selection.modelId ?: JSONObject.NULL).toString()

    fun decode(raw: String): TtsVoiceSelection = JSONObject(raw).let {
        TtsVoiceSelection(TtsVoiceKind.valueOf(it.getString("kind")), it.getString("sourceId"),
            it.getString("voiceId"), if (it.isNull("modelId")) null else it.getString("modelId"))
    }.also { it.validate() }
}

/** History is deliberately not a catalog, preview cache, or last-success registry. */
object TtsVoiceRecovery {
    fun replacement(current: TtsVoiceSelection, previous: TtsVoiceSelection?,
        permanentlyUnavailable: Boolean, usable: (TtsVoiceSelection) -> Boolean): TtsVoiceSelection? =
        previous?.takeIf { permanentlyUnavailable && current.kind == TtsVoiceKind.API &&
            it != current && usable(it) }
}
