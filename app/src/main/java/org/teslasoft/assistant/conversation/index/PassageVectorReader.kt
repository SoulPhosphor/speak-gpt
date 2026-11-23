/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.conversation.index

import org.teslasoft.assistant.preferences.memory.librarian.EmbeddingModel
import org.teslasoft.assistant.preferences.memory.librarian.EmbeddingQueue

/**
 * Makes one passage vector from a complete model input (memory development
 * plan G.5a rules 2 and 6). The input already holds the instruction prefix
 * with the passage's title; nothing is added and nothing is cut.
 */
interface PassageVectorReader {
    val vectorModel: String
    val vectorDimensions: Int

    /** The most tokens one input may have, special tokens included. */
    val tokenLimit: Int

    /** The tokens the model reads for [modelInput], counted with its own tokenizer. */
    fun countTokens(modelInput: String): Int

    /**
     * One background read. Throws
     * [org.teslasoft.assistant.preferences.memory.librarian.InputTooLongException]
     * for an input over [tokenLimit] without reading it.
     */
    fun read(modelInput: String): FloatArray
}

/**
 * Reads through the app's one embedding queue at background priority, one
 * text per queue turn, so a live request waits for at most the one read that
 * is running, and the queue stops that read where the model allows it.
 */
class QueuedPassageVectorReader(
    private val queue: EmbeddingQueue,
    private val model: EmbeddingModel
) : PassageVectorReader {
    override val vectorModel: String get() = model.tag
    override val vectorDimensions: Int get() = model.dimensions
    override val tokenLimit: Int get() = model.storedTextTokenLimit

    override fun countTokens(modelInput: String): Int = model.countExactInputTokens(modelInput)

    override fun read(modelInput: String): FloatArray =
        queue.run(EmbeddingQueue.Priority.BACKGROUND) { control ->
            model.embedExactInput(modelInput, tokenLimit, control)
        }
}
