package org.teslasoft.assistant.util

/** The chat-wide X must be repainted after success even if the photo cache is unchanged. */
class CompanionAvatarRecovery {
    private var errorAvatarBound = false

    fun onErrorAvatarBound() {
        errorAvatarBound = true
    }

    fun takeRecovery(chatShowsErrorAvatar: Boolean): Boolean {
        if (chatShowsErrorAvatar || !errorAvatarBound) return false
        errorAvatarBound = false
        return true
    }
}
