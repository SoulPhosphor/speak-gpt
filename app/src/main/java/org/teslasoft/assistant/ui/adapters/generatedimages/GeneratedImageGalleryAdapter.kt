/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 **************************************************************************/

package org.teslasoft.assistant.ui.adapters.generatedimages

import android.content.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.generatedimages.GeneratedImageCatalogRecord
import java.io.File

data class GeneratedImageGalleryRow(
    val record: GeneratedImageCatalogRecord,
    val file: File,
    val originChatExists: Boolean
)

class GeneratedImageGalleryAdapter(
    private val dateFormatter: (Long) -> String,
    private val listener: Listener
) : RecyclerView.Adapter<GeneratedImageGalleryAdapter.Holder>() {

    interface Listener {
        fun onClick(row: GeneratedImageGalleryRow)
        fun onLongClick(anchor: View, row: GeneratedImageGalleryRow): Boolean
    }

    private var rows: List<GeneratedImageGalleryRow> = emptyList()
    private var selectedIds: Set<String> = emptySet()
    var selectionMode = false
        set(value) { field = value; notifyDataSetChanged() }
    var showLabels = true
        set(value) { field = value; notifyDataSetChanged() }

    init { setHasStableIds(true) }

    fun submit(newRows: List<GeneratedImageGalleryRow>, selected: Set<String> = selectedIds) {
        rows = newRows
        selectedIds = selected
        notifyDataSetChanged()
    }

    fun updateSelection(selected: Set<String>) {
        selectedIds = selected
        notifyDataSetChanged()
    }

    override fun getItemId(position: Int): Long {
        var hash = -0x340d631b7bdddcdbL
        for (char in rows[position].record.imageId) hash = (hash xor char.code.toLong()) * 0x100000001b3L
        return hash
    }

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.view_generated_image_gallery_tile, parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(rows[position])

    override fun onViewRecycled(holder: Holder) {
        holder.reset()
        super.onViewRecycled(holder)
    }

    inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val labelBlock: View = itemView.findViewById(R.id.label_block)
        private val chatName: TextView = itemView.findViewById(R.id.text_chat_name)
        private val date: TextView = itemView.findViewById(R.id.text_created_date)
        private val image: ImageView = itemView.findViewById(R.id.img_thumbnail)
        private val badge: FrameLayout = itemView.findViewById(R.id.badge_container)
        private val badgeBackground: View = itemView.findViewById(R.id.badge_background)
        private val badgeIcon: ImageView = itemView.findViewById(R.id.badge_icon)

        fun reset() {
            Glide.with(itemView).clear(image)
            image.setImageDrawable(null)
            labelBlock.visibility = View.GONE
            chatName.text = ""
            date.text = ""
            badge.visibility = View.GONE
            badgeIcon.setImageDrawable(null)
            itemView.alpha = 1f
            itemView.isEnabled = true
            itemView.isSelected = false
            itemView.contentDescription = null
            itemView.setOnClickListener(null)
            itemView.setOnLongClickListener(null)
        }

        fun bind(row: GeneratedImageGalleryRow) {
            reset()
            val record = row.record
            labelBlock.visibility = if (showLabels) View.VISIBLE else View.GONE
            if (showLabels) {
                chatName.text = record.originChatName.orEmpty()
                date.text = dateFormatter(record.createdAt)
            }
            Glide.with(itemView).load(row.file).override(512, 512).centerCrop().into(image)

            val selected = record.imageId in selectedIds
            if (selected || record.locked) {
                badge.visibility = View.VISIBLE
                badgeBackground.setBackgroundResource(
                    if (selected) R.drawable.bg_gallery_selected_badge else R.drawable.bg_gallery_locked_badge
                )
                badgeIcon.setImageResource(if (selected) R.drawable.ic_done else R.drawable.ic_lock)
                ImageViewCompat.setImageTintList(
                    badgeIcon,
                    ColorStateList.valueOf(android.graphics.Color.WHITE)
                )
            }

            val selectable = selectionMode && !record.locked
            itemView.alpha = if (selectionMode && !selectable) 0.6f else 1f
            itemView.isEnabled = !selectionMode || selectable
            itemView.isSelected = selected
            val context = itemView.context
            val description = mutableListOf(record.originChatName.orEmpty(), dateFormatter(record.createdAt))
            if (record.locked) description.add(context.getString(R.string.image_gallery_locked))
            if (selectionMode) description.add(context.getString(
                if (selected) R.string.profile_image_state_selected else R.string.profile_image_state_not_selected
            ))
            itemView.contentDescription = description.filter { it.isNotBlank() }.joinToString(", ")
            itemView.setOnClickListener { if (!selectionMode || selectable) listener.onClick(row) }
            itemView.setOnLongClickListener {
                if (selectionMode) false else listener.onLongClick(itemView, row)
            }
        }
    }
}
