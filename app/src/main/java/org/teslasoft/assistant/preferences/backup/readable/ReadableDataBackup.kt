/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.backup.readable

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.teslasoft.assistant.preferences.backup.companion.CompanionBackupExporter
import org.teslasoft.assistant.preferences.backup.portable.GeneratedImagePortableBackup
import org.teslasoft.assistant.preferences.backup.portable.LorebookPortableCodec
import org.teslasoft.assistant.preferences.backup.portable.ModelEndpointPortableBackup
import org.teslasoft.assistant.preferences.lorebook.LoreBookStore
import org.teslasoft.assistant.preferences.memory.MemoryPortableGroup
import org.teslasoft.assistant.preferences.memory.MemoryPortableRowFormat
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.profileimages.ProfileImageFileNaming
import org.teslasoft.assistant.preferences.profileimages.ProfileImageStore

/** App-independent export that extends the original readable-chat ZIP. It
 * contains logical JSON and original image files, never encrypted databases,
 * database keys, endpoint credentials, recovery secrets, or restore metadata. */
object ReadableDataBackup {
    enum class Category {
        CHATS,
        GENERATED_IMAGES,
        IDENTITIES,
        PROFILE_IMAGES,
        MODEL_SETTINGS,
        MEMORIES,
        MODEL_RULES,
        LOREBOOKS
    }

    sealed class Result {
        data class Ok(val fingerprints: Map<String, String>) : Result()
        data object NothingNew : Result()
        data object NothingToBackUp : Result()
        data object ChatsUnreadable : Result()
        data object Failed : Result()
    }

    fun build(
        context: Context,
        destination: File,
        allChats: Boolean,
        chatFormat: ReadableChatBackup.Format,
        categories: Set<Category>
    ): Result {
        if (categories.isEmpty()) return Result.NothingToBackUp
        val staging = File(destination.parentFile, destination.name + ".parts")
        if (staging.exists()) staging.deleteRecursively()
        if (!staging.mkdirs()) return Result.Failed
        val sources = ArrayList<Pair<String, File>>()
        var fingerprints = ReadableBackupState.getBaseline(context)
        var chatNothingNew = false
        var complete = false
        try {
            if (Category.CHATS in categories) {
                val chatZip = File(staging, "chats.zip")
                when (val result = ReadableChatBackup.build(context, chatZip, allChats, chatFormat)) {
                    is ReadableChatBackup.BuildResult.Ok -> {
                        fingerprints = result.fingerprints
                        collectZip(chatZip, "chats/", sources)
                    }
                    ReadableChatBackup.BuildResult.NothingNew -> chatNothingNew = true
                    ReadableChatBackup.BuildResult.NothingToBackUp -> Unit
                    ReadableChatBackup.BuildResult.ChatsUnreadable -> return Result.ChatsUnreadable
                    ReadableChatBackup.BuildResult.Failed -> return Result.Failed
                }
            }

            if (Category.IDENTITIES in categories) {
                val archive = File(staging, "identities.zip")
                when (CompanionBackupExporter.buildBackupZip(
                    context, archive, validateAssignedImages = true
                )) {
                    is CompanionBackupExporter.BuildResult.Ok -> collectZip(archive, "identities/", sources)
                    else -> return Result.Failed
                }
            }

            if (Category.GENERATED_IMAGES in categories) {
                when (val generated = GeneratedImagePortableBackup.buildArtifacts(context, staging)) {
                    is GeneratedImagePortableBackup.Result.Ok -> generated.artifacts.forEach {
                        sources.add(it.entryName to it.file)
                    }
                    GeneratedImagePortableBackup.Result.NothingToBackUp -> Unit
                    is GeneratedImagePortableBackup.Result.Failed -> return Result.Failed
                }
            }

            if (Category.PROFILE_IMAGES in categories) {
                val store = ProfileImageStore.getInstance(context.applicationContext)
                val records = store.listNewestFirst()
                if (records.isNotEmpty()) {
                    val catalog = File(staging, "profile-images.json")
                    catalog.writeText(JSONObject().put("images", JSONArray().apply {
                        records.forEach { put(JSONObject().put("hash", it.hash).put("created_at", it.createdAt)) }
                    }).toString(2), Charsets.UTF_8)
                    sources.add("profile_images/catalog.json" to catalog)
                    records.forEach { record ->
                        val image = store.imageFile(record.hash) ?: return Result.Failed
                        if (!image.isFile) return Result.Failed
                        sources.add("profile_images/assets/${ProfileImageFileNaming.permanentFileName(record.hash)}" to image)
                    }
                }
            }

            if (Category.MODEL_SETTINGS in categories) {
                val model = File(staging, "model-settings.json")
                if (ModelEndpointPortableBackup.write(context, model) is ModelEndpointPortableBackup.Result.Failed) {
                    return Result.Failed
                }
                sources.add("model_settings.json" to model)
            }

            if (MemoryStore.isProvisioned(context)) {
                val memory = MemoryStore.getInstance(context.applicationContext)
                if (Category.MEMORIES in categories) {
                    val file = File(staging, "memories.json")
                    file.writeText(MemoryPortableRowFormat.toJson(
                        MemoryPortableGroup.MEMORIES,
                        memory.exportPortableRows(MemoryPortableGroup.MEMORIES)
                    ), Charsets.UTF_8)
                    sources.add("memories.json" to file)
                }
                if (Category.MODEL_RULES in categories) {
                    val file = File(staging, "model-rules.json")
                    file.writeText(MemoryPortableRowFormat.toJson(
                        MemoryPortableGroup.MODEL_RULES,
                        memory.exportPortableRows(MemoryPortableGroup.MODEL_RULES)
                    ), Charsets.UTF_8)
                    sources.add("model_rules.json" to file)
                }
            }

            if (Category.LOREBOOKS in categories && LoreBookStore.isProvisioned(context)) {
                val file = File(staging, "lorebooks.json")
                file.writeText(
                    LorebookPortableCodec.toJson(LoreBookStore.getInstance(context).exportPortableData()),
                    Charsets.UTF_8
                )
                sources.add("lorebooks.json" to file)
            }

            if (sources.isEmpty()) {
                return if (chatNothingNew) Result.NothingNew else Result.NothingToBackUp
            }
            val expected = LinkedHashSet<String>()
            ZipOutputStream(destination.outputStream().buffered()).use { zip ->
                for ((name, source) in sources) {
                    if (!expected.add(name) || !source.isFile) return Result.Failed
                    zip.putNextEntry(ZipEntry(name))
                    source.inputStream().use { it.copyTo(zip, 64 * 1024) }
                    zip.closeEntry()
                }
            }
            if (!verify(destination, expected)) return Result.Failed
            complete = true
            return Result.Ok(fingerprints)
        } catch (_: Exception) {
            return Result.Failed
        } finally {
            staging.deleteRecursively()
            if (!complete) destination.delete()
        }
    }

    private fun collectZip(zipFile: File, prefix: String, out: MutableList<Pair<String, File>>) {
        ZipInputStream(FileInputStream(zipFile).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    val target = File(zipFile.parentFile, "part_${out.size}")
                    target.outputStream().use { zip.copyTo(it, 64 * 1024) }
                    out.add(prefix + entry.name to target)
                }
                zip.closeEntry()
            }
        }
    }

    private fun verify(file: File, expected: Set<String>): Boolean = try {
        val seen = HashSet<String>()
        ZipInputStream(FileInputStream(file).buffered()).use { zip ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory || !seen.add(entry.name)) return false
                while (zip.read(buffer) >= 0) Unit
                zip.closeEntry()
            }
        }
        seen == expected
    } catch (_: Exception) {
        false
    }
}
