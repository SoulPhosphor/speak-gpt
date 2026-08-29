package org.teslasoft.assistant.tts.api

import org.teslasoft.assistant.providers.ProviderDiagnosticSnapshot
import org.teslasoft.assistant.util.GenErrorCode
import org.teslasoft.assistant.util.GenErrorResult
import org.teslasoft.assistant.util.GenerationErrorClassifier
import org.teslasoft.assistant.util.ProviderLimitKind

enum class TtsOperation(val label: String, val item: String) {
    MODELS("Model List", "model list"), PROVIDERS("Provider List", "provider list"),
    VOICES("Voice List", "voice list"), PREVIEW("Voice Preview", "voice preview"),
    SPEECH("Text to Speech", "speech")
}

enum class TtsFailureKind {
    ENDPOINT_REQUIRED, MODEL_REQUIRED, VOICE_REQUIRED, PROVIDER_REQUIRED, INVALID_ADDRESS, KEY_MISSING,
    OFFLINE, DNS, REFUSED, CONNECT_TIMEOUT, RESPONSE_TIMEOUT, TLS, CONNECTION,
    AUTH, DENIED, RATE_LIMIT, USAGE_LIMIT, NO_CREDITS, SERVER, REJECTED,
    UNSUPPORTED_PARAMETER, REGION_RESTRICTED, CONTENT_REJECTED,
    DISCOVERY_UNAVAILABLE, EMPTY, MALFORMED, IDENTIFIERS_MISSING, MODEL_UNAVAILABLE,
    VOICE_UNSUPPORTED, ROUTING_REJECTED, PROVIDER_UNAVAILABLE, NOT_FOUND,
    NO_AUDIO, AUDIO_FORMAT, PLAYBACK, SOURCE_MISSING, PROFILE_MISSING, STORAGE, UNKNOWN,
    VOICE_DELETED, PERMANENT_UNAVAILABLE, DUPLICATE, SAVE_FAILED, REMOVE_FAILED, SAVED_SOURCE_MISSING, ENDPOINT_LIST_FAILED, STORAGE_FULL
}

data class TtsFailure(
    val operation: TtsOperation, val target: TtsTarget, val endpointName: String,
    val kind: TtsFailureKind,
    /** Null for local validation, successful empty lists, and missing optional metadata. */
    val evidence: ProviderDiagnosticSnapshot? = null,
    val classification: GenErrorResult? = null,
    val responseReceived: Boolean = evidence?.providerResponded == true
)

class TtsException(val failure: TtsFailure) : Exception(failure.kind.name)
data class TtsMessage(val title: String, val explanation: String, val actions: List<String>)

object TtsFailures {
    /** Successful discovery states are not provider errors and carry no fabricated error block. */
    fun voiceDiscovery(source: ResolvedTtsSource, result: TtsVoiceCatalog): TtsFailure? {
        val kind = when(result) {
            is TtsVoiceCatalog.Known -> if (result.voices.isEmpty()) TtsFailureKind.EMPTY else return null
            is TtsVoiceCatalog.Invalid -> result.kind
            TtsVoiceCatalog.Unavailable -> TtsFailureKind.DISCOVERY_UNAVAILABLE
        }
        return TtsFailure(TtsOperation.VOICES, source.target, source.endpoint.label, kind,
            responseReceived = result != TtsVoiceCatalog.Unavailable)
    }

    fun serverKind(evidence: ProviderDiagnosticSnapshot): Pair<TtsFailureKind, GenErrorResult> {
        val classified = GenerationErrorClassifier.classify(Exception("Speech request failed"), evidence)
        val text = (evidence.errorMessages + listOfNotNull(evidence.providerCode,
            evidence.providerType, evidence.providerErrorType)).joinToString("\n").lowercase()
        val kind = when {
            evidence.providerCode in setOf("voice_deleted", "voice_permanently_unavailable") -> TtsFailureKind.VOICE_DELETED
            listOf("unknown voice", "unsupported voice", "voice not found", "invalid voice").any(text::contains) -> TtsFailureKind.VOICE_UNSUPPORTED
            listOf("unsupported provider routing", "provider routing rejected", "unsupported parameter: provider",
                "unsupported parameter: only", "unsupported parameter: order").any(text::contains) -> TtsFailureKind.ROUTING_REJECTED
            listOf("no endpoints found", "selected provider unavailable", "no available providers").any(text::contains) -> TtsFailureKind.PROVIDER_UNAVAILABLE
            listOf("region not supported", "unsupported country", "not available in your region", "region restriction").any(text::contains) -> TtsFailureKind.REGION_RESTRICTED
            classified.code in setOf(GenErrorCode.S3, GenErrorCode.S4, GenErrorCode.S5) -> TtsFailureKind.CONTENT_REJECTED
            classified.providerLimit == ProviderLimitKind.OUT_OF_CREDITS -> TtsFailureKind.NO_CREDITS
            listOf("quota exceeded", "insufficient_quota", "spending limit", "usage limit").any(text::contains) -> TtsFailureKind.USAGE_LIMIT
            classified.providerLimit == ProviderLimitKind.QUOTA_OR_SPENDING -> TtsFailureKind.USAGE_LIMIT
            classified.providerLimit == ProviderLimitKind.RATE_OR_THROUGHPUT -> TtsFailureKind.RATE_LIMIT
            classified.code == GenErrorCode.A1 -> TtsFailureKind.AUTH
            classified.code == GenErrorCode.A2 -> TtsFailureKind.DENIED
            classified.code == GenErrorCode.M2 -> TtsFailureKind.MODEL_UNAVAILABLE
            classified.code == GenErrorCode.M4 -> TtsFailureKind.UNSUPPORTED_PARAMETER
            evidence.outerHttpStatus == 404 -> TtsFailureKind.NOT_FOUND
            evidence.outerHttpStatus in 500..599 -> TtsFailureKind.SERVER
            else -> TtsFailureKind.REJECTED
        }
        return kind to classified
    }

    fun message(f: TtsFailure): TtsMessage {
        val e = f.endpointName
        val m = f.target.modelId
        val item = f.operation.item
        val list = f.operation in setOf(TtsOperation.MODELS, TtsOperation.PROVIDERS, TtsOperation.VOICES)
        val action = if (list) "load the $item" else "generate the $item"
        val loading = if (list) "loading the $item" else "generating the $item"
        val heading = f.operation.label
        val retry = listOf("Cancel", "Retry")
        val okay = listOf("Okay")
        val (title, explanation, actions) = when (f.kind) {
            TtsFailureKind.ENDPOINT_REQUIRED -> Triple("Endpoint Required", "Select an endpoint before choosing a model.", okay)
            TtsFailureKind.MODEL_REQUIRED -> Triple("Model Required", "Select a text-to-speech model before choosing its provider or adding it.", okay)
            TtsFailureKind.VOICE_REQUIRED -> Triple("Voice Required", "Select a voice before requesting speech.", okay)
            TtsFailureKind.PROVIDER_REQUIRED -> Triple("Provider Required",
                if (f.target.routing.mode == org.teslasoft.assistant.preferences.tts.TtsRoutingMode.ONLY)
                    "Select a provider to use Only." else "Select a preferred provider or allow fallbacks.", okay)
            TtsFailureKind.INVALID_ADDRESS -> Triple("Service Address Invalid", "The Base URL saved for $e is not a valid service address. Check its API profile.", okay)
            TtsFailureKind.KEY_MISSING -> Triple("API Key Missing", "$e requires an API key, but none is saved in its API profile.", okay)
            TtsFailureKind.OFFLINE -> Triple("No Internet Connection", "The $item could not be ${if (list) "loaded" else "generated"} because this device is offline. Reconnect and try again.", retry)
            TtsFailureKind.DNS -> Triple("Service Address Not Found", "The address saved for $e could not be found. Check the Base URL in its API profile and your connection.", okay)
            TtsFailureKind.REFUSED -> Triple("Connection Refused", "$e refused the connection while $loading.", retry)
            TtsFailureKind.CONNECT_TIMEOUT -> Triple("Connection Timed Out", "A connection to $e could not be established before the request timed out.", retry)
            TtsFailureKind.RESPONSE_TIMEOUT -> Triple("Response Timed Out", "The complete $item did not arrive from $e before the request timed out.", retry)
            TtsFailureKind.TLS -> Triple("Secure Connection Failed", "A secure connection to $e could not be verified. Check the service address and this device's date and time.", okay)
            TtsFailureKind.CONNECTION -> Triple("Connection Interrupted", "The connection to $e was interrupted while $loading.", retry)
            TtsFailureKind.AUTH -> Triple("API Access Rejected", "$e did not accept the credentials for this request. Check the API key and authentication settings in its API profile.", okay)
            TtsFailureKind.DENIED -> Triple("Access Denied", "$e refused access to the requested $item. Its response is shown below.", okay)
            TtsFailureKind.RATE_LIMIT -> Triple("Too Many Requests", "$e is limiting requests. Wait before trying again.", retry)
            TtsFailureKind.USAGE_LIMIT -> Triple("Usage Limit Reached", "The account's usage or spending limit at $e has been reached. Check the account's limits before trying again.", okay)
            TtsFailureKind.NO_CREDITS -> Triple("No API Credits Remaining", "$e reports that the account has no credits remaining. Add credits with the service before trying again.", okay)
            TtsFailureKind.SERVER -> Triple("Service Error", "$e reported a server error while $loading. Its response is shown below.", retry)
            TtsFailureKind.UNSUPPORTED_PARAMETER -> Triple("Speech Option Not Supported", "$e rejected a request option: ${f.evidence?.errorMessages?.joinToString("\n").orEmpty()}", okay)
            TtsFailureKind.REGION_RESTRICTED -> Triple("Service Region Restricted", "$e reports that this request is not available in the current region. Its response is shown below.", okay)
            TtsFailureKind.CONTENT_REJECTED -> Triple("Speech Content Rejected", "$e rejected content for this request. Its response is shown below.", okay)
            TtsFailureKind.DISCOVERY_UNAVAILABLE -> Triple("$heading Unavailable", "No supported way to list ${if (f.operation == TtsOperation.MODELS) "text-to-speech models" else if (f.operation == TtsOperation.PROVIDERS) "providers" else "voices"} was found${if (m.isBlank()) "" else " for $m"} at $e.", okay)
            TtsFailureKind.EMPTY -> Triple("No ${when(f.operation) { TtsOperation.MODELS -> "Models"; TtsOperation.PROVIDERS -> "Providers"; else -> "Voices" }} Returned", "$e returned an empty $item${if (m.isBlank()) "" else " for $m"}.", retry)
            TtsFailureKind.MALFORMED -> Triple("$heading Could Not Be Read", "$e responded, but its $item could not be read.", retry)
            TtsFailureKind.IDENTIFIERS_MISSING -> Triple("${heading.removeSuffix(" List")} Information Missing", "$e's response did not include the identifiers needed to list ${item.removeSuffix(" list")}s${if (m.isBlank()) "" else " for $m"}.", okay)
            TtsFailureKind.MODEL_UNAVAILABLE -> Triple("TTS Model Unavailable", "$e reports that $m is unavailable. Choose another model.", okay)
            TtsFailureKind.VOICE_UNSUPPORTED -> Triple("Voice Not Supported", "$e reports that ${f.target.voiceId.orEmpty()} is not supported for $m.", okay)
            TtsFailureKind.ROUTING_REJECTED -> Triple("Provider Routing Rejected", "$e rejected the requested provider routing. The selected routing mode has not been changed.", okay)
            TtsFailureKind.PROVIDER_UNAVAILABLE -> Triple("Selected Provider Unavailable", "The service could not use the selected provider for $m. The requested provider settings have not been changed.", okay)
            TtsFailureKind.NOT_FOUND -> Triple(if (list) "$heading Request Not Found" else "Speech Request Not Found", "$e returned Not Found for this ${if (list) item else "speech"} request without identifying what was missing.", okay)
            TtsFailureKind.NO_AUDIO -> Triple("No Audio Returned", "$e completed the request but returned no audio to play.", retry)
            TtsFailureKind.AUDIO_FORMAT -> Triple("Audio Format Not Supported", "The service returned audio in a format this player cannot play.", okay)
            TtsFailureKind.PLAYBACK -> Triple("Audio Could Not Be Played", "The returned audio could not be played.", okay)
            TtsFailureKind.VOICE_DELETED, TtsFailureKind.PERMANENT_UNAVAILABLE -> Triple(
                "Selected Voice Is Permanently Unavailable", "Please select a new voice.", listOf("Okay", "Select New Voice"))
            TtsFailureKind.SOURCE_MISSING -> Triple("Voice Source Unavailable", "The endpoint, model, and provider for this voice could not be identified. Choose a voice again in Select Voice.", okay)
            TtsFailureKind.PROFILE_MISSING -> Triple("API Profile No Longer Exists", "The API profile used by this TTS selection was deleted. Choose another saved TTS selection.", okay)
            TtsFailureKind.STORAGE -> Triple("Saved TTS Models Could Not Be Read", "The saved text-to-speech list could not be read. It has not been replaced or cleared.", okay)
            TtsFailureKind.DUPLICATE -> Triple("Combination Already Exists", "endpoint model and provider combination already exists.", okay)
            TtsFailureKind.SAVE_FAILED -> Triple("TTS Selection Could Not Be Saved", "The TTS selection could not be saved. The saved list and your current selections have not changed.", retry)
            TtsFailureKind.STORAGE_FULL -> Triple("Not Enough Storage", "There is not enough space on this device to save the TTS selection. The saved list and your current selections have not changed.", okay)
            TtsFailureKind.REMOVE_FAILED -> Triple("TTS Selection Could Not Be Removed", "The selected TTS entries could not be removed. They remain in the saved list.", retry)
            TtsFailureKind.SAVED_SOURCE_MISSING -> Triple("Saved TTS Selection No Longer Exists", "This saved TTS selection was removed. It has not been recreated.", okay)
            TtsFailureKind.ENDPOINT_LIST_FAILED -> Triple("API Profiles Could Not Be Read", "The saved API profiles could not be read. Your TTS selections have not changed. Try loading the profiles again.", retry)
            TtsFailureKind.REJECTED -> Triple("Request Rejected", "$e rejected the request to $action. Any explanation it supplied is shown below.", okay)
            TtsFailureKind.UNKNOWN -> Triple("$heading Could Not Be ${if(list) "Loaded" else "Generated"}", "The $item could not be ${if(list) "loaded" else "generated"}, and the cause could not be identified. No provider response was received.", retry)
        }
        return TtsMessage(title, explanation, actions)
    }

}
