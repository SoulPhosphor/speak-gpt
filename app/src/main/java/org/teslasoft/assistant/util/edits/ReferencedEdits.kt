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

package org.teslasoft.assistant.util.edits

import java.util.Locale

/** One edit a model returned, before validation: the request-local
 *  references it names and its complete new text. */
interface ReferencedEdit {
    val refs: List<String>
    val content: String
}

/**
 * Checks the edits a model returns against the items a request showed it by
 * request-local references (such as "D4" or "S2"). Kotlin owns the items:
 * nothing changes until every operation has been checked, and an operation
 * may only name items that were shown and still exist.
 *
 * The caller owns everything feature-specific: its reference names, the
 * extra fields its edits carry, whether items may be combined, and how each
 * [Problem] is described.
 */
object ReferencedEdits {

    /** A checked operation, in stable item IDs. */
    sealed class Accepted<out E> {
        abstract val ids: List<Int>

        data class Edit<E>(override val ids: List<Int>, val edit: E) : Accepted<E>()

        data class Withdraw(val id: Int) : Accepted<Nothing>() {
            override val ids: List<Int> get() = listOf(id)
        }
    }

    /** Why an operation could not be accepted. References are normalized. */
    sealed class Problem {
        /** An edit named no item. */
        data object NoReference : Problem()

        /** A reference that was not shown, no longer exists, or is not allowed. */
        data class Unknown(val ref: String) : Problem()

        /** An edit with blank content, and the references it named. */
        data class NoContent(val refs: List<String>) : Problem()

        /** The caller's rule refused to combine the named items. */
        data class CannotCombine(val text: String) : Problem()

        /** The item was named by more than one operation. */
        data class Conflict(val id: Int) : Problem()
    }

    data class Failed<E>(
        val edit: E?,
        /** The shown items the operation named (invented references are
         *  never resolved to an item). */
        val knownIds: List<Int>
    )

    data class Validation<E>(
        val valid: List<Accepted<E>>,
        val failed: List<Failed<E>>,
        /** Distinct problems, in the order they were found. */
        val problems: List<Problem>
    )

    fun normalize(ref: String): String = ref.trim().uppercase(Locale.ROOT)

    /**
     * @param exposed the request's references to stable IDs.
     * @param present the IDs that currently exist.
     * @param combineProblem the caller's rule: a problem line when the named
     *  items (with their normalized references) may not be combined, else null.
     * @param allowed when given, only these items may be named.
     */
    fun <E : ReferencedEdit> validate(
        edits: List<E>,
        withdrawals: List<String>,
        exposed: Map<String, Int>,
        present: Set<Int>,
        combineProblem: (ids: List<Int>, refs: List<String>) -> String? = { _, _ -> null },
        allowed: Set<Int>? = null
    ): Validation<E> {
        val problems = LinkedHashSet<Problem>()
        val failed = ArrayList<Failed<E>>()
        val candidates = ArrayList<Pair<Accepted<E>, E?>>()

        fun resolve(ref: String): Int? {
            val id = exposed[normalize(ref)] ?: return null
            if (id !in present) return null
            if (allowed != null && id !in allowed) return null
            return id
        }

        for (edit in edits.distinct()) {
            val ids = edit.refs.mapNotNull(::resolve).distinct()
            val unknown = edit.refs.filter { resolve(it) == null }
            val refs = edit.refs.map(::normalize)
            var combine: String? = null
            when {
                edit.refs.isEmpty() -> problems.add(Problem.NoReference)
                unknown.isNotEmpty() -> unknown.forEach { problems.add(Problem.Unknown(normalize(it))) }
                edit.content.isBlank() -> problems.add(Problem.NoContent(refs))
                ids.size > 1 && combineProblem(ids, refs).also { combine = it } != null ->
                    problems.add(Problem.CannotCombine(combine!!))
                else -> {
                    candidates.add(Accepted.Edit(ids, edit) to edit)
                    continue
                }
            }
            failed.add(Failed(edit, ids))
        }
        for (ref in withdrawals.map(::normalize).distinct()) {
            val id = resolve(ref)
            if (id == null) {
                problems.add(Problem.Unknown(ref))
                failed.add(Failed(null, emptyList()))
            } else {
                candidates.add(Accepted.Withdraw(id) to null)
            }
        }

        // An item named by more than one operation is a conflict: none of
        // those operations may change it.
        val uses = HashMap<Int, Int>()
        candidates.forEach { (op, _) -> op.ids.forEach { uses[it] = (uses[it] ?: 0) + 1 } }
        val conflicted = uses.filterValues { it > 1 }.keys
        val valid = ArrayList<Accepted<E>>()
        for ((op, raw) in candidates) {
            val clash = op.ids.filter { it in conflicted }
            if (clash.isEmpty()) {
                valid.add(op)
            } else {
                clash.forEach { problems.add(Problem.Conflict(it)) }
                failed.add(Failed(raw, op.ids))
            }
        }
        return Validation(valid, failed, problems.toList())
    }

    /** The JSON object in a model's reply, from its first "{" to its last "}". */
    fun jsonObjectIn(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        require(start in 0 until end) { "no JSON object in response" }
        return raw.substring(start, end + 1)
    }
}
