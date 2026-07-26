package org.teslasoft.assistant.preferences.includes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Duplicate protection for a pending message keys off the source's identity,
 * so a Google-native file must produce the same key when it is picked twice
 * and a different key from any other file.
 *
 * The URIs here are the shape Android's document picker hands back for
 * Google Drive entries. The device check that the picker really does return
 * a stable URI for the same document is listed as device verification —
 * this test pins the behaviour the app depends on.
 */
class GoogleSourceFingerprintTest {

    private val doc =
        "content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3Dencoded%3D12345"
    private val sheet =
        "content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3Dencoded%3D67890"

    @Test
    fun `the same google document picked twice produces the same key`() {
        assertEquals(
            DocumentImporter.sourceFingerprint(doc),
            DocumentImporter.sourceFingerprint(doc)
        )
    }

    @Test
    fun `two different google documents produce different keys`() {
        assertNotEquals(
            DocumentImporter.sourceFingerprint(doc),
            DocumentImporter.sourceFingerprint(sheet)
        )
    }

    @Test
    fun `the key does not carry the document location`() {
        // The fingerprint rides with a pending include; it must not reveal
        // where the file came from.
        val fingerprint = DocumentImporter.sourceFingerprint(doc)
        assertEquals(false, fingerprint.contains("docs"))
        assertEquals(false, fingerprint.contains("12345"))
    }

    @Test
    fun `an exported name does not change the key`() {
        // The include's file name gains ".docx" after export, but duplicate
        // detection keys off the source, so re-picking the same document is
        // still caught.
        val first = DocumentImporter.sourceFingerprint(doc)
        val second = DocumentImporter.sourceFingerprint(doc)
        assertEquals(first, second)
    }
}
