package org.teslasoft.assistant.conversation.responsegroups

/** Snapshot references protect original bytes even if the source Include has since been reduced/removed. */
object ResponseGroupAttachments {
    /** Null means unknown ownership: cleanup must preserve the existing files. */
    fun retainedHashes(messages: List<Map<String, Any>>, store: String): Set<String>? {
        val result = LinkedHashSet<String>()
        messages.forEach { row ->
            when (val read = ResponseGroupCodec.read(row)) {
                ResponseGroupRead.Absent -> Unit
                is ResponseGroupRead.Valid -> read.group.input.parts.mapNotNull { it.attachment }
                    .filter { it.store == store }.forEach { result.add(it.fileName.substringBeforeLast('.')) }
                else -> return null
            }
        }
        return result
    }

    fun of(message: Map<String, Any>): List<ResponseAttachment> =
        (ResponseGroupCodec.read(message) as? ResponseGroupRead.Valid)?.group?.input?.parts
            ?.mapNotNull { it.attachment }.orEmpty()
}
