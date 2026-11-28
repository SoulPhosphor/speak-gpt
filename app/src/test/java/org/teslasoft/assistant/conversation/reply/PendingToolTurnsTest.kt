package org.teslasoft.assistant.conversation.reply

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.model.ModelId
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PendingToolTurnsTest {
    private fun turn() = ToolTurnContext("approval", "endpoint", ChatCompletionRequest(
        model = ModelId("fixture"), messages = emptyList()
    ), true, emptyList(), null)

    @Test fun renamingMovesTheExactRequestWithoutLeavingTheOldChatHoldingIt() {
        val old = UUID.randomUUID().toString()
        val new = UUID.randomUUID().toString()
        val context = turn()
        PendingToolTurns.keep(old, context)
        ReplyRunRegistry.rename(old, new)
        assertNull(PendingToolTurns.take(old, "approval"))
        assertSame(context, PendingToolTurns.take(new, "approval"))
    }

    @Test fun deletionReleasesAParkedRequestEvenWhenNoLiveRunIsRegistered() {
        val id = UUID.randomUUID().toString()
        PendingToolTurns.keep(id, turn())
        ReplyRunRegistry.markChatsDeleted(setOf(id))
        assertNull(PendingToolTurns.take(id, "approval"))
    }

    @Test fun replacingTheChatSetReleasesParkedRequestsAndOldDeletionTombstones() {
        val id = UUID.randomUUID().toString()
        ReplyRunRegistry.markChatsDeleted(setOf(id))
        PendingToolTurns.keep(id, turn())
        val read = ReplyRunRegistry.capture(id)
        ReplyRunRegistry.discardAll()
        assertFalse(ReplyRunRegistry.isCurrent(id, read))
        assertNull(PendingToolTurns.take(id, "approval"))
        assertFalse(ReplyRunRegistry.isDeleted(id))
    }
}
