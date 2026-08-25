package org.teslasoft.assistant.ui.adapters

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
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
    private val onPreview: (BrowserVoice) -> Unit,
    private val onDownload: (BrowserVoice) -> Unit
) : RecyclerView.Adapter<VoiceListAdapter.ViewHolder>() {
    private var voices: List<BrowserVoice> = emptyList()
    private var selectedProviderId: String? = null
    private var selectedVoiceId: String? = null
    private var filters = VoiceFilterState()

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
            voice.gender?.label?.let(::add)
            voice.quality?.label?.let { add("$it Quality") }
            if (filters.location == VoiceLocation.ALL) {
                when (voice.requiresNetwork) {
                    true -> add("Network")
                    false -> add("On-device")
                    null -> Unit
                }
            }
            if (voice.downloadable) add(holder.itemView.context.getString(R.string.voice_browser_download_required))
        }.joinToString(" · ")
        holder.metadata.text = metadata
        holder.metadata.visibility = if (metadata.isBlank()) View.GONE else View.VISIBLE

        holder.row.contentDescription = if (selected) {
            holder.itemView.context.getString(R.string.voice_browser_selected_voice, voice.displayName)
        } else {
            holder.itemView.context.getString(R.string.voice_browser_voice_row, voice.displayName, metadata)
        }
        holder.row.setOnClickListener { onSelect(voice) }

        when {
            voice.downloadable -> {
                holder.action.visibility = View.VISIBLE
                holder.action.text = holder.itemView.context.getString(R.string.voice_browser_download)
                holder.action.setIconResource(R.drawable.ic_download)
                holder.action.contentDescription = holder.itemView.context.getString(R.string.voice_browser_download_desc, voice.displayName)
                holder.action.setOnClickListener { onDownload(voice) }
            }
            voice.canPreview -> {
                holder.action.visibility = View.VISIBLE
                holder.action.text = holder.itemView.context.getString(R.string.voice_browser_preview)
                holder.action.setIconResource(R.drawable.ic_play)
                holder.action.contentDescription = holder.itemView.context.getString(R.string.voice_browser_preview_desc, voice.displayName)
                holder.action.setOnClickListener { onPreview(voice) }
            }
            else -> {
                holder.action.visibility = View.INVISIBLE
                holder.action.setOnClickListener(null)
            }
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val row: ConstraintLayout = view.findViewById(R.id.voice_row)
        val selected: ImageView = view.findViewById(R.id.voice_selected)
        val name: TextView = view.findViewById(R.id.voice_name)
        val metadata: TextView = view.findViewById(R.id.voice_metadata)
        val action: MaterialButton = view.findViewById(R.id.voice_action)
    }
}
