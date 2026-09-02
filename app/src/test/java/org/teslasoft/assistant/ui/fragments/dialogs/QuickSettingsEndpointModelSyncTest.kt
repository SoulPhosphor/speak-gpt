package org.teslasoft.assistant.ui.fragments.dialogs

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the endpoint editor result contract used by the active chat. */
class QuickSettingsEndpointModelSyncTest {
    @Test
    fun savedEndpointModelBecomesTheVisibleAndRequestedChatModel() {
        val relative = "src/main/java/org/teslasoft/assistant/ui/fragments/dialogs/" +
            "QuickSettingsBottomSheetDialogFragment.kt"
        val candidates = listOf(File(relative), File("app/$relative"), File("../$relative"))
        val source = candidates.firstOrNull { it.exists() }?.readText()
            ?: throw AssertionError("$relative not found from " + File(".").absolutePath)

        val resultHandler = source.substringAfter("apiEndpointActivityResultLauncher")
            .substringBefore("chooseProviderLauncher")
        assertTrue(resultHandler.contains("apiEndpoint?.model?.takeIf { it.isNotBlank() }"))
        assertTrue(resultHandler.contains("preferences?.setModel(endpointModel)"))
        assertTrue(resultHandler.contains("textModel?.text = endpointModel"))
        assertTrue(resultHandler.contains("updateListener?.onUpdate()"))
    }
}
