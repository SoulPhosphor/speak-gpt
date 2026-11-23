package org.teslasoft.assistant.preferences.memory
import org.junit.Assert.*
import org.junit.Test
class PendingPairSnapshotTest {
    private fun memory(id: String) = MemoryRecord(id, "global", "Wording $id", null, "[]", 0,
        emptyList(), emptyList(), emptyList(), emptyList(), null, "[]", "2026-01-01T00:00:00Z",
        null, "draft", null, emptyList(), emptyList(), emptyList())
    private val a = memory("a")
    private val b = memory("b")
    private val links = mapOf("a" to setOf("b"), "b" to setOf("a"))
    private val pair = PendingPairSnapshot(a, b, links)
    private fun holds(records: Map<String, MemoryRecord>, hints: Map<String, Set<String>> = links) = pair.holds({records[it]}, {hints[it].orEmpty()})
    @Test fun pairAndBothLinkDirectionsMustStillMatch() {
        assertTrue(pair.isLinkedAlternative)
        assertFalse(pair.copy(links=emptyMap()).isLinkedAlternative)
        assertFalse(pair.copy(links=mapOf("a" to setOf("b"))).isLinkedAlternative)
        assertTrue(holds(mapOf("a" to a, "b" to b)))
        assertFalse(holds(mapOf("a" to a)))
        assertFalse(holds(mapOf("a" to a, "b" to b), links + ("a" to emptySet())))
        assertFalse(holds(mapOf("a" to a, "b" to b), links + ("b" to setOf("a", "new"))))
    }
    @Test fun everyVisibleEditOrStatusChangeOnEitherSideInvalidatesTheDecision() {
        for (base in listOf(a,b)) for (updated in listOf(base.copy(content="changed"), base.copy(status="active"),
            base.copy(status="archived"), base.copy(scope="real_life"), base.copy(tagsJson="[\"x\"]"),
            base.copy(importance=2), base.copy(companionIds=listOf("other")), base.copy(typeId="other"))) {
            assertFalse(holds(mapOf("a" to a, "b" to b)+(base.memoryId to updated)))
        }
    }
    @Test fun backgroundDerivedTextDoesNotInvalidateTheChoice() {
        assertTrue(holds(mapOf("a" to a.copy(embeddingText="derived",updatedAt="later"),"b" to b)))
    }
    @Test fun selfPairsAndAlreadyActiveShownRecordsCannotAuthorizeActions() {
        assertFalse(PendingPairSnapshot(a,a,links).holds({a},{links[it].orEmpty()}))
        assertFalse(PendingPairSnapshot(a.copy(status="active"),b,links).holds({if(it=="a")a else b},{links[it].orEmpty()}))
    }
}
