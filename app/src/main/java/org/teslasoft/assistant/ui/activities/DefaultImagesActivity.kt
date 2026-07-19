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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.GlobalPreferences
import org.teslasoft.assistant.preferences.profileimages.GlobalDefaultImageSeeder
import org.teslasoft.assistant.theme.ThemeManager

/**
 * Default Images (profile-images-plan.md ADDENDUM, Phase 6): Global
 * Default (shared with Companions; auto-seeded, see
 * [GlobalDefaultImageSeeder]) and Personal Default (user-side only,
 * optional; falls through to the Global Default when unset). Both are
 * assigned through the same Profile Images gallery, in assignment mode.
 * Plan item 4 approves only "a screen or row group where the user sets
 * the Global Default and the Personal Default separately" - no leading
 * preview thumbnail or Remove Picture row on this screen; do not add
 * either without asking first.
 */
class DefaultImagesActivity : FragmentActivity() {

    companion object {
        private const val STATE_PENDING_ASSIGNMENT = "pending_assignment"
    }

    private val ioScope = CoroutineScope(Dispatchers.IO)

    private var pendingAssignment: Target? = null

    private enum class Target { GLOBAL, PERSONAL }

    private val assignmentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val target = pendingAssignment
            pendingAssignment = null
            if (result.resultCode != RESULT_OK || target == null) return@registerForActivityResult
            val hash = result.data?.getStringExtra(ProfileImagesActivity.EXTRA_RESULT_HASH) ?: return@registerForActivityResult
            val preferences = GlobalPreferences.getPreferences(this)
            when (target) {
                Target.GLOBAL -> preferences.setGlobalDefaultImageRef(hash)
                Target.PERSONAL -> preferences.setDefaultUserImageRef(hash)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_default_images)

        // Survives rotation while the assignment gallery is on top - a
        // plain field alone would silently drop the result (the plan calls
        // out exactly this class of bug for image-assignment flows).
        pendingAssignment = savedInstanceState?.getString(STATE_PENDING_ASSIGNMENT)?.let {
            runCatching { Target.valueOf(it) }.getOrNull()
        }

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        findViewById<LinearLayout>(R.id.row_global_default).setOnClickListener {
            launchAssignment(Target.GLOBAL)
        }

        findViewById<LinearLayout>(R.id.row_personal_default).setOnClickListener {
            launchAssignment(Target.PERSONAL)
        }
    }

    override fun onResume() {
        super.onResume()
        // Ensures the Global Default is seeded so the assignment gallery
        // always has it as a catalog entry - off the main thread, since
        // seeding may write a permanent file.
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingAssignment?.let { outState.putString(STATE_PENDING_ASSIGNMENT, it.name) }
    }

    private fun launchAssignment(target: Target) {
        pendingAssignment = target
        assignmentLauncher.launch(
            Intent(this, ProfileImagesActivity::class.java)
                .putExtra(ProfileImagesActivity.EXTRA_MODE, ProfileImagesActivity.MODE_ASSIGNMENT)
        )
    }

}
