/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.ui.activities.memory

/**
 * Which memories each Memory Browser view lists (memory plan Phase 2, D.11).
 *
 * - **Memories** lists the memories in use. Superseded versions stay here
 *   behind the Superseded Memories filter (owner ruling, August 3 2026):
 *   Hide shows none, Include adds them, Only shows just them.
 * - **Pending** lists the Memory Assistant's suggestions (drafts).
 * - **Archived** lists every memory taken out of use: archived from the row
 *   menu, or deleted while Delete Moves to Archived is on. Nothing in it is
 *   sent to the AI or found by search.
 */
object MemoryBrowserViews {
    const val MEMORIES = "memories"
    const val PENDING = "pending"
    const val ARCHIVED = "archived"

    /** The memory status each entry point writes to open a view directly. */
    fun statusFor(view: String): String = when (view) {
        PENDING -> STATUS_DRAFT
        ARCHIVED -> STATUS_ARCHIVED
        else -> STATUS_ACTIVE
    }

    /** The view an entry point's status asks for; anything else opens Memories. */
    fun viewFor(statuses: Set<String>): String = when (statuses) {
        setOf(STATUS_DRAFT) -> PENDING
        setOf(STATUS_ARCHIVED) -> ARCHIVED
        else -> MEMORIES
    }

    /** Whether a memory with [status] is listed in [view]. [superseded] is the
     *  Superseded Memories filter: "hide", "include" or "only". */
    fun lists(view: String, status: String, superseded: String): Boolean = when (view) {
        PENDING -> status == STATUS_DRAFT
        ARCHIVED -> status == STATUS_ARCHIVED
        else -> when (superseded) {
            "only" -> status == STATUS_SUPERSEDED
            "include" -> status == STATUS_ACTIVE || status == STATUS_SUPERSEDED
            else -> status == STATUS_ACTIVE
        }
    }

    private const val STATUS_ACTIVE = "active"
    private const val STATUS_DRAFT = "draft"
    private const val STATUS_ARCHIVED = "archived"
    private const val STATUS_SUPERSEDED = "superseded"
}
