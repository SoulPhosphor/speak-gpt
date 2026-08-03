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

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.loadingindicator.LoadingIndicator
import com.google.android.material.textfield.TextInputEditText
import org.teslasoft.assistant.R
import org.teslasoft.assistant.imagegen.ImageProviderAdapters
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.FavoriteModelsPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject
import org.teslasoft.assistant.ui.activities.ChooseProviderActivity
import org.teslasoft.assistant.ui.adapters.FavoriteModelListAdapter

class AdvancedFavoriteModelSelectorDialogFragment : DialogFragment() {
    companion object {
        /** [endpointId], when non-blank, browses/fetches for that specific saved
         *  endpoint instead of the chat's own active endpoint (Preferences'
         *  apiEndpointId) — used by callers assigning a model to a feature that
         *  has its own independently-chosen endpoint (e.g. Memory Assistant). */
        fun newInstance(name: String, chatId: String, endpointId: String = "") : AdvancedFavoriteModelSelectorDialogFragment {
            val advancedModelSelectorDialogFragment = AdvancedFavoriteModelSelectorDialogFragment()

            val args = Bundle()
            args.putString("name", name)
            args.putString("chatId", chatId)
            args.putString("endpointId", endpointId)

            advancedModelSelectorDialogFragment.arguments = args

            return advancedModelSelectorDialogFragment
        }
    }

    private var apiEndpointPreferences: ApiEndpointPreferences? = null
    private var apiEndpointObject: ApiEndpointObject? = null
    private var listener: OnModelSelectedListener? = null
    private var progressBar: LoadingIndicator? = null
    private var ttsSelectorTitle: TextView? = null
    private var fieldSearch: TextInputEditText? = null
    private var builder: AlertDialog.Builder? = null
    private var modelList: ListView? = null

    private var modelListAdapter: FavoriteModelListAdapter? = null

    private var availableModels: ArrayList<Map<String, String>> = arrayListOf()
    private var availableModelsProjection: ArrayList<Map<String, String>> = arrayListOf()

    private var preferences: Preferences? = null
    private var favoriteModelsPreferences: FavoriteModelsPreferences? = null

    /** The endpoint the "All models" fallback browses — the caller's override
     *  if given, else the chat's own active endpoint (old behavior). */
    private var resolvedEndpointId: String = ""

    private var modelSelectedListener: AdvancedModelSelectorDialogFragment.OnModelSelectedListener = AdvancedModelSelectorDialogFragment.OnModelSelectedListener { model ->
        listener?.onModelSelected(model, resolvedEndpointId)
        dismiss()
    }

    /** Return from the Choose Provider screen opened by a row's routing gear.
     *  It persists the favorite itself, so we just refresh the list (a gear can
     *  flip outline → filled once routing is set up). */
    private val chooseProviderLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshFavorites() }

    private var modelClickListener = object : FavoriteModelListAdapter.OnItemClickListener {
        override fun onItemClick(model: String, endpointId: String) {
            // Which preference this belongs to (the chat's own endpoint, or a
            // feature's independently-chosen one) is the caller's call, not
            // this dialog's — it just reports what was picked.
            listener?.onModelSelected(model, endpointId)
            dismiss()
        }

        override fun onActionClick(model: String, endpointId: String, position: Int) {
            // Removing a favorite also clears its provider-routing preferences,
            // so confirm first instead of deleting on the single tap (owner
            // ruling). The actual removal happens in confirmRemoveFavorite.
            confirmRemoveFavorite(model, endpointId)
        }

        override fun onSettingsClick(model: String, endpointId: String) {
            openRoutingSettings(model, endpointId)
        }
    }

    /**
     * Confirm before removing a favorite. Uses the shared themed dialog and the
     * standard two-action layout, with "Cancel" first (dismiss) and "Okay"
     * second (proceed) per the owner's ordering. "Okay" removes just this one
     * entry from the whole store — never rewritten from this endpoint's
     * filtered view, which would drop other profiles' favorites.
     */
    private fun confirmRemoveFavorite(model: String, endpointId: String) {
        val actionsView = layoutInflater.inflate(R.layout.dialog_two_actions, null)

        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.App_MaterialAlertDialog)
            .setTitle(R.string.favorite_remove_title)
            .setMessage(R.string.favorite_remove_message)
            .setView(actionsView)
            .create()

        actionsView.findViewById<MaterialButton>(R.id.btn_dialog_primary_action).apply {
            setText(R.string.btn_cancel)
            setOnClickListener { dialog.dismiss() }
        }

        actionsView.findViewById<MaterialButton>(R.id.btn_dialog_destructive_action).apply {
            setText(R.string.okay)
            setOnClickListener {
                dialog.dismiss()
                removeFavorite(model, endpointId)
            }
        }

        dialog.show()
    }

    private fun removeFavorite(model: String, endpointId: String) {
        // Remove just this one entry from the whole store (matched by model +
        // endpoint), then rebuild the visible list from the store so the row
        // actually leaves the list on this tap.
        favoriteModelsPreferences?.removeFavoriteModel(model, endpointId)
        refreshFavorites()
    }

    /**
     * Open the Choose Provider screen for a favorite from its routing gear, with
     * that model's stored routing loaded. Connection details come from the
     * favorite's own endpoint profile so the provider chart can fetch. The
     * screen writes the favorite directly on Save (EXTRA_PERSIST_DIRECTLY).
     */
    private fun openRoutingSettings(model: String, endpointId: String) {
        val endpoint = apiEndpointPreferences?.getApiEndpoint(requireActivity(), endpointId) ?: return
        if (!ImageProviderAdapters.isOpenRouter(endpoint)) return

        val routingType = favoriteModelsPreferences?.getRoutingType(model, endpointId)
            ?: FavoriteModelObject.ROUTING_AUTOMATIC

        val intent = Intent(requireActivity(), ChooseProviderActivity::class.java)
            .putExtra(ChooseProviderActivity.EXTRA_PERSIST_DIRECTLY, true)
            .putExtra(ChooseProviderActivity.EXTRA_ENDPOINT_ID, endpointId)
            .putExtra(ChooseProviderActivity.EXTRA_MODEL, model)
            .putExtra(ChooseProviderActivity.EXTRA_ROUTING_TYPE, routingType)
            .putExtra(ChooseProviderActivity.EXTRA_HOST, endpoint.host)
            .putExtra(ChooseProviderActivity.EXTRA_API_KEY, endpoint.apiKey)
            .putExtra(ChooseProviderActivity.EXTRA_AUTH_TYPE, endpoint.authType)
            .putExtra(
                ChooseProviderActivity.EXTRA_DISCOVERY_PATH,
                endpoint.providerDiscoveryPath.ifBlank { ApiEndpointObject.DEFAULT_PROVIDER_DISCOVERY_PATH }
            )
        chooseProviderLauncher.launch(intent)
    }

    /** Rebuild the list from the store (used after a gear round-trip may have
     *  changed a favorite), keeping the current search query. */
    private fun refreshFavorites() {
        availableModels.clear()
        val list = favoriteModelsPreferences?.getFavoriteModels(resolvedEndpointId)
        if (list != null) availableModels.addAll(list)
        updateProjection(fieldSearch?.text?.toString()?.trim() ?: "")
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        builder = MaterialAlertDialogBuilder(this.requireContext(), R.style.App_MaterialAlertDialog)

        val view: View = this.layoutInflater.inflate(R.layout.fragment_select_voice, null)

        modelList = view.findViewById(R.id.voices_list)
        ttsSelectorTitle = view.findViewById(R.id.tts_selector_title)
        fieldSearch = view.findViewById(R.id.field_search_text)

        fieldSearch?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { /* unused */ }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateProjection(s.toString().trim())
            }

            override fun afterTextChanged(s: Editable?) { /* unused */ }

        })

        modelList?.divider = null

        progressBar = view.findViewById(R.id.progressBar)

        ttsSelectorTitle?.text = getString(R.string.label_favorite_ai_models)

        progressBar?.visibility = View.GONE

        val model = requireArguments().getString("name")
        val chatId = requireArguments().getString("chatId")

        preferences = Preferences.getPreferences(requireActivity(), requireArguments().getString("chatId").toString())
        apiEndpointPreferences = ApiEndpointPreferences.getApiEndpointPreferences(requireActivity())
        val endpointIdOverride = requireArguments().getString("endpointId").orEmpty()
        resolvedEndpointId = endpointIdOverride.ifEmpty { preferences?.getApiEndpointId() ?: "" }
        apiEndpointObject = apiEndpointPreferences?.getApiEndpoint(requireActivity(), resolvedEndpointId)
        favoriteModelsPreferences = FavoriteModelsPreferences.getPreferences(requireActivity())

        reloadList()

        builder!!.setView(view)
            .setCancelable(false)
            .setNeutralButton(R.string.btn_all_models) {_, _ -> run{
                val dialog = AdvancedModelSelectorDialogFragment.newInstance(model!!, chatId!!, endpointIdOverride)
                dialog.setModelSelectedListener(modelSelectedListener)
                dialog.show(parentFragmentManager, "AdvancedModelSelectorDialogFragment")
            }}
            .setNegativeButton(android.R.string.cancel, null)

        return builder!!.create()
    }

    private fun reloadList() {
        val list = favoriteModelsPreferences?.getFavoriteModels(resolvedEndpointId)?.toMutableList()

        if (list != null) {
            availableModels.addAll(list)
        }

        updateProjection("")

        modelListAdapter = FavoriteModelListAdapter(requireContext(), availableModelsProjection, requireArguments().getString("chatId").toString(), showRoutingGear = true)
        modelListAdapter?.setOnItemClickListener(modelClickListener)
        modelList?.adapter = modelListAdapter
        modelListAdapter?.notifyDataSetChanged()
    }

    fun interface OnModelSelectedListener {
        /** [endpointId] is which saved endpoint the picked model belongs to —
         *  the caller decides what that means (switch the chat's active
         *  endpoint, or a feature's own independently-chosen one). */
        fun onModelSelected(model: String, endpointId: String)
    }

    fun setModelSelectedListener(listener: OnModelSelectedListener) {
        this.listener = listener
    }

    private fun updateProjection(query: String) {
        if (availableModelsProjection == null) availableModelsProjection = arrayListOf()
        availableModelsProjection.clear()
        if (availableModelsProjection == null) availableModelsProjection = arrayListOf()

        if (query == "") {
            availableModelsProjection.addAll(availableModels)
        } else {
            availableModelsProjection = availableModels.filter { item -> item["modelId"].toString() == query || item["modelId"].toString().contains(query) || query.contains(item["modelId"].toString())} as ArrayList<Map<String, String>>
        }

        modelListAdapter = FavoriteModelListAdapter(requireContext(), availableModelsProjection, requireArguments().getString("chatId").toString(), showRoutingGear = true)
        modelListAdapter?.setOnItemClickListener(modelClickListener)
        modelList?.adapter = modelListAdapter
        modelListAdapter?.notifyDataSetChanged()
    }
}
