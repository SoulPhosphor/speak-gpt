/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0.
 **************************************************************************/

package org.teslasoft.assistant.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import org.teslasoft.assistant.service.GenerationKeepAliveDiagnostics
import org.teslasoft.assistant.service.HandsFreeConnectionDiagnostics

data class NetworkTransition(val from: String, val to: String, val elapsedMs: Long)

data class GenerationNetworkSnapshot(
    val atDispatch: String,
    val atFailure: String,
    val transitions: List<NetworkTransition>,
    val transitionTrackingAvailable: Boolean = true
) {
    fun transitionsDisplay(): String = when {
        !transitionTrackingAvailable -> "unavailable"
        transitions.isEmpty() -> "none observed"
        else -> transitions.joinToString(" | ") {
            "${it.from} -> ${it.to} at +${it.elapsedMs} ms"
        }
    }
}

internal class NetworkTransitionTrace(val initial: String, private val startedAtMs: Long) {
    private var current = initial
    private val changes = ArrayList<NetworkTransition>()

    fun record(state: String, nowMs: Long) {
        if (state == current || state == "unknown") return
        changes += NetworkTransition(current, state, (nowMs - startedAtMs).coerceAtLeast(0L))
        current = state
    }

    fun snapshot(finalState: String) = GenerationNetworkSnapshot(initial, finalState, changes.toList())
}

class GenerationNetworkMonitor(context: Context) : AutoCloseable {
    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val lock = Any()
    private val trace = NetworkTransitionTrace(currentTransport(), SystemClock.elapsedRealtime())
    @Volatile private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { record(stateFor(network)) }
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            record(stateFor(networkCapabilities))
        }
        override fun onLost(network: Network) { record(currentTransport()) }
    }

    init {
        try {
            cm.registerDefaultNetworkCallback(callback)
            registered = true
        } catch (_: Throwable) {}
    }

    fun snapshot(): GenerationNetworkSnapshot {
        val finalState = currentTransport()
        synchronized(lock) {
            trace.record(finalState, SystemClock.elapsedRealtime())
            return trace.snapshot(finalState).copy(transitionTrackingAvailable = registered)
        }
    }

    private fun record(state: String) {
        synchronized(lock) { trace.record(state, SystemClock.elapsedRealtime()) }
    }

    private fun currentTransport(): String = try {
        val network = cm.activeNetwork ?: return "none"
        stateFor(network)
    } catch (_: Throwable) { "unknown" }

    private fun stateFor(network: Network): String = try {
        stateFor(cm.getNetworkCapabilities(network) ?: return "unknown")
    } catch (_: Throwable) { "unknown" }

    private fun stateFor(caps: NetworkCapabilities): String = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
        else -> "other"
    }

    override fun close() {
        if (!registered) return
        registered = false
        try { cm.unregisterNetworkCallback(callback) } catch (_: Throwable) {}
    }
}

data class GenerationFailureSnapshot(
    val voiceLive: Boolean,
    val network: GenerationNetworkSnapshot,
    val generationKeepAlive: GenerationKeepAliveDiagnostics,
    val handsFreeKeepAlive: HandsFreeConnectionDiagnostics
) {
    fun asLogLines(): List<String> = listOf(
        "Network at dispatch: ${network.atDispatch}",
        "Network at failure: ${network.atFailure}",
        "Network transitions: ${network.transitionsDisplay()}",
        "Generation keep-alive: ${generationKeepAlive.asLogFields()}",
        "Hands-free keep-alive: ${handsFreeKeepAlive.asLogFields()}"
    )
}

internal fun resolveFailureVoiceState(
    captured: GenerationFailureSnapshot?,
    currentVoiceLive: Boolean
): Boolean = captured?.voiceLive ?: currentVoiceLive
