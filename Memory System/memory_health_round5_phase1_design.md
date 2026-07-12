# Round 5, Phase 1 — Memory Health, Silent Degradation, and Recovery (DESIGN ONLY)

> **STATUS: DRAFT AND PARTIALLY SUPERSEDED**
>
> Round 4, covering Keystore loss and storage-lock recovery, has not been
> designed. Any proposal in this document that chooses plaintext
> SharedPreferences or another final storage location for memory-health state
> is withdrawn and not approved. Persistent health storage, cross-restart
> repair state, reset-survival behavior, and handling of unreadable health
> storage remain blocked by Round 4. Unreadable or unavailable storage must
> never be interpreted as empty or automatically reinitialized. The pipeline
> findings and storage-independent health logic remain useful design material.

**Status: DESIGN AWAITING OWNER APPROVAL. Nothing in this document is built.
Phase 2 (user-facing wording/placement) has not begun and must not begin
until the owner approves this design.**

Date: July 12 2026. Branch: `claude/phosphor-memory-health-audit-hjw6ow`.
Audited against the current `main` (through the Round 3 merge, a34fa7f).
Every claim below was verified by reading the current code; file:line
references are to that state of the tree.

Scope guard: Round 4 (Keystore loss / store-lock recovery) is referenced
only where the health system must *represent* that state; its design is not
revisited here. Rounds 1–3 machinery (rename transaction, RenameJournal,
capture recovery, message completion states) is treated as fixed.

---

## 1. Current memory pipeline map

### 1.1 Per-turn path (every generation, ChatActivity's single funnel)

```
regularGPTResponse
 ├─ Lorebook gather                      ChatActivity.kt:4713-4744
 │    try/catch → gated MemoryLog line; activeLoreBookCount = -1 sentinel;
 │    every turn recorded to LoreBookInjectionLog (in-process ring, 50)
 ├─ Model-rules block                    ChatActivity.kt:~4670
 │    try/catch → gated MemoryLog line; block absent on failure
 ├─ Enforcer gate                        ChatActivity.kt:4755-4757
 │    getChatMemoryEnabled() && MemoryStore.isProvisioned()
 │    └─ Enforcer.assembleTurn (Dispatchers.IO)   Enforcer.kt:124-499
 │        ├─ provisionOperatingDefaults   catch → note in AssemblyLog
 │        ├─ companion resolution         MemoryCompanionSync.ensureCompanionForPersona
 │        │     catch → null (gated log); chat proceeds companion-less
 │        ├─ campaign/world/character fetch   catch → null each (silent)
 │        ├─ updateAppState mirror        catch → swallowed (mirror only)
 │        ├─ Librarian.search             Librarian.kt:258-302
 │        │     no model / self-check failed → keyword fallback
 │        │     vector path throws → gated log + keyword fallback
 │        │     search() throws entirely → Enforcer catch → note, empty list
 │        ├─ near-duplicate suppression   embedOrNull per pair; null → text check
 │        │     suppressed pairs → flagContradictions → meta JSON (write-only)
 │        ├─ freshness cooldown           catch → note, suppression skipped
 │        ├─ card retrieval (Zone 2)      catch → note, no cards this turn
 │        ├─ budget + injection stamps    catch → note (re-inject risk only)
 │        └─ AssemblyLog.record           in-process ring, 50, lost on death
 │    catch at call site (4777-4783) → gated MemoryLog line +
 │    notifyMemoryDegradedOnce() [Toast, per-ACTIVITY-INSTANCE flag] +
 │    degrade to classic lore message
 └─ finally → recordTranscriptTurn       ChatActivity.kt:4163-4174, 4215-4263
      reads Round-3 completion state; fire-and-forget Thread →
      TranscriptRecorder.recordTurn      TranscriptRecorder.kt:45-107
        skip paths (blank / not provisioned / "Don't archive") → gated log
        appendTranscriptTurn (insertOrThrow, transactional)
        ANY failure → catch → gated MemoryLog line. Nothing else.
```

### 1.2 Startup path (MainApplication.onCreate, one background Thread)

```
Logger.logLastExitReason            (ungated, event channel)
Thread:
 1. RenameJournal.reconcile          failure → gated log; entry retained → retry next start
 2. if MemoryStore.isProvisioned:
    a. integrityCheck()              problem → GATED MemoryLog line + Toast
    b. backfillExistingChats (once)  META_BACKFILL_DONE set EVEN IF the
                                     backfill failed internally (it catches
                                     and returns 0) → permanent silent skip
    c. MemoryExporter.autoExportIfDue  failure → gated log; throttle marker
                                     left unset → retries next start
(MemoryStore.onOpen: purgeSystemModesOnce — retries until flag set)
```

### 1.3 Edit / reindex path

```
MemoryEditorActivity save (worker thread)      MemoryEditorActivity.kt:585-633
 ├─ store.insertMemory / updateMemory          MemoryStore.kt:4437-4477
 │    updateMemory drops embeddings on text change (correct)
 └─ Librarian.reindexMemory                    Librarian.kt:216-227
      no usable model → SILENT no-op (returns; nothing queued, nothing logged)
      embed throws → gated log; vector stays missing
MemoryBrowserActivity Accept (activate)        MemoryBrowserActivity.kt:378
 └─ setMemoryStatus('active') drops vectors → reindexMemory (same holes)
Recovery today: ONLY the manual Rebuild Index button
(AdvancedMemorySettingsActivity.kt:301-317) or noticing the
"memory_index_stale" line on that screen. Nothing automatic.
```

### 1.4 Archivist path (user-triggered from MemoryAssistantActivity)

```
Archivist.analyze/rerun → run()                Archivist.kt:164-401
 ├─ not configured → logAlways + outcome not_configured
 ├─ per conversation, per chunk:
 │    chat call → ArchivistResponseParser.parse (throw → UNREADABLE)
 │    parsed.dropped (validation) → GATED log, count NOT persisted
 │    importance floor drops → GATED log, count NOT persisted
 │    cap drops → GATED log, count NOT persisted
 │    duplicate skips → counted in RunOutcome.duplicatesSkipped (persisted? no —
 │    RunOutcome only; run row has foundCount etc., not filter stats)
 │    draft insert failure → logAlways + SAVE_FAILED aborts conversation
 │    rule draft insert failure → GATED log, run continues
 ├─ conversation failure → logAlways, rows stay pending → next run retries
 ├─ CancellationException → outcome interrupted, unprocessed rows stay pending
 └─ run row written to archivist_runs (logAlways if the write fails)
ArchivistPrompt.renderTurns                    ArchivistPrompt.kt:135-157
 drops assistant turns with complete:false (Round 3 gate) — EXCEPT the
 malformed-JSON fallback, which passes the RAW stored JSON through,
 bypassing the gate (see §11).
```

### 1.5 Logging and display substrate

- `Logger` (Logger.kt): four persistent channels in EncryptedPreferences file
  `"logs"` — survive process death. `memory` channel: **1000 entries / 7 days**
  (Logger.kt:201-202). Trim is whole-entry, age first.
- `MemoryLog` (MemoryLog.kt): the single funnel to the `memory` channel.
  `log()` gated on `memory_debug_logging` (global, default **false**);
  `logAlways()` ungated but **reserved by owner ruling for Archivist run
  failure records** (MemoryLog.kt:48-52) — seven call sites, all in Archivist.
- `AssemblyLog` / `LoreBookInjectionLog`: in-process rings (50 records),
  rendered by LoreBookDebugActivity, **lost on process death**.
- Existing "health-like" surfaces: AdvancedMemorySettingsActivity System
  Status (integrity result, row counts, chats-since-backup, pending count,
  last backup, index ok/stale/none, debug search) and MemoryAssistantActivity
  (facts row, spec-worded run outcomes, Recent Memory Analysis with the
  Deleted-Later badge). MemoryControlsActivity shows only last-backup time.

---

## 2. Failure and degradation inventory

Attributes per row: **Caught where / Continues? / Fallback / What is lost /
Auto-recovery / Persisted? / Diagnostics-gated? / Survives death? / User
action needed / Spam risk / Cleared on later success?**

### A. Transcript capture

| # | Failure | Facts |
|---|---|---|
| A1 | `appendTranscriptTurn` throws (constraint, SQLCipher error, disk) | Caught TranscriptRecorder.kt:104. Turn continues. No fallback. **The turn is permanently absent from the memory archive** (the chat itself still holds the text — see §5 recovery). No auto-recovery. Log: gated only. Not visible, survives death only as a log line if the toggle was on. No user action prompted. Spam: one line per failed turn. Never cleared/reconciled. |
| A2 | Synchronous prep in `recordTranscriptTurn` throws | Caught ChatActivity.kt:4254-4262 → gated log. Same profile as A1. |
| A3 | Skip paths: blank fields / not provisioned / "Don't archive" | Deliberate no-ops, gated info lines. These are **"nothing to save," not failures** — the health design must keep them out of the failure counters. |
| A4 | `ensureCompanionForPersona` fails during capture | Caught inside sync (gated log) → capture proceeds with `companion=null`; turn is archived but **unattributed** (quality loss, silent). Self-heals next turn if the store recovers. |
| A5 | Backfill fails internally | TranscriptRecorder.kt:164-167 catches, returns 0; MainApplication.kt:130-133 sets `META_BACKFILL_DONE = "1"` regardless → **pre-existing chats permanently never enter the review queue, invisibly**. |

### B. Embedding / semantic index

| # | Failure | Facts |
|---|---|---|
| B1 | Model load throws (`ensureModel`) | Caught Librarian.kt:186-190. `modelLoadFailed` latched **for the process**. Fallback: keyword retrieval. Quality-only loss. Auto-recovery: next process restart, or `invalidateModel()` on download/delete. Gated log only. |
| B2 | Semantic self-check fails | Caught Librarian.kt:171-183. Same latch + keyword fallback. **No persistent record**; recurs (and re-logs) every process until the model is re-downloaded. The Advanced screen's index-status line reads from storage/meta, not the load state, so it can show "ok"/"stale" while semantic retrieval is actually disabled this process (AdvancedMemorySettingsActivity.kt:319-333). |
| B3 | `embed()` throws mid-search | Caught Librarian.kt:297-299 → keyword fallback this call. Gated log. Quality-only, self-clearing. |
| B4 | `embedOrNull` fails (near-dup checks) | Returns null → word-overlap fallback. Silent beyond gated log. Quality-only. |
| B5 | **Edit while no model usable** | `updateMemory`/`setMemoryStatus` correctly drop the stale vector (MemoryStore.kt:4465-4468, 4497); `reindexMemory` then silently no-ops (Librarian.kt:216-219). The memory stays active but **invisible to semantic ranking** — `search()` ranks only `withVectors` (Librarian.kt:285-296); an unembedded memory doesn't even get keyword fallback while other memories have vectors. Recovery: manual Rebuild only. Detectable today via `countMissingEmbeddings` but surfaced only if the user visits Advanced Memory Settings. |
| B6 | Per-memory embed failure during rebuild | Caught Librarian.kt:353-355, loop continues, `done++` anyway; META tag set at the end → index declared current while N memories still miss vectors (the stale-hint then re-arms via `countMissingEmbeddings`, but nothing retries). |
| B7 | Rebuild interrupted (process death, activity gone) | No resume point; META tag not yet set → `indexNeedsRebuild()` true. Restartable-by-hand, idempotent. No record that it was interrupted. |
| B8 | Self-check marker write fails | Swallowed (Librarian.kt:181) → re-check next process. Benign. |
| B9 | Model download failure / partial install | Downloader keeps completed files, `isInstalled` false until complete; failure shown as a Toast in Advanced settings (pre-ban leftover). Repair = re-tap Download. |

### C. Retrieval

| # | Failure | Facts |
|---|---|---|
| C1 | `Librarian.search` throws entirely | Caught Enforcer.kt:208-213 → empty retrieval, note in AssemblyLog only (in-process). Turn proceeds with lore + scene only. |
| C2 | Keyword fallback in use (no model) | Not a failure; noted per turn in AssemblyLog ("no embedding model — keyword retrieval"). Quality-only. No persistent record that the user has been on keyword retrieval for three weeks. |
| C3 | Cooldown clock/store failure | Caught Enforcer.kt:276-278 → suppression skipped (inject-rather-than-starve). Note only. |
| C4 | Injection stamp failure | Caught Enforcer.kt:401-419 → possible premature re-injection later. Note only. |

### D. Enforcer assembly

| # | Failure | Facts |
|---|---|---|
| D1 | `assembleTurn` throws | Caught ChatActivity.kt:4777-4783 → classic-lore-only turn (reduced context), gated log, plus `notifyMemoryDegradedOnce()` — a **Toast** (banned pattern, flagged in Phase 7) whose once-flag is a ChatActivity **instance** field (ChatActivity.kt:4189), not per-process as its comment claims: rotation/recreate re-arms it. |
| D2 | Sub-steps (defaults, campaign fetch, cards, scene records) | Each caught individually → emptier assembly + AssemblyLog note. All quality-only, all invisible after process death. |
| D3 | Lorebook store unavailable | Caught ChatActivity.kt:4734-4739 → no lore this turn, gated log, `-1` sentinel in the in-process debug log. |

### E. Archivist

| # | Failure | Facts |
|---|---|---|
| E1 | Not configured / run failures / interruption | The **gold standard today**: typed reasons (ArchivistFailure A–G), ungated `logAlways`, persisted run rows, spec-worded UI, pending rows retried next run automatically. |
| E2 | Filter drops: validation (`parsed.dropped`), importance floor, cap | Counted transiently, **gated log lines only, not persisted on the run row** — the Memory Assistant cannot tell the user "3 suggestions were below your importance floor," although the owner configured that floor. |
| E3 | Duplicate skips / rejected-draft skips | `duplicatesSkipped` reaches RunOutcome (drives "found only memories that already exist") but is not stored on the run row; rejected-draft skips are gated-log only. |
| E4 | Rule-draft insert failure | Gated log only; run continues; count lost. |
| E5 | Run-record write failure | logAlways — but then the run vanishes from Recent Memory Analysis with no other persistent trace of its outcome. |

### F. Sync, contradiction flags, DB, export

| # | Failure | Facts |
|---|---|---|
| F1 | Persona→companion sync failure (`onPersonaSaved`) | Caught MemoryCompanionSync.kt:112-116, gated log. Companion mirror drifts (stale name/prompt, or missing record) until the next edit or bootstrap re-run. Silent. |
| F2 | Contradiction flags | Written by Enforcer.kt:659-681 to meta `enforcer.contradiction_flags`, capped 50 (oldest silently dropped). **Nothing reads them.** The comment "Phase 6 reads and clears this list" (Enforcer.kt:657) is stale — Archivist never touches it. Write-only forever; survives even Reset Memories (meta table is not cleared by `resetAllMemoryData`, MemoryStore.kt:4246-4285). |
| F3 | Startup `integrityCheck` failure | **Gated** log + a Toast (MainApplication.kt:120-125). With diagnostics off, a corrupt database leaves no persistent trace at all; the Toast is a banned pattern and evaporates. Backfill/auto-export are skipped that start (correct), silently. |
| F4 | Store locked (Round 4: key unreadable, DB exists) | `MemoryStore.getInstance` throws IllegalStateException (MemoryStore.kt:96-99). Every caller catches → each component reports its own local symptom (capture failed, assembly failed…) with no shared "the store is locked" state. |
| F5 | Auto-export failure | Gated log; throttle marker unset → retried next start (good). But **repeated failure across weeks is indistinguishable from success** without the toggle on; `textLastBackup` going stale on MemoryControls is the only hint. Pre-reset backup failure is handled well (aborts the reset, dialog). |
| F6 | Import | Transactional; ImportReport counts added/skipped → shown (as a Toast — pre-ban leftover). Imported memories arrive **without vectors** → same hole as B5 until a manual rebuild. |

### G. Conflicts between the earlier audit description and current code

1. *"Logging hidden behind a diagnostics toggle that is off by default"* —
   true for everything **except** the seven Archivist `logAlways` sites and
   RenameJournal's recovery lines (which go ungated to the `crash` channel).
2. *"Important state held only in a small in-process log that disappears
   after process death"* — true of **AssemblyLog / LoreBookInjectionLog**
   (50-record rings). **Not** true of MemoryLog: it persists in encrypted
   prefs — but it is gated, and its retention is 1000 entries / 7 days, so
   the *origin* of a long-lived degradation ages out even when the toggle
   was on.
3. *"Contradiction flags written but never consumed"* — confirmed, plus the
   stale code comment claiming Phase 6 consumes them.
4. `notifyMemoryDegradedOnce` documents "once per process" but implements
   once per activity instance.
5. Two silent failures **not** in the earlier list: the backfill-done flag
   set on failure (A5), and the index-status line able to read "ok" while a
   self-check failure has semantic retrieval disabled (B2).
6. The Round-3 incomplete-turn gate has a bypass: `renderTurns`'
   malformed-JSON fallback ships the raw stored JSON to the model,
   incomplete fragments included (§11).

---

## 3. Structured health-state model

One system, one vocabulary. New package `preferences/memory/health/`.

### 3.1 Levels (`HealthLevel`)

| Level | Meaning | Examples |
|---|---|---|
| `HEALTHY` | Working as designed | — |
| `TEMP_DEGRADED` | Failed recently, expected to self-clear, no fallback engaged | one-off retrieval exception (C1) |
| `FALLBACK_ACTIVE` | Working, on a designed lesser path | keyword retrieval (B1/B2/C2), lore-only turns (D1) |
| `AWAITING_RETRY` | Failed; a bounded automatic retry is scheduled/possible | auto-export failure (F5), reindex sweep pending pass |
| `AWAITING_REPAIR` | Specific data needs work before it is fully usable | memories missing vectors (B5/B6/F6), interrupted rebuild (B7) |
| `USER_ACTION_REQUIRED` | Cannot recover without the user | self-check failure needing re-download (B2), store locked (F4), model switch needing rebuild |
| `DATA_LOSS` | Something is confirmedly gone or was never saved | failed transcript capture (A1/A2), draft insert failure (E, SAVE_FAILED) |
| `RECOVERED` | Was degraded, now verified working; kept in history | any of the above after clearing |
| `UNKNOWN` | Unrecognized persisted state (forward compatibility) | a future build's category read by this build — preserved, never coerced |

Severity is a property of the **category**, not the level: every category
carries `impact ∈ {quality_only, delayed, omitted, possible_loss,
confirmed_loss}` so keyword fallback can never render as catastrophe and a
capture failure can never render as a hiccup.

### 3.2 Components

`TRANSCRIPT_CAPTURE, EMBEDDING, SEMANTIC_RETRIEVAL, KEYWORD_RETRIEVAL,
ARCHIVIST_RUN, ARCHIVIST_FILTERING, COMPANION_SYNC, MEMORY_REINDEX,
CONTRADICTIONS, ENFORCER_ASSEMBLY, MEMORY_DB, EXPORT_BACKUP`

(KEYWORD_RETRIEVAL exists so "even the fallback is failing" is expressible;
ARCHIVIST_FILTERING is state about discarded-suggestion counts, normally
HEALTHY with data, per the requirement that filtering is not a failure.)

### 3.3 Per-component record (`ComponentHealth`)

```
component        enum name (string-persisted)
level            HealthLevel (string-persisted; unknown string → UNKNOWN)
category         short stable key, e.g. "selfcheck_failed", "capture_db_error",
                 "store_locked", "vector_search_error", "export_failed"
firstSeenMs      epoch of the first occurrence of the ACTIVE episode
lastSeenMs       epoch of the latest occurrence
occurrences      count within the active episode (monotonic)
fallbackMode     nullable: "keyword", "lore_only", "text_neardup", "none"
retry            { attempts, budget, nextEligibleMs } or null
dataLoss         NONE | POSSIBLE | CONFIRMED
qualityOnly      boolean (derived from category impact)
userActionKey    nullable stable key naming the needed action (Phase 2 maps
                 it to wording; backend never stores prose)
active           boolean
recoveredAtMs    nullable
detail           one short technical string (exception class + message,
                 truncated; NEVER conversation or memory content)
```

History: when an episode recovers, it is appended to a bounded per-component
history list (keep the most recent 20 episodes; aggregates below survive
trimming). Lifetime aggregates per component: `lifetimeEpisodes`,
`lifetimeOccurrences`, `lastRecoveredAtMs` — these are never trimmed, so
"this keeps happening across sessions" stays diagnosable even after episode
detail ages out (requirement: no silent deletion of diagnosis history).

### 3.4 Transition rules (spam control at the model level)

- `report(component, category, …)` with the **same active category** →
  bump `occurrences`/`lastSeenMs` only. **No log line.**
- New category, level escalation, or dataLoss escalation → update record +
  **one** persistent log line (§4).
- `recovered(component)` (explicit, or implied by a success signal defined
  per component in §5) → close episode, append history, **one** log line.
- Success on a HEALTHY component → no-op (no bookkeeping cost on the hot
  path beyond a volatile read).
- UNKNOWN persisted data is carried forward verbatim, surfaced as UNKNOWN,
  never overwritten except by an explicit new report for that component.

---

## 4. Persistent logging design

### 4.1 Storage: a dedicated health store, not more log lines

`MemoryHealthStore`: a **plain SharedPreferences file `memory_health`**
holding one JSON blob per component plus one counters blob.

Why plain prefs and not the memory DB or EncryptedPreferences:
- The store must keep working when `companion_memory.db` is locked, corrupt,
  or unprovisioned — MEMORY_DB is itself a monitored component (F4).
- Independence from the Keystore matters for the same reason (Round 4: an
  EncryptedPreferences health store would die with the exact failure it is
  supposed to describe).
- Contents are enum names, stable keys, counters, timestamps, and truncated
  exception strings — **never** conversation text, memory titles, or memory
  content. (Owner decision §14.3 if encrypted-anyway is preferred.)

Writes: synchronous `commit()` on state **transitions** (rare); counter
bumps accumulate in memory and flush via `apply()` on a throttle (every 25
increments or 60 s, whichever first, plus on any transition commit). A
process kill between flushes loses at most a few counter ticks — never a
state transition.

### 4.2 The narrative channel: reuse the existing Memory Debug Log

Health **transitions** (not occurrences) write one line each to the
existing `memory` Logger channel, **ungated**, via a new
`MemoryLog.health(context, message)` next to `logAlways`.

- This honors "no new independent diagnostics toggle" and "reuse existing
  controls": the lines land in the Memory log the user already has, and the
  existing `memory_debug_logging` toggle keeps gating the *detail* firehose
  exactly as today.
- **Owner gate:** `logAlways`'s comment reserves ungated writes for
  Archivist run records by owner ruling (MemoryLog.kt:48-52). Extending
  that ruling to health *transitions* needs an explicit owner yes (§14.1).
  The class of information is the same one the owner already ruled
  user-relevant ("recovery information, not optional debug noise"), and
  transition-only logging bounds the volume structurally: a component that
  fails identically 500 times in a week produces **one** begin line, a
  possible escalation line, and one recovery line.
- Rate backstop on top of transition semantics: at most one ungated health
  line per (component, category) per 6 hours even across process restarts
  (`lastHealthLogMs` in the store), so a crash-loop cannot flood the
  channel. The 7-day/1000-entry retention of the channel is acceptable
  because the **store** (not the log) is the durable state: firstSeen
  survives even when the log line ages out.

### 4.3 Classification discipline

- Begin lines name the fallback explicitly and carry the impact class, e.g.
  category `keyword_fallback` is `quality_only` — the writer cannot express
  it as loss. Categories with `confirmed_loss` (capture failure, draft save
  failure) are the only ones allowed to say data was lost.
- Ungated lines never include conversation or memory content — ids and
  counts only. Full detail (messages, per-item lines) remains behind the
  existing gated `MemoryLog.log`, unchanged.
- Specific ungated promotions (each currently gated-invisible, each meeting
  the owner's "recovery information" bar): startup `integrityCheck` failure
  (F3), transcript capture failure begin/recover (A1), self-check failure
  begin (B2), store-locked begin (F4), auto-export repeated failure —
  "repeated" defined as ≥3 consecutive failed starts (F5), backfill failure
  (A5). Everything else stays gated as today, plus its silent health-store
  bookkeeping.

---

## 5. Recovery and retry design

Per scenario. Common laws: every recovery job is **idempotent** (re-runnable
from scratch), **derived** from durable state (never from an in-memory
queue), **bounded** (attempt budgets, chunk sizes), and gated on
`isProvisioned()` (never provisions the store; never runs while
`RenameJournal.hasPending` would make its reads stale — reconcile runs
first, as today).

| Scenario | Design |
|---|---|
| **Embedding self-check failure** | On failure: EMBEDDING → `USER_ACTION_REQUIRED` / `selfcheck_failed`, fallbackMode=keyword, userActionKey=`redownload_model`; ungated begin line. The latch stays per-process as today. Recovery signal: `ensureModel()` success (any process) while the active episode is `selfcheck_failed` → `recovered`. No auto-retry beyond the existing "re-check after re-download" (marker deletion already forces it). |
| **Model becomes available later** | Hook in `ensureModel()` success: close any EMBEDDING episode; then **signal the reindex sweeper** (below) — this is what fixes B5 automatically when the model comes back. |
| **Failed vector generation (single memory)** | Counted (`embeddings_failed`); memory remains in the derived missing set. The sweeper retries it with a per-memory attempt count kept in the health store (key: memoryId → attempts). Budget: 3 sweep attempts per memory; exceeded → that id is parked and MEMORY_REINDEX → `AWAITING_REPAIR` with `userActionKey=rebuild_index` (a full manual rebuild resets all park counters, as does a model re-download). Parked ids are counts + ids only, never content. |
| **Edited memories awaiting reindex (B5) — hard requirement** | **The queue is derived, not stored**: `active memories lacking an embedding for the active model tag` (`countMissingEmbeddings` + a new `missingEmbeddingIds(tag, limit)`). Derivation makes the queue impossible to lose or corrupt, automatically restartable, and automatically consistent after import/reset/delete. `reindexMemory`'s silent no-op becomes: report MEMORY_REINDEX `AWAITING_REPAIR` (count = missing), no log spam (transition only). **ReindexSweeper** drains it: triggers = (1) startup housekeeping (after integrity check), (2) first `ensureModel()` success in a process, (3) after a manual rebuild completes (to verify empty). Chunked (32 embeds per pass, then re-derive and continue until empty or cancelled), runs on one background thread, checks a cooperative cancel flag. When the missing set reaches 0 **and** the active tag matches, set `META_INDEX_MODEL_TAG` to the active tag (this is what lets an **interrupted rebuild** finish automatically — same end state as a completed rebuild). Sweeper never deletes foreign-model vectors (that stays rebuild's job; foreign rows are inert because `activeEmbeddings` filters by tag). |
| **Model switched (tag mismatch)** | Not auto-swept (a full re-embed of everything is heavy and user-visible): MEMORY_REINDEX → `USER_ACTION_REQUIRED` / `model_changed`, userActionKey=`rebuild_index`. (Owner may later choose auto-rebuild; §14.6.) |
| **Retrieval exceptions** | SEMANTIC_RETRIEVAL → `TEMP_DEGRADED` on a vector-path throw (fallback keyword, self-clears on next success → `recovered`); sustained keyword operation because no model is installed is **not** an episode at all — it is the designed state of the "lorebooks"/no-model configuration. Only an *unexpected* fallback (model installed but unusable) opens an episode. |
| **Transcript capture failure — hard requirement** | A1/A2 open TRANSCRIPT_CAPTURE `DATA_LOSS`/`capture_failed` (confirmed loss of that turn from the archive; the chat text itself still exists). Occurrences accumulate per episode; recovery signal = next successful capture → `RECOVERED` (history keeps the episode with its count and time span). Skip paths (A3) are counters only (`captures_skipped_*` by reason) and can never open an episode — this is the "failed ≠ nothing worth saving" distinction. **Optional repair path (owner decision §14.5):** a per-chat "re-archive from chat history" backfill (the machinery exists as `insertBackfillTranscript`) could reconstruct lost turns; not designed further here. |
| **Archivist failure** | Already recovers (pending rows re-eligible). Additions only: counters + persisted filter stats (§8), and ARCHIVIST_RUN mirrors the last outcome (a `full_failed` outcome opens `AWAITING_RETRY` with userActionKey from ArchivistFailure.settingsRelated; a later completed run closes it). No new retry machinery — runs stay user-triggered per owner rules. |
| **Sync failure** | COMPANION_SYNC `TEMP_DEGRADED` on `onPersonaSaved`/`ensureCompanion` failure; self-heals on the next successful sync call (they run per-turn/per-save already — natural retry). Bootstrap button remains the manual repair. |
| **Contradiction flags** | See §7. |
| **Interrupted rebuild** | Covered by the sweeper (above): derived queue + end-of-drain meta stamp. Additionally the rebuild button writes a `rebuild_in_progress` marker in the health store at start and clears it at end; found set at startup → count it (`rebuilds_interrupted`) and let the sweeper resume. |
| **Process death during repair** | The sweeper holds no state worth losing (derived queue); the startup reconciler clears any stale `sweep_in_progress` marker → level `AWAITING_RETRY` → the startup trigger re-runs it. Counter ticks since last flush may be lost (accepted, §4.1). |
| **App restart while degraded** | Startup reconciler (new, first thing after RenameJournal in the housekeeping thread): load the store; for each active episode re-evaluate cheaply what can be re-evaluated without opening the DB (model installed? key readable? — via `isProvisioned` + catalog checks); leave the rest active until their component's own next success/failure signal. No probing that would provision or unlock anything. |
| **Repeated failure across sessions** | firstSeen/occurrences/lifetime aggregates persist; a category configurable threshold (e.g. `capture_failed` occurrences ≥ 3, or any episode active > 7 days) sets an `escalated` bit — consumed by Phase 2 for visibility, produces **no** additional logging. |

Loop safety: no recovery path self-schedules on failure. Triggers are
discrete (startup, model-success, manual). Attempt budgets are monotonic
counters in durable storage, so a crash loop cannot reset them.

---

## 6. Reindex queue design (summary of the load-bearing choice)

**No new queue table.** "Awaiting reindex" ≡ `status='active'` memory with
no `embeddings` row for the active model tag — the condition the store
already expresses (`countMissingEmbeddings`, MemoryStore.kt:2962-2968) and
that every mutation path already maintains correctly (insert: no vector;
edit: vector dropped; archive: vector dropped; import: no vectors; reset:
table emptied). New store method: `missingEmbeddingIds(tag, limit)`.
Park-list (per-memory failed attempts) and in-progress markers live in the
health store, ids only. Orphan-cleanup rule extended: any future pruning
must not treat a vectorless active memory as garbage — it is repair
inventory — and must still honor `RenameJournal.hasPending` first.

---

## 7. Contradiction-flag lifecycle (write-only no more)

Today: appended by Enforcer to meta `enforcer.contradiction_flags`, cap 50
silently dropping oldest, read by nothing, surviving reset.

Designed lifecycle:

1. **Created** — unchanged (enforcer near-dup suppression), plus counter
   `contradictions_created`; overflow past the cap increments
   `contradictions_dropped_overflow` (no longer silent).
2. **Retired automatically (stale)** — a retirement pass, run at the start
   of every Archivist run and once per startup housekeeping: a flag is
   stale when its `memory_id` no longer exists or is no longer active, or
   its lore label no longer matches any entry in any lorebook. Stale flags
   are removed; counter `contradictions_resolved`.
3. **Pending** — everything else; `contradictions_pending` is a derived
   count, mirrored into the CONTRADICTIONS component (HEALTHY-with-data;
   pending contradictions are information, not a failure).
4. **Consumed** — Phase 1 scope: the pending list becomes available as
   structured data (`MemoryStore.getContradictionFlags()`), included in the
   Archivist run's stored stats so the run history can say "N memory/lore
   disagreements were active during this run," and visible in the existing
   debug surfaces. **User-facing review/resolution UI is Phase 2** (owner
   wording/placement) — nothing auto-edits or auto-archives the memories
   involved (owner law: the user decides).
5. **Reset** — `resetAllMemoryData` starts clearing this meta key (the
   flags describe rows the reset just deleted; keeping them is corruption,
   not history — the health counters retain the lifetime totals).
6. The stale Enforcer comment ("Phase 6 reads and clears") is corrected.

---

## 8. Counters and RunOutcome design

### 8.1 Counters (health store; long values; throttled flush)

All are events the current code can already identify at a single call site:

| Counter | Hook |
|---|---|
| `captures_attempted` | TranscriptRecorder.recordTurn after skip checks, before `appendTranscriptTurn` |
| `captures_succeeded` | after successful append |
| `captures_failed` | the catch (A1) + ChatActivity prep catch (A2) |
| `captures_skipped_blank / _unprovisioned / _excluded / _participation` | the existing skip branches (A3) |
| `captures_incomplete_reply` | `assistantComplete == false` at capture (Round 3 visibility) |
| `embeds_attempted / _succeeded / _failed` | Librarian: `embed()` call sites (search query, reindexMemory, rebuild loop, embedOrNull) |
| `memories_awaiting_reindex` | derived gauge (not a counter): `countMissingEmbeddings(activeTag)` snapshot, refreshed by the sweeper and the status surfaces |
| `retrieval_semantic / retrieval_keyword / retrieval_failed` | Librarian.search: which path returned |
| `archivist_runs_attempted / _succeeded / _failed / _interrupted` | Archivist.run outcome mapping |
| `suggestions_proposed` | `parsed.memories.size` summed per run |
| `suggestions_dropped_floor` | `belowFloor` (Archivist.kt:273-285) |
| `suggestions_dropped_cap` | the cap branch (Archivist.kt:275-281) |
| `suggestions_dropped_validation` | `parsed.dropped` |
| `suggestions_dropped_incomplete` | assistant turns skipped by `renderTurns` (plumbed return, §11) |
| `suggestions_skipped_duplicate / _rejected` | existing branches in fileMemoryDrafts |
| `sync_failures` | MemoryCompanionSync catches |
| `contradictions_created / _resolved / _dropped_overflow` | §7 (pending is derived) |
| `exports_succeeded / _failed` | MemoryExporter both paths |

Nothing else — explicitly no invented analytics (no timings, no
success-rate synthesis in the backend; ratios are Phase 2 display math).

### 8.2 RunOutcome / run-row extension

`Archivist.RunOutcome` gains: `suggestionsProposed`, `droppedBelowFloor`,
`droppedByCap`, `droppedByValidation`, `droppedIncompleteTurns`,
`contradictionsPendingAtRun`. Persisted on `archivist_runs` as one additive
nullable column `filter_stats_json` (**DB v15**; absent = old run, display
as "not recorded"). This is what lets the Memory Assistant answer "were
suggestions discarded by my importance floor?" for past runs, not just the
one still on screen.

---

## 9. Startup ordering (revised housekeeping thread)

Single background thread, same as today, strictly ordered:

1. `RenameJournal.reconcile` (unchanged, always first).
2. **Health store load + reconciler** (§5 "app restart while degraded";
   clears stale in-progress markers; no DB access).
3. `isProvisioned()` gate — everything below skipped if false (unchanged).
4. `integrityCheck()` — failure now: MEMORY_DB → `USER_ACTION_REQUIRED` /
   `integrity_failed` (dataLoss=POSSIBLE), **ungated** health line; skip
   steps 5–8 (as today). The startup Toast is retired in Phase 2 (its
   replacement wording needs owner approval; until then the Toast stays —
   no silent removal of the only current signal).
5. Backfill: flag set **only when the backfill actually completed**
   (`backfillExistingChats` gains a success/failure return distinct from
   "0 chats needed"); failure → counter + retry next start.
6. Contradiction retirement pass (§7, cheap).
7. **ReindexSweeper** startup trigger (§5/§6, budgeted).
8. `autoExportIfDue` — failures feed EXPORT_BACKUP (`AWAITING_RETRY`;
   escalates to an ungated line on the 3rd consecutive failed start).

Nothing in the list provisions the store, opens dialogs, or touches the
main thread (except the pre-existing Toast noted in step 4).

---

## 10. Export / backup / reset / rebuild interactions

- **Export/import:** health state, counters, park lists, and run filter
  stats are **device-local operational data — never exported** (same
  precedent as embeddings and `archivist_runs`). Imported memories arrive
  vectorless and are picked up by the derived reindex queue automatically;
  the import report can (Phase 2) say "N memories will be indexed in the
  background."
- **Reset memories:** the store's tables empty (unchanged, incl. the new
  behavior of clearing contradiction flags §7). The derived reindex queue
  empties itself by derivation. **Health history and lifetime counters
  survive** — a reset must not destroy the evidence of a recurring problem
  (hard requirement); the reset itself is recorded as an event line +
  `resets_performed` counter. Active episodes that describe now-deleted
  data (MEMORY_REINDEX, CONTRADICTIONS) are closed as `RECOVERED
  (by reset)`. (Owner confirmation §14.4.)
- **Rebuild index:** unchanged UX; adds the in-progress marker, per-item
  failure counting, park-counter reset, and the sweeper's end-of-drain meta
  stamp so an interrupted rebuild self-completes.
- **Backup-before-reset:** unchanged (Round 1 gating stays authoritative);
  its failure now also counts as `exports_failed`.

---

## 11. Round 3 (incomplete-message) interactions

- Capture already carries `complete:false` per assistant turn; the health
  layer adds only the `captures_incomplete_reply` counter. An incomplete
  reply is a **successful** capture (Round 3's design), never a failure.
- `ArchivistPrompt.renderTurns` remains the single render gate; it gains a
  dropped-turn count returned to the runner (`suggestions_dropped_incomplete`
  feeds §8) — no behavioral change to what the model sees on the normal
  path.
- **Bypass found and must be closed (behavior change, flagged §14.7):** the
  malformed-JSON fallback in `renderTurns` (ArchivistPrompt.kt:151-155)
  passes raw stored JSON to the model — incomplete fragments included,
  `complete:false` markers reduced to inert text. Proposed: on parse
  failure, do **not** ship raw JSON; count the transcript as corrupt
  (`captures_corrupt` + TRANSCRIPT_CAPTURE episode, dataLoss=POSSIBLE) and
  skip it, leaving it pending so a future repair can look at it. The
  current "the model can still read it" behavior trades Round 3's guarantee
  for recall; the owner should choose.
- Retry/rerun paths (Archivist rerun, sweep, rebuild) never re-render
  transcripts through any other code path, so the gate holds everywhere
  once the fallback is closed. `rejected_drafts` and text-dedup are
  unaffected.

---

## 12. Test matrix (all pure-JVM, `app/src/test`, CI-gated)

| Area | Cases |
|---|---|
| Health transitions | same-category repeat bumps count without transition; category change transitions; escalation (TEMP→USER_ACTION, dataLoss POSSIBLE→CONFIRMED) one-way per episode; recovery closes + appends history; history cap keeps lifetime aggregates; UNKNOWN level/category round-trips untouched |
| Store serialization | JSON round-trip of every field; unknown component key preserved; corrupted blob → fresh state + `health_store_reset` counter, never a crash |
| Log throttling | transition-only emission; 6-hour per-(component,category) backstop across simulated restarts; occurrence storms produce zero extra lines |
| Counters | increment/flush threshold logic; flush-on-transition; loss window bounded to unflushed ticks |
| Sweep planner (pure: ids + attempts + budget in → plan out) | chunking; park at attempt budget; park reset on rebuild/model-change; empty-queue → meta-stamp decision; tag-mismatch → no sweep, USER_ACTION |
| Contradiction retirement (pure: flags + live ids/labels in → keep/retire out) | memory deleted/archived → retire; lore label gone → retire; live pair → keep; overflow counting |
| RunOutcome stats | floor/cap/validation/incomplete counts mapped and JSON-persisted; absent `filter_stats_json` on legacy rows → "not recorded", not zeros |
| renderTurns | dropped-incomplete count correct; complete/legacy turns untouched; malformed JSON → skip+corrupt signal (per §14.7 decision), never raw passthrough |
| Backfill flag | internal failure → flag NOT set; zero-eligible success → flag set |
| Reset semantics | which health keys survive vs close; contradiction meta cleared |
| Capture classification | each skip reason → its own counter, never `captures_failed`; append throw → failed + episode |

Existing tests untouched: `PerChatSettingKeysTest`, codec round-trip,
teardown planner, parser, matcher suites.

---

## 13. Exact files likely to change (implementation phase, after approval)

New:
- `preferences/memory/health/HealthModel.kt` (levels, components,
  categories, ComponentHealth — pure)
- `preferences/memory/health/MemoryHealthStore.kt` (prefs persistence)
- `preferences/memory/health/MemoryHealth.kt` (facade: report/recover/
  count, transition+throttle logic — logic pure & tested)
- `preferences/memory/health/ReindexSweeper.kt` (+ pure `SweepPlanner`)
- tests: `HealthModelTest`, `HealthStoreCodecTest`, `SweepPlannerTest`,
  `ContradictionRetirementTest`, `RenderTurnsGateTest`, extensions to
  existing Archivist parser/runner tests

Modified:
- `preferences/memory/MemoryLog.kt` — add `health()` (owner-gated decision)
- `preferences/memory/librarian/Librarian.kt` — hooks: ensureModel
  success/failure, self-check, search path outcome, reindexMemory no-op →
  health signal, rebuild markers
- `preferences/memory/MemoryStore.kt` — `missingEmbeddingIds`,
  `getContradictionFlags`/retirement, contradiction-meta clear in reset,
  DB v15 (`archivist_runs.filter_stats_json`, additive + fresh-install)
- `preferences/memory/TranscriptRecorder.kt` — counters, episode reports,
  backfill success signaling
- `preferences/memory/archivist/Archivist.kt` — RunOutcome fields, stats
  persistence, retirement-pass call, counters
- `preferences/memory/archivist/ArchivistPrompt.kt` — dropped count,
  malformed-JSON policy (§14.7)
- `preferences/memory/MemoryCompanionSync.kt` — sync failure/success hooks
- `preferences/memory/MemoryExporter.kt` — export counters/episodes
- `preferences/memory/enforcer/Enforcer.kt` — assembly-failure hook via
  caller, contradiction counters, stale comment fix
- `app/MainApplication.kt` — startup ordering §9
- `ui/activities/ChatActivity.kt` — enforcer/capture catch hooks (the
  Toast in `notifyMemoryDegradedOnce` and its once-flag scope are Phase 2)
- `CLAUDE.md` + this folder's docs — onboarding updates

Explicitly **not** changed in Phase 1 implementation: any layout, string,
or screen (Phase 2, owner-approved wording only); `SecurePrefs`;
`DatabaseKeys`; the Round 1–3 mechanisms themselves.

---

## 14. Risks and unresolved owner decisions

1. **Ungated health lines in the Memory log.** `MemoryLog.logAlways` is
   reserved by owner ruling for Archivist run records. The design needs the
   owner to extend that ruling to health *transitions* (begin / worsen /
   recover, throttled). Without it, everything still works but genuine
   failures stay invisible with diagnostics off — the core problem.
2. **Two banned-pattern Toasts already flagged in Phase 7** (enforcer
   degradation notice; startup integrity failure) plus several pre-ban
   Toasts on the memory settings screens (import/export/bootstrap/model
   download results). Their replacements are wording decisions → Phase 2.
   Phase 1 keeps them untouched.
3. **Health store encryption.** Proposed plain (non-encrypted) prefs so
   health survives Keystore loss; contents are ids/counters/enum keys only.
   Owner may prefer encrypted-anyway (accepting that a Keystore failure
   then also blinds the health system).
4. **Reset semantics.** Proposed: reset wipes memory data but *keeps*
   health history/counters (recording the reset). Confirm that keeping
   diagnostic history through a reset matches the owner's intent for
   "reset means empty."
5. **Capture-loss repair.** Failed captures are confirmed losses from the
   archive, but the chat still holds the text; a per-chat "re-archive"
   action could recover them. Backend-feasible; needs owner interest
   before design (and its surface is Phase 2 regardless).
6. **Model switch = manual rebuild** stays manual (heavy, battery/time
   cost). Owner may prefer automatic full re-embed; not assumed.
7. **Malformed-transcript fallback** (§11): closing the Round-3 bypass
   changes behavior (a corrupt transcript is skipped rather than fed raw to
   the model). Recommended, but it is a recall-vs-integrity trade the owner
   should rule on.
8. **Automatic background embedding writes** (the sweeper) are derived
   index maintenance, not memory content — judged compatible with the
   "no automatic process writes cards/entries/tags mid-conversation" law,
   but stated here so the owner can veto the interpretation.
9. **DB v15** (one additive nullable column on `archivist_runs`) — routine,
   but it is a schema bump and is listed per the migration rules.
10. **Escalation thresholds** (when an episode flips the `escalated` bit:
    proposed ≥3 occurrences for capture failures, >7 days active for
    fallback states) are tunable constants; Phase 2 decides what
    escalation *shows*, the owner may also tune when.

---

*End of Phase 1 design. No implementation, no UI, no strings. Phase 2
(user-facing states, wording, placement) begins only on the owner's
explicit approval of this design.*
