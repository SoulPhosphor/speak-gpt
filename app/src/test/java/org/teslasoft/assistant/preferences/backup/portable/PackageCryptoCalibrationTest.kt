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

package org.teslasoft.assistant.preferences.backup.portable

import org.junit.Assert.assertEquals
import org.junit.Test

/** The pure KDF calibration clamp: the v1 window is a hard floor and ceiling
 *  regardless of how fast or slow the calibrating device is. */
class PackageCryptoCalibrationTest {

    @Test
    fun scalesToTargetWithinTheWindow() {
        // 100k iters took 100ms -> ~1s wants ~1,000,000 (inside the window).
        assertEquals(1_000_000, PackageCrypto.calibrateIterations(100_000, 100, 1000))
    }

    @Test
    fun aFastDeviceIsCappedAtTheCeiling() {
        // 100k in 5ms would scale to 20,000,000 -> clamped to the 2M ceiling,
        // protecting slow devices at restore time.
        assertEquals(PackageCrypto.KDF_MAX_ITERATIONS, PackageCrypto.calibrateIterations(100_000, 5, 1000))
    }

    @Test
    fun aSlowDeviceIsFlooredAtTheMinimum() {
        // 100k in 2s would scale to 50,000 -> raised to the 600k floor.
        assertEquals(PackageCrypto.KDF_MIN_ITERATIONS, PackageCrypto.calibrateIterations(100_000, 2000, 1000))
    }

    @Test
    fun degenerateInputsFallBackToTheFloor() {
        assertEquals(PackageCrypto.KDF_MIN_ITERATIONS, PackageCrypto.calibrateIterations(0, 100, 1000))
        assertEquals(PackageCrypto.KDF_MIN_ITERATIONS, PackageCrypto.calibrateIterations(100_000, 0, 1000))
    }
}
