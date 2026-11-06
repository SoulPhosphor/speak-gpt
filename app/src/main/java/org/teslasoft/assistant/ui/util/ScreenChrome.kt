/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 **************************************************************************/

package org.teslasoft.assistant.ui.util

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Build
import android.view.View
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.elevation.SurfaceColors
import org.teslasoft.assistant.R

/**
 * The one place that colors a full-screen settings activity's window and
 * Widget.App.ActionBar header. Screens call this instead of copying the
 * SurfaceColors code, so moving these colors onto theme attributes later
 * is a change to this file only.
 */
object ScreenChrome {
    private val HEADER_SAVE_IDS = setOf(
        R.id.btn_save, R.id.btn_header_save, R.id.btn_rule_save,
        R.id.btn_world_save, R.id.btn_campaign_save, R.id.btn_card_save
    )

    /** Trailing icons share the header surface, except save discs whose
     *  neutral background is owned by Widget.App.ActionBar.SaveButton. */
    fun apply(activity: Activity, actionBar: View?, backButton: View?, vararg headerButtons: View?) {
        val windowColor = SurfaceColors.SURFACE_0.getColor(activity)
        val headerColor = SurfaceColors.SURFACE_4.getColor(activity)
        activity.window.setBackgroundDrawable(windowColor.toDrawable())
        if (Build.VERSION.SDK_INT <= 34) {
            @Suppress("DEPRECATION")
            activity.window.navigationBarColor = windowColor
            @Suppress("DEPRECATION")
            activity.window.statusBarColor = headerColor
        }
        actionBar?.setBackgroundColor(headerColor)
        backButton?.backgroundTintList = ColorStateList.valueOf(headerColor)
        headerButtons.forEach { button ->
            if (button != null && button.id !in HEADER_SAVE_IDS) {
                button.backgroundTintList = ColorStateList.valueOf(headerColor)
            }
        }
    }

    /**
     * Keeps a full-screen page's Widget.App.ActionBar header joined to the top
     * edge of the screen. Android 15 and later draw the app behind the system
     * bars; this extends the header's own background up behind the status bar
     * with its contents below it, ends [content] above the navigation bar, and
     * keeps [root] clear of side insets and cutouts. Earlier versions already
     * place the page between the system bars, so nothing is added there.
     */
    fun fitSystemBars(root: View?, header: View?, content: View?) {
        if (Build.VERSION.SDK_INT < 35 || root == null) return
        val headerTop = header?.paddingTop ?: 0
        val contentBottom = content?.paddingBottom ?: 0
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(left = bars.left, right = bars.right)
            header?.updatePadding(top = headerTop + bars.top)
            content?.updatePadding(bottom = contentBottom + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    /**
     * [fitSystemBars] for a page the user types into. While the keyboard is
     * open the page ends at the keyboard's top edge, the same max(navigation
     * bar, keyboard) measure the chat composer uses, so everything in the
     * page can still be scrolled into view above the keyboard. The page keeps
     * its scroll position when the keyboard opens or closes; only a focused
     * cursor that would end up behind the keyboard is brought into view.
     */
    fun fitSystemBarsAndKeyboard(root: View?, header: View?, content: View?) {
        if (Build.VERSION.SDK_INT < 35 || root == null) return
        val headerTop = header?.paddingTop ?: 0
        val contentBottom = content?.paddingBottom ?: 0
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val keyboardOpen = keyboard > bars.bottom
            // An open keyboard shortens the page itself, so the scrolling
            // area ends above it; closed, the content runs behind the
            // navigation bar as on every other page.
            view.updatePadding(
                left = bars.left,
                right = bars.right,
                bottom = if (keyboardOpen) keyboard else 0
            )
            header?.updatePadding(top = headerTop + bars.top)
            content?.updatePadding(bottom = contentBottom + if (keyboardOpen) 0 else bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }
}
