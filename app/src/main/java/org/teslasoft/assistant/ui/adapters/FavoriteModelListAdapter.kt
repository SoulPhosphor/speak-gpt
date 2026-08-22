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

package org.teslasoft.assistant.ui.adapters

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.FavoriteModelsPreferences
import org.teslasoft.assistant.preferences.Preferences
import org.teslasoft.assistant.preferences.dto.FavoriteModelObject
import org.teslasoft.assistant.preferences.models.ModelCleanupReportStore
import org.teslasoft.assistant.preferences.models.ModelIdentity

/** ListView adapter to display list of voices */
/**
 * [showRoutingGear] turns on the per-row provider-routing gear (OpenRouter
 * favorites only). Off by default so this shared list looks unchanged wherever
 * favorites are shown; the dedicated Favorite AI Models list opts in.
 */
class FavoriteModelListAdapter(private val context: Context, private val items: ArrayList<Map<String, String>>, private var chatId: String, private val showRoutingGear: Boolean = false) : BaseAdapter() {

    private var listener: OnItemClickListener? = null
    private val unavailableTargets = ModelCleanupReportStore.get(context).load().unavailable

    override fun getCount(): Int {
        return items.size
    }

    override fun getItem(position: Int): Any {
        return items[position]
    }

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val viewHolder: ViewHolder
        val view: View

        if (convertView == null) {
            view = LayoutInflater.from(context).inflate(R.layout.view_model, parent, false)
            viewHolder = ViewHolder(view)
            view.tag = viewHolder
        } else {
            view = convertView
            viewHolder = view.tag as ViewHolder
        }

        val item = getItem(position) as Map<String, String>
        val modelId = item["modelId"]!!
        val endpointId = item["endpointId"]!!
        viewHolder.textView.text = modelId
        viewHolder.unavailableWarning.visibility = if (
            ModelIdentity(endpointId, modelId) in unavailableTargets
        ) View.VISIBLE else View.GONE

        val preferences: Preferences = Preferences.getPreferences(context, chatId)

        val rowTextColor: Int
        if (preferences.getModel() == modelId) {
            viewHolder.voiceBg.background = getDarkAccentDrawableV2(
                ContextCompat.getDrawable(context, R.drawable.btn_accent_tonal_selector_v4)!!, context)

            rowTextColor = ContextCompat.getColor(context, R.color.accent_250)
            viewHolder.textView.setTextColor(rowTextColor)

            viewHolder.modelAction.setImageResource(R.drawable.ic_close_item_inv)
        } else {
            viewHolder.voiceBg.background = getDarkAccentDrawable(
                ContextCompat.getDrawable(context, R.drawable.btn_accent_tonal_selector_v3)!!, context)

            rowTextColor = ContextCompat.getColor(context, R.color.text)
            viewHolder.textView.setTextColor(rowTextColor)

            viewHolder.modelAction.setImageResource(R.drawable.ic_close_item)
        }

        viewHolder.modelAction.tooltipText = context.getString(R.string.label_remove_from_favorites)
        viewHolder.modelAction.contentDescription = context.getString(R.string.label_remove_from_favorites)

        bindRoutingGear(viewHolder, modelId, endpointId, rowTextColor)
        bindReasoningLightbulb(viewHolder, modelId, endpointId, rowTextColor)

        viewHolder.voiceBg.setOnClickListener {
            listener?.onItemClick(modelId, endpointId)
        }

        viewHolder.modelAction.setOnClickListener {
            listener?.onActionClick(modelId, endpointId, position)
        }

        return view
    }

    /**
     * The provider-routing gear, shown only for a favorite whose endpoint is an
     * OpenRouter endpoint. A FILLED gear means the model already has routing set
     * up — Only mode, Preferred mode, or at least one banned (ignored) provider;
     * an OUTLINE gear means it is still on the plain Automatic default with no
     * banned providers. Tapping it opens the Choose Provider screen for the
     * model with those settings loaded. Non-OpenRouter favorites show no gear.
     */
    private fun bindRoutingGear(viewHolder: ViewHolder, modelId: String, endpointId: String, tintColor: Int) {
        if (!showRoutingGear) {
            viewHolder.routingSettings.visibility = View.GONE
            viewHolder.routingSettings.setOnClickListener(null)
            return
        }

        val endpoint = ApiEndpointPreferences.getApiEndpointPreferences(context).getApiEndpoint(context, endpointId)
        if (!endpoint.isOpenRouterRouting()) {
            viewHolder.routingSettings.visibility = View.GONE
            viewHolder.routingSettings.setOnClickListener(null)
            return
        }

        val favorite = FavoriteModelsPreferences.getPreferences(context).getFavorite(modelId, endpointId)
        val routingSetUp = favorite != null && (
            favorite.routingType == FavoriteModelObject.ROUTING_ONLY ||
            favorite.routingType == FavoriteModelObject.ROUTING_PREFERRED ||
            favorite.ignoredProviders.isNotEmpty()
        )

        viewHolder.routingSettings.setImageResource(
            if (routingSetUp) R.drawable.ic_settings else R.drawable.ic_settings_outline
        )
        viewHolder.routingSettings.imageTintList = ColorStateList.valueOf(tintColor)
        viewHolder.routingSettings.tooltipText = context.getString(R.string.favorite_routing_settings_desc, modelId)
        viewHolder.routingSettings.contentDescription = context.getString(R.string.favorite_routing_settings_desc, modelId)
        viewHolder.routingSettings.visibility = View.VISIBLE
        viewHolder.routingSettings.setOnClickListener {
            listener?.onSettingsClick(modelId, endpointId)
        }
    }

    /**
     * The actionable reasoning lightbulb (chat-redesign-plan.md §7.4). Shown for
     * a favorite whose model/provider path exposes at least one reasoning
     * setting (an effort choice and/or Show Reasoning). Tapping opens the
     * dedicated Reasoning Settings screen. Independent of the routing gear —
     * both may appear on the same favorite.
     */
    private fun bindReasoningLightbulb(viewHolder: ViewHolder, modelId: String, endpointId: String, tintColor: Int) {
        val endpoint = ApiEndpointPreferences.getApiEndpointPreferences(context).getApiEndpoint(context, endpointId)
        val capability = org.teslasoft.assistant.reasoning.EndpointReasoningCapability.resolve(
            endpoint.reasoningCapabilityByModel,
            modelId,
            providerPath = org.teslasoft.assistant.reasoning.ReasoningProviderPath.forEndpoint(
                endpoint.host,
                endpoint.isOpenRouterRouting()
            )
        )
        if (!capability.isReasoningCapable) {
            viewHolder.reasoningSettings.visibility = View.GONE
            viewHolder.reasoningSettings.setOnClickListener(null)
            return
        }
        viewHolder.reasoningSettings.imageTintList = ColorStateList.valueOf(tintColor)
        viewHolder.reasoningSettings.visibility = View.VISIBLE
        if (capability.hasConfigurableSetting) {
            viewHolder.reasoningSettings.alpha = 1f
            viewHolder.reasoningSettings.scaleX = 1f
            viewHolder.reasoningSettings.scaleY = 1f
            viewHolder.reasoningSettings.isClickable = true
            viewHolder.reasoningSettings.isFocusable = true
            viewHolder.reasoningSettings.tooltipText = context.getString(R.string.reasoning_settings_bulb_desc)
            viewHolder.reasoningSettings.contentDescription = context.getString(R.string.reasoning_settings_bulb_desc)
            viewHolder.reasoningSettings.setOnClickListener {
                context.startActivity(
                    org.teslasoft.assistant.ui.activities.ReasoningSettingsActivity.createIntent(context, modelId, endpointId)
                )
            }
        } else {
            // A fixed reasoning model keeps the bulb's identity meaning but has
            // no empty settings screen to open.
            viewHolder.reasoningSettings.alpha = 0.55f
            viewHolder.reasoningSettings.scaleX = 0.82f
            viewHolder.reasoningSettings.scaleY = 0.82f
            viewHolder.reasoningSettings.isClickable = false
            viewHolder.reasoningSettings.isFocusable = false
            viewHolder.reasoningSettings.tooltipText = null
            viewHolder.reasoningSettings.contentDescription = context.getString(R.string.reasoning_model_indicator_desc)
            viewHolder.reasoningSettings.setOnClickListener(null)
        }
    }

    private fun getDarkAccentDrawable(drawable: Drawable, context: Context) : Drawable {
        DrawableCompat.setTint(DrawableCompat.wrap(drawable), getSurfaceColor(context))
        return drawable
    }

    private fun getDarkAccentDrawableV2(drawable: Drawable, context: Context) : Drawable {
        DrawableCompat.setTint(DrawableCompat.wrap(drawable), getSurfaceColorV2(context))
        return drawable
    }

    private fun getSurfaceColor(context: Context) : Int {
        return context.getColor(android.R.color.transparent)
    }

    private fun getSurfaceColorV2(context: Context) : Int {
        return context.getColor(R.color.accent_900)
    }

    private class ViewHolder(view: View) {
        val textView: TextView = view.findViewById(R.id.voice_name)
        val voiceBg: ConstraintLayout = view.findViewById(R.id.voice_bg)
        val modelAction: ImageButton = view.findViewById(R.id.btn_action)
        val routingSettings: ImageButton = view.findViewById(R.id.btn_routing_settings)
        val reasoningSettings: ImageButton = view.findViewById(R.id.btn_reasoning_settings)
        val unavailableWarning: ImageView = view.findViewById(R.id.model_unavailable_warning)
    }

    interface OnItemClickListener {
        fun onItemClick(model: String, endpointId: String)
        fun onActionClick(model: String, endpointId: String, position: Int)

        /** The provider-routing gear was tapped (OpenRouter favorites only). */
        fun onSettingsClick(model: String, endpointId: String)
    }

    fun setOnItemClickListener(listener: OnItemClickListener) {
        this.listener = listener
    }
}
