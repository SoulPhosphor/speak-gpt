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
 * Why a chat was recorded as damaged — shown as "Reason" in the Damaged Chat
 * Files log (owner-approved wording, Oct 30 2026). Saved when the damage is
 * found; chats recorded before causes were saved have none and show
 * [R.string.damaged_reason_not_recorded].
 */
enum class DamagedChatCause(@StringRes val label: Int) {
    /** The encrypted file could not be decrypted. */
    CANNOT_UNLOCK(R.string.damaged_reason_cannot_unlock),
    /** The file decrypted, but its chat data could not be read. */
    CONTENTS_DAMAGED(R.string.damaged_reason_contents_damaged),
    /** A save was refused because the chat was already marked damaged; the
     *  original reason is not known to the operation that found it. */
    ALREADY_MARKED(R.string.damaged_reason_already_marked)
}
