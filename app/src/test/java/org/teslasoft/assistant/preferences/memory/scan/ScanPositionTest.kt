/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.preferences.memory.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanPositionTest {

    private val ids = (1..100).map { "m$it" }

    @Test fun theExactMessageIsFoundFirst() {
        val anchors = ScanPosition.anchors(ids, 59)
        assertEquals("m60", anchors.first())
        assertEquals(59, ScanPosition.resolve(ids, anchors))
    }

    @Test fun deletingTheBookmarkedMessageFallsBackToTheNearestEarlierOne() {
        val anchors = ScanPosition.anchors(ids, 59)
        val afterDelete = ids - listOf("m60", "m59")
        assertEquals(afterDelete.indexOf("m58"), ScanPosition.resolve(afterDelete, anchors))
    }

    @Test fun farAnchorsSurviveADeletedBlock() {
        val anchors = ScanPosition.anchors(ids, 79)
        val afterDelete = ids.filterNot { it.drop(1).toInt() in 40..80 }
        val found = ScanPosition.resolve(afterDelete, anchors)!!
        assertTrue(afterDelete[found].drop(1).toInt() < 40)
    }

    @Test fun noAnchorLeftMeansUnknown() {
        assertNull(ScanPosition.resolve(listOf("x"), ScanPosition.anchors(ids, 10)))
        assertTrue(ScanPosition.anchors(ids, -1).isEmpty())
    }

    @Test fun anchorsRoundTrip() {
        val anchors = ScanPosition.anchors(ids, 30)
        assertEquals(anchors, ScanPosition.decode(ScanPosition.encode(anchors)))
    }
}
