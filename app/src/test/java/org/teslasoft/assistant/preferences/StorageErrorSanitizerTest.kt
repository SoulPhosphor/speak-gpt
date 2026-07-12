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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * The plaintext `storage_health` journal may hold sanitized categories only
 * (owner rule). These tests pin the two structural guarantees: exception
 * MESSAGES never reach the output (only class names are consulted), and the
 * fallback token is clamped to a harmless character set.
 */
class StorageErrorSanitizerTest {

    private class AeadBadTagException(message: String) : GeneralSecurityException(message)
    private class UnrecoverableKeyStoreFailure(message: String) : Exception(message)

    @Test fun sensitiveMessagesNeverReachTheCategory() {
        // The message deliberately looks like the worst possible leak: an
        // API key, a chat fragment, and a file path.
        val poison = "sk-SECRET-KEY-12345 /data/data/com.soulphosphor.phosphorshines/shared_prefs/enc.chat_1.xml user said: hello"
        for (e in listOf<Throwable>(
            AeadBadTagException(poison),
            UnrecoverableKeyStoreFailure(poison),
            GeneralSecurityException(poison),
            IOException(poison),
            RuntimeException(poison)
        )) {
            val category = StorageErrorSanitizer.categorize(e)
            assertFalse("category '$category' leaked message content", category.contains("SECRET"))
            assertFalse(category.contains("sk-"))
            assertFalse(category.contains("/"))
            assertFalse(category.contains("hello"))
            assertTrue(category.length <= 48)
        }
    }

    @Test fun knownFailureShapesGetStableCategories() {
        assertEquals("bad_decrypt", StorageErrorSanitizer.categorize(AeadBadTagException("x")))
        assertEquals("keystore_unavailable", StorageErrorSanitizer.categorize(UnrecoverableKeyStoreFailure("x")))
        assertEquals("crypto_failure", StorageErrorSanitizer.categorize(GeneralSecurityException("x")))
        assertEquals("io_failure", StorageErrorSanitizer.categorize(IOException("x")))
        assertEquals("unknown", StorageErrorSanitizer.categorize(null))
    }

    @Test fun causesAreWalkedForTheRealFailure() {
        val wrapped = RuntimeException("outer", AeadBadTagException("inner"))
        assertEquals("bad_decrypt", StorageErrorSanitizer.categorize(wrapped))
    }

    @Test fun unknownShapesFallBackToClampedClassName() {
        val category = StorageErrorSanitizer.categorize(IllegalStateException("boom"))
        assertEquals("other_IllegalStateException", category)
        assertTrue(category.all { it.isLetterOrDigit() || it == '_' })
    }

    @Test fun sanitizeTokenStripsAndClamps() {
        val token = StorageErrorSanitizer.sanitizeToken("a/b:c d\ne" + "x".repeat(100))
        assertTrue(token.all { it.isLetterOrDigit() || it == '_' })
        assertTrue(token.length <= 48)
    }
}
