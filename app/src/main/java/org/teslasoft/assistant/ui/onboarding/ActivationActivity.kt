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

package org.teslasoft.assistant.ui.onboarding

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import org.teslasoft.assistant.ui.activities.MainActivity
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.util.Hash
import androidx.core.content.edit
import eightbitlab.com.blurview.BlurView

class ActivationActivity : FragmentActivity() {

    private var btnNext: MaterialButton? = null
    private var keyInput: EditText? = null
    private var hostInput: EditText? = null
    private var debugFeatures: ConstraintLayout? = null
    private var foregroundBlur: BlurView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_activation)

        btnNext = findViewById(R.id.btn_next)
        keyInput = findViewById(R.id.password)
        hostInput = findViewById(R.id.username)
        debugFeatures = findViewById(R.id.debug_features)
        debugFeatures?.visibility = ConstraintLayout.INVISIBLE

        foregroundBlur = findViewById(R.id.foreground_blur)

        // Deprecated renderscript seems does not work properly on the older android versions
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            val tl = findViewById<ConstraintLayout>(R.id.tl)
            val tr = findViewById<ConstraintLayout>(R.id.tr)
            tl?.visibility = ConstraintLayout.GONE
            tr?.visibility = ConstraintLayout.GONE
        } else {
            val decorView = window.decorView
            val rootView: ViewGroup = decorView.findViewById(android.R.id.content)
            val windowBackground = decorView.background

            foregroundBlur?.setupWith(rootView)?.setFrameClearDrawable(windowBackground)?.setBlurRadius(250f)
        }

        btnNext?.setOnClickListener {
            if (keyInput?.text.toString().trim() == "") {
                Toast.makeText(this, "Please enter an API key", Toast.LENGTH_SHORT).show()
            } else if (hostInput?.text.toString().trim() == "") {
                Toast.makeText(this, "Please enter API endpoint", Toast.LENGTH_SHORT).show()
            } else {
                val apiEndpointObject = ApiEndpointObject("Default", hostInput?.text.toString(), keyInput?.text.toString())
                val apiEndpointPreferences = ApiEndpointPreferences.getApiEndpointPreferences(this)
                apiEndpointPreferences.setApiEndpoint(this, apiEndpointObject)
                val gPreferences = Preferences.getPreferences(this, "")
                gPreferences.setApiEndpointId(Hash.hash("Default"))
                getSharedPreferences("setup", MODE_PRIVATE).edit { putBoolean("setup", true) }
                gPreferences.setApiKey(keyInput?.text.toString(), this)
                gPreferences.setCustomHost(hostInput?.text.toString())
                startActivity(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW))
                finish()
            }
        }
    }
}
