/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.chatnavigation

/** The one validation policy Add Folder and Rename Folder must both call. */
object FolderNamePolicy {
    sealed class Validation {
        data class Valid(val trimmedName: String) : Validation()
        data object Blank : Validation()
        data object Duplicate : Validation()
    }

    fun validate(
        proposedName: String,
        existingFolders: Collection<FolderRecord>,
        renamedFolderId: String? = null
    ): Validation {
        val trimmed = proposedName.trim()
        if (trimmed.isBlank()) return Validation.Blank
        if (existingFolders.any {
                it.id != renamedFolderId && it.name.trim().equals(trimmed, ignoreCase = true)
            }
        ) return Validation.Duplicate
        return Validation.Valid(trimmed)
    }
}
