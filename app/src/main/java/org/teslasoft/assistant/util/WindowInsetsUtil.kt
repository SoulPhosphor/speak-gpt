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

import android.app.Activity
import android.content.Context
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.EnumSet

class WindowInsetsUtil {
    companion object {
        enum class Flags {
            STATUS_BAR,
            NAVIGATION_BAR,
            IGNORE_PADDINGS
        }

        fun adjustPaddings(activity: Activity, parentView: View?, res: Int, flags: EnumSet<Flags>, customPaddingTop: Int = 0, customPaddingBottom: Int = 0, forceFromAndroidR: Boolean = false) {
            val view = parentView?.findViewById<View>(res) ?: return
            applyInsetPaddings(activity, view, res, flags, customPaddingTop, customPaddingBottom)
        }

        fun adjustPaddings(activity: Activity, res: Int, flags: EnumSet<Flags>, customPaddingTop: Int = 0, customPaddingBottom: Int = 0, forceFromAndroidR: Boolean = false) {
            val view = activity.findViewById<View>(res) ?: return
            applyInsetPaddings(activity, view, res, flags, customPaddingTop, customPaddingBottom)
        }

        /**
         * Apply system-bar insets as padding, re-applying on every insets
         * dispatch via an [androidx.core.view.OnApplyWindowInsetsListener].
         *
         * This deliberately replaces the previous Android-12+ path, which read
         * `decorView.rootWindowInsets` a single time. With edge-to-edge enabled
         * that read can land before the first insets dispatch (right after a
         * shared-element transition, a re-attach, or a config change) and return
         * *zero* insets — leaving the action bar tucked under the status bar
         * (its buttons look "gone" while the bar's space remains) and the input
         * row behind the gesture nav bar, with nothing to correct it afterwards.
         * A listener that fires on every dispatch is self-correcting and works
         * identically on all API levels via [WindowInsetsCompat].
         */
        private fun applyInsetPaddings(activity: Activity, view: View, res: Int, flags: EnumSet<Flags>, customPaddingTop: Int, customPaddingBottom: Int) {
            try {
                // Cache the view's own paddings once so repeated dispatches add
                // the inset to the original padding instead of stacking onto an
                // already-inset value.
                val cached = view.getTag(res) as? Pair<*, *>
                val originalTop = cached?.first as? Int ?: view.paddingTop
                val originalBottom = cached?.second as? Int ?: view.paddingBottom
                if (cached == null) view.setTag(res, originalTop to originalBottom)

                ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
                    val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                    val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom

                    val baseTop = if (flags.contains(Flags.IGNORE_PADDINGS)) 0 else originalTop
                    val baseBottom = if (flags.contains(Flags.IGNORE_PADDINGS)) 0 else originalBottom

                    val topInsetPart = if (flags.contains(Flags.STATUS_BAR)) statusTop else 0
                    val bottomInsetPart = if (flags.contains(Flags.NAVIGATION_BAR)) navBottom else 0

                    v.setPadding(
                        0,
                        topInsetPart + baseTop + pxToDp(activity, customPaddingTop),
                        0,
                        bottomInsetPart + baseBottom + pxToDp(activity, customPaddingBottom)
                    )

                    insets
                }

                // Kick off the first dispatch (and re-dispatch if already attached).
                ViewCompat.requestApplyInsets(view)
            } catch (_: Exception) { /* unused */ }
        }

        private fun pxToDp(context: Context, px: Int): Int {
            return (px / context.resources.displayMetrics.density).toInt()
        }
    }
}
