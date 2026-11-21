package org.teslasoft.assistant.conversation.responsegroups

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import org.teslasoft.assistant.preferences.FakeSharedPreferences
import org.teslasoft.assistant.preferences.PreferenceCommitRollback
import org.teslasoft.assistant.preferences.SecurePrefs

class ResponseGroupFailedCommitTest {
    @Test fun failedCommitRestoresEnvelopeMirrorAndMergeEvidenceEvenWhenRollbackDiskWriteFails() {
        val oldHistory = javaClass.classLoader!!.getResource("response-groups/history.json")!!.readText()
        val backing = FakeSharedPreferences()
        backing.edit().putString("chat", oldHistory).putString("response_group_fingerprint", "old-group")
            .putString("search_revision", "old-revision").putString("search_projection_fingerprint", "old-search")
            .putLong("content_modified_at", 7L).commit()
        val previous = backing.all
        val failing = object : SharedPreferences by backing {
            override fun edit(): SharedPreferences.Editor {
                val delegate = backing.edit()
                return object : SharedPreferences.Editor by delegate {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        delegate.putString(key, value)
                        return this
                    }
                    override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                        delegate.putLong(key, value)
                        return this
                    }
                    override fun commit(): Boolean { delegate.commit(); return false }
                }
            }
        }
        assertFalse(PreferenceCommitRollback.commit(failing, failing.edit().putString("chat", "changed")
            .putString("response_group_fingerprint", "changed").putString("search_revision", "changed")
            .putString("search_projection_fingerprint", "changed").putLong("content_modified_at", 8L),
            setOf("chat", "response_group_fingerprint", "search_revision", "search_projection_fingerprint", "content_modified_at")))
        assertEquals(previous, backing.all)
        assertFalse(PreferenceCommitRollback.commit(SecurePrefs.LockedSharedPreferences,
            SecurePrefs.LockedSharedPreferences.edit().putString("chat", "changed"), setOf("chat")))
        assertEquals(oldHistory, backing.getString("chat", null))
    }
}
