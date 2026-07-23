/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.preferences.backup

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Human-readable binary file sizes (owner directive, July 23 2026). Pins the
 * unit boundaries, the one-decimal rounding, and the near-boundary "rounds up
 * to 1024" edge case that a naive divide-and-format would get wrong.
 */
class ByteSizeFormatterTest {

    @Test
    fun bytesRenderAsPlainInteger() {
        assertEquals("0 B", ByteSizeFormatter.format(0L))
        assertEquals("1 B", ByteSizeFormatter.format(1L))
        assertEquals("512 B", ByteSizeFormatter.format(512L))
        assertEquals("1023 B", ByteSizeFormatter.format(1023L))
    }

    @Test
    fun negativeIsTreatedAsZero() {
        assertEquals("0 B", ByteSizeFormatter.format(-1L))
        assertEquals("0 B", ByteSizeFormatter.format(-1_000_000L))
    }

    @Test
    fun exactKilobyteBoundary() {
        // 1024 bytes is exactly 1 KB, not "1024 B".
        assertEquals("1.0 KB", ByteSizeFormatter.format(1024L))
    }

    @Test
    fun kilobytesRoundToOneDecimal() {
        assertEquals("1.5 KB", ByteSizeFormatter.format(1536L)) // 1.5 * 1024
        assertEquals("2.0 KB", ByteSizeFormatter.format(2048L))
    }

    @Test
    fun exampleFromSpec_fourPointEightMb() {
        // The owner's own example: "A value such as 4.8 MB is preferable to
        // raw bytes." 5,000,000 bytes = 4.76837... MiB -> rounds to 4.8 MB.
        assertEquals("4.8 MB", ByteSizeFormatter.format(5_000_000L))
    }

    @Test
    fun exactMegabyteBoundary() {
        assertEquals("1.0 MB", ByteSizeFormatter.format(1024L * 1024L))
    }

    @Test
    fun exactGigabyteBoundary() {
        assertEquals("1.0 GB", ByteSizeFormatter.format(1024L * 1024L * 1024L))
    }

    @Test
    fun gigabytesRoundToOneDecimal() {
        val twoAndAHalfGb = (2.5 * 1024 * 1024 * 1024).toLong()
        assertEquals("2.5 GB", ByteSizeFormatter.format(twoAndAHalfGb))
    }

    @Test
    fun cappedAtGb_noTbTier() {
        // A huge value stays in GB (the spec's largest unit) rather than
        // inventing a TB tier.
        val hundredGb = 100L * 1024L * 1024L * 1024L
        assertEquals("100.0 GB", ByteSizeFormatter.format(hundredGb))
    }

    /* -------------------- the "rounds up to 1024.0" edge case -------------- */

    @Test
    fun justUnderMegabyte_doesNotDisplayAs1024Kb() {
        // 1,048,550 bytes / 1024 = 1023.9755... KB, which rounds to "1024.0"
        // at one decimal — a naive formatter would print "1024.0 KB". The
        // unit must bump to MB instead.
        val bytes = 1_048_550L
        val result = ByteSizeFormatter.format(bytes)
        assertEquals("1.0 MB", result)
    }

    @Test
    fun justUnderGigabyte_doesNotDisplayAs1024Mb() {
        // 1,073,699,880 bytes is ~1023.96 MB — below 1024 MB, so the main
        // unit-walking loop alone would NOT bump it to GB, but rounding to
        // one decimal would print "1024.0 MB" without the post-loop guard.
        // This specifically exercises that guard (verified via exact
        // division: kb=1048535.039..., mb=1023.9599990844727).
        assertEquals("1.0 GB", ByteSizeFormatter.format(1_073_699_880L))
    }

    @Test
    fun wellBelowBoundary_staysAtTheLowerUnit() {
        // Sanity check the edge-case guard doesn't over-fire for ordinary
        // values that are nowhere near a boundary.
        assertEquals("500.0 KB", ByteSizeFormatter.format(500L * 1024L))
        assertEquals("999.0 KB", ByteSizeFormatter.format(999L * 1024L))
    }

    @Test
    fun usesDotDecimalSeparatorRegardlessOfLocale() {
        // Locale.US is forced internally; this just pins that the output
        // never contains a comma decimal separator.
        assertEquals("4.8 MB", ByteSizeFormatter.format(5_000_000L))
        assert(!ByteSizeFormatter.format(5_000_000L).contains(","))
    }
}
