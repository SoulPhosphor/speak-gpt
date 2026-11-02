package org.teslasoft.assistant.tts.api

import org.teslasoft.assistant.providers.ProviderFailureLoggingPolicy
import kotlinx.coroutines.launch
import android.content.Context
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.tts.SavedTtsSourcesPreferences
import org.teslasoft.assistant.util.GenErrorCode
import org.teslasoft.assistant.util.GenErrorResult
import org.teslasoft.assistant.util.providerDetailBlock

object TtsAndroidServices {
    private val loggedAttempts = LinkedHashSet<String>()
    private val logScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    /** Keep a provider's error even when playback has no foreground screen. */
    fun recordProviderFailure(context: Context, failure: TtsFailure) {
        val evidence = failure.evidence ?: return
        if (!ProviderFailureLoggingPolicy.hasProviderFailure(evidence)) return
        val app = context.applicationContext
        if (!org.teslasoft.assistant.preferences.Preferences.getPreferences(app, "").getLogChatFailures()) return
        synchronized(loggedAttempts) {
            if (!loggedAttempts.add(evidence.attemptId)) return
            while (loggedAttempts.size > 256) loggedAttempts.remove(loggedAttempts.first())
        }
        val detail = org.teslasoft.assistant.util.summarizer.SummarizerDetailSanitizer.sanitize(
            evidence.errorMessages.joinToString("\n").ifBlank {
                evidence.errorEvents.mapNotNull { it.rawPayload }.joinToString("\n")
            }
        ) ?: "Provider returned no error message"
        logScope.launch {
            val recorded = ProviderFailureLoggingPolicy.writeSafely {
                org.teslasoft.assistant.preferences.Logger.logProviderFailure(
                    app, failure.endpointName, evidence.actualServingProvider ?: "Not Reported",
                    failure.target.modelId, failure.operation.label, detail,
                    requestedRoutedProvider = TtsRouting.requestedProvider(failure.target.routing),
                    outerHttpStatus = evidence.outerHttpStatus,
                    embeddedProviderStatus = evidence.embeddedHttpStatus,
                    providerCode = evidence.providerCode, providerType = evidence.providerType,
                    providerErrorType = evidence.providerErrorType,
                    contentFilterSide = evidence.contentFilterSide.wire, attemptId = evidence.attemptId
                )
            }
            if (!recorded) synchronized(loggedAttempts) { loggedAttempts.remove(evidence.attemptId) }
        }
    }

    fun resolver(context: Context): TtsSourceResolver {
        val app = context.applicationContext
        return TtsSourceResolver(
            { SavedTtsSourcesPreferences.getPreferences(app).load() },
            { ApiEndpointPreferences.getApiEndpointPreferences(app).getApiEndpointsList(app) }
        )
    }

    /** The existing chat detail labels, without logging or process-wide latest-request state. */
    fun providerDetails(context: Context, f: TtsFailure): String? {
        if (f.operation == TtsOperation.VOICES) when (TtsFailures.voiceOutcome(f.kind)) {
            // A voice list that answered is not a provider error, so it is not described as one.
            TtsVoiceOutcome.NONE_USABLE -> return voiceResponseDetails(context, f,
                listOf(context.getString(R.string.provider_voice_results_none)))
            TtsVoiceOutcome.UNREADABLE -> return voiceResponseDetails(context, f, listOf(
                context.getString(R.string.provider_parsing_result_unreadable),
                context.getString(R.string.provider_parsing_error_line,
                    f.voiceEvidence?.parsingError ?: context.getString(R.string.provider_value_not_reported))))
            else -> {}
        }
        // A headed error block is shown even when no response arrived, and then says so.
        val evidence = f.evidence
        if (evidence == null && TtsFailures.message(f).detailsHeading == null) return null
        val classified = f.classification ?: GenErrorResult(GenErrorCode.U0,
            evidence?.outerHttpStatus, providerResponseReceived = evidence?.providerResponded == true)
        return classified.providerDetailBlock(context, null, f.endpointName,
            evidence?.actualServingProvider ?: context.getString(R.string.provider_value_not_reported),
            f.target.modelId, f.operation.label, providerEvidence = evidence,
            requestedRoutedProvider = TtsRouting.requestedProvider(f.target.routing))
    }

    private fun voiceResponseDetails(context: Context, f: TtsFailure, result: List<String>): String {
        val notReported = context.getString(R.string.provider_value_not_reported)
        return (listOf(context.getString(R.string.provider_api_provider_line, f.endpointName.ifBlank { notReported }),
            context.getString(R.string.provider_model_line, f.target.modelId.ifBlank { notReported }),
            context.getString(R.string.provider_function_line, context.getString(R.string.provider_function_voice_list)),
            context.getString(R.string.provider_http_status_line, f.voiceEvidence?.httpStatus?.toString() ?: notReported)) +
            result + context.getString(R.string.provider_raw_response_line,
                f.voiceEvidence?.rawResponse ?: notReported)).joinToString("\n")
    }

    /** Explanation first, then the headed provider details, as one standard dialog message. */
    fun dialogMessage(context: Context, f: TtsFailure, message: TtsMessage = TtsFailures.message(f)): String {
        val details = providerDetails(context, f)?.let { d -> message.detailsHeading?.let { "$it\n$d" } ?: d }
        return listOfNotNull(message.explanation, details).joinToString("\n\n")
    }
}
