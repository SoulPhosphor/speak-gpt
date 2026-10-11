package org.teslasoft.assistant.ui.drawer

import org.teslasoft.assistant.preferences.MessageBookmarks
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Derived from the conversation itself, so imports/restores need no separate marker store. */
data class ChatListDetails(val hasBookmarks: Boolean, val startedAt: Long?, val mostUsedModel: String? = null) {
    companion object {
        fun fromMessages(messages: List<Map<String, *>>, startedAt: Long? = null): ChatListDetails = ChatListDetails(
            messages.any(MessageBookmarks::isBookmarked),
            startedAt?.takeIf { it > 0 } ?: messages.mapNotNull { message ->
                val raw = message["messageTime"]
                val time = when (raw) {
                    is Number -> raw.toDouble().takeIf { it.isFinite() && it > 0 && it < Long.MAX_VALUE }
                        ?.toLong()
                    else -> raw?.toString()?.toLongOrNull()
                }
                time?.takeIf { it > 0 }
            }.minOrNull()
        )

        fun metadata(
            startedAt: Long?, model: String?,
            locale: Locale = Locale.getDefault(), timeZone: TimeZone = TimeZone.getDefault()
        ): String = listOfNotNull(
            startedAt?.takeIf { it > 0 }?.let {
                DateFormat.getDateInstance(DateFormat.LONG, locale).apply { this.timeZone = timeZone }
                    .format(Date(it))
            },
            model?.takeIf { it.isNotBlank() }
        ).joinToString(" · ")
    }
}
