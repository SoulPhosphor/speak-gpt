package org.teslasoft.assistant.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.memory.MemoryRecord
import org.teslasoft.assistant.preferences.memory.librarian.EmbeddingTokenCounter
import org.teslasoft.assistant.preferences.memory.librarian.MemoryTokenBudget

/** Lifecycle-bound, conflated off-thread measurements. Rebinding invalidates old results. */
class MemoryTokenMeter @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    private data class Request(val revision: Long, val input: MemoryTokenBudget.Input)
    private val requests = MutableStateFlow<Request?>(null)
    private var revision = 0L
    private var scope: CoroutineScope? = null
    private var worker: Job? = null
    private val words: TextView
    private val tokens: TextView
    private val bar: LinearProgressIndicator
    internal var measure: (MemoryTokenBudget.Input) -> MemoryTokenBudget.Usage? = {
        EmbeddingTokenCounter.reader(context.applicationContext)?.measure(it)
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.view_memory_token_meter, this, true)
        words = findViewById(R.id.memory_word_count)
        tokens = findViewById(R.id.memory_token_count)
        bar = findViewById(R.id.memory_token_progress)
    }

    /** Shows a different memory: earlier counts are cleared without changing the meter's height. */
    fun bind(input: MemoryTokenBudget.Input) {
        enqueue(input)
        words.setText(R.string.mem_words_pending)
        tokens.setText(R.string.mem_tokens_pending)
        tokens.isActivated = false
        if (bar.visibility == View.VISIBLE) bar.visibility = View.INVISIBLE
    }

    /** Re-measures the memory already shown; current counts stay until the new ones replace them. */
    fun refresh(input: MemoryTokenBudget.Input) = enqueue(input)

    private fun enqueue(input: MemoryTokenBudget.Input) {
        requests.value = Request(++revision, input.copy(tags = input.tags.toList()))
    }

    fun bind(record: MemoryRecord) = bind(input(record))

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val activeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = activeScope
        worker = activeScope.launch {
            requests.collectLatest { request ->
                if (request == null) return@collectLatest
                delay(120)
                val result = withContext(Dispatchers.Default) {
                    val wordCount = MemoryTokenBudget.words(request.input.content)
                    val usage = runCatching { measure(request.input) }.getOrNull()
                    wordCount to usage
                }
                if (requests.value?.revision != request.revision) return@collectLatest
                words.text = resources.getQuantityString(R.plurals.mem_word_count, result.first, result.first)
                val usage = result.second
                if (usage == null) {
                    tokens.setText(R.string.mem_tokens_unavailable)
                    tokens.isActivated = false
                    bar.visibility = View.GONE
                } else {
                    tokens.text = context.getString(R.string.mem_token_count, usage.tokens, usage.limit)
                    tokens.isActivated = usage.tokens >= usage.limit
                    bar.visibility = View.VISIBLE
                    bar.setProgressCompat(usage.percent, false)
                }
            }
        }
        activeScope.launch {
            EmbeddingTokenCounter.changes.drop(1).collect {
                requests.value?.input?.let { refresh(it) }
            }
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE && isAttachedToWindow) {
            requests.value?.input?.let { refresh(it) }
        }
    }

    override fun onDetachedFromWindow() {
        worker?.cancel()
        scope?.cancel()
        scope = null
        super.onDetachedFromWindow()
    }

    companion object {
        fun input(record: MemoryRecord): MemoryTokenBudget.Input = MemoryTokenBudget.Input.from(record)
    }
}
