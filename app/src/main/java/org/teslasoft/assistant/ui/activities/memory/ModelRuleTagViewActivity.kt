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
import org.json.JSONArray
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.ui.adapters.memory.MemoryRow

/**
 * The "tap a tag → everything" cross view for one model-rule tag (§11
 * Revision 5): every rule carrying the tag, tapping through to the editor.
 * Read-only over a separate pool — it never reaches roleplay tags or memory
 * tags. Its title is the tag name (passed in). Since a model-rule tag only
 * links to rules, this is a flat list, not the grouped card/section view the
 * roleplay tag screen needs.
 */
class ModelRuleTagViewActivity : MemoryScreenActivity() {

    private var tagId: String = ""
    private var tagName: String = ""

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        tagId = intent.getStringExtra("tagId") ?: ""
        tagName = intent.getStringExtra("tagName") ?: ""
        super.onCreate(savedInstanceState)
    }

    override fun screenTitle(): String =
        if (tagName.isNotEmpty()) "#$tagName" else getString(R.string.model_rule_tags_title)
    override fun showSearch(): Boolean = false

    override fun loadRows(query: String): List<MemoryRow> {
        if (tagId.isEmpty() || !MemoryStore.isProvisioned(this)) return emptyList()
        val store = MemoryStore.getInstance(this)
        return store.getModelRulesForTag(tagId).map { r ->
            val firstLine = r.text.substringBefore('\n').trim()
            val modelStrings = parseModels(r.modelStringsJson)
            val subtitle = if (modelStrings.isEmpty()) getString(R.string.model_rules_no_models)
            else modelStrings.joinToString(", ")
            MemoryRow(id = r.ruleId, title = firstLine, subtitle = subtitle)
        }
    }

    override fun onClick(row: MemoryRow) {
        startActivity(
            Intent(this, ModelRuleEditorActivity::class.java)
                .putExtra("chatId", chatId)
                .putExtra("ruleId", row.id)
        )
    }

    private fun parseModels(json: String?): List<String> = try {
        if (json.isNullOrBlank()) emptyList() else {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        }
    } catch (_: Exception) { emptyList() }
}
