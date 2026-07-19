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
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.GlobalPreferences
import org.teslasoft.assistant.preferences.profileimages.GlobalDefaultImageSeeder
import org.teslasoft.assistant.preferences.profileimages.ProfileImageStore
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.util.ProfileImageBinder

/**
 * Default Images (profile-images-plan.md ADDENDUM, Phase 6): Global
 * Default (shared with Companions; auto-seeded, see
 * [GlobalDefaultImageSeeder]) and Personal Default (user-side only,
 * optional; falls through to the Global Default when unset). Both are
 * assigned through the same Profile Images gallery, in assignment mode.
 */
class DefaultImagesActivity : FragmentActivity() {

    companion object {
        private const val STATE_PENDING_ASSIGNMENT = "pending_assignment"
    }

    private var imgGlobalPreview: ImageView? = null
    private var imgPersonalPreview: ImageView? = null
    private var rowGlobalRemove: LinearLayout? = null
    private var rowPersonalRemove: LinearLayout? = null

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
            refresh()
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

        imgGlobalPreview = findViewById(R.id.img_global_default_preview)
        imgPersonalPreview = findViewById(R.id.img_personal_default_preview)
        rowGlobalRemove = findViewById(R.id.row_global_default_remove)
        rowPersonalRemove = findViewById(R.id.row_personal_default_remove)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        findViewById<LinearLayout>(R.id.row_global_default).setOnClickListener {
            launchAssignment(Target.GLOBAL)
        }
        rowGlobalRemove?.setOnClickListener {
            GlobalPreferences.getPreferences(this).setGlobalDefaultImageRef("")
            refresh()
        }

        findViewById<LinearLayout>(R.id.row_personal_default).setOnClickListener {
            launchAssignment(Target.PERSONAL)
        }
        rowPersonalRemove?.setOnClickListener {
            GlobalPreferences.getPreferences(this).setDefaultUserImageRef("")
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
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

    /** Ensures the Global Default is seeded, then binds both previews from
     *  live preferences - off the main thread, since seeding may write a
     *  permanent file and both lookups touch ProfileImageStore. */
    private fun refresh() {
        ioScope.launch {
            val globalHash = GlobalDefaultImageSeeder.ensureSeeded(this@DefaultImagesActivity)
            val preferences = GlobalPreferences.getPreferences(this@DefaultImagesActivity)
            val personalHash = preferences.getDefaultUserImageRef()
            val store = ProfileImageStore.getInstance(this@DefaultImagesActivity)
            val globalFile = if (globalHash.isNotEmpty()) store.imageFile(globalHash) else null
            val personalFile = if (personalHash.isNotEmpty()) store.imageFile(personalHash) else null
            val shape = preferences.getProfileImageShape()

            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext

                imgGlobalPreview?.let { iv ->
                    ProfileImageBinder.bind(this@DefaultImagesActivity, iv, globalFile, shape) { fallback ->
                        fallback.setImageResource(R.drawable.ic_user)
                    }
                }
                rowGlobalRemove?.visibility = if (globalFile != null) View.VISIBLE else View.GONE

                imgPersonalPreview?.let { iv ->
                    ProfileImageBinder.bind(this@DefaultImagesActivity, iv, personalFile, shape) { fallback ->
                        fallback.setImageResource(R.drawable.ic_user)
                    }
                }
                rowPersonalRemove?.visibility = if (personalFile != null) View.VISIBLE else View.GONE
            }
        }
    }
}
