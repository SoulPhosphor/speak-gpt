package org.teslasoft.assistant.preferences.backup.readable

import java.io.File
import java.security.MessageDigest

/** A stable candidate snapshot. Planning never changes the verified saved baseline. */
internal object ReadableFileBackupPlan {
    data class Plan(val files: List<Pair<String, File>>, val fingerprints: Map<String, String>)

    fun prepare(
        sources: List<Pair<String, File>>,
        staging: File,
        baseline: Map<String, String>,
        allFiles: Boolean
    ): Plan {
        require(sources.map { it.first }.distinct().size == sources.size)
        val next = baseline.toMutableMap()
        val selected = ArrayList<Pair<String, File>>()
        sources.forEachIndexed { index, (name, source) ->
            require(source.isFile)
            val snapshot = File(staging, "candidate_$index")
            source.copyTo(snapshot, overwrite = false)
            // Store hashed entry identities; chat titles and folder names never enter preferences.
            val key = digest(name.toByteArray(Charsets.UTF_8))
            val hash = snapshot.inputStream().use { input ->
                val md = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    md.update(buffer, 0, count)
                }
                hex(md.digest())
            }
            next[key] = hash
            if (allFiles || baseline[key] != hash) selected.add(name to snapshot)
        }
        return Plan(selected, next)
    }

    private fun digest(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
