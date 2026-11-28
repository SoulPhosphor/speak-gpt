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

package org.teslasoft.assistant.util

import android.content.Context

/** Screen, network and power-save state, as written in the diagnostic logs. */
object DeviceStateDiagnostics {
    fun screen(context: Context): String = try {
        if ((context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive) "on" else "off"
    } catch (_: Throwable) { "unknown" }

    fun network(context: Context): String = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val net = cm.activeNetwork
        if (net == null) "none" else {
            val caps = cm.getNetworkCapabilities(net)
            when {
                caps == null -> "unknown"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
        }
    } catch (_: Throwable) { "unknown" }

    fun powerSave(context: Context): String = try {
        if ((context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isPowerSaveMode) "on" else "off"
    } catch (_: Throwable) { "unknown" }
}
