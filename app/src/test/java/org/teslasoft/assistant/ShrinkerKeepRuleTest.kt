package org.teslasoft.assistant

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R8 shrinking runs for EVERY build type of this app, and no JVM unit test
 * ever runs through it. A class or member that only the shrinker removes
 * therefore fails on a device while the whole suite stays green — which is
 * exactly how a keep rule aimed at the wrong package went unnoticed.
 *
 * The rules kept the upstream fork's old `com.teslasoft.assistant` package
 * while this app's classes live in `org.teslasoft.assistant`, so the rule
 * matched nothing and every one of the app's own classes was exposed. Fields
 * that exist only to be written and read reflectively (Gson) look unused to
 * the shrinker and can be removed, which serializes objects as `{}`.
 */
class ShrinkerKeepRuleTest {

    private val rules: String = listOf(
        File("proguard-rules.pro"), File("app/proguard-rules.pro")
    ).firstOrNull { it.isFile }?.readText() ?: error("Missing proguard-rules.pro")

    private val gradle: String = listOf(
        File("build.gradle"), File("app/build.gradle")
    ).firstOrNull { it.isFile }?.readText() ?: error("Missing app/build.gradle")

    @Test
    fun theApplicationsOwnPackageIsKept() {
        val namespace = Regex("""namespace\s+['"]([^'"]+)['"]""")
            .find(gradle)?.groupValues?.get(1)
            ?: error("Could not read the application namespace")
        assertTrue(
            "proguard-rules.pro must keep the app's real package ($namespace)",
            rules.contains("-keep class $namespace.** { *; }")
        )
    }

    @Test
    fun theStaleUpstreamPackageRuleIsNotTheOnlyOne() {
        // Harmless on its own, but it must never again be mistaken for
        // protection of this app's classes.
        val keeps = rules.lineSequence()
            .filter { it.trimStart().startsWith("-keep class") && it.contains("teslasoft.assistant") }
            .toList()
        assertTrue(
            "Expected a keep rule for org.teslasoft.assistant.**, found: $keeps",
            keeps.any { it.contains("org.teslasoft.assistant.**") }
        )
    }
}
