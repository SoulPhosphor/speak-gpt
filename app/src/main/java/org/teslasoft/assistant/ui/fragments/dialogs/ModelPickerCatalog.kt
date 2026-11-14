package org.teslasoft.assistant.ui.fragments.dialogs

import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.dto.ApiEndpointObject
import org.teslasoft.assistant.providers.ModelBrowserCatalog
import org.teslasoft.assistant.providers.ModelBrowserCatalogClient
import org.teslasoft.assistant.providers.ModelBrowserEntry
import org.teslasoft.assistant.providers.ModelBrowserProvider
import org.teslasoft.assistant.providers.newModelCutoff
import org.teslasoft.assistant.ui.widgets.AppDropdown
import org.teslasoft.assistant.ui.widgets.SegmentedPill
import java.time.Instant

/** Owns the scrollable controls and requests for the chat picker's View All tab. */
internal class ModelPickerCatalog(
    val header: View,
    private val profiles: List<ApiEndpointObject>,
    initialProfileId: String,
    private val scope: CoroutineScope,
    private val onChanged: () -> Unit,
    private val onFailure: (ApiEndpointObject, Exception) -> Unit,
    private val onCatalogReceived: (ApiEndpointObject, String) -> Unit,
    private val rawCatalog: Boolean = false
) {
    private enum class Filter { ALL, NEW, FREE }

    private enum class Sort(val label: Int) {
        CATALOG(R.string.model_browser_catalog_order),
        ALPHABETICAL(R.string.model_browser_alphabetical),
        NEWEST(R.string.mem_filter_sort_newest)
    }

    private val profileRow = header.findViewById<View>(R.id.model_browser_profile_row)
    private val profileValue = header.findViewById<TextView>(R.id.model_browser_profile)
    private val searchScope = header.findViewById<TextView>(R.id.model_browser_search_scope)
    private val searchDivider = header.findViewById<View>(R.id.model_browser_search_divider)
    private val searchField = header.findViewById<TextInputEditText>(R.id.model_browser_search)
    private val filterRow = header.findViewById<SegmentedPill>(R.id.model_browser_filters)
    private val filterButtons = Filter.entries.zip(
        listOf(R.id.model_browser_all, R.id.model_browser_new, R.id.model_browser_free).map {
            header.findViewById<TextView>(it)
        }
    )
    private val sortRow = header.findViewById<View>(R.id.model_browser_sort_row)
    private val sortValue = header.findViewById<TextView>(R.id.model_browser_sort)
    private var client: ModelBrowserCatalogClient? = null
    private var pageJob: Job? = null
    private var directoryJob: Job? = null
    private var providerSearchJob: Job? = null
    private var entries = linkedMapOf<String, ModelBrowserEntry>()
    private var providerDirectory = emptyList<ModelBrowserProvider>()
    private var providerMatches = emptySet<String>()
    private var nextPage: Int? = null
    private var filter = Filter.ALL
    private var sort = Sort.CATALOG
    private var searchProviders = false
    private var query = ""
    private var paginated = false
    private var hasModels = false
    private var hasDates = false
    private var hasNew = false
    private var hasFree = false
    private var hasInlineProviders = false
    private var pageLoading = false
    private var providerLoading = false

    var profile = profiles.firstOrNull { it.id == initialProfileId } ?: profiles.firstOrNull()
        private set
    val loading get() = pageLoading || providerLoading

    init {
        profileValue.setOnClickListener {
            AppDropdown.show(profileValue, profiles.map(::profileLabel), profiles.indexOfFirst { it.id == profile?.id }) { index ->
                profile = profiles[index]
                restart()
            }
        }
        searchScope.setOnClickListener {
            AppDropdown.show(
                searchScope,
                listOf(text(R.string.model_browser_ai_models), text(R.string.provider_col_provider)),
                if (searchProviders) 1 else 0
            ) { index ->
                searchProviders = index == 1
                searchChanged()
            }
        }
        searchField.doAfterTextChanged {
            query = it.toString().trim()
            searchChanged()
        }
        filterButtons.forEach { (choice, button) ->
            button.setOnClickListener {
                filter = choice
                if (client?.supportsServerSearch == true) {
                    if (choice == Filter.NEW) sort = Sort.NEWEST
                    reloadPage()
                } else update()
            }
        }
        sortValue.setOnClickListener {
            val options = sortOptions()
            AppDropdown.show(sortValue, options.map { text(it.label) }, options.indexOf(sort)) { index ->
                sort = options[index]
                if (client?.supportsServerSearch == true) reloadPage()
                else update()
            }
        }
        renderControls()
    }

    fun restart() {
        stop()
        val selected = profile ?: return
        entries.clear()
        providerDirectory = emptyList()
        providerMatches = emptySet()
        hasModels = false
        hasDates = false
        hasNew = false
        hasFree = false
        hasInlineProviders = false
        paginated = false
        filter = Filter.ALL
        searchProviders = false
        query = ""
        if (searchField.text?.isNotEmpty() == true) searchField.setText("")
        val transport = try {
            ModelBrowserCatalogClient(selected)
        } catch (failure: IllegalArgumentException) {
            update()
            onFailure(selected, failure)
            return
        }
        client = transport
        // Featherless is paged, so request its newest page before deciding
        // whether the catalog has any entries recent enough for the New chip.
        sort = if (transport.supportsServerSearch) Sort.NEWEST else Sort.CATALOG
        reloadPage()
        if (transport.supportsProviderDirectory) {
            directoryJob = scope.launch {
                try {
                    providerDirectory = transport.providers()
                    update()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Directory data is optional. Inline catalog providers, if
                    // present, remain usable without a second endpoint.
                }
            }
        }
    }

    fun stop() {
        pageJob?.cancel()
        directoryJob?.cancel()
        providerSearchJob?.cancel()
        client = null
        nextPage = null
        pageLoading = false
        providerLoading = false
    }

    fun loadMore() {
        val page = nextPage ?: return
        if (!pageLoading) requestPage(page)
    }

    fun visibleEntries(): List<ModelBrowserEntry> {
        val now = Instant.now()
        val visible = entries.values.filter { entry ->
            val matchesSearch = when {
                query.isBlank() -> true
                searchProviders -> entry.id in providerMatches || entry.providers.any { it.matches(query) }
                client?.supportsServerSearch == true -> true
                else -> entry.matchesModel(query)
            }
            matchesSearch && when (filter) {
                Filter.ALL -> true
                Filter.NEW -> entry.isNew(now)
                Filter.FREE -> entry.isFree
            }
        }
        return when (sort) {
            Sort.CATALOG -> visible
            Sort.ALPHABETICAL -> visible.sortedBy { it.id.lowercase() }
            Sort.NEWEST -> if (client?.supportsServerSearch == true) visible
                else visible.sortedWith(compareByDescending { it.created })
        }
    }

    private fun searchChanged() {
        providerSearchJob?.cancel()
        providerLoading = false
        providerMatches = emptySet()
        if (client?.supportsServerSearch == true && !searchProviders) {
            reloadPage(debounce = true)
        } else {
            if (searchProviders && query.isNotBlank()) searchDirectory()
            update()
        }
    }

    private fun searchDirectory() {
        val transport = client ?: return
        val selected = profile ?: return
        val providers = providerDirectory.filter { it.matches(query) }
        if (providers.isEmpty()) return
        providerLoading = true
        providerSearchJob = scope.launch {
            try {
                delay(SEARCH_DELAY_MS)
                val body = transport.models(1, "", newestFirst = false, providers = providers)
                providerMatches = withContext(Dispatchers.Default) {
                    ModelBrowserCatalog.parse(body, includeAllModels = rawCatalog).entries.mapTo(HashSet()) { it.id }
                }
                providerLoading = false
                update()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                providerLoading = false
                update()
                onFailure(selected, failure)
            }
        }
    }

    private fun reloadPage(debounce: Boolean = false) {
        pageJob?.cancel()
        entries.clear()
        nextPage = null
        requestPage(1, debounce)
    }

    private fun requestPage(page: Int, debounce: Boolean = false) {
        val transport = client ?: return
        val selected = profile ?: return
        val search = if (searchProviders) "" else query
        val newestFirst = sort == Sort.NEWEST || filter == Filter.NEW
        pageLoading = true
        pageJob = scope.launch {
            try {
                if (debounce) delay(SEARCH_DELAY_MS)
                val body = transport.models(page, search, newestFirst)
                val response = withContext(Dispatchers.Default) { ModelBrowserCatalog.parse(body, page, includeAllModels = rawCatalog) }
                response.entries.forEach { entries[it.id] = it }
                paginated = paginated || response.nextPage != null
                nextPage = response.nextPage
                if (filter == Filter.NEW && transport.supportsServerSearch) {
                    val cutoff = newModelCutoff(Instant.now())
                    if (response.entries.any { it.created?.isBefore(cutoff) == true }) nextPage = null
                }
                updateCapabilities(response.entries)
                if (page == 1 && transport.supportsProviderDirectory) onCatalogReceived(selected, body)
                pageLoading = false
                update()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                nextPage = null
                pageLoading = false
                update()
                onFailure(selected, failure)
            }
        }
        update()
    }

    private fun updateCapabilities(page: List<ModelBrowserEntry>) {
        val now = Instant.now()
        hasModels = hasModels || page.isNotEmpty()
        hasDates = hasDates || page.any { it.created != null }
        hasNew = hasNew || page.any { it.isNew(now) }
        hasFree = hasFree || page.any { it.isFree }
        hasInlineProviders = hasInlineProviders || entries.values.asSequence()
            .flatMap { it.providers.asSequence() }.map { it.id.lowercase() }.distinct().take(2).count() > 1
    }

    private fun sortOptions(): List<Sort> = buildList {
        if (filter == Filter.NEW && client?.supportsServerSearch == true) {
            add(Sort.NEWEST)
            return@buildList
        }
        add(Sort.CATALOG)
        if (!paginated) add(Sort.ALPHABETICAL)
        if (hasDates && (!paginated || client?.supportsServerSearch == true)) add(Sort.NEWEST)
    }

    private fun renderControls() {
        profileRow.isVisible = profiles.size > 1
        profileValue.text = profile?.let(::profileLabel)
        searchScope.isVisible = hasInlineProviders || providerDirectory.size > 1
        searchDivider.isVisible = searchScope.isVisible
        searchScope.setText(if (searchProviders) R.string.provider_col_provider else R.string.model_browser_ai_models)
        searchField.setHint(if (searchProviders) R.string.model_browser_search_providers else R.string.label_search_models)
        val showNew = hasNew && (!paginated || client?.supportsServerSearch == true)
        val showFree = hasFree && !paginated
        filterRow.isVisible = showNew || showFree
        filterButtons.forEach { (choice, button) ->
            button.isVisible = when (choice) {
                Filter.ALL -> true
                Filter.NEW -> showNew
                Filter.FREE -> showFree
            }
        }
        filterButtons.firstOrNull { it.first == filter }?.second?.let { filterRow.select(it, animate = true) }
        sortRow.isVisible = hasModels && sortOptions().size > 1
        sortValue.setText(sort.label)
    }

    private fun update() {
        renderControls()
        onChanged()
    }

    private fun profileLabel(endpoint: ApiEndpointObject): String = endpoint.label.ifBlank { endpoint.host }

    private fun text(id: Int): String = header.context.getString(id)

    private companion object {
        const val SEARCH_DELAY_MS = 300L
    }
}
