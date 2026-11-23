package org.teslasoft.assistant.preferences.memory

/** Exactly the pair the user saw, including its relationships. No similarity
 * score authorizes a write; each decision explicitly names a Pending record. */
data class PendingPairSnapshot(
    val proposal: MemoryRecord,
    val related: MemoryRecord,
    val links: Map<String, Set<String>>
) {
    val records: List<MemoryRecord> get() = listOf(proposal, related)
    val ids: Set<String> get() = records.map { it.memoryId }.toSet()
    fun holds(current: (String) -> MemoryRecord?, currentLinks: (String) -> Set<String>): Boolean =
        ids.size == 2 && records.all { shown ->
            shown.status == "draft" && current(shown.memoryId)?.let { live ->
                live.status == "draft" && PendingMatchPresentation.reviewFingerprint(live) ==
                    PendingMatchPresentation.reviewFingerprint(shown)
            } == true && links[shown.memoryId] == currentLinks(shown.memoryId)
        }
}

enum class PendingPairAction { KEEP_BOTH, EDIT, DISCARD, SAVE }
