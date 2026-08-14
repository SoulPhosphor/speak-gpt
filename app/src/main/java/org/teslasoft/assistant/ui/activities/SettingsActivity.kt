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
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.transition.TransitionInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentTransaction
import androidx.interpolator.view.animation.FastOutLinearInInterpolator
import androidx.interpolator.view.animation.LinearOutSlowInInterpolator
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.GlobalPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.ui.fragments.TileFragment
import org.teslasoft.assistant.util.WindowInsetsUtil
import java.util.EnumSet
import kotlin.math.roundToInt
import androidx.core.view.WindowCompat
import org.teslasoft.assistant.theme.ThemeManager

class SettingsActivity : FragmentActivity() {

    // Plain rows (not TileFragment) -- row-style conversion review slice, July 18 2026.
    private var rowCharacters: LinearLayout? = null
    private var rowAiSystemSettings: LinearLayout? = null
    private var rowMemorySystem: LinearLayout? = null
    private var rowRoleplay: LinearLayout? = null
    private var rowProfileImageProperties: LinearLayout? = null
    private var rowVoiceSettings: LinearLayout? = null
    private var rowImages: LinearLayout? = null
    private var rowAppearance: LinearLayout? = null
    private var rowAboutApp: LinearLayout? = null
    private var tileDocumentation: TileFragment? = null
    private var rowAlertDebugMenu: LinearLayout? = null
    // private var threadLoading: LinearLayout? = null
    private var root: ScrollView? = null
    private var btnBack: ImageButton? = null
    private var actionBar: ConstraintLayout? = null

    private var areFragmentsInitialized = false
    private var chatId = ""
    private var preferences: Preferences? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= 30) {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
            )
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)

        val transition = TransitionInflater.from(this).inflateTransition(android.R.transition.move).apply {
            interpolator = LinearOutSlowInInterpolator()
            duration = 300
        }

        transition.excludeTarget(R.id.scrollable, true)
        transition.excludeTarget(R.id.action_bar, true)
        transition.excludeTarget(R.id.constraintLayout8, true)
        transition.excludeTarget(R.id.tile_images, true)
        transition.excludeTarget(R.id.tile_voice_settings, true)
        transition.excludeTarget(R.id.constraintLayout14, true)
        transition.excludeTarget(R.id.constraintLayout16, true)
        transition.excludeTarget(R.id.activity_new_settings_title, true)
        transition.excludeTarget(R.id.btn_back, true)
        transition.excludeTarget(R.id.tile_characters, true)
        transition.excludeTarget(R.id.tile_ai_system_settings, true)
        transition.excludeTarget(R.id.tile_memory_system, true)
        transition.excludeTarget(R.id.tile_roleplay, true)
        transition.excludeTarget(R.id.tile_profile_image_properties, true)
        transition.excludeTarget(R.id.switch_auto_send, true)
        transition.excludeTarget(R.id.tile_voice, true)
        transition.excludeTarget(R.id.row_voice_language, true)
        transition.excludeTarget(R.id.tile_tts, true)
        transition.excludeTarget(R.id.tile_stt, true)
        transition.excludeTarget(R.id.switch_always_speak, true)
        transition.excludeTarget(R.id.switch_auto_lang_detect, true)
        transition.excludeTarget(R.id.tile_appearance, true)
        transition.excludeTarget(R.id.tile_about_app, true)
        transition.excludeTarget(R.id.tile_documentation, true)
        transition.excludeTarget(R.id.tile_alert_debug_menu, true)

        val transition2 = TransitionInflater.from(this).inflateTransition(android.R.transition.move).apply {
            interpolator = FastOutLinearInInterpolator()
            duration = 200
        }

        transition2.excludeTarget(R.id.scrollable, true)
        transition2.excludeTarget(R.id.action_bar, true)
        transition2.excludeTarget(R.id.constraintLayout8, true)
        transition2.excludeTarget(R.id.tile_images, true)
        transition2.excludeTarget(R.id.tile_voice_settings, true)
        transition2.excludeTarget(R.id.constraintLayout14, true)
        transition2.excludeTarget(R.id.constraintLayout16, true)
        transition2.excludeTarget(R.id.tile_characters, true)
        transition2.excludeTarget(R.id.tile_ai_system_settings, true)
        transition2.excludeTarget(R.id.tile_memory_system, true)
        transition2.excludeTarget(R.id.tile_roleplay, true)
        transition2.excludeTarget(R.id.tile_profile_image_properties, true)
        transition2.excludeTarget(R.id.switch_auto_send, true)
        transition2.excludeTarget(R.id.tile_voice, true)
        transition2.excludeTarget(R.id.row_voice_language, true)
        transition2.excludeTarget(R.id.tile_tts, true)
        transition2.excludeTarget(R.id.tile_stt, true)
        transition2.excludeTarget(R.id.switch_always_speak, true)
        transition2.excludeTarget(R.id.switch_auto_lang_detect, true)
        transition2.excludeTarget(R.id.tile_appearance, true)
        transition2.excludeTarget(R.id.tile_about_app, true)
        transition2.excludeTarget(R.id.tile_documentation, true)
        transition2.excludeTarget(R.id.tile_alert_debug_menu, true)

        // Set the transition as the shared element enter transition
        window.sharedElementEnterTransition = transition
        window.sharedElementExitTransition = transition2

        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_settings)

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) {
                finishActivity()
            }
        }

        val expandableWindow = findViewById<LinearLayout>(R.id.expandable_window)

        if (isDarkThemeEnabled() && GlobalPreferences.getPreferences(this).getAmoledPitchBlack()) {
            expandableWindow?.backgroundTintList = ColorStateList.valueOf(getColor(R.color.amoled_window_background))
        } else {
            expandableWindow?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_1.getColor(this))
        }

        btnBack = findViewById(R.id.btn_back)
        actionBar = findViewById(R.id.action_bar)
        root = findViewById(R.id.root)

        val extras: Bundle? = intent.extras

        if (extras != null) {
            chatId = extras.getString("chatId", "")
        }

        preferences = Preferences.getPreferences(this, chatId)

        reloadAmoled()

        val t1 = Thread {
            createFragments5()
        }

        t1.start()
        t1.join()

        Thread {
            val fragmentTransaction = placeFragments()

            runOnUiThread {
                val t = Thread {
                    runOnUiThread {
                        fragmentTransaction.commit()
                    }
                }

                t.start()
                t.join()

                Thread {
                    Thread.sleep(100)
                    areFragmentsInitialized = true
                }.start()

                initializeLogic()
                adjustPaddings()
            }
        }.start()
    }

    private fun createFragments5() {
        val t5 = Thread {
            tileDocumentation = TileFragment.newInstance(
                checked = false,
                checkable = false,
                enabledText = getString(R.string.tile_documentation_title),
                disabledText = null,
                enabledDesc = getString(R.string.tile_documentation_subtitle),
                disabledDesc = null,
                icon = R.drawable.ic_book,
                disabled = false,
                chatId = chatId,
                functionDesc = getString(R.string.tile_documentation_desc)
            )

        }

        t5.start()
        t5.join()
    }

    private fun placeFragments() : FragmentTransaction {
        val operation = supportFragmentManager.beginTransaction()
            .replace(R.id.tile_documentation, tileDocumentation!!)

        return operation
    }

    private fun initializeLogic() {
        btnBack?.setOnClickListener {
            finishActivity()
        }

        rowCharacters = findViewById(R.id.tile_characters)
        rowAiSystemSettings = findViewById(R.id.tile_ai_system_settings)
        rowMemorySystem = findViewById(R.id.tile_memory_system)
        rowRoleplay = findViewById(R.id.tile_roleplay)
        rowProfileImageProperties = findViewById(R.id.tile_profile_image_properties)
        rowVoiceSettings = findViewById(R.id.tile_voice_settings)
        rowImages = findViewById(R.id.tile_images)
        rowAppearance = findViewById(R.id.tile_appearance)
        rowAboutApp = findViewById(R.id.tile_about_app)
        rowAlertDebugMenu = findViewById(R.id.tile_alert_debug_menu)

        rowCharacters?.setOnClickListener {
            startActivity(Intent(this, CharactersActivity::class.java).putExtra("chatId", chatId))
        }

        rowAiSystemSettings?.setOnClickListener {
            startActivity(Intent(this, AiSystemSettingsActivity::class.java).putExtra("chatId", chatId))
        }

        rowMemorySystem?.setOnClickListener {
            startActivity(Intent(this, MemoryManagerActivity::class.java).putExtra("chatId", chatId))
        }

        rowRoleplay?.setOnClickListener {
            startActivity(Intent(this, org.teslasoft.assistant.ui.activities.memory.RoleplayHubActivity::class.java).putExtra("chatId", chatId))
        }

        rowProfileImageProperties?.setOnClickListener {
            startActivity(Intent(this, ProfileImagePropertiesActivity::class.java))
        }

        rowVoiceSettings?.setOnClickListener {
            startActivity(Intent(this, VoiceSettingsActivity::class.java).putExtra("chatId", chatId))
        }

        // The Images row opens the app-wide Image Generation settings
        // (image-generation-rebuild-plan.md §5) — it replaced the old
        // fixed-list Image model and Resolution tiles.
        rowImages?.setOnClickListener {
            startActivity(Intent(this, ImageGenerationSettingsActivity::class.java))
        }

        rowAppearance?.setOnClickListener {
            startActivity(Intent(this, AppearanceActivity::class.java))
        }

        rowAboutApp?.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java).putExtra("chatId", chatId))
        }

        rowAlertDebugMenu?.setOnClickListener {
            startActivity(Intent(this, AlertDebugMenuActivity::class.java).putExtra("chatId", chatId))
        }

        tileDocumentation?.setOnTileClickListener {
            startActivity(Intent(this, DocumentationActivity::class.java).putExtra("chatId", chatId))
        }
    }

    private fun restartActivity() {
        runOnUiThread {
            recreate()
        }
    }

    private fun isDarkThemeEnabled(): Boolean {
        return when (resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_YES -> true
            Configuration.UI_MODE_NIGHT_NO -> false
            Configuration.UI_MODE_NIGHT_UNDEFINED -> false
            else -> false
        }
    }

    @Suppress("DEPRECATION")
    private fun reloadAmoled() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            window.statusBarColor = 0x00000000
            window.navigationBarColor = 0x00000000
        }
        if (isDarkThemeEnabled() && preferences?.getAmoledPitchBlack() == true) {
            actionBar?.setBackgroundColor(ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme))
            btnBack?.backgroundTintList = ColorStateList.valueOf(ResourcesCompat.getColor(resources, R.color.amoled_accent_50, theme))
        } else {
            actionBar?.setBackgroundColor(SurfaceColors.SURFACE_4.getColor(this))
            btnBack?.backgroundTintList = ColorStateList.valueOf(SurfaceColors.SURFACE_4.getColor(this))
        }
    }

    override fun onResume() {
        super.onResume()

        // Reset preferences singleton
        Preferences.getPreferences(this, chatId)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        adjustPaddings()
    }

    private fun adjustPaddings() {
        WindowInsetsUtil.adjustPaddings(this, R.id.action_bar, EnumSet.of(WindowInsetsUtil.Companion.Flags.STATUS_BAR, WindowInsetsUtil.Companion.Flags.IGNORE_PADDINGS))
        WindowInsetsUtil.adjustPaddings(this, R.id.scrollable, EnumSet.of(WindowInsetsUtil.Companion.Flags.NAVIGATION_BAR, WindowInsetsUtil.Companion.Flags.IGNORE_PADDINGS), customPaddingBottom = (48 * resources.displayMetrics.density).roundToInt())
    }

    private fun finishActivity() {
        val root: View = findViewById(R.id.root)
        root.animate().alpha(0.0f).setDuration(200)
        supportFinishAfterTransition()
    }
}
