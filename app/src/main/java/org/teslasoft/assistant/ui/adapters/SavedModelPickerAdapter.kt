package org.teslasoft.assistant.ui.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.isVisible
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject
import org.teslasoft.assistant.preferences.models.ModelIdentity
import org.teslasoft.assistant.reasoning.EndpointReasoningCapabilityIndex
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.ui.activities.ModelParametersActivity
import org.teslasoft.assistant.ui.activities.ReasoningSettingsActivity
import org.teslasoft.assistant.ui.util.CompactActionPopup
import org.teslasoft.assistant.ui.widgets.AppDropdown

internal class SavedModelPickerAdapter(
    private val context: Context,
    private val chatId: String,
    private val selected: ModelIdentity,
    private val unavailable: Set<ModelIdentity>,
    private val onSelect: (Row, Source) -> Unit,
    /** A provider picked from the row's provider dropdown. */
    private val onProvider: (Row, Source, String) -> Unit,
    private val onRoute: (ModelIdentity) -> Unit,
    private val onPin: (FavoriteModelObject, Boolean) -> Unit,
    private val onFavorite: (FavoriteModelObject) -> Unit,
    private val onDelete: (ModelIdentity, Source) -> Unit
) : BaseAdapter() {

    enum class Source { FAVORITES, RECENT, CATALOG }

    data class Row(
        val settings: FavoriteModelObject,
        val profileName: String,
        val routingLabel: String,
        val supportsRouting: Boolean,
        val reasoning: EndpointReasoningCapabilityIndex,
        val saved: Boolean,
        val pinned: Boolean,
        val advertisesReasoning: Boolean = false,
        /** The provider dropdown's choices (slugs): the model's favorite
         *  providers on a Favorites row, its last five used on a Recent row. */
        val providerOptions: List<String> = emptyList(),
        /** Display names for [providerOptions]. */
        val providerNames: List<String> = emptyList()
    ) {
        val identity = ModelIdentity(settings.endpointId, settings.modelId)

        fun matches(query: String, subtitle: String): Boolean =
            settings.modelId.contains(query, ignoreCase = true) ||
                subtitle.contains(query, ignoreCase = true) ||
                (settings.routingType == FavoriteModelObject.ROUTING_ONLY &&
                    settings.selectedProvider.contains(query, ignoreCase = true))
    }

    private var rows: List<Row> = emptyList()
    private val modelSettingsEnabled = Preferences.getPreferences(context, "").getModelSettingsEnabled()
    private var source = Source.FAVORITES

    fun submit(rows: List<Row>, query: String, source: Source) {
        this.source = source
        this.rows = if (query.isBlank()) rows else rows.filter { it.matches(query, subtitle(it)) }
        notifyDataSetChanged()
    }

    override fun getCount() = rows.size
    override fun getItem(position: Int) = rows[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.view_saved_model, parent, false)
            .also { it.tag = Holder(it) }
        val holder = view.tag as Holder
        val row = getItem(position)
        val source = this.source
        val catalog = source == Source.CATALOG
        val settings = row.settings
        val target = row.identity
        val capability = row.reasoning.resolve(target.modelId)
        val routingConfigured = settings.routingType != FavoriteModelObject.ROUTING_AUTOMATIC ||
            settings.ignoredProviders.isNotEmpty()

        holder.card.isActivated = target == selected
        holder.card.setOnClickListener { onSelect(row, source) }
        holder.name.text = target.modelId
        holder.subtitle.isVisible = !catalog
        holder.profile.text = if (catalog) null
            else context.getString(R.string.model_picker_profile_prefix, row.profileName)
        // The provider part is a dropdown when the row has providers to pick.
        val hasProviderChoice = !catalog && row.providerOptions.isNotEmpty()
        holder.route.isVisible = !catalog && !hasProviderChoice
        holder.route.text = row.routingLabel
        holder.providerDropdown.isVisible = hasProviderChoice
        holder.providerDropdown.text = row.routingLabel
        holder.providerDropdown.contentDescription =
            context.getString(R.string.model_picker_provider_desc, target.modelId)
        holder.providerDropdown.setOnClickListener {
            val current = row.providerOptions.indexOfFirst {
                settings.routingType == FavoriteModelObject.ROUTING_ONLY &&
                    it.equals(settings.selectedProvider, ignoreCase = true)
            }
            AppDropdown.show(holder.providerDropdown, row.providerNames, current, outlineAnchor = false) { position ->
                onProvider(row, source, row.providerOptions[position])
            }
        }
        holder.warning.isVisible = !catalog && target in unavailable

        holder.pin.isVisible = !catalog && row.pinned
        holder.pin.setOnClickListener { onPin(settings, false) }

        val showsReasoning = capability.isReasoningCapable || row.advertisesReasoning
        val canConfigure = !catalog && capability.hasConfigurableSetting
        holder.reasoningIndicator.isVisible = showsReasoning && !canConfigure
        holder.reasoning.isVisible = showsReasoning && canConfigure
        holder.reasoning.setOnClickListener {
            context.startActivity(ReasoningSettingsActivity.createIntent(context, target.modelId, target.endpointId))
        }

        holder.routing.isVisible = row.supportsRouting
        holder.routing.contentDescription = context.getString(R.string.favorite_routing_settings_desc, target.modelId)
        holder.routing.setImageResource(if (routingConfigured) R.drawable.ic_settings else R.drawable.ic_settings_outline)
        holder.routing.setOnClickListener { onRoute(target) }

        holder.menu.setOnClickListener { anchor ->
            val popup = CompactActionPopup(context, anchor)
            if (source == Source.FAVORITES) {
                popup.add(
                    PIN, 0,
                    context.getString(if (row.pinned) R.string.model_picker_unfavorite else R.string.model_picker_favorite)
                )
            } else {
                popup.add(PIN, 0, context.getString(R.string.chat_menu_pin), enabled = !row.pinned)
                popup.add(FAVORITE, 1, context.getString(R.string.model_picker_favorite), enabled = !row.saved)
            }
            if (!catalog && modelSettingsEnabled) {
                popup.add(MODEL_SETTINGS, 2, context.getString(R.string.model_picker_model_settings))
            }
            if (!catalog) popup.add(DELETE, 3, context.getString(R.string.btn_delete))
            popup.onAction { action ->
                when (action) {
                    PIN -> onPin(settings, !row.pinned)
                    FAVORITE -> onFavorite(settings)
                    MODEL_SETTINGS -> context.startActivity(
                        ModelParametersActivity.createIntent(context, target.modelId, target.endpointId, chatId)
                    )
                    DELETE -> onDelete(target, source)
                }
                true
            }.show()
        }
        return view
    }

    private fun subtitle(row: Row): String =
        context.getString(R.string.model_picker_routing_subtitle, row.profileName, row.routingLabel)

    private class Holder(view: View) {
        val card: View = view.findViewById(R.id.model_picker_card)
        val name: TextView = view.findViewById(R.id.model_picker_model_name)
        val subtitle: View = view.findViewById(R.id.model_picker_model_subtitle)
        val profile: TextView = view.findViewById(R.id.model_picker_profile)
        val route: TextView = view.findViewById(R.id.model_picker_route)
        val providerDropdown: TextView = view.findViewById(R.id.model_picker_provider_dropdown)
        val pin: ImageButton = view.findViewById(R.id.model_picker_pin)
        val warning: View = view.findViewById(R.id.model_picker_unavailable)
        val reasoningIndicator: View = view.findViewById(R.id.model_picker_reasoning_indicator)
        val reasoning: ImageButton = view.findViewById(R.id.btn_reasoning_settings)
        val routing: ImageButton = view.findViewById(R.id.btn_routing_settings)
        val menu: ImageButton = view.findViewById(R.id.model_picker_menu)
    }

    private companion object {
        const val PIN = 1
        const val FAVORITE = 2
        const val MODEL_SETTINGS = 4
        const val DELETE = 3
    }
}
