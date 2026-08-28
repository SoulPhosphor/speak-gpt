package org.teslasoft.assistant.ui.activities

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.textfield.TextInputEditText
import org.teslasoft.assistant.R
import org.teslasoft.assistant.tts.api.*

/** Focused View All purpose. Shares presentation, never the chat adapter's actions or state. */
class TtsModelPickerActivity : TtsPickerActivity() {
    private lateinit var target: TtsTarget
    private var catalog = TtsModelCatalog(emptyList(), false)
    private var rows: List<TtsModel> = emptyList()
    private var query = ""
    private var loaded = false
    private lateinit var empty: TextView
    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
            val view = recycled ?: layoutInflater.inflate(R.layout.view_model, parent, false)
            val model = rows[position]
            listOf(R.id.btn_action, R.id.btn_reasoning_settings, R.id.btn_routing_settings,
                R.id.model_unavailable_warning).forEach { view.findViewById<View>(it).visibility = View.GONE }
            val selected = model.id == target.modelId
            val background = ContextCompat.getDrawable(this@TtsModelPickerActivity, if (selected)
                R.drawable.btn_accent_tonal_selector_v4 else R.drawable.btn_accent_tonal_selector_v3)!!.mutate()
            DrawableCompat.setTint(background, ContextCompat.getColor(this@TtsModelPickerActivity,
                if (selected) R.color.accent_900 else android.R.color.transparent))
            view.findViewById<TextView>(R.id.voice_name).apply {
                text = model.id
                setTextColor(ContextCompat.getColor(context, if (selected) R.color.accent_250 else R.color.text))
            }
            view.findViewById<View>(R.id.voice_bg).apply {
                this.background = background
                isSelected = selected
                setOnClickListener { returnSelection(target.copy(modelId = model.id)) }
            }
            return view
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        target = readTarget(savedInstanceState) ?: return
        // There is deliberately no model picker for saved-row edits.
        if (target.sourceId != null) { finish(); return }
        setContentView(R.layout.fragment_model_selector)
        bindInsets()
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_view_all).visibility = View.GONE
        findViewById<View>(R.id.btn_use_current_model).visibility = View.GONE
        val list = findViewById<ListView>(R.id.voices_list)
        list.adapter = adapter
        // Shared status styling; the existing View All scaffold has no empty label.
        empty = TextView(this, null, 0, R.style.Widget_App_Section_Hint).apply {
            id = View.generateViewId()
            gravity = android.view.Gravity.CENTER
            visibility = View.GONE
        }
        (list.parent as ViewGroup).addView(empty,
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(0, 0).apply {
                startToStart = R.id.voices_list; endToEnd = R.id.voices_list
                topToTop = R.id.voices_list; bottomToBottom = R.id.voices_list
            })
        val search = findViewById<TextInputEditText>(R.id.field_search_text)
        query = savedInstanceState?.getString("query").orEmpty()
        search.setText(query)
        search.doAfterTextChanged { query = it?.toString().orEmpty(); render() }
        load()
    }

    private fun load() {
        loaded = false
        catalog = TtsModelCatalog(emptyList(), false)
        render()
        findViewById<View>(R.id.progressBar).visibility = View.VISIBLE
        discover(target, TtsOperation.MODELS, { source, token ->
            TtsDiscoveryClient().models(source, token).also {
                if (it.models.isEmpty()) throw TtsException(TtsFailure(TtsOperation.MODELS,
                    source.target, source.endpoint.label, TtsFailureKind.EMPTY, responseReceived = true))
            }
        }, {
            catalog = it; loaded = true
            findViewById<View>(R.id.progressBar).visibility = View.GONE
            render()
        }, {
            findViewById<View>(R.id.progressBar).visibility = View.GONE
            empty.text = TtsFailures.message(it).explanation
            empty.visibility = View.VISIBLE
            showFailure(it, ::load)
        })
    }

    private fun render() {
        rows = TtsPickerPresentation.models(catalog, query)
        adapter.notifyDataSetChanged()
        empty.setText(R.string.tts_no_matching_models)
        empty.visibility = if (loaded && rows.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(EXTRA_TARGET, TtsPickerCodec.encode(target))
        outState.putString("query", query)
        super.onSaveInstanceState(outState)
    }
}
