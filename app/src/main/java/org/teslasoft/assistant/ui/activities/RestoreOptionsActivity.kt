package org.teslasoft.assistant.ui.activities

import android.os.Bundle
import android.view.View
import org.teslasoft.assistant.R
import org.teslasoft.assistant.ui.util.ScreenChrome

/** Dedicated category and database restore screen, retaining the existing coordinator. */
class RestoreOptionsActivity : MemoryBackupRestoreActivity() {
    override val isRestoreOptionsPage: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val header = findViewById<View>(R.id.action_bar)
        val back = findViewById<View>(R.id.btn_back)
        ScreenChrome.apply(this, header, back)
        ScreenChrome.fitSystemBars(findViewById(R.id.root), header, findViewById(R.id.scroll))
        back?.setOnClickListener { finish() }
    }
}
