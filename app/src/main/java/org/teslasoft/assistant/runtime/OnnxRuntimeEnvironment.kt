package org.teslasoft.assistant.runtime

import ai.onnxruntime.OrtEnvironment
import android.system.Os

/** One process-wide ONNX environment. Telemetry is disabled before any model
 * session can be created. Failure to apply the opt-out propagates to the
 * caller's existing model-unavailable fallback; inference must not proceed. */
object OnnxRuntimeEnvironment {
    private val environment: OrtEnvironment by lazy {
        // Newer ORT builds can emit an initialization event before the API
        // opt-out. Set the startup opt-out before loading the native runtime;
        // retain the API opt-out for the shipped runtime and other backends.
        Os.setenv("ORT_DISABLE_TELEMETRY", "1", true)
        OrtEnvironment.getEnvironment().apply { setTelemetry(false) }
    }

    fun get(): OrtEnvironment = environment
}
