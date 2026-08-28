package org.teslasoft.assistant.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import org.teslasoft.assistant.R
import org.teslasoft.assistant.providers.SortDirection
import org.teslasoft.assistant.tts.api.TtsPickerCodec
import org.teslasoft.assistant.tts.api.TtsProviderSort
import org.teslasoft.assistant.ui.widgets.AppDropdown

/** A value passed back to one picker, not a process-wide filter singleton. No Apply action. */
class TtsProviderFiltersActivity : TtsPickerActivity() {
    companion object { const val EXTRA_SORT = "tts.picker.sort" }
    private var sort = TtsProviderSort()
    private val directions = listOf(SortDirection.NONE, SortDirection.HIGH_TO_LOW, SortDirection.LOW_TO_HIGH)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sort = (savedInstanceState?.getString(EXTRA_SORT) ?: intent.getStringExtra(EXTRA_SORT))
            ?.let(TtsPickerCodec::decodeSort) ?: TtsProviderSort()
        setContentView(R.layout.activity_tts_provider_filters)
        bindInsets()
        findViewById<View>(R.id.btn_close).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tts_sort_alphabetical).setOnClickListener { view ->
            AppDropdown.show(view as TextView, listOf(getString(R.string.provider_filter_a_to_z),
                getString(R.string.provider_filter_z_to_a))) { sort = sort.copy(alphaAToZ = it == 0); render() }
        }
        bind(R.id.tts_sort_price) { sort = sort.copy(price = it) }
        bind(R.id.tts_sort_latency) { sort = sort.copy(latency = it) }
        bind(R.id.tts_sort_uptime) { sort = sort.copy(uptime = it) }
        findViewById<View>(R.id.btn_reset_filters).setOnClickListener { sort = TtsProviderSort(); render() }
        render()
    }

    private fun label(direction: SortDirection) = getString(when (direction) {
        SortDirection.NONE -> R.string.provider_filter_sort_none
        SortDirection.HIGH_TO_LOW -> R.string.provider_filter_sort_high_low
        SortDirection.LOW_TO_HIGH -> R.string.provider_filter_sort_low_high
    })
    private fun bind(id: Int, apply: (SortDirection) -> Unit) {
        findViewById<TextView>(id).setOnClickListener { view ->
            AppDropdown.show(view as TextView, directions.map(::label)) { apply(directions[it]); render() }
        }
    }
    private fun render() {
        findViewById<TextView>(R.id.tts_sort_alphabetical).setText(if (sort.alphaAToZ)
            R.string.provider_filter_a_to_z else R.string.provider_filter_z_to_a)
        findViewById<TextView>(R.id.tts_sort_price).text = label(sort.price)
        findViewById<TextView>(R.id.tts_sort_latency).text = label(sort.latency)
        findViewById<TextView>(R.id.tts_sort_uptime).text = label(sort.uptime)
        setResult(RESULT_OK, Intent().putExtra(EXTRA_SORT, TtsPickerCodec.encodeSort(sort)))
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(EXTRA_SORT, TtsPickerCodec.encodeSort(sort))
        super.onSaveInstanceState(outState)
    }
    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.anim_hold, R.anim.slide_out_right)
    }
}
