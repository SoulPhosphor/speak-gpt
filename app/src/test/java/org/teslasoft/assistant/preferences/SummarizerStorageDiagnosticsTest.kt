package org.teslasoft.assistant.preferences

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.util.summarizer.SummarySectionStore

class SummarizerStorageDiagnosticsTest {
    private fun failingPreferences(error: Exception? = null): Preferences {
        val backing = FakeSharedPreferences()
        val failing = object : SharedPreferences by backing {
            override fun edit(): SharedPreferences.Editor {
                val editor = backing.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this
                    }
                    override fun remove(key: String?): SharedPreferences.Editor {
                        editor.remove(key)
                        return this
                    }
                    override fun commit(): Boolean {
                        if (error != null) throw error
                        editor.commit()
                        return false
                    }
                }
            }
        }
        return Preferences(failing, FakeSharedPreferences(), "chat")
    }

    @Test fun sectionSaveRetainsActualExceptionAndCause() {
        val error = IllegalStateException("encrypted storage broke", java.io.IOException("disk unavailable"))
        val prefs = failingPreferences(error)
        assertFalse(prefs.commitSummarySections("[]"))
        assertSame(error, prefs.summarizerStorageFailure)
        assertEquals("disk unavailable", prefs.summarizerStorageFailure?.cause?.message)
    }

    @Test fun compactSaveRetainsActualException() {
        val error = java.io.IOException("cannot commit compacted state")
        val prefs = failingPreferences(error)
        assertFalse(prefs.commitManualCompaction("private summary", 10, false, 10))
        assertSame(error, prefs.summarizerStorageFailure)
    }

    @Test fun falseCommitHasConcreteDiagnostic() {
        val backing = FakeSharedPreferences()
        backing.edit().putString("summary_sections", "old").commit()
        val failing = object : SharedPreferences by backing {
            override fun edit(): SharedPreferences.Editor {
                val editor = backing.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this
                    }
                    override fun remove(key: String?): SharedPreferences.Editor {
                        editor.remove(key)
                        return this
                    }
                    override fun commit(): Boolean {
                        editor.commit()
                        return false
                    }
                }
            }
        }
        val prefs = Preferences(failing, FakeSharedPreferences(), "chat")
        assertFalse(prefs.commitSummarySections("[]"))
        assertTrue(prefs.summarizerStorageFailure is java.io.IOException)
        assertEquals("SharedPreferences.commit() returned false", prefs.summarizerStorageFailure?.message)
        assertEquals("old", prefs.getSummarySections())
    }

    @Test fun migrationAndCheckpointFailuresRetainEvidence() {
        val error = IllegalStateException("write unavailable")
        val prefs = failingPreferences(error)
        assertFalse(prefs.ensureSummarizerProjectionCompatibility())
        assertSame(error, prefs.summarizerStorageFailure)
        assertFalse(prefs.restoreCompactionCheckpoint(mapOf("summarizer_folded" to "0")))
        assertSame(error, prefs.summarizerStorageFailure)
    }

    @Test fun corruptSectionsAreLoggedAndNotOverwritten() {
        val backing = FakeSharedPreferences()
        backing.edit().putString("summary_sections", "{broken JSON").commit()
        val prefs = Preferences(backing, FakeSharedPreferences(), "chat")
        assertNull(SummarySectionStore.load(prefs, emptyList()))
        assertNotNull(prefs.summarizerStorageFailure)
        assertEquals("{broken JSON", prefs.getSummarySections())
    }
    @Test fun invalidSectionOwnershipIsDiagnosedWithoutDiscardingStoredState() {
        val backing = FakeSharedPreferences()
        val corrupt = "[{\"messageIds\":[\"a\"],\"fingerprints\":[]}]"
        backing.edit().putString("summary_sections", corrupt).commit()
        val prefs = Preferences(backing, FakeSharedPreferences(), "chat")
        assertNull(SummarySectionStore.load(prefs, emptyList()))
        assertTrue(prefs.summarizerStorageFailure is IllegalStateException)
        assertEquals(corrupt, prefs.getSummarySections())
    }

}
