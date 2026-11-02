package org.teslasoft.assistant.tts.api

import org.teslasoft.assistant.providers.ProviderDiagnosticSnapshot

internal object TtsFailureLoggingPolicy {
    fun hasProviderFailure(evidence: ProviderDiagnosticSnapshot): Boolean =
        evidence.errorEvents.isNotEmpty() ||
            evidence.outerHttpStatus?.let { it !in 200..299 } == true

    /** A diagnostic write must not replace the original request failure. */
    fun writeSafely(write: () -> Unit): Boolean = try {
        write()
        true
    } catch (_: Exception) {
        false
    }
}
