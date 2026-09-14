/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.preferences.backup.portable

/**
 * Versioned decoded-size policy for portable recovery artifacts.
 *
 * These are conservative implementation safety ceilings, not measured phone
 * limits. They remain provisional until the real-device validation in Phase
 * 12.10. Keeping the policy here makes a later evidence-based adjustment one
 * reviewable change instead of a hunt through individual readers.
 */
object PortableRecoveryLimits {
    const val POLICY_VERSION = 1

    const val CHATS_JSON_BYTES = 64L * 1024L * 1024L
    const val GENERATED_IMAGE_CATALOG_BYTES = 32L * 1024L * 1024L
    const val MODEL_ENDPOINT_SETTINGS_BYTES = 8L * 1024L * 1024L
    const val COMPANION_ROLEPLAY_ARCHIVE_BYTES = 512L * 1024L * 1024L
    const val DATABASE_BYTES = 512L * 1024L * 1024L
    const val IMAGE_ASSET_BYTES = 64L * 1024L * 1024L
    const val COMPLETE_PACKAGE_BYTES = 1L * 1024L * 1024L * 1024L

    fun maxDecodedBytes(entryName: String, type: String): Long? = when (type) {
        PortablePackage.TYPE_CHATS_JSON -> CHATS_JSON_BYTES
        PortablePackage.TYPE_GENERATED_IMAGES_CATALOG -> GENERATED_IMAGE_CATALOG_BYTES
        PortablePackage.TYPE_MODEL_ENDPOINT_SETTINGS -> MODEL_ENDPOINT_SETTINGS_BYTES
        PortablePackage.TYPE_COMPANION_ROLEPLAY_ARCHIVE -> COMPANION_ROLEPLAY_ARCHIVE_BYTES
        PortablePackage.TYPE_SQLCIPHER_DB,
        PortablePackage.TYPE_SQLITE_DB -> DATABASE_BYTES
        PortablePackage.TYPE_GENERATED_IMAGE_ASSET,
        PortablePackage.TYPE_PROFILE_IMAGE_ASSET -> IMAGE_ASSET_BYTES
        else -> null
    }

    fun accepts(entryName: String, type: String, decodedBytes: Long): Boolean {
        if (decodedBytes < 0L) return false
        val limit = maxDecodedBytes(entryName, type) ?: return false
        return decodedBytes <= limit
    }
}
