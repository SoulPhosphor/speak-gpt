package org.teslasoft.assistant.ui.drawer

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class FullWidthDrawerContractTest {
    @Test
    fun chatInstallsTheFullWidthDrawerWithoutChangingItsLockingContract() {
        val controller = source("ui/drawer/ChatDrawerController.kt")
        val fullWidth = source("ui/drawer/FullWidthDrawerLayout.kt")

        assertTrue(controller.contains("FullWidthDrawerLayout(activity)"))
        assertTrue(controller.contains("LOCK_MODE_LOCKED_CLOSED"))
        assertTrue(controller.contains("LOCK_MODE_LOCKED_OPEN"))
        assertTrue(fullWidth.contains("params.width = width"))
        assertTrue(fullWidth.contains("super.onMeasure(widthMeasureSpec, heightMeasureSpec)"))
    }

    private fun source(relative: String): String {
        val path = "src/main/java/org/teslasoft/assistant/$relative"
        return listOf(File(path), File("app/$path"))
            .firstOrNull { it.isFile }?.readText() ?: error("Missing $relative")
    }
}
