package org.teslasoft.assistant.preferences.backup.portable

import android.content.Context

object ChatRestorePreferences {
    private const val FILE_NAME = "portable_restore_options"
    private const val KEY_ALWAYS_USE_NEWER = "always_use_newer_chats"
    private const val KEY_KEEP_BOTH = "keep_both_when_older_has_more_messages"

    fun options(context: Context): ChatMergePlanner.Options {
        val preferences = preferences(context)
        return ChatMergePlanner.Options(
            alwaysUseNewer = preferences.getBoolean(KEY_ALWAYS_USE_NEWER, true),
            keepBothWhenOlderHasMore = preferences.getBoolean(KEY_KEEP_BOTH, false)
        )
    }

    fun setAlwaysUseNewer(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_ALWAYS_USE_NEWER, enabled).apply()
    }

    fun setKeepBoth(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_KEEP_BOTH, enabled).apply()
    }

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
}
