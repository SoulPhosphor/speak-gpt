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

package org.teslasoft.assistant.preferences.profileimages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.teslasoft.assistant.preferences.profileimages.ProfileImageTransform.Params
import org.junit.Test

class ProfileImageTransformTest {

    /* ---------------- rotation composition ---------------- */

    @Test
    fun quarterTurnPlusFineAngleComposeButReadoutStaysTheFineAngle() {
        // The plan's worked example: one 90-degree turn plus +7 fine rotation
        // is 97 degrees effective, while the readout (fineAngleDeg) stays +7.
        val p = Params(quarterTurns = 1, fineAngleDeg = 7f)
        assertEquals(97.0, ProfileImageTransform.totalRotationDeg(p), 1e-9)
        assertEquals(7f, p.fineAngleDeg, 0f)
    }

    @Test
    fun quarterTurnsNormalizeAndWrapAround() {
        assertEquals(0, ProfileImageTransform.normalizeQuarterTurns(4))
        assertEquals(3, ProfileImageTransform.normalizeQuarterTurns(-1))
        assertEquals(1, ProfileImageTransform.normalizeQuarterTurns(5))
        assertEquals(270.0, ProfileImageTransform.totalRotationDeg(Params(quarterTurns = 3)), 1e-9)
    }

    /* ---------------- minimum scale ---------------- */

    @Test
    fun minScaleAtZeroRotationIsCropOverSmallerImageSide() {
        assertEquals(0.5f, ProfileImageTransform.minScale(1000, 1000, 500f, 0.0), 1e-4f)
    }

    @Test
    fun minScaleAt45DegreesGrowsByRootTwo() {
        assertEquals(0.70710677f, ProfileImageTransform.minScale(1000, 1000, 500f, 45.0), 1e-4f)
    }

    @Test
    fun minScaleForARectangleUsesTheSmallerSide() {
        // 90 degrees: factor 1, min(1000,500) = 500 -> 400/500 = 0.8.
        assertEquals(0.8f, ProfileImageTransform.minScale(1000, 500, 400f, 90.0), 1e-4f)
    }

    /* ---------------- coverage (no empty edges) ---------------- */

    @Test
    fun exactMinScaleCenteredIsCovered() {
        val p = Params(scale = 0.5f)
        assertTrue(ProfileImageTransform.isFullyCovered(p, 1000, 1000, 500f))
    }

    @Test
    fun belowMinScaleIsNotCovered() {
        val p = Params(scale = 0.4f)
        assertFalse(ProfileImageTransform.isFullyCovered(p, 1000, 1000, 500f))
    }

    @Test
    fun offCenterTranslationBreaksCoverage() {
        // At exactly minScale there is no slack, so any translation uncovers.
        val p = Params(scale = 0.5f, translateX = 200f)
        assertFalse(ProfileImageTransform.isFullyCovered(p, 1000, 1000, 500f))
    }

    @Test
    fun flipDoesNotBreakCoverageOfACenteredImage() {
        val p = Params(scale = 0.5f, flipX = true)
        assertTrue(ProfileImageTransform.isFullyCovered(p, 1000, 1000, 500f))
    }

    /* ---------------- zoom bounds and slider mapping ---------------- */

    @Test
    fun zoomBoundsAreTheShrinkFloorAndZoomCeiling() {
        // 1000x1000, crop 500: floor = 0.2*500/1000 = 0.1; ceiling = 8*500/1000 = 4.0.
        assertEquals(0.1f, ProfileImageTransform.minZoomScale(1000, 1000, 500f), 1e-4f)
        assertEquals(4.0f, ProfileImageTransform.maxZoomScale(1000, 1000, 500f), 1e-4f)
    }

    @Test
    fun zoomFractionMapsEndpointsToTheBounds() {
        assertEquals(0.1f, ProfileImageTransform.scaleForZoomFraction(0f, 0.1f, 4.0f), 1e-4f)
        assertEquals(4.0f, ProfileImageTransform.scaleForZoomFraction(1f, 0.1f, 4.0f), 1e-4f)
        assertEquals(0f, ProfileImageTransform.zoomFractionForScale(0.1f, 0.1f, 4.0f), 1e-4f)
        assertEquals(1f, ProfileImageTransform.zoomFractionForScale(4.0f, 0.1f, 4.0f), 1e-4f)
    }

    @Test
    fun zoomFractionRoundTrips() {
        val f = 0.37f
        val scale = ProfileImageTransform.scaleForZoomFraction(f, 0.1f, 4.0f)
        assertEquals(f, ProfileImageTransform.zoomFractionForScale(scale, 0.1f, 4.0f), 1e-4f)
    }

    /* ---------------- clampParams (owner: shrink allowed) ---------------- */

    @Test
    fun clampKeepsAScaleBetweenTheShrinkFloorAndCoverWithoutRaisingIt() {
        // 0.3 is below cover (0.5) but above the shrink floor (0.1): it stays,
        // proving the crop is no longer forced to be covered.
        val clamped = ProfileImageTransform.clampParams(Params(scale = 0.3f), 1000, 1000, 500f)
        assertEquals(0.3f, clamped.scale, 1e-4f)
        assertFalse(ProfileImageTransform.isFullyCovered(clamped, 1000, 1000, 500f))
    }

    @Test
    fun clampRaisesScaleToTheShrinkFloor() {
        val clamped = ProfileImageTransform.clampParams(Params(scale = 0.05f), 1000, 1000, 500f)
        assertEquals(0.1f, clamped.scale, 1e-4f)
    }

    @Test
    fun clampCapsScaleAtTheZoomCeiling() {
        val clamped = ProfileImageTransform.clampParams(Params(scale = 10f), 1000, 1000, 500f)
        assertEquals(4.0f, clamped.scale, 1e-4f)
    }

    @Test
    fun clampCentersAShrunkOffCenterImage() {
        // Smaller than the crop and shoved off-center: it is re-centered so the
        // shrunk picture sits in the middle of the square.
        val clamped = ProfileImageTransform.clampParams(
            Params(scale = 0.3f, translateX = 200f, translateY = -120f), 1000, 1000, 500f
        )
        assertEquals(0.3f, clamped.scale, 1e-4f)
        assertEquals(0f, clamped.translateX, 1e-3f)
        assertEquals(0f, clamped.translateY, 1e-3f)
    }

    @Test
    fun clampStillCoversAndRecentersAtExactlyCover() {
        // At the cover scale there is no slack, so any offset is pulled back and
        // the crop stays fully covered — the old guarantee still holds here.
        val clamped = ProfileImageTransform.clampParams(
            Params(scale = 0.5f, translateX = 200f), 1000, 1000, 500f
        )
        assertEquals(0.5f, clamped.scale, 1e-4f)
        assertEquals(0f, clamped.translateX, 1e-3f)
        assertTrue(ProfileImageTransform.isFullyCovered(clamped, 1000, 1000, 500f))
    }

    @Test
    fun clampAllowsPanningAlongAnOverhangWhenZoomedIn() {
        // Zoomed past cover, a within-range pan is kept and the crop stays covered.
        val clamped = ProfileImageTransform.clampParams(
            Params(scale = 1.0f, translateX = 100f), 1000, 1000, 500f
        )
        assertEquals(100f, clamped.translateX, 1e-3f)
        assertTrue(ProfileImageTransform.isFullyCovered(clamped, 1000, 1000, 500f))
    }

    @Test
    fun clampCoversWhenZoomedInAtAnArbitraryFineAngle() {
        // A covered state above cover, off-center at a fine angle, stays covered.
        val cover = ProfileImageTransform.minScale(1200, 900, 500f, ProfileImageTransform.totalRotationDeg(
            Params(quarterTurns = 1, fineAngleDeg = 12.5f)))
        val clamped = ProfileImageTransform.clampParams(
            Params(scale = cover * 1.3f, translateX = 60f, translateY = -40f, quarterTurns = 1, fineAngleDeg = 12.5f),
            1200, 900, 500f
        )
        assertTrue(ProfileImageTransform.isFullyCovered(clamped, 1200, 900, 500f))
    }

    /* ---------------- output matrix ---------------- */

    @Test
    fun outputMatrixMapsTheCoveredImageOntoTheFullOutput() {
        // Centered image at minScale: the whole 1000x1000 image exactly fills
        // the crop, so it maps onto the full 512x512 output.
        val m = ProfileImageTransform.outputMatrixValues(Params(scale = 0.5f), 1000, 1000, 500f, 512)

        // Explicit Float return: m[i] * Double promotes to Double in Kotlin,
        // and JUnit's assertEquals has no (Float, Double, Float) overload.
        fun mapX(x: Double, y: Double): Float = (m[0] * x + m[1] * y + m[2]).toFloat()
        fun mapY(x: Double, y: Double): Float = (m[3] * x + m[4] * y + m[5]).toFloat()

        assertEquals(0f, mapX(0.0, 0.0), 1e-3f)
        assertEquals(0f, mapY(0.0, 0.0), 1e-3f)
        assertEquals(512f, mapX(1000.0, 1000.0), 1e-3f)
        assertEquals(512f, mapY(1000.0, 1000.0), 1e-3f)
        // Android affine layout: bottom row is [0,0,1].
        assertEquals(0f, m[6], 0f)
        assertEquals(0f, m[7], 0f)
        assertEquals(1f, m[8], 0f)
    }

    @Test
    fun outputMatrixCropCornersMapToOutputCorners() {
        // A zoomed-in, off-center crop still maps its own crop square onto the
        // full output. Verify by inverse-checking the covered corners land in
        // [0,512] after mapping the crop's image-space corners.
        val p = Params(scale = 1.2f, translateX = 30f, translateY = -20f)
        val imgW = 1000
        val imgH = 1000
        val cropSize = 400f
        val corners = ProfileImageTransform.cropCornersInImageSpace(p, imgW, imgH, cropSize)
        val m = ProfileImageTransform.outputMatrixValues(p, imgW, imgH, cropSize, 512)

        fun mapX(x: Double, y: Double) = m[0] * x + m[1] * y + m[2]
        fun mapY(x: Double, y: Double) = m[3] * x + m[4] * y + m[5]

        // The four crop corners, in image space, must land on the output's own
        // four corners (0..512), in some order — proving the crop fills output.
        for (corner in corners) {
            val ox = mapX(corner.x, corner.y)
            val oy = mapY(corner.x, corner.y)
            val onXEdge = kotlin.math.abs(ox) < 1e-2f || kotlin.math.abs(ox - 512f) < 1e-2f
            val onYEdge = kotlin.math.abs(oy) < 1e-2f || kotlin.math.abs(oy - 512f) < 1e-2f
            assertTrue("corner mapped to ($ox,$oy) should be on an output edge", onXEdge && onYEdge)
        }
    }
}
