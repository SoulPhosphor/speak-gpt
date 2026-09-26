package org.teslasoft.assistant.preferences

import android.content.Context

/** Durable fence between the authoritative chat files and derived stores. */
object ChatSetSourceGeneration {
    private const val HEALTH_FILE = "storage_health"
    private const val KEY_SOURCE_GENERATION = "chatset.source_generation"

    fun current(context: Context): Long = preferences(context)
        .getLong(KEY_SOURCE_GENERATION, 0L)

    @Synchronized
    fun advance(context: Context): Long? {
        val next = current(context) + 1L
        return if (preferences(context).edit()
                .putLong(KEY_SOURCE_GENERATION, next)
                .commit()
        ) next else null
    }

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(HEALTH_FILE, Context.MODE_PRIVATE)
}
