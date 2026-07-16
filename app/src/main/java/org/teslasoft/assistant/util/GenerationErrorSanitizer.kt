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

/** Privacy-safe exception diagnostics for the user-visible Error Log. */
object GenerationErrorSanitizer {

    fun exceptionClassNames(error: Throwable): List<String> =
        causeChain(error).map { it::class.qualifiedName ?: it.javaClass.name }

    /**
     * Do not copy arbitrary exception messages into the log. HTTP-client
     * messages can contain URLs, headers, provider bodies, or echoed request
     * content. The classifier already extracted the useful safe facts, so this
     * field records only a structural summary.
     */
    fun safeDetail(result: GenErrorResult): String = when {
        result.httpStatus != null -> "HTTP response detail omitted; status recorded separately"
        result.kind == GenFailureKind.CONNECT_TIMEOUT -> "Connection timeout"
        result.kind == GenFailureKind.NO_RESPONSE_DATA_TIMEOUT -> "Read/socket timeout before response data"
        result.kind == GenFailureKind.STREAM_INACTIVITY_TIMEOUT -> "Read/socket inactivity after response data"
        result.kind == GenFailureKind.OVERALL_TIMEOUT -> "Overall request timeout"
        result.code == GenErrorCode.N1 -> "Connection closed during the request"
        result.code == GenErrorCode.N3 -> "Host lookup or reachability failure"
        else -> "Exception message omitted for privacy"
    }

    /**
     * Stack frames only: no exception messages, suppressed-exception text,
     * URLs, request/response bodies, or headers. Cause classes are emitted as
     * headings and are also logged in a compact field beside this trace.
     */
    fun safeStackTrace(error: Throwable, maxFrames: Int = 80): String {
        val out = StringBuilder()
        var remaining = maxFrames.coerceAtLeast(0)
        for ((index, cause) in causeChain(error).withIndex()) {
            if (index > 0) out.append("\nCaused by: ")
            out.append(cause::class.qualifiedName ?: cause.javaClass.name)
            for (frame in cause.stackTrace) {
                if (remaining-- <= 0) {
                    out.append("\n  ... additional frames omitted")
                    return out.toString()
                }
                out.append("\n  at ")
                    .append(frame.className)
                    .append('.')
                    .append(frame.methodName)
                    .append('(')
                    .append(frame.fileName ?: "Unknown Source")
                if (frame.lineNumber >= 0) out.append(':').append(frame.lineNumber)
                out.append(')')
            }
        }
        return out.toString()
    }

    private fun causeChain(error: Throwable): List<Throwable> {
        val out = ArrayList<Throwable>()
        val seen = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<Throwable, Boolean>()
        )
        var current: Throwable? = error
        while (current != null && seen.add(current)) {
            out.add(current)
            current = current.cause
        }
        return out
    }
}
