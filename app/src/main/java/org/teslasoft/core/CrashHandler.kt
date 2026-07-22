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

package org.teslasoft.core

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import cat.ereza.customactivityoncrash.CustomActivityOnCrash
import com.google.android.material.button.MaterialButton
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Logger
import org.teslasoft.assistant.ui.activities.MainActivity
import org.teslasoft.assistant.util.CrashReportFormat
import java.time.Instant
import java.time.format.DateTimeFormatter
import org.teslasoft.assistant.theme.ThemeManager

/** This activity will be opened if app os crashed. */
@Suppress("DEPRECATION")
class CrashHandlerActivity : FragmentActivity() {

    private var error: String? = null
    private var textError: TextView? = null
    private var btnRestart: MaterialButton? = null
    private var btnCopy: MaterialButton? = null

    @SuppressLint("SetTextI18n", "HardwareIds")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)

        if (Build.VERSION.SDK_INT < 30) {
            window.statusBarColor = getColor(R.color.amoled_window_background)
            window.navigationBarColor = getColor(R.color.amoled_window_background)
        }

        val appVersion = try {
            val pInfo: PackageInfo = if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                packageManager.getPackageInfo(packageName, 0)
            }

            val version = pInfo.versionName

            version
        } catch (_: PackageManager.NameNotFoundException) {
            "unknown"
        }

        val versionCode = try {
            val pInfo: PackageInfo = if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                packageManager.getPackageInfo(packageName, 0)
            }

            val version = pInfo.longVersionCode

            version
        } catch (_: PackageManager.NameNotFoundException) {
            "unknown"
        }

        try {
            error = CustomActivityOnCrash.getStackTraceFromIntent(intent)

            setContentView(R.layout.activity_crash)

            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    finishAndRemoveTask()
                }
            })

            textError = findViewById(R.id.text_error)
            btnRestart = findViewById(R.id.btn_restart)
            btnCopy = findViewById(R.id.btn_copy)

            textError!!.setTextIsSelectable(true)

            // The only screen breadcrumb the app collects: CustomActivityOnCrash's
            // activity history (trackActivities). It is captured on every crash
            // but was being discarded — surface it now. Retrieved and formatted
            // defensively (never throws) so it can't disturb the crash report;
            // it is ACTIVITY-level, so it shows the last SCREEN, not the
            // fragment/dialog/tab within it (no navigation tracking is added).
            val activityHistory = CrashReportFormat.formatActivityHistory(
                try { CustomActivityOnCrash.getActivityLogFromIntent(intent) } catch (_: Throwable) { null }
            )

            textError!!.text = "\nApp has been crashed and needs to be restarted.\n\n===== BEGIN SYSTEM INFO =====\nFailure type: Uncaught JVM exception\nAndroid version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT} ${Build.VERSION.CODENAME})\nROM version: ${Build.VERSION.INCREMENTAL}\nApp version: $appVersion ($versionCode)\nDevice model: ${Build.MODEL}\nAndroid device ID: ${Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)}\nEffective time: ${
                DateTimeFormatter.ISO_INSTANT.format(
                    Instant.now())}\n===== END SYSTEM INFO =====\n\n===== BEGIN OF CRASH =====\n$error\n===== END OF CRASH =====\n\n===== RECENT SCREEN/ACTIVITY HISTORY =====\n$activityHistory\n===== END SCREEN/ACTIVITY HISTORY =====\n"

            // Do NOT clear the crash log here (the automatic clear was removed
            // July 22 2026). Logger.clearCrashLog blanks the ENTIRE "crash"
            // Error Log value — crashes, generation errors, database-health
            // lines, process-exit and rename entries all share it — so clearing
            // on every crash destroyed the pre-crash context that led to this
            // crash, exactly when it is most useful. The Error Log self-bounds
            // via Logger.trimByEntries (500 entries / 30 days); the user-facing
            // Clear Log button in LogsActivity still calls clearCrashLog for a
            // deliberate, user-initiated wipe.
            Logger.log(this, "crash", "CrashHandler", "error", textError!!.text.toString())

            if (error == "") {
                finishAndRemoveTask()
            }

            btnRestart!!.setOnClickListener { restart() }

            btnCopy!!.setOnClickListener { copy() }
        } catch (_: Exception) {
            finishAndRemoveTask()
        }
    }

    fun restart() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    fun copy() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Error", textError!!.text.toString())
        clipboard.setPrimaryClip(clip)

        Toast.makeText(
            applicationContext,
            R.string.label_copy,
            Toast.LENGTH_SHORT
        ).show()
    }
}
