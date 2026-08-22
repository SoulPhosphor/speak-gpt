/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.reasoning

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyReasoningRepairTest {

    @Test
    fun repairsAdjacentDuplicateChunksFromTwoEquivalentFields() {
        val first = "We need to inspect the request carefully. "
        val second = "Then choose the smallest safe change."
        val corrupted = first + first + second + second

        assertEquals(
            first + second,
            LegacyReasoningRepair.repairDuplicatedStreamText(corrupted)
        )
    }

    @Test
    fun repairsTripleChunksWhenAllLegacyFieldsWerePresent() {
        val first = "Start by checking the provider metadata. "
        val second = "Keep the published effort order unchanged."
        val corrupted = first.repeat(3) + second.repeat(3)

        assertEquals(
            first + second,
            LegacyReasoningRepair.repairDuplicatedStreamText(corrupted)
        )
    }

    @Test
    fun leavesOrdinaryReasoningWithLocalRepetitionAlone() {
        val normal =
            "This is a normal explanation where the phrase very very important " +
                "appears once, but the entire stream is not duplicated."
        assertNull(LegacyReasoningRepair.repairDuplicatedStreamText(normal))
    }

    @Test
    fun repairsTopLevelAndRegeneratedVariantsAndIsIdempotent() {
        val chunk = "This old reasoning fragment was emitted twice. "
        val corrupted = chunk + chunk
        val variants = arrayListOf(hashMapOf("reasoningText" to corrupted))
        val message = hashMapOf<String, Any>(
            "isBot" to true,
            "reasoningText" to corrupted,
            "variants" to Gson().toJson(variants)
        )
        val history = arrayListOf(message)

        assertTrue(LegacyReasoningRepair.repairHistory(history))
        assertEquals(chunk, message["reasoningText"])
        assertEquals(
            LegacyReasoningRepair.FORMAT_VERSION,
            message["reasoningTextFormat"]
        )

        val type = TypeToken.getParameterized(
            ArrayList::class.java,
            HashMap::class.java
        ).type
        val repairedVariants =
            Gson().fromJson<ArrayList<HashMap<String, String>>>(
                message["variants"].toString(),
                type
            )
        assertEquals(chunk, repairedVariants[0]["reasoningText"])
        assertEquals(
            LegacyReasoningRepair.FORMAT_VERSION,
            repairedVariants[0]["reasoningTextFormat"]
        )
        assertFalse(LegacyReasoningRepair.repairHistory(history))
    }
}
