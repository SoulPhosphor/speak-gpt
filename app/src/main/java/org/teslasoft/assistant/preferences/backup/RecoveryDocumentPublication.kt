/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.preferences.backup

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

/** SAF publication policy: bytes exist only under an unmistakably incomplete
 * name until their digest has been verified, then one provider rename publishes
 * the final recognized backup name. */
object RecoveryDocumentPublication {
    const val INCOMPLETE_SUFFIX = ".incomplete"

    fun incompleteName(finalName: String): String {
        require(finalName.isNotBlank() && !finalName.endsWith(INCOMPLETE_SUFFIX))
        return finalName + INCOMPLETE_SUFFIX
    }

    fun finalize(
        resolver: ContentResolver,
        incompleteDocument: Uri,
        finalName: String
    ): Uri? = try {
        DocumentsContract.renameDocument(resolver, incompleteDocument, finalName)
    } catch (_: Exception) {
        null
    }
}
