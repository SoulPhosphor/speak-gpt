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

package org.teslasoft.assistant.util

/** Text of the memory fields in the diagnostic logs. Pure, so it is unit-tested. */
internal object MemoryFields {

    /**
     * Android's own per-app breakdown (the same categories as the system's
     * memory report), from Debug.MemoryInfo.getMemoryStats() values in KB:
     * `java=… native=… graphics=… code=… other=… swap=…`. A category Android
     * did not report shows "?".
     */
    fun breakdown(stats: Map<String, String>): String {
        fun field(name: String, key: String): String {
            val kb = stats[key]?.trim()?.toLongOrNull()
            return "$name=${if (kb == null) "?" else "${(kb + 512) / 1024}MB"}"
        }
        return listOf(
            field("java", "summary.java-heap"),
            field("native", "summary.native-heap"),
            field("graphics", "summary.graphics"),
            field("code", "summary.code"),
            field("other", "summary.private-other"),
            field("swap", "summary.total-swap")
        ).joinToString(" ")
    }

    /** `heap=<in use>/<limit>MB`. */
    fun javaHeap(usedBytes: Long, maxBytes: Long): String =
        "heap=${mb(usedBytes)}/${mb(maxBytes)}MB"

    private fun mb(bytes: Long): Long = (bytes + (512L * 1024L)) / (1024L * 1024L)
}
