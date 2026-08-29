package org.teslasoft.assistant.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import org.teslasoft.assistant.R

/**
 * Shared sampling control used by Quick Settings and both API Endpoint editor
 * layouts. The value box and slider always mirror one another. Direct input is
 * committed on Done or focus loss, normalized to two-decimal precision, and
 * clamped to the parameter's supported range.
 */
class SamplingParameterControl @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val valueField: TextInputEditText
    private val slider: Slider
    private var spec: SamplingParameterSpec = SamplingParameterSpec.TEMPERATURE
    private var configured = false
    private var updatingField = false
    private var typingValue = false
    private var onValueChanged: (Float) -> Unit = {}

    val value: Float
        get() = SamplingParameterValuePolicy.parse(valueField.text?.toString().orEmpty())
            ?.let { SamplingParameterValuePolicy.normalize(it, spec) }
            ?: SamplingParameterValuePolicy.normalize(slider.value, spec)

    init {
        LayoutInflater.from(context).inflate(R.layout.view_sampling_parameter_control, this, true)
        valueField = findViewById(R.id.sampling_parameter_value)
        slider = findViewById(R.id.sampling_parameter_slider)

        slider.addOnChangeListener { _, rawValue, _ ->
            if (!configured) return@addOnChangeListener
            val normalized = SamplingParameterValuePolicy.normalize(rawValue, spec)
            if (!typingValue) updateValueText(normalized)
            onValueChanged(normalized)
        }
        valueField.doAfterTextChanged { editable ->
            if (!configured || updatingField) return@doAfterTextChanged
            val parsed = SamplingParameterValuePolicy.parse(editable?.toString().orEmpty())
                ?: return@doAfterTextChanged
            // Keep incomplete/out-of-range typing intact until Done or focus
            // loss; valid direct entry moves the slider immediately.
            if (parsed < spec.minimum || parsed > spec.maximum) return@doAfterTextChanged
            val normalized = SamplingParameterValuePolicy.normalize(parsed, spec)
            typingValue = true
            slider.value = normalized
            typingValue = false
        }
        valueField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitTypedValue()
                true
            } else {
                false
            }
        }
        valueField.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) commitTypedValue()
        }
    }

    fun configure(
        spec: SamplingParameterSpec,
        initialValue: Float,
        onValueChanged: (Float) -> Unit = {}
    ) {
        configured = false
        this.spec = spec
        this.onValueChanged = onValueChanged
        slider.valueFrom = spec.minimum
        slider.valueTo = spec.maximum
        slider.stepSize = spec.step
        slider.contentDescription = contentDescription
        valueField.contentDescription = contentDescription
        slider.value = SamplingParameterValuePolicy.normalize(initialValue, spec)
        updateValueText(slider.value)
        configured = true
    }

    private fun commitTypedValue() {
        if (!configured || updatingField) return
        val parsed = SamplingParameterValuePolicy.parse(valueField.text?.toString().orEmpty())
        if (parsed == null) {
            updateValueText(slider.value)
            return
        }
        val normalized = SamplingParameterValuePolicy.normalize(parsed, spec)
        if (slider.value != normalized) {
            slider.value = normalized
        } else {
            updateValueText(normalized)
        }
    }

    private fun updateValueText(value: Float) {
        val formatted = SamplingParameterValuePolicy.format(value, spec)
        if (valueField.text?.toString() == formatted) return
        updatingField = true
        valueField.setText(formatted)
        valueField.setSelection(formatted.length)
        updatingField = false
    }
}
