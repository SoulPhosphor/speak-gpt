package org.teslasoft.assistant.preferences.chatsearch

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import org.teslasoft.assistant.preferences.MessageCompletionState

object SearchableMessageProjection {
    const val MESSAGE_ID_KEY = "message_id"
    private const val MESSAGE_TIME_KEY = "messageTime"
    private const val IMAGE_CONFIRMATION_KEY = "imageConfirmationCard"
    private const val IMAGE_PROGRESS_KEY = "imageProgressCard"

    data class Projected(
        val ordinal: Int,
        val role: String,
        val text: String,
        val messageId: String?,
        val timestamp: Long?,
        val fingerprint: String
    )

    fun project(messages: List<Map<String, Any>>, locale: Locale = Locale.getDefault()): List<Projected> {
        val idCounts = messages.mapNotNull { row ->
            row[MESSAGE_ID_KEY]?.toString()?.takeIf(::isUuid)
        }.groupingBy { it }.eachCount()
        return messages.mapIndexedNotNull { ordinal, row ->
            if (row[IMAGE_CONFIRMATION_KEY].asBoolean() || row[IMAGE_PROGRESS_KEY].asBoolean()) {
                return@mapIndexedNotNull null
            }
            val isBot = row["isBot"].asBoolean()
            if (isBot && row[MessageCompletionState.KEY_STATE]?.toString() == MessageCompletionState.STREAMING) {
                return@mapIndexedNotNull null
            }
            val text = row["message"]?.toString().orEmpty()
            if (text.isBlank() || text.trimStart().startsWith("~file:")) return@mapIndexedNotNull null
            val candidateId = row[MESSAGE_ID_KEY]?.toString()?.takeIf(::isUuid)
            val stableId = candidateId?.takeIf { idCounts[it] == 1 }
            val role = if (isBot) "assistant" else "user"
            Projected(
                ordinal = ordinal,
                role = role,
                text = text,
                messageId = stableId,
                timestamp = row[MESSAGE_TIME_KEY]?.toString()?.toLongOrNull(),
                fingerprint = fingerprint("$role\u001f$text")
            )
        }
    }

    fun projectionFingerprint(messages: List<Map<String, Any>>): String =
        fingerprint(project(messages).joinToString("\u001e") {
            "${it.ordinal}\u001f${it.role}\u001f${it.messageId.orEmpty()}\u001f${it.fingerprint}"
        })

    fun documentKey(chatId: String, projected: Projected): String = projected.messageId?.let {
        "message:$chatId:$it"
    } ?: "legacy:$chatId:${projected.ordinal}:${projected.role}:${projected.timestamp ?: 0}:${projected.fingerprint}"

    fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun Any?.asBoolean(): Boolean = this == true || toString() == "true"

    private fun isUuid(value: String): Boolean = try {
        UUID.fromString(value).toString().equals(value, ignoreCase = true)
    } catch (_: Exception) { false }
}
