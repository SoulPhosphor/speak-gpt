package org.teslasoft.assistant.ui.widgets

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class SamplingParameterValuePolicyTest {

    @Test
    fun `normalizes to two decimals`() {
        assertEquals(
            0.13f,
            SamplingParameterValuePolicy.normalize(0.126f, SamplingParameterSpec.TEMPERATURE)
        )
    }

    @Test
    fun `clamps typed values to parameter range`() {
        assertEquals(
            1f,
            SamplingParameterValuePolicy.normalize(1.75f, SamplingParameterSpec.TOP_P)
        )
        assertEquals(
            -2f,
            SamplingParameterValuePolicy.normalize(-7f, SamplingParameterSpec.FREQUENCY_PENALTY)
        )
    }

    @Test
    fun `formats with no more than two decimal places`() {
        assertEquals(
            "0.7",
            SamplingParameterValuePolicy.format(0.7f, SamplingParameterSpec.TEMPERATURE, Locale.US)
        )
        assertEquals(
            "0.13",
            SamplingParameterValuePolicy.format(0.126f, SamplingParameterSpec.TEMPERATURE, Locale.US)
        )
    }

    @Test
    fun `parses locale decimal separator`() {
        assertEquals(0.75f, SamplingParameterValuePolicy.parse("0,75", Locale.GERMANY))
    }

    @Test
    fun `memory spread preserves its calibrated third decimal without changing sampling precision`() {
        val spread = SamplingParameterSpec.MEMORY_MINIMUM_SPREAD
        assertEquals(0.034f, SamplingParameterValuePolicy.normalize(0.034f, spread))
        assertEquals("0.034", SamplingParameterValuePolicy.format(0.034f, spread, Locale.US))
        assertEquals("0,034", SamplingParameterValuePolicy.format(0.034f, spread, Locale.GERMANY))
        assertEquals(0.001f, spread.step)
        assertEquals(0.03f, SamplingParameterValuePolicy.normalize(0.034f, SamplingParameterSpec.TEMPERATURE))
        assertEquals(0.01f, SamplingParameterSpec.TEMPERATURE.step)
    }
}
