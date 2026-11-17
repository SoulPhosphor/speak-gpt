package org.teslasoft.assistant.ui.activities.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which memories each Memory Browser view lists (memory plan Phase 2). */
class MemoryBrowserViewsTest {

    private val statuses = listOf("active", "draft", "archived", "superseded")

    private fun listed(view: String, superseded: String = "hide") =
        statuses.filter { MemoryBrowserViews.lists(view, it, superseded) }

    @Test
    fun memoriesListsOnlyMemoriesInUse() {
        assertEquals(listOf("active"), listed(MemoryBrowserViews.MEMORIES))
    }

    @Test
    fun archivedMemoriesAreListedOnlyInArchived() {
        assertEquals(listOf("archived"), listed(MemoryBrowserViews.ARCHIVED))
        for (superseded in listOf("hide", "include", "only")) {
            assertFalse(MemoryBrowserViews.lists(MemoryBrowserViews.MEMORIES, "archived", superseded))
        }
    }

    @Test
    fun pendingListsOnlySuggestions() {
        assertEquals(listOf("draft"), listed(MemoryBrowserViews.PENDING))
    }

    @Test
    fun supersededVersionsStayBehindTheSupersededFilterInMemories() {
        assertEquals(listOf("active", "superseded"), listed(MemoryBrowserViews.MEMORIES, "include"))
        assertEquals(listOf("superseded"), listed(MemoryBrowserViews.MEMORIES, "only"))
        assertFalse(MemoryBrowserViews.lists(MemoryBrowserViews.ARCHIVED, "superseded", "include"))
    }

    @Test
    fun entryPointStatusesOpenTheMatchingView() {
        for (view in listOf(MemoryBrowserViews.MEMORIES, MemoryBrowserViews.PENDING, MemoryBrowserViews.ARCHIVED)) {
            assertEquals(view, MemoryBrowserViews.viewFor(setOf(MemoryBrowserViews.statusFor(view))))
        }
        assertEquals(MemoryBrowserViews.MEMORIES, MemoryBrowserViews.viewFor(emptySet()))
        assertTrue(MemoryBrowserViews.viewFor(setOf("draft", "active")) == MemoryBrowserViews.MEMORIES)
    }
}
