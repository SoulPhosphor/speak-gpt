/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.preferences.backup.portable

/** Pure bounded-retry policy used by backup capture and its JVM tests. */
internal object PortableBackupCaptureStability {
    const val MAX_ATTEMPTS = 2

    val CAPTURE_STEPS = listOf(
        "memory",
        "lorebooks",
        "profile_images",
        "generated_images",
        "identities",
        "model_endpoints",
        "chats"
    )

    enum class Decision { STABLE, RETRY, REFUSE, UNAVAILABLE }

    fun decide(
        before: Map<String, String>?,
        after: Map<String, String>?,
        attempt: Int
    ): Decision {
        if (before == null || after == null) return Decision.UNAVAILABLE
        if (before == after) return Decision.STABLE
        return if (attempt < MAX_ATTEMPTS) Decision.RETRY else Decision.REFUSE
    }
}
