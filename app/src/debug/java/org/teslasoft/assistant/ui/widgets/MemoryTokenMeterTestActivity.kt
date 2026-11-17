package org.teslasoft.assistant.ui.widgets

import android.app.Activity
import android.os.Bundle
import org.teslasoft.assistant.R

/** Debug-only host for instrumenting the actual memory editor layout. */
class MemoryTokenMeterTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.UI_Material)
        super.onCreate(savedInstanceState)
        org.teslasoft.assistant.theme.ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_memory_editor)
    }
}
