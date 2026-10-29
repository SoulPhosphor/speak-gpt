/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.ui.chat

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ChatActivity is a FragmentActivity, so `app:srcCompat` alone draws nothing
 * on a plain ImageButton. Header icons the activity never sets from code must
 * carry `android:src`.
 */
class ChatHeaderIconSourceContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../$relative"))
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: throw AssertionError("$relative not found from " + File(".").absolutePath)
    }

    private fun element(layout: String, id: String): String {
        val at = layout.indexOf("android:id=\"@+id/$id\"")
        assertTrue("$id missing", at >= 0)
        return layout.substring(layout.lastIndexOf('<', at), layout.indexOf("/>", at))
    }

    @Test
    fun headerIconsNotSetFromCodeCarryAndroidSrc() {
        val layout = source("src/main/res/layout/activity_chat.xml")
        mapOf(
            "btn_new_chat" to "ic_edit_square",
            "btn_active_profile" to "ic_theater_comedy",
            "btn_summarizer_errors" to "ic_report"
        ).forEach { (id, icon) ->
            assertTrue(id, element(layout, id).contains("android:src=\"@drawable/$icon\""))
        }
    }
}
