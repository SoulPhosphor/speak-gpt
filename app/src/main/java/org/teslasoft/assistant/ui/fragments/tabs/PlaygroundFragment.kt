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

package org.teslasoft.assistant.ui.fragments.tabs

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import android.text.Editable
import android.text.TextWatcher
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.aallam.ktoken.Encoding
import com.aallam.ktoken.Tokenizer
import com.aallam.openai.api.chat.ChatCompletionChunk
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.logging.LogLevel
import com.aallam.openai.api.logging.Logger
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.LoggingConfig
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import com.aallam.openai.client.OpenAIHost
import com.aallam.openai.client.RetryStrategy
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.loadingindicator.LoadingIndicator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.LogitBiasPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.ui.fragments.dialogs.QuickSettingsBottomSheetDialogFragment
import org.teslasoft.assistant.ui.fragments.dialogs.ReportAIContentBottomSheet
import org.teslasoft.assistant.util.WindowInsetsUtil
import org.teslasoft.assistant.util.connectionAbortMessage
import org.teslasoft.assistant.playground.PlaygroundRunner
import org.teslasoft.assistant.playground.PlaygroundSessionStore
import java.util.EnumSet
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

class PlaygroundFragment : Fragment() {

    interface PendingCommitHost {
        /** Called before Run changes UI or starts network work. */
        fun commitPendingPlaygroundTurn(input: String): Boolean
    }

    private var btnSettings: ImageButton? = null
    private var layoutBottom: ConstraintLayout? = null
    private var btnRun: FloatingActionButton? = null
    private var btnStop: FloatingActionButton? = null
    private var btnTokenize: FloatingActionButton? = null
    private var clearIn: ImageButton? = null
    private var clearOut: ImageButton? = null
    private var runLoader: LoadingIndicator? = null
    private var editTextIn: EditText? = null
    private var editTextOut: EditText? = null
    private var btnReport: ImageButton? = null

    private var apiEndpointPreferences: ApiEndpointPreferences? = null
    private var logitBiasPreferences: LogitBiasPreferences? = null
    private var preferences: Preferences? = null

    private var apiEndpoint: ApiEndpointObject? = null
    private var output = ""
    private var model = ""

    private var mContext: Context? = null

    private var runner: PlaygroundRunner? = null
    private var sessionStore: PlaygroundSessionStore? = null
    private var chatId: String = ""
    private var embedded: Boolean = false
    private var pendingConversation: Boolean = false

    override fun onAttach(context: Context) {
        super.onAttach(context)

        mContext = context

        if (rootView != null) WindowInsetsUtil.adjustPaddings((mContext as Activity?) ?: return, rootView, R.id.root, EnumSet.of(WindowInsetsUtil.Companion.Flags.STATUS_BAR, WindowInsetsUtil.Companion.Flags.IGNORE_PADDINGS))
    }

    override fun onDetach() {
        super.onDetach()

        mContext = null
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        chatId = arguments?.getString(ARG_CHAT_ID).orEmpty()
        embedded = arguments?.getBoolean(ARG_EMBEDDED, false) == true
        pendingConversation = arguments?.getBoolean(ARG_PENDING, false) == true
        return inflater.inflate(R.layout.fragment_playground, container, false)
    }

    private var rootView: View? = null
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rootView = view
        if (!embedded) {
            WindowInsetsUtil.adjustPaddings((mContext as Activity?) ?: return, rootView, R.id.root, EnumSet.of(WindowInsetsUtil.Companion.Flags.STATUS_BAR, WindowInsetsUtil.Companion.Flags.IGNORE_PADDINGS))
        }

        btnRun = view.findViewById(R.id.btn_run)
        btnStop = view.findViewById(R.id.btn_stop)
        btnTokenize = view.findViewById(R.id.btn_tokenize)
        clearIn = view.findViewById(R.id.clear_in)
        clearOut = view.findViewById(R.id.clear_out)
        runLoader = view.findViewById(R.id.run_loader)
        editTextIn = view.findViewById(R.id.editTextIn)
        editTextOut = view.findViewById(R.id.editTextOut)
        btnSettings = view.findViewById(R.id.btn_settings)
        layoutBottom = view.findViewById(R.id.layout_bottom)
        btnReport = view.findViewById(R.id.btn_report_playground_abuse)

        runLoader?.visibility = View.GONE

        Thread {
            val th = Thread {
                while (mContext == null) { /* wait */ }
            }

            th.start()
            th.join()

            try {
                initialize(mContext ?: return@Thread)
            } catch (_: NullPointerException) { /* ignored */ }
        }.start()
    }

    @SuppressLint("SetTextI18n")
    private fun initialize(context: Context) {
        preferences = Preferences.getPreferences(context, chatId)
        apiEndpointPreferences = ApiEndpointPreferences.getApiEndpointPreferences(context)
        logitBiasPreferences = LogitBiasPreferences(context, preferences?.getLogitBiasesConfigId() ?: return)
        apiEndpoint = apiEndpointPreferences?.getApiEndpoint(context, preferences?.getApiEndpointId() ?: return)
        sessionStore = PlaygroundSessionStore(context, chatId)

        (mContext as Activity?)?.runOnUiThread {
            editTextIn?.setText(sessionStore?.input().orEmpty())
            editTextOut?.setText(sessionStore?.output().orEmpty())
            editTextIn?.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    sessionStore?.saveInput(s?.toString().orEmpty())
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }

        btnSettings?.background = getDisabledDrawable(ResourcesCompat.getDrawable(context.resources, R.drawable.btn_accent_tonal, context.theme)!!)
        btnReport?.background = getDisabledDrawable(ResourcesCompat.getDrawable(context.resources, R.drawable.btn_accent_tonal, context.theme)!!)

        if (isDarkThemeEnabled() && preferences!!.getAmoledPitchBlack()) {
            layoutBottom?.background = ResourcesCompat.getDrawable(context.resources, R.drawable.playground_bottom_amoled, context.theme)
        } else {
            layoutBottom?.background = getDisabledDrawableV2(ResourcesCompat.getDrawable(context.resources, R.drawable.playground_bottom, context.theme)!!)
        }

        model = preferences?.getModel() ?: "unknown"

        btnSettings?.setOnClickListener {
            val quickSettingsBottomSheetDialogFragment = QuickSettingsBottomSheetDialogFragment
                .newInstance(
                    chatId,
                    -1,
                    -1,
                    0.0f,
                    0.0f
                )
            quickSettingsBottomSheetDialogFragment.setOnUpdateListener(object : QuickSettingsBottomSheetDialogFragment.OnUpdateListener {
                override fun onUpdate() {
                    /* for future */
                }

                override fun onForceUpdate() {
                    (mContext as Activity?)?.recreate()
                }
            })
            quickSettingsBottomSheetDialogFragment.show((mContext as FragmentActivity?)?.supportFragmentManager!!, "QuickSettingsBottomSheetDialogFragment")
        }

        btnRun?.setOnClickListener {
            val input = editTextIn?.text?.toString().orEmpty()
            if (pendingConversation) {
                val committed = (activity as? PendingCommitHost)
                    ?.commitPendingPlaygroundTurn(input) == true
                if (!committed) return@setOnClickListener
                pendingConversation = false
            }
            runLoader?.visibility = View.VISIBLE
            btnStop?.visibility = View.VISIBLE
            btnRun?.visibility = View.GONE

            lifecycleScope.launch {
                runAIRequest()

                btnStop?.setOnClickListener {
                    runLoader?.visibility = View.GONE
                    btnStop?.visibility = View.GONE
                    btnRun?.visibility = View.VISIBLE
                    cancel()
                }
            }
        }

        btnReport?.setOnClickListener {
            if (editTextOut?.text?.toString()?.trim() == "") {
                Toast.makeText(context, "Nothing to report", Toast.LENGTH_SHORT).show()
            } else {
                val reportAIContentBottomSheet = ReportAIContentBottomSheet.newInstance(
                    editTextOut?.text?.toString() ?: "",
                    editTextIn?.text?.toString() ?: ""
                )
                reportAIContentBottomSheet.show(parentFragmentManager, "ReportAIContentBottomSheet")
            }
        }

        btnTokenize?.setOnClickListener {
            runLoader?.visibility = View.VISIBLE
            btnStop?.visibility = View.VISIBLE
            btnRun?.visibility = View.GONE
            btnTokenize?.isEnabled = false

            CoroutineScope(Dispatchers.Main).launch {
                val tokenizer = try {
                    Tokenizer.of(model = preferences?.getModel() ?: return@launch)
                } catch (_: Exception) {
                    Tokenizer.of(encoding = Encoding.CL100K_BASE)
                }

                val tokens = tokenizer.encode(editTextIn?.text.toString())

                if (tokens.isEmpty()) {
                    Toast.makeText(context, "Please enter some text to the input textarea.", Toast.LENGTH_SHORT).show()
                } else {
                    val tokenString: StringBuilder = StringBuilder()
                    for (i in tokens) {
                        tokenString.append(i.toString())
                        tokenString.append(", ")
                    }

                    tokenString.deleteCharAt(tokenString.length - 2)

                    val response = "Chars count: ${editTextIn?.text?.toList()?.size}\nTokens count: ${tokens.size}\nTokens: ${tokenString.toString()}"

                    editTextOut?.setText(response)
                }

                runLoader?.visibility = View.GONE
                btnStop?.visibility = View.GONE
                btnRun?.visibility = View.VISIBLE
                btnTokenize?.isEnabled = true
            }
        }

        clearIn?.setOnClickListener {
            editTextIn?.setText("")
            Toast.makeText(requireActivity(), "Input cleared", Toast.LENGTH_SHORT).show()
        }

        clearOut?.setOnClickListener {
            editTextOut?.setText("")
            Toast.makeText(requireActivity(), "Output cleared", Toast.LENGTH_SHORT).show()
        }

        runner = PlaygroundRunner(preferences!!, logitBiasPreferences!!, apiEndpoint!!)
    }

    private suspend fun runAIRequest() {
        output = ""
        try {
            output = runner!!.run(editTextIn?.text.toString()) { streamed ->
                output = streamed
                editTextOut?.setText(streamed)
                sessionStore?.saveOutput(streamed)
            }
            editTextOut?.setText(output)
            sessionStore?.saveOutput(output)

            runLoader?.visibility = View.GONE
            btnStop?.visibility = View.GONE
            btnRun?.visibility = View.VISIBLE
        } catch (_: CancellationException) {
            (mContext as Activity?)?.runOnUiThread {
                runLoader?.visibility = View.GONE
                btnStop?.visibility = View.GONE
                btnRun?.visibility = View.VISIBLE
            }
        } catch (e: Exception) {
            val response = when {
                e.stackTraceToString().contains("invalid model") -> {
                    getString(R.string.prompt_no_model_provided)
                }
                e.stackTraceToString().contains("does not exist") -> {
                    String.format(getString(R.string.prompt_model_not_available), model)
                }
                e.stackTraceToString().contains("Connect timeout has expired") || e.stackTraceToString().contains("SocketTimeoutException") -> {
                    getString(R.string.prompt_timed_out)
                }
                e.stackTraceToString().contains("This model's maximum") -> {
                    getString(R.string.prompt_max_tokens_error)
                }
                e.stackTraceToString().contains("No address associated with hostname") -> {
                    getString(R.string.prompt_offline)
                }
                e.stackTraceToString().contains("Incorrect API key") -> {
                    getString(R.string.prompt_key_invalid)
                }
                e.stackTraceToString().contains("you must provide a model") -> {
                    getString(R.string.prompt_no_model)
                }
                e.stackTraceToString().contains("Software caused connection abort") -> {
                    connectionAbortMessage(apiEndpoint?.label, apiEndpoint?.host, model, e.message)
                }
                e.stackTraceToString().contains("You exceeded your current quota") -> {
                    getString(R.string.prompt_quota_reached)
                }
                else -> {
                    e.stackTraceToString() + "\n\n" + e.message
                }
            }

            // A failed run always shows its error — never hidden.
            output = "${output}\n\n${getString(R.string.prompt_show_error)}\n\n$response"
            editTextOut?.setText(output)
            sessionStore?.saveOutput(output)

            (mContext as Activity?)?.runOnUiThread {
                runLoader?.visibility = View.GONE
                btnStop?.visibility = View.GONE
                btnRun?.visibility = View.VISIBLE
            }
        }
    }

    private fun getDisabledDrawable(drawable: Drawable) : Drawable {
        DrawableCompat.setTint(DrawableCompat.wrap(drawable), getDisabledColor())
        return drawable
    }

    private fun getDisabledColor() : Int {
        return if (isDarkThemeEnabled() && preferences!!.getAmoledPitchBlack()) {
            ResourcesCompat.getColor(mContext?.resources!!, R.color.amoled_accent_50,  mContext?.theme)
        } else if (mContext != null) {
            SurfaceColors.SURFACE_5.getColor(mContext!!)
        } else 0
    }

    private fun getDisabledDrawableV2(drawable: Drawable) : Drawable {
        DrawableCompat.setTint(DrawableCompat.wrap(drawable), getDisabledColorV2())
        return drawable
    }

    private fun getDisabledColorV2() : Int {
        return if (isDarkThemeEnabled() && preferences!!.getAmoledPitchBlack()) {
            ResourcesCompat.getColor(mContext?.resources!!, R.color.amoled_accent_50,  mContext?.theme)
        } else if (mContext != null) {
            SurfaceColors.SURFACE_1.getColor(mContext!!)
        } else 0
    }

    private fun isDarkThemeEnabled(): Boolean {
        return when (mContext?.resources?.configuration?.uiMode?.and(Configuration.UI_MODE_NIGHT_MASK)) {
            Configuration.UI_MODE_NIGHT_YES -> true
            Configuration.UI_MODE_NIGHT_NO -> false
            Configuration.UI_MODE_NIGHT_UNDEFINED -> false
            else -> false
        }
    }

    companion object {
        private const val ARG_CHAT_ID = "chatId"
        private const val ARG_EMBEDDED = "embedded"
        private const val ARG_PENDING = "pendingConversation"

        fun embedded(chatId: String, pending: Boolean): PlaygroundFragment =
            PlaygroundFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_CHAT_ID, chatId)
                    putBoolean(ARG_EMBEDDED, true)
                    putBoolean(ARG_PENDING, pending)
                }
            }
    }
}
