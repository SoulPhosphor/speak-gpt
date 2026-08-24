/*
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.teslasoft.assistant.ui.chat

/**
 * Resolves which logical edge owns a message row.
 *
 * Logical start mirrors automatically in RTL. Assistant replies always use it;
 * user prompts use logical end only while staggered presentation is enabled.
 */
internal object ChatMessagePlacement {
    fun usesLogicalStart(isBot: Boolean, staggeredResponses: Boolean): Boolean =
        isBot || !staggeredResponses
}
