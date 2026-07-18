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
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.transition.TransitionInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentTransaction
import androidx.interpolator.view.animation.FastOutLinearInInterpolator
import androidx.interpolator.view.animation.LinearOutSlowInInterpolator
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.ChatPreferences
import org.teslasoft.assistant.preferences.GlobalPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.ui.fragments.TileFragment
import org.teslasoft.assistant.ui.fragments.dialogs.CustomizeAssistantDialog
import org.teslasoft.assistant.ui.fragments.dialogs.SelectImageModelFragment
import org.teslasoft.assistant.ui.fragments.dialogs.SelectResolutionFragment
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
    private var rowVoiceSettings: LinearLayout? = null
    private var tileImageModel: TileFragment? = null
    private var tileImageResolution: TileFragment? = null
    private var tileChatLayout: TileFragment? = null
    private var tileFunctionCalling: TileFragment? = null
    private var tileSlashCommands: TileFragment? = null
    private var tileDesktopMode: TileFragment? = null
    private var rowAboutApp: LinearLayout? = null
    private var rowClearChat: LinearLayout? = null
    private var tileDocumentation: TileFragment? = null
    private var tileAmoledMode: TileFragment? = null
    private var tileCustomize: TileFragment? = null
    private var tileChatsAutoSave: TileFragment? = null
    private var rowAlertDebugMenu: LinearLayout? = null
    private var tileHideModelNames: TileFragment? = null
    private var tileMonochromeBackgroundForChatList: TileFragment? = null
    // private var threadLoading: LinearLayout? = null
    private var root: ScrollView? = null
    private var btnBack: ImageButton? = null

    private var areFragmentsInitialized = false
    private var chatId = ""
    private var preferences: Preferences? = null
    private var resolution = ""
    private var imageModel = ""

    private var resolutionChangedListener: SelectResolutionFragment.StateChangesListener = object : SelectResolutionFragment.StateChangesListener {
        override fun onSelected(name: String) {
            preferences?.setResolution(name)
            resolution = name
            tileImageResolution?.updateSubtitle(name)
        }

        override fun onFormError(name: String) {
            Toast.makeText(this@SettingsActivity, getString(R.string.image_resolution_error_empty), Toast.LENGTH_SHORT).show()
            val resolutionSelectorDialogFragment: SelectResolutionFragment = SelectResolutionFragment.newInstance(name, chatId)
            resolutionSelectorDialogFragment.setStateChangedListener(this)
            resolutionSelectorDialogFragment.show(supportFragmentManager.beginTransaction(), "ResolutionSelectorDialog")
        }
    }

    private var imageModelChangedListener: SelectImageModelFragment.StateChangesListener = object : SelectImageModelFragment.StateChangesListener {
        override fun onSelected(name: String) {
            preferences?.setImageModel(name)
            imageModel = name
            tileImageModel?.updateSubtitle(name)
        }

        override fun onFormError(name: String) {
            Toast.makeText(this@SettingsActivity, "Please select an image generating model", Toast.LENGTH_SHORT).show()
            val imageModelSelectorDialogFragment: SelectImageModelFragment = SelectImageModelFragment.newInstance(name, chatId)
            imageModelSelectorDialogFragment.setStateChangedListener(this)
            imageModelSelectorDialogFragment.show(supportFragmentManager.beginTransaction(), "SelectImageModelFragment")
        }
    }

    private var customizeAssistantDialogListener: CustomizeAssistantDialog.CustomizeAssistantDialogListener = object : CustomizeAssistantDialog.CustomizeAssistantDialogListener {
        override fun onEdit(assistantName: String, avatarType: String, avatarId: String) {
            preferences?.setAssistantName(assistantName)
            preferences?.setAvatarType(avatarType)
            preferences?.setAvatarId(avatarId)
        }

        override fun onError(assistantName: String, avatarType: String, avatarId: String, error: String, dialog: CustomizeAssistantDialog) {
            Toast.makeText(this@SettingsActivity, error, Toast.LENGTH_SHORT).show()
            dialog.show(supportFragmentManager.beginTransaction(), "CustomizeAssistantDialog")
        }

        override fun onCancel() { /* unused */ }
    }

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
        transition.excludeTarget(R.id.textView30, true)
        transition.excludeTarget(R.id.textView31, true)
        transition.excludeTarget(R.id.textView32, true)
        transition.excludeTarget(R.id.textView34, true)
        transition.excludeTarget(R.id.textView35, true)
        transition.excludeTarget(R.id.textView36, true)
        transition.excludeTarget(R.id.textView389, true)
        transition.excludeTarget(R.id.constraintLayout8, true)
        transition.excludeTarget(R.id.constraintLayout9, true)
        transition.excludeTarget(R.id.tile_voice_settings, true)
        transition.excludeTarget(R.id.constraintLayout12, true)
        transition.excludeTarget(R.id.constraintLayout13, true)
        transition.excludeTarget(R.id.constraintLayout14, true)
        transition.excludeTarget(R.id.constraintLayout16, true)
        transition.excludeTarget(R.id.constraintLayout17, true)
        transition.excludeTarget(R.id.activity_new_settings_title, true)
        transition.excludeTarget(R.id.btn_back, true)
        transition.excludeTarget(R.id.tile_characters, true)
        transition.excludeTarget(R.id.tile_ai_system_settings, true)
        transition.excludeTarget(R.id.tile_memory_system, true)
        transition.excludeTarget(R.id.tile_roleplay, true)
        transition.excludeTarget(R.id.tile_autosend, true)
        transition.excludeTarget(R.id.tile_voice, true)
        transition.excludeTarget(R.id.tile_voice_language, true)
        transition.excludeTarget(R.id.tile_image_model, true)
        transition.excludeTarget(R.id.tile_image_resolution, true)
        transition.excludeTarget(R.id.tile_tts, true)
        transition.excludeTarget(R.id.tile_stt, true)
        transition.excludeTarget(R.id.tile_silent_mode, true)
        transition.excludeTarget(R.id.tile_always_speak, true)
        transition.excludeTarget(R.id.tile_auto_language_detection, true)
        transition.excludeTarget(R.id.tile_chat_layout, true)
        transition.excludeTarget(R.id.tile_function_calling, true)
        transition.excludeTarget(R.id.tile_slash_commands, true)
        transition.excludeTarget(R.id.tile_desktop_mode, true)
        transition.excludeTarget(R.id.tile_about_app, true)
        transition.excludeTarget(R.id.tile_clear_chat, true)
        transition.excludeTarget(R.id.tile_documentation, true)
        transition.excludeTarget(R.id.tile_amoled_mode, true)
        transition.excludeTarget(R.id.tile_customize, true)
        transition.excludeTarget(R.id.tile_chats_autosave, true)
        transition.excludeTarget(R.id.tile_alert_debug_menu, true)
        transition.excludeTarget(R.id.tile_hide_model_names, true)
        transition.excludeTarget(R.id.tile_monochrome_background_for_chat_list, true)

        val transition2 = TransitionInflater.from(this).inflateTransition(android.R.transition.move).apply {
            interpolator = FastOutLinearInInterpolator()
            duration = 200
        }

        transition2.excludeTarget(R.id.scrollable, true)
        transition2.excludeTarget(R.id.textView30, true)
        transition2.excludeTarget(R.id.textView31, true)
        transition2.excludeTarget(R.id.textView32, true)
        transition2.excludeTarget(R.id.textView34, true)
        transition2.excludeTarget(R.id.textView35, true)
        transition2.excludeTarget(R.id.textView36, true)
        transition2.excludeTarget(R.id.textView389, true)
        transition2.excludeTarget(R.id.constraintLayout8, true)
        transition2.excludeTarget(R.id.constraintLayout9, true)
        transition2.excludeTarget(R.id.tile_voice_settings, true)
        transition2.excludeTarget(R.id.constraintLayout12, true)
        transition2.excludeTarget(R.id.constraintLayout13, true)
        transition2.excludeTarget(R.id.constraintLayout14, true)
        transition2.excludeTarget(R.id.constraintLayout16, true)
        transition2.excludeTarget(R.id.constraintLayout17, true)
        transition2.excludeTarget(R.id.tile_characters, true)
        transition2.excludeTarget(R.id.tile_ai_system_settings, true)
        transition2.excludeTarget(R.id.tile_memory_system, true)
        transition2.excludeTarget(R.id.tile_roleplay, true)
        transition2.excludeTarget(R.id.tile_autosend, true)
        transition2.excludeTarget(R.id.tile_voice, true)
        transition2.excludeTarget(R.id.tile_voice_language, true)
        transition2.excludeTarget(R.id.tile_image_model, true)
        transition2.excludeTarget(R.id.tile_image_resolution, true)
        transition2.excludeTarget(R.id.tile_tts, true)
        transition2.excludeTarget(R.id.tile_stt, true)
        transition2.excludeTarget(R.id.tile_silent_mode, true)
        transition2.excludeTarget(R.id.tile_always_speak, true)
        transition2.excludeTarget(R.id.tile_auto_language_detection, true)
        transition2.excludeTarget(R.id.tile_chat_layout, true)
        transition2.excludeTarget(R.id.tile_function_calling, true)
        transition2.excludeTarget(R.id.tile_slash_commands, true)
        transition2.excludeTarget(R.id.tile_desktop_mode, true)
        transition2.excludeTarget(R.id.tile_about_app, true)
        transition2.excludeTarget(R.id.tile_clear_chat, true)
        transition2.excludeTarget(R.id.tile_documentation, true)
        transition2.excludeTarget(R.id.tile_amoled_mode, true)
        transition2.excludeTarget(R.id.tile_customize, true)
        transition2.excludeTarget(R.id.tile_chats_autosave, true)
        transition2.excludeTarget(R.id.tile_alert_debug_menu, true)
        transition2.excludeTarget(R.id.tile_hide_model_names, true)
        transition2.excludeTarget(R.id.tile_monochrome_background_for_chat_list, true)

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
        root = findViewById(R.id.root)

        val extras: Bundle? = intent.extras

        if (extras != null) {
            chatId = extras.getString("chatId", "")

            if (chatId == "") {
                rowClearChat?.isEnabled = false
                rowClearChat?.visibility = View.GONE
            }
        } else {
            rowClearChat?.isEnabled = false
            rowClearChat?.visibility = View.GONE
        }

        preferences = Preferences.getPreferences(this, chatId)

        resolution = preferences?.getResolution() ?: "256x256"
        imageModel = preferences?.getImageModel() ?: "dall-e-3"

        reloadAmoled()

        val t1 = Thread {
            createFragments2()
            createFragments4()
            createFragments5()
        }

        val t2 = Thread {
            createFragments6()
            createFragments7()
        }

        t1.start()
        t2.start()

        t1.join()

        Thread {
            t2.join()
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

                if (chatId == "") {
                    rowClearChat?.isEnabled = false
                    rowClearChat?.visibility = View.GONE
                } else {
                    rowClearChat?.isEnabled = true
                    rowClearChat?.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    private fun createFragments2() {
        val t2 = Thread {
            tileImageModel = TileFragment.newInstance(
                checked = false,
                checkable = false,
                enabledText = "Image model",
                disabledText = null,
                enabledDesc = preferences?.getImageModel() ?: "dall-e-3",
                disabledDesc = null,
                icon = R.drawable.ic_image,
                disabled = false,
                chatId = chatId,
                functionDesc = getString(R.string.tile_dalle_desc)
            )

            tileImageResolution = TileFragment.newInstance(
                checked = false,
                checkable = false,
                enabledText = getString(R.string.tile_image_resolution_title),
                disabledText = null,
                enabledDesc = preferences?.getResolution() ?: "1024x1024",
                disabledDesc = null,
                icon = R.drawable.ic_image,
                disabled = false,
                chatId = chatId,
                functionDesc = getString(R.string.tile_image_resolution_desc),
                transitionName = "expand_resolution"
            )
        }

        t2.start()
        t2.join()
    }

    private fun createFragments4() {
        val t4 = Thread {
            tileChatLayout = TileFragment.newInstance(
                preferences?.getLayout() == "classic",
                true,
                getString(R.string.tile_classic_layout_title),
                null,
                getString(R.string.on),
                getString(R.string.off),
                R.drawable.ic_chat,
                false,
                chatId,
                getString(R.string.tile_classic_layout_desc)
            )

            tileFunctionCalling = TileFragment.newInstance(
                preferences?.getFunctionCalling() == true,
                true,
                "Function calling",
                null,
                getString(R.string.on),
                getString(R.string.off),
                R.drawable.ic_experiment,
                false,
                chatId,
                "This feature allows you to enable function calling. Please note that this feature is experimental and unstable."
            )

            tileSlashCommands = TileFragment.newInstance(
                preferences?.getImagineCommand() == true,
                true,
                getString(R.string.tile_sh_title),
                null,
                getString(R.string.on),
                getString(R.string.off),
                R.drawable.ic_experiment,
                false,
                chatId,
                getString(R.string.tile_sh_desc)
            )

            tileDesktopMode = TileFragment.newInstance(
                preferences?.getDesktopMode() == true,
                true,
                getString(R.string.tile_desktop_mode_title),
                null,
                getString(R.string.on),
                getString(R.string.off),
                R.drawable.ic_desktop,
                false,
                chatId,
                getString(R.string.tile_desktop_mode_desc)
            )

            tileMonochromeBackgroundForChatList = TileFragment.newInstance(
                preferences?.getMonochromeBackgroundForChatList() == true,
                true,
                "Monochrome background for chat list",
                null,
                getString(R.string.on),
                getString(R.string.off),
                R.drawable.ic_experiment,
                false,
                chatId,
                "This feature allows you to enable monochrome background for chat list."
            )
        }

        t4.start()
        t4.join()
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

            tileAmoledMode = TileFragment.newInstance(
                preferences?.getAmoledPitchBlack() == true && isDarkThemeEnabled(),
                true,
                getString(R.string.tile_amoled_mode_title),
                null,
                getString(R.string.on),
                getString(R.string.off),
                R.drawable.ic_experiment,
                !isDarkThemeEnabled(),
                chatId,
                getString(R.string.tile_amoled_mode_desc)
            )

            tileHideModelNames = TileFragment.newInstance(
                preferences?.getHideModelNames() == true,
                true,
                "Hide model names",
                null,
                getString(R.string.on),
                getString(R.string.off),
                R.drawable.ic_visibility_off,
                false,
                chatId,
                "This feature allows you to hide model names in the chat list to make it more minimalist and less distractive."
            )
        }

        t5.start()
        t5.join()
    }

    private fun createFragments6() {
        val t6 = Thread {
            tileCustomize = TileFragment.newInstance(
                checked = false,
                checkable = false,
                enabledText = getString(R.string.tile_assistant_customize_title),
                disabledText = null,
                enabledDesc = getString(R.string.tile_assistant_customize_title),
                disabledDesc = null,
                icon = R.drawable.ic_experiment,
                disabled = false,
                chatId = chatId,
                functionDesc = getString(R.string.tile_assistant_customize_desc),
                transitionName = "expand_customize"
            )
        }

        t6.start()
        t6.join()
    }

    private fun createFragments7() {
        val t7 = Thread {
            tileChatsAutoSave = TileFragment.newInstance(
                preferences?.getChatsAutosave() == true,
                true,
                getString(R.string.tile_autosave_title),
                null,
                getString(R.string.on),
                getString(R.string.off),
                R.drawable.ic_experiment,
                false,
                chatId,
                getString(R.string.tile_autosave_desc)
            )
        }

        t7.start()
        t7.join()
    }

    private fun placeFragments() : FragmentTransaction {
        val operation = supportFragmentManager.beginTransaction()
            .replace(R.id.tile_image_model, tileImageModel!!)
            .replace(R.id.tile_image_resolution, tileImageResolution!!)
            .replace(R.id.tile_chat_layout, tileChatLayout!!)
            .replace(R.id.tile_function_calling, tileFunctionCalling!!)
            .replace(R.id.tile_slash_commands, tileSlashCommands!!)
            .replace(R.id.tile_desktop_mode, tileDesktopMode!!)
            .replace(R.id.tile_amoled_mode, tileAmoledMode!!)
            .replace(R.id.tile_customize, tileCustomize!!)
            .replace(R.id.tile_chats_autosave, tileChatsAutoSave!!)
            .replace(R.id.tile_documentation, tileDocumentation!!)
            .replace(R.id.tile_hide_model_names, tileHideModelNames!!)
            .replace(R.id.tile_monochrome_background_for_chat_list, tileMonochromeBackgroundForChatList!!)

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
        rowVoiceSettings = findViewById(R.id.tile_voice_settings)
        rowAboutApp = findViewById(R.id.tile_about_app)
        rowClearChat = findViewById(R.id.tile_clear_chat)
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

        rowVoiceSettings?.setOnClickListener {
            startActivity(Intent(this, VoiceSettingsActivity::class.java).putExtra("chatId", chatId))
        }

        rowAboutApp?.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java).putExtra("chatId", chatId))
        }

        rowClearChat?.setOnClickListener {
            MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
                .setTitle(R.string.label_clear_chat)
                .setMessage(R.string.msg_clear_chat)
                .setPositiveButton(R.string.yes) { _, _ ->
                    run {
                        ChatPreferences.getChatPreferences().clearChat(this, chatId)
                        Toast.makeText(this, getString(R.string.submsg_chat_cleared), Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(R.string.no) { _, _ -> }
                .show()
        }

        rowAlertDebugMenu?.setOnClickListener {
            startActivity(Intent(this, AlertDebugMenuActivity::class.java).putExtra("chatId", chatId))
        }

        tileImageModel?.setOnTileClickListener {
            val imageModelSelectorDialogFragment: SelectImageModelFragment = SelectImageModelFragment.newInstance(imageModel, chatId)
            imageModelSelectorDialogFragment.setStateChangedListener(imageModelChangedListener)
            imageModelSelectorDialogFragment.show(supportFragmentManager.beginTransaction(), "SelectImageModelFragment")
        }

        tileImageResolution?.setOnTileClickListener {
            val resolutionSelectorDialogFragment: SelectResolutionFragment = SelectResolutionFragment.newInstance(resolution, chatId)
            resolutionSelectorDialogFragment.setStateChangedListener(resolutionChangedListener)
            resolutionSelectorDialogFragment.show(supportFragmentManager.beginTransaction(), "ResolutionSelectorDialog")
        }

        tileChatLayout?.setOnCheckedChangeListener { isChecked -> run {
            if (isChecked) {
                preferences?.setLayout("classic")
            } else {
                preferences?.setLayout("bubbles")
            }
        }}

        tileFunctionCalling?.setOnCheckedChangeListener { isChecked -> run {
            if (isChecked) {
                preferences?.setFunctionCalling(true)
            } else {
                preferences?.setFunctionCalling(false)
            }
        }}

        tileSlashCommands?.setOnCheckedChangeListener { isChecked -> run {
            if (isChecked) {
                preferences?.setImagineCommand(true)
            } else {
                preferences?.setImagineCommand(false)
            }
        }}

        tileDesktopMode?.setOnCheckedChangeListener { isChecked -> run {
            if (isChecked) {
                preferences?.setDesktopMode(true)
            } else {
                preferences?.setDesktopMode(false)
            }
        }}

        tileAmoledMode?.setOnCheckedChangeListener { isChecked -> run {
            if (isChecked) {
                preferences?.setAmoledPitchBlack(true)
            } else {
                preferences?.setAmoledPitchBlack(false)
            }

            restartActivity()
        }}

        tileChatsAutoSave?.setOnCheckedChangeListener { isChecked -> run {
            if (isChecked) {
                preferences?.setChatsAutosave(true)
            } else {
                preferences?.setChatsAutosave(false)
            }
        }}

        tileHideModelNames?.setOnCheckedChangeListener { isChecked -> run {
            if (isChecked) {
                preferences?.setHideModelNames(true)
            } else {
                preferences?.setHideModelNames(false)
            }
        }}

        tileMonochromeBackgroundForChatList?.setOnCheckedChangeListener { isChecked -> run {
            if (isChecked) {
                preferences?.setMonochromeBackgroundForChatList(true)
            } else {
                preferences?.setMonochromeBackgroundForChatList(false)
            }
        }}

        tileDocumentation?.setOnTileClickListener {
            startActivity(Intent(this, DocumentationActivity::class.java).putExtra("chatId", chatId))
        }

        tileCustomize?.setOnTileClickListener { _ ->
            val customizeAssistantDialogFragment: CustomizeAssistantDialog = CustomizeAssistantDialog.newInstance(chatId, preferences?.getAssistantName() ?: "SpeakGPT", preferences?.getAvatarType() ?: "builtin", preferences?.getAvatarId() ?: "gpt")
            customizeAssistantDialogFragment.setCustomizeAssistantDialogListener(customizeAssistantDialogListener)
            customizeAssistantDialogFragment.show(supportFragmentManager.beginTransaction(), "CustomizeAssistantDialog")
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
            btnBack?.setBackgroundResource(R.drawable.btn_accent_icon_large_amoled)
        } else {
            btnBack?.background = getDisabledDrawable(ResourcesCompat.getDrawable(resources, R.drawable.btn_accent_icon_large, theme)!!)
        }
    }

    override fun onResume() {
        super.onResume()

        // Reset preferences singleton
        Preferences.getPreferences(this, chatId)
    }

    private fun getDisabledDrawable(drawable: Drawable) : Drawable {
        DrawableCompat.setTint(DrawableCompat.wrap(drawable), getDisabledColor())
        return drawable
    }

    private fun getDisabledColor() : Int {
        return if (isDarkThemeEnabled() && preferences?.getAmoledPitchBlack() == true) {
            ResourcesCompat.getColor(resources, R.color.accent_50, theme)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SurfaceColors.SURFACE_5.getColor(this)
            } else {
                getColor(R.color.accent_100)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        adjustPaddings()
    }

    private fun adjustPaddings() {
        WindowInsetsUtil.adjustPaddings(this, R.id.scrollable, EnumSet.of(WindowInsetsUtil.Companion.Flags.STATUS_BAR, WindowInsetsUtil.Companion.Flags.NAVIGATION_BAR, WindowInsetsUtil.Companion.Flags.IGNORE_PADDINGS), customPaddingBottom = (48 * resources.displayMetrics.density).roundToInt())
    }

    private fun finishActivity() {
        val root: View = findViewById(R.id.root)
        root.animate().alpha(0.0f).setDuration(200)
        supportFinishAfterTransition()
    }
}
