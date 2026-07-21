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

package org.teslasoft.assistant.preferences.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.lorebook.LoreBookEncryption
import org.teslasoft.assistant.preferences.memory.DatabaseKeys
import org.teslasoft.assistant.preferences.memory.MemoryLog
import org.teslasoft.assistant.preferences.memory.MemoryStore
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherDatabase
import android.database.sqlite.SQLiteDatabase as PlainDatabase

/**
 * The recovery-backup engine (Database Health & Backups, Build Phase 2 item 4):
 * for each of the four artifacts it produces a verified snapshot in internal
 * staging, copies it into the user-chosen SAF folder, reopens and verifies the
 * destination copy, and only then rotates older copies (keep-5 per type). Each
 * type runs independently — one failing never touches another's files or its
 * last-known-good.
 *
 * ⚠️ RUNTIME NOT DEVICE-VERIFIED. This code compiles under CI (the project's
 * only automated gate); its on-device behaviour — SAF provider quirks, the
 * SQLCipher `VACUUM INTO` snapshot, the chat file archive — must be confirmed
 * on a real phone before it is considered working (owner rule). It is written
 * to the proven patterns (LoreBookEncryption's keyed open; MemoryExporter's
 * chat read) and degrades safely: any failure is caught, CATEGORISED
 * (BackupFailureClassifier) and recorded per type; a failure never throws out
 * of [createBackup] and never deletes a last-known-good copy.
 *
 * Snapshot method by store:
 *  - Memory / Lorebook (SQLCipher): `VACUUM INTO` on a connection opened with
 *    the store's own key. SQLCipher bundles a modern SQLite (VACUUM INTO ≥
 *    3.27) and the copy inherits the source's encryption, so the snapshot is
 *    encrypted with the same key with no raw-key ATTACH hazard.
 *  - User Image catalog (plain framework SQLite): a row-level rebuild into a
 *    fresh staged database. `VACUUM INTO` is NOT used here — framework SQLite
 *    on minSdk 28 predates it. The JPEG files are deliberately NOT backed up
 *    (owner ruling): this artifact is the catalog only.
 *  - Chats: the encrypted SharedPreferences files, archived under
 *    CHAT_LIST_LOCK with a versioned manifest + per-file hashes. Same-install
 *    recovery copy; the readable path is the portable JSON export.
 */
object RecoveryBackupManager {

    data class TypeResult(val type: BackupType, val success: Boolean, val category: BackupFailureCategory?)

    private const val CHAT_LIST_FILE = "chat_list"

    /**
     * Run a backup of all four types into [treeUri] (a persisted SAF tree).
     * Records per-type success/failure in [RecoveryBackupState] and returns the
     * per-type results. Never throws.
     */
    fun createBackup(context: Context, treeUri: Uri): List<TypeResult> =
        BackupType.displayOrder.map { runOne(context, it, treeUri) }

    private fun runOne(context: Context, type: BackupType, treeUri: Uri): TypeResult {
        val now = System.currentTimeMillis()
        val staged = File(context.cacheDir, "backup_stage_${type.key}_${System.nanoTime()}.tmp")
        var stage = BackupStage.READ_SOURCE
        try {
            // ---- snapshot to internal staging (SOURCE stage) ----
            val produced = snapshot(context, type, staged)
            if (!produced) {
                // Nothing to back up (store absent / empty). Not a failure and
                // not a success — leave this type's status untouched.
                return TypeResult(type, success = false, category = null)
            }

            // ---- verify the staged snapshot (still SOURCE) ----
            stage = BackupStage.VERIFY_STAGED
            verifyStaged(context, type, staged)

            // ---- copy to the destination folder (DESTINATION stages) ----
            stage = BackupStage.WRITE_DESTINATION
            val childUri = writeToTree(context, treeUri, destName(type, now), staged)

            stage = BackupStage.VERIFY_DESTINATION
            verifyDestination(context, childUri, staged.length())

            // ---- rotate keep-5 (best-effort; never fails the backup) ----
            try { rotate(context, treeUri, type) } catch (_: Exception) { /* rotation is best-effort */ }

            RecoveryBackupState.recordSuccess(context, type, now)
            return TypeResult(type, success = true, category = null)
        } catch (e: Exception) {
            val category = BackupFailureClassifier.classify(stage, e)
            RecoveryBackupState.recordFailure(context, type, now, category)
            MemoryLog.log(context, "RecoveryBackup", "error",
                "Recovery backup of ${type.key} failed at $stage (${category.name}).")
            return TypeResult(type, success = false, category = category)
        } finally {
            try { if (staged.exists()) staged.delete() } catch (_: Exception) { }
        }
    }

    // ---- snapshot producers -------------------------------------------------

    /** @return true if a snapshot file was produced, false if there was
     *  nothing to back up (store absent). Throws on a real source failure. */
    private fun snapshot(context: Context, type: BackupType, staged: File): Boolean {
        if (staged.exists()) staged.delete()
        return when (type) {
            BackupType.MEMORY -> snapshotCipher(
                context, MemoryStore.DATABASE_NAME,
                DatabaseKeys.getOrCreate(context, DatabaseKeys.KEY_MEMORY, MemoryStore.isProvisioned(context)),
                staged
            )
            BackupType.LOREBOOK -> snapshotCipher(
                context, "lorebook.db",
                LoreBookEncryption.obtainPassword(context, "lorebook.db"),
                staged
            )
            BackupType.USER_IMAGE -> snapshotUserImageCatalog(context, staged)
            BackupType.CHATS -> snapshotChats(context, staged)
        }
    }

    /** VACUUM INTO a staged encrypted copy, keyed like the source. A null/empty
     *  key means the store is locked or unmigrated — a real SOURCE failure. */
    private fun snapshotCipher(context: Context, dbName: String, key: ByteArray?, staged: File): Boolean {
        val src = context.getDatabasePath(dbName)
        if (!src.exists()) return false
        if (key == null || key.isEmpty()) throw IllegalStateException("$dbName key unavailable")
        LoreBookEncryption.loadLibrary() // idempotent; the SQLCipher .so may not be loaded yet
        val db = CipherDatabase.openDatabase(src.path, key, null, CipherDatabase.OPEN_READONLY, null, null)
        try {
            db.execSQL("VACUUM INTO '" + staged.path.replace("'", "''") + "'")
        } finally {
            try { db.close() } catch (_: Exception) { }
        }
        return staged.exists() && staged.length() > 0
    }

    /** Row-level rebuild of the plain profile-image catalog into a staged DB. */
    private fun snapshotUserImageCatalog(context: Context, staged: File): Boolean {
        val src = context.getDatabasePath("profile_images.db")
        if (!src.exists()) return false
        val source = PlainDatabase.openDatabase(src.path, null, PlainDatabase.OPEN_READONLY)
        val target = PlainDatabase.openOrCreateDatabase(staged.path, null)
        try {
            target.execSQL(
                "CREATE TABLE IF NOT EXISTS profile_images (" +
                    "hash TEXT PRIMARY KEY NOT NULL, created_at INTEGER NOT NULL)"
            )
            source.rawQuery("SELECT hash, created_at FROM profile_images", null).use { c ->
                target.beginTransaction()
                try {
                    while (c.moveToNext()) {
                        target.execSQL(
                            "INSERT OR IGNORE INTO profile_images (hash, created_at) VALUES (?, ?)",
                            arrayOf<Any?>(c.getString(0), c.getLong(1))
                        )
                    }
                    target.setTransactionSuccessful()
                } finally {
                    target.endTransaction()
                }
            }
        } finally {
            try { source.close() } catch (_: Exception) { }
            try { target.close() } catch (_: Exception) { }
        }
        return staged.exists() && staged.length() > 0
    }

    /**
     * Archive the encrypted chat SharedPreferences files under CHAT_LIST_LOCK,
     * with a versioned manifest and per-file SHA-256s. Held inside the lock so
     * no chat mutation interleaves (verified lock order: CHAT_LIST_LOCK →
     * SecurePrefs monitor). A non-authoritative chat list (Round-4 locked)
     * yields no archive — the chats artifact simply pauses.
     */
    private fun snapshotChats(context: Context, staged: File): Boolean {
        val sharedPrefsDir = File(context.dataDir, "shared_prefs")
        // Hold CHAT_LIST_LOCK across BOTH the manifest read and the file archive
        // so no mutation can interleave between them (build() re-enters the same
        // lock, which is safe). Verified lock order: CHAT_LIST_LOCK first, then
        // SecurePrefs monitor.
        synchronized(ChatPreferences.CHAT_LIST_LOCK) {
            val manifest = ChatSnapshotManifest.build(context)
            if (!manifest.listAuthoritative) {
                // Chat storage is locked/unreadable: pause ONLY this artifact.
                throw IllegalStateException("chat storage unavailable")
            }
            val files = LinkedHashMap<String, File>() // archive entry name -> source file
            addEncFile(sharedPrefsDir, CHAT_LIST_FILE, files)
            for (entry in manifest.chats) {
                addEncFile(sharedPrefsDir, "chat_${entry.chatId}", files)
                addEncFile(sharedPrefsDir, "settings.${entry.chatId}", files)
            }
            val fileHashes = JSONObject()
            ZipOutputStream(staged.outputStream().buffered()).use { zip ->
                for ((entryName, src) in files) {
                    zip.putNextEntry(ZipEntry(entryName))
                    val bytes = src.readBytes()
                    zip.write(bytes)
                    zip.closeEntry()
                    fileHashes.put(entryName, sha256(bytes))
                }
                val meta = JSONObject()
                meta.put("manifest_version", ChatSnapshotManifest.MANIFEST_VERSION)
                meta.put("complete", manifest.complete)
                val chatsArr = JSONArray()
                for (entry in manifest.chats) {
                    chatsArr.put(
                        JSONObject()
                            .put("chat_id", entry.chatId)
                            .put("available", entry.available)
                            .apply { if (entry.contentHash != null) put("content_hash", entry.contentHash) }
                    )
                }
                meta.put("chats", chatsArr)
                meta.put("file_hashes", fileHashes)
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(meta.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return staged.exists() && staged.length() > 0
    }

    private fun addEncFile(sharedPrefsDir: File, logicalName: String, out: LinkedHashMap<String, File>) {
        val f = File(sharedPrefsDir, "enc.$logicalName.xml")
        if (f.exists()) out["enc.$logicalName.xml"] = f
    }

    // ---- staged verification ------------------------------------------------

    private fun verifyStaged(context: Context, type: BackupType, staged: File) {
        if (!staged.exists() || staged.length() == 0L) throw IllegalStateException("staged snapshot missing")
        when (type) {
            BackupType.MEMORY -> integrityCheckCipher(
                staged, DatabaseKeys.getOrCreate(context, DatabaseKeys.KEY_MEMORY, true)
            )
            BackupType.LOREBOOK -> integrityCheckCipher(
                staged, LoreBookEncryption.obtainPassword(context, "lorebook.db")
            )
            BackupType.USER_IMAGE -> integrityCheckPlain(staged)
            BackupType.CHATS -> { /* ZIP entries + manifest were written and hashed above */ }
        }
    }

    private fun integrityCheckCipher(staged: File, key: ByteArray?) {
        if (key == null || key.isEmpty()) throw IllegalStateException("snapshot key unavailable")
        LoreBookEncryption.loadLibrary()
        val db = CipherDatabase.openDatabase(staged.path, key, null, CipherDatabase.OPEN_READONLY, null, null)
        try { assertIntegrity(db.rawQuery("PRAGMA integrity_check", emptyArray<String>())) }
        finally { try { db.close() } catch (_: Exception) { } }
    }

    private fun integrityCheckPlain(staged: File) {
        val db = PlainDatabase.openDatabase(staged.path, null, PlainDatabase.OPEN_READONLY)
        try { assertIntegrity(db.rawQuery("PRAGMA integrity_check", null)) }
        finally { try { db.close() } catch (_: Exception) { } }
    }

    private fun assertIntegrity(cursor: android.database.Cursor) {
        cursor.use {
            val ok = it.moveToFirst() && it.getString(0).equals("ok", ignoreCase = true)
            if (!ok) throw IllegalStateException("staged snapshot failed integrity check")
        }
    }

    // ---- SAF destination write + verify + rotation --------------------------

    private fun writeToTree(context: Context, treeUri: Uri, displayName: String, staged: File): Uri {
        val resolver = context.contentResolver
        val parentDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
        val child = DocumentsContract.createDocument(resolver, parentDocUri, "application/octet-stream", displayName)
            ?: throw IllegalStateException("could not create destination document")
        try {
            resolver.openOutputStream(child)?.use { out ->
                staged.inputStream().use { input -> input.copyTo(out) }
            } ?: throw IllegalStateException("could not open destination for writing")
        } catch (e: Exception) {
            // Roll back the half-written document so it can never masquerade as
            // a good backup; the last-known-good copies are untouched.
            try { DocumentsContract.deleteDocument(resolver, child) } catch (_: Exception) { }
            throw e
        }
        return child
    }

    private fun verifyDestination(context: Context, childUri: Uri, expectedLength: Long) {
        val resolver = context.contentResolver
        val actual = resolver.openInputStream(childUri)?.use { it.readBytes().size.toLong() }
            ?: throw IllegalStateException("could not reopen destination for verification")
        if (actual != expectedLength) {
            try { DocumentsContract.deleteDocument(resolver, childUri) } catch (_: Exception) { }
            throw IllegalStateException("destination verification failed (size mismatch)")
        }
    }

    private fun rotate(context: Context, treeUri: Uri, type: BackupType) {
        val resolver = context.contentResolver
        val parentDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val prefix = type.key + "-"
        val existing = ArrayList<Pair<String, String>>() // displayName -> documentId
        resolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val id = c.getString(1) ?: continue
                if (name.startsWith(prefix)) existing.add(name to id)
            }
        }
        // Oldest first: destName timestamps are lexically sortable.
        val oldestFirst = existing.sortedBy { it.first }
        val toDelete = BackupRotationPlanner.toDelete(oldestFirst.map { it.first })
        val deleteSet = toDelete.toHashSet()
        for ((name, id) in oldestFirst) {
            if (name in deleteSet) {
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                try { DocumentsContract.deleteDocument(resolver, docUri) } catch (_: Exception) { }
            }
        }
    }

    // ---- helpers ------------------------------------------------------------

    /** A filesystem-safe, lexically-sortable name: "<type>-<epochMillis>.<ext>". */
    private fun destName(type: BackupType, atMillis: Long): String {
        val ext = if (type == BackupType.CHATS) "zip" else "dbbackup"
        // Zero-padded millis keeps lexical order == chronological order.
        return "${type.key}-${atMillis.toString().padStart(14, '0')}.$ext"
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
