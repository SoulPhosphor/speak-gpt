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
import com.google.android.material.elevation.SurfaceColors

/**
 * The one place that colors a full-screen settings activity's window and
 * Widget.App.ActionBar header. Screens call this instead of copying the
 * SurfaceColors code, so moving these colors onto theme attributes later
 * is a change to this file only.
 */
object ScreenChrome {

    fun apply(activity: Activity, actionBar: View?, backButton: View?) {
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
    }
}
