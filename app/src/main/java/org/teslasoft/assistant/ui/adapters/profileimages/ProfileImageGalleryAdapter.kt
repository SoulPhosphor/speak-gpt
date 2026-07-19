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

package org.teslasoft.assistant.ui.adapters.profileimages

import android.content.Context
import android.content.res.ColorStateList
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
import org.teslasoft.assistant.ui.views.SquareFrameLayout
import java.io.File

/**
 * One Profile Images gallery tile (profile-images-plan.md, PROFILE IMAGES
 * GALLERY). [file] is null for a Missing tile (a catalog record whose
 * permanent file is gone - RECONCILIATION). [corrupted] is true for a
 * catalog record whose file EXISTS but will not decode as an image - a
 * distinct, owner-approved state from Missing (July 19 2026): the file is
 * there, its content is not. Always false when [file] is null. [isUsed]
 * drives both the Unused label/filter and, in Selection Mode, whether the
 * tile is selectable at all; Missing and Corrupted do not change
 * selectability on their own - a still-referenced record locks exactly
 * like a normal in-use image regardless of file health. [isCurrentDefault]
 * is only ever true when the gallery was opened from Default Images
 * (ProfileImagesActivity.EXTRA_DEFAULT_TARGET) and this tile is the hash
 * currently assigned to that target (owner ruling, July 19 2026).
 */
data class GalleryTile(
    val hash: String,
    val file: File?,
    val createdAt: Long,
    val isUsed: Boolean,
    val corrupted: Boolean = false,
    val isCurrentDefault: Boolean = false
)

/**
 * RecyclerView.Adapter for the gallery grid. Selection state and mode are
 * owned by the hosting activity and pushed in via [submit]/[updateSelection]
 * so filtering, selection, and Select All Shown all share one source of
 * truth (EXACT GALLERY CONTROL ORDER: "The active filter is locked while
 * selection mode is active").
 */
class ProfileImageGalleryAdapter(
    private val context: Context,
    /** Formats an epoch-millis timestamp for tile content descriptions. */
    private val dateFormatter: (Long) -> String
) : RecyclerView.Adapter<ProfileImageGalleryAdapter.ViewHolder>() {

    fun interface Listener {
        /** Tapped a tile that is currently interactive (see [GalleryTile] docs
         *  for when a tile is locked instead). */
        fun onTileClick(tile: GalleryTile)
    }

    var listener: Listener? = null

    var selectionMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    /** Show Labels (owner ruling, July 19 2026): governs only the text
     *  label at the bottom of a tile (Unused/Missing/Corrupted/Default) -
     *  the Default checkmark badge is independent and always shows. */
    var showLabels: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    private var items: List<GalleryTile> = emptyList()
    private var selected: Set<String> = emptySet()

    fun submit(newItems: List<GalleryTile>, selectedHashes: Set<String> = selected) {
        items = newItems
        selected = selectedHashes
        notifyDataSetChanged()
    }

    fun updateSelection(selectedHashes: Set<String>) {
        selected = selectedHashes
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.view_profile_image_tile, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val root: SquareFrameLayout = itemView as SquareFrameLayout
        private val img: ImageView = itemView.findViewById(R.id.img_thumbnail)
        private val missingIcon: ImageView = itemView.findViewById(R.id.img_missing_icon)
        private val statusLabel: TextView = itemView.findViewById(R.id.text_status_label)
        private val badgeContainer: FrameLayout = itemView.findViewById(R.id.badge_container)
        private val badgeBackground: View = itemView.findViewById(R.id.badge_background)
        private val badgeIcon: ImageView = itemView.findViewById(R.id.badge_icon)
        private val badgeDefaultContainer: FrameLayout = itemView.findViewById(R.id.badge_default_container)

        fun bind(tile: GalleryTile) {
            // Reset first: cancels any in-flight Glide request still
            // targeting this recycled view, matching ProfileImageBinder's
            // rule so a reused tile can never briefly show a stale image.
            Glide.with(context).clear(img)
            img.setImageDrawable(null)

            val missing = tile.file == null
            val corrupted = !missing && tile.corrupted
            if (missing || corrupted) {
                img.visibility = View.INVISIBLE
                missingIcon.visibility = View.VISIBLE
            } else {
                img.visibility = View.VISIBLE
                missingIcon.visibility = View.GONE
                Glide.with(context).load(tile.file).centerCrop().into(img)
            }

            statusLabel.visibility = View.GONE
            if (showLabels) {
                when {
                    missing -> {
                        statusLabel.visibility = View.VISIBLE
                        statusLabel.setText(R.string.profile_image_status_missing)
                    }
                    corrupted -> {
                        statusLabel.visibility = View.VISIBLE
                        statusLabel.setText(R.string.profile_image_status_corrupted)
                    }
                    tile.isCurrentDefault -> {
                        statusLabel.visibility = View.VISIBLE
                        statusLabel.setText(R.string.profile_image_status_default)
                    }
                    !tile.isUsed -> {
                        statusLabel.visibility = View.VISIBLE
                        statusLabel.setText(R.string.filter_unused)
                    }
                }
            }
            badgeDefaultContainer.visibility = if (tile.isCurrentDefault) View.VISIBLE else View.GONE

            val isSelected = selected.contains(tile.hash)
            // A Missing record can still be referenced (RECONCILIATION); its
            // usage did not change just because the file did, so it locks
            // exactly like a normal in-use image in Selection Mode.
            val locked = selectionMode && tile.isUsed
            val selectable = selectionMode && !tile.isUsed

            // Both badge backgrounds are solid, non-day/night-varying fills
            // (colorPrimary and a fixed dark scrim - see the two drawables),
            // so a fixed white icon reads correctly on either without
            // resolving a Material-only color-role attribute at runtime
            // (com.google.android.material.R.attr.colorOnPrimary has a
            // known CI resolution risk in this project - see the identical
            // colorPrimary workaround in MemoryScreenActivity).
            badgeContainer.visibility = View.GONE
            if (isSelected) {
                badgeContainer.visibility = View.VISIBLE
                badgeBackground.setBackgroundResource(R.drawable.bg_gallery_selected_badge)
                badgeIcon.setImageResource(R.drawable.ic_done)
                ImageViewCompat.setImageTintList(badgeIcon, ColorStateList.valueOf(android.graphics.Color.WHITE))
            } else if (locked) {
                badgeContainer.visibility = View.VISIBLE
                badgeBackground.setBackgroundResource(R.drawable.bg_gallery_locked_badge)
                badgeIcon.setImageResource(R.drawable.ic_lock)
                ImageViewCompat.setImageTintList(badgeIcon, ColorStateList.valueOf(android.graphics.Color.WHITE))
            }

            root.alpha = if (selectionMode && !selectable) 0.6f else 1f
            root.isEnabled = !selectionMode || selectable

            root.contentDescription = buildContentDescription(tile, missing, corrupted, isSelected, selectionMode)

            root.setOnClickListener {
                if (selectionMode && !selectable) return@setOnClickListener
                listener?.onTileClick(tile)
            }
        }

        private fun buildContentDescription(
            tile: GalleryTile,
            missing: Boolean,
            corrupted: Boolean,
            isSelected: Boolean,
            inSelectionMode: Boolean
        ): String {
            val parts = ArrayList<String>()
            parts.add(context.getString(R.string.profile_image_content_description, dateFormatter(tile.createdAt)))
            parts.add(
                when {
                    missing -> context.getString(R.string.profile_image_status_missing)
                    corrupted -> context.getString(R.string.profile_image_status_corrupted)
                    tile.isUsed -> context.getString(R.string.filter_in_use)
                    else -> context.getString(R.string.filter_unused)
                }
            )
            // The Default checkmark is always visible regardless of Show
            // Labels (see bind()), so it always needs an announcement too -
            // independent of the missing/corrupted/used/unused line above.
            if (tile.isCurrentDefault) {
                parts.add(context.getString(R.string.profile_image_status_default))
            }
            if (inSelectionMode) {
                parts.add(
                    context.getString(
                        if (isSelected) R.string.profile_image_state_selected
                        else R.string.profile_image_state_not_selected
                    )
                )
            }
            return parts.joinToString(", ")
        }
    }
}
