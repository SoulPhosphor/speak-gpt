/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.memory

/**
 * What Delete does to a memory (memory plan Phase 2, D.11, D44).
 *
 * Archived is the out-of-use state: nothing archived is sent to the AI or
 * found by search, and an archived memory can be restored or deleted
 * permanently. Delete moves an in-use memory there while Delete Moves to
 * Archived is on. Everything else is removed permanently after the existing
 * confirmation: a memory that is already archived, a superseded version (it
 * stays behind the Superseded filter, owner ruling of August 3 2026), every
 * memory while the setting is off, and a Pending suggestion, which was never
 * a saved memory.
 */
enum class MemoryDeleteAction { MOVE_TO_ARCHIVED, CONFIRM_PERMANENT_DELETE }

object MemoryDeleteRules {
    fun forDelete(status: String, preferences: MemoryHandlingPreferences): MemoryDeleteAction =
        if (status == STATUS_ACTIVE && preferences.deleteMovesToArchived) {
            MemoryDeleteAction.MOVE_TO_ARCHIVED
        } else {
            MemoryDeleteAction.CONFIRM_PERMANENT_DELETE
        }

    private const val STATUS_ACTIVE = "active"
}
