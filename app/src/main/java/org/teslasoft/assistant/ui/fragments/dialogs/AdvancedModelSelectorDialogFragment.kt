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
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.AbsListView
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
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
import org.teslasoft.assistant.preferences.ChatModelRouting
import org.teslasoft.assistant.preferences.ModelPickerScope
import org.teslasoft.assistant.preferences.RecentModelsPreferences
import org.teslasoft.assistant.preferences.SummarizerModelRouting
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject
import org.teslasoft.assistant.providers.OnlyProviderAvailability
import org.teslasoft.assistant.providers.OnlyProviderAvailabilityClient
import org.teslasoft.assistant.ui.adapters.FavoriteModelListAdapter
import org.teslasoft.assistant.ui.adapters.ModelListAdapter
import org.teslasoft.assistant.ui.activities.ChooseProviderActivity
import org.teslasoft.assistant.ui.adapters.SavedModelPickerAdapter
import org.teslasoft.assistant.ui.util.ScreenChrome
import org.teslasoft.assistant.preferences.models.ModelCleanupReportStore
import org.teslasoft.assistant.preferences.models.ModelIdentity
import org.teslasoft.assistant.providers.ModelSupportedParametersStore
import org.teslasoft.assistant.providers.RecentOnlyProviders
import org.teslasoft.assistant.providers.RecentOnlyProvidersStore
import org.teslasoft.assistant.reasoning.EndpointReasoningCapability
import org.teslasoft.assistant.reasoning.EndpointReasoningCapabilityIndex
import org.teslasoft.assistant.reasoning.ReasoningProviderPath
import org.teslasoft.core.api.network.RequestNetwork
import kotlin.time.Duration.Companion.seconds

/** Full-screen model picker for chat, endpoint catalogs, and feature-specific selection. */
class AdvancedModelSelectorDialogFragment : DialogFragment() {
    companion object {
        private const val ARG_START_WITH_ALL_MODELS = "startWithAllModels"
        private const val ARG_RAW_MODEL_CATALOG = "rawModelCatalog"
        private const val ARG_CURRENT_CHAT_MODEL = "currentChatModel"
        private const val ARG_CHAT_MODEL_PICKER = "chatModelPicker"
        private const val ARG_SCOPE = "scope"
        private const val STATE_PICKER_TAB = "pickerTab"
        private const val STATE_CATALOG_PROFILE = "catalogProfile"
        private const val STATE_ROUTING_MODEL = "routingModel"
        private const val STATE_ROUTING_ENDPOINT = "routingEndpoint"
        const val CHAT_SELECTION_RESULT = "chatModelPickerSelection"
        const val SELECTED_MODEL = "modelId"
        const val SELECTED_ENDPOINT = "endpointId"
        /** Fragment result of the Summarizer's picker after a model is chosen
         *  (already saved as the Summarizer's model). */
        const val SUMMARIZER_SELECTION_RESULT = "summarizerModelPickerSelection"

        fun newChatModelPickerInstance(name: String, chatId: String) =
            newInstance(name, chatId).apply {
                requireArguments().putBoolean(ARG_CHAT_MODEL_PICKER, true)
            }

        /**
         * Select Summarizer Model: the chat picker's Favorites, Recent and View
         * All, but with the Summarizer's own lists. Picking a model saves it
         * (with its routing) as the Summarizer's model.
         */
        fun newSummarizerModelPickerInstance(endpointId: String) =
            newInstance("", "", endpointId).apply {
                requireArguments().putBoolean(ARG_CHAT_MODEL_PICKER, true)
                requireArguments().putString(ARG_SCOPE, ModelPickerScope.SUMMARIZER.name)
            }

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

        /**
         * Provider-wide entry point. It uses the same selector screen, fetch,
         * search, rows, and selection result as [newInstance], but opens on the
         * endpoint's complete model catalog instead of its favorites landing.
         * Back closes the screen rather than stepping into favorites.
         */
        fun newAllModelsInstance(
            name: String,
            chatId: String,
            endpointId: String,
            /** Whose favorites a starred model is added to. */
            scope: ModelPickerScope = ModelPickerScope.CHAT
        ): AdvancedModelSelectorDialogFragment =
            newInstance(name, chatId, endpointId).apply {
                requireArguments().putBoolean(ARG_START_WITH_ALL_MODELS, true)
                requireArguments().putString(ARG_SCOPE, scope.name)
            }

        /**
         * Model Rule entry point: keep the Favorites-first landing, but make
         * View all/search use every exact id advertised by the endpoint rather
         * than the chat picker's name-based exclusions.
         */
        fun newModelRuleTargetInstance(
            chatId: String,
            endpointId: String,
            currentChatModel: String = ""
        ): AdvancedModelSelectorDialogFragment =
            newInstance(name = "", chatId = chatId, endpointId = endpointId).apply {
                requireArguments().putBoolean(ARG_RAW_MODEL_CATALOG, true)
                requireArguments().putString(ARG_CURRENT_CHAT_MODEL, currentChatModel)
            }
    }

    private var modelList: ListView? = null
    private var progressBar: LoadingIndicator? = null
    private var selectorTitle: TextView? = null
    private var fieldSearch: TextInputEditText? = null
    private var btnBack: ImageButton? = null
    private var btnViewAll: MaterialButton? = null
    private var btnUseCurrentModel: MaterialButton? = null
    private var btnFavorites: MaterialButton? = null
    private var btnRecent: MaterialButton? = null

    private enum class PickerTab { FAVORITES, RECENT, VIEW_ALL }

    /** The Favorites / Recent / View All picker (chat or Summarizer). */
    private val chatPicker: Boolean
        get() = requireArguments().getBoolean(ARG_CHAT_MODEL_PICKER)
    private val scope: ModelPickerScope
        get() = ModelPickerScope.from(requireArguments().getString(ARG_SCOPE))
    /** Select Summarizer Model (the Favorites / Recent / View All picker in
     *  the Summarizer's scope). */
    private val summarizerPicker: Boolean
        get() = chatPicker && scope == ModelPickerScope.SUMMARIZER
    private var pickerTab = PickerTab.FAVORITES
    private var savedModelAdapter: SavedModelPickerAdapter? = null
    private var catalog: ModelPickerCatalog? = null
    private var catalogHeaderAttached = false
    private var catalogReasoningIndex: EndpointReasoningCapabilityIndex? = null
    private var catalogReasoningProfile: String? = null
    private var savedModels = emptyMap<ModelIdentity, Map<String, String>>()
    private var favoriteRows = emptyList<SavedModelPickerAdapter.Row>()
    private var recentRows = emptyList<SavedModelPickerAdapter.Row>()

    private var preferences: Preferences? = null
    private var apiEndpointPreferences: ApiEndpointPreferences? = null
    private var favoriteModelsPreferences: FavoriteModelsPreferences? = null

    private var apiEndpointObject: ApiEndpointObject? = null
    private var reasoningCapabilityIndex =
        org.teslasoft.assistant.reasoning.EndpointReasoningCapability.index(null)
    private var listener: OnModelSelectedListener? = null

    /** The endpoint this picker is choosing a model for (caller override, else
     *  the chat's active endpoint). Favorites and stars are scoped to it. */
    private var resolvedEndpointId: String = ""

    /** The endpoint's full catalog, populated once the fetch completes. */
    private var availableModels: ArrayList<String> = arrayListOf()
    private var catalogLoaded = false
    private var checkingProvider = false

    /** This endpoint's favorites (landing list). */
    private var favorites: ArrayList<Map<String, String>> = arrayListOf()

    /** false = favorites landing; true = the full "View all" list. */
    private var showingAll = false
    /** True when the caller's purpose is the provider-wide catalog itself. */
    private var startsWithAllModels = false
    private var rawModelCatalog = false
    private var currentChatModel = ""
    private var query = ""

    private var requestNetwork: RequestNetwork? = null

    private var mContext: Context? = null

    /** Image-generator picker variant (see [newInstance]). */
    private var imageMode = false

    /**
     * Only the ordinary chat model picker performs the interactive Only-provider
     * check. Provider-wide/model-rule/image pickers have different jobs and must
     * not suddenly acquire a chat-routing warning.
     */
    private var checkOnlyProviderOnSelection = false
    private var providerAvailabilityGeneration = 0
    /** A successful routing result completes the model pick for this target. */
    private var pendingRoutingTarget: ModelIdentity? = null

    /** How a chat-picker selection sets the chat's provider routing. */
    private sealed class RoutingChoice {
        /** The model's saved favorite routing (or Automatic). */
        object Saved : RoutingChoice()
        /** The chat uses this routing (a Recent entry or a trial). */
        class Use(val routing: FavoriteModelObject) : RoutingChoice()
        /** Already saved by the provider screen; leave it. */
        object Keep : RoutingChoice()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)

        mContext = context
    }

    override fun onDetach() {
        super.onDetach()

        mContext = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A match-parent view inside the default DialogFragment theme is still
        // a floating window with dialog insets. Use the app's normal screen
        // theme so the shared action bar and content genuinely fill the screen.
        setStyle(STYLE_NORMAL, R.style.UI_Material)
        pickerTab = PickerTab.entries.firstOrNull {
            it.name == savedInstanceState?.getString(STATE_PICKER_TAB)
        } ?: PickerTab.FAVORITES
        val routingEndpoint = savedInstanceState?.getString(STATE_ROUTING_ENDPOINT)
        val routingModel = savedInstanceState?.getString(STATE_ROUTING_MODEL)
        if (routingEndpoint != null && routingModel != null) {
            pendingRoutingTarget = ModelIdentity(routingEndpoint, routingModel)
        }
    }

    private var modelSelectedListener: ModelListAdapter.OnItemClickListener = object : ModelListAdapter.OnItemClickListener {
        override fun onItemClick(model: String) {
            handleModelSelection(model)
        }

        override fun onActionClick(model: String, endpointId: String, position: Int) {
            // Star in the all-models list adds to this endpoint's favorites.
            val m = FavoriteModelObject(model, endpointId)
            Toast.makeText(mContext ?: return, getString(R.string.label_added_to_favorites), Toast.LENGTH_SHORT).show()
            favoriteModelsPreferences?.addFavoriteModel(m)
            reloadFavorites()
        }
    }

    private val chooseProviderLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        reloadFavorites()
        render()

        val pendingModel = pendingRoutingTarget ?: return@registerForActivityResult
        pendingRoutingTarget = null
        if (result.resultCode == Activity.RESULT_OK) {
            val savedModel = result.data?.getStringExtra(ChooseProviderActivity.EXTRA_MODEL)
                ?.takeIf { it.isNotBlank() }
                ?: pendingModel.modelId
            finishModelSelection(savedModel, pendingModel.endpointId, RoutingChoice.Keep)
        }
        // Cancel/back leaves the selector open on purpose. The not-yet-applied
        // bad model never reached the chat, so choosing another model is a valid
        // recovery without another modal layer.
    }

    private var favoriteSelectedListener: FavoriteModelListAdapter.OnItemClickListener = object : FavoriteModelListAdapter.OnItemClickListener {
        override fun onItemClick(model: String, endpointId: String) {
            handleModelSelection(model)
        }

        override fun onActionClick(model: String, endpointId: String, position: Int) {
            // Removing a favorite also clears its provider-routing preferences,
            // so confirm first (owner ruling). "Okay" removes one entry from the
            // whole store.
            FavoriteRoutingActions.confirmRemove(requireContext()) {
                favoriteModelsPreferences?.removeFavoriteModel(model, endpointId)
                reloadFavorites()
                render()
            }
        }

        override fun onSettingsClick(model: String, endpointId: String) {
            openRoutingSettings(ModelIdentity(endpointId, model))
        }

        override fun onParametersClick(model: String, endpointId: String) {
            startActivity(
                org.teslasoft.assistant.ui.activities.ModelParametersActivity.createIntent(
                    requireActivity(), model, endpointId, requireArguments().getString("chatId").orEmpty()
                )
            )
        }
    }

    /** Finalize a normal model pick. Kept in one place so every resolution path
     *  (available provider, Automatic rescue, provider-screen Save) behaves the
     *  same and dismisses the selector exactly once. */
    private fun finishModelSelection(
        model: String,
        endpointId: String = resolvedEndpointId,
        choice: RoutingChoice = RoutingChoice.Saved
    ) {
        if (summarizerPicker) {
            applySummarizerSelection(model, endpointId, choice)
            parentFragmentManager.setFragmentResult(
                SUMMARIZER_SELECTION_RESULT,
                bundleOf(SELECTED_MODEL to model, SELECTED_ENDPOINT to endpointId)
            )
        } else if (chatPicker) {
            applyChatRouting(model, endpointId, choice)
            parentFragmentManager.setFragmentResult(
                CHAT_SELECTION_RESULT,
                bundleOf(SELECTED_MODEL to model, SELECTED_ENDPOINT to endpointId)
            )
        } else {
            listener?.onModelSelected(model)
        }
        dismiss()
    }

    /** Saves the picked model as the Summarizer's, with the routing it was
     *  picked with, by the same rules as a chat. */
    private fun applySummarizerSelection(model: String, endpointId: String, choice: RoutingChoice) {
        val context = context ?: return
        when (choice) {
            RoutingChoice.Keep -> Unit
            RoutingChoice.Saved -> SummarizerModelRouting.clear(context)
            is RoutingChoice.Use -> {
                val favorite = favoriteModelsPreferences?.getFavorite(model, endpointId)
                if (favorite != null && ChatModelRouting.sameRouting(favorite, choice.routing)) {
                    SummarizerModelRouting.clear(context)
                } else {
                    SummarizerModelRouting.use(context, choice.routing)
                }
            }
        }
        val summarizer = Preferences.getPreferences(context, "")
        summarizer.setSummarizerEndpointId(endpointId)
        summarizer.setSummarizerModel(model)
    }

    /** Sets the chat's own routing for the picked model. */
    private fun applyChatRouting(model: String, endpointId: String, choice: RoutingChoice) {
        val chat = preferences ?: return
        when (choice) {
            RoutingChoice.Keep -> Unit
            RoutingChoice.Saved -> ChatModelRouting.clear(chat)
            is RoutingChoice.Use -> {
                val favorite = favoriteModelsPreferences?.getFavorite(model, endpointId)
                if (favorite != null && ChatModelRouting.sameRouting(favorite, choice.routing)) {
                    ChatModelRouting.clear(chat)
                } else {
                    ChatModelRouting.use(chat, choice.routing)
                }
            }
        }
    }

    /**
     * Before an ordinary chat adopts a favorite whose saved routing is Only,
     * verify that the selected provider is still in the model's current,
     * authoritative OpenRouter provider list. The check is deliberately
     * fail-open on UNKNOWN network state: request-time Only routing is still
     * fail-closed, but a transient lookup failure must not falsely accuse the
     * provider of being unavailable.
     */
    private fun handleModelSelection(
        model: String,
        endpointId: String = resolvedEndpointId,
        choice: RoutingChoice = RoutingChoice.Saved
    ) {
        if (checkingProvider) return
        val endpoint = if (endpointId == resolvedEndpointId) apiEndpointObject
            else apiEndpointPreferences?.getApiEndpoint(requireContext(), endpointId)
        // The routing this pick will use decides which provider to check.
        val favorite = (choice as? RoutingChoice.Use)?.routing
            ?: favoriteModelsPreferences?.getFavorite(model, endpointId)

        if (!checkOnlyProviderOnSelection ||
            endpoint == null ||
            !endpoint.isOpenRouterRouting() ||
            favorite?.routingType != FavoriteModelObject.ROUTING_ONLY ||
            favorite.selectedProvider.isBlank()
        ) {
            finishModelSelection(model, endpointId, choice)
            return
        }

        val selectedProvider = favorite.selectedProvider
        val generation = ++providerAvailabilityGeneration
        checkingProvider = true
        modelList?.isEnabled = false
        progressBar?.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            val availability = OnlyProviderAvailabilityClient.check(
                endpoint = endpoint,
                modelId = model,
                providerSlug = selectedProvider
            )
            if (!isAdded || generation != providerAvailabilityGeneration) return@launch

            checkingProvider = false
            modelList?.isEnabled = true
            progressBar?.visibility = View.GONE

            if (availability == OnlyProviderAvailability.UNAVAILABLE) {
                showProviderUnavailableDialog(model, endpointId, selectedProvider)
            } else {
                finishModelSelection(model, endpointId, choice)
            }
        }
    }

    /**
     * A confirmed unavailable Only provider cannot be ignored: the dialog has no
     * Cancel/back/outside-tap escape. Each button is a real resolution path.
     * Change Model simply uncovers this already-open picker, so the user does
     * not have to return to Quick Settings and tap the model tile again.
     */
    private fun showProviderUnavailableDialog(model: String, endpointId: String, providerSlug: String) {
        if (!isAdded) return
        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.App_MaterialAlertDialog)
            .setTitle(R.string.provider_unavailable_dialog_title)
            .setMessage(getString(R.string.provider_unavailable_dialog_message, providerSlug, model))
            .setPositiveButton(R.string.provider_unavailable_change_provider) { _, _ ->
                val prefs = apiEndpointPreferences ?: return@setPositiveButton
                val favPrefs = favoriteModelsPreferences ?: return@setPositiveButton
                FavoriteRoutingActions.buildRoutingIntent(
                    requireContext(),
                    prefs,
                    favPrefs,
                    model,
                    endpointId,
                    FavoriteModelObject.ROUTING_ONLY,
                    keepRoutingOnModelChange = true,
                    chatId = chatIdForRouting(),
                    scope = scope
                )?.let { intent ->
                    pendingRoutingTarget = ModelIdentity(endpointId, model)
                    chooseProviderLauncher.launch(intent)
                }
            }
            .setNegativeButton(R.string.provider_unavailable_use_automatic) { _, _ ->
                val favPrefs = favoriteModelsPreferences ?: return@setNegativeButton
                val routing = favPrefs.getFavorite(model, endpointId)
                    ?: FavoriteModelObject(model, endpointId)
                // Preserve the remembered selected provider; only the routing
                // type changes, saved by the same rules as every routing choice.
                routing.routingType = FavoriteModelObject.ROUTING_AUTOMATIC
                if (summarizerPicker) SummarizerModelRouting.save(requireContext(), routing)
                else ChatModelRouting.save(requireContext(), preferences.takeIf { chatPicker }, routing)
                reloadFavorites()
                finishModelSelection(model, endpointId, RoutingChoice.Keep)
            }
            .setNeutralButton(R.string.provider_unavailable_change_model) { _, _ ->
                // The selector is already underneath this dialog. Closing the
                // warning exposes it immediately; the invalid model was never
                // applied to the chat.
            }
            .setCancelable(false)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
    }

    private var requestListener: RequestNetwork.RequestListener = object : RequestNetwork.RequestListener {
        override fun onResponse(tag: String, message: String) {
            try {
                val endpoint = apiEndpointObject
                if (endpoint?.hasOpenRouterCatalogAuthority() == true) {
                    mContext?.let { ModelSupportedParametersStore.get(it).learnFromCatalog(endpoint.id, message) }
                    val refresh = org.teslasoft.assistant.reasoning.EndpointReasoningCapability
                        .refreshFromOpenRouterCatalog(endpoint.reasoningCapabilityByModel, message)
                        ?: run {
                            logReasoningCatalogRefresh(success = false)
                            showProviderError(message)
                            return
                        }
                    val ids = refresh.models.mapNotNull { model ->
                        if (imageMode && !catalogAllowsImageOutput(model.entry)) null else model.id
                    }
                    val imageCapabilities =
                        org.teslasoft.assistant.preferences.includes.ImageCapabilityMetadata
                            .capabilitiesFromResponse(
                                message,
                                org.teslasoft.assistant.preferences.includes.ImageCapabilityProvider.OPENROUTER
                            )
                    val refreshedImageJson = imageCapabilities?.let {
                        org.teslasoft.assistant.preferences.includes.ImageCapabilityStore
                            .refreshMetadata(endpoint.imageCapabilityByModel, it)
                    } ?: endpoint.imageCapabilityByModel
                    if (refresh.capabilityJson != endpoint.reasoningCapabilityByModel ||
                        refreshedImageJson != endpoint.imageCapabilityByModel
                    ) {
                        endpoint.reasoningCapabilityByModel = refresh.capabilityJson
                        endpoint.imageCapabilityByModel = refreshedImageJson
                        apiEndpointPreferences?.setApiEndpoint(requireContext(), endpoint)
                    }
                    refreshReasoningCapabilityIndex(
                        capabilityJson = refresh.capabilityJson,
                        providerPath = org.teslasoft.assistant.reasoning.ReasoningProviderPath.OPENROUTER
                    )
                    // Capability refresh succeeded even if a specialized picker
                    // (for example image-only) filters every model out.
                    logReasoningCatalogRefresh(success = true)
                    if (ids.isEmpty()) {
                        showProviderError(message)
                        return
                    }
                    availableModels.clear()
                    availableModels.addAll(ids)
                    catalogLoaded = true
                    render()
                    return
                }

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

                val provider = endpoint?.let {
                    org.teslasoft.assistant.preferences.includes.ImageCapabilityProvider
                        .forEndpoint(it)
                } ?: org.teslasoft.assistant.preferences.includes.ImageCapabilityProvider.GENERIC
                val imageCapabilities =
                    org.teslasoft.assistant.preferences.includes.ImageCapabilityMetadata
                        .capabilitiesFromResponse(message, provider)
                if (endpoint != null && imageCapabilities != null) {
                    val updated =
                        org.teslasoft.assistant.preferences.includes.ImageCapabilityStore
                            .refreshMetadata(endpoint.imageCapabilityByModel, imageCapabilities)
                    if (updated != endpoint.imageCapabilityByModel) {
                        endpoint.imageCapabilityByModel = updated
                        apiEndpointPreferences?.setApiEndpoint(requireContext(), endpoint)
                    }
                }

                availableModels.clear()
                availableModels.addAll(ids)
                catalogLoaded = true
                render()
            } catch (e: Exception) {
                showProviderError(message, e)
            }
        }

        override fun onErrorResponse(tag: String, message: String) {
            logReasoningCatalogRefresh(success = false)
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

    private fun showProviderError(
        responseBody: String?,
        e: Exception? = null,
        endpointLabel: String? = apiEndpointObject?.label
    ) {
        if (context == null || view == null || (chatPicker && pickerTab != PickerTab.VIEW_ALL)) return
        val excerpt = (responseBody ?: "").take(400)
        val msg = buildString {
            // Plain-language cause first, so a human sees what to do before the
            // raw provider dump. The technical detail stays below, unchanged.
            append(plainLanguageCause(responseBody))
            append("\n\n")
            append("Couldn't read the models list from ")
            append(endpointLabel ?: "this profile")
            append(".\n\nProvider returned:\n")
            append(if (excerpt.isBlank()) "(empty response)" else excerpt)
            if (e != null) {
                append("\n\nDetails: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        progressBar?.visibility = View.GONE
        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.App_MaterialAlertDialog)
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
        val layout = if (chatPicker) R.layout.fragment_chat_model_picker else R.layout.fragment_model_selector
        return inflater.inflate(layout, container, false)
    }

    override fun onStart() {
        super.onStart()
        // Fill the screen: this is a full-screen selector, not a floating pop-up.
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        if (chatPicker) {
            @Suppress("DEPRECATION")
            dialog?.window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
            )
        } else {
            applySystemBarInsets()
        }
    }

    /** Match the app's full-screen activity treatment on Android 15+. */
    private fun applySystemBarInsets() {
        if (Build.VERSION.SDK_INT < 35) return
        val window = dialog?.window ?: return
        val root = view ?: return
        window.decorView.post {
            val insets = window.decorView.rootWindowInsets ?: return@post
            root.findViewById<View>(R.id.action_bar)?.setPadding(
                0,
                insets.getInsets(WindowInsets.Type.statusBars()).top,
                0,
                0
            )
            root.setPadding(
                root.paddingLeft,
                root.paddingTop,
                root.paddingRight,
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        modelList = view.findViewById(R.id.voices_list)
        if (chatPicker) {
            ScreenChrome.fitSystemBarsAndKeyboard(view, view.findViewById(R.id.action_bar), modelList)
        } else {
            modelList?.divider = null
        }
        selectorTitle = view.findViewById(R.id.selector_title)
        progressBar = view.findViewById(R.id.progressBar)
        fieldSearch = view.findViewById(R.id.field_search_text)
        btnBack = view.findViewById(R.id.btn_back)
        btnViewAll = view.findViewById(R.id.btn_view_all)
        btnUseCurrentModel = view.findViewById(R.id.btn_use_current_model)
        btnFavorites = view.findViewById(R.id.model_picker_favorites)
        btnRecent = view.findViewById(R.id.model_picker_recent)

        val ctx = mContext ?: requireContext()

        imageMode = requireArguments().getBoolean("imageModels", false)
        startsWithAllModels = requireArguments().getBoolean(ARG_START_WITH_ALL_MODELS, false)
        rawModelCatalog = requireArguments().getBoolean(ARG_RAW_MODEL_CATALOG, false)
        currentChatModel = requireArguments().getString(ARG_CURRENT_CHAT_MODEL).orEmpty()
        selectorTitle?.text = getString(
            when {
                summarizerPicker -> R.string.summarizer_model_picker_title
                chatPicker -> R.string.model_picker_title
                imageMode -> R.string.label_select_image_model
                else -> R.string.label_select_ai_model
            }
        )

        val chatId = requireArguments().getString("chatId").orEmpty()
        preferences = Preferences.getPreferences(ctx, chatId)
        apiEndpointPreferences = ApiEndpointPreferences.getApiEndpointPreferences(ctx)
        val endpointIdOverride = requireArguments().getString("endpointId").orEmpty()
        resolvedEndpointId = endpointIdOverride.ifEmpty { preferences?.getApiEndpointId() ?: "" }
        apiEndpointObject = apiEndpointPreferences?.getApiEndpoint(ctx, resolvedEndpointId)
        refreshReasoningCapabilityIndex()
        favoriteModelsPreferences = FavoriteModelsPreferences.getPreferences(ctx, scope)
        checkOnlyProviderOnSelection = summarizerPicker ||
            (chatId.isNotBlank() && endpointIdOverride.isBlank() && !imageMode && !startsWithAllModels && !rawModelCatalog)

        if (!chatPicker) reloadFavorites()

        btnBack?.setOnClickListener { handleBack() }

        btnUseCurrentModel?.apply {
            visibility = if (currentChatModel.isBlank()) View.GONE else View.VISIBLE
            setOnClickListener {
                finishModelSelection(currentChatModel)
            }
        }

        btnFavorites?.setOnClickListener { showPickerTab(PickerTab.FAVORITES) }
        btnRecent?.setOnClickListener { showPickerTab(PickerTab.RECENT) }
        btnViewAll?.setOnClickListener {
            if (chatPicker) {
                showPickerTab(PickerTab.VIEW_ALL)
            } else {
                showingAll = true
                fieldSearch?.setText("")
                query = ""
                render()
            }
        }
        if (chatPicker) {
            configureSavedModelAdapter(ctx, chatId)
            configureCatalog(savedInstanceState?.getString(STATE_CATALOG_PROFILE))
        }

        fieldSearch?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { /* unused */ }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s.toString().trim()
                render()
                if (chatPicker) modelList?.setSelection(0)
            }
            override fun afterTextChanged(s: Editable?) { /* unused */ }
        })

        // Image mode and provider-wide callers have no favorites landing.
        // The ordinary Select AI Model purpose remains favorites-first.
        if (imageMode || startsWithAllModels) showingAll = true

        render()
        if (!chatPicker) startCatalogFetch()
        else if (pickerTab == PickerTab.VIEW_ALL) catalog?.restart()
    }

    override fun onResume() {
        super.onResume()
        if (chatPicker) {
            reloadSavedModelRows()
            render()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PICKER_TAB, pickerTab.name)
        outState.putString(STATE_CATALOG_PROFILE, catalog?.profile?.id)
        pendingRoutingTarget?.let {
            outState.putString(STATE_ROUTING_ENDPOINT, it.endpointId)
            outState.putString(STATE_ROUTING_MODEL, it.modelId)
        }
    }

    override fun onDestroyView() {
        checkingProvider = false
        catalog?.stop()
        catalog = null
        catalogHeaderAttached = false
        catalogReasoningIndex = null
        catalogReasoningProfile = null
        modelList?.adapter = null
        modelList = null
        progressBar = null
        selectorTitle = null
        fieldSearch = null
        btnBack = null
        btnViewAll = null
        btnUseCurrentModel = null
        btnFavorites = null
        btnRecent = null
        savedModelAdapter = null
        super.onDestroyView()
    }

    private fun showPickerTab(tab: PickerTab) {
        if (checkingProvider) {
            render()
            return
        }
        if (tab == pickerTab) {
            render()
            return
        }
        val switchingCatalog = tab == PickerTab.VIEW_ALL || pickerTab == PickerTab.VIEW_ALL
        pickerTab = tab
        if (switchingCatalog) fieldSearch?.setText("")
        if (tab == PickerTab.VIEW_ALL) {
            catalog?.restart()
        } else {
            catalog?.stop()
            reloadSavedModelRows()
        }
        render()
        modelList?.setSelection(0)
    }

    private fun configureSavedModelAdapter(context: Context, chatId: String) {
        val selectedModel = if (summarizerPicker) {
            Preferences.getPreferences(context, "").getSummarizerModel()
        } else preferences?.getModel().orEmpty()
        savedModelAdapter = SavedModelPickerAdapter(
            context,
            chatId,
            ModelIdentity(resolvedEndpointId, selectedModel),
            ModelCleanupReportStore.get(context).load().unavailable,
            onSelect = { row, source ->
                val target = row.identity
                val choice = if (source == SavedModelPickerAdapter.Source.RECENT) {
                    RoutingChoice.Use(row.settings.copy())
                } else RoutingChoice.Saved
                handleModelSelection(target.modelId, target.endpointId, choice)
            },
            onProvider = ::pickProvider,
            onRoute = ::openRoutingSettings,
            onPin = { settings, pinned ->
                favoriteModelsPreferences?.setPinned(settings, pinned)
                reloadSavedModelRows()
                render()
            },
            onFavorite = { settings ->
                favoriteModelsPreferences?.addFavoriteIfAbsent(settings)
                reloadSavedModelRows()
                render()
            },
            onDelete = { target, source ->
                if (source == SavedModelPickerAdapter.Source.RECENT) {
                    RecentModelsPreferences(requireContext(), scope).remove(target)
                    reloadSavedModelRows()
                    render()
                } else {
                    FavoriteRoutingActions.confirmRemove(requireContext()) {
                        favoriteModelsPreferences?.removeFavoriteModel(target.modelId, target.endpointId)
                        reloadSavedModelRows()
                        render()
                    }
                }
            },
            scope = scope
        )
        modelList?.adapter = savedModelAdapter
        reloadSavedModelRows()
    }

    private fun configureCatalog(savedProfileId: String?) {
        val list = modelList ?: return
        val context = requireContext()
        val profiles = apiEndpointPreferences?.getApiEndpointsList(context).orEmpty()
        catalog = ModelPickerCatalog(
            LayoutInflater.from(context).inflate(R.layout.view_model_browser_controls, list, false),
            profiles,
            savedProfileId ?: resolvedEndpointId,
            viewLifecycleOwner.lifecycleScope,
            onChanged = ::render,
            onFailure = { endpoint, error ->
                showProviderError(error.message, endpointLabel = endpoint.label.ifBlank { endpoint.host })
            },
            onCatalogReceived = ::refreshChatCatalogCapabilities
        )
        list.setItemsCanFocus(true)
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, scrollState: Int) = Unit

            override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) {
                if (pickerTab == PickerTab.VIEW_ALL && visible > 0 && first + visible >= total - 5) {
                    list.post { if (pickerTab == PickerTab.VIEW_ALL) catalog?.loadMore() }
                }
            }
        })
    }

    private fun refreshChatCatalogCapabilities(endpoint: ApiEndpointObject, body: String) {
        val context = context ?: return
        val profiles = apiEndpointPreferences ?: return
        val current = profiles.getApiEndpoint(context, endpoint.id)
        if (current.host != endpoint.host || current.apiKey != endpoint.apiKey ||
            current.authType != endpoint.authType
        ) return
        if (current.hasOpenRouterCatalogAuthority()) {
            ModelSupportedParametersStore.get(context).learnFromCatalog(endpoint.id, body)
        }
        val refresh = EndpointReasoningCapability.refreshFromOpenRouterCatalog(
            current.reasoningCapabilityByModel, body
        ) ?: return
        val imageMetadata = org.teslasoft.assistant.preferences.includes.ImageCapabilityMetadata
            .capabilitiesFromResponse(body, org.teslasoft.assistant.preferences.includes.ImageCapabilityProvider.OPENROUTER)
        val imageJson = imageMetadata?.let {
            org.teslasoft.assistant.preferences.includes.ImageCapabilityStore.refreshMetadata(
                current.imageCapabilityByModel, it
            )
        } ?: current.imageCapabilityByModel
        if (refresh.capabilityJson != current.reasoningCapabilityByModel ||
            imageJson != current.imageCapabilityByModel
        ) {
            current.reasoningCapabilityByModel = refresh.capabilityJson
            current.imageCapabilityByModel = imageJson
            profiles.setApiEndpoint(context, current)
        }
        endpoint.reasoningCapabilityByModel = current.reasoningCapabilityByModel
        catalogReasoningIndex = null
        if (endpoint.id == resolvedEndpointId) apiEndpointObject = current
        reloadSavedModelRows()
    }

    private fun showCatalogRows(adapter: SavedModelPickerAdapter) {
        val browser = catalog ?: return
        val endpoint = browser.profile
        val rows = if (endpoint == null) emptyList() else {
            val reasoning = catalogReasoningIndex.takeIf { catalogReasoningProfile == endpoint.id }
                ?: EndpointReasoningCapability.index(
                    endpoint.reasoningCapabilityByModel,
                    ReasoningProviderPath.forEndpoint(endpoint.host, endpoint.hasOpenRouterCatalogAuthority())
                ).also {
                    catalogReasoningProfile = endpoint.id
                    catalogReasoningIndex = it
                }
            browser.visibleEntries().map { entry ->
                val saved = savedModels[ModelIdentity(endpoint.id, entry.id)]
                val settings = saved?.let { favoriteModelsPreferences?.decodeFavorite(it) }
                    ?: FavoriteModelObject(entry.id, endpoint.id)
                SavedModelPickerAdapter.Row(
                    settings, "", "", endpoint.isOpenRouterRouting(),
                    reasoning, saved != null, saved?.get("pinned") == "true", entry.advertisesReasoning
                )
            }
        }
        adapter.submit(rows, "", SavedModelPickerAdapter.Source.CATALOG)
    }

    private fun openRoutingSettings(target: ModelIdentity) {
        if (checkingProvider) return
        val prefs = apiEndpointPreferences ?: return
        val favorites = favoriteModelsPreferences ?: return
        FavoriteRoutingActions.buildRoutingIntent(
            requireContext(), prefs, favorites, target.modelId, target.endpointId,
            returnOnBackAfterSave = chatPicker,
            chatId = chatIdForRouting(),
            scope = scope
        )?.let {
            if (chatPicker) pendingRoutingTarget = target
            chooseProviderLauncher.launch(it)
        }
    }

    /** The chat's id for the provider screen, only for the chat picker. */
    private fun chatIdForRouting(): String =
        if (chatPicker) requireArguments().getString("chatId").orEmpty() else ""

    /** A provider picked from a row's dropdown selects the model with it. On a
     *  Favorites row it becomes the favorite's provider; on a Recent row only
     *  the chat uses it. */
    private fun pickProvider(row: SavedModelPickerAdapter.Row, source: SavedModelPickerAdapter.Source, slug: String) {
        val target = row.identity
        if (source == SavedModelPickerAdapter.Source.FAVORITES) {
            val favorites = favoriteModelsPreferences ?: return
            val favorite = favorites.getFavorite(target.modelId, target.endpointId) ?: return
            favorite.routingType = FavoriteModelObject.ROUTING_ONLY
            favorite.selectedProvider = slug
            favorites.addFavoriteModel(favorite)
            reloadSavedModelRows()
            render()
            handleModelSelection(target.modelId, target.endpointId, RoutingChoice.Saved)
        } else {
            val routing = row.settings.copy().apply {
                routingType = FavoriteModelObject.ROUTING_ONLY
                selectedProvider = slug
            }
            RecentOnlyProvidersStore.get(requireContext(), scope).record(target.endpointId, target.modelId, slug)
            if (summarizerPicker) SummarizerModelRouting.recordRecent(requireContext(), routing)
            else ChatModelRouting.recordRecent(requireContext(), routing)
            handleModelSelection(target.modelId, target.endpointId, RoutingChoice.Use(routing))
        }
    }

    private fun reloadSavedModelRows() {
        val context = context ?: return
        val store = favoriteModelsPreferences ?: return
        val endpoints = apiEndpointPreferences?.getApiEndpointsList(context).orEmpty().associateBy { it.id }
        val saved = store.getFavoriteModels().associateBy {
            ModelIdentity(it["endpointId"].orEmpty(), it["modelId"].orEmpty())
        }
        savedModels = saved
        val providerNames = RecentOnlyProvidersStore.get(context, scope)
        val reasoningByEndpoint = mutableMapOf<String, EndpointReasoningCapabilityIndex>()

        fun row(settings: FavoriteModelObject): SavedModelPickerAdapter.Row? {
            val endpoint = endpoints[settings.endpointId] ?: return null
            val entry = saved[ModelIdentity(settings.endpointId, settings.modelId)]
            val route = when (settings.routingType) {
                FavoriteModelObject.ROUTING_ONLY -> providerNames.displayName(settings.selectedProvider)
                    .ifBlank { getString(R.string.choose_provider_routing_only) }
                FavoriteModelObject.ROUTING_PREFERRED -> getString(R.string.choose_provider_routing_preferred)
                else -> getString(R.string.provider_filter_quant_any)
            }
            val reasoning = reasoningByEndpoint.getOrPut(endpoint.id) {
                EndpointReasoningCapability.index(
                    endpoint.reasoningCapabilityByModel,
                    ReasoningProviderPath.forEndpoint(endpoint.host, endpoint.hasOpenRouterCatalogAuthority())
                )
            }
            return SavedModelPickerAdapter.Row(
                settings, endpoint.label.ifBlank { endpoint.host }, route, endpoint.isOpenRouterRouting(),
                reasoning,
                saved = entry != null, pinned = entry?.get("pinned") == "true"
            )
        }

        fun withProviders(row: SavedModelPickerAdapter.Row, options: List<String>) =
            if (!row.supportsRouting || options.isEmpty()) row
            else row.copy(providerOptions = options, providerNames = options.map(providerNames::displayName))

        favoriteRows = saved.values.mapNotNull { entry ->
            val favorite = store.decodeFavorite(entry)
            row(favorite)?.let { withProviders(it, favorite.favoriteProviders) }
        }.sortedByDescending { it.pinned }
        recentRows = runCatching { RecentModelsPreferences(context, scope).models() }.getOrDefault(emptyList())
            .mapNotNull { recent ->
                row(recent)?.let { row ->
                    // Its favorite providers (starred), then the last five used;
                    // a single one that is already in use leaves nothing to pick.
                    // A model that is also a favorite keeps its favorite
                    // providers on the favorite, as everywhere else.
                    val favorites = saved[ModelIdentity(recent.endpointId, recent.modelId)]
                        ?.let { store.decodeFavorite(it).favoriteProviders }
                        ?: recent.favoriteProviders
                    val options = RecentOnlyProviders.quickSettingsOptions(
                        "", favorites, providerNames.recents(recent.endpointId, recent.modelId)
                    )
                    val inUse = recent.routingType == FavoriteModelObject.ROUTING_ONLY &&
                        options.size == 1 && options.single().equals(recent.selectedProvider, ignoreCase = true)
                    if (!row.supportsRouting || inUse || options.isEmpty()) row
                    else row.copy(
                        providerOptions = options,
                        providerNames = options.map { slug ->
                            val name = providerNames.displayName(slug)
                            if (favorites.any { it.equals(slug, ignoreCase = true) }) {
                                getString(R.string.provider_favorite_option, name)
                            } else name
                        }
                    )
                }
            }
    }

    /** The header Back returns to Favorites from the other tabs. */
    private fun handleBack() {
        if (chatPicker) {
            if (pickerTab == PickerTab.FAVORITES) dismiss() else showPickerTab(PickerTab.FAVORITES)
        } else if (showingAll && !imageMode && !startsWithAllModels) {
            showingAll = false
            fieldSearch?.setText("")
            query = ""
            render()
        } else {
            dismiss()
        }
    }

    private fun reloadFavorites() {
        if (chatPicker) {
            reloadSavedModelRows()
        } else {
            favorites = favoriteModelsPreferences?.getFavoriteModels(resolvedEndpointId) ?: arrayListOf()
        }
    }

    /** Decide, from the current mode/search/catalog state, which list to show
     *  and whether the "View all" button and spinner belong on screen. */
    private fun render() {
        if (!isAdded || modelList == null) return
        val allMode = if (chatPicker) pickerTab == PickerTab.VIEW_ALL
            else imageMode || showingAll || query.isNotEmpty()

        if (chatPicker) {
            btnFavorites?.isChecked = pickerTab == PickerTab.FAVORITES
            btnRecent?.isChecked = pickerTab == PickerTab.RECENT
            btnViewAll?.isChecked = allMode
            view?.findViewById<View>(R.id.field_search)?.isVisible = !allMode
            progressBar?.isVisible = checkingProvider || (allMode && catalog?.loading == true)
            val adapter = savedModelAdapter ?: return
            val header = catalog?.header
            if (header != null && catalogHeaderAttached != allMode) {
                catalogHeaderAttached = allMode
                if (allMode) modelList?.addHeaderView(header, null, false)
                else modelList?.removeHeaderView(header)
            }
            when (pickerTab) {
                PickerTab.FAVORITES -> adapter.submit(favoriteRows, query, SavedModelPickerAdapter.Source.FAVORITES)
                PickerTab.RECENT -> adapter.submit(recentRows, query, SavedModelPickerAdapter.Source.RECENT)
                PickerTab.VIEW_ALL -> showCatalogRows(adapter)
            }
            return
        }

        btnViewAll?.visibility = if (!imageMode && !showingAll && query.isEmpty()) View.VISIBLE else View.GONE
        progressBar?.visibility = if (allMode && !catalogLoaded) View.VISIBLE else View.GONE

        if (allMode) {
            val filtered = if (query.isEmpty()) {
                ArrayList(availableModels)
            } else {
                // Preserve the existing bidirectional substring match, ignoring capitalization.
                ArrayList(availableModels.filter { item -> item.contains(query, ignoreCase = true) || query.contains(item, ignoreCase = true) })
            }
            val adapter = ModelListAdapter(
                requireContext(),
                filtered,
                requireArguments().getString("chatId").toString(),
                resolvedEndpointId,
                reasoningCapabilityIndex
            )
            adapter.setOnItemClickListener(modelSelectedListener)
            modelList?.adapter = adapter
        } else {
            val endpointId = resolvedEndpointId
            val adapter = FavoriteModelListAdapter(
                requireContext(),
                ArrayList(favorites),
                requireArguments().getString("chatId").toString(),
                showRoutingGear = true,
                reasoningCapabilityIndexes = if (endpointId.isBlank()) emptyMap()
                    else mapOf(endpointId to reasoningCapabilityIndex)
            )
            adapter.setOnItemClickListener(favoriteSelectedListener)
            modelList?.adapter = adapter
        }
    }

    private fun refreshReasoningCapabilityIndex(
        capabilityJson: String? = apiEndpointObject?.reasoningCapabilityByModel,
        providerPath: org.teslasoft.assistant.reasoning.ReasoningProviderPath? = null
    ) {
        val endpoint = apiEndpointObject
        reasoningCapabilityIndex = org.teslasoft.assistant.reasoning.EndpointReasoningCapability.index(
            capabilityJson,
            providerPath ?: if (endpoint == null) {
                org.teslasoft.assistant.reasoning.ReasoningProviderPath.GENERIC_OPENAI_COMPATIBLE
            } else {
                org.teslasoft.assistant.reasoning.ReasoningProviderPath.forEndpoint(
                    endpoint.host,
                    endpoint.hasOpenRouterCatalogAuthority()
                )
            }
        )
    }

    /** Catalog fetch for the feature-specific pickers. The chat browser uses
     * [ModelPickerCatalog] for profile switching, filtering and pagination. */
    private fun startCatalogFetch() {
        val endpoint = apiEndpointObject ?: return
        if (imageMode) {
            lifecycleScope.launch {
                try {
                    val models = kotlinx.coroutines.withContext(Dispatchers.IO) {
                        org.teslasoft.assistant.imagegen.ImageCatalogClient.models(endpoint)
                    }
                    if (!isAdded) return@launch
                    availableModels.clear()
                    availableModels.addAll(models.filter { it.hasDisplayableOutput() }.map { it.id })
                    catalogLoaded = true
                    render()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (isAdded) showProviderError(failure.message, failure)
                }
            }
            return
        }
        // OpenRouter's raw catalog contains both ids and authoritative
        // reasoning metadata. Use it as the one shared request instead of an
        // SDK id fetch plus a disposable second capability fetch.
        if (endpoint.hasOpenRouterCatalogAuthority() || startsWithAllModels || rawModelCatalog) {
            startRawModelsRequest()
            return
        }

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
            try {
                val models: List<Model> = ai.models()
                availableModels.clear()
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
     *  response lands in [requestListener]. Used as the fallback when the SDK
     *  path fails, and as the primary path in image/provider-wide modes. */
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
        val endpoint = apiEndpointObject
        val base = (endpoint?.host ?: "").let { if (it.isBlank() || it.endsWith("/")) it else "$it/" }
        when (endpoint?.let {
            org.teslasoft.assistant.preferences.includes.ImageCapabilityProvider.forEndpoint(it)
        }) {
            org.teslasoft.assistant.preferences.includes.ImageCapabilityProvider.NANOGPT ->
                requestNetwork?.startRequestNetwork(
                    "GET", base + "models?detailed=true", "A", requestListener
                )
            org.teslasoft.assistant.preferences.includes.ImageCapabilityProvider.VENICE ->
                requestNetwork?.startRequestNetwork(
                    "GET", base + "models?type=text", "A", requestListener
                )
            else -> requestNetwork?.startRequestNetwork("GET", base + "models", "A", requestListener)
        }
    }

    private fun logReasoningCatalogRefresh(success: Boolean) {
        if (!isAdded) return
        val endpoint = apiEndpointObject ?: return
        if (!endpoint.hasOpenRouterCatalogAuthority()) return
        val model = preferences?.getModel().orEmpty()
        val capability = org.teslasoft.assistant.reasoning.EndpointReasoningCapability.resolve(
            endpoint.reasoningCapabilityByModel,
            model,
            providerPath = org.teslasoft.assistant.reasoning.ReasoningProviderPath.OPENROUTER
        )
        val efforts = capability.supportedEfforts.joinToString(",") { it.serialized }
            .ifEmpty { "unknown" }
        org.teslasoft.assistant.preferences.Logger.logAsync(
            requireContext().applicationContext,
            "event",
            "ReasoningCapability",
            if (success) "info" else "warning",
            "Reasoning capability source: ${capability.source}\n" +
                "Catalog refresh: ${if (success) "success" else "unavailable; cache preserved"}\n" +
                "Model: ${model.ifBlank { "(none selected)" }}\n" +
                "Reasoning: ${capability.support}\n" +
                "Supported efforts: $efforts"
        )
    }

    fun interface OnModelSelectedListener {
        fun onModelSelected(model: String)
    }

    fun setModelSelectedListener(listener: OnModelSelectedListener) {
        this.listener = listener
    }
}
