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
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.GlobalPreferences
import org.teslasoft.assistant.preferences.profileimages.ProfileImageShape
import org.teslasoft.assistant.preferences.profileimages.ProfileShapeTransformation
import org.teslasoft.assistant.theme.ThemeManager

/**
 * Profile Image Properties (profile-images-plan.md ADDENDUM, Phase 6): the
 * hub reached from the main Settings screen row of the same name. Three
 * rows - Profile Images (the gallery, management mode), Default Images
 * (Global Default / Personal Default), and Default Shape.
 */
class ProfileImagePropertiesActivity : FragmentActivity() {

    private val shapeOptions = listOf(
        ProfileImageShape.FLOWER to R.string.shape_flower,
        ProfileImageShape.CIRCLE to R.string.shape_circle,
        ProfileImageShape.SQUARE to R.string.shape_square
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_profile_image_properties)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        findViewById<LinearLayout>(R.id.row_profile_images).setOnClickListener {
            startActivity(
                Intent(this, ProfileImagesActivity::class.java)
                    .putExtra(ProfileImagesActivity.EXTRA_MODE, ProfileImagesActivity.MODE_MANAGEMENT)
            )
        }

        findViewById<LinearLayout>(R.id.row_default_images).setOnClickListener {
            startActivity(Intent(this, DefaultImagesActivity::class.java))
        }

        findViewById<LinearLayout>(R.id.row_default_shape).setOnClickListener {
            showShapePicker()
        }
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

    /** Default Shape (plan: "a Material single-choice dialog... each choice
     *  includes a small visual preview"). Previews render live through the
     *  same ProfileShapeTransformation every Profile Image display uses, so
     *  what is shown here is exactly what the shape will look like. */
    private fun showShapePicker() {
        val view = layoutInflater.inflate(R.layout.dialog_default_shape, null)
        val container = view.findViewById<LinearLayout>(R.id.container_shape_options)
        val currentShape = ProfileImageShape.normalize(GlobalPreferences.getPreferences(this).getProfileImageShape())

        val dialog = MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.row_default_shape_title)
            .setView(view)
            .setNegativeButton(R.string.btn_cancel, null)
            .create()

        for ((shapeKey, labelRes) in shapeOptions) {
            val option = LayoutInflater.from(this).inflate(R.layout.view_shape_option, container, false)
            val preview = option.findViewById<ImageView>(R.id.option_preview)
            val badge = option.findViewById<View>(R.id.option_badge)
            val label = option.findViewById<TextView>(R.id.option_label)

            label.setText(labelRes)
            label.contentDescription = getString(labelRes)
            badge.visibility = if (shapeKey == currentShape) View.VISIBLE else View.GONE

            Glide.with(this)
                .load(R.drawable.bg_shape_preview_swatch)
                .apply(RequestOptions().transform(ProfileShapeTransformation(this, shapeKey)))
                .into(preview)

            option.setOnClickListener {
                GlobalPreferences.getPreferences(this).setProfileImageShape(shapeKey)
                dialog.dismiss()
            }
            container.addView(option)
        }

        dialog.show()
    }
}
