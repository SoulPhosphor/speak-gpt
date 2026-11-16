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
import androidx.constraintlayout.widget.ConstraintLayout
import org.teslasoft.assistant.R
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.util.ScreenChrome

/**
 * RAG Active Memory Settings, opened from the Memory Manager (owner design,
 * Nov 16 2026). Memory Search Test sits first, moved here from what is now
 * Memory Repair Settings.
 */
class RagActiveMemorySettingsActivity : SettingsPageActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_rag_active_memory_settings)

        val actionBar = findViewById<ConstraintLayout>(R.id.action_bar)
        val btnBack = findViewById<ImageButton>(R.id.btn_back)
        ScreenChrome.apply(this, actionBar, btnBack)
        ScreenChrome.fitSystemBars(findViewById(R.id.root), actionBar, findViewById(R.id.scroll))

        btnBack.setOnClickListener { finish() }
        findViewById<View>(R.id.row_memory_search_test).setOnClickListener {
            startActivity(Intent(this, MemorySearchTestActivity::class.java))
        }
    }
}
