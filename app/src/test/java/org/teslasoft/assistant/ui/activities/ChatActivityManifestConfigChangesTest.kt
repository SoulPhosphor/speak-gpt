package org.teslasoft.assistant.ui.activities

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for ChatActivity's manifest configChanges.
 *
 * ChatActivity is a long-lived voice/generation surface. If Android is allowed
 * to recreate it for an ordinary configuration change, onDestroy runs the full
 * session teardown (killAllProcesses + stopHandsFreeService) and an in-flight
 * reply comes back as an app_cancel "screen was closed before the reply
 * finished" even though the user never left the chat.
 *
 * uiMode is the flag that matters most here: the app follows the system
 * day/night theme, and a night-mode flip commonly fires while the screen is off
 * (battery saver dark, scheduled/sunset dark). Handling it in configChanges is
 * what lets a hands-free conversation survive the screen turning off. This exact
 * flag was lost from the manifest once before; this test fails in ordinary unit
 * CI if it is removed again, or if any of the other required flags disappear.
 */
class ChatActivityManifestConfigChangesTest {

    private val requiredFlags = listOf(
        "orientation",
        "screenSize",
        "screenLayout",
        "smallestScreenSize",
        "keyboardHidden",
        "uiMode"
    )

    @Test
    fun chatActivityDeclaresRequiredConfigChanges() {
        val manifest = manifestSource().readText()

        val nameMarker = "android:name=\".ui.activities.ChatActivity\""
        val nameIndex = manifest.indexOf(nameMarker)
        assertTrue("ChatActivity declaration not found in AndroidManifest.xml", nameIndex >= 0)

        // The <activity …> opening tag runs from the name attribute to the next
        // '>'. Attribute values contain no '>', so this reliably bounds the tag.
        val tag = manifest.substring(nameIndex).substringBefore('>', missingDelimiterValue = "")
        assertTrue("Could not read ChatActivity's opening <activity> tag", tag.isNotEmpty())

        val configMarker = "android:configChanges=\""
        val configIndex = tag.indexOf(configMarker)
        assertTrue("ChatActivity is missing android:configChanges", configIndex >= 0)
        val configValue = tag.substring(configIndex + configMarker.length)
            .substringBefore('"', missingDelimiterValue = "")
        assertTrue("Could not read ChatActivity's configChanges value", configValue.isNotEmpty())

        val declared = configValue.split('|').map { it.trim() }.toSet()
        for (flag in requiredFlags) {
            assertTrue(
                "ChatActivity's configChanges must contain '$flag' so a configuration " +
                    "change does not recreate the Activity and tear down a live voice " +
                    "conversation. Current value: \"$configValue\"",
                declared.contains(flag)
            )
        }
    }


    @Test
    fun chatActivityUsesSingleTopToRejectDuplicateLaunches() {
        val manifest = manifestSource().readText()
        val nameIndex = manifest.indexOf("android:name=\".ui.activities.ChatActivity\"")
        assertTrue("ChatActivity declaration not found in AndroidManifest.xml", nameIndex >= 0)
        val tag = manifest.substring(nameIndex).substringBefore('>', missingDelimiterValue = "")
        assertTrue(
            "ChatActivity must use singleTop so double taps and refreshes cannot stack two chats",
            tag.contains("android:launchMode=\"singleTop\"")
        )
    }

    private fun manifestSource(): File {
        val relative = "src/main/AndroidManifest.xml"
        val candidates = listOf(
            File(relative),
            File("app/$relative"),
            File(System.getProperty("user.dir"), relative),
            File(System.getProperty("user.dir"), "app/$relative")
        )
        return candidates.firstOrNull { it.isFile }
            ?: error(
                "Could not locate AndroidManifest.xml from ${System.getProperty("user.dir")}; " +
                    "checked: ${candidates.joinToString { it.path }}"
            )
    }
}
