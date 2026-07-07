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

import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Filter
import androidx.fragment.app.FragmentActivity
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.memory.RpTagRecord

/**
 * The roleplay tag input (spec §3): typing ≥3 characters fuzzy-searches the
 * existing roleplay-realm tag pool in a dropdown; picking a suggestion or
 * confirming the text (IME done) attaches the tag, creating it in the pool if
 * it's new. Selected tags render as removable chips. REALM WALL: this touches
 * ONLY rp_tags — real-life memory tags never appear here. The controller only
 * edits its in-memory selection; the hosting editor persists links on save.
 */
class CardTagChips(
    private val activity: FragmentActivity,
    private val chipGroup: ChipGroup,
    private val input: AutoCompleteTextView
) {

    /** Selected tags, tagId -> record, in pick order. */
    private val selected = LinkedHashMap<String, RpTagRecord>()

    /** The whole pool, loaded once off-thread; refreshed after a create. */
    private val pool = ArrayList<RpTagRecord>()

    init {
        input.threshold = 3
        val adapter = FuzzyTagAdapter()
        input.setAdapter(adapter)
        input.setOnItemClickListener { _, _, position, _ ->
            adapter.resultAt(position)?.let { addTag(it) }
            input.setText("")
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                confirmText()
                true
            } else false
        }

        Thread {
            try {
                if (MemoryStore.isProvisioned(activity)) {
                    val all = MemoryStore.getInstance(activity).getAllRpTags()
                    activity.runOnUiThread {
                        pool.clear(); pool.addAll(all)
                    }
                }
            } catch (_: Exception) { /* tag suggestions just stay empty */ }
        }.start()
    }

    fun setInitial(tags: List<RpTagRecord>) {
        selected.clear()
        tags.forEach { selected[it.tagId] = it }
        renderChips()
    }

    fun selectedTagIds(): List<String> = selected.keys.toList()

    /** Creates/attaches whatever is typed — called on Save too, so a typed but
     *  unconfirmed tag isn't silently dropped. */
    fun confirmText() {
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        input.setText("")
        pool.firstOrNull { it.name.equals(text, ignoreCase = true) }?.let {
            addTag(it)
            return
        }
        Thread {
            try {
                val tag = MemoryStore.getInstance(activity).getOrCreateRpTag(text)
                activity.runOnUiThread {
                    if (pool.none { it.tagId == tag.tagId }) pool.add(tag)
                    addTag(tag)
                }
            } catch (_: Exception) { /* nothing attached */ }
        }.start()
    }

    private fun addTag(tag: RpTagRecord) {
        if (selected.containsKey(tag.tagId)) return
        selected[tag.tagId] = tag
        renderChips()
    }

    private fun renderChips() {
        chipGroup.removeAllViews()
        for ((id, tag) in selected) {
            val chip = Chip(activity).apply {
                text = tag.name
                isCloseIconVisible = true
                setOnCloseIconClickListener {
                    selected.remove(id)
                    renderChips()
                }
            }
            chipGroup.addView(chip)
        }
    }

    /** Case-insensitive contains-match over the pool — the "fuzzy search"
     *  the spec asks for, kept simple and predictable. */
    private inner class FuzzyTagAdapter :
        ArrayAdapter<String>(activity, android.R.layout.simple_dropdown_item_1line) {

        private var results: List<RpTagRecord> = emptyList()

        fun resultAt(position: Int): RpTagRecord? = results.getOrNull(position)

        override fun getFilter(): Filter = object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val q = constraint?.toString()?.trim().orEmpty()
                val matches = if (q.length < 3) emptyList() else {
                    pool.filter { it.name.contains(q, ignoreCase = true) && !selected.containsKey(it.tagId) }
                        .sortedBy { it.name.lowercase() }
                }
                return FilterResults().apply { values = matches; count = matches.size }
            }

            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, filterResults: FilterResults?) {
                results = (filterResults?.values as? List<RpTagRecord>) ?: emptyList()
                clear()
                addAll(results.map { it.name })
                if (results.isEmpty()) notifyDataSetInvalidated() else notifyDataSetChanged()
            }
        }
    }
}
