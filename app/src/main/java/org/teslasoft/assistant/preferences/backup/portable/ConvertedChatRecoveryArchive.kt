/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.preferences.backup.portable

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.teslasoft.assistant.preferences.backup.ChatRestoreManager
import org.teslasoft.assistant.preferences.backup.ChatSnapshotManifest
import org.teslasoft.assistant.preferences.chatnavigation.ChatNavigationRepository

/**
 * Builds the normal same-install chat-recovery ZIP directly from a logical
 * conversion plan. The encrypted preference files are created under isolated
 * temporary SharedPreferences names and only their bytes are placed into the
 * archive under the live restore names. No live chat preference is opened,
 * cleared or written.
 */
object ConvertedChatRecoveryArchive {

    fun write(context: Context, plan: ChatLogicalImportPlan.Plan, output: File): Boolean =
        write(context, plan.chats, folders = null, output = output)

    /** Permanent v1/v2 restore staging. A non-null folder list records the
     * authoritative v2 folder catalog; v1 passes an empty list and therefore
     * rebuilds every chat unfiled. */
    fun write(context: Context, plan: PortableChatRestorePlan.Plan, output: File): Boolean =
        write(context, plan.chats, plan.folders, output)

    private fun write(
        context: Context,
        chats: List<ChatLogicalImportPlan.ChatPlan>,
        folders: List<PortableChatRestorePlan.FolderPlan>?,
        output: File
    ): Boolean {
        val app = context.applicationContext
        val prefix = "legacy_conversion_${UUID.randomUUID()}_"
        val temporaryNames = ArrayList<String>()
        val files = LinkedHashMap<String, File>()

        // EncryptedSharedPreferences binds encrypted keys to the supplied file
        // name. Supply the final name to the crypto layer while redirecting the
        // actual backing SharedPreferences file to an isolated temporary name.
        val isolated = object : ContextWrapper(app) {
            // EncryptedSharedPreferences immediately replaces the supplied
            // context with context.applicationContext. Keep that lookup inside
            // this wrapper or its getSharedPreferences override is bypassed and
            // the converter writes the final names into live app storage.
            override fun getApplicationContext(): Context = this

            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val temporaryName = prefix + name
                if (temporaryName !in temporaryNames) temporaryNames.add(temporaryName)
                return app.getSharedPreferences(temporaryName, mode)
            }
        }

        try {
            if (output.exists() && !output.delete()) return false
            val masterKey = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            fun encrypted(finalName: String): SharedPreferences =
                EncryptedSharedPreferences.create(
                    isolated,
                    finalName,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )

            fun retain(finalName: String) {
                val temporaryName = prefix + finalName
                val physical = File(
                    File(app.applicationInfo.dataDir, "shared_prefs"),
                    "$temporaryName.xml"
                )
                check(physical.exists() && physical.length() > 0L) {
                    "temporary encrypted preference was not written"
                }
                files["$finalName.xml"] = physical
            }

            val listName = "enc.chat_list"
            val listJson = Gson().toJson(chats.map { it.listRow })
            val listPreferences = encrypted(listName)
            val listEditor = listPreferences.edit().putString("data", listJson)
            if (folders != null) {
                listEditor
                    .putString(
                        ChatNavigationRepository.FOLDERS_KEY,
                        ChatFolderPortableCodec.encodeStored(folders)
                    )
                    .putInt(
                        ChatNavigationRepository.SCHEMA_VERSION_KEY,
                        ChatNavigationRepository.SCHEMA_VERSION
                    )
            }
            check(listEditor.commit()) { "chat list write failed" }
            check(listPreferences.getString("data", null) == listJson) {
                "chat list verification failed"
            }
            if (folders != null) {
                check(
                    listPreferences.getString(ChatNavigationRepository.FOLDERS_KEY, null) ==
                        ChatFolderPortableCodec.encodeStored(folders)
                ) { "chat folder verification failed" }
            }
            retain(listName)

            for (chat in chats) {
                val historyName = "enc.chat_${chat.chatId}"
                val historyPreferences = encrypted(historyName)
                check(
                    historyPreferences.edit()
                        .putString("chat", chat.messagesJson)
                        .commit()
                ) { "chat history write failed" }
                check(historyPreferences.getString("chat", null) == chat.messagesJson) {
                    "chat history verification failed"
                }
                retain(historyName)

                val settingsName = "enc.settings.${chat.chatId}"
                val settingsPreferences = encrypted(settingsName)
                val editor = settingsPreferences.edit()
                for (entry in chat.settings) putSetting(editor, entry)
                check(editor.commit()) { "chat settings write failed" }
                check(settingsPreferences.all == expectedSettings(chat.settings)) {
                    "chat settings verification failed"
                }
                retain(settingsName)
            }

            writeZip(output, chats, files)
            if (!ChatRestoreManager.archivePassesValidation(output)) {
                output.delete()
                return false
            }
            return true
        } catch (_: Exception) {
            runCatching { output.delete() }
            return false
        } finally {
            for (name in temporaryNames) {
                runCatching { app.deleteSharedPreferences(name) }
            }
        }
    }

    private fun putSetting(
        editor: SharedPreferences.Editor,
        entry: ChatLogicalImportPlan.SettingEntry
    ) {
        when (val value = entry.value) {
            is String -> editor.putString(entry.key, value)
            is Boolean -> editor.putBoolean(entry.key, value)
            is Int -> editor.putInt(entry.key, value)
            is Long -> editor.putLong(entry.key, value)
            is Float -> editor.putFloat(entry.key, value)
            is Set<*> -> editor.putStringSet(
                entry.key,
                value.mapTo(LinkedHashSet()) { it.toString() }
            )
            else -> throw IllegalArgumentException("unsupported setting value")
        }
    }

    private fun expectedSettings(
        settings: List<ChatLogicalImportPlan.SettingEntry>
    ): Map<String, Any> = settings.associate { entry ->
        val value = if (entry.value is Set<*>) {
            entry.value.mapTo(LinkedHashSet()) { it.toString() }
        } else {
            entry.value
        }
        entry.key to value
    }

    private fun writeZip(
        output: File,
        chatsToWrite: List<ChatLogicalImportPlan.ChatPlan>,
        files: Map<String, File>
    ) {
        val hashes = JSONObject()
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            for ((entryName, source) in files) {
                val bytes = source.readBytes()
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(bytes)
                zip.closeEntry()
                hashes.put(entryName, sha256Hex(bytes))
            }

            val chats = JSONArray()
            for (chat in chatsToWrite) {
                chats.put(
                    JSONObject()
                        .put("chat_id", chat.chatId)
                        .put("available", true)
                )
            }
            val manifest = JSONObject()
                .put("manifest_version", ChatSnapshotManifest.MANIFEST_VERSION)
                .put("complete", true)
                .put("chats", chats)
                .put("file_hashes", hashes)
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
