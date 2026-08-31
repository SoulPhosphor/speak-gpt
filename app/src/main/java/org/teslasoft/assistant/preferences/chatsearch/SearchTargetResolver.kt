package org.teslasoft.assistant.preferences.chatsearch

/** Verifies a Search result against the authoritative history before navigation. */
object SearchTargetResolver {
    const val EXTRA_MESSAGE_ID = "search_message_id"
    const val EXTRA_LEGACY_ORDINAL = "search_legacy_ordinal"
    const val EXTRA_LEGACY_ROLE = "search_legacy_role"
    const val EXTRA_FINGERPRINT = "search_content_fingerprint"

    fun resolve(
        messages: List<Map<String, Any>>,
        messageId: String?,
        legacyOrdinal: Int?,
        legacyRole: String?,
        fingerprint: String?
    ): Int? {
        if (!messageId.isNullOrBlank()) {
            val hits = messages.indices.filter {
                messages[it][SearchableMessageProjection.MESSAGE_ID_KEY]?.toString() == messageId
            }
            return hits.singleOrNull()
        }
        if (fingerprint.isNullOrBlank() || legacyRole.isNullOrBlank()) return null
        fun matches(index: Int): Boolean {
            val row = messages.getOrNull(index) ?: return false
            val role = if (row["isBot"] == true || row["isBot"]?.toString() == "true") "assistant" else "user"
            val text = row["message"]?.toString().orEmpty()
            return role == legacyRole &&
                SearchableMessageProjection.fingerprint("$role\u001f$text") == fingerprint
        }
        if (legacyOrdinal != null && matches(legacyOrdinal)) return legacyOrdinal
        return messages.indices.filter(::matches).singleOrNull()
    }
}

