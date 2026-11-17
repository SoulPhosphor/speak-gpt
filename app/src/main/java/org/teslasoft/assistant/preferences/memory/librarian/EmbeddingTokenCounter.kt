package org.teslasoft.assistant.preferences.memory.librarian

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/** Shares the installed tokenizer with inference; counting never loads an ONNX session. */
object EmbeddingTokenCounter {
    private var cachedKey: String? = null
    private var cachedTokenizer: HfTokenizer? = null
    private val revision = AtomicLong()
    private val modelChanges = MutableStateFlow(0L)
    val changes = modelChanges.asStateFlow()

    /** Signal model install/removal without waiting for tokenization on the UI thread. */
    fun invalidate() { modelChanges.value = revision.incrementAndGet() }

    @Synchronized
    fun tokenizer(context: Context, model: EmbeddingModels.Model): HfTokenizer {
        val file = EmbeddingModelStorage.tokenizerFile(context, model)
        val key = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
        if (key == cachedKey) cachedTokenizer?.let { return it }
        return HfTokenizer.load(file).also {
            cachedKey = key
            cachedTokenizer = it
        }
    }

    /** Called off the main thread. Null means no embedding model is installed. */
    fun reader(context: Context): MemoryTokenBudget.Reader? {
        val model = EmbeddingModelStorage.activeModel(context) ?: return null
        val tokenizer = tokenizer(context, model)
        return MemoryTokenBudget.Reader(model.embeddingTag, model.storedTextMaxTokens) { document ->
            tokenizer.countTokens(model.documentPrefix + document)
        }
    }
}
