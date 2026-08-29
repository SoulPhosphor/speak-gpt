package org.teslasoft.assistant.ui.activities

import android.graphics.Color
import android.content.DialogInterface
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Space
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.tts.*
import org.teslasoft.assistant.tts.api.*
import org.teslasoft.assistant.tts.voices.SavedApiVoiceProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.loadingindicator.LoadingIndicator
import com.google.android.material.textfield.TextInputEditText
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.tts.voices.GoogleSpeechVoiceProvider
import org.teslasoft.assistant.tts.voices.VoiceBrowserController
import org.teslasoft.assistant.tts.voices.VoiceFacet
import org.teslasoft.assistant.tts.voices.VoiceFilterDefinition
import org.teslasoft.assistant.tts.voices.VoiceLoadState
import org.teslasoft.assistant.tts.voices.VoiceLocation
import org.teslasoft.assistant.tts.voices.BrowserVoice
import org.teslasoft.assistant.tts.voices.LastKnownGoodVoiceRegistry
import org.teslasoft.assistant.tts.voices.LastKnownGoodVoiceSelection
import org.teslasoft.assistant.tts.voices.VoiceIdentityRegistry
import org.teslasoft.assistant.tts.voices.VoiceFilterStatePersistence
import org.teslasoft.assistant.tts.voices.VoicePreviewText
import org.teslasoft.assistant.tts.voices.VoiceSelectionExitPolicy
import org.teslasoft.assistant.ui.adapters.VoiceListAdapter
import org.teslasoft.assistant.ui.widgets.AppDropdown
import org.teslasoft.assistant.util.WindowInsetsUtil
import java.util.EnumSet

class VoiceBrowserActivity : FragmentActivity() {
    companion object {
        const val EXTRA_CHAT_ID = "chatId"
        const val EXTRA_SUPPRESS_INITIAL_PERMANENT_NOTICE = "suppressInitialPermanentTtsNotice"
    }

    private lateinit var preferences: Preferences
    private lateinit var controller: VoiceBrowserController
    private lateinit var adapter: VoiceListAdapter
    private lateinit var providerDropdown: TextView
    private lateinit var locationSegments: MaterialButtonToggleGroup
    private lateinit var filterGrid: LinearLayout
    private lateinit var voicesList: RecyclerView
    private lateinit var voiceCount: TextView
    private lateinit var stateView: LinearLayout
    private lateinit var loading: LoadingIndicator
    private lateinit var stateMessage: TextView
    private lateinit var resetFilters: MaterialButton
    private lateinit var previewText: TextInputEditText
    private lateinit var identityRegistry: VoiceIdentityRegistry
    private lateinit var lastKnownGoodVoiceRegistry: LastKnownGoodVoiceRegistry
    private lateinit var selections: TtsSelectionService
    private val refreshGate = TtsRequestGate()
    private val recoveryGate = TtsRequestGate()
    private var notice: androidx.appcompat.app.AlertDialog? = null
    private var shownLoadFailure: Throwable? = null
    private var activating = false
    private var suppressInitialPermanentNotice = false

    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= 30) {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
            )
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_voice_browser)
        suppressInitialPermanentNotice =
            intent.getBooleanExtra(EXTRA_SUPPRESS_INITIAL_PERMANENT_NOTICE, false)

        val chatId = intent.getStringExtra(EXTRA_CHAT_ID).orEmpty()
        preferences = Preferences.getPreferences(this, chatId)
        selections = TtsSelectionService(this, preferences)
        identityRegistry = VoiceIdentityRegistry(this)
        lastKnownGoodVoiceRegistry = LastKnownGoodVoiceRegistry(this)
        providerDropdown = findViewById(R.id.provider_dropdown)
        locationSegments = findViewById(R.id.location_segments)
        filterGrid = findViewById(R.id.filter_grid)
        voicesList = findViewById(R.id.voices_list)
        voiceCount = findViewById(R.id.voice_count)
        stateView = findViewById(R.id.voice_state)
        loading = findViewById(R.id.voice_loading)
        stateMessage = findViewById(R.id.voice_state_message)
        resetFilters = findViewById(R.id.reset_filters)
        previewText = findViewById(R.id.voice_preview_text)
        previewText.setText(preferences.getVoicePreviewText())
        previewText.doAfterTextChanged { preferences.setVoicePreviewText(it?.toString().orEmpty()) }
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { attemptChevronExit() }

        controller = VoiceBrowserController(
            providers = listOf(
                GoogleSpeechVoiceProvider(this, preferences)
            ),
            activeProviderId = preferences.getSelectedTtsVoice()?.sourceId ?: "google",
            decorateVoice = identityRegistry::apply,
            initialFilterState = { providerId ->
                VoiceFilterStatePersistence.decode(preferences.getVoiceBrowserFilters(providerId))
            }
        )
        adapter = VoiceListAdapter(
            onSelect = ::activateVoice,
            onLongPress = ::showVoiceIdentityDialog,
            onPreview = { voice ->
                adapter.setPreviewing(voice.providerId, voice.providerVoiceId)
                controller.preview(
                    voice,
                    previewText.text?.toString()?.takeIf(String::isNotBlank) ?: VoicePreviewText.DEFAULT,
                    onFailure = { message ->
                        adapter.setPreviewing(null, null)
                        showActionError(message)
                    },
                    onChanged = ::renderOnMainThread,
                    onPlaybackChanged = { playingVoiceId ->
                        if (!isFinishing && !isDestroyed) adapter.setPreviewing(voice.providerId, playingVoiceId)
                    }
                )
            },
            onStopPreview = {
                adapter.setPreviewing(null, null)
                controller.stopPreview()
            },
            onDownload = { voice -> controller.download(voice, ::showActionError, ::renderOnMainThread) }
        )
        voicesList.layoutManager = LinearLayoutManager(this)
        voicesList.adapter = adapter

        providerDropdown.setOnClickListener { showProviderDropdown() }
        locationSegments.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            controller.filterState.location = when (checkedId) {
                R.id.location_device -> VoiceLocation.ON_DEVICE
                R.id.location_network -> VoiceLocation.NETWORK
                else -> VoiceLocation.ALL
            }
            persistFilters()
            renderListAndState()
        }
        resetFilters.setOnClickListener {
            controller.filterState.location = VoiceLocation.ALL
            controller.filterState.selectedFacetValues.clear()
            persistFilters()
            render()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        WindowInsetsUtil.adjustPaddings(
            this, R.id.action_bar,
            EnumSet.of(WindowInsetsUtil.Companion.Flags.STATUS_BAR, WindowInsetsUtil.Companion.Flags.IGNORE_PADDINGS)
        )
        WindowInsetsUtil.adjustPaddings(
            this, R.id.voice_browser_content,
            EnumSet.of(WindowInsetsUtil.Companion.Flags.NAVIGATION_BAR)
        )
    }

    override fun onResume() {
        super.onResume()
        refreshSources()
    }

    override fun onPause() {
        refreshGate.cancel()
        recoveryGate.cancel()
        controller.suspendLoads()
        notice?.dismiss()
        super.onPause()
    }

    override fun onDestroy() {
        controller.shutdown()
        super.onDestroy()
    }

    private fun refreshSources() {
        val token = refreshGate.begin()
        lifecycleScope.launch {
            try {
                val (rows, endpoints) = withContext(Dispatchers.IO) {
                    val rows = SavedTtsSourcesPreferences.getPreferences(this@VoiceBrowserActivity).load()
                        .getOrElse { throw TtsException(TtsFailure(TtsOperation.VOICES, TtsTarget(""), "", TtsFailureKind.STORAGE)) }
                    rows to ApiEndpointPreferences.getApiEndpointPreferences(this@VoiceBrowserActivity)
                        .getApiEndpointsList(this@VoiceBrowserActivity)
                }
                token.deliver {
                    val google = controller.availableProviders.first { it.id == "google" }
                    val apis = rows.map { row -> SavedApiVoiceProvider(this@VoiceBrowserActivity, row,
                        endpoints.firstOrNull { it.id == row.endpointId }?.label ?: row.endpointId,
                        preferences) { failure, retry ->
                            if (controller.browsedProviderId == row.sourceId) showTtsFailure(failure, retry)
                        } }
                    shownLoadFailure = null
                    controller.replaceProviders(listOf(google) + apis, ::renderOnMainThread)
                    val current = preferences.getSelectedTtsVoice()?.sourceId
                    if (firstSourceRefresh && current != null && apis.any { it.id == current }) {
                        firstSourceRefresh = false
                        controller.browse(current, ::renderOnMainThread)
                    }
                    firstSourceRefresh = false
                    apis.forEach { it.loadLabel(::renderOnMainThread) }
                }
                val recovery = selections.reconcile(token)
                token.deliver {
                    val failure = recovery.exceptionOrNull()?.let { (it as? TtsException)?.failure }
                    val suppress = suppressInitialPermanentNotice &&
                        failure?.kind == TtsFailureKind.PERMANENT_UNAVAILABLE
                    suppressInitialPermanentNotice = false
                    if (!suppress) failure?.let(::showTtsFailure)
                    render()
                }
            } catch (_: java.util.concurrent.CancellationException) {
            } catch (error: Exception) {
                token.deliver { showTtsFailure((error as? TtsException)?.failure
                    ?: TtsFailure(TtsOperation.VOICES, TtsTarget(""), "", TtsFailureKind.ENDPOINT_LIST_FAILED), ::refreshSources) }
            }
        }
    }
    private var firstSourceRefresh = true

    private fun activateVoice(voice: BrowserVoice) {
        if (activating) return
        activating = true
        controller.stopPreview()
        notice?.dismiss()
        val next = TtsVoiceSelection(if (voice.providerId == "google") TtsVoiceKind.DEVICE else TtsVoiceKind.API,
            voice.providerId, voice.providerVoiceId, voice.providerModelId)
        lifecycleScope.launch {
            try {
                selections.activate(next).fold(onSuccess = {
                    if (voice.providerId == "google" && !VoiceSelectionExitPolicy.requiresUnavailableVoiceWarning(voice) && voice.canPreview)
                        rememberLastKnownGood(voice)
                    render()
                }, onFailure = { error ->
                    showTtsFailure((error as? TtsException)?.failure ?: TtsFailure(TtsOperation.SPEECH,
                        TtsTarget("", sourceId = next.sourceId, voiceId = next.voiceId), "", TtsFailureKind.SAVE_FAILED)) {
                        if (controller.browsedProviderId == voice.providerId) activateVoice(voice)
                    }
                })
            } finally { activating = false }
        }
    }

    private fun showTtsFailure(failure: TtsFailure, retry: () -> Unit = {}) {
        if (isFinishing || isDestroyed || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (failure.kind in setOf(TtsFailureKind.VOICE_DELETED, TtsFailureKind.SOURCE_MISSING, TtsFailureKind.PROFILE_MISSING) &&
            preferences.getSelectedTtsVoice()?.let { it.sourceId == failure.target.sourceId && it.voiceId == failure.target.voiceId } == true) {
            controller.stopPreview()
            val token = recoveryGate.begin()
            lifecycleScope.launch {
                val result = selections.reconcile(token, failure)
                token.deliver {
                    result.exceptionOrNull()?.let { showTtsFailure((it as TtsException).failure) }
                    render()
                }
            }
            return
        }
        val browsed = controller.browsedProviderId
        notice?.dismiss()
        notice = TtsVoiceDialogs.show(this, preferences.ttsPreferenceScope(),
            if (failure.kind == TtsFailureKind.VOICE_DELETED) failure.copy(kind = TtsFailureKind.VOICE_UNSUPPORTED) else failure) {
            if (browsed == controller.browsedProviderId) retry()
        }
    }

    private fun showProviderDropdown() {
        val providers = controller.availableProviders
        AppDropdown.show(
            providerDropdown,
            providers.map { it.displayName },
            providers.indexOfFirst { it.id == controller.browsedProviderId }
        ) { index ->
            notice?.dismiss()
            shownLoadFailure = null
            adapter.setPreviewing(null, null)
            controller.browse(providers[index].id, ::renderOnMainThread)
        }
    }

    private fun renderOnMainThread() {
        if (isFinishing || isDestroyed) return
        runOnUiThread { if (!isFinishing && !isDestroyed) render() }
    }

    private fun render() {
        providerDropdown.text = controller.provider.displayName
        locationSegments.visibility = if (controller.provider.exposesLocationFilter) View.VISIBLE else View.GONE
        if (controller.provider.exposesLocationFilter) {
            val checked = when (controller.filterState.location) {
                VoiceLocation.ALL -> R.id.location_all
                VoiceLocation.ON_DEVICE -> R.id.location_device
                VoiceLocation.NETWORK -> R.id.location_network
            }
            if (locationSegments.checkedButtonId != checked) locationSegments.check(checked)
        }
        renderFilters(controller.filterDefinitions())
        renderListAndState()
    }

    private fun renderFilters(definitions: List<VoiceFilterDefinition>) {
        filterGrid.removeAllViews()
        definitions.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
            }
            pair.forEach { definition -> row.addView(createFilterView(definition, row)) }
            if (pair.size == 1) row.addView(Space(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
            })
            filterGrid.addView(row)
        }
    }

    private fun createFilterView(definition: VoiceFilterDefinition, parent: LinearLayout): View {
        val view = LayoutInflater.from(this).inflate(R.layout.view_voice_filter, parent, false)
        val label = view.findViewById<TextView>(R.id.filter_label)
        val value = view.findViewById<TextView>(R.id.filter_value)
        label.text = definition.facet.label
        val emptyLabel = if (definition.facet == VoiceFacet.GENDER || definition.facet == VoiceFacet.QUALITY) {
            getString(R.string.voice_browser_all)
        } else getString(R.string.voice_browser_any)
        val selectedId = controller.filterState.selectedFacetValues[definition.facet]
        value.text = definition.options.firstOrNull { it.id == selectedId }?.label ?: emptyLabel
        val labels = listOf(emptyLabel) + definition.options.map { it.label }
        value.setOnClickListener {
            val selectedIndex = definition.options.indexOfFirst { it.id == selectedId } + 1
            AppDropdown.show(value, labels, selectedIndex.coerceAtLeast(0)) { index ->
                if (index == 0) controller.filterState.selectedFacetValues.remove(definition.facet)
                else controller.filterState.selectedFacetValues[definition.facet] = definition.options[index - 1].id
                persistFilters()
                render()
            }
        }
        return view
    }

    private fun renderListAndState() {
        val visible = controller.visibleVoices()
        val activeProviderId = preferences.getSelectedTtsVoice()?.sourceId.orEmpty()
        val activeVoiceId = controller.availableProviders.firstOrNull { it.id == activeProviderId }?.activeVoiceId()
        controller.loadedVoice(activeProviderId, activeVoiceId)?.let { activeVoice ->
            if (activeVoice.providerId == "google" && !VoiceSelectionExitPolicy.requiresUnavailableVoiceWarning(activeVoice) && activeVoice.canPreview) {
                rememberLastKnownGood(activeVoice)
            }
        }
        adapter.submit(visible, activeProviderId, activeVoiceId, controller.filterState)
        voiceCount.text = getString(R.string.voice_browser_count, visible.size)

        when (val state = controller.loadState) {
            VoiceLoadState.Loading -> showState(message = null, showLoading = true, allowReset = false)
            is VoiceLoadState.Failed -> {
                val failure = (state.cause as? TtsException)?.failure
                showState(message = if (controller.browsedProviderId.startsWith("api-tts:"))
                    getString(R.string.tts_voices_currently_unavailable)
                    else getString(R.string.voice_browser_load_failed, state.message),
                    showLoading = false, allowReset = false)
                if (failure != null && shownLoadFailure !== state.cause) {
                    shownLoadFailure = state.cause
                    val sourceId = controller.browsedProviderId
                    showTtsFailure(failure) {
                        if (controller.browsedProviderId == sourceId) controller.load(::renderOnMainThread)
                    }
                }
            }
            is VoiceLoadState.Ready -> when {
                state.voices.isEmpty() -> showState(
                    getString(R.string.voice_browser_provider_empty), showLoading = false, allowReset = false
                )
                visible.isEmpty() -> showState(
                    getString(R.string.voice_browser_no_matches), showLoading = false, allowReset = true
                )
                else -> hideState()
            }
        }
    }

    private fun showState(message: String?, showLoading: Boolean, allowReset: Boolean) {
        stateView.visibility = View.VISIBLE
        voicesList.visibility = View.INVISIBLE
        loading.visibility = if (showLoading) View.VISIBLE else View.GONE
        stateMessage.visibility = if (message == null) View.GONE else View.VISIBLE
        stateMessage.text = message.orEmpty()
        resetFilters.visibility = if (allowReset) View.VISIBLE else View.GONE
    }

    private fun hideState() {
        stateView.visibility = View.GONE
        voicesList.visibility = View.VISIBLE
    }

    private fun showActionError(message: String) {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.voice_browser_action_failed)
            .setMessage(message)
            .setPositiveButton(R.string.btn_ok, null)
            .show()
    }

    private fun attemptChevronExit() {
        val activeProviderId = preferences.getSelectedTtsVoice()?.sourceId.orEmpty()
        val activeVoiceId = controller.availableProviders.firstOrNull { it.id == activeProviderId }?.activeVoiceId()
        val activeVoice = controller.loadedVoice(activeProviderId, activeVoiceId)
        if (!VoiceSelectionExitPolicy.requiresUnavailableVoiceWarning(activeVoice)) {
            finish()
            return
        }

        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setMessage(R.string.voice_browser_selected_not_downloaded)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.btn_ok) { _, _ ->
                val fallback = lastKnownGoodVoiceRegistry.load()
                    ?: controller.firstUsableLoadedVoice()?.let(::selectionFor)
                if (fallback != null) {
                    lastKnownGoodVoiceRegistry.save(fallback)
                    restoreSelection(fallback)
                }
                finish()
            }
            .show()
    }

    private fun persistFilters() {
        preferences.setVoiceBrowserFilters(
            controller.browsedProviderId,
            VoiceFilterStatePersistence.encode(controller.filterState)
        )
    }

    private fun rememberLastKnownGood(voice: BrowserVoice) {
        lastKnownGoodVoiceRegistry.save(selectionFor(voice))
    }

    private fun restoreSelection(selection: LastKnownGoodVoiceSelection) {
        when (selection.providerId) {
            "google" -> preferences.setVoice(selection.providerVoiceId)
            "openai" -> {
                preferences.setOpenAIVoice(selection.providerVoiceId)
                selection.providerModelId?.let(preferences::setOpenAITtsModel)
            }
            else -> return
        }
        preferences.setTtsEngine(selection.providerId)
    }

    private fun selectionFor(voice: BrowserVoice) = LastKnownGoodVoiceSelection(
        providerId = voice.providerId,
        providerVoiceId = voice.providerVoiceId,
        providerModelId = voice.providerModelId
    )

    private fun showVoiceIdentityDialog(voice: BrowserVoice) {
        val content = layoutInflater.inflate(R.layout.dialog_voice_identity, null)
        val name = content.findViewById<TextInputEditText>(R.id.voice_identity_name)
        val genderGroup = content.findViewById<RadioGroup>(R.id.voice_identity_gender_group)
        name.setText(voice.displayName)
        content.findViewById<TextView>(R.id.voice_identity_identifiers).text =
            listOf(voice.originalDisplayName, voice.providerVoiceId)
                .filter(String::isNotBlank)
                .joinToString("  ·  ")

        val currentGenderId = voice.gender?.id
        genderGroup.check(when (currentGenderId) {
            "female" -> R.id.voice_identity_female
            "male" -> R.id.voice_identity_male
            "neutral" -> R.id.voice_identity_neutral
            else -> View.NO_ID
        })

        content.findViewById<MaterialButton>(R.id.voice_identity_preview).setOnClickListener {
            controller.preview(
                voice,
                previewText.text?.toString()?.takeIf(String::isNotBlank) ?: VoicePreviewText.DEFAULT,
                ::showActionError,
                ::renderOnMainThread
            )
        }

        val dialog = MaterialAlertDialogBuilder(this, R.style.App_VoiceIdentityDialog)
            .setView(content)
            .setNegativeButton(R.string.voice_identity_cancel, null)
            .setPositiveButton(R.string.voice_identity_save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val selectedGender = when (genderGroup.checkedRadioButtonId) {
                    R.id.voice_identity_female -> "female"
                    R.id.voice_identity_male -> "male"
                    R.id.voice_identity_neutral -> "neutral"
                    else -> null
                }
                val updated = identityRegistry.save(
                    voice = voice,
                    displayName = name.text?.toString().orEmpty(),
                    genderId = selectedGender
                )
                controller.updateVoice(updated)
                render()
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}
