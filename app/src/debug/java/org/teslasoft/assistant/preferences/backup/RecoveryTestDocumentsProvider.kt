package org.teslasoft.assistant.preferences.backup

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File

/** Minimal real DocumentsProvider used only by the Phase 12.9 SAF test. */
class RecoveryTestDocumentsProvider : DocumentsProvider() {
    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: ROOT_COLUMNS).apply {
            newRow()
                .add(Root.COLUMN_ROOT_ID, ROOT_ID)
                .add(Root.COLUMN_DOCUMENT_ID, ROOT_ID)
                .add(Root.COLUMN_TITLE, "Recovery test documents")
                .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE)
        }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { addDocumentRow(documentId) }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
        root().listFiles()?.sortedBy { it.name }?.forEach { addDocumentRow(it.name) }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor = ParcelFileDescriptor.open(
        document(documentId),
        ParcelFileDescriptor.parseMode(mode)
    )

    override fun renameDocument(documentId: String, displayName: String): String {
        val source = document(documentId)
        val destination = document(displayName)
        check(source.isFile && !destination.exists() && source.renameTo(destination))
        return displayName
    }

    private fun MatrixCursor.addDocumentRow(documentId: String) {
        val file = document(documentId)
        newRow()
            .add(Document.COLUMN_DOCUMENT_ID, documentId)
            .add(Document.COLUMN_DISPLAY_NAME, if (documentId == ROOT_ID) ROOT_ID else file.name)
            .add(Document.COLUMN_MIME_TYPE, if (documentId == ROOT_ID) {
                Document.MIME_TYPE_DIR
            } else "application/octet-stream")
            .add(Document.COLUMN_SIZE, if (file.isFile) file.length() else 0L)
            .add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_WRITE)
    }

    private fun root(): File = File(requireNotNull(context).filesDir, DIRECTORY).apply { mkdirs() }

    private fun document(documentId: String): File {
        require(documentId == ROOT_ID || SAFE_NAME.matches(documentId))
        return if (documentId == ROOT_ID) root() else File(root(), documentId)
    }

    companion object {
        const val AUTHORITY = "com.soulphosphor.phosphorshines.recovery.documents"
        const val ROOT_ID = "recovery-root"
        const val DIRECTORY = "recovery-provider"
        private val SAFE_NAME = Regex("[A-Za-z0-9._-]{1,160}")
        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_FLAGS
        )
        private val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_FLAGS
        )
    }
}

