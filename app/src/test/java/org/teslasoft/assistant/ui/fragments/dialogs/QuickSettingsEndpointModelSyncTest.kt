package org.teslasoft.assistant.ui.fragments.dialogs

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the endpoint editor result contract used by the active chat. */
class QuickSettingsEndpointModelSyncTest {
    @Test
    fun savedEndpointModelBecomesTheVisibleAndRequestedChatModel() {
        val source = source(
            "src/main/java/org/teslasoft/assistant/ui/fragments/dialogs/" +
                "QuickSettingsBottomSheetDialogFragment.kt"
        )

        val resultHandler = source.substringAfter("apiEndpointActivityResultLauncher")
            .substringBefore("chooseProviderLauncher")
        assertTrue(resultHandler.contains("apiEndpoint?.model?.takeIf { it.isNotBlank() }"))
        assertTrue(resultHandler.contains("preferences?.setModel(endpointModel)"))
        assertTrue(resultHandler.contains("textModel?.text = endpointModel"))
        assertTrue(resultHandler.contains("updateListener?.onUpdate()"))
    }

    /** The chat screen's half of the same flow. Quick Settings writes the new
     *  values to storage and then calls back; the screen caches them and builds
     *  every request from the cached copy, so the callback has to re-read them.
     *  The rebuild callback finishes the screen, which must not abandon the
     *  provisional conversation the replacement screen is about to open. */
    @Test
    fun theChatScreenRereadsItsModelAndNeverAbandonsTheChatItIsHandingOver() {
        val source = source(
            "src/main/java/org/teslasoft/assistant/ui/activities/ChatActivity.kt"
        )
        val listener = source.substringAfter("private fun openSummoningCircle() {")
            .substringBefore("private fun renameChatTitle(")

        val onUpdate = listener.substringAfter("override fun onUpdate() {")
            .substringBefore("override fun onForceUpdate() {")
        assertTrue(onUpdate.contains("loadModel()"))

        val onForceUpdate = listener.substringAfter("override fun onForceUpdate() {")
        assertTrue(onForceUpdate.contains("recreatingForSettings = true"))
        assertTrue(onForceUpdate.contains("putExtra(\"pendingConversation\", pendingConversation)"))
        assertTrue(
            onForceUpdate.indexOf("recreatingForSettings = true") <
                onForceUpdate.indexOf("finishActivity()")
        )
    }

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../$relative"))
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: throw AssertionError("$relative not found from " + File(".").absolutePath)
    }
}
