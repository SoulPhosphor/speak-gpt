package org.teslasoft.assistant.preferences

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test

class ImageCompatibilityPreferencesTest {
    private fun preferences(backing: SharedPreferences): GlobalPreferences =
        GlobalPreferences::class.java.getDeclaredConstructor(SharedPreferences::class.java).apply {
            isAccessible = true
        }.newInstance(backing)

    @Test fun committedEvidenceIsRecoveredIndependentlyFromSavedImagePreferences() {
        val backing = FakeSharedPreferences()
        backing.edit().putString("image_generator_parameters", "saved selections").commit()
        assertTrue(preferences(backing).commitImageCompatibilityEvidence("scope", "evidence"))
        assertEquals("evidence", preferences(backing).getImageCompatibilityEvidence("scope"))
        assertEquals("saved selections", backing.getString("image_generator_parameters", null))
    }

    @Test fun aFalseCommitAfterMemoryMutationRestoresThePreviousEvidence() {
        verifyFailedCommit(throws = false, previous = "old evidence")
        verifyFailedCommit(throws = false, previous = null)
    }

    @Test fun anExceptionAfterMemoryMutationRestoresThePreviousEvidence() {
        verifyFailedCommit(throws = true, previous = "old evidence")
        verifyFailedCommit(throws = true, previous = null)
    }

    private fun verifyFailedCommit(throws: Boolean, previous: String?) {
        val backing = FakeSharedPreferences()
        previous?.let { backing.edit().putString("image_compatibility_scope", it).commit() }
        val failing = object : SharedPreferences by backing {
            override fun edit(): SharedPreferences.Editor {
                val editor = backing.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun commit(): Boolean {
                        editor.commit() // Android mutates its memory map before the disk result.
                        if (throws) throw java.io.IOException("simulated disk error")
                        return false
                    }
                }
            }
        }
        assertFalse(preferences(failing).commitImageCompatibilityEvidence("scope", "new evidence"))
        assertEquals(previous, preferences(backing).getImageCompatibilityEvidence("scope"))
        assertEquals(previous != null, backing.contains("image_compatibility_scope"))
    }
}
