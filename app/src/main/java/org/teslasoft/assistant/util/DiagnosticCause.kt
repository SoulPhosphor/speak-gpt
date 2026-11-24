package org.teslasoft.assistant.util

/** Exception types, messages and related failures, without stack traces or surrounding content. */
object DiagnosticCause {
    fun describe(failure: Throwable): String {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        return buildString {
            fun appendFailure(current: Throwable, prefix: String) {
                if (!seen.add(current)) return
                if (seen.size > 16) {
                    append("; further exception details omitted")
                    return
                }
                append(prefix).append(current.javaClass.name)
                current.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
                current.cause?.let { appendFailure(it, "; caused by ") }
                current.suppressed.forEach { appendFailure(it, "; suppressed: ") }
            }
            appendFailure(failure, "")
        }
    }
}
