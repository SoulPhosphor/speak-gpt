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
import org.teslasoft.assistant.preferences.ApiEndpointPreferences
import org.teslasoft.assistant.preferences.memory.MemoryStore
import org.teslasoft.assistant.preferences.models.ModelCleanupReportStore
import org.teslasoft.assistant.preferences.models.ModelIdentity
import org.teslasoft.assistant.preferences.models.ModelIdentityCodec
import org.teslasoft.assistant.ui.adapters.memory.MemoryRow

/**
 * The "tap a tag → everything" cross view for one model-rule tag (§11
 * Revision 6): every rule carrying the tag, tapping through to the editor.
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
        store.migrateUnambiguousLegacyModelTargets()
        val unavailable = ModelCleanupReportStore.get(this).load().unavailable
        return store.getModelRulesForTag(tagId).map { r ->
            val firstLine = r.text.substringBefore('\n').trim()
            val targets = ModelIdentityCodec.decode(r.modelTargetsJson)
            val modelLabels = targets.map(::targetLabel) + parseModels(r.modelStringsJson).map {
                getString(R.string.model_rule_legacy_target_label, it)
            }
            val subtitle = if (modelLabels.isEmpty()) getString(R.string.model_rules_no_models)
            else modelLabels.joinToString(", ")
            val hasUnavailable = targets.any { it in unavailable }
            MemoryRow(
                id = r.ruleId,
                title = firstLine,
                subtitle = subtitle,
                iconRes = if (hasUnavailable) R.drawable.ic_report else null,
                iconTintError = hasUnavailable
            )
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

    private fun targetLabel(target: ModelIdentity): String = getString(
        R.string.model_rule_target_label,
        endpointLabel(target.endpointId),
        target.modelId
    )

    private fun endpointLabel(endpointId: String): String = try {
        val endpoint = ApiEndpointPreferences.getApiEndpointPreferences(this)
            .getApiEndpoint(this, endpointId)
        when {
            endpoint.provider.isNotBlank() &&
                !endpoint.provider.equals(endpoint.label, ignoreCase = true) ->
                "${endpoint.provider} — ${endpoint.label}"
            endpoint.label.isNotBlank() -> endpoint.label
            endpoint.provider.isNotBlank() -> endpoint.provider
            else -> getString(R.string.model_rule_missing_endpoint)
        }
    } catch (_: Exception) {
        getString(R.string.model_rule_missing_endpoint)
    }
}
