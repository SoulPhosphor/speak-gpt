package org.teslasoft.assistant.ui.widgets

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.ParsePosition
import java.util.Locale

/** Domain ranges and precision shared by every sampling-parameter editor. */
enum class SamplingParameterSpec(val minimum: Float, val maximum: Float, val decimalPlaces: Int = 2) {
    TEMPERATURE(0f, 2f),
    TOP_P(0f, 1f),
    FREQUENCY_PENALTY(-2f, 2f),
    PRESENCE_PENALTY(-2f, 2f),

    // Memory search tuning (Memory Search Test screen). Ranges match the
    // bounds LiveRetrieval.Params.bounded applies to stored values.
    MEMORY_MINIMUM_MATCH(0f, 0.95f),
    MEMORY_STAND_OUT(0f, 10f),
    MEMORY_KEYWORD_MATCH(0.05f, 1f),
    MEMORY_IMPORTANCE_INFLUENCE(0f, 0.5f),
    MEMORY_SMALL_LIBRARY_MATCH(0f, 0.95f),
    MEMORY_FULL_STRENGTH_MATCH(0.01f, 1f),
    MEMORY_AGREEMENT_BONUS(0f, 1f),
    MEMORY_MINIMUM_SPREAD(0.01f, 0.5f, 3);

    val step: Float get() = if (this == MEMORY_MINIMUM_SPREAD) 0.001f else SamplingParameterValuePolicy.STEP
}

/**
 * One precision policy for slider movement, typed values, display text, and
 * persistence. Values use their parameter's precision (three places for
 * memory spread, two elsewhere) and stay inside its supported range.
 */
object SamplingParameterValuePolicy {
    const val DECIMAL_PLACES = 2
    const val STEP = 0.01f

    fun normalize(value: Float, spec: SamplingParameterSpec): Float {
        val rounded = BigDecimal(value.toString())
            .setScale(spec.decimalPlaces, RoundingMode.HALF_UP)
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
            maximumFractionDigits = spec.decimalPlaces
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
