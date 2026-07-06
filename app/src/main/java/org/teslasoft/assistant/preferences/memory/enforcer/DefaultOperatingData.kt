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

package org.teslasoft.assistant.preferences.memory.enforcer

/**
 * The enforcer's operating defaults. Only the retrieval policy remains — it is
 * neutral machinery (top-k, scoring weights, budgets), not content, and only
 * ever fills an EMPTY policy row.
 *
 * The five pre-written "modes" that used to live here were DELETED (owner
 * decision, `owner_approved_rules.md` §15): no AI pre-authors memory content,
 * and modes are content. The store now ships with NO modes; the modes table
 * stays dormant and empty until the user (or a future Phase 6 proposal they
 * approve) fills it. Existing origin='system' mode rows are purged once at
 * store open (see `MemoryStore.onOpen`).
 */
object DefaultOperatingData {

    /** Matches the schema's retrieval_policy object; the enforcer-specific
     *  knobs (memory_char_budget, mode_threshold) are optional extensions the
     *  parser falls back on when absent, so imported policies keep working. */
    const val DEFAULT_POLICY_JSON: String = """{
  "always_include": ["owner_profile", "directives", "active_companion_record", "always_load_memories"],
  "top_k": 8,
  "weights": {"similarity": 0.6, "importance": 0.3, "recency": 0.1},
  "memory_char_budget": 6000,
  "mode_threshold": 0.45
}"""
}
