/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/
package org.teslasoft.assistant.util.summarizer

import android.content.Context

/** Process-wide ownership for conversation summarizer controllers. A chat
 * screen may attach and detach, but leaving that screen never owns or cancels
 * the underlying network work. */
object SummarizerControllerRegistry {
    fun interface AppListener {
        fun onOperationChanged(chatId: String, state: SummarizerController.OperationState)
    }
    private data class Record(
        var chatId: String,
        val controller: SummarizerController,
        var listener: SummarizerController.Listener? = null
    )

    private val records = LinkedHashMap<String, Record>()
    private val appListeners = LinkedHashSet<AppListener>()

    @Synchronized
    fun controller(context: Context, chatId: String): SummarizerController {
        records[chatId]?.let { return it.controller }
        lateinit var record: Record
        val controller = SummarizerController(context.applicationContext) { record.chatId }
        record = Record(chatId, controller)
        controller.listener = forwardingListener(record)
        records[chatId] = record
        return controller
    }

    @Synchronized
    fun attach(chatId: String, listener: SummarizerController.Listener) {
        records[chatId]?.let { record ->
            record.listener = listener
            listener.onSummarizerOperationChanged(record.controller.currentOperationState())
        }
    }

    @Synchronized
    fun detach(chatId: String, listener: SummarizerController.Listener) {
        records[chatId]?.let { if (it.listener === listener) it.listener = null }
    }

    @Synchronized
    fun addAppListener(listener: AppListener) { appListeners.add(listener) }

    @Synchronized
    fun removeAppListener(listener: AppListener) { appListeners.remove(listener) }

    @Synchronized
    fun cancel(chatId: String) {
        records[chatId]?.controller?.cancel()
    }

    @Synchronized
    fun rename(oldChatId: String, newChatId: String) {
        if (oldChatId == newChatId) return
        records.remove(oldChatId)?.let {
            it.chatId = newChatId
            records[newChatId] = it
        }
    }

    @Synchronized
    fun activeOperations(): List<SummarizerController.OperationState.Running> =
        records.values.mapNotNull {
            it.controller.currentOperationState() as? SummarizerController.OperationState.Running
        }

    private fun forwardingListener(record: Record) = object : SummarizerController.Listener {
        override fun onSummarizerStateChanged() {
            synchronized(this@SummarizerControllerRegistry) {
                record.listener?.onSummarizerStateChanged()
            }
        }

        override fun onSummarizerErrorEpisode() {
            synchronized(this@SummarizerControllerRegistry) {
                record.listener?.onSummarizerErrorEpisode()
            }
        }

        override fun onSummarizerOperationChanged(state: SummarizerController.OperationState) {
            synchronized(this@SummarizerControllerRegistry) {
                record.listener?.onSummarizerOperationChanged(state)
                appListeners.toList().forEach { it.onOperationChanged(record.chatId, state) }
            }
        }
    }
}
