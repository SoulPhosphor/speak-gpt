package org.teslasoft.assistant.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.getSystemService
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.chatsearch.ChatSearchIndexManager
import org.teslasoft.assistant.preferences.chatsearch.SearchCorpusState
import org.teslasoft.assistant.preferences.chatsearch.SearchDocumentKind
import org.teslasoft.assistant.preferences.chatsearch.SearchOptions
import org.teslasoft.assistant.preferences.chatsearch.SearchResult
import org.teslasoft.assistant.preferences.chatsearch.SearchTargetResolver
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.adapters.SearchResultAdapter

class SearchActivity : FragmentActivity() {
    private lateinit var field: EditText
    private lateinit var wholeWords: MaterialCheckBox
    private lateinit var matchCase: MaterialCheckBox
    private lateinit var status: TextView
    private lateinit var rebuild: MaterialButton
    private lateinit var results: RecyclerView
    private lateinit var adapter: SearchResultAdapter
    private val manager by lazy { ChatSearchIndexManager.get(this) }
    private var queryJob: Job? = null
    private var requestGeneration = 0
    private var nextOffset = 0
    private var hasMore = false
    private var loadingMore = false
    private val loaded = ArrayList<SearchResult>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_search)
        field = findViewById(R.id.field_search)
        wholeWords = findViewById(R.id.check_whole_words)
        matchCase = findViewById(R.id.check_match_case)
        status = findViewById(R.id.search_status)
        rebuild = findViewById(R.id.search_rebuild)
        results = findViewById(R.id.search_results)
        adapter = SearchResultAdapter(this, ::openResult)
        results.layoutManager = LinearLayoutManager(this)
        results.adapter = adapter

        field.setText(savedInstanceState?.getString(STATE_QUERY).orEmpty())
        wholeWords.isChecked = savedInstanceState?.getBoolean(STATE_WHOLE) ?: false
        matchCase.isChecked = savedInstanceState?.getBoolean(STATE_CASE) ?: false
        field.doAfterTextChanged { scheduleQuery(reset = true) }
        wholeWords.setOnCheckedChangeListener { _, _ -> scheduleQuery(reset = true) }
        matchCase.setOnCheckedChangeListener { _, _ -> scheduleQuery(reset = true) }
        field.setOnEditorActionListener { _, _, _ ->
            hideKeyboard()
            true
        }
        findViewById<ImageButton>(R.id.btn_search).setOnClickListener { hideKeyboard() }
        rebuild.setOnClickListener {
            showStatus(SearchCorpusState.PREPARING)
            manager.rebuild { runOnUiThread { scheduleQuery(reset = true, immediate = true) } }
        }
        results.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val layout = recyclerView.layoutManager as? LinearLayoutManager ?: return
                if (hasMore && !loadingMore && layout.findLastVisibleItemPosition() >= adapter.itemCount - 5) {
                    scheduleQuery(reset = false, immediate = true)
                }
            }
        })

        manager.ensureReady { runOnUiThread { scheduleQuery(reset = true, immediate = true) } }
        field.requestFocus()
        field.post {
            getSystemService<InputMethodManager>()?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
        scheduleQuery(reset = true, immediate = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_QUERY, field.text.toString())
        outState.putBoolean(STATE_WHOLE, wholeWords.isChecked)
        outState.putBoolean(STATE_CASE, matchCase.isChecked)
        super.onSaveInstanceState(outState)
    }

    private fun scheduleQuery(reset: Boolean, immediate: Boolean = false) {
        queryJob?.cancel()
        queryJob = lifecycleScope.launch {
            if (!immediate) delay(ChatSearchIndexManager.QUERY_DEBOUNCE_MS)
            runQuery(reset)
        }
    }

    private suspend fun runQuery(reset: Boolean) {
        val query = field.text.toString()
        if (reset) {
            requestGeneration++
            nextOffset = 0
            hasMore = false
            loaded.clear()
            adapter.submitList(emptyList())
        }
        if (query.isBlank()) {
            showStatus(withContext(Dispatchers.IO) { manager.health() }, emptyQuery = true)
            return
        }
        val generation = requestGeneration
        val options = SearchOptions(wholeWords.isChecked, matchCase.isChecked)
        loadingMore = true
        val page = try {
            manager.query(query, options, nextOffset)
        } finally {
            if (generation == requestGeneration) loadingMore = false
        }
        if (generation != requestGeneration || query != field.text.toString() ||
            options != SearchOptions(wholeWords.isChecked, matchCase.isChecked)
        ) return
        nextOffset = page.nextCandidateOffset
        hasMore = page.hasMore
        loaded += page.results
        adapter.submitList(loaded.distinctBy { it.rowId }.sortedWith(
            org.teslasoft.assistant.preferences.chatsearch.SearchRankingPolicy.comparator
        ))
        showStatus(page.health, noResults = loaded.isEmpty())
    }

    private fun hideKeyboard() {
        getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(field.windowToken, 0)
    }

    private fun showStatus(
        health: org.teslasoft.assistant.preferences.chatsearch.SearchHealth,
        emptyQuery: Boolean = false,
        noResults: Boolean = false
    ) = showStatus(health.state, emptyQuery, noResults)

    private fun showStatus(state: SearchCorpusState, emptyQuery: Boolean = false, noResults: Boolean = false) {
        val text = when {
            state == SearchCorpusState.PREPARING -> getString(R.string.search_preparing)
            state == SearchCorpusState.INCOMPLETE -> getString(R.string.search_incomplete)
            state == SearchCorpusState.UNAVAILABLE -> getString(R.string.search_unavailable)
            noResults && !emptyQuery -> getString(R.string.search_no_results)
            else -> ""
        }
        status.text = text
        status.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        rebuild.visibility = if (state == SearchCorpusState.UNAVAILABLE) View.VISIBLE else View.GONE
    }

    private fun openResult(result: SearchResult) {
        val intent = Intent(this, ChatActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra("name", result.chatName)
            .putExtra("chatId", result.chatId)
        if (result.kind == SearchDocumentKind.MESSAGE) {
            result.messageId?.let { intent.putExtra(SearchTargetResolver.EXTRA_MESSAGE_ID, it) }
            result.legacyOrdinal?.let { intent.putExtra(SearchTargetResolver.EXTRA_LEGACY_ORDINAL, it) }
            result.legacyRole?.let { intent.putExtra(SearchTargetResolver.EXTRA_LEGACY_ROLE, it) }
            intent.putExtra(SearchTargetResolver.EXTRA_FINGERPRINT, result.contentFingerprint)
        }
        startActivity(intent)
    }

    companion object {
        private const val STATE_QUERY = "search.query"
        private const val STATE_WHOLE = "search.whole"
        private const val STATE_CASE = "search.case"
    }
}
