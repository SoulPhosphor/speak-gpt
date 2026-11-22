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

package org.teslasoft.assistant.ui.adapters.memory

import android.content.Context
import android.view.View
import android.widget.TextView
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.memory.PendingMatchPresentation

/**
 * Text and binding for Stage 6C's match details: the related-memory preview on
 * a Pending card and the status/method line on Possible Match Review cards.
 * Owner-approved labels only; a score appears only for a similarity match.
 */
object PendingMatchText {

    fun statusLabel(context: Context, status: String): String = context.getString(
        when (status) {
            "draft" -> R.string.mem_filter_pending
            "archived" -> R.string.mem_status_archived
            "superseded" -> R.string.mem_status_superseded
            else -> R.string.mem_status_active
        }
    )

    fun methodLabel(context: Context, method: PendingMatchPresentation.Method): String = context.getString(
        when (method) {
            PendingMatchPresentation.Method.EXACT -> R.string.mem_match_method_exact
            PendingMatchPresentation.Method.ALTERNATIVE -> R.string.mem_match_method_alternative
            PendingMatchPresentation.Method.SIMILARITY -> R.string.mem_match_method_similarity
            PendingMatchPresentation.Method.SCANNER -> R.string.mem_match_method_scanner
        }
    )

    /** "Active · Similarity Match · 0.91 Similarity", or without the score. */
    fun detail(context: Context, match: PendingMatchPresentation.Presented): String {
        val status = statusLabel(context, match.status)
        val method = methodLabel(context, match.method)
        val score = match.score
        return if (match.method == PendingMatchPresentation.Method.SIMILARITY && score != null) {
            context.getString(
                R.string.mem_match_detail_score, status, method,
                context.getString(R.string.mem_match_score, score)
            )
        } else {
            context.getString(R.string.mem_match_detail, status, method)
        }
    }

    /** What a Pending card shows for its closest related memory. */
    data class CardPreview(
        val summary: PendingMatchPresentation.Summary,
        /** The related memory's current wording. */
        val content: String
    )

    /**
     * Binds the card's related-memory block (`view_pending_related_match.xml`).
     * [preview] null hides it. Content and visibility only; the look comes
     * from the `Widget.App.MemoryMatch` styles.
     */
    fun bindCard(
        card: View,
        preview: CardPreview?,
        onRelated: (memoryId: String) -> Unit,
        onAdditional: (relatedId: String) -> Unit
    ) {
        val block = card.findViewById<View>(R.id.pending_related) ?: return
        val additional = card.findViewById<TextView>(R.id.pending_related_additional)
        if (preview == null) {
            block.visibility = View.GONE
            block.setOnClickListener(null)
            additional.setOnClickListener(null)
            return
        }
        val closest = preview.summary.closest
        block.visibility = View.VISIBLE
        card.findViewById<TextView>(R.id.pending_related_preview).text = preview.content
        card.findViewById<TextView>(R.id.pending_related_detail).text = detail(card.context, closest)
        block.setOnClickListener { onRelated(closest.memoryId) }
        val count = preview.summary.additionalCount
        if (count > 0) {
            additional.visibility = View.VISIBLE
            additional.text = card.context.getString(R.string.mem_match_additional_count, count)
            additional.setOnClickListener { onAdditional(closest.memoryId) }
        } else {
            additional.visibility = View.GONE
            additional.setOnClickListener(null)
        }
    }
}
