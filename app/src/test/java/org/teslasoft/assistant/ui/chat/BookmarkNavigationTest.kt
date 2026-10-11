package org.teslasoft.assistant.ui.chat

import org.junit.Assert.*
import org.junit.Test

class BookmarkNavigationTest {
    private val targets = listOf(
        BookmarkNavigation.Target("a", 0, "Blue dragons live here"),
        BookmarkNavigation.Target("b", 4, "Another blue dragon"),
        BookmarkNavigation.Target("c", 7, "A quiet mountain")
    )
    private fun navigation() = BookmarkNavigation().apply { refresh(targets); open() }

    @Test fun searchesTheResponseVersionDisplayedByThePager() {
        val message = hashMapOf<String, Any>(
            "message" to "Canonical reply", "canonicalVariant" to "1", "displayVariant" to "0"
        )
        val variants = listOf(mapOf("message" to "Earlier blue dragons"), mapOf("message" to "Canonical reply"))
        val nav = BookmarkNavigation().apply {
            refresh(listOf(BookmarkNavigation.Target("a", 0, BookmarkNavigation.displayedText(message, variants))))
        }
        assertEquals("a", nav.search("blue dragon")?.id)
        assertNull(nav.search("Canonical"))
        message["displayVariant"] = "1"
        nav.refresh(listOf(BookmarkNavigation.Target("a", 0, BookmarkNavigation.displayedText(message, variants))))
        assertEquals("a", nav.current?.id)
        assertNull(nav.search("blue dragon"))
        assertEquals("Canonical reply", message["message"])
    }

    @Test fun displayedTextUsesTheSameDefaultsAndClampedIndicesAsThePager() {
        val message = hashMapOf<String, Any>("message" to "Live canonical")
        val variants = listOf(mapOf("message" to "First reply"), emptyMap())
        assertEquals("Live canonical", BookmarkNavigation.displayedText(message, variants))
        message["displayVariant"] = "-1"
        assertEquals("First reply", BookmarkNavigation.displayedText(message, variants))
        message["displayVariant"] = "999"
        assertEquals("Live canonical", BookmarkNavigation.displayedText(message, variants))
        message["canonicalVariant"] = "0"
        assertEquals("", BookmarkNavigation.displayedText(message, variants))
        assertEquals("Live canonical", BookmarkNavigation.displayedText(message, variants.take(1)))
        assertEquals("Live canonical", BookmarkNavigation.displayedText(message, emptyList()))
    }

    @Test fun wrapsInBothDirectionsInConversationOrder() {
        val nav = navigation()
        assertEquals(1, nav.ordinal)
        assertEquals("c", nav.move(-1)?.id)
        assertEquals(3, nav.ordinal)
        assertEquals("a", nav.move(1)?.id)
        assertEquals("b", nav.move(1)?.id)
        assertEquals(2, nav.ordinal)
    }

    @Test fun matchesPartialWordsWithoutCaseSensitivity() {
        val nav = navigation()
        assertEquals("a", nav.search("DRAGON")?.id)
        assertEquals(2, nav.count)
        assertEquals("b", nav.move(1)?.id)
        assertEquals("a", nav.move(1)?.id)
    }

    @Test fun matchesPhrasesAsOneContinuousTerm() {
        val nav = navigation()
        assertEquals("a", nav.search("  dragons live  ")?.id)
        assertEquals(1, nav.count)
        assertFalse(nav.canNavigate)
    }

    @Test fun aSearchWithNoMatchesShowsZeroWithoutJumping() {
        val nav = navigation()
        assertNull(nav.search("missing"))
        assertEquals(0, nav.count)
        assertEquals(0, nav.ordinal)
        assertNull(nav.move(1))
        assertFalse(nav.canNavigate)
        assertTrue(nav.isOpen)
    }

    @Test fun clearingSearchRestoresAllBookmarksAndKeepsCurrentMessage() {
        val nav = navigation()
        nav.search("mountain")
        nav.clearSearch()
        assertEquals(3, nav.count)
        assertEquals(3, nav.ordinal)
        assertNull(nav.query)
        assertTrue(nav.canNavigate)
    }

    @Test fun closingForgetsSearchAndReopeningStartsFresh() {
        val nav = navigation()
        nav.search("mountain")
        nav.close()
        assertNull(nav.query)
        assertNull(nav.current)
        assertFalse(nav.isOpen)
        assertEquals("a", nav.open()?.id)
        assertEquals(3, nav.count)
    }

    @Test fun oneBookmarkJumpsWithoutOpeningThePanel() {
        val nav = BookmarkNavigation()
        nav.refresh(targets.takeLast(1))
        assertEquals("c", nav.open()?.id)
        assertFalse(nav.isOpen)
        assertFalse(nav.canNavigate)
    }

    @Test fun deletingAnEarlierMessageKeepsTheSelectedIdentityAndUpdatesPosition() {
        val nav = navigation()
        nav.move(1)
        nav.refresh(targets.drop(1).map { it.copy(position = it.position - 1) })
        assertEquals("b", nav.current?.id)
        assertEquals(3, nav.current?.position)
        assertEquals(1, nav.ordinal)
    }

    @Test fun removingTheCurrentBookmarkSelectsTheFirstRemainingMatch() {
        val nav = navigation()
        nav.search("dragon")
        nav.refresh(targets.drop(1))
        assertEquals("b", nav.current?.id)
        assertEquals(1, nav.count)
        assertTrue(nav.isOpen)
        assertFalse(nav.canNavigate)
    }

    @Test fun editingABookmarkUpdatesSearchResultsWithoutSavingTheSearch() {
        val nav = navigation()
        nav.search("mountain")
        nav.refresh(targets.map { it.copy(text = "Changed") })
        assertEquals(0, nav.count)
        assertEquals("mountain", nav.query)
        nav.refresh(emptyList())
        assertFalse(nav.hasBookmarks)
        assertTrue(nav.isOpen)
        assertEquals("mountain", nav.query)
        nav.close()
        assertFalse(nav.isOpen)
        assertNull(nav.query)
    }

    @Test fun selectingNavigateAgainKeepsTheExistingSearchPanelOpen() {
        val nav = navigation()
        nav.search("mountain")
        assertEquals("c", nav.open()?.id)
        assertEquals("mountain", nav.query)
        assertTrue(nav.isOpen)
    }
}
