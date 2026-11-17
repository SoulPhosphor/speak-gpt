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

package org.teslasoft.assistant.ui.activities.memory

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.memory.context.HelperFailure
import org.teslasoft.assistant.usage.TokenUsageAccounting
import org.teslasoft.assistant.util.providerDetailBlock

/**
 * The Memory Context Helper's error pop-up (conversation_recall_plan.md M.4
 * item 4), following the app's error-detail standard: the category as a
 * heading, a plain explanation, that the message was still sent (only once
 * its reply finished), then the actual sanitized provider or technical error.
 * It stays until the user presses Okay; nothing in it stops or delays the chat
 * or hands-free voice.
 */
object ContextHelperErrorDialog {

    fun show(activity: Activity, failure: HelperFailure, delivered: Boolean) {
        if (activity.isFinishing || activity.isDestroyed) return
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_context_helper_error, null)
        val heading = activity.getString(titleRes(failure.category))
        val explanation = activity.getString(messageRes(failure.category))
        val status = if (delivered) activity.getString(R.string.memory_context_helper_error_status) else null
        val details = details(activity, failure)
        view.findViewById<TextView>(R.id.context_helper_error_heading).text = heading
        view.findViewById<TextView>(R.id.context_helper_error_message).text = explanation
        view.findViewById<TextView>(R.id.context_helper_error_status).apply {
            text = status.orEmpty()
            visibility = if (status == null) View.GONE else View.VISIBLE
        }
        view.findViewById<TextView>(R.id.context_helper_error_details).text = details

        val dialog = MaterialAlertDialogBuilder(activity, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.memory_context_helper_error_title)
            .setView(view)
            .setCancelable(false)
            .create()
        view.findViewById<View>(R.id.btn_context_helper_error_copy).setOnClickListener {
            val title = activity.getString(R.string.memory_context_helper_error_title)
            val text = listOfNotNull(title, heading, explanation, status, details).joinToString("\n\n")
            (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText(title, text))
        }
        view.findViewById<View>(R.id.btn_context_helper_error_okay).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /** The evidence under the explanation: the provider's own error and its
     *  details, or what the app observed itself. Never invented. */
    fun details(context: Context, failure: HelperFailure): String {
        val notReported = TokenUsageAccounting.PROVIDER_NOT_REPORTED
        failure.provider?.let { p ->
            val block = p.result.providerDetailBlock(
                context,
                exceptionMessage = p.exceptionMessage,
                apiProvider = failure.profile ?: notReported,
                modelServiceProvider = p.modelServiceProvider ?: notReported,
                model = failure.model ?: notReported,
                function = context.getString(R.string.memory_context_helper_function),
                rawProviderMessage = p.providerMessage,
                providerEvidence = p.evidence
            )
            // With no response from a server the block cannot quote one, so
            // the client's own error follows it rather than being lost.
            val clientError = p.exceptionMessage?.takeIf { it !in block }
            return listOfNotNull(block, clientError).joinToString("\n")
        }
        val lines = ArrayList<String>()
        failure.profile?.let { lines += context.getString(R.string.summarizer_errors_profile_line, it) }
        failure.model?.let { lines += context.getString(R.string.summarizer_errors_model_line, it) }
        failure.local?.let { local -> localLine(context, local)?.let { lines += it } }
        return lines.joinToString("\n")
    }

    /** Null only for a too-long reply whose limit the app no longer knows. */
    private fun localLine(context: Context, local: HelperFailure.Local): String? = when (local) {
        HelperFailure.Local.NoModel -> context.getString(R.string.memory_context_helper_detail_no_model)
        HelperFailure.Local.ProfileGone -> context.getString(R.string.memory_context_helper_detail_profile_gone)
        HelperFailure.Local.RoutingNotSetUp -> context.getString(R.string.memory_context_helper_detail_routing)
        is HelperFailure.Local.NoReplyWithin ->
            context.getString(R.string.memory_context_helper_detail_no_reply, local.seconds)
        HelperFailure.Local.ReplyEmpty -> context.getString(R.string.memory_context_helper_detail_reply_empty)
        HelperFailure.Local.ReplyMultipleLines ->
            context.getString(R.string.memory_context_helper_detail_reply_lines)
        is HelperFailure.Local.ReplyTooLong -> local.limitTokens?.let {
            context.getString(R.string.memory_context_helper_detail_reply_too_long, it)
        }
    }

    fun titleRes(category: HelperFailure.Category): Int = when (category) {
        HelperFailure.Category.CONFIGURATION_NEEDED -> R.string.memory_context_helper_err_configuration_title
        HelperFailure.Category.TIME_ALLOWANCE -> R.string.memory_context_helper_err_time_allowance_title
        HelperFailure.Category.UNREACHABLE -> R.string.summarizer_err_unreachable_title
        HelperFailure.Category.CONNECT_TIMEOUT -> R.string.summarizer_err_connect_timeout_title
        HelperFailure.Category.RESPONSE_TIMEOUT -> R.string.summarizer_err_response_timeout_title
        HelperFailure.Category.ACCESS_REJECTED -> R.string.summarizer_err_access_rejected_title
        HelperFailure.Category.MODEL_UNAVAILABLE -> R.string.summarizer_err_model_unavailable_title
        HelperFailure.Category.RATE_LIMIT -> R.string.summarizer_err_rate_limit_title
        HelperFailure.Category.QUOTA -> R.string.memory_context_helper_err_quota_title
        HelperFailure.Category.TOO_LARGE -> R.string.memory_context_helper_err_too_large_title
        HelperFailure.Category.REJECTED -> R.string.memory_context_helper_err_rejected_title
        HelperFailure.Category.SERVICE_ERROR -> R.string.memory_context_helper_err_service_title
        HelperFailure.Category.UNUSABLE -> R.string.memory_context_helper_err_unusable_title
        HelperFailure.Category.UNEXPECTED -> R.string.memory_context_helper_err_unexpected_title
    }

    fun messageRes(category: HelperFailure.Category): Int = when (category) {
        HelperFailure.Category.CONFIGURATION_NEEDED -> R.string.memory_context_helper_err_configuration
        HelperFailure.Category.TIME_ALLOWANCE -> R.string.memory_context_helper_err_time_allowance
        HelperFailure.Category.UNREACHABLE -> R.string.memory_context_helper_err_unreachable
        HelperFailure.Category.CONNECT_TIMEOUT -> R.string.memory_context_helper_err_connect_timeout
        HelperFailure.Category.RESPONSE_TIMEOUT -> R.string.memory_context_helper_err_response_timeout
        HelperFailure.Category.ACCESS_REJECTED -> R.string.summarizer_err_access_rejected
        HelperFailure.Category.MODEL_UNAVAILABLE -> R.string.memory_context_helper_err_model_unavailable
        HelperFailure.Category.RATE_LIMIT -> R.string.memory_context_helper_err_rate_limit
        HelperFailure.Category.QUOTA -> R.string.memory_context_helper_err_quota
        HelperFailure.Category.TOO_LARGE -> R.string.memory_context_helper_err_too_large
        HelperFailure.Category.REJECTED -> R.string.memory_context_helper_err_rejected
        HelperFailure.Category.SERVICE_ERROR -> R.string.memory_context_helper_err_service
        HelperFailure.Category.UNUSABLE -> R.string.memory_context_helper_err_unusable
        HelperFailure.Category.UNEXPECTED -> R.string.memory_context_helper_err_unexpected
    }
}
