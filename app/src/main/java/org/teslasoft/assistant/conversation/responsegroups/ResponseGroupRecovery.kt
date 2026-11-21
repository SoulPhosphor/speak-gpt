package org.teslasoft.assistant.conversation.responsegroups

/** Reconciles a detached load snapshot. The caller persists once through the guarded history boundary. */
object ResponseGroupRecovery {
    fun reconcile(message: HashMap<String, Any>): Boolean {
        val before = (ResponseGroupCodec.read(message) as? ResponseGroupRead.Valid)?.group ?: return false
        val after = ResponseGroupEdits.interrupted(before)
        if (before == after) return false
        val replacement = ResponseGroupEdits.install(message, after)
        message.clear()
        message.putAll(replacement)
        return true
    }
}
