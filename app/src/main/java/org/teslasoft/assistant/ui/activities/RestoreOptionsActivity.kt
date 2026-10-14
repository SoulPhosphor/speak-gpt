package org.teslasoft.assistant.ui.activities

/** Dedicated category and database restore screen, retaining the existing coordinator. */
class RestoreOptionsActivity : MemoryBackupRestoreActivity() {
    override val isRestoreOptionsPage: Boolean = true
}
