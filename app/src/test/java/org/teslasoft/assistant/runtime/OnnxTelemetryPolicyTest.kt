package org.teslasoft.assistant.runtime

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Architectural guard: new ONNX consumers cannot silently bypass the
 * telemetry-disabled environment. JNI execution is covered on Android. */
class OnnxTelemetryPolicyTest {
    private fun appRoot(): File = if (File("src/main/java").exists()) File(".") else File("app")

    @Test fun allOnnxConsumersUseTheOptedOutEnvironment() {
        val sources = File(appRoot(), "src/main/java").walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java") }.toList()
        val rawAccess = sources.filter { Regex("OrtEnvironment\\.getEnvironment\\s*\\(").containsMatchIn(it.readText()) }
        assertEquals(listOf("OnnxRuntimeEnvironment.kt"), rawAccess.map { it.name })
        val gateway = rawAccess.single().readText()
        assertTrue(gateway.contains("setTelemetry(false)"))
        val startup = gateway.indexOf("Os.setenv(\"ORT_DISABLE_TELEMETRY\", \"1\", true)")
        assertTrue(startup >= 0 && startup < gateway.indexOf("OrtEnvironment.getEnvironment()"))
        assertTrue(sources.none { Regex("setTelemetry\\s*\\(\\s*true").containsMatchIn(it.readText()) })
    }

    @Test fun fixtureToolsDisableTelemetryBeforeAnySession() {
        for (name in listOf("generate_vectors.py", "generate_section_vectors.py")) {
            val text = File(appRoot(), "src/test/resources/recall_test_set/$name").readText()
            val optOut = text.indexOf("ort.disable_telemetry_events()")
            val startup = text.indexOf("os.environ[\"ORT_DISABLE_TELEMETRY\"] = \"1\"")
            assertTrue("$name must opt out before importing the native runtime", startup >= 0 && startup < text.indexOf("import onnxruntime"))
            assertTrue("$name must disable telemetry before constructing a session", optOut >= 0 && optOut < text.indexOf("ort.InferenceSession("))
            assertFalse(text.contains("ort.enable_telemetry_events()"))
        }
    }
}
