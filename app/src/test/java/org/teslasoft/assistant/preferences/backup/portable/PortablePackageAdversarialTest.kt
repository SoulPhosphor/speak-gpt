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

package org.teslasoft.assistant.preferences.backup.portable

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * Adversarial inner-ZIP vectors (owner corrections, July 22 2026): duplicate
 * entry names, a second manifest.json, duplicate artifact names inside the
 * manifest, and an oversized manifest. ZipOutputStream itself refuses
 * duplicate names, so hostile archives are built here as raw ZIP bytes
 * (STORED entries) — exactly what an attacker would hand the reader.
 */
class PortablePackageAdversarialTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ----- minimal raw ZIP writer (STORED entries, duplicates allowed) -------

    private class RawZip {
        private val body = ByteArrayOutputStream()
        private val central = ByteArrayOutputStream()
        private var entryCount = 0

        fun add(name: String, data: ByteArray) {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val crc = CRC32().apply { update(data) }.value
            val offset = body.size()

            val local = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
            local.putInt(0x04034b50)
            local.putShort(20)              // version needed
            local.putShort(0)               // flags
            local.putShort(0)               // method: STORED
            local.putShort(0); local.putShort(0) // time, date
            local.putInt(crc.toInt())
            local.putInt(data.size)         // compressed size
            local.putInt(data.size)         // uncompressed size
            local.putShort(nameBytes.size.toShort())
            local.putShort(0)               // extra len
            body.write(local.array())
            body.write(nameBytes)
            body.write(data)

            val cd = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN)
            cd.putInt(0x02014b50)
            cd.putShort(20)                 // version made by
            cd.putShort(20)                 // version needed
            cd.putShort(0)                  // flags
            cd.putShort(0)                  // method
            cd.putShort(0); cd.putShort(0)  // time, date
            cd.putInt(crc.toInt())
            cd.putInt(data.size)
            cd.putInt(data.size)
            cd.putShort(nameBytes.size.toShort())
            cd.putShort(0)                  // extra
            cd.putShort(0)                  // comment
            cd.putShort(0)                  // disk
            cd.putShort(0)                  // internal attrs
            cd.putInt(0)                    // external attrs
            cd.putInt(offset)
            central.write(cd.array())
            central.write(nameBytes)
            entryCount++
        }

        fun writeTo(file: File) {
            val cdOffset = body.size()
            val cdBytes = central.toByteArray()
            val eocd = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
            eocd.putInt(0x06054b50)
            eocd.putShort(0)                // disk
            eocd.putShort(0)                // cd start disk
            eocd.putShort(entryCount.toShort())
            eocd.putShort(entryCount.toShort())
            eocd.putInt(cdBytes.size)
            eocd.putInt(cdOffset)
            eocd.putShort(0)                // comment len
            file.outputStream().use { out ->
                out.write(body.toByteArray())
                out.write(cdBytes)
                out.write(eocd.array())
            }
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun manifestFor(vararg artifacts: Pair<String, ByteArray>): ByteArray {
        val entries = artifacts.joinToString(",") { (name, data) ->
            """{"name":"$name","type":"x","sha256":"${sha256Hex(data)}"}"""
        }
        return """{"artifacts":[$entries]}""".toByteArray(Charsets.UTF_8)
    }

    private fun failsValidation(zipFile: File): Boolean =
        PortablePackage.validateAndExtract(zipFile, tmp.newFolder()) is PortablePackage.ValidateResult.Failed

    // ----- the required vectors ----------------------------------------------

    @Test
    fun wellFormedRawZipPassesAsBaseline() {
        // Prove the raw writer itself produces archives the reader accepts —
        // otherwise the rejection tests below would pass vacuously.
        val data = ByteArray(64) { 5 }
        val zip = tmp.newFile("ok.zip")
        RawZip().apply {
            add("a.bin", data)
            add(PortablePackage.MANIFEST_ENTRY, manifestFor("a.bin" to data))
        }.writeTo(zip)
        assertTrue(PortablePackage.validateAndExtract(zip, tmp.newFolder()) is PortablePackage.ValidateResult.Ok)
    }

    @Test
    fun duplicateEntryNamesAreRejected() {
        val data = ByteArray(64) { 5 }
        val zip = tmp.newFile("dup_entry.zip")
        RawZip().apply {
            add("a.bin", data)
            add("a.bin", ByteArray(64) { 9 }) // same name, different bytes
            add(PortablePackage.MANIFEST_ENTRY, manifestFor("a.bin" to data))
        }.writeTo(zip)
        assertTrue(failsValidation(zip))
    }

    @Test
    fun secondManifestEntryIsRejected() {
        val data = ByteArray(64) { 5 }
        val zip = tmp.newFile("dup_manifest.zip")
        RawZip().apply {
            add(PortablePackage.MANIFEST_ENTRY, manifestFor("a.bin" to data))
            add("a.bin", data)
            add(PortablePackage.MANIFEST_ENTRY, manifestFor()) // planted second manifest
        }.writeTo(zip)
        assertTrue(failsValidation(zip))
    }

    @Test
    fun duplicateArtifactNamesInsideManifestAreRejected() {
        val data = ByteArray(64) { 5 }
        val manifest =
            """{"artifacts":[{"name":"a.bin","type":"x","sha256":"${sha256Hex(data)}"},{"name":"a.bin","type":"x","sha256":"${sha256Hex(data)}"}]}"""
                .toByteArray(Charsets.UTF_8)
        val zip = tmp.newFile("dup_in_manifest.zip")
        RawZip().apply {
            add("a.bin", data)
            add(PortablePackage.MANIFEST_ENTRY, manifest)
        }.writeTo(zip)
        assertTrue(failsValidation(zip))
    }

    @Test
    fun oversizedManifestIsRejected() {
        val data = ByteArray(16) { 1 }
        // A syntactically valid but > 1 MiB manifest (padding key) — the
        // bounded read must refuse it without buffering it all.
        val padding = "x".repeat(PortablePackage.MAX_MANIFEST_BYTES + 1024)
        val manifest =
            """{"pad":"$padding","artifacts":[{"name":"a.bin","type":"x","sha256":"${sha256Hex(data)}"}]}"""
                .toByteArray(Charsets.UTF_8)
        val zip = tmp.newFile("big_manifest.zip")
        RawZip().apply {
            add("a.bin", data)
            add(PortablePackage.MANIFEST_ENTRY, manifest)
        }.writeTo(zip)
        assertTrue(failsValidation(zip))
    }

    @Test
    fun manifestListingTheManifestItselfIsRejected() {
        // A manifest that names manifest.json as an artifact must not confuse
        // the exact-set matching.
        val data = ByteArray(16) { 1 }
        val manifest = manifestFor("a.bin" to data, PortablePackage.MANIFEST_ENTRY to ByteArray(1))
        val zip = tmp.newFile("self_ref.zip")
        RawZip().apply {
            add("a.bin", data)
            add(PortablePackage.MANIFEST_ENTRY, manifest)
        }.writeTo(zip)
        assertTrue(failsValidation(zip))
    }
}
