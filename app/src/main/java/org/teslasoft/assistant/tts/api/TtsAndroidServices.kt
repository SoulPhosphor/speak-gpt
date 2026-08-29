package org.teslasoft.assistant.tts.api

import android.content.Context
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.tts.SavedTtsSourcesPreferences
import org.teslasoft.assistant.util.GenErrorCode
import org.teslasoft.assistant.util.GenErrorResult
import org.teslasoft.assistant.util.providerDetailBlock

object TtsAndroidServices {
    fun resolver(context: Context): TtsSourceResolver {
        val app = context.applicationContext
        return TtsSourceResolver(
            { SavedTtsSourcesPreferences.getPreferences(app).load() },
            { ApiEndpointPreferences.getApiEndpointPreferences(app).getApiEndpointsList(app) }
        )
    }

    /** The existing chat detail labels, without logging or process-wide latest-request state. */
    fun providerDetails(context: Context, f: TtsFailure): String? {
        val evidence = f.evidence ?: return null
        val classified = f.classification ?: GenErrorResult(GenErrorCode.U0,
            evidence.outerHttpStatus, providerResponseReceived = evidence.providerResponded)
        return classified.providerDetailBlock(context, null, f.endpointName,
            evidence.actualServingProvider ?: context.getString(R.string.provider_value_not_reported),
            f.target.modelId, f.operation.label, providerEvidence = evidence,
            requestedRoutedProvider = TtsRouting.requestedProvider(f.target.routing))
    }
}
