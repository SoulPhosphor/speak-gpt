/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *************************************************************************/
package org.teslasoft.assistant.service

/**
 * Pure lifetime rules for the Activity-owned hands-free controller.
 *
 * HandsFreeService protects process/mic/wake-lock lifetime, but ChatActivity
 * still owns the recognizer, TTS objects and generation coroutines. Android is
 * therefore allowed to destroy the window without that being a request to end
 * the conversation. Only the registered session owner may survive that destroy,
 * and an explicit stop always wins.
 */
internal object HandsFreeActivityLifetimePolicy {
    fun shouldPreserveOnDestroy(
        isSessionOwner: Boolean,
        handsFreeEnabled: Boolean,
        loopStopped: Boolean
    ): Boolean = isSessionOwner && handsFreeEnabled && !loopStopped

    fun controllerCanRun(
        activityDestroyed: Boolean,
        isSessionOwner: Boolean,
        loopStopped: Boolean,
        cancelled: Boolean
    ): Boolean = !loopStopped && !cancelled && (!activityDestroyed || isSessionOwner)
}
