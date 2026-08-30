package org.teslasoft.assistant.ui.adapters

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import org.teslasoft.assistant.R
import org.teslasoft.assistant.tts.voices.BrowserVoice
import org.teslasoft.assistant.tts.voices.VoiceFilterState
import org.teslasoft.assistant.tts.voices.VoiceLocation

class VoiceListAdapter(
    private val onSelect: (BrowserVoice) -> Unit,
    private val onLongPress: (BrowserVoice) -> Unit,
    private val onPreview: (BrowserVoice) -> Unit,
    private val onStopPreview: (BrowserVoice) -> Unit,
    private val onDownload: (BrowserVoice) -> Unit
) : RecyclerView.Adapter<VoiceListAdapter.ViewHolder>() {
    private var voices: List<BrowserVoice> = emptyList()
    private var selectedProviderId: String? = null
    private var selectedVoiceId: String? = null
    private var filters = VoiceFilterState()
    private var previewingProviderId: String? = null
    private var previewingVoiceId: String? = null
    private var loadingProviderId: String? = null
    private var loadingVoiceId: String? = null

    /** Marks the voice currently sounding so its row shows Stop instead of Preview. */
    fun setPreviewing(providerId: String?, voiceId: String?) {
        if (previewingProviderId == providerId && previewingVoiceId == voiceId) return
        previewingProviderId = providerId
        previewingVoiceId = voiceId
        notifyDataSetChanged()
    }

    /**
     * Marks the voice whose preview was requested but has not started sounding yet.
     * That row shows a green Preview so the user sees the request landed, and
     * [isLoading] lets the caller drop further taps until this clears.
     */
    fun setLoading(providerId: String?, voiceId: String?) {
        if (loadingProviderId == providerId && loadingVoiceId == voiceId) return
        loadingProviderId = providerId
        loadingVoiceId = voiceId
        notifyDataSetChanged()
    }

    fun isLoading(): Boolean = loadingVoiceId != null

    fun submit(voices: List<BrowserVoice>, selectedProviderId: String?, selectedVoiceId: String?, filters: VoiceFilterState) {
        this.voices = voices
        this.selectedProviderId = selectedProviderId
        this.selectedVoiceId = selectedVoiceId
        this.filters = filters.copy(selectedFacetValues = filters.selectedFacetValues.toMutableMap())
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.view_voice, parent, false)
    )

    override fun getItemCount(): Int = voices.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val voice = voices[position]
        val selected = selectedProviderId == voice.providerId && selectedVoiceId == voice.providerVoiceId
        holder.name.text = voice.displayName
        holder.selected.visibility = View.VISIBLE
        holder.selected.setImageResource(if (selected) R.drawable.ic_check_circle else R.drawable.ic_circle_outline)
        ImageViewCompat.setImageTintList(
            holder.selected,
            ColorStateList.valueOf(
                MaterialColors.getColor(
                    holder.selected,
                    if (selected) androidx.appcompat.R.attr.colorPrimary else R.attr.appSubtleTextColor
                )
            )
        )

        val metadata = buildList {
            (voice.userAssignedGender ?: voice.gender)?.label?.let(::add)
            voice.quality?.label?.let { add("$it Quality") }
            if (filters.location == VoiceLocation.ALL) {
                when (voice.requiresNetwork) {
                    true -> add("Network")
                    false -> add("On-device")
                    null -> Unit
                }
            }
        }.joinToString(" · ")
        holder.metadata.text = metadata
        holder.metadata.visibility = if (metadata.isBlank()) View.GONE else View.VISIBLE

        holder.row.contentDescription = if (selected) {
            holder.itemView.context.getString(R.string.voice_browser_selected_voice, voice.displayName)
        } else {
            holder.itemView.context.getString(R.string.voice_browser_voice_row, voice.displayName, metadata)
        }
        holder.row.setOnClickListener { onSelect(voice) }
        holder.row.setOnLongClickListener {
            onLongPress(voice)
            true
        }

        // Reset first so a recycled row that showed the green loading state never
        // leaks that tint into a download or idle Preview button; the loading
        // branch re-applies green when it applies.
        setActionForeground(holder.action, primaryColor(holder.action))
        when {
            voice.downloadInProgress -> {
                holder.action.visibility = View.VISIBLE
                holder.action.isEnabled = false
                holder.action.text = holder.itemView.context.getString(R.string.voice_browser_downloading)
                holder.action.setIconResource(R.drawable.ic_download)
                holder.action.contentDescription = holder.itemView.context.getString(
                    R.string.voice_browser_downloading_desc,
                    voice.displayName
                )
                holder.action.setOnClickListener(null)
            }
            voice.downloadable -> {
                holder.action.visibility = View.VISIBLE
                holder.action.isEnabled = true
                holder.action.text = holder.itemView.context.getString(R.string.voice_browser_download)
                holder.action.setIconResource(R.drawable.ic_download)
                holder.action.contentDescription = holder.itemView.context.getString(R.string.voice_browser_download_desc, voice.displayName)
                holder.action.setOnClickListener { onDownload(voice) }
            }
            voice.canPreview -> {
                holder.action.visibility = View.VISIBLE
                holder.action.isEnabled = true
                val previewing = previewingProviderId == voice.providerId && previewingVoiceId == voice.providerVoiceId
                val loading = loadingProviderId == voice.providerId && loadingVoiceId == voice.providerVoiceId
                when {
                    previewing -> {
                        setActionForeground(holder.action, primaryColor(holder.action))
                        holder.action.text = holder.itemView.context.getString(R.string.voice_browser_stop)
                        holder.action.setIconResource(R.drawable.ic_stop)
                        holder.action.contentDescription = holder.itemView.context.getString(R.string.voice_browser_stop_desc, voice.displayName)
                        holder.action.setOnClickListener { onStopPreview(voice) }
                    }
                    loading -> {
                        // Requested but not sounding yet: green plus a "Loading…"
                        // label so the tap clearly registered as something happening.
                        setActionForeground(holder.action, ContextCompat.getColor(holder.itemView.context, R.color.mic_listening_green))
                        holder.action.text = holder.itemView.context.getString(R.string.voice_browser_loading)
                        holder.action.setIconResource(R.drawable.ic_play)
                        holder.action.contentDescription = holder.itemView.context.getString(R.string.voice_browser_preview_desc, voice.displayName)
                        holder.action.setOnClickListener { onPreview(voice) }
                    }
                    else -> {
                        setActionForeground(holder.action, primaryColor(holder.action))
                        holder.action.text = holder.itemView.context.getString(R.string.voice_browser_preview)
                        holder.action.setIconResource(R.drawable.ic_play)
                        holder.action.contentDescription = holder.itemView.context.getString(R.string.voice_browser_preview_desc, voice.displayName)
                        holder.action.setOnClickListener { onPreview(voice) }
                    }
                }
            }
            else -> {
                holder.action.visibility = View.INVISIBLE
                holder.action.isEnabled = false
                holder.action.setOnClickListener(null)
            }
        }
    }

    private fun primaryColor(view: View): Int =
        MaterialColors.getColor(view, androidx.appcompat.R.attr.colorPrimary)

    /** Tints both the label and the leading icon of the preview/stop button together. */
    private fun setActionForeground(button: MaterialButton, color: Int) {
        button.setTextColor(color)
        button.iconTint = ColorStateList.valueOf(color)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val row: ConstraintLayout = view.findViewById(R.id.voice_row)
        val selected: ImageView = view.findViewById(R.id.voice_selected)
        val name: TextView = view.findViewById(R.id.voice_name)
        val metadata: TextView = view.findViewById(R.id.voice_metadata)
        val action: MaterialButton = view.findViewById(R.id.voice_action)
    }
}
