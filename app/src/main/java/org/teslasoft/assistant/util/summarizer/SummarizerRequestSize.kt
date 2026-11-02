package org.teslasoft.assistant.util.summarizer

import org.teslasoft.assistant.preferences.includes.IncludeTextPolicy

/** Numeric evidence only: never retains request or conversation text. */
data class SummarizerRequestSize(
    val batchMessages: Int,
    val estimatedBatchTokens: Int,
    val estimatedExistingSummaryTokens: Int,
    val requestTextCharacters: Int,
    val estimatedRequestTokens: Int,
    val configuredBatchTokens: Int?,
    val attemptBatchTokens: Int?
) {
    fun format(): String = buildString {
        configuredBatchTokens?.let { append("Configured Batch Token Amount: $it\n") }
        attemptBatchTokens?.let { append("Attempt Batch Token Amount: $it\n") }
        append("Batch Messages: $batchMessages\n")
        append("Estimated Batch Tokens: $estimatedBatchTokens\n")
        append("Estimated Existing Summary Tokens: $estimatedExistingSummaryTokens\n")
        append("Request Text Characters: $requestTextCharacters\n")
        append("Estimated Request Tokens: $estimatedRequestTokens\n")
        append("Output Token Limit: Not Sent")
    }

    companion object {
        fun capture(
            body: String,
            batch: List<String>,
            existingSummary: String = "",
            configuredBatchTokens: Int? = null,
            attemptBatchTokens: Int? = null
        ) = SummarizerRequestSize(
            batchMessages = batch.size,
            estimatedBatchTokens = batch.sumOf(IncludeTextPolicy::estimateTokens),
            estimatedExistingSummaryTokens = IncludeTextPolicy.estimateTokens(existingSummary),
            requestTextCharacters = body.length,
            estimatedRequestTokens = IncludeTextPolicy.estimateTokens(body),
            configuredBatchTokens = configuredBatchTokens,
            attemptBatchTokens = attemptBatchTokens
        )
    }
}
