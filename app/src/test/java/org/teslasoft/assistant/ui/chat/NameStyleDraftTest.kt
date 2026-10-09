package org.teslasoft.assistant.ui.chat

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NameStyleDraftTest {
    @Test fun restoreInheritedStyleKeepsFollowingDefaults() {
        val draft = NameStyleDraft(ChatNameStyle.Override())
        draft.pending = ChatNameStyle.Override("kalnia", 18, "bold")
        assertTrue(draft.dirty)
        draft.restoreOriginal()
        assertFalse(draft.dirty)
        val changedDefault = ChatNameStyle.Resolved("solitreo", 23, false)
        assertEquals(changedDefault, ChatNameStyle.withOverride(changedDefault, draft.pending))
    }

    @Test fun restoreCustomStylePreservesPartiallyInheritedFields() {
        val original = ChatNameStyle.Override(fontId = "kalnia")
        val draft = NameStyleDraft(original)
        draft.pending = original.copy(sizeSp = 30, fontStyle = "italic")
        draft.restoreOriginal()
        assertEquals(original, draft.pending)
        val base = ChatNameStyle.Resolved("roboto", 21, true)
        assertEquals(ChatNameStyle.Resolved("kalnia", 21, true), ChatNameStyle.withOverride(base, draft.pending))
    }

    @Test fun successfulSaveBecomesTheNewRestorePoint() {
        val original = ChatNameStyle.Override(fontId = "kalnia")
        val draft = NameStyleDraft(original)
        val saved = original.copy(sizeSp = 18)
        draft.pending = saved
        assertEquals(original, draft.saved)
        draft.markSaved(saved)
        draft.pending = saved.copy(sizeSp = 28)
        draft.restoreOriginal()
        assertEquals(saved, draft.pending)
        assertFalse(draft.dirty)
    }

    @Test fun recreationPreservesBothSavedAndUnsavedValues() {
        val draft = NameStyleDraft(ChatNameStyle.Override(), ChatNameStyle.Override(sizeSp = 26))
        val restored = Gson().fromJson(Gson().toJson(draft), NameStyleDraft::class.java)
        assertEquals(draft, restored)
        assertTrue(restored.dirty)
        restored.restoreOriginal()
        assertEquals(ChatNameStyle.Override(), restored.pending)
    }
}
