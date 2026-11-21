package org.teslasoft.assistant.preferences.backup.portable

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.json.JSONArray
import org.json.JSONObject
import org.teslasoft.assistant.conversation.responsegroups.ResponseAttachment
import org.teslasoft.assistant.conversation.responsegroups.ResponseGroupAttachments
import org.teslasoft.assistant.conversation.responsegroups.ResponseGroupCodec
import org.teslasoft.assistant.conversation.responsegroups.ResponseGroupRead
import org.teslasoft.assistant.preferences.includes.ChatInclude
import org.teslasoft.assistant.preferences.includes.IncludeForm
import org.teslasoft.assistant.preferences.includes.IncludeKind
import org.teslasoft.assistant.preferences.includes.ImageImporter
import java.io.File
import java.security.MessageDigest

/** Snapshot PDFs reuse the existing PDF asset contract; snapshot images use their existing image store. */
object ResponseGroupAttachmentBackup {
    internal class UnreadableMetadata : IllegalArgumentException()

    internal fun attachments(message: JSONObject): List<ResponseAttachment> {
        if (!message.has("responseGroup")) return emptyList()
        val row: Map<String, Any> = Gson().fromJson(message.toString(), object : TypeToken<Map<String, Any>>() {}.type)
        if (ResponseGroupCodec.read(row) !is ResponseGroupRead.Valid) throw UnreadableMetadata()
        return ResponseGroupAttachments.of(row)
    }

    fun pdfIncludes(message: JSONObject): List<ChatInclude> = attachments(message).filter { it.store == "pdf" }.map {
        ChatInclude(id = it.sha256, fileName = it.fileName, kind = IncludeKind.PDF, form = IncludeForm.FULL,
            fullText = "", pdfFileHash = it.sha256, pdfMimeType = it.mimeType, pdfByteSize = it.sizeBytes)
    }

    fun imageRefs(plan: PortableChatRestorePlan.Plan): List<Pair<String, ResponseAttachment>> = plan.chats.flatMap { chat ->
        val rows = JSONArray(chat.messagesJson)
        (0 until rows.length()).flatMap { i ->
            rows.optJSONObject(i)?.let(::attachments).orEmpty().filter { it.store == "image" }.map { chat.chatId to it }
        }
    }

    internal fun imageArtifacts(context: Context, refs: List<ChatsArtifactScanner.SnapshotImageRef>): List<PortablePackage.Artifact> =
        refs.distinctBy { it.chatId to it.attachment.fileName }.map { (chat, ref) ->
            val file = File(ImageImporter.chatImagesDir(context, chat), ref.fileName)
            require(matches(file, ref)) { "snapshot_image_unavailable" }
            PortablePackage.Artifact("response_group_images/${safe(chat)}/${ref.fileName}", TYPE_IMAGE,
                file, null, null, 1)
        }

    fun prepareImages(artifacts: List<PortablePackage.ValidatedArtifact>): Map<String, File>? {
        val out = LinkedHashMap<String, File>()
        for (artifact in artifacts.filter { it.type == TYPE_IMAGE }) {
            val name = artifact.entryName.substringAfterLast('/')
            if (digest(artifact.stagedFile) != name.substringBeforeLast('.')) return null
            out.putIfAbsent(name, artifact.stagedFile)
        }
        return out
    }

    /** Content-addressed copies: no original bytes are removed, including on rollback or restore-as-copy. */
    fun stageImages(context: Context, desired: PortableChatRestorePlan.Plan, current: PortableChatRestorePlan.Plan,
                    supplied: Map<String, File>, staging: File): Boolean = try {
        val local = imageRefs(current).associate { (chat, ref) ->
            ref.fileName to File(ImageImporter.chatImagesDir(context, chat), ref.fileName)
        }
        imageRefs(desired).distinctBy { it.first to it.second.fileName }.forEach { (chat, ref) ->
            val file = supplied[ref.fileName] ?: local[ref.fileName] ?: error("snapshot_image_missing")
            require(matches(file, ref)) { "snapshot_image_identity" }
            val destination = File(File(staging, safe(chat)).apply { mkdirs() }, ref.fileName)
            file.copyTo(destination, overwrite = false)
        }
        true
    } catch (_: Exception) { false }

    fun installImages(context: Context, staging: File): Boolean = try {
        staging.listFiles().orEmpty().filter(File::isDirectory).forEach { chat ->
            chat.listFiles().orEmpty().forEach { file ->
                val destination = File(ImageImporter.chatImagesDir(context, chat.name), file.name)
                if (destination.exists()) require(digest(destination) == digest(file))
                else {
                    val temporary = File.createTempFile("snapshot-", ".tmp", destination.parentFile)
                    try {
                        file.copyTo(temporary, overwrite = true)
                        require(digest(temporary) == digest(file))
                        require(temporary.renameTo(destination))
                    } finally { temporary.delete() }
                }
            }
        }
        true
    } catch (_: Exception) { false }

    fun matches(file: File, ref: ResponseAttachment): Boolean = file.isFile && file.length() == ref.sizeBytes && digest(file) == ref.sha256
    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun safe(chat: String) = chat.replace(Regex("[^A-Za-z0-9_-]"), "_")
    const val TYPE_IMAGE = "response-group-image-asset"
}
