/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.reasoning

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type

/**
 * Repairs reasoning text saved by the legacy stream collector. That collector
 * appended the same provider delta once for every equivalent response field
 * (reasoning, reasoning_content, and reasoning_details[].text), producing
 * adjacent two- or three-copy runs in already-saved Thinking blocks.
 *
 * The repair is deliberately conservative: the complete string must be
 * explainable as repeated adjacent stream chunks. A partial/local repetition is
 * never rewritten. Work runs on ChatActivity's storage worker before UI binding.
 */
object LegacyReasoningRepair {
    const val FORMAT_VERSION = "single_source_v2"

    private const val REASONING_KEY = "reasoningText"
    private const val FORMAT_KEY = "reasoningTextFormat"
    private const val VARIANTS_KEY = "variants"
    private const val MIN_REPAIR_LENGTH = 32
    private const val MAX_STREAM_CHUNK = 8 * 1024

    private val gson = Gson()
    private val variantsType: Type = TypeToken.getParameterized(
        ArrayList::class.java,
        HashMap::class.java
    ).type

    fun repairHistory(messages: MutableList<HashMap<String, Any>>): Boolean {
        var changed = false
        for (message in messages) {
            if (repairMessage(message)) changed = true
        }
        return changed
    }

    internal fun repairDuplicatedStreamText(text: String?): String? {
        val source = text?.takeIf { it.length >= MIN_REPAIR_LENGTH } ?: return null
        val failed = HashSet<Int>()
        val choices = HashMap<Int, Candidate>()
        val stack = ArrayList<Frame>()
        stack.add(Frame(0, candidatesAt(source, 0)))

        var complete = false
        while (stack.isNotEmpty()) {
            val frame = stack.last()
            if (frame.nextIndex >= frame.candidates.size) {
                failed.add(frame.position)
                choices.remove(frame.position)
                stack.removeAt(stack.lastIndex)
                continue
            }

            val candidate = frame.candidates[frame.nextIndex++]
            val next = frame.position + candidate.consumed
            if (next in failed) continue
            choices[frame.position] = candidate
            if (next == source.length) {
                complete = true
                break
            }

            val nextCandidates = candidatesAt(source, next)
            if (nextCandidates.isEmpty()) {
                failed.add(next)
                continue
            }
            stack.add(Frame(next, nextCandidates))
        }
        if (!complete) return null

        val repaired = StringBuilder(source.length / 2)
        var position = 0
        while (position < source.length) {
            val candidate = choices[position] ?: return null
            repaired.append(source, position, position + candidate.unitLength)
            position += candidate.consumed
        }
        return repaired.toString().takeIf { it.length < source.length }
    }

    private fun repairMessage(message: HashMap<String, Any>): Boolean {
        var changed = repairReasoningMap(message)

        val rawVariants = message[VARIANTS_KEY]?.toString().orEmpty()
        if (rawVariants.isBlank()) return changed
        val variants = try {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson<ArrayList<HashMap<String, String>>>(rawVariants, variantsType)
        } catch (_: Exception) {
            null
        } ?: return changed

        var variantsChanged = false
        for (variant in variants) {
            if (variant[FORMAT_KEY] == FORMAT_VERSION) continue
            val repaired = repairDuplicatedStreamText(variant[REASONING_KEY]) ?: continue
            variant[REASONING_KEY] = repaired
            variant[FORMAT_KEY] = FORMAT_VERSION
            variantsChanged = true
        }
        if (variantsChanged) {
            message[VARIANTS_KEY] = gson.toJson(variants)
            changed = true
        }
        return changed
    }

    private fun repairReasoningMap(message: HashMap<String, Any>): Boolean {
        if (message[FORMAT_KEY]?.toString() == FORMAT_VERSION) return false
        val repaired = repairDuplicatedStreamText(message[REASONING_KEY]?.toString()) ?: return false
        message[REASONING_KEY] = repaired
        message[FORMAT_KEY] = FORMAT_VERSION
        return true
    }

    private fun candidatesAt(source: String, position: Int): List<Candidate> {
        val maxUnit = minOf(MAX_STREAM_CHUNK, (source.length - position) / 2)
        if (maxUnit <= 0) return emptyList()
        val candidates = ArrayList<Candidate>()
        for (unit in 1..maxUnit) {
            if (!source.regionMatches(position, source, position + unit, unit)) continue
            val hasThird = position + unit * 3 <= source.length &&
                source.regionMatches(position, source, position + unit * 2, unit)
            candidates.add(Candidate(unit, if (hasThird) 3 else 2))
        }
        candidates.sortWith(
            compareByDescending<Candidate> { it.consumed }
                .thenByDescending { it.unitLength }
        )
        return candidates
    }

    private data class Candidate(
        val unitLength: Int,
        val repeatCount: Int
    ) {
        val consumed: Int get() = unitLength * repeatCount
    }

    private data class Frame(
        val position: Int,
        val candidates: List<Candidate>,
        var nextIndex: Int = 0
    )
}
