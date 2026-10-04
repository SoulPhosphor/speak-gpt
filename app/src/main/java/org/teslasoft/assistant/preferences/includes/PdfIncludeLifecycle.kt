/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.preferences.includes

/** Pure PDF state transitions; physical deletion happens only after persistence. */
object PdfIncludeLifecycle {
    fun afterCondenseAttempt(include: ChatInclude, condensedText: String?): ChatInclude {
        require(include.kind == IncludeKind.PDF)
        val result = condensedText?.trim().takeUnless { it.isNullOrBlank() } ?: return include
        return include.copy(
            form = IncludeForm.CONDENSED,
            condensedText = result
        ).withoutPdfBytes()
    }

    fun removePreviouslySent(include: ChatInclude, artifactLine: String): ChatInclude {
        require(include.kind == IncludeKind.PDF)
        return include.copy(
            form = IncludeForm.ARTIFACT,
            artifactLine = artifactLine,
            notice = IncludeNotice.None
        ).withoutPdfBytes()
    }
}
