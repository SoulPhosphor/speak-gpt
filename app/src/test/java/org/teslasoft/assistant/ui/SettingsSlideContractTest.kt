package org.teslasoft.assistant.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSlideContractTest {
    @Test fun settingsSlidesFromTheRightAtTheDrawerSpeedFromEveryEntry() {
        val settings = source("java/org/teslasoft/assistant/ui/activities/SettingsActivity.kt")
        assertTrue(settings.contains("overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.settings_slide_in, R.anim.settings_hold)"))
        assertTrue(settings.contains("overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.settings_hold, R.anim.settings_slide_out)"))
        assertTrue(settings.contains("overridePendingTransition(R.anim.settings_slide_in, R.anim.settings_hold)"))
        assertTrue(settings.contains("overridePendingTransition(R.anim.settings_hold, R.anim.settings_slide_out)"))
        // The chat gear no longer overrides Settings' own slide with its grow effect.
        val chat = source("java/org/teslasoft/assistant/ui/activities/ChatActivity.kt")
        val gear = chat.substringAfter("btnSettings?.setOnClickListener {").substringBefore("btnChatMenu?.setOnClickListener")
        assertFalse(gear.contains("makeSceneTransitionAnimation"))

        val slideIn = source("res/anim/settings_slide_in.xml")
        assertTrue(slideIn.contains("android:fromXDelta=\"100%p\""))
        assertTrue(slideIn.contains("@interpolator/drawer_settle"))
        assertTrue(source("res/anim/settings_slide_out.xml").contains("android:toXDelta=\"100%p\""))
        assertTrue(source("res/values/integers.xml").contains("<integer name=\"drawer_slide_duration\">512</integer>"))
    }

    @Test fun slideCurveMatchesTheDrawerSettleInterpolator() {
        // DrawerLayout settles with t -> (t - 1)^5 + 1; the XML curve must stay close to it.
        fun drawer(t: Float) = (t - 1).let { it * it * it * it * it + 1 }
        fun bezier(t: Float, a: Float, b: Float) = 3 * (1 - t) * (1 - t) * t * a + 3 * (1 - t) * t * t * b + t * t * t
        for (step in 1..19) {
            val x = step / 20f
            var lo = 0f; var hi = 1f
            repeat(40) { val mid = (lo + hi) / 2; if (bezier(mid, 0.22f, 0.36f) < x) lo = mid else hi = mid }
            assertEquals("at $x", drawer(x), bezier(lo, 1f, 1f), 0.06f)
        }
    }

    private fun source(relative: String): String {
        val path = "src/main/$relative"
        return listOf(File(path), File("app/$path")).firstOrNull { it.isFile }?.readText() ?: error("Missing $relative")
    }
}
