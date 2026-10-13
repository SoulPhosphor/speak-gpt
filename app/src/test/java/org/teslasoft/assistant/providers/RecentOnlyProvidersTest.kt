package org.teslasoft.assistant.providers

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentOnlyProvidersTest {

    @Test
    fun aNewChoiceGoesFirstAndTheOldestDropsOffAfterThree() {
        var recents = emptyList<String>()
        for (slug in listOf("a", "b", "c", "d")) recents = RecentOnlyProviders.bump(recents, slug)
        assertEquals(listOf("d", "c", "b"), recents)
    }

    @Test
    fun choosingAProviderAgainMovesItFirstWithoutDuplicating() {
        assertEquals(listOf("b", "a", "c"), RecentOnlyProviders.bump(listOf("a", "b", "c"), "b"))
        assertEquals(listOf("B", "a", "c"), RecentOnlyProviders.bump(listOf("a", "b", "c"), "B"))
    }

    @Test
    fun aBlankProviderIsNeverRecorded() {
        assertEquals(listOf("a"), RecentOnlyProviders.bump(listOf("a"), ""))
    }

    @Test
    fun theProviderInUseIsAlwaysListedFirst() {
        assertEquals(listOf("x"), RecentOnlyProviders.options("x", emptyList()))
        assertEquals(listOf("b", "a", "c"), RecentOnlyProviders.options("b", listOf("a", "b", "c")))
        assertEquals(listOf("z", "a", "b"), RecentOnlyProviders.options("z", listOf("a", "b", "c")))
    }

    @Test
    fun providersNoLongerServingTheModelAreDroppedButTheOneInUseStays() {
        val available = setOf("a", "c")
        assertEquals(listOf("b", "a", "c"),
            RecentOnlyProviders.withoutUnavailable(listOf("b", "a", "c"), "b", available))
        assertEquals(listOf("a"),
            RecentOnlyProviders.withoutUnavailable(listOf("a", "b"), "a", available))
        assertEquals(listOf("A"),
            RecentOnlyProviders.withoutUnavailable(listOf("A", "B"), "x", available))
    }
}
