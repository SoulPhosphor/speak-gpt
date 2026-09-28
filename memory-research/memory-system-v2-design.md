# Memory System V2 — Research-Based Design Proposal

**Date:** 2026-09-28
**Status:** Design proposal. Architecture recommendations only. No code has been written. **Decision** items are approval gates under `CLAUDE.md` §3; **Approved** items record owner rulings made in conversation.
**Companion file:** `memory-research/memory-research-review.md` — verified audit of the current code and the +3 defect.
**Inputs:** root `memory research.md` (external report), plus first-hand reading of the source code of twelve open-source memory systems (§1).

---

## 0. Goals (owner's terms)

1. The AI knows **what is going on in the user's life right now** — current situations, recent events, upcoming plans — and treats them as current only while they are.
2. The AI reliably holds **long-standing knowledge** — history, relationships, preferences, identity.
3. The user can **see and test** exactly what memory the AI receives and why, without starting a chat (**Memory Previewer**, approved).
4. Advanced users can **tune** behavior themselves (owner direction 2026-09-28).
5. Keep the existing human-control boundary: AI-proposed memory content always passes through Pending.
6. Do not reinvent what other systems have already solved; adopt proven mechanisms and adapt them to an on-device Android app with a user-chosen API model.

---

## 1. Research: what each system does well, and what this design takes

All repositories were shallow-cloned and read at the commits below (2026-09-28). File paths are relative to each repository. Licenses were read from each repository's `LICENSE` file.

| System | Commit | License | Strongest proven idea | Taken into this design |
|---|---|---|---|---|
| **companion-emergence** (`hanamorix/companion-emergence`) | `56e9b88` | MIT | Companion-specific memory with forgetting; semantic recall with a **cross-encoder reranker and a calibrated floor**; "surfacing never reinforces" | Reranker-gated relevance (§4.3), floor calibration from labeled pairs (§4.4), stop-word + IDF + proper-noun query terms (§4.2), bounded importance full-inject (§4.6), no reinforcement on retrieval (§3.5), "not found" honesty section (§4.8) |
| **Hindsight** (`vectorize-io/hindsight`) | `1e42702` | MIT | Four retrieval arms (semantic, BM25, graph, temporal) fused with **Reciprocal Rank Fusion**, cross-encoder rerank, **bounded multiplicative** recency/temporal boosts; `occurred_start/occurred_end/mentioned_at`; coarse-date handling; date text added to embeddings; observations with `proof_count` + sources; user-defined **mental models** refreshed from memories | Multi-arm retrieval + RRF (§4.1), bounded boosts (§4.5), event vs mention time and date precision (§3.2), date-augmented embedding text (§3.4), consolidation (§7), Current Life note modeled on mental models (§5) |
| **Graphiti** (`getzep/graphiti`) | `6b4b56f` | Apache-2.0 | Per-message **reference time** used to resolve "last week" into absolute `valid_at/invalid_at`; deterministic invalidation using time comparison | Date anchoring prompt rule and per-segment conversation date (§3.3); supersession sets old memory's valid-until to new memory's valid-from (§3.6) |
| **LongMemory** (formerly OpenMemory, `CaviraOSS/LongMemory`) | `9ee2c8e` | Apache-2.0 | **Bitemporal** facts (valid time vs recorded time); decay as exponential with **reinforcement pulses**; **reconsolidation at recall time** (a memory's current meaning is computed when recalled, original never mutated) | Two time axes (§3.2); strength math (§3.5); render-time interpretation of dated memories (§3.4) |
| **Memobase** (`memodb-io/memobase`) | `358c16b` | Apache-2.0 | Always-present **current profile + dated event timeline** per user; extraction rules "never use relative dates" and "mention time vs event time — don't mix them up"; context note "do not actively mention these memories unless relevant" | Current Life note + dated events shape (§5); extraction rules (§3.3); proactive-mention policy (Decision B) |
| **Letta** (formerly MemGPT, `letta-ai/letta`, `archive` branch) | `56ba9c2` | Apache-2.0 | Always-in-context **core memory blocks** with size limits, maintained by a background **sleep-time memory agent** that rewrites the block and is told to write specific dates, never "today/recently" | Current Life note maintained by the existing Memory Assistant run, bounded size, whole-note rewrite proposals (§5) |
| **MemoryOS** (`BAI-LAB/MemoryOS`) | `587ed77` | Apache-2.0 | Short/mid/long-term tiers; topic **"heat"** = visits + interaction length + time-decayed recency, hot topics promoted to the long-term profile | "Heat" signal for suggesting items for the Current Life note (§5.3) |
| **Generative Agents** (`joonspk-research/generative_agents`) | `fe05a71` | Apache-2.0 | Classic recency + importance + relevance retrieval; reflection triggered when accumulated importance crosses a threshold (`importance_trigger_max = 150`) | Consolidation trigger idea (§7). **Not taken:** its recency is rank-position based (`recency_decay ** i`, `retrieve.py`), the same weakness the app has now |
| **Mem0** (`mem0ai/mem0`) | `94c3fe9` | Apache-2.0 | Retrieve existing memories before extraction (already adopted by Feature 1) | Nothing new beyond Feature 1 |
| **LangMem** (`langchain-ai/langmem`) | `9d033b4` | MIT | Semantic / episodic / procedural taxonomy | Confirms the app's existing split: memories (semantic/episodic) vs Model Rules and Instruction memories (procedural). No new mechanism needed |
| **A-MEM** (`agiresearch/A-mem`) | `ceffb86` | MIT | Zettelkasten-style linked notes; "memory evolution" where a new note automatically rewrites linked notes | **Not taken:** automatic rewriting of existing memories conflicts with the human-review boundary and costs an LLM call per note |
| **Connectome** (`anima-research/connectome-host`) | `142d25d` | **No license file; `package.json` has no license field** | Operator retrieval-trace viewer: candidates, match provenance, relevance decision, exact injected block; raw inputs opt-in | Behavior concept only for the Previewer (§6). **No code may be copied.** |

### 1.1 The research finding that changed the design

companion-emergence (`brain/memory/semantic_recall.py`, `brain/memory/reranker.py` module docstrings) first derived a relevance floor from the spread of cosine similarities in the user's own memory corpus. Their own review found it **does not generalize** ("breaks silently on tight/diffuse/bimodal corpora, because the query-match cosine scale is MODEL-FIXED, not corpus-shaped"), deleted it, and replaced it with a **cross-encoder reranker** — a small model that reads the query and the memory *together* and scores true relevance — with the floor applied to the reranker score. Hindsight independently uses the same pattern (cross-encoder primary, other signals as bounded boosts).

The previous draft of this file proposed exactly the approach companion-emergence abandoned. It is withdrawn. The app's current `MIN_SIMILARITY = 0.30` cosine floor has the same underlying weakness.

---

## 2. Diagnosis of the current app (verified in code)

Details and line references are in the review file.

| # | Finding |
|---|---|
| D1 | Memories carry no event or validity date; only record timestamps. |
| D2 | The Archivist is not told to anchor relative time ("next week") to absolute dates, and does not receive the conversation date as a rule input. |
| D3 | Memories reach the chat model with **no date** (`PromptAssembler.renderMemoryLine`). |
| D4 | "Recency" is rank position among candidates (`Librarian.freshness`), not elapsed time. |
| D5 | No fade, no reinforcement, no "still true?" lifecycle for ongoing situations. |
| D6 | Relevance-only retrieval cannot surface current life context on messages that do not name it (a greeting). |
| D7 | Relevance gate is a single cosine floor (0.30) or a one-word keyword hit with no stop words; importance weight (0.30) competes with relevance; +3 bypasses the count without bound. |
| D8 | Memory budget is shared with lore/cards, contrary to the approved copy spec. |
| D9 | Semantic and keyword retrieval are either/or for the whole turn. |
| D10 | Diagnostics require real chats and vanish on restart. |

---

## 3. Time model (addresses D1–D5)

### 3.1 Time kind

A fixed internal set, **independent of user-owned Memory Types**. Internal names; user-facing names are a wording decision.

| Time kind | Meaning | Research basis |
|---|---|---|
| `ONGOING` | True now, expected to change or end ("in the middle of moving") | Graphiti `valid_at` with open `invalid_at`; LongMemory `valid_to = null` |
| `PLANNED` | Future dated event ("interview on Oct 6") | Hindsight `occurred_start` in the future |
| `EVENT` | Happened at a time ("hard talk with her sister on Sep 20") | Hindsight `fact_kind = event`; Memobase event timeline |
| `ENDURING` | Long-standing fact, preference, relationship, history | Hindsight `fact_kind = conversation`; Memobase profile |
| `PERMANENT` | User wants it held at full strength | Letta core block content; not always-loaded (still relevance-gated) |

Defaults: starter Type `Status → ONGOING`, `Event → EVENT`, others and No Type → `ENDURING`. The Archivist proposes a time kind per memory; the user can change it in Pending and in the editor.

### 3.2 Two time axes plus precision

Following LongMemory's bitemporal model, Hindsight's `occurred_*`/`mentioned_at`, and Memobase's "mention time vs event time":

```text
-- additive columns on memories
time_kind           TEXT NOT NULL DEFAULT 'enduring'   -- ongoing|planned|event|enduring|permanent
occurred_start      TEXT NULL   -- world time: event date, or when an ongoing situation began
occurred_end        TEXT NULL   -- world time: event end, or when an ongoing situation ended / is expected to end
date_precision      TEXT NULL   -- day|week|month|season|year|approx
mentioned_at        TEXT NULL   -- conversation date the fact came from (record time already exists as created_at)
last_confirmed_at   TEXT NULL   -- last time the USER restated/confirmed it
strength_base       REAL NOT NULL DEFAULT 1.0
strength_updated_at TEXT NOT NULL   -- migration time for existing rows
reinforcement_count INTEGER NOT NULL DEFAULT 0
```

```text
memory_reinforcements (
  memory_id TEXT NOT NULL REFERENCES memories(memory_id) ON DELETE CASCADE,
  at        TEXT NOT NULL,
  kind      TEXT NOT NULL CHECK (kind IN ('user_restated','user_checkin_confirmed','user_edited','user_marked_permanent')),
  PRIMARY KEY (memory_id, at, kind)
)
```

No chat, transcript, or run identifiers are stored on memories (Revision 26 §4 item 7; `CLAUDE.md` §12). Backup/restore (`MemorySeedCodec`, `MemoryExporter`) carries all fields; older backups import with defaults.

### 3.3 Date anchoring at write time

Adopted from Graphiti (`graphiti_core/prompts/extract_edges.py`: per-episode timestamp, `REFERENCE_TIME` fallback), Memobase (`prompts/extract_profile.py`: "never use relative dates"; "mention time vs event time, don't mix them up"), and Letta (`prompts/system_prompts/sleeptime_v2.py`: "do not write 'today' or 'recently'").

- The Archivist protocol block supplies the **conversation date per scene segment** from `transcripts.started_at` (per-row, like Graphiti's per-episode timestamps; the frozen-range segmentation from Revision 26 already exists).
- Rules: resolve every relative time to an absolute date or range with precision; set `time_kind`, `occurred_start`, `occurred_end`, `date_precision`; never leave "currently", "next week", "lately" unanchored in `content`.
- Hindsight's `why` field principle: the memory states why it matters, not only the fact (consistent with `CLAUDE.md` §7). The existing prompt's "prose, the way a friend would hold it" rule already points this way; reinforce it in the prompt revision.
- The app validates all date fields (unparseable → null, proposal kept).
- Prompt text changes are an approval item; the behavior is specified here.

### 3.4 Dates at read time (reconsolidation)

Adopted from LongMemory's reconsolidation (`docs/reconsolidation.md`: a memory's current meaning is computed at recall, the stored memory is never mutated; "fear grounded to a tiger" — the past is preserved, not presented as present) and Hindsight's date handling.

1. **Rendered annotation**, computed against "now" (or the Previewer's simulated date):

```text
- Is in the middle of moving apartments. (since Sep 10; last mentioned 5 days ago)
- Is in the middle of moving apartments. (since Sep 10; last mentioned 7 weeks ago — may have changed)
- Has a job interview. (planned for Oct 6 — in 8 days)
- Has a job interview. (was planned for Oct 6 — 3 days ago; outcome not yet known)
- Had a hard conversation with her sister. (Sep 20)
```

   Annotation strings are model-facing wording and need approval.

2. **Date-augmented embedding text** (Hindsight `retain/embedding_processing.py`: "`<fact> (happened in <date>)`", stored text unchanged). The app already separates `memories.embedding_text` from `content` (`RetrievalDocument`), so the date phrase is added only to the embedded document. Queries like "what happened in March" then match by meaning.

3. **Coarse dates** (Hindsight `search/reranking.py` `_recency_for_unit`): a memory dated only to a month or year is aged from the **end** of that period and never receives a freshness bonus, so "sometime in 2026" is not treated as stale in August or as brand-new.

### 3.5 Strength: fade and reinforcement

Truth and accessibility are separate (LongMemory; report). Strength never deletes or changes content.

```text
strength(now) = F + (strength_base − F) · 2^(−Δdays / H)        -- Δdays since strength_updated_at
PLANNED: strength = 1.0 until occurred_start + grace, then decays from there
Coarse dates: age measured from end of the stated period
```

This is a simplified form of LongMemory's `w(t) = w0·e^(−λΔt) + Σ pulses` (`docs/formulas.md`, `src/core/math/decay.ts`): each reinforcement is a pulse that resets the baseline. Half-life form chosen because its parameters are readable in settings.

| Time kind | Half-life H | Floor F | Stale after |
|---|---:|---:|---:|
| ONGOING | 30 days | 0.20 | 30 days |
| PLANNED | 14 days after date + 3-day grace | 0.05 | — |
| EVENT | 90 days | 0.15 | — |
| ENDURING | 540 days | 0.50 | — |
| PERMANENT | none | 1.00 | — |

Starting values, all user-adjustable (§8). Validate with the Previewer's simulated date.

**Reinforcement** (baseline → 1.0, `last_confirmed_at` = now):

| Event | Reinforces |
|---|---|
| User independently restates or confirms it (Archivist `reaffirmed_existing_memory_refs`, user turns only) | Yes |
| User answers "still true" in the check-in queue | Yes |
| User edits it | Yes |
| Retrieved, sent to the AI, mentioned by the AI, or shown in the Previewer | **No** |

The "no" row follows companion-emergence (`brain/chat/prompt.py` `_build_recall_block`: "stop bumping recall_count on mere surfacing") — otherwise retrieval makes memories stronger, which makes them retrieved more.

### 3.6 Endings and supersession

- The Archivist may return `ended_existing_memory_refs` when the user says a situation ended; these route to the **existing** Possible Match / Supersede review. Nothing changes without the user.
- When the user supersedes, the old memory's `occurred_end` defaults to the new memory's `occurred_start` (Graphiti `resolve_edge_contradictions`), separate from the existing resolution timestamp `memory_supersessions.at`.

### 3.7 Stale ongoing situations and check-ins

An ONGOING memory past its stale-after period stays retrievable, is annotated "may have changed," and appears in a check-in queue in the Memory Browser: still true (reinforce) / it ended (set `occurred_end`, convert to EVENT or archive) / edit / archive. The companion stops treating a past situation as current, and the user decides when it is over.

---

## 4. Retrieval pipeline (addresses D6–D9)

### 4.1 Candidate arms, fused (Hindsight)

Run every arm every turn; cap each arm; fuse with **Reciprocal Rank Fusion** `score = Σ 1/(60 + rank)` (Hindsight `search/fusion.py`, `k = 60`, per-arm caps via `cap_per_source`).

| Arm | Source | Cap |
|---|---|---:|
| Semantic | EmbeddingGemma cosine on date-augmented embedding text; queries: latest message and recent context separately | 30 |
| Keyword (BM25) | Content, tags, aliases; stop words removed; terms selected by IDF with a proper-noun bonus (companion-emergence `_build_recall_block`; Hindsight `search/bm25_term_selection.py`) | 20 |
| Name/alias | Whole-token match of companion, project, world, campaign, character names and aliases, and capitalized proper nouns | 10 |
| Temporal | Date expressions in the message ("last month", "in March", "this week") → memories whose `occurred_*` overlaps the range (Hindsight `search/temporal_extraction.py`) | 10 |

Memories missing a vector still participate through the other arms, so an incomplete index degrades one arm instead of flipping the whole turn to keyword mode (fixes D9). Eligibility filters (scope, companion isolation, roleplay wall, status) apply before any arm, as today.

### 4.2 Query terms

The current lexical path counts any shared ≥3-letter token. Replace with companion-emergence's approach: drop stop words and fragments, select terms by corpus IDF, add a proper-noun bonus. English stop-word list first; other languages fall back to IDF-only weighting.

### 4.3 Relevance judged by an on-device reranker

The top fused candidates (auto-sized; see below) are scored by a **cross-encoder reranker** running on the phone through ONNX Runtime, which the app already ships for EmbeddingGemma (`librarian/OnnxEmbeddingModel.kt`).

- **Width auto-sizing** (companion-emergence `reranker.py` `get_rerank_width`): measure per-candidate latency once on the device and rerank as many candidates as fit a latency budget (default 400 ms; setting).
- **Model candidates** (sizes approximate, to verify before choosing): `cross-encoder/ms-marco-MiniLM-L-6-v2` (~23M parameters, English), `jinaai/jina-reranker-v1-tiny-en` (~33M, English), `jinaai/jina-reranker-v2-base-multilingual` (~278M, multilingual; the one companion-emergence ships). Selection needs on-device latency and memory measurement; offered as an optional download like the embedding model.
- **Without the reranker** (not downloaded, or too slow on the phone): fall back to fused RRF order with the current cosine floor, and label the mode truthfully in the Previewer and debug log. Retrieval still works, less precisely.

### 4.4 Match Strictness and floor calibration

- **Match Strictness** (approved control: Strict / Balanced / Broad) sets offsets around a **reranker-score floor**.
- The floor starts from a per-model default measured on a small built-in set of relevant and irrelevant pairs (companion-emergence `floor_calibration.py` "cold start" pairs).
- **Personal calibration** (adapted from companion-emergence's labeled-pair fit, which uses an LLM judge daily): in the Previewer, the user can mark a shown memory "relevant" or "not relevant" to the test message. With enough labels, the app fits the floor to the user's own judgments (companion-emergence `fit_threshold_fbeta`: F-beta cutoff leaning toward recall). No paid model call. Storage of labeled pairs is a privacy/storage decision (Decision F).

### 4.5 Ranking: relevance first, bounded boosts (Hindsight)

```text
final = relevance_normalized × importance_boost × strength_boost × context_boost
each boost ∈ [1 − α/2, 1 + α/2]
```

Hindsight `search/reranking.py` `apply_combined_scoring` uses α = 0.2 for recency and temporal, 0.1 for evidence count, so the combined effect stays within about ±20%. Proposed defaults: importance α 0.3 (±15%), strength α 0.2 (±10%), context α 0.1 (±5%); all settings. Result: importance and fading **reorder** relevant memories; they cannot beat a clearly more relevant one (fixes D7).

### 4.6 Selection and +3 (Approved: bounded tie-break, user settings)

```text
eligible = passes floor (Match Strictness), fade-adjusted:
           required_floor = floor + (1 − strength) · fade_strictness
ranked   = eligible by final score
normal   = ranked[0 ..< N]                          N = Maximum Memories Per Response (default 8)
extras   = +3 memories in ranked[N ..< N + W], best first, at most E
                                                     E = Extra +3 allowance (default 2)
                                                     W = closeness window (default 4 ranks)
then cooldown → lore overlap → budget, with the existing RetrievalBackfill walk
```

companion-emergence caps its equivalent high-importance full-inject at 3 (`relevance.py` `FULL_INJECT_MAX = 3`, importance ≥ 9) — independent evidence for a small bounded allowance.

**Approved 2026-09-28:** +3 means a bounded near-cutoff tie-break; N and E are user settings; defaults N = 8, E = 2.

### 4.7 Separate memory budget and diversity

- Implement `Maximum Memory Context` as its **own** allowance, as the approved copy spec already states (fixes D8). Default equivalent to the current 6,000 characters.
- Suppress near-duplicates in the final set (existing `NearDuplicate`), so one topic cannot fill every slot.

### 4.8 Telling the model what it does not know (companion-emergence)

companion-emergence renders "not recognised (searched; no memory found)" when a recall finds nothing. Proposed: when the user's message asks about the past ("do you remember…") and nothing clears the floor, add a short line that no saved memory matched, so the model says it doesn't remember instead of inventing. Wording and trigger need approval.

---

## 5. Current Life note (addresses D6)

### 5.1 What the research shows

Every system aimed at personal continuity keeps a **small always-present summary of the user's present state** alongside searchable memory:

- Letta: core memory blocks always in context, size-limited, rewritten by a background sleep-time agent.
- Memobase: "User Current Profile" plus "Past Events" in every request, with the instruction not to mention memories unless relevant.
- Hindsight: user-defined "mental models" — standing questions whose answers are refreshed from memories.
- MemoryOS: topics with high recent "heat" are promoted into the persistent user profile.

### 5.2 Proposal (fits owner rule §10)

Owner rule §10 forbids per-memory always-load flags but permits always-on content that is "visible, deliberate, user-written." The design follows Hindsight's mental-model framing:

- One **Current Life note**: user-visible, user-editable text (default limit 800 characters; setting), always included where real-life memory is allowed (respects the roleplay wall, owner rules §3, and per-companion enablement).
- It is a standing answer to "what is going on in my life right now?" During normal Memory Assistant runs, the Archivist may propose a **whole-note rewrite** (Letta's `rethink` pattern, bounded to the limit, dates absolute). The proposal goes through Pending with the old and new text shown. No extra API call.
- Detail stays in ordinary memories; the note is a short summary. Individual ONGOING/PLANNED memories still arrive by relevance with date annotations.

### 5.3 Heat-based suggestions (MemoryOS)

The app can compute locally which ONGOING/PLANNED memories the user reinforced most in the last N days (count of `memory_reinforcements` in window × strength). The top items are supplied to the Archivist as "currently prominent" when it considers a note rewrite, and are shown in the Previewer. No LLM cost.

### 5.4 Proactive follow-up

Memobase instructs its model not to volunteer memories unless relevant. The owner wants the AI to remember what is going on, which may include asking "how did the interview go?" Whether the companion should bring up current-life items unprompted is **Decision B**.

---

## 6. Memory Previewer

**Approved 2026-09-28:** named **Memory Previewer**; its own screen, reached by its own row under **Memory Manager**; the screen holds its own test copies of the retrieval-related settings beneath the test area; a button copies the user's regular settings into the test settings.

Trace concept from Connectome (`docs/retrieval-traces.md`: selected items, all matched candidates with match source, relevance decision, exact injected block, raw inputs opt-in). **Reimplemented independently; no Connectome code** (no license).

### 6.1 Functions

1. **Run a preview:** test message, optional pretend recent conversation, context pickers (companion, project, world/campaign/character, real-life-in-roleplay), **simulated date**.
2. **Summary:** which arms ran and how many candidates each gave; reranker on/off and why; eligible, sent, +3 extras; budget used; removals by reason.
3. **Sent list:** full text, scope/target, Type, time kind, dates, importance, strength now, arm ranks, reranker score vs floor, boosts, final score, reason sent (normal slot or +3 extra).
4. **Not-sent list:** each near candidate with its exact reason (below floor; below fade-adjusted floor; below cutoff; +3 outside window; extra allowance used; cooldown — shown as "would be skipped in a real chat", with a toggle to ignore; lore overlap; budget; scope/companion/roleplay wall).
5. **Exact text** the AI would receive: Current Life note plus "Things you know" with date annotations.
6. **Compare:** regular settings vs test settings on the same input, with differences highlighted.
7. **Replay:** re-run a recent real turn from the in-memory `AssemblyLog` with test settings.
8. **Relevance marking:** mark results relevant / not relevant to feed personal floor calibration (§4.4; Decision F).
9. **Library health:** counts by scope, Type, time kind, status, importance, strength band, stale ongoing items, missing vectors.

### 6.2 Technical requirements

- **One code path.** Refactor the `Enforcer` retrieval/selection core to take an explicit `RetrievalSettings`, a `Clock`, and a side-effects flag. Live chat passes real settings, the real clock, side effects on. The Previewer passes test settings, the simulated clock, side effects off.
- **Dry run:** no cooldown stamps, no reinforcement, no memory-database writes, no network.
- Produces an in-memory `RetrievalTrace` rendered by both the Previewer and the existing debug log.
- Showing a trace on screen is not logging. Persisting traces, or any new Logcat/Event/error log line, needs separate approval (`CLAUDE.md` §8). None is proposed.
- UI follows `ui-style-guide.md` / `ui-style-adoption.md` with a component map before implementation. No AMOLED work.

---

## 7. Consolidation (later stage)

- Hindsight "observations": synthesized from facts, carrying `proof_count`, `source_memory_ids`, and change history (`engine/consolidation/consolidator.py`).
- Generative Agents: reflection runs when the importance of new memories accumulates past a threshold.
- Adaptation: when enough EVENT memories accumulate for a period or topic, the Memory Assistant offers a consolidation run (user-triggered, bounded input, one API call per group). The Archivist proposes a "chapter" ENDURING memory linked to its sources:

```text
memory_sources (derived_memory_id, source_memory_id; both FK ON DELETE CASCADE; PK both)
```

It goes through Pending. Deleting a source flags derived chapters for review. A-MEM's automatic rewriting of neighbors is deliberately not adopted.

---

## 8. User-adjustable settings (owner direction: expose tuning)

The Previewer holds test copies of every live-retrieval setting. Ordinary vs advanced grouping is a UI decision.

| Setting | Default | Status |
|---|---|---|
| Maximum Memories Per Response (N) | 8 | **Approved** |
| Extra +3 allowance (E) | 2 | **Approved**; wording needed |
| +3 closeness window (W) | 4 ranks | Proposed |
| Maximum Memory Context (own budget) | ≈1,500 tokens (6,000 chars) | Spec'd; unit Decision G |
| Memory Match Strictness | Balanced | Spec'd; now reranker-based |
| Memory Priority | Balanced | Spec'd |
| Use Importance Ratings | On | Exists |
| Importance / fade / context influence (α values) | 0.3 / 0.2 / 0.1 | Proposed advanced |
| Fade speed per time kind (H, F), stale-after, planned grace | §3.5 | Proposed advanced |
| Fade strictness | 0.5 × floor margin | Proposed advanced |
| Reranker on/off and latency budget | On if downloaded / 400 ms | Proposed |
| Show dates to the AI | On | Proposed |
| Current Life note on/off, length, per companion | On / 800 chars | Depends on Decision A |
| Repeat cooldown (turns) | 10 | Owner rule §10 says constant — Decision D |

Internal data-safety rails (`RetrievalPolicy` bounds) stay internal.

---

## 9. What this design deliberately does not adopt

| Idea | Source | Reason |
|---|---|---|
| Graph database / entity graph | Graphiti, Hindsight graph arm | Deferred by `project-plan.md`. The name/alias arm gets most of the benefit. |
| AI rewriting existing memories automatically | A-MEM evolution, Mem0 UPDATE/DELETE, Letta block edits | Human-review boundary (owner rules; Revision 26). Rewrites become Pending proposals. |
| Automatic deletion ("graveyard") by low salience | companion-emergence `forgetting/` | Owner rules: the system never deletes; fading lowers accessibility only. |
| Emotion vectors, hebbian co-activation, "soul" links | companion-emergence | Require an always-running companion brain; large scope for uncertain gain. Reconsider after V2. |
| Confidence arithmetic in log-odds, contradiction pressure | LongMemory | The user resolves contradictions in Possible Match; automatic truth math adds opacity. |
| Paid LLM judge for daily calibration | companion-emergence `relevance_judge.py` (Haiku tie-break) | Replaced by the user's own relevance marks in the Previewer; no recurring cost. |
| Rank-position recency | Generative Agents (and the current app) | Replaced by elapsed-time strength. |
| Snippet-then-read tool calls | companion-emergence | Depends on provider tool support; possible later token saving. |

---

## 10. Staging

Each stage is testable and useful on its own. Roadmap placement is an owner decision (`project-plan.md`: one active feature).

| Stage | Contents | Depends on |
|---|---|---|
| 1 | **Memory Previewer** with the settings/clock/dry-run seam, test settings, library health | — |
| 2 | **Retrieval repair:** stop words/IDF/proper nouns, fused arms (semantic, BM25, name), bounded boosts, +3 bounded extras, N/E/strictness/priority settings, separate budget, diversity | 1 |
| 3 | **Reranker:** on-device cross-encoder download, latency-sized width, floor, Strictness on reranker scale, personal calibration marks | 1, 2 |
| 4 | **Time foundation:** schema, migration, backup; date anchoring and time fields in the Archivist; Pending shows and edits dates; date annotations; date-augmented embeddings; temporal arm | Feature 1 device proof |
| 5 | **Fade, reinforcement, check-ins, endings** | 4 |
| 6 | **Current Life note** with heat suggestions | 4; Decision A |
| 7 | **Consolidation chapters** | 4, 5 |

Recommended order: 1 → 2 → 3 → 4 → 5 → 6 → 7. The Previewer comes first because every later stage is tuned and checked with it. Retrieval comes next because the flooding is the most visible current defect.

`project-plan.md` lists Feature 1 (API Memory Assistant Repair) as active. Its code is largely present on `main` (DB v31: `analysis_chat_bookmarks`, `memory_possible_match_hints`, `Librarian.searchForReconciliation`, `related_existing_memory_refs` in the prompt). Its completion gate is the on-device changed-fact proof. Stage 4 extends the same Archivist protocol, so Feature 1 should be proven first.

---

## 11. Verification scenarios (automated; no provider credentials)

Fake Archivist and injectable clock (Revision 26 Stage F seam):

1. "Interview next Tuesday" in a conversation dated 2026-09-28 → PLANNED, `occurred_start 2026-10-06`, absolute date in content.
2. Per-segment reference time: two transcript rows on different dates resolve "yesterday" differently.
3. PLANNED annotation before the date, and "outcome not yet known" after it; strength decays after the grace period.
4. ONGOING becomes stale after 31 days without confirmation; "still true" reinforces.
5. User restatement reinforces; AI restatement, retrieval, and Previewer runs do not.
6. A highly relevant old ENDURING memory is still sent; a marginal faded EVENT is not.
7. Bounded boosts: the maximum importance × strength × context boost cannot reverse a relevance gap larger than their combined range.
8. +3: added at rank N+2; not at rank N+W+1; the (E+1)-th extra is not added; below the floor is never added.
9. The query "the" retrieves nothing; "Portland" retrieves the Portland memory.
10. Naming a companion, project, or person alias retrieves its memories through the name arm.
11. Partial vector index: memories without vectors are found by BM25 and names; no whole-turn mode flip.
12. Temporal arm: "what happened last month" retrieves EVENTs in that range.
13. Coarse date: a "2026" memory is not stale during 2026.
14. The reranker fallback path is labeled, and results match the RRF-only order.
15. Separate budget: a lore-heavy turn does not shrink the memory allowance.
16. Previewer dry run: no cooldown stamps, reinforcement rows, or database changes; output identical to a live turn with the same settings, clock, and cooldown state.
17. Migration: time kind comes from Type; strength is 1.0 at migration; backup/restore round-trips; old backups import.
18. Ended situation routes to Possible Match; nothing mutates without the user.
19. The Current Life note rewrite goes through Pending and respects the roleplay wall and per-companion enablement.
20. Supersession sets the old memory's `occurred_end` to the new memory's `occurred_start`.

---

## 12. Decisions

**Status for all: No code has been changed.** Ordered by architectural weight.

- **A. Current Life note** (§5): adopt the user-approved, Archivist-maintained note. Recommended: yes.
- **B. Proactive follow-up:** may the companion bring up current-life items unprompted ("how did the interview go?"), or only when relevant to what the user says (Memobase's rule)?
- **C. Time model** (§3): time kinds, two time axes, date anchoring, date annotations, date-augmented embeddings. Recommended: yes.
- **D. Cooldown as a user setting:** owner rule §10 currently says it is a constant.
- **E. Reinforcement without Pending** (§3.5): automatic, visible, reversible. Recommended: yes.
- **F. Personal calibration marks** (§4.4): store the user's relevant / not-relevant marks from the Previewer (test text + memory ID + mark) locally in the encrypted memory database, with a clear control to erase them. Recommended: yes.
- **G. Budget unit:** show tokens (approved spec wording), estimate internally from characters. Recommended: yes.
- **H. Optional reranker download** (§4.3), after on-device measurement picks the model. Recommended: yes.
- **I. "Apply test settings to my regular settings"** Previewer button, with confirmation. Recommended: yes.
- **J. Staging and roadmap placement** (§10).

Wording for every new control, annotation, check-in action, and Archivist prompt change is a separate approval after the behavior decisions.
