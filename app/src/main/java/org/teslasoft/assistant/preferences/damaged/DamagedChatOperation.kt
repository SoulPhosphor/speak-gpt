/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.preferences.damaged

import androidx.annotation.StringRes
import org.teslasoft.assistant.R

/**
 * The operation that read a chat and found it damaged — shown as
 * "Detected During" in the Damaged Chat Files log (owner-approved labels,
 * Oct 12 2026). Every chat read names one, so the log is the single place
 * damaged conversations are recorded, whichever part of the app finds them.
 */
enum class DamagedChatOperation(@StringRes val label: Int) {
    RENAMING_USERNAMES(R.string.damaged_during_renaming_usernames),
    OPENING_A_CHAT(R.string.damaged_during_opening_a_chat),
    CHAT_LIST(R.string.damaged_during_chat_list),
    CHAT_PREVIEW(R.string.damaged_during_chat_preview),
    CONVERSATION_SUMMARY(R.string.damaged_during_conversation_summary),
    RECOVERY_BACKUP(R.string.damaged_during_recovery_backup),
    READABLE_BACKUP(R.string.damaged_during_readable_backup),
    CHAT_SEARCH(R.string.damaged_during_chat_search),
    MEMORY(R.string.damaged_during_memory),
    IMAGE_GALLERY(R.string.damaged_during_image_gallery),
    NEW_CHAT(R.string.damaged_during_new_chat)
}
