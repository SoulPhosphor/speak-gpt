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

import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.stt.LocalWhisperModels
import org.teslasoft.assistant.stt.LocalWhisperStorage

/**
 * Lists installed on-device Whisper models with a Delete button per row.
 * The active model can't be deleted in place — its row says
 * "Active — pick another to delete" so the user has to switch before freeing
 * up the disk.
 */
class LocalWhisperManageActivity : FragmentActivity() {

    private var preferences: Preferences? = null
    private var root: ConstraintLayout? = null
    private var btnBack: ImageButton? = null
    private var emptyLabel: TextView? = null
    private var totalLabel: TextView? = null
    private var container: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_local_whisper_manage)

        preferences = Preferences.getPreferences(this, "")

        root = findViewById(R.id.root)
        btnBack = findViewById(R.id.btn_back)
        emptyLabel = findViewById(R.id.empty_label)
        totalLabel = findViewById(R.id.total_label)
        container = findViewById(R.id.manage_container)

        btnBack?.setOnClickListener { finish() }

        refresh()
        reloadAmoled()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        reloadAmoled()
    }

    private fun refresh() {
        val installed = LocalWhisperStorage.installedModels(this)
        container?.removeAllViews()

        if (installed.isEmpty()) {
            emptyLabel?.visibility = View.VISIBLE
            totalLabel?.visibility = View.GONE
            return
        }

        emptyLabel?.visibility = View.GONE
        totalLabel?.visibility = View.VISIBLE

        val inflater = LayoutInflater.from(this)
        val activeId = preferences?.getActiveLocalWhisperModel() ?: ""

        for (model in installed) {
            val row = inflater.inflate(R.layout.item_local_whisper_manage, container, false)
            val nameView: TextView = row.findViewById(R.id.manage_model_name)
            val sizeView: TextView = row.findViewById(R.id.manage_model_size)
            val deleteBtn: MaterialButton = row.findViewById(R.id.btn_delete_model)

            nameView.text = model.displayName
            sizeView.text = getString(R.string.local_whisper_size_mb_fmt, model.sizeMb)

            if (model.id == activeId) {
                deleteBtn.isEnabled = false
                deleteBtn.text = getString(R.string.local_whisper_active_cannot_delete)
            } else {
                deleteBtn.isEnabled = true
                deleteBtn.text = getString(R.string.btn_delete)
                deleteBtn.setOnClickListener { confirmDelete(model) }
            }

            container?.addView(row)
        }

        val totalMb = (LocalWhisperStorage.totalUsedBytes(this) / 1_000_000).toInt()
        totalLabel?.text = getString(R.string.local_whisper_storage_total_fmt, totalMb)
    }

    private fun confirmDelete(model: LocalWhisperModels.Model) {
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.local_whisper_delete_confirm_title)
            .setMessage(getString(R.string.local_whisper_delete_confirm_msg_fmt, model.displayName))
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                LocalWhisperStorage.delete(this, model)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .show()
    }

    private fun reloadAmoled() {
        try {
            if (isDarkThemeEnabled() && preferences?.getAmoledPitchBlack() == true) {
                window.setBackgroundDrawableResource(R.color.amoled_window_background)
                root?.setBackgroundColor(ResourcesCompat.getColor(resources, R.color.amoled_window_background, theme))
                btnBack?.setBackgroundResource(R.drawable.btn_accent_icon_large_amoled)
            } else {
                window.setBackgroundDrawableResource(R.color.window_background)
                root?.setBackgroundColor(SurfaceColors.SURFACE_0.getColor(this))
                btnBack?.background = getDisabledDrawable(ResourcesCompat.getDrawable(resources, R.drawable.btn_accent_icon_large, theme)!!)
            }
        } catch (_: Exception) {
            window.setBackgroundDrawableResource(R.color.window_background)
            root?.setBackgroundColor(SurfaceColors.SURFACE_0.getColor(this))
        }
    }

    private fun isDarkThemeEnabled(): Boolean = when (resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) {
        Configuration.UI_MODE_NIGHT_YES -> true
        else -> false
    }

    private fun getDisabledDrawable(drawable: Drawable): Drawable {
        DrawableCompat.setTint(DrawableCompat.wrap(drawable), getDisabledColor())
        return drawable
    }

    private fun getDisabledColor(): Int =
        if (isDarkThemeEnabled() && preferences?.getAmoledPitchBlack() == true) {
            ResourcesCompat.getColor(resources, R.color.accent_50, theme)
        } else {
            SurfaceColors.SURFACE_5.getColor(this)
        }
}
