package org.teslasoft.assistant.preferences

import android.content.SharedPreferences
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class MessageBookmarksTest {
    private val keys = setOf("chat", "content_modified_at", "search_revision", "search_projection_fingerprint")

    private class MemoryFirstFailures(val backing: FakeSharedPreferences, val failAgain: Boolean) : SharedPreferences by backing {
        var attempts = 0
        override fun edit(): SharedPreferences.Editor {
            val editor = backing.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    editor.putString(key, value)
                    return this
                }
                override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                    editor.putLong(key, value)
                    return this
                }
                override fun commit(): Boolean {
                    editor.commit()
                    attempts++
                    return !failAgain && attempts > 1
                }
            }
        }
    }

    @Test fun successfulToggleCommitsOnce() {
        val message = hashMapOf<String, Any>("message" to "answer")
        var commits = 0
        assertTrue(MessageBookmarks.toggleAndSave(message) { changed ->
            assertTrue(changed)
            commits++
            true
        })
        assertEquals(1, commits)
        assertTrue(MessageBookmarks.isBookmarked(message))
    }

    @Test fun failedAdditionRestoresHistoryAndRemovesNewMetadataEvenWhenRollbackDiskWriteFails() {
        for (failAgain in listOf(false, true)) {
            val message = hashMapOf<String, Any>("message" to "answer")
            val backing = FakeSharedPreferences().apply {
                edit().putString("chat", Gson().toJson(message)).putString("unrelated", "keep").commit()
            }
            val previous = backing.all
            val prefs = MemoryFirstFailures(backing, failAgain)
            assertFalse(MessageBookmarks.toggleAndSave(message) { changed ->
                assertTrue(changed)
                PreferenceCommitRollback.commit(prefs, prefs.edit()
                    .putString("chat", Gson().toJson(message))
                    .putLong("content_modified_at", 100L)
                    .putString("search_revision", "new revision")
                    .putString("search_projection_fingerprint", "new fingerprint"), keys)
            })
            assertEquals(2, prefs.attempts)
            assertEquals(previous, backing.all)
            assertFalse(MessageBookmarks.isBookmarked(message))
        }
    }

    @Test fun failedRemovalRestoresTheExactPreviousMarkerAndAllExistingMetadata() {
        for (original in listOf(true, "true")) {
            val message = hashMapOf<String, Any>("message" to "answer", MessageBookmarks.KEY to original)
            val backing = FakeSharedPreferences().apply {
                edit().putString("chat", Gson().toJson(message))
                    .putLong("content_modified_at", 10L)
                    .putString("search_revision", "old revision")
                    .putString("search_projection_fingerprint", "old fingerprint").commit()
            }
            val previous = backing.all
            val prefs = MemoryFirstFailures(backing, false)
            assertFalse(MessageBookmarks.toggleAndSave(message) {
                PreferenceCommitRollback.commit(prefs, prefs.edit()
                    .putString("chat", Gson().toJson(message))
                    .putLong("content_modified_at", 100L)
                    .putString("search_revision", "new revision")
                    .putString("search_projection_fingerprint", "new fingerprint"), keys)
            })
            assertEquals(original, message[MessageBookmarks.KEY])
            assertEquals(previous, backing.all)
        }
    }
}
