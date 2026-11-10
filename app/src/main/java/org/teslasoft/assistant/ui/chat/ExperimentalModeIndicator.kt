/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 *************************************************************************/

package org.teslasoft.assistant.ui.chat

import android.view.View
import org.teslasoft.assistant.preferences.Preferences

/**
 * The atom icon in the chat composer, right of the Quick Settings button.
 * It is shown only while this chat's Experimental Mode is on; tapping it
 * turns the mode off and hides the icon. It never touches the message box
 * or the keyboard.
 */
class ExperimentalModeIndicator(
    private val button: View,
    private val preferences: () -> Preferences?
) {
    init {
        button.setOnClickListener {
            preferences()?.setChatExperimentalMode(false)
            refresh()
        }
        refresh()
    }

    /** Matches the icon to the stored setting. */
    fun refresh() {
        button.visibility =
            if (preferences()?.getChatExperimentalMode() == true) View.VISIBLE else View.GONE
    }
}
