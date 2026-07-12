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

package org.teslasoft.assistant.preferences

/**
 * Reduces storage-layer exceptions to stable, content-free categories before
 * anything reaches the plaintext `storage_health` journal (owner rule: the
 * journal holds metadata and sanitized error categories only — never message
 * text, prompts, keys, file paths, or raw exception messages, because
 * exception messages from the crypto/IO stack can embed file names and
 * provider details).
 *
 * Only exception CLASS NAMES are consulted — `Throwable.message` is never
 * read, so nothing an exception message carries can leak into the journal by
 * construction. Pure Kotlin, unit-tested (StorageErrorSanitizerTest).
 */
object StorageErrorSanitizer {

    /**
     * A short, stable category for one failure. The category set is small on
     * purpose: recovery coordination needs "what kind of failure", never the
     * details. Unknown shapes degrade to the exception's bare class name,
     * clamped and stripped to a safe character set.
     */
    fun categorize(e: Throwable?): String {
        if (e == null) return "unknown"
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 8) {
            val n = t.javaClass.simpleName
            when {
                n.contains("AEADBadTag", ignoreCase = true) ||
                    n.contains("BadPadding", ignoreCase = true) -> return "bad_decrypt"
                n.contains("KeyPermanentlyInvalidated", ignoreCase = true) -> return "key_invalidated"
                n.contains("UnrecoverableKey", ignoreCase = true) ||
                    n.contains("KeyStore", ignoreCase = true) -> return "keystore_unavailable"
                t is java.security.GeneralSecurityException -> return "crypto_failure"
                t is java.io.IOException -> return "io_failure"
            }
            t = t.cause
            depth++
        }
        return sanitizeToken("other_" + e.javaClass.simpleName)
    }

    /**
     * Clamp any string destined for the journal to a harmless token: letters,
     * digits and underscores only, bounded length. Used for the fallback
     * category and available to any future journal writer.
     */
    fun sanitizeToken(raw: String): String =
        raw.map { if (it.isLetterOrDigit() || it == '_') it else '_' }
            .joinToString("").take(48)
}
