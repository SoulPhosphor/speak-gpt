/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.preferences.backup.companion

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.teslasoft.assistant.preferences.PersonaPreferences
import org.teslasoft.assistant.preferences.dto.PersonaObject
import org.teslasoft.assistant.preferences.profileimages.ProfileImageFileNaming

/**
 * BR-07 regression: creating a backup must refuse rather than publish an
 * archive that silently omits a profile picture assigned to an identity, while
 * a restore-time snapshot (validateAssignedImages = false) stays tolerant of an
 * already-dangling reference.
 */
@RunWith(AndroidJUnit4::class)
class CompanionBackupExporterImageValidationInstrumentedTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var imagesDir: File

    @Before
    fun setUp() {
        context.getSharedPreferences("personas", Context.MODE_PRIVATE).edit().clear().commit()
        imagesDir = requireNotNull(context.getExternalFilesDir("profile_images"))
        imagesDir.mkdirs()
        imagesDir.listFiles()?.forEach { it.delete() }
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("personas", Context.MODE_PRIVATE).edit().clear().commit()
        imagesDir.listFiles()?.forEach { it.delete() }
    }

    @Test
    fun missingAssignedImageRefusesBackupCreationButNotRestoreSnapshot() {
        seedCompanionWithAvatar(HASH_A) // no file written for this hash
        val staged = File(context.cacheDir, "exporter-missing-${System.nanoTime()}.zip")

        assertTrue(
            CompanionBackupExporter.buildBackupZip(context, staged, validateAssignedImages = true)
                is CompanionBackupExporter.BuildResult.ProfileImageUnavailable
        )
        // A tolerant snapshot still succeeds, carrying the reference only.
        assertTrue(
            CompanionBackupExporter.buildBackupZip(context, staged, validateAssignedImages = false)
                is CompanionBackupExporter.BuildResult.Ok
        )
        staged.delete()
    }

    @Test
    fun hashMismatchedAssignedImageRefusesBackupCreation() {
        seedCompanionWithAvatar(HASH_A)
        // A file exists for the hash, but its bytes do not match it.
        File(imagesDir, ProfileImageFileNaming.permanentFileName(HASH_A)).writeBytes(jpeg(9))
        val staged = File(context.cacheDir, "exporter-mismatch-${System.nanoTime()}.zip")

        assertTrue(
            CompanionBackupExporter.buildBackupZip(context, staged, validateAssignedImages = true)
                is CompanionBackupExporter.BuildResult.ProfileImageUnavailable
        )
        staged.delete()
    }

    private fun seedCompanionWithAvatar(hash: String) {
        PersonaPreferences.getPersonaPreferences(context).setPersona(
            PersonaObject(label = "Test Companion", prompt = "hello", avatarRef = hash)
        )
    }

    private fun jpeg(marker: Int) = byteArrayOf(
        0xff.toByte(), 0xd8.toByte(), 0xff.toByte(),
        marker.toByte(), 0x01, 0x02,
        0xff.toByte(), 0xd9.toByte()
    )

    private companion object {
        // A syntactically valid 64-hex Profile Images hash whose bytes are not
        // present on the device (or, in the mismatch case, do not hash to it).
        val HASH_A: String = MessageDigest.getInstance("SHA-256")
            .digest(byteArrayOf(1, 2, 3, 4))
            .joinToString("") { "%02x".format(it) }
    }
}
