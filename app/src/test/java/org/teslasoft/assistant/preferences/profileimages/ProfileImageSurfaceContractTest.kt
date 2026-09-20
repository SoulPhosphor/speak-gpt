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

    @Test
    fun roleplayCharacterCardUsesTheSharedProfileImagePickerButPartyMembersDoNot() {
        val activity = source("ui/activities/memory/CharacterCardActivity.kt")
        val layout = sourceText("src/main/res/layout/activity_character_card.xml")
        val store = source("preferences/memory/MemoryStore.kt")

        assertTrue(layout.contains("@+id/img_card_avatar"))
        assertTrue(activity.contains("ProfileImagesActivity.EXTRA_RESULT_ASSIGNED_HASH"))
        assertTrue(activity.contains("ProfileImagesActivity.TARGET_COMPANION"))
        assertTrue(activity.contains("outState.putString(STATE_IMAGE_REF, selectedImageRef)"))
        assertTrue(activity.contains("imgCardAvatar?.visibility = if (isParty) View.GONE else View.VISIBLE"))
        assertTrue(activity.contains("imageRef = selectedImageRef.ifEmpty { null }"))
        assertTrue(activity.contains("setRoleplayCharacterImageRef(id, hash)"))
        assertTrue(store.contains("fun setRoleplayCharacterImageRef("))
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

    private fun sourceText(path: String): String =
        listOf(File(path), File("app/$path"))
            .firstOrNull { it.isFile }?.readText() ?: error("Missing $path")
}
