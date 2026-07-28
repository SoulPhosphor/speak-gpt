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

package org.teslasoft.assistant.preferences.includes

import android.os.StatFs
import java.io.File
import kotlin.math.max

/**
 * One admission budget for one document import.
 *
 * Charges describe retained working state rather than source-file size. A
 * parser may stream a much larger file successfully when its retained output,
 * indexes and current parser buffers stay inside this allowance.
 */
class ImportMemoryBudget private constructor(val allowanceBytes: Long) {

    class LimitExceeded : Exception()

    class Charge internal constructor(
        private val owner: ImportMemoryBudget,
        internal var bytes: Long
    ) {
        private var released = false

        fun resize(newBytes: Long) {
            check(!released)
            owner.resize(this, newBytes)
        }

        fun release() {
            if (released) return
            released = true
            owner.release(bytes)
            bytes = 0
        }
    }

    private var retainedBytes: Long = 0

    @Synchronized
    fun claim(bytes: Long): Charge {
        require(bytes >= 0)
        if (bytes > allowanceBytes - retainedBytes) throw LimitExceeded()
        retainedBytes += bytes
        return Charge(this, bytes)
    }

    @Synchronized
    private fun resize(charge: Charge, newBytes: Long) {
        require(newBytes >= 0)
        val delta = newBytes - charge.bytes
        if (delta > allowanceBytes - retainedBytes) throw LimitExceeded()
        retainedBytes += delta
        charge.bytes = newBytes
    }

    @Synchronized
    private fun release(bytes: Long) {
        retainedBytes = (retainedBytes - bytes).coerceAtLeast(0)
    }

    @Synchronized
    internal fun retainedForTest(): Long = retainedBytes

    companion object {
        const val MIN_IMPORT_ALLOWANCE = 8L * 1024L * 1024L
        private const val MAX_IMPORT_ALLOWANCE = 64L * 1024L * 1024L

        data class Admission(
            val heapLimit: Long,
            val heapAvailable: Long,
            val allowance: Long
        ) {
            val canBegin: Boolean
                get() = allowance >= MIN_IMPORT_ALLOWANCE
        }

        fun admission(runtime: Runtime = Runtime.getRuntime()): Admission {
            val heapLimit = runtime.maxMemory()
            val heapUsed = runtime.totalMemory() - runtime.freeMemory()
            val heapAvailable = (heapLimit - heapUsed).coerceAtLeast(0)
            val allowance = minOf(
                heapLimit / 4,
                heapAvailable / 2,
                MAX_IMPORT_ALLOWANCE
            )
            return Admission(heapLimit, heapAvailable, allowance)
        }

        fun fromAdmission(admission: Admission): ImportMemoryBudget =
            ImportMemoryBudget(admission.allowance)

        internal fun forTest(allowanceBytes: Long): ImportMemoryBudget =
            ImportMemoryBudget(allowanceBytes)
    }
}

/**
 * App-private filesystem limits used by temporary document copies.
 *
 * The reserve is re-read before every write so another process consuming
 * storage during an import cannot make SpeakGPT cross the protected floor.
 */
class ImportStorageGuard(private val directory: File) {

    class LimitExceeded : Exception()

    data class Snapshot(
        val capacityBytes: Long,
        val availableBytes: Long,
        val reserveBytes: Long
    ) {
        val maximumExpandedBytes: Long
            get() = minOf(256L * 1024L * 1024L, availableBytes / 2)
    }

    fun snapshot(): Snapshot {
        val stat = StatFs(directory.absolutePath)
        val capacity = stat.totalBytes.coerceAtLeast(0)
        val available = stat.availableBytes.coerceAtLeast(0)
        val reserve = max(
            128L * 1024L * 1024L,
            capacity / 20
        )
        return Snapshot(capacity, available, reserve)
    }

    fun requireWrite(bytes: Int) {
        require(bytes >= 0)
        val state = snapshot()
        if (state.availableBytes - bytes.toLong() < state.reserveBytes) {
            throw LimitExceeded()
        }
    }
}

/**
 * Complete-archive expansion accounting for DOCX and XLSX ZIP containers.
 */
class OfficeArchiveExpansionGuard(
    private val compressedSourceBytes: Long,
    private val maximumExpandedBytes: Long
) {
    class LimitExceeded : Exception()

    private var expandedBytes: Long = 0
    private var normalizedOutputBytes: Long = 0

    fun preflightTotal(totalExpandedBytes: Long) {
        if (totalExpandedBytes < 0) return
        enforce(totalExpandedBytes)
    }

    fun account(bytes: Int) {
        if (bytes <= 0) return
        if (expandedBytes > Long.MAX_VALUE - bytes.toLong()) throw LimitExceeded()
        enforce(expandedBytes + bytes)
        expandedBytes += bytes
    }

    fun accountNormalized(bytes: Int) {
        if (bytes <= 0) return
        if (normalizedOutputBytes > Long.MAX_VALUE - bytes.toLong()) throw LimitExceeded()
        normalizedOutputBytes += bytes
        // Retained normalized output is bounded by ImportMemoryBudget. The
        // archive ceiling applies to decompressed ZIP-entry data, not to a
        // second, differently encoded representation of that data.
    }

    private fun enforce(candidate: Long) {
        if (candidate > maximumExpandedBytes) throw LimitExceeded()
        if (compressedSourceBytes >= RATIO_ENFORCEMENT_START &&
            candidate > compressedSourceBytes.saturatedTimes(MAX_EXPANSION_RATIO)
        ) {
            throw LimitExceeded()
        }
    }

    internal fun expandedForTest(): Long = expandedBytes
    internal fun normalizedForTest(): Long = normalizedOutputBytes

    private fun Long.saturatedTimes(multiplier: Long): Long =
        if (this > Long.MAX_VALUE / multiplier) Long.MAX_VALUE else this * multiplier

    companion object {
        const val MAX_EXPANSION_RATIO = 100L
        const val RATIO_ENFORCEMENT_START = 1L * 1024L * 1024L
    }
}

/**
 * StringBuilder wrapper whose retained UTF-16 capacity and final String copy
 * are charged to the import budget before allocation.
 */
internal class BudgetedTextBuilder(
    private val budget: ImportMemoryBudget,
    initialCapacity: Int = 1024
) {
    class RetainedText internal constructor(
        val text: String,
        private val charge: ImportMemoryBudget.Charge
    ) {
        fun release() = charge.release()
    }

    private val builderCharge = budget.claim(initialCapacity.toLong() * 2L)
    private var builder = StringBuilder(initialCapacity)
    private var finished = false

    val length: Int
        get() = builder.length

    fun isNotEmpty(): Boolean = builder.isNotEmpty()

    fun append(value: Char): BudgetedTextBuilder {
        ensureAdditional(1)
        builder.append(value)
        return this
    }

    fun append(value: CharSequence): BudgetedTextBuilder {
        ensureAdditional(value.length)
        builder.append(value)
        return this
    }

    fun append(value: CharArray, offset: Int, count: Int): BudgetedTextBuilder {
        ensureAdditional(count)
        builder.append(value, offset, count)
        return this
    }

    fun setLength(length: Int) {
        builder.setLength(length.coerceIn(0, builder.length))
    }

    fun charAt(index: Int): Char = builder[index]

    /**
     * Claims the immutable UTF-16 String before creating it. The builder's
     * charge is released only after the copy exists, so peak duplication is
     * part of admission rather than an accidental OOM test.
     */
    fun finish(): String {
        return finishRetained().text
    }

    fun finishRetained(): RetainedText {
        check(!finished)
        val stringCharge = budget.claim(builder.length.toLong() * 2L)
        return try {
            val result = builder.toString()
            finished = true
            builderCharge.release()
            RetainedText(result, stringCharge)
        } catch (e: OutOfMemoryError) {
            stringCharge.release()
            throw e
        } catch (e: Exception) {
            stringCharge.release()
            throw e
        }
    }

    fun discard() {
        if (finished) return
        finished = true
        builder.setLength(0)
        builderCharge.release()
    }

    private fun ensureAdditional(additional: Int) {
        if (additional <= 0) return
        val required = builder.length.toLong() + additional.toLong()
        if (required > Int.MAX_VALUE) throw ImportMemoryBudget.LimitExceeded()
        if (required <= builder.capacity()) return
        val grown = max(
            required,
            builder.capacity().toLong() * 2L + 2L
        ).coerceAtMost(Int.MAX_VALUE.toLong())
        builderCharge.resize(grown * 2L)
        builder.ensureCapacity(grown.toInt())
    }
}
