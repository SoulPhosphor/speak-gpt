package org.teslasoft.assistant.preferences.backup.portable

import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Explicit codec shared by the encrypted folder store and portable chats. */
internal object ChatFolderPortableCodec {
    data class Folder(val id: String, val name: String, val pinned: Boolean)

    fun decodeStored(raw: String): List<Folder>? {
        return try {
            val root = JSONObject(raw)
            if (root.optInt("version", -1) != 1) return null
            decodeArray(root.optJSONArray("folders") ?: return null)
        } catch (_: Exception) {
            null
        }
    }

    fun decodeArtifact(array: JSONArray): List<Folder>? = try {
        decodeArray(array)
    } catch (_: Exception) {
        null
    }

    fun toArtifactJson(folder: Folder): JSONObject = JSONObject()
        .put("id", folder.id)
        .put("name", folder.name)
        .put("pinned", folder.pinned)

    fun encodeStored(folders: List<PortableChatRestorePlan.FolderPlan>): String = JSONObject()
        .put("version", 1)
        .put("folders", JSONArray().apply {
            folders.forEach { folder ->
                put(JSONObject()
                    .put("id", folder.id)
                    .put("name", folder.name)
                    .put("pinned", folder.pinned))
            }
        })
        .toString()

    private fun decodeArray(array: JSONArray): List<Folder>? {
        val folders = ArrayList<Folder>(array.length())
        for (index in 0 until array.length()) {
            val raw = array.optJSONObject(index) ?: return null
            val id = raw.optString("id", "")
            val name = raw.optString("name", "")
            if (!isCanonicalUuid(id) || name.isBlank() || name != name.trim()) return null
            folders.add(Folder(id, name, raw.optBoolean("pinned", false)))
        }
        val ids = folders.map { it.id }
        val names = folders.map { it.name.lowercase(Locale.ROOT) }
        if (ids.size != ids.toSet().size || names.size != names.toSet().size) return null
        return folders
    }

    private fun isCanonicalUuid(value: String): Boolean = try {
        UUID.fromString(value).toString().equals(value, ignoreCase = true)
    } catch (_: Exception) {
        false
    }
}
