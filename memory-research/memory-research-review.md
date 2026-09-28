# Memory Research Review and Recommendations

**Date:** 2026-09-28
**Status:** Research notes and proposals only. Nothing in this file is approved for implementation.

## 0. Authority and how to use this file

- This file reviews the root-level `memory research.md` (an external research report) against the **current code on this branch**, and records proposals for the owner to decide on.
- It is **not** an implementation contract. It does not override `project-plan.md`, `Memory System/ACTIVE_ASSOCIATIVE_MEMORY_REPAIR.md`, `Memory System/owner_approved_rules.md`, or the approved copy specifications.
- Every item marked **Decision needed** is an approval gate under `CLAUDE.md` §3. Do not write code, strings, placeholders, tests, or constants that encode an undecided item.
- No user-facing wording is proposed here. Any screen described below is described by *content and behavior*, not by labels. Labels require a separate wording approval after behavior is approved.
- The external report calls the app by the upstream fork's name. Per `CLAUDE.md` §9 that name is not used for this app; this file says "the app".

### Reliability of the external report

- The report states it **did not inspect this app's code** (report line 21). Its integration estimates are generic.
- Its citation markers (`fileciteturn…`, `citeturn…`) are internal references from the tool that produced it. They cannot be followed or verified from the repository. Claims about third-party projects (file paths, formulas, licenses) have **not been independently verified** in this review. Verify against the upstream repository and commit before borrowing any code or formula.
- Its roadmap estimates ("person-days") are not calendar promises and assume a developer familiar with the code.

---

## 1. What the current app actually does (verified in code)

File references are to `app/src/main/java/org/teslasoft/assistant/preferences/memory/` unless stated otherwise.

### 1.1 Live retrieval pipeline (per chat turn)

1. `enforcer/Enforcer.kt:228` builds the retrieval query as **the user's message plus a recent-turns context string**, joined.
2. `librarian/Librarian.kt` `search` → `searchCore`:
   - **Semantic path** (only when every eligible memory has a current vector): cosine similarity against an on-device EmbeddingGemma vector. Relevance floor `MIN_SIMILARITY = 0.30` (`Librarian.kt:57`).
   - **Lexical fallback** (no model, or any eligible memory missing a vector): whole-word token overlap. A memory is eligible if it shares **at least one** query token of length ≥ 3 (`Librarian.kt:63`, `rankLexical` at ~`:224`). There is **no stop-word list**, so tokens such as "the", "and", "you", "was", "that" count as matches.
3. Score (`Librarian.rank`, ~`:185`):
   `score = w_sim·similarity + w_imp·importance + w_rec·recency + contextBoost`
   Defaults (`RetrievalPolicy.kt`): `w_sim 0.6`, `w_imp 0.3`, `w_rec 0.1`.
   - `importance` is the signed rating mapped to −1..+1 (`ImportanceRanking.normalizedRankingImportance`: −2→−1, 0→0, +2→+1, +3→+1).
   - `recency` is **rank position among the candidates** by `updated_at`/`created_at` (`Librarian.freshness`, ~`:310`), normalized 0..1. It is **not** based on elapsed time.
   - `contextBoost`: scope ladder 0–0.12, selected project +0.08, tag hits up to +0.06.
4. `Librarian` returns the top `scanCap + 1` candidates (`scanCap = topK + 64`), **plus every +3 candidate beyond that** (`ImportanceRanking.includeMandatory`).
5. `enforcer/RetrievalBackfill.select` walks candidates best-first. Ordinary candidates stop once `topK` survive. **+3 candidates are appended even after `topK` is full**, as long as they pass the per-candidate filters: 10-turn cooldown (`Enforcer.COOLDOWN_TURNS = 10`), lore near-duplicate check, and the remaining character budget.
6. Limits actually in force:
   - `topK` = **8**, fixed. `RetrievalPolicy.kt` comments: "no UI edits it".
   - Character budget = **6,000 characters** shared with lorebook and roleplay-card entries (`PromptAssembler.DEFAULT_CHAR_BUDGET`).
7. Diagnostics: `enforcer/AssemblyLog` records per-turn injected/omitted items in **process memory only** (lost on app restart), shown in `ui/activities/LoreBookDebugActivity.kt`. Only produced by real chat turns.

### 1.2 Existing test/debug surfaces

- **Advanced Memory Settings → Debug search** (`ui/activities/AdvancedMemorySettingsActivity.kt:335`, `runDebugSearch`): type a query, see up to 20 memories with a raw score and text. It does **not** apply the live policy (scope/companion, cooldown, character budget, +3 overrides, lore overlap) and does not show why an item would or would not be sent.
- **Lorebook/assembly debug log** (above): shows what a real turn did; requires starting a real chat and sending messages.

### 1.3 Approved controls that are specified but not built

`Memory System/memory_retrieval_and_analysis_ui_copy.md` §1 specifies `Maximum Memories Per Response`, `Maximum Memory Context`, `Memory Priority`, `Memory Match Strictness`, `Use Model-Aware Limits`, `Current Retrieval Limits`, and `Context Window Override`. A search of `res/values/strings.xml` and the Kotlin sources finds **none of these implemented**. The owner-visible "maximum" is therefore the hidden constant 8.

### 1.4 Time awareness

- No event date, valid-from/valid-to, or "current situation" concept on memories used by live retrieval.
- No decay or reinforcement. Nothing distinguishes "what is going on in my life right now" from "long-standing facts about me" except the rank-based recency term (weight 0.1).
- Supersession exists (`memory_supersessions` with `at` timestamp) and is user-driven through Possible Match Review.

---

## 2. Defect: the +3 rating floods the prompt

### 2.1 Owner's stated intent (confirmed 2026-09-28 — see Decision 1)

+3 was meant as a **tie-break at the cutoff**: when several relevant memories compete for the limited slots, a +3 memory should be included even if that pushes the count past the maximum. It was not meant to make every +3 memory appear in most turns.

The approved subtext (`memory_controls_and_pending_ui_copy.md` line 80) says "+3 is always included when relevant, even when that exceeds the normal memory-count limit." The code implements that sentence literally. The flood happens because "relevant" is defined very loosely.

### 2.2 Why nearly every +3 memory is sent

Several verified mechanisms combine:

| # | Mechanism | Location | Effect |
|---|---|---|---|
| a | +3 bypasses the count limit with **no cap on extras** | `ImportanceRanking.includeMandatory`, `RetrievalBackfill.select` | The only remaining limit is the 6,000-character budget. |
| b | Semantic relevance floor is cosine 0.30 | `Librarian.kt:57` | For sentence-embedding models, unrelated same-language sentences commonly score in the 0.2–0.5 range. Many +3 memories clear 0.30 against almost any conversation. *Not measured on this model; the Memory Lab (§3) should measure it.* |
| c | Lexical fallback accepts a single shared ≥3-letter token, with no stop-word filtering | `Librarian.kt:63`, `rankLexical` | While the vector index is incomplete or the model is absent, any +3 memory sharing "the"/"and"/"you" with the query is "relevant". |
| d | +3 (and +2) receive the maximum importance bonus `0.3 × 1.0 = 0.30` against `0.6 × similarity` | `Librarian.rank`, `RetrievalPolicy` defaults | A +2/+3 memory with similarity 0.35 (score ≈ 0.51) outranks a neutral memory with similarity 0.80 (score ≈ 0.48). High ratings therefore crowd out more relevant memories even inside the normal 8. |
| e | Query includes recent-turn context, not just the latest message | `Enforcer.kt:228` | A wider query raises baseline similarity and token overlap for everything. |
| f | 10-turn cooldown | `Enforcer.COOLDOWN_TURNS` | Flooding memories rotate rather than stop; the user sees the same group return every ~10 turns. |

Result in plain terms: +3 currently behaves close to "always load, limited only by space", which also conflicts with `owner_approved_rules.md` §10 ("There is no per-memory 'always load' flag").

### 2.3 Repair options (no code written)

**Option A — +3 as a bounded cutoff tie-break (matches stated intent).**
- A memory is eligible only if it passes the normal relevance rule (the same rule that governs ordinary memories, including the future Match Strictness setting).
- Rank all eligible memories. Take the top N (the maximum).
- A +3 memory that is eligible but ranked below N may be added **only if it falls within a bounded window just below the cutoff** (for example, within the next K ranks, or within a score margin of the N-th item), and at most E extra +3 memories per turn.
- K/margin and E are owner decisions.

**Option B — +3 overrides count, but with a stricter relevance bar.**
- +3 still bypasses the count, but only when its similarity is above a higher floor than ordinary memories. Easier to reason about, but still unbounded in count if many +3 memories are strongly relevant.

**Supporting fixes that apply to either option (each needs approval because each changes retrieval behavior):**
1. Reduce the importance weight so it acts as a tie-break rather than a relevance override. The Archivist reconciliation path already does this (`RECONCILIATION_MAX_TIE_BREAK = 0.06`, `Librarian.kt:108`); live retrieval does not.
2. Add a stop-word filter (or require ≥2 meaningful token hits / a minimum overlap ratio) in the lexical fallback.
3. Calibrate `MIN_SIMILARITY` from measured on-device score distributions (Memory Lab) instead of the current guess; this also supplies real numbers for Strict/Balanced/Broad.
4. Build the already-approved `Maximum Memories Per Response` / `Memory Match Strictness` controls so the "maximum" the +3 rule refers to is visible and user-set.

**Owner ruling (2026-09-28):** Option A is the intended meaning of +3. Parameters remain open (see §6, Decision 1).

**Recommendation:** Option A plus supporting fixes 1 and 2, with fix 3 done using the Memory Lab once it exists. Option A is the only one that matches the owner's described meaning of +3.

**Wording impact:** The approved +3 subtext ("always included when relevant") would no longer describe Option A accurately. The subtext and the `+3 · Always include` label need a wording decision **after** the behavior is chosen. Do not draft replacements before then.

---

## 3. Proposal: Memory Lab (test memory without starting a chat)

The external report calls this the "Memory Observatory" (report §"The Memory Observatory", "Retrieval Trace mockup"). Name and labels are wording decisions; "Memory Lab" is a working name in this file only.

### 3.1 Purpose

Let the owner type a pretend message (optionally with pretend recent context and a chosen companion/project/roleplay context) and see **exactly what memory would be sent and why**, with no AI provider call, no cost, and no change to any stored memory or cooldown state.

### 3.2 Proposed content, in priority order

1. **Dry-run of the real pipeline.** Run the same `Enforcer`/`Librarian`/`RetrievalBackfill` path as a live turn, with a flag that:
   - performs no network request;
   - does **not** stamp or read-modify cooldown state (option to show "would be on cooldown in a real chat" vs "ignore cooldown");
   - writes nothing to the memory database.
   This must reuse the production code path, not a copy, so the Lab cannot drift from real behavior.
2. **Result summary:** retrieval mode (semantic vs keyword fallback, and why), eligible count, candidates ranked, number sent, characters used of budget, counts removed by each filter.
3. **Per-memory trace** for every candidate above a display cutoff:
   - full memory text, scope/target, Type, importance rating;
   - similarity (or keyword hits and which words matched — this directly exposes the "the/and/you" problem);
   - each score component (similarity part, importance part, recency part, context boost) and the total;
   - final decision: sent / sent because +3 override / not sent, with the specific reason (below relevance floor, below top-N, cooldown, lore duplicate, out of character budget, wrong scope/companion).
4. **Exact text that would be inserted** into the request, as the model would receive it.
5. **Library health counts** computed live from the device: totals by scope, Type, status (Active / Pending / Archived / Superseded), importance rating, and memories missing a current vector.
6. **Later (depends on §4 decisions):** simulated date ("pretend it is 90 days from now") for any time-based fading.

### 3.3 Reuse and placement

- Extends existing pieces: the Advanced Memory Settings debug search and the `AssemblyLog` record format. Placement (replace the debug search section, or a separate screen reached from Memory settings) is a UI decision.
- Must follow `ui-style-guide.md` / `ui-style-adoption.md` and produce a component map before implementation (`CLAUDE.md` §9). No AMOLED-specific work.

### 3.4 Privacy and logging

- Local only; nothing leaves the device.
- Showing results on screen is not logging. **Persisting** Lab runs, or adding any Logcat/Event/error log line, requires separate owner approval under `CLAUDE.md` §8.
- If an export is ever added, the report's three export levels (statistics only / sanitized / full) are a reasonable starting model; that is a separate decision.

### 3.5 Roadmap impact

`project-plan.md` allows one active feature (Feature 1, API Memory Assistant Repair). The Lab and the +3 repair are not on the roadmap. Adding them, and in what order relative to Feature 1, is an owner decision (Decision 4). Technically, the Lab also helps Feature 1: its §7 on-device proof requires "debug/test evidence that the local Librarian retrieved the green memory", which the Lab's trace format could supply.

---

## 4. Proposal: "what's going on in my life" versus long-standing memory

### 4.1 The gap

The owner wants the AI to remember both **current life situation** (this week's stress, an ongoing move, a current project) and **long-standing facts** (history, relationships, preferences). The current design treats both identically: relevance plus a rank-order recency term. Consequences:
- A situation that ended months ago is as retrievable as today's, unless the user manually supersedes or archives it.
- Current situation is only surfaced when the conversation happens to mention it, because nothing is "resident".

### 4.2 Useful ideas from the report, mapped to this app

| Report idea | Fit with current app and rules | Notes |
|---|---|---|
| Separate truth/confidence from **retrieval strength**; fade strength, never delete (report "Separate truth from accessibility") | Good fit. Additive; does not change what is stored or approved. | Strength computed lazily from a stored baseline + timestamp; no background jobs. |
| Half-life + floor per memory class (report table: fleeting 14 d … relationship 365 d; explicit/core ∞) | Good fit **if** classes map to user-owned Memory Types or a new optional per-memory setting. | Values in the report are its own proposals, not from any project. Owner decides classes and numbers. |
| **User re-mention restores strength to 1.0; retrieval or AI mention does not reinforce** | Good fit and matches owner's earlier stated instinct per the report. | Detecting "user independently repeated this" needs a mechanism: e.g. the Archivist's `related_existing_memory_ids` (Feature 1) for an "already known" item could be recorded as a reinforcement event. Must not be triggered by the memory merely being retrieved. |
| Strength must never veto a highly relevant old memory (report weights: semantic 0.42 … strength 0.10) | Required. | Keeps decay from becoming amnesia. |
| Temporal supersession (valid_from/valid_to) | Partly exists (user-driven supersession with date). | Automatic historical queries ("what did I used to…") are a later extension. |
| Persistent/"core" tier always in context (Letta-style) | **Conflicts** with `owner_approved_rules.md` §10 (no always-load flag). | The approved alternative is card cores / system-prompt / Model Rules. A short, user-written "current situation" card section could serve the "what's going on now" need within existing rules. Owner decision. |
| Companion-self memory with a quarantine gate for AI-generated content | Partially aligned: the app already requires human approval (Pending) for every memory, which is a stronger gate. | A distinct subject ("about the user" vs "about the companion/relationship") could be expressed via Types/targets. Deferred. |
| Delete means delete (vectors, indexes, derived data) | Should be audited against current delete path. | Audit item, not new design. |
| Full graph layer (Graphiti) | Already deferred by `project-plan.md`. | No change. |

### 4.3 Minimal technical shape (if approved)

Additive columns on the active memory table (names illustrative, not decided):

```text
strength_base        REAL    -- 0..1, value at last reinforcement
strength_updated_at  INTEGER -- epoch ms of last reinforcement (migration time for existing rows)
reinforcement_count  INTEGER
last_reinforced_at   INTEGER
last_retrieved_at    INTEGER -- diagnostics only; never used to reinforce
```

Per-class parameters (half-life `H`, floor `F`) keyed by Type or a small fixed class list.

```text
strength(t) = F + (strength_base − F) · 2^(−Δt / H)
reinforce on genuine user re-mention: strength_base = 1.0, strength_updated_at = now
```

Migration rule from the report that should be kept: existing memories start with `strength_base = 1.0` and `strength_updated_at = migration time`, so old memories are not instantly faded because of their `created_at`.

Scoring integration: add strength as a **small** additive term (or multiplier bounded below by the floor) after relevance. It must satisfy the existing relevance-precedence property already tested for reconciliation (Revision 26 test 16): a larger relevance advantage always wins over any tie-break contribution.

Database/migration, backup/restore, and SQLCipher implications follow the existing `MemoryStore.kt` migration pattern. The Archivist reconciliation path should **not** use strength as a gate (Revision 26 §3.3 item 9).

### 4.4 Research sources worth reading before building (per the report; verify first)

- LongMemory (formerly OpenMemory), Apache-2.0: `src/core/math/decay.ts`, `docs/formulas.md` — decay/reinforcement math.
- companion-emergence, MIT: `brain/forgetting/salience.py`, `brain/memory/store.py` — "surfacing does not count as recall" rule.
- Connectome: retrieval-trace UI concept only. **No license identified — do not copy code.**
- Mem0 V3 `mem0/memory/main.py` — already the reference for Feature 1's pre-retrieval.

Record any borrowed code in a third-party notices file with upstream commit and license, as the report suggests.

---

## 5. Owner design direction for this work (2026-09-28)

**Expose tuning to the user where reasonable.** The owner wants advanced users to be able to adjust memory behavior themselves rather than needing a code change each time behavior is slightly off. When a retrieval value is a tuning choice (counts, limits, thresholds), prefer proposing it as a user setting with a sensible default over hard-coding it.

Limits of this direction:
- It is a preference to apply when proposing, not approval of any specific setting. Each new setting, its range, default, placement, and wording still needs owner approval before implementation.
- It does not override `memory_retrieval_and_analysis_ui_copy.md` §4, which says not to add protected-capacity, percentage-balance, subtype-budget, dynamic-preset, or automatic-tuning controls without owner approval. Proposals in those categories must be put to the owner explicitly.
- Internal safety rails against corrupt or imported data (for example `RetrievalPolicy` bounds) are not tuning and remain internal.

## 6. Decisions needed from the owner

Asked one at a time in chat; recorded here for reference. Status for all: **No code has been changed.**

1. **Meaning of +3.** **Answered 2026-09-28: Option A approved** (bounded tie-break at the cutoff: a +3 memory must pass the normal relevance rule, and may be added beyond the maximum only when it narrowly missed the cutoff, with a small cap on extras). Still open: the maximum number of extra +3 memories per turn, and how close to the cutoff a +3 must be. Approval of Option A does not approve new +3 wording; the label and subtext need a separate wording decision.
   - **Answered 2026-09-28: both counts are user settings.** The normal maximum (the already-specified `Maximum Memories Per Response`) and the number of extra +3 memories allowed beyond it are each exposed to the user. **Defaults: normal maximum 8, extra +3 allowance 2.** Still open: the allowed range of each setting, the closeness-to-cutoff rule, and all wording for the new extra-allowance setting.
2. **Importance weight.** Reduce importance from a large score bonus to a small tie-break so a +2/+3 rating cannot beat a clearly more relevant memory?
3. **Keyword fallback.** Stop counting common words ("the", "and", "you") as matches?
4. **Roadmap order.** Where the Memory Lab and +3 repair go relative to Feature 1 (e.g. before it, as a narrow defect fix plus diagnostic tool; or after it).
5. **Memory Lab placement and scope** (replace the existing debug search, or a new screen), after which a component map and wording proposal follow.
6. **Build the already-approved Memory Retrieval controls** (Maximum Memories, Strictness, Priority) as part of the +3 repair, or separately? *Partly answered 2026-09-28: `Maximum Memories Per Response` must be user-visible as part of the +3 repair (see Decision 1). Strictness and Priority timing still open.*
7. **Time awareness.** Whether to pursue fading-and-restoring retrieval strength; if yes, which categories fade and how fast.
8. **"Current situation" delivery.** Rely on retrieval with fading, or use a user-written card section (fits the no-always-load rule), or both.
