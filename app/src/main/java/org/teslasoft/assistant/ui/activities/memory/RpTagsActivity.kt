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

package org.teslasoft.assistant.ui.activities.memory

import android.content.Intent
import android.view.View
import android.widget.PopupMenu
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.RpTagRecord
import org.teslasoft.assistant.ui.adapters.memory.MemoryRow

/**
 * The Tags index (3.6e, roleplay_cards_and_tags_spec §3): a searchable list
 * of every roleplay-realm tag — user-created only, nothing preloaded, and
 * NEVER real-life tags (those keep the Memories browser's tag filter as
 * their only door; the realm wall means the two searches never mix). Each
 * row shows whether auto-trigger is on or the tag is browse-only; the row
 * menu flips it (the app never auto-flips — spec §3). Tapping a tag opens
 * the cross-card view. A human findability tool, not a retrieval path.
 */
class RpTagsActivity : MemoryScreenActivity() {

    override fun contentLayoutRes(): Int = R.layout.activity_memory_list_simple
    override fun screenTitle(): String = getString(R.string.rp_tags_title)
    override fun showSearch(): Boolean = true

    override fun loadRows(query: String): List<MemoryRow> {
        if (!MemoryStore.isProvisioned(this)) return emptyList()
        val q = query.trim()
        return MemoryStore.getInstance(this).getAllRpTags()
            .filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
            .map { tag ->
                MemoryRow(
                    id = tag.tagId,
                    title = tag.name,
                    subtitle = null,
                    badge = getString(
                        if (tag.autoTrigger) R.string.rp_tag_badge_auto else R.string.rp_tag_badge_browse
                    ),
                    hasAction = true
                )
            }
    }

    override fun onClick(row: MemoryRow) {
        if (row.isHeader) return
        startActivity(
            Intent(this, RpTagViewActivity::class.java)
                .putExtra("chatId", chatId)
                .putExtra("tagId", row.id)
                .putExtra("tagName", row.title)
        )
    }

    override fun onAction(row: MemoryRow, anchor: View) {
        runOffThread {
            val tag = MemoryStore.getInstance(this).getRpTag(row.id) ?: return@runOffThread
            runOnUiThread { showRowMenu(anchor, tag) }
        }
    }

    private fun showRowMenu(anchor: View, tag: RpTagRecord) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(
            0, 1, 0,
            getString(if (tag.autoTrigger) R.string.rp_tag_make_browse else R.string.rp_tag_make_auto)
        )
        menu.setOnMenuItemClickListener { item ->
            if (item.itemId == 1) {
                runOffThread {
                    MemoryStore.getInstance(this).setRpTagAutoTrigger(tag.tagId, !tag.autoTrigger)
                    runOnUiThread { reload() }
                }
            }
            true
        }
        menu.show()
    }
}
