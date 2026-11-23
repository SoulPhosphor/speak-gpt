/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.index

/**
 * Brings one chat's passages up to date, one message at a time (memory
 * development plan G.5a rule 4 and "The indexing steps for one new or
 * changed message", steps 1–5; vectors are only queued as "waiting").
 *
 * Messages are compared with the index by permanent message ID and
 * original-text fingerprint, and only messages that are new, changed or gone
 * are written. Each write is its own transaction against the recorded chat
 * version: cutting and measuring happen first, and the transaction then
 * checks again that the chat is still at that version and that the message
 * is still as compared. A result that finishes after the chat changed is
 * thrown away. A failure leaves every message either as it was or fully
 * replaced, and the chat marked as needing a refresh.
 *
 * Run off the main thread. Nothing in the app calls this yet (Step 10).
 */
class ConversationIndexUpdater(
    private val storage: ConversationIndexStorage,
    private val reader: SearchTextReader,
    private val targetTokens: Int,
    private val overlapWords: Int,
    private val titles: SpeakerTitlePolicy = SpeakerTitlePolicy.DEFAULT
) {
    init {
        require(targetTokens in 1..reader.hardLimit) { "targetTokens must be within the model's limit" }
        require(overlapWords >= 0) { "overlapWords must not be negative" }
    }

    sealed interface Outcome {
        /** The index now describes the chat at the recorded version. */
        data class Updated(val written: Int, val restamped: Int, val removed: Int, val unchanged: Int) : Outcome

        /** The chat changed during the update; the rest of the result was thrown away. */
        data object Stale : Outcome

        data object Cancelled : Outcome

        /** Something failed; the chat is left marked as needing a refresh. */
        data class Failed(val cause: Exception) : Outcome
    }

    private val searchSettings = SearchSettings(targetTokens, overlapWords, reader.key).encoded

    /**
     * @param messages every stored message of the chat, in stored order, as
     *   it was at [chatVersion].
     * @param currentChatVersion the chat's version now; checked before each write.
     * @param isCancelled checked between messages.
     */
    fun update(
        chatId: String,
        chatVersion: String,
        messages: List<IndexSourceMessage>,
        currentChatVersion: () -> String?,
        isCancelled: () -> Boolean = { false }
    ): Outcome {
        val chatUnchanged = { currentChatVersion() == chatVersion }
        return try {
            if (!chatUnchanged()) return Outcome.Stale
            storage.beginUpdate(chatId)
            val indexed = storage.indexedMessages(chatId)
            val wanted = wantedStamps(messages)
            val final = HashMap<String, MessageStamp>()
            var written = 0
            var restamped = 0
            var removed = 0
            var unchanged = 0

            for ((message, stamp) in wanted) {
                if (isCancelled()) return Outcome.Cancelled
                val id = message.messageId
                val current = indexed[id]
                when {
                    current == stamp -> unchanged++
                    current != null && current.sameSearchTexts(stamp) -> {
                        if (storage.restampMessage(chatId, id, current, stamp, chatUnchanged) == IndexWrite.STALE) {
                            return Outcome.Stale
                        }
                        restamped++
                    }
                    else -> {
                        val built = PassageSearchText.build(
                            message.modelText, stamp.speakerTitle, reader, targetTokens, overlapWords
                        )
                        check(built.cutterVersion == stamp.cutterVersion)
                        val entries = MessageEntries(id, stamp, built.passages)
                        if (storage.writeMessage(chatId, id, current, entries, chatUnchanged) == IndexWrite.STALE) {
                            return Outcome.Stale
                        }
                        written++
                    }
                }
                final[id] = stamp
            }
            for ((id, current) in indexed) {
                if (id in final) continue
                if (isCancelled()) return Outcome.Cancelled
                if (storage.writeMessage(chatId, id, current, null, chatUnchanged) == IndexWrite.STALE) {
                    return Outcome.Stale
                }
                removed++
            }
            if (storage.finishUpdate(chatId, chatVersion, final, chatUnchanged) == IndexWrite.STALE) {
                return Outcome.Stale
            }
            Outcome.Updated(written, restamped, removed, unchanged)
        } catch (e: Exception) {
            Outcome.Failed(e)
        }
    }

    /** Steps 1 and 3: the eligible messages, each with the stamp its passages must carry. */
    private fun wantedStamps(messages: List<IndexSourceMessage>): List<Pair<IndexSourceMessage, MessageStamp>> {
        val wanted = ArrayList<Pair<IndexSourceMessage, MessageStamp>>()
        val seen = HashSet<String>()
        var exchangeId: String? = null
        messages.forEachIndexed { position, message ->
            if (!message.isBot) exchangeId = message.messageId.takeIf { it.isNotBlank() }
            // A permanent ID is unique; a repeated one is never indexed twice.
            if (!IndexEligibility.isEligible(message) || !seen.add(message.messageId)) return@forEachIndexed
            wanted.add(message to MessageStamp(
                textFingerprint = IndexFingerprint.of(message.modelText),
                speaker = Speaker.of(message.isBot),
                speakerTitle = SearchTextReader.checkedTitle(titles.titleFor(message)),
                exchangeId = exchangeId,
                chatPosition = position,
                timeMillis = message.timeMillis,
                scope = message.scope,
                cutterVersion = PassageCutter.CUTTER_VERSION,
                searchSettings = searchSettings,
                vectorModel = reader.vectorModel,
                vectorDimensions = reader.vectorDimensions
            ))
        }
        return wanted
    }
}

/**
 * The cutting settings recorded on every passage: the target size, the
 * overlap word count and the reader key. A change means cutting again; the
 * vector worker reads them back to rebuild a passage's search text exactly.
 */
data class SearchSettings(val targetTokens: Int, val overlapWords: Int, val readerKey: String) {
    val encoded: String get() = "cut-t$targetTokens|o$overlapWords|$readerKey"

    companion object {
        private val FORM = Regex("""cut-t(\d+)\|o(\d+)\|(.+)""", RegexOption.DOT_MATCHES_ALL)

        /** The settings in [encoded], or null when it is not in the recorded form. */
        fun decode(encoded: String): SearchSettings? {
            val match = FORM.matchEntire(encoded) ?: return null
            val target = match.groupValues[1].toIntOrNull() ?: return null
            val overlap = match.groupValues[2].toIntOrNull() ?: return null
            return SearchSettings(target, overlap, match.groupValues[3])
        }
    }
}
