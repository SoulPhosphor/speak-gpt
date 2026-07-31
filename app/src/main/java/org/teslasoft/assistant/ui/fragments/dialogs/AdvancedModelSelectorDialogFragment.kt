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

package org.teslasoft.assistant.ui.fragments.dialogs

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.logging.LogLevel
import com.aallam.openai.api.logging.Logger
import com.aallam.openai.api.model.Model
import com.aallam.openai.client.LoggingConfig
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import com.aallam.openai.client.OpenAIHost
import com.aallam.openai.client.RetryStrategy
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.loadingindicator.LoadingIndicator
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.FavoriteModelsPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject
import org.teslasoft.assistant.ui.adapters.FavoriteModelListAdapter
import org.teslasoft.assistant.ui.adapters.ModelListAdapter
import org.teslasoft.core.api.network.RequestNetwork
import kotlin.time.Duration.Companion.seconds

/**
 * Full-screen model picker (owner redesign, July 31 2026). Replaces the old
 * pop-up: it fills the screen, has a header with a back button, and opens on a
 * favorites-first landing — the current endpoint's favorites, a search box that
 * searches the endpoint's whole catalog, and a "View all" button that swaps the
 * screen to the full model list. Favorites are per-endpoint (see
 * [FavoriteModelsPreferences]); the image-generator variant keeps its previous
 * behavior of showing the full (capability-narrowed) catalog directly.
 */
class AdvancedModelSelectorDialogFragment : DialogFragment() {
    companion object {
        /** [endpointId], when non-blank, fetches models for that specific saved
         *  endpoint instead of the chat's own active endpoint (Preferences'
         *  apiEndpointId) — used by callers assigning a model to a feature that
         *  has its own independently-chosen endpoint (e.g. Memory Assistant).
         *
         *  [imageModels] selects the image-generator variant of this picker
         *  (image-generation-rebuild-plan.md §5/§10): the chat variant's
         *  name-based exclusions do NOT apply (they hide exactly the models
         *  that picker exists to show), the raw catalog is fetched so
         *  image-output capability information can narrow the list when the
         *  provider exposes any, and no model id is rejected merely because
         *  it is unfamiliar to the app. */
        fun newInstance(name: String, chatId: String, endpointId: String = "", imageModels: Boolean = false) : AdvancedModelSelectorDialogFragment {
            val advancedModelSelectorDialogFragment = AdvancedModelSelectorDialogFragment()

            val args = Bundle()
            args.putString("name", name)
            args.putString("chatId", chatId)
            args.putString("endpointId", endpointId)
            args.putBoolean("imageModels", imageModels)

            advancedModelSelectorDialogFragment.arguments = args

            return advancedModelSelectorDialogFragment
        }
    }

    private var modelList: ListView? = null
    private var progressBar: LoadingIndicator? = null
    private var selectorTitle: TextView? = null
    private var fieldSearch: TextInputEditText? = null
    private var btnBack: ImageButton? = null
    private var btnViewAll: MaterialButton? = null

    private var preferences: Preferences? = null
    private var apiEndpointPreferences: ApiEndpointPreferences? = null
    private var favoriteModelsPreferences: FavoriteModelsPreferences? = null

    private var apiEndpointObject: ApiEndpointObject? = null
    private var listener: OnModelSelectedListener? = null

    /** The endpoint this picker is choosing a model for (caller override, else
     *  the chat's active endpoint). Favorites and stars are scoped to it. */
    private var resolvedEndpointId: String = ""

    /** The endpoint's full catalog, populated once the fetch completes. */
    private var availableModels: ArrayList<String> = arrayListOf()
    private var catalogLoaded = false

    /** This endpoint's favorites (landing list). */
    private var favorites: ArrayList<Map<String, String>> = arrayListOf()

    /** false = favorites landing; true = the full "View all" list. */
    private var showingAll = false
    private var query = ""

    private var requestNetwork: RequestNetwork? = null

    private var mContext: Context? = null

    /** Image-generator picker variant (see [newInstance]). */
    private var imageMode = false

    override fun onAttach(context: Context) {
        super.onAttach(context)

        mContext = context
    }

    override fun onDetach() {
        super.onDetach()

        mContext = null
    }

    private var modelSelectedListener: ModelListAdapter.OnItemClickListener = object : ModelListAdapter.OnItemClickListener {
        override fun onItemClick(model: String) {
            listener?.onModelSelected(model)
            dismiss()
        }

        override fun onActionClick(model: String, endpointId: String, position: Int) {
            // Star in the all-models list adds to this endpoint's favorites.
            val m = FavoriteModelObject(model, endpointId)
            Toast.makeText(mContext ?: return, getString(R.string.label_added_to_favorites), Toast.LENGTH_SHORT).show()
            favoriteModelsPreferences?.addFavoriteModel(m)
            reloadFavorites()
        }
    }

    private var favoriteSelectedListener: FavoriteModelListAdapter.OnItemClickListener = object : FavoriteModelListAdapter.OnItemClickListener {
        override fun onItemClick(model: String, endpointId: String) {
            listener?.onModelSelected(model)
            dismiss()
        }

        override fun onActionClick(model: String, endpointId: String, position: Int) {
            // The action on a favorite removes it (one entry, whole store).
            favoriteModelsPreferences?.removeFavoriteModel(model, endpointId)
            reloadFavorites()
            render()
        }
    }

    private var requestListener: RequestNetwork.RequestListener = object : RequestNetwork.RequestListener {
        override fun onResponse(tag: String, message: String) {
            try {
                val parsed = com.google.gson.Gson().fromJson(message, com.google.gson.JsonObject::class.java)
                    ?: throw IllegalStateException("Empty response from provider.")

                val dataEl = parsed.get("data")
                if (dataEl == null || dataEl.isJsonNull || !dataEl.isJsonArray) {
                    showProviderError(message)
                    return
                }

                val ids = dataEl.asJsonArray.mapNotNull { el ->
                    if (!el.isJsonObject) return@mapNotNull null
                    val obj = el.asJsonObject
                    val id = obj.get("id")?.takeIf { !it.isJsonNull }?.asString
                        ?: return@mapNotNull null
                    // Image variant: capability metadata may narrow the list
                    // (§10) — an entry is dropped ONLY when its catalog entry
                    // explicitly says its outputs exclude image. Entries with
                    // no capability information always stay listed; metadata
                    // must never become a hard-coded name filter.
                    if (imageMode && !catalogAllowsImageOutput(obj)) return@mapNotNull null
                    id
                }

                if (ids.isEmpty()) {
                    showProviderError(message)
                    return
                }

                availableModels.addAll(ids)
                catalogLoaded = true
                render()
            } catch (e: Exception) {
                showProviderError(message, e)
            }
        }

        override fun onErrorResponse(tag: String, message: String) {
            showProviderError(message)
        }
    }

    /** True unless the catalog entry carries output-modality information
     *  that excludes image output (image-generation-rebuild-plan.md §10). */
    private fun catalogAllowsImageOutput(obj: com.google.gson.JsonObject): Boolean {
        val architecture = obj.get("architecture")
            ?.takeIf { it.isJsonObject }?.asJsonObject ?: return true
        val modalities = architecture.get("output_modalities")
            ?.takeIf { it.isJsonArray }?.asJsonArray ?: return true
        return modalities.any { m ->
            !m.isJsonNull && m.isJsonPrimitive && m.asString.equals("image", ignoreCase = true)
        }
    }

    private fun showProviderError(responseBody: String?, e: Exception? = null) {
        if (context == null) return
        val excerpt = (responseBody ?: "").take(400)
        val msg = buildString {
            // Plain-language cause first, so a human sees what to do before the
            // raw provider dump. The technical detail stays below, unchanged.
            append(plainLanguageCause(responseBody))
            append("\n\n")
            append("Couldn't read the models list from ")
            append(apiEndpointObject?.label ?: "this profile")
            append(".\n\nProvider returned:\n")
            append(if (excerpt.isBlank()) "(empty response)" else excerpt)
            if (e != null) {
                append("\n\nDetails: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        progressBar?.visibility = View.GONE
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.label_error)
            .setMessage(msg)
            .setPositiveButton(R.string.btn_ok) { _, _ -> }
            .setNeutralButton(R.string.btn_copy, null)
            .create()
        // Override the Copy button AFTER show so a copy does NOT close the
        // dialog — the user can copy and keep reading.
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL)?.setOnClickListener {
                val ctx = context ?: return@setOnClickListener
                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.label_error), msg))
                Toast.makeText(ctx, R.string.label_error_copied, Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    /** One plain sentence naming the most likely cause of a failed models
     *  fetch, shown above the raw provider response. Detects the cases we can
     *  recognise from the response itself; falls back to an honest "couldn't
     *  read it" line when the cause is genuinely unknown (§8: no invented
     *  causes). */
    private fun plainLanguageCause(responseBody: String?): String {
        val body = (responseBody ?: "").trim()

        if (body.isEmpty()) {
            return "The server sent back an empty response, so there was no model list to read. The server may be temporarily down, or the Base URL for this profile may be wrong."
        }

        val lower = body.lowercase()
        if (lower.startsWith("<!doctype") || lower.startsWith("<html") || lower.startsWith("<?xml") || lower.startsWith("<")) {
            return "The server sent back a web page instead of data. This almost always means the Base URL for this profile is wrong. For OpenRouter it must be https://openrouter.ai/api/v1/ — open this profile's settings and check the Base URL."
        }

        // A real error the provider reported in JSON (e.g. a rejected key).
        // Showing its own message is the truthful cause, not a guess.
        extractProviderMessage(body)?.let { providerMsg ->
            return "The server reported an error: \"$providerMsg\""
        }

        return "The server replied, but its response was not a model list the app could read. The full response is shown below so you can see what it sent."
    }

    /** Pulls a human-readable message out of a provider JSON error body of the
     *  common shapes `{"error":{"message":...}}` or `{"error":"..."}`. Returns
     *  null when the body is not JSON or carries no such message. */
    private fun extractProviderMessage(body: String): String? {
        return try {
            val json = com.google.gson.Gson().fromJson(body, com.google.gson.JsonObject::class.java) ?: return null
            val errEl = json.get("error") ?: return null
            when {
                errEl.isJsonObject -> errEl.asJsonObject.get("message")?.takeIf { it.isJsonPrimitive }?.asString
                errEl.isJsonPrimitive -> errEl.asString
                else -> null
            }?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_model_selector, container, false)
    }

    override fun onStart() {
        super.onStart()
        // Fill the screen: this is a full-screen selector, not a floating pop-up.
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        modelList = view.findViewById(R.id.voices_list)
        modelList?.divider = null
        selectorTitle = view.findViewById(R.id.selector_title)
        progressBar = view.findViewById(R.id.progressBar)
        fieldSearch = view.findViewById(R.id.field_search_text)
        btnBack = view.findViewById(R.id.btn_back)
        btnViewAll = view.findViewById(R.id.btn_view_all)

        val ctx = mContext ?: requireContext()

        imageMode = requireArguments().getBoolean("imageModels", false)
        selectorTitle?.text = getString(
            if (imageMode) R.string.label_select_image_model else R.string.label_select_ai_model
        )

        preferences = Preferences.getPreferences(ctx, requireArguments().getString("chatId").toString())
        apiEndpointPreferences = ApiEndpointPreferences.getApiEndpointPreferences(ctx)
        val endpointIdOverride = requireArguments().getString("endpointId").orEmpty()
        resolvedEndpointId = endpointIdOverride.ifEmpty { preferences?.getApiEndpointId() ?: "" }
        apiEndpointObject = apiEndpointPreferences?.getApiEndpoint(ctx, resolvedEndpointId)
        favoriteModelsPreferences = FavoriteModelsPreferences.getPreferences(ctx)

        reloadFavorites()

        btnBack?.setOnClickListener { handleBack() }

        btnViewAll?.setOnClickListener {
            showingAll = true
            fieldSearch?.setText("")
            query = ""
            render()
        }

        fieldSearch?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { /* unused */ }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s.toString().trim()
                render()
            }
            override fun afterTextChanged(s: Editable?) { /* unused */ }
        })

        // Image mode has no favorites landing — it goes straight to the full
        // (capability-narrowed) catalog, as it did before this redesign.
        if (imageMode) showingAll = true

        render()
        startCatalogFetch()
    }

    /** System/hardware back: from the full list return to the favorites
     *  landing; from the landing, close the picker. */
    private fun handleBack() {
        if (showingAll && !imageMode) {
            showingAll = false
            fieldSearch?.setText("")
            query = ""
            render()
        } else {
            dismiss()
        }
    }

    private fun reloadFavorites() {
        favorites = favoriteModelsPreferences?.getFavoriteModels(resolvedEndpointId) ?: arrayListOf()
    }

    /** Decide, from the current mode/search/catalog state, which list to show
     *  and whether the "View all" button and spinner belong on screen. */
    private fun render() {
        if (!isAdded) return
        val allMode = imageMode || showingAll || query.isNotEmpty()

        btnViewAll?.visibility = if (!imageMode && !showingAll && query.isEmpty()) View.VISIBLE else View.GONE
        progressBar?.visibility = if (allMode && !catalogLoaded) View.VISIBLE else View.GONE

        if (allMode) {
            val filtered = if (query.isEmpty()) {
                ArrayList(availableModels)
            } else {
                // Preserve the existing match rule (owner: keep search as it is).
                ArrayList(availableModels.filter { item -> item == query || item.contains(query) || query.contains(item) })
            }
            val adapter = ModelListAdapter(requireContext(), filtered, requireArguments().getString("chatId").toString(), apiEndpointObject?.id ?: "")
            adapter.setOnItemClickListener(modelSelectedListener)
            modelList?.adapter = adapter
        } else {
            val adapter = FavoriteModelListAdapter(requireContext(), ArrayList(favorites), requireArguments().getString("chatId").toString())
            adapter.setOnItemClickListener(favoriteSelectedListener)
            modelList?.adapter = adapter
        }
    }

    /** Fetch the endpoint's catalog: the OpenAI SDK path first (with the chat
     *  variant's name exclusions), falling back to a plain GET; image mode uses
     *  the raw request directly so capability metadata survives. */
    private fun startCatalogFetch() {
        val endpoint = apiEndpointObject ?: return
        val extraHeaders: Map<String, String> = when (endpoint.authType) {
            ApiEndpointObject.AUTH_X_API_KEY -> mapOf("x-api-key" to endpoint.apiKey)
            ApiEndpointObject.AUTH_API_KEY -> mapOf("api-key" to endpoint.apiKey)
            else -> emptyMap()
        }

        val config = OpenAIConfig(
            token = endpoint.apiKey,
            logging = LoggingConfig(LogLevel.None, Logger.Simple),
            timeout = Timeout(socket = 30.seconds),
            organization = null,
            headers = extraHeaders,
            host = OpenAIHost(endpoint.host),
            proxy = null,
            retry = RetryStrategy()
        )
        val ai = OpenAI(config)

        CoroutineScope(Dispatchers.Main).launch {
            if (imageMode) {
                startRawModelsRequest()
                return@launch
            }
            try {
                val models: List<Model> = ai.models()
                for (model in models) {
                    if (!model.id.id.contains("tts") && !model.id.id.contains("dall") && !model.id.id.contains("whisper") && !model.id.id.contains("embedding") && !model.id.id.contains("vision")) {
                        availableModels.add(model.id.id)
                    } else if (model.id.id.contains("ft:") || model.id.id.contains(":ft")) {
                        availableModels.add(model.id.id)
                    }
                }
                catalogLoaded = true
                render()
            } catch (_: Exception) {
                startRawModelsRequest()
            }
        }
    }

    /** Plain GET {base}models with the endpoint's auth mode; the parsed
     *  response lands in [requestListener]. Used as the fallback when the
     *  SDK path fails, and as the primary path in image mode. */
    private fun startRawModelsRequest() {
        requestNetwork = RequestNetwork((mContext as Activity?) ?: return)
        val authHeaders = HashMap<String, Any>()
        val apiKey = apiEndpointObject?.apiKey ?: ""
        when (apiEndpointObject?.authType) {
            ApiEndpointObject.AUTH_X_API_KEY -> authHeaders["x-api-key"] = apiKey
            ApiEndpointObject.AUTH_API_KEY -> authHeaders["api-key"] = apiKey
            else -> authHeaders["Authorization"] = "Bearer $apiKey"
        }
        requestNetwork?.setHeaders(authHeaders)
        val base = (apiEndpointObject?.host ?: "").let { if (it.isBlank() || it.endsWith("/")) it else "$it/" }
        requestNetwork?.startRequestNetwork("GET", base + "models", "A", requestListener)
    }

    fun interface OnModelSelectedListener {
        fun onModelSelected(model: String)
    }

    fun setModelSelectedListener(listener: OnModelSelectedListener) {
        this.listener = listener
    }
}
