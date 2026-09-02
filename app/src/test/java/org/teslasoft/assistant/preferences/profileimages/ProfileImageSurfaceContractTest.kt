package org.teslasoft.assistant.preferences.profileimages

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileImageSurfaceContractTest {
    @Test
    fun chatUserAndCompanionStartWithTheCircleDefault() {
        val adapter = source("ui/adapters/chat/ChatAdapter.kt")
        assertTrue(adapter.contains(
            "private var companionImageShape: String = ProfileImageShape.DEFAULT"
        ))
        assertTrue(adapter.contains(
            "private var userImageShape: String = ProfileImageShape.DEFAULT"
        ))
        assertFalse(adapter.contains("ImageShape: String = \"flower\""))
    }

    @Test
    fun oneGlobalShapeRefreshesBothSidesOfChat() {
        val activity = source("ui/activities/ChatActivity.kt")
        val companion = function(activity, "private fun refreshCompanionAvatar()")
        val user = function(activity, "private fun refreshUserAvatar()")

        assertTrue(companion.contains("getProfileImageShape()"))
        assertTrue(companion.contains("adapter?.setCompanionPresentation("))
        assertTrue(user.contains("getProfileImageShape()"))
        assertTrue(user.contains("adapter?.setUserAvatar(file, shape)"))
    }

    @Test
    fun pooledMaskBitmapIsClearedBeforeCircleOrFlowerIsDrawn() {
        val transform = source("preferences/profileimages/ProfileShapeTransformation.kt")
        assertTrue(transform.contains(
            "canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)"
        ))
    }

    private fun function(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "Missing $signature" }
        val next = source.indexOf("\n    private fun ", start + signature.length)
        return source.substring(start, if (next >= 0) next else source.length)
    }

    private fun source(relative: String): String {
        val path = "src/main/java/org/teslasoft/assistant/$relative"
        return listOf(File(path), File("app/$path"))
            .firstOrNull { it.isFile }?.readText() ?: error("Missing $relative")
    }
}
