/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.ui.activities

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.profileimages.GlobalDefaultImageSeeder
import org.teslasoft.assistant.theme.ThemeManager

/**
 * Default Images (profile-images-plan.md ADDENDUM, Phase 6): Global
 * Default (shared with Companions; auto-seeded, see
 * [GlobalDefaultImageSeeder]) and Personal Default (user-side only,
 * optional; falls through to the Global Default when unset). Both rows
 * open the same Profile Images gallery, in ordinary browsing (owner
 * ruling, July 19 2026 - see ProfileImagesActivity.EXTRA_DEFAULT_TARGET):
 * assignment happens from inside the gallery's Image Detail sheet (Set as
 * Default), not by tapping a tile here, so there is no result to wait for.
 * Plan item 4 approves only "a screen or row group where the user sets
 * the Global Default and the Personal Default separately" - no leading
 * preview thumbnail or Remove Picture row on this screen; do not add
 * either without asking first.
 */
class DefaultImagesActivity : FragmentActivity() {

    private val ioScope = CoroutineScope(Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_default_images)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        findViewById<LinearLayout>(R.id.row_global_default).setOnClickListener {
            startActivity(
                Intent(this, ProfileImagesActivity::class.java)
                    .putExtra(ProfileImagesActivity.EXTRA_MODE, ProfileImagesActivity.MODE_MANAGEMENT)
                    .putExtra(ProfileImagesActivity.EXTRA_DEFAULT_TARGET, ProfileImagesActivity.TARGET_GLOBAL)
            )
        }

        findViewById<LinearLayout>(R.id.row_personal_default).setOnClickListener {
            startActivity(
                Intent(this, ProfileImagesActivity::class.java)
                    .putExtra(ProfileImagesActivity.EXTRA_MODE, ProfileImagesActivity.MODE_MANAGEMENT)
                    .putExtra(ProfileImagesActivity.EXTRA_DEFAULT_TARGET, ProfileImagesActivity.TARGET_PERSONAL)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Ensures the Global Default is seeded so the gallery always has it
        // as a catalog entry - off the main thread, since seeding may write
        // a permanent file.
        ioScope.launch { GlobalDefaultImageSeeder.ensureSeeded(this@DefaultImagesActivity) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        adjustPaddings()
    }

    private fun adjustPaddings() {
        if (Build.VERSION.SDK_INT < 35) return
        try {
            findViewById<View>(R.id.action_bar)?.setPadding(
                0,
                window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.statusBars()).top,
                0,
                0
            )
            findViewById<ScrollView>(R.id.scroll)?.setPadding(
                0,
                0,
                0,
                window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom + pxToDp(24)
            )
        } catch (_: Exception) { /* unused */ }
    }

    private fun pxToDp(px: Int): Int {
        val density = resources.displayMetrics.density
        return (px * density).toInt()
    }

    override fun onDestroy() {
        ioScope.cancel()
        super.onDestroy()
    }
}
