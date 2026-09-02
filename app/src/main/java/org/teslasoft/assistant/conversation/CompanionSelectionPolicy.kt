package org.teslasoft.assistant.conversation

/** Pure selection recovery shared by chat startup and covered without Android. */
object CompanionSelectionPolicy {
    fun resolve(
        currentId: String?,
        lastSuccessfulId: String?,
        availableIds: List<String>
    ): String? {
        if (availableIds.isEmpty()) return null
        return currentId?.takeIf(availableIds::contains)
            ?: lastSuccessfulId?.takeIf(availableIds::contains)
            ?: availableIds.first()
    }
}
