package org.teslasoft.assistant.preferences.lorebook

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatLoreBookSelectionTest {

    @Test
    fun aNewCompanionDefaultsEveryLinkedBookOn() {
        val active = ChatLoreBookSelection.reconcileActiveIds(
            personaId = "new",
            previousPersonaId = "old",
            companionIds = listOf("core", "vacation", "goals"),
            extraIds = emptyList(),
            previousKnownIds = setOf("old-book"),
            previousActiveIds = emptySet()
        )

        assertEquals(linkedSetOf("core", "vacation", "goals"), active)
    }

    @Test
    fun anExistingCompanionPreservesOffChoicesAndDefaultsNewLinksOn() {
        val active = ChatLoreBookSelection.reconcileActiveIds(
            personaId = "same",
            previousPersonaId = "same",
            companionIds = listOf("core", "vacation", "goals"),
            extraIds = emptyList(),
            previousKnownIds = setOf("core", "vacation"),
            previousActiveIds = setOf("core")
        )

        assertEquals(linkedSetOf("core", "goals"), active)
    }

    @Test
    fun unlinkedBooksArePruned() {
        val active = ChatLoreBookSelection.reconcileActiveIds(
            personaId = "same",
            previousPersonaId = "same",
            companionIds = listOf("core"),
            extraIds = emptyList(),
            previousKnownIds = setOf("core", "removed"),
            previousActiveIds = setOf("core", "removed")
        )

        assertEquals(linkedSetOf("core"), active)
    }

    @Test
    fun newlyAddedChatLorebookDefaultsOnAndCanStayOffLater() {
        val first = ChatLoreBookSelection.reconcileActiveIds(
            personaId = "same",
            previousPersonaId = "same",
            companionIds = listOf("core"),
            extraIds = listOf("specialty"),
            previousKnownIds = setOf("core"),
            previousActiveIds = setOf("core")
        )
        assertEquals(linkedSetOf("core", "specialty"), first)

        val later = ChatLoreBookSelection.reconcileActiveIds(
            personaId = "same",
            previousPersonaId = "same",
            companionIds = listOf("core"),
            extraIds = listOf("specialty"),
            previousKnownIds = setOf("core", "specialty"),
            previousActiveIds = setOf("core")
        )
        assertEquals(linkedSetOf("core"), later)
    }

    @Test
    fun changingCompanionKeepsEnabledChatExtrasButDropsOldCompanionLinks() {
        val active = ChatLoreBookSelection.reconcileActiveIds(
            personaId = "new",
            previousPersonaId = "old",
            companionIds = listOf("new-core", "new-link"),
            extraIds = listOf("specialty"),
            previousKnownIds = setOf("old-core", "specialty"),
            previousActiveIds = setOf("old-core", "specialty")
        )

        assertEquals(linkedSetOf("new-core", "new-link", "specialty"), active)
    }
}
