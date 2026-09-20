package org.teslasoft.assistant

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BetaIsolationContractTest {

    @Test
    fun betaBuildHasDistinctPackageLabelVersionAndBackupBrand() {
        val gradle = projectFile("app/build.gradle").readText()
        val resources = projectFile("app/src/beta/res/values/strings.xml").readText()
        assertTrue(gradle.contains("beta {"))
        assertTrue(gradle.contains("applicationIdSuffix \".beta\""))
        assertTrue(gradle.contains("versionNameSuffix \"-beta\""))
        assertTrue(resources.contains("Phosphor Shines Beta"))
        assertTrue(resources.contains("Phosphor-Shines-Beta"))
    }

    @Test
    fun providerAuthorityTracksResolvedApplicationId() {
        val manifest = projectFile("app/src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:authorities=\"\${applicationId}.fileprovider\""))
        assertFalse(manifest.contains("com.soulphosphor.phosphorshines.fileprovider"))
    }

    @Test
    fun betaWorkflowAssertsManifestAndNeverTouchesLatestTag() {
        val workflow = projectFile(".github/workflows/beta-release.yml").readText()
        assertTrue(workflow.contains("apkanalyzer manifest application-id"))
        assertTrue(workflow.contains("com.soulphosphor.phosphorshines.beta"))
        assertTrue(workflow.contains("beta-latest"))
        assertFalse(Regex("release (delete|create) latest").containsMatchIn(workflow))
        assertFalse(workflow.contains("refs/tags/latest"))
    }

    private fun projectFile(relative: String): File {
        val candidates = listOf(
            File(relative),
            File("..", relative),
            File(System.getProperty("user.dir"), relative),
            File(System.getProperty("user.dir"), "../$relative")
        )
        return candidates.firstOrNull { it.isFile } ?: error("Missing $relative")
    }
}
