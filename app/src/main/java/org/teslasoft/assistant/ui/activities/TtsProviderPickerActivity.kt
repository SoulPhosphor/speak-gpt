package org.teslasoft.assistant.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.tts.TtsRoutingMode
import org.teslasoft.assistant.tts.api.*
import org.teslasoft.assistant.ui.widgets.AppDropdown

/** Draft and saved-row edits share this result-only picker. Endpoint/model are always locked. */
class TtsProviderPickerActivity : TtsPickerActivity() {
    private lateinit var state: TtsProviderPickerState
    private var providers: List<TtsProvider> = emptyList()
    private var loading = false
    private val modes = TtsRoutingMode.entries
    private val filters = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.getStringExtra(TtsProviderFiltersActivity.EXTRA_SORT)?.let {
                state.sort = TtsPickerCodec.decodeSort(it)
                renderChart()
            }
        }
    }

    // One width definition for header and every body row. The leading selection control is unlabelled.
    private val columns = listOf(
        R.string.provider_col_provider to 160,
        R.string.tts_provider_col_price to 220,
        R.string.provider_col_latency to 120,
        R.string.provider_col_uptime to 120,
        R.string.provider_col_zdr to 56,
        R.string.tts_provider_col_training to 150
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = TtsProviderPickerState(TtsPickerRequest(readTarget(savedInstanceState) ?: return))
        savedInstanceState?.getString(TtsProviderFiltersActivity.EXTRA_SORT)?.let {
            state.sort = TtsPickerCodec.decodeSort(it)
        }
        setContentView(R.layout.activity_tts_provider_picker)
        bindInsets()
        findViewById<View>(R.id.btn_back).setOnClickListener { save() }
        findViewById<View>(R.id.btn_save).setOnClickListener { save() }
        findViewById<TextView>(R.id.tts_fixed_model).text = state.request.target.modelId
        findViewById<TextView>(R.id.field_routing_type).setOnClickListener { view ->
            AppDropdown.show(view as TextView, modes.map(::modeLabel), modes.indexOf(state.routing.mode)) {
                state.mode(modes[it]); render()
            }
        }
        findViewById<MaterialSwitch>(R.id.switch_allow_fallbacks).apply {
            isChecked = state.routing.allowFallbacks
            setOnCheckedChangeListener { _, checked -> state.fallbacks(checked) }
        }
        findViewById<View>(R.id.row_allow_fallbacks).setOnClickListener {
            findViewById<MaterialSwitch>(R.id.switch_allow_fallbacks).toggle()
        }
        findViewById<View>(R.id.btn_provider_filters).setOnClickListener { openFilters() }
        render()
        load()
    }

    private fun modeLabel(mode: TtsRoutingMode) = getString(when (mode) {
        TtsRoutingMode.AUTOMATIC -> R.string.choose_provider_routing_automatic
        TtsRoutingMode.PREFERRED -> R.string.choose_provider_routing_preferred
        TtsRoutingMode.ONLY -> R.string.choose_provider_routing_only
    })

    @Suppress("DEPRECATION")
    private fun openFilters() {
        filters.launch(Intent(this, TtsProviderFiltersActivity::class.java)
            .putExtra(TtsProviderFiltersActivity.EXTRA_SORT, TtsPickerCodec.encodeSort(state.sort)))
        overridePendingTransition(R.anim.slide_in_right, R.anim.anim_hold)
    }

    private fun load() {
        loading = true
        findViewById<View>(R.id.tts_provider_progress).visibility = View.VISIBLE
        findViewById<View>(R.id.text_provider_status).visibility = View.GONE
        discover(state.request.target.copy(routing = state.routing), TtsOperation.PROVIDERS, { source, token ->
            TtsDiscoveryClient().providers(source, token).also {
                if (it.providers.isEmpty()) throw TtsException(TtsFailure(TtsOperation.PROVIDERS,
                    source.target, source.endpoint.label, TtsFailureKind.EMPTY, responseReceived = true))
            }
        }, {
            providers = it.providers
            loading = false
            findViewById<View>(R.id.tts_provider_progress).visibility = View.GONE
            render()
        }, {
            loading = false
            findViewById<View>(R.id.tts_provider_progress).visibility = View.GONE
            findViewById<TextView>(R.id.text_provider_status).apply {
                text = TtsFailures.message(it).explanation; visibility = View.VISIBLE
            }
            render()
            showFailure(it, ::load)
        })
    }

    private fun render() {
        findViewById<TextView>(R.id.field_routing_type).text = modeLabel(state.routing.mode)
        val preferred = state.routing.mode == TtsRoutingMode.PREFERRED
        findViewById<View>(R.id.row_allow_fallbacks).visibility = if (preferred) View.VISIBLE else View.GONE
        findViewById<View>(R.id.section_preferred_order).visibility = if (preferred) View.VISIBLE else View.GONE
        val rows = findViewById<LinearLayout>(R.id.rows_preferred_order)
        rows.removeAllViews()
        findViewById<View>(R.id.text_preferred_order).visibility =
            if (state.routing.providerOrder.isEmpty()) View.VISIBLE else View.GONE
        state.routing.providerOrder.forEachIndexed { index, id ->
            val name = providers.firstOrNull { it.id == id }?.name ?: id
            val row = layoutInflater.inflate(R.layout.view_tts_preferred_order, rows, false)
            row.findViewById<TextView>(R.id.tts_order_name).text = "${index + 1}. $name"
            row.findViewById<View>(R.id.tts_order_up).apply {
                contentDescription = getString(R.string.provider_move_up_desc, name)
                isEnabled = index > 0
                setOnClickListener { state.move(index, index - 1); render() }
            }
            row.findViewById<View>(R.id.tts_order_down).apply {
                contentDescription = getString(R.string.provider_move_down_desc, name)
                isEnabled = index < state.routing.providerOrder.lastIndex
                setOnClickListener { state.move(index, index + 1); render() }
            }
            row.findViewById<View>(R.id.tts_order_remove).apply {
                contentDescription = getString(R.string.provider_remove_desc, name)
                setOnClickListener { state.remove(id); render() }
            }
            rows.addView(row)
        }
        renderChart()
    }

    private fun renderChart() {
        val chart = findViewById<LinearLayout>(R.id.tts_provider_chart)
        chart.removeAllViews()
        val lead = state.routing.mode != TtsRoutingMode.AUTOMATIC
        fun row() = layoutInflater.inflate(R.layout.view_tts_chart_row, chart, false) as LinearLayout
        val header = row()
        if (lead) header.addView(View(this), LinearLayout.LayoutParams(dp(48), 1))
        columns.forEach { (label, width) -> header.addView(cell(getString(label), width, true)) }
        chart.addView(header)
        val displayed = state.sort.apply(providers)
        // Keep undiscovered selected identities visible without claiming they were deleted.
        val selected = (state.routing.providerOrder + state.routing.selectedProvider).filter(String::isNotBlank).distinct()
        val missing = selected.filter { id -> providers.none { it.id == id } }.map {
            TtsProvider(it, it, TtsPrice(emptyList(), false), null, null, null, null)
        }
        (displayed + missing).forEach { provider ->
            val row = row()
            if (lead) {
                val available = !loading && providers.any { it.id == provider.id }
                val control = if (state.routing.mode == TtsRoutingMode.ONLY) MaterialRadioButton(this).apply {
                    isChecked = state.routing.selectedProvider == provider.id
                    isEnabled = available
                    setOnClickListener { state.select(provider.id); render() }
                } else MaterialCheckBox(this).apply {
                    isChecked = provider.id in state.routing.providerOrder
                    isEnabled = available || isChecked
                    setOnClickListener { state.select(provider.id); render() }
                }
                control.contentDescription = provider.name
                row.addView(control, LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            val values = listOf(provider.name, provider.price.display(),
                metric(provider.latency, "s"), metric(provider.uptime, "%"),
                TtsPickerPresentation.mark(provider.zdr), TtsPickerPresentation.mark(provider.training))
            values.forEachIndexed { index, value -> row.addView(cell(value, columns[index].second, false)) }
            chart.addView(row)
        }
    }

    private fun metric(metric: TtsMetric?, unit: String): String = metric?.let {
        // Preserve which metric was reported; never label a fallback as the 30-minute metric.
        "${java.math.BigDecimal.valueOf(it.value).stripTrailingZeros().toPlainString()}$unit\n${it.definition}"
    } ?: "?"

    private fun cell(value: String, width: Int, header: Boolean) = TextView(this, null, 0,
        if (header) R.style.Widget_App_Chart_HeaderCell else R.style.Widget_App_Chart_Cell).apply {
        text = value
        // Billing components and long IDs must remain readable at large font sizes.
        maxLines = Int.MAX_VALUE
        ellipsize = null
        layoutParams = LinearLayout.LayoutParams(dp(width), LinearLayout.LayoutParams.WRAP_CONTENT)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun save() {
        val result = try { state.result() } catch (error: TtsException) {
            showFailure(error.failure, ::save); return
        }
        // Resolve again on Save: profile/source removal must not cause a stale result to be accepted.
        loading = false
        findViewById<View>(R.id.tts_provider_progress).visibility = View.GONE
        findViewById<View>(R.id.btn_save).isEnabled = false
        discover(result, TtsOperation.PROVIDERS, { _, _ -> result }, { returnSelection(it) }, {
            findViewById<View>(R.id.btn_save).isEnabled = true
            render()
            showFailure(it, ::save)
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::state.isInitialized) {
            outState.putString(EXTRA_TARGET, TtsPickerCodec.encode(state.request.target.copy(routing = state.routing)))
            outState.putString(TtsProviderFiltersActivity.EXTRA_SORT, TtsPickerCodec.encodeSort(state.sort))
        }
        super.onSaveInstanceState(outState)
    }
}
