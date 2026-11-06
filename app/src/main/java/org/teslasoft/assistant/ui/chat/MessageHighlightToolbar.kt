/*
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package org.teslasoft.assistant.ui.chat

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import org.teslasoft.assistant.R

/**
 * The floating Copy / Share / Select All pop-up for the chat's own message
 * highlighting. It is Android's own floating text pop-up, with the same
 * items Android shows for highlighted read-only text, including other apps'
 * text actions such as Translate.
 */
internal class MessageHighlightToolbar(private val host: Host) {

    interface Host {
        fun onCopy()
        fun onShare()
        fun onSelectAll()
        fun onProcessText(intent: Intent)
        fun isAllSelected(): Boolean

        /** The highlighted area, in the coordinates of the view the pop-up was opened from. */
        fun getContentRect(out: Rect)

        /** Android closed the pop-up (for example, Back). */
        fun onClosed()
    }

    private var mode: ActionMode? = null
    private var context: Context? = null

    val isShown: Boolean get() = mode != null

    /** Opens the pop-up over [view], or brings it back and moves it to the highlight. */
    fun show(view: View) {
        val current = mode
        if (current != null) {
            current.invalidateContentRect()
            current.hide(0)
            return
        }
        context = view.context
        mode = view.startActionMode(callback, ActionMode.TYPE_FLOATING)
    }

    /** Hides the pop-up while a handle is dragged or the chat scrolls; it returns on its own. */
    fun hideWhileMoving() {
        mode?.hide(ActionMode.DEFAULT_HIDE_DURATION.toLong())
    }

    /** Re-checks which items apply (Select All disappears once everything is highlighted). */
    fun refresh() {
        mode?.invalidate()
    }

    fun dismiss() {
        val current = mode ?: return
        mode = null
        current.finish()
    }

    private val callback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(actionMode: ActionMode, menu: Menu): Boolean {
            menu.add(Menu.NONE, android.R.id.copy, ORDER_COPY, android.R.string.copy)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.add(Menu.NONE, android.R.id.shareText, ORDER_SHARE, R.string.chat_highlight_share)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(Menu.NONE, android.R.id.selectAll, ORDER_SELECT_ALL, android.R.string.selectAll)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            context?.let { addOtherAppActions(it, menu) }
            return true
        }

        override fun onPrepareActionMode(actionMode: ActionMode, menu: Menu): Boolean {
            menu.findItem(android.R.id.selectAll)?.isVisible = !host.isAllSelected()
            return true
        }

        override fun onActionItemClicked(actionMode: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                android.R.id.copy -> host.onCopy()
                android.R.id.shareText -> host.onShare()
                android.R.id.selectAll -> host.onSelectAll()
                else -> item.intent?.let { host.onProcessText(Intent(it)) } ?: return false
            }
            return true
        }

        override fun onDestroyActionMode(actionMode: ActionMode) {
            if (mode === actionMode) {
                mode = null
                host.onClosed()
            }
        }

        override fun onGetContentRect(actionMode: ActionMode, view: View, outRect: Rect) {
            host.getContentRect(outRect)
        }
    }

    /** Other apps that act on highlighted text, the same ones Android lists. */
    private fun addOtherAppActions(context: Context, menu: Menu) {
        val packageManager = context.packageManager
        val query = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
        val activities = try {
            packageManager.queryIntentActivities(query, 0)
        } catch (_: RuntimeException) {
            emptyList()
        }
        var order = ORDER_OTHER_APPS
        for (info in activities) {
            val activity = info.activityInfo ?: continue
            val allowed = activity.packageName == context.packageName ||
                (activity.exported && (activity.permission == null ||
                    context.checkSelfPermission(activity.permission) == PackageManager.PERMISSION_GRANTED))
            if (!allowed) continue
            val intent = Intent(query)
                .setClassName(activity.packageName, activity.name)
                .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
            menu.add(Menu.NONE, Menu.NONE, order++, info.loadLabel(packageManager))
                .setIntent(intent)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
    }

    private companion object {
        const val ORDER_COPY = 1
        const val ORDER_SHARE = 2
        const val ORDER_SELECT_ALL = 3
        const val ORDER_OTHER_APPS = 100
    }
}
