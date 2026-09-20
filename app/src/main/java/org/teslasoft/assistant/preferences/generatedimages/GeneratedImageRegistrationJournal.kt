package org.teslasoft.assistant.preferences.generatedimages

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import org.teslasoft.assistant.preferences.SecurePrefs
import java.io.File

/**
 * Durable proof for generated-image files that may exist without a completed
 * catalog transaction. Recovery only touches the exact filenames recorded
 * here; filename shape or an empty catalog is never deletion authority.
 */
object GeneratedImageRegistrationJournal {
    private const val FILE = "generated_image_registration_journal"
    private const val KEY = "pending"

    data class Entry(
        val imageId: String,
        val assetFileName: String,
        val newFileCreated: Boolean
    )

    @Synchronized
    fun begin(context: Context, imageId: String, assetFileName: String): Boolean {
        if (!safe(imageId) || !safe(assetFileName)) return false
        val entries = read(context).filterNot { it.imageId == imageId }.toMutableList()
        entries.add(Entry(imageId, assetFileName, newFileCreated = false))
        return write(context, entries)
    }

    @Synchronized
    fun markFileReady(context: Context, imageId: String, newFileCreated: Boolean): Boolean {
        val entries = read(context).toMutableList()
        val index = entries.indexOfFirst { it.imageId == imageId }
        if (index < 0) return false
        entries[index] = entries[index].copy(newFileCreated = newFileCreated)
        return write(context, entries)
    }

    @Synchronized
    fun complete(context: Context, imageId: String): Boolean {
        val entries = read(context).filterNot { it.imageId == imageId }
        return write(context, entries)
    }

    @Synchronized
    fun recover(context: Context): GeneratedImageCatalogStorageState {
        if (SecurePrefs.isLockedName(FILE)) return GeneratedImageCatalogStorageState.UNAVAILABLE
        val app = context.applicationContext
        val imagesDir = app.getExternalFilesDir("images")
            ?: return GeneratedImageCatalogStorageState.UNAVAILABLE
        for (entry in read(app)) {
            val lookup = GeneratedImageCatalogStore.lookup(app, entry.imageId)
            if (lookup.state != GeneratedImageCatalogStorageState.AVAILABLE) return lookup.state

            val target = child(imagesDir, entry.assetFileName) ?: continue
            val temp = child(imagesDir, entry.assetFileName + ".catalogtmp") ?: continue
            if (lookup.record?.assetFileName == entry.assetFileName) {
                try { if (temp.exists()) temp.delete() } catch (_: Exception) { }
                complete(app, entry.imageId)
                continue
            }
            if (lookup.record != null || lookup.tombstoned) continue

            // The journal began before AtomicFileWriter touched either path.
            // A matching temp is therefore proven uncommitted. A final target
            // is removable only after the journal durably recorded that this
            // attempt created it and the authoritative catalog has no row.
            try { if (temp.exists()) temp.delete() } catch (_: Exception) { }
            if (entry.newFileCreated) {
                try { if (target.exists()) target.delete() } catch (_: Exception) { }
            }
            if (!target.exists()) complete(app, entry.imageId)
        }
        return GeneratedImageCatalogStorageState.AVAILABLE
    }

    internal fun entriesForTest(context: Context): List<Entry> = read(context)

    private fun read(context: Context): List<Entry> {
        if (SecurePrefs.isLockedName(FILE)) return emptyList()
        return try {
            val json = SecurePrefs.get(context, FILE).getString(KEY, "[]") ?: "[]"
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val imageId = item.optString("image_id")
                val asset = item.optString("asset_file_name")
                if (!safe(imageId) || !safe(asset)) null else Entry(
                    imageId,
                    asset,
                    item.optBoolean("new_file_created", false)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun write(context: Context, entries: List<Entry>): Boolean = try {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("image_id", entry.imageId)
                    .put("asset_file_name", entry.assetFileName)
                    .put("new_file_created", entry.newFileCreated)
            )
        }
        SecurePrefs.get(context, FILE).edit().putString(KEY, array.toString()).commit()
    } catch (_: Exception) {
        false
    }

    private fun safe(value: String): Boolean =
        value.isNotBlank() && !value.contains('/') && !value.contains('\\')

    private fun child(parent: File, name: String): File? =
        if (safe(name)) File(parent, name) else null
}
