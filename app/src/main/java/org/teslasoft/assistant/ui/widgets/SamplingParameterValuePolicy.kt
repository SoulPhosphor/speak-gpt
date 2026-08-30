package org.teslasoft.assistant.ui.widgets

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.ParsePosition
import java.util.Locale

/** Domain ranges and precision shared by every sampling-parameter editor. */
enum class SamplingParameterSpec(val minimum: Float, val maximum: Float) {
    TEMPERATURE(0f, 2f),
    TOP_P(0f, 1f),
    FREQUENCY_PENALTY(-2f, 2f),
    PRESENCE_PENALTY(-2f, 2f);

    val step: Float get() = SamplingParameterValuePolicy.STEP
}

/**
 * One precision policy for slider movement, typed values, display text, and
 * persistence. Values are rounded to at most two decimal places and clamped
 * to the selected parameter's real supported range.
 */
object SamplingParameterValuePolicy {
    const val DECIMAL_PLACES = 2
    const val STEP = 0.01f

    fun normalize(value: Float, spec: SamplingParameterSpec): Float {
        val rounded = BigDecimal(value.toString())
            .setScale(DECIMAL_PLACES, RoundingMode.HALF_UP)
            .toFloat()
        return rounded.coerceIn(spec.minimum, spec.maximum)
    }

    fun format(
        value: Float,
        spec: SamplingParameterSpec,
        locale: Locale = Locale.getDefault()
    ): String {
        val formatter = DecimalFormat("0.##", DecimalFormatSymbols.getInstance(locale)).apply {
            isGroupingUsed = false
            maximumFractionDigits = DECIMAL_PLACES
        }
        return formatter.format(normalize(value, spec))
    }

    fun parse(text: String, locale: Locale = Locale.getDefault()): Float? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val position = ParsePosition(0)
        val formatter = DecimalFormat("0.##", DecimalFormatSymbols.getInstance(locale)).apply {
            isGroupingUsed = false
            isParseBigDecimal = true
        }
        val parsed = formatter.parse(trimmed, position)
        if (parsed != null && position.index == trimmed.length) return parsed.toFloat()
        return trimmed.toFloatOrNull()
    }
}
