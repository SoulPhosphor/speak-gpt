package org.teslasoft.assistant.ui.activities

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.tts.SavedTtsSource
import org.teslasoft.assistant.preferences.tts.TtsRoutingMode
import org.teslasoft.assistant.tts.api.TtsManagerProviderDisplay
import org.teslasoft.assistant.ui.widgets.AppDropdown

/** App-wide saved sources, independent of both the active chat endpoint and voice activation. */
class ApiVoiceModelsActivity : TtsPickerActivity() {
    private lateinit var model: ApiVoiceModelsViewModel
    private var renderedRows: List<SavedTtsSource>? = null
    private var renderedEndpoints: List<TtsEndpointChoice>? = null
    private var renderedBusy: Boolean? = null
    private val modelPicker = registerForActivityResult(TtsModelPickerContract()) { model.modelResult(it) }
    private val providerPicker = registerForActivityResult(TtsProviderPickerContract()) { model.providerResult(it) }
    private val modes = TtsRoutingMode.entries

    // Shared by every header/body cell. The whole table scrolls horizontally as one unit;
    // its rows and the add form share the one outer vertical ScrollView.
    private val columns = listOf(R.string.tts_manager_endpoint to 150,
        R.string.tts_manager_model to 190, R.string.provider_col_provider to 150,
        R.string.tts_manager_remove_heading to 56)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[ApiVoiceModelsViewModel::class.java]
        setContentView(R.layout.activity_api_voice_models)
        bindInsets()
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tts_endpoint).setOnClickListener { view ->
            val ui = model.ui.value
            AppDropdown.show(view as TextView, ui.endpoints.map { it.label },
                ui.endpoints.indexOfFirst { it.id == ui.draft.endpointId }) { model.endpoint(ui.endpoints[it].id) }
        }
        findViewById<View>(R.id.tts_model_value).setOnClickListener { model.openModel()?.let(modelPicker::launch) }
        findViewById<TextView>(R.id.tts_routing_mode).setOnClickListener { view ->
            AppDropdown.show(view as TextView, modes.map(::modeLabel), modes.indexOf(model.ui.value.draft.routing.mode)) {
                model.mode(modes[it])
            }
        }
        findViewById<View>(R.id.tts_provider_value).setOnClickListener {
            if (model.ui.value.draft.modelId.isBlank()) showSelectModelSnackbar()
            else model.openProvider()?.let(providerPicker::launch)
        }
        findViewById<View>(R.id.tts_add_model).setOnClickListener { model.add() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.ui.collect { ui ->
                    render(ui)
                    ui.notice?.let { showFailure(it, model.takeRetry()) }
                }
            }
        }
    }

    override fun onStart() { super.onStart(); model.refresh() }

    /** Snackbar with an Okay button that stays until the user dismisses it. */
    private fun showSelectModelSnackbar() {
        val root = findViewById<View>(R.id.root) ?: return
        com.google.android.material.snackbar.Snackbar.make(root,
            getString(R.string.tts_manager_select_model_required),
            com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE)
            .setAction(R.string.okay) { /* dismiss */ }
            .show()
    }

    private fun modeLabel(mode: TtsRoutingMode) = getString(when (mode) {
        TtsRoutingMode.AUTOMATIC -> R.string.choose_provider_routing_automatic
        TtsRoutingMode.PREFERRED -> R.string.choose_provider_routing_preferred
        TtsRoutingMode.ONLY -> R.string.choose_provider_routing_only
    })

    private fun availableWidth(anchor: TextView): Int =
        (anchor.parent as? View)?.width ?: resources.displayMetrics.widthPixels

    private fun render(ui: TtsManagerUi) {
        val select = getString(R.string.tts_manager_select)
        findViewById<TextView>(R.id.tts_endpoint).apply {
            text = if (ui.draft.endpointId.isBlank()) select else
                ui.endpoints.firstOrNull { it.id == ui.draft.endpointId }?.label ?: ui.draft.endpointId
            isEnabled = !ui.mutating && ui.endpoints.isNotEmpty()
        }
        findViewById<TextView>(R.id.tts_model_value).text = ui.draft.modelId.ifBlank { select }
        findViewById<TextView>(R.id.tts_routing_mode).apply {
            text = modeLabel(ui.draft.routing.mode)
            AppDropdown.sizeToOptions(this, modes.map(::modeLabel)) { availableWidth(this) }
        }
        findViewById<TextView>(R.id.tts_provider_value).text = TtsManagerProviderDisplay.label(ui.draft.routing, select)
        // A read-only refresh keeps the pickers live; only a save/remove disables them.
        for (id in listOf(R.id.tts_model_value, R.id.tts_routing_mode, R.id.tts_provider_value))
            findViewById<View>(id).isEnabled = !ui.mutating
        findViewById<View>(R.id.tts_add_model).isEnabled = !ui.busy
        findViewById<View>(R.id.tts_manager_progress).visibility = if (ui.busy) View.VISIBLE else View.GONE
        if (ui.rows != renderedRows || ui.endpoints != renderedEndpoints || ui.busy != renderedBusy) {
            renderTable(ui)
            renderedRows = ui.rows; renderedEndpoints = ui.endpoints; renderedBusy = ui.busy
        }
    }

    private fun renderTable(ui: TtsManagerUi) {
        val chart = findViewById<LinearLayout>(R.id.tts_saved_table)
        chart.removeAllViews()
        fun row() = layoutInflater.inflate(R.layout.view_tts_chart_row, chart, false) as LinearLayout
        val header = row()
        columns.forEach { (label, width) -> header.addView(cell(getString(label), width, true)) }
        chart.addView(header)
        for (source in ui.rows) {
            val row = row()
            val endpoint = ui.endpoints.firstOrNull { it.id == source.endpointId }?.label ?: source.endpointId
            row.addView(cell(endpoint, columns[0].second))
            row.addView(cell(source.modelId, columns[1].second))
            row.addView(cell(TtsManagerProviderDisplay.label(source.routing,
                getString(R.string.choose_provider_routing_automatic)), columns[2].second).apply {
                isClickable = true; isFocusable = true; isEnabled = !ui.busy
                minimumHeight = resources.getDimensionPixelSize(R.dimen.tts_manager_action_height)
                contentDescription = getString(R.string.tts_manager_edit_provider, endpoint, source.modelId, text)
                setOnClickListener { model.openProvider(source)?.let(providerPicker::launch) }
            })
            row.addView(cell(getString(R.string.tts_manager_remove_heading), columns[3].second).apply {
                isClickable = true; isFocusable = true; isEnabled = !ui.busy
                minimumHeight = resources.getDimensionPixelSize(R.dimen.tts_manager_action_height)
                contentDescription = getString(R.string.tts_manager_remove, endpoint, source.modelId,
                    TtsManagerProviderDisplay.label(source.routing, modeLabel(TtsRoutingMode.AUTOMATIC)))
                setOnClickListener { model.remove(source) }
            })
            chart.addView(row)
        }
    }

    private fun cell(value: String, width: Int, header: Boolean = false) = TextView(this, null, 0,
        if (header) R.style.Widget_App_Chart_HeaderCell else R.style.Widget_App_Chart_Cell).apply {
        text = value
        maxLines = Int.MAX_VALUE
        ellipsize = null
        layoutParams = LinearLayout.LayoutParams((width * resources.displayMetrics.density).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT)
    }
}
