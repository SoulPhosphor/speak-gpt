# Round 5, Phase 1 — Memory Health, Silent Degradation, and Recovery (DESIGN ONLY)

> **STATUS: DRAFT, BROUGHT CURRENT July 12 2026 (evening). AWAITING OWNER
> APPROVAL — nothing here is built.**
>
> **Round 4 is now BUILT AND MERGED** (merge `e2f8289`, "chat storage locks
> instead of masquerading as empty"). The earlier caveat that Round 4 "has
> not been designed" and that persistent health storage was "blocked by
> Round 4" is now RESOLVED: chat storage has an explicit LOCKED/HEALTHY/
> LEGACY_PLAINTEXT/FRESH_UNENCRYPTED state machine (`ChatStorageHealth`,
> `SecurePrefs`, `OutageReconciler`, `SnapshotRegistry`), a blocking
> full-screen `ChatStorageLockedActivity`, and the owner lock policy
> (LOCKED means BLOCKED — never a plaintext fallback). This document's
> proposal to hold memory-health state in a **plain, unencrypted**
> `memory_health` prefs file is now consistent with Round 4's own choice to
> keep the `storage_health` journal raw so it works while the Keystore is
> down — the two health stores are siblings.
>
> Still true: **nothing in THIS document (the memory-health system) is
> built.** Phase 2 (user-facing wording/placement) must not begin until the
> owner approves the design. Unreadable or unavailable storage must never be
> interpreted as empty or automatically reinitialized.
>
> **Owner directions added July 12 2026 (evening) live in the new §15** —
> the mandatory database-issue dialog, the repair flow, force-close-on-
> decline, the crash-triggered check, and the verified backup reality. §15
> is the current owner intent and outranks anything earlier in this document
> where they disagree. Wording is still not written; §15 records behavior
> and choices, not final copy.

**Status: DESIGN AWAITING OWNER APPROVAL. Nothing in this document is built.
Phase 2 (user-facing wording/placement) has not begun and must not begin
until the owner approves this design.**

Original draft date: July 12 2026 (early), branch
`claude/phosphor-memory-health-audit-hjw6ow`. Audited against the then-current
`main` (through the Round 3 merge, a34fa7f). Brought current July 12 2026
(evening) against `main` through the Round 4 merge (`e2f8289`). Every
original claim was verified by reading the code at a34fa7f; the §15 backup
facts were verified against `MemoryExporter.kt` at the Round 4 tree.

Scope guard: Round 4 (Keystore loss / store-lock recovery) is now BUILT and
is referenced where the health system must *represent* that state. Rounds
1–3 machinery (rename transaction, RenameJournal, capture recovery, message
completion states) is treated as fixed.

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
   steps 5–8 (as today). **Owner direction July 12 2026 (§15): the vanishing
   Toast is replaced by a MANDATORY blocking dialog the user must dismiss,
   with a Repair path.** The integrity check itself should run on a
   crash-triggered basis (§15.3), not unconditionally every launch — the
   background thread first checks the already-computed last-exit reason and
   only runs the PRAGMA when the previous exit was abnormal or a repair is
   pending. See §15 for the full flow.
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

## 15. Owner directions — July 12 2026 (evening)

These are the owner's current instructions, recorded during the walkthrough.
They set behavior and choices; final wording is still not written. Where they
conflict with earlier sections, §15 wins.

### 15.1 Verified backup reality (facts, not proposal)

Read from the current code so the plan starts from truth:

- **This app has TWO SQLite databases**, both SQLCipher-encrypted:
  1. `companion_memory.db` — the memory system (companions, memories,
     transcripts, roleplay cards, embeddings, run history).
  2. `lorebook.db` — the lorebook tier (books, entries, triggers).
  - Chats and settings are **not** SQLite — they live in
    EncryptedSharedPreferences files (the Round 4 storage-lock machinery
    covers those separately).
- **Automatic backup exists, but only for ONE of the two databases.**
  `MemoryExporter.autoExportIfDue` runs once per app start on the startup
  background thread, throttled to at most once per 24 h. It writes a
  rotating JSON export (keep the newest **5**; older ones deleted) into
  `getExternalFilesDir("memory_backups")`. That export contains
  `companion_memory.db`'s contents **plus the app's chats** (carried along
  under `app_chats`). Writes are atomic + verified (torn file can't become
  the newest backup).
- **`lorebook.db` is NOT backed up by anything, automatically or on a
  schedule.** It has no integrity check either. (Only `companion_memory.db`
  has `integrityCheck()`.) This is a gap — see §15.5.
- **Embeddings are never exported** (they are regenerated by re-indexing).
- **The automatic backup is SILENT.** There is no user-facing signal that it
  ran, succeeded, or failed — successes and failures go only to the Memory
  log, and that log is gated off by default. The only visible hint is the
  "last backup" time on the Memory Controls screen going stale.
- Backup **pauses** while chat storage is locked/degraded (Round 4), so a
  backup written during an outage can't overwrite the last complete one.
- **Restore is manual only** (SAF file-picker Import). Nothing restores
  automatically today.

Performance note: the auto-backup is *not* an every-launch cost (24 h
throttle), but when it does run it serializes the whole memory DB + all
chats to JSON on the startup background thread — it competes for disk with
the other startup work but does not block the UI thread.

> **SUPERSEDED (owner July 15 2026):** the single-combined-file design
> described above is being REPLACED by separate per-type backup files (memory
> / lorebook / chats), each written, verified, rotated, and restored
> independently. See **§15.13** — it is authoritative for the backup design
> from here on.

### 15.2 Database-issue detection → repair flow (owner-directed, RESOLVED)

Applies to **both** SQLite databases (`companion_memory.db` and
`lorebook.db` — both are SQLite; "the sqlite thing" and "the lorebook" are
the two of them). The framework is shared; the user-facing effect names
whichever database is affected (memory features vs lorebooks).

Replaces the vanishing Toast (F3). When a database integrity problem is
detected:

1. **A blocking dialog the user must actively dismiss** appears (not a Toast,
   not a snackbar — a real dialog). It states, in plain words to be written
   later: which database has a problem, what the app is going to try, and
   what the consequences are.
2. **The app tries to REPAIR first, automatically** (owner order, Flag 2):
   attempt an in-place salvage/rebuild of the corrupt database from its
   readable pages. If that succeeds, the user is told it was repaired.
3. **If repair is not possible, the app offers to REPLACE the database with
   the last good backup** — and the dialog must spell out the complications
   *before* the user agrees: specifically what that restore would cost (e.g.
   "memories added since [backup date] would be lost," or for lorebooks
   "lorebook edits since [date] would be lost"). The user chooses whether to
   accept the restore.
4. **During any repair or restore, the user is clearly warned NOT to close
   the app** — interrupting a mid-write could worsen corruption. (The repair
   itself must still be built restartable/idempotent so an accidental kill or
   an Android kill is survivable — §5 common laws.)
5. **The dialog must distinguish repairable / restorable / not recoverable on
   this device.** It must never promise a repair it cannot deliver. The
   not-recoverable path is §15.6.

**If the user declines all repair/restore → DEGRADED MODE, not force-close
(owner decision — §15.2a).** This REVERSES the earlier force-close direction.

### 15.2a Degraded mode — memory/lorebooks disabled until repaired (RESOLVED)

When a database problem exists and the user has not repaired it, the app does
**not** shut down. Chatting still works (chat storage is separate). But the
affected feature is turned **fully off** — no reads and no writes to the
corrupt database — until it is repaired. (Reading a corrupt SQLite file is
itself unsafe, so "disabled" means genuinely off, not just read-only.)

**Persists across restarts (B10, owner July 15 2026: yes).** The disabled
state is STORED and survives closing/reopening the app: a damaged database
stays off — banner shown — until a repair or restore actually succeeds.
Reopening never silently re-uses a corrupt database. Because a mid-session
failure is not a crash, this is a stored "disabled pending repair" flag,
independent of the crash-triggered check (§15.3) — it does not rely on the
next launch happening to re-run a check.

While in degraded mode:

- **Every new chat shows a persistent, dismissible notice at the top**
  stating that memory (or lorebooks) is currently disabled because of
  database corruption, with two actions: **Repair** and **OK**. It is a
  persistent banner the user dismisses — NOT a vanishing snackbar/Toast
  (this honors the app-wide no-Toast rule; the owner's "snack bar" is
  implemented as a persistent acknowledged banner). It reappears on each new
  chat so the user keeps being reminded and must re-acknowledge — they can't
  forget memory is off, and they can't make it stick around forever either.
- **The Archivist / "Analyze Conversations" action is DISABLED and not
  clickable** whenever there is *any* database problem — the owner rule is
  the Archivist must never run against a bad database (it writes to it). The
  disabled control carries an inline note saying why and pointing to the
  repair/replace choice. (This is stronger than the normal not-ready state:
  it is a hard block tied to DB health.)
- Rationale (owner): if memory isn't being used, the user must KNOW; and if
  the only loss is that new memories can't be made, the button that makes
  them must be visibly, explicitly blocked with the reason — never silently
  inert.

Open sub-point: the banner names **Memory**, **Lorebooks**, or **both**
depending on which database(s) failed (they are separate features on separate
databases; both can fail at once). Final wording is Phase 2.

### 15.2b Restore-from-backup is a confirmed, verified action (owner Q, July 15 2026)

Owner asked whether the restore-from-backup dialog acts as a "secondary
check" when they tap **Revert to Last Good Database**. Yes — restoring
overwrites the current database, so it is a deliberate two-part safeguard, not
an instant action:

1. **Verify first (behind the scenes).** When Revert is tapped, the app picks
   the newest backup and CHECKS that the backup itself is actually good before
   trusting it (§15.6 step 3). If the newest backup is also damaged, it walks
   to the next-older one until one passes. This prevents "restoring" from one
   corrupt file onto another.
2. **Confirm second (the secondary check the owner means).** Before it
   overwrites anything, the app shows a **confirmation dialog** stating which
   backup date it will restore and what will be lost (memories / lorebook
   entries added since that date), and that the damaged database is kept aside
   (§15.6). Only on confirm does the restore run. This is **A5** — its wording
   is not yet approved. It also satisfies the app's standing rule that
   destructive actions always get a confirm dialog.

So: tapping Revert → (verify the backup is good) → **A5 confirmation** →
restore. A5 is exactly that secondary check.

### 15.2c Mid-conversation detection — audio warning + existing banner (B9 RESOLVED, owner July 15 2026)

If a memory or lorebook database failure is detected **mid-conversation**:

1. **Immediately play a distinct audio warning.** Its purpose is to notify
   hands-free users who may not be looking at the screen. "Distinct" = not
   confusable with the existing chimes (error / done / no-speech two-tone).
2. **Then show the already-approved persistent top banner (§15.2a / A2)** —
   unchanged.

Hard constraints (owner):
- **Do NOT add a new dialog** for the mid-conversation case.
- **Do NOT redesign the existing warning behavior.** The banner is exactly
  §15.2a / A2: stays visible until dismissed; `Repair` and `OK` buttons;
  reappears in each new chat while the affected database stays disabled; names
  **Memory**, **Lorebooks**, or **both** depending on what failed.

This is the mid-session path only; the startup behavior in §15.2 is unchanged.

**Scope (RESOLVED, owner July 16 2026): hands-free sessions only.** The audio
warning plays only when the user is in a hands-free/voice session. A typed
session with the screen visible does not play it — the banner alone is enough
when the user is already looking at the screen.

### 15.3 Crash-triggered checking (owner-directed)

The integrity check should **not** run on every launch (that is part of why
startup is slow — see the separate startup-performance discussion). Instead:

- The app already computes, at every startup, **why the previous process
  died** (`Logger.logLastExitReason` → Android's historical exit reasons:
  clean exit vs crash vs low-memory kill vs ANR vs force-stop). This signal
  exists today and costs nothing new.
- The startup background thread runs the database integrity check **only
  when** the last exit looked abnormal (crash / ANR / kill), **or** when a
  repair was left pending from a previous session. On a clean previous exit,
  the check is skipped — the common case, and the fast path.
- A **manual "Check database now"** action (like a Windows manual disk check)
  is available for when the user suspects a problem without a crash having
  happened. Placement/wording is Phase 2.

This directly ties the health work to the startup-speed goal: the expensive
check becomes occasional-and-justified instead of every-single-open.

### 15.4 One-time vs ongoing (carried from the startup discussion)

Recorded so the implementation keeps them separate:

- **One-time past-event chores** (encrypting leftover plaintext files, the
  Round 4 outage reconcile) should run once, record "done," and never run
  again — not re-scan every launch.
- **Ongoing risk** (corruption) is the only thing that justifies a repeated
  check, and even that is now crash-triggered (§15.3), not every launch.

### 15.5 Lorebook backup + integrity — DECIDED: extend coverage (owner: "Absolutely")

- `lorebook.db` today has **no automatic backup and no integrity check**.
- **Decision: fold `lorebook.db` into the same protection** as
  `companion_memory.db` — include it in the automatic rotating backup, and
  give it an integrity check on the same crash-triggered schedule (§15.3).
  Low-risk: the backup + rotation + verify machinery already exists; this
  adds a second database to it.
- Consequence: the §15.2 repair/replace flow and §15.2a degraded mode apply
  to lorebooks too (a corrupt lorebook DB disables lorebooks, names them in
  the banner, and can be restored from the same backup set).

### 15.6 Not-recoverable handling — the professional pattern (RESOLVED)

Owner asked "what is the professional way of handling this?" The professional
pattern, and the owner's instinct, agree. When a database is corrupt:

1. **Never delete the corrupt file.** Move it aside, renamed with a date and a
   clear "corrupt" marker (e.g. `companion_memory.corrupt-2026-07-12.db`),
   into a findable recovery folder (parallel to Round 4's
   `files/storage_recovery/`). A user who wants to attempt manual rescue can
   find it; everyone else can ignore it.
2. **Try repair first** (§15.2 step 2).
3. **If repair fails, walk the backups newest-to-oldest**, verifying each
   before trusting it — if the newest backup is *also* bad (corruption can be
   captured into a backup), fall back to the next older one, and so on, until
   one passes an integrity check. That verified backup is the restore source
   (with the loss warning of §15.2 step 3, dated to that backup).
4. **Only if NO backup is usable, start a fresh empty database** so the app is
   functional again — but the renamed corrupt file from step 1 stays
   preserved. This is the honest last resort: most users do need a working
   (fresh) instance to keep going, and the old data is not destroyed, just
   set aside. The app must *say* it started fresh and that the old data was
   preserved — never present a fresh empty database as if nothing happened.

This is exactly the industry pattern: quarantine the bad file, restore from
the newest *verified* backup, reinitialize only as a last resort, and never
silently discard.

### 15.7 Backup-failure surfacing — 3-strikes dialog (RESOLVED)

The auto-backup is silent today; a backup failing repeatedly is invisible.

- **After 3 consecutive failed automatic backups, show a blocking dialog**
  (persistent, must be dismissed — not a Toast). Three strikes filters out a
  one-off transient glitch and only speaks up when something is genuinely
  wrong.
- **What the user can actually do** (the owner's open question — "I don't know
  what I would do"): a repeatedly failing backup almost always means the
  device is **out of storage space**, or the backup folder isn't writable.
  The dialog says the likely cause in plain words and gives a concrete
  escape hatch instead of a dead-end warning.
- **HISTORICAL, DO NOT IMPLEMENT (superseded July 20 2026):** the original
  escape hatch here was a "Save a backup somewhere else now" action running
  the manual export. The ACTIVE design is the category-split dialog
  (`Change Backup Folder | Retry | Cancel`, folder path as selectable text
  — §15.12 A4 supersession + build plan Rev 2 Round 3 item 8): changing the
  backup folder IS the escape hatch, and source-type failures are checked
  and routed by store type instead of being treated as storage problems.

### 15.8 Backup cap — already exists; why more than one (RESOLVED)

- **There is already a cap: the newest 5 backups are kept, older ones deleted
  automatically** (`ROTATION_KEEP = 5`). Backups do not pile up to a hundred —
  the owner's requirement is already met by existing code; this just makes it
  an explicit, named policy.
- **Why keep more than one** (the owner asked): because corruption can be
  silently copied *into* a backup. If only the single newest backup were kept
  and it captured already-corrupt data, there would be nothing clean to fall
  back to. Keeping ~5 days of history means §15.6 step 3 can walk back to an
  older, still-clean backup. That is the whole reason a rotation exists rather
  than a single overwrite. Five is a reasonable default; it is a tunable
  constant if the owner later wants more or fewer.

### 15.9 Memory Controls screen — layout + exact wording (owner-directed, VERBATIM)

**Date format (owner requirement):** every user-facing date in this feature is
shown as **`Month D, YYYY`** (e.g. `July 5, 2026`) — full month name, day,
comma, four-digit year. This governs every date line below. (Internal
*filenames*, such as the quarantined corrupt-DB copy in §15.6, keep a sortable
date for correct ordering and are not user-facing display text.)

**CASING RULE (style guide — owner, July 15 2026, CORRECTED):** dialog/screen
**TITLES** and **BUTTON LABELS** are **Title Case** — capitalize the first
letter of each major word (minor words like "to", "in", "the" stay lowercase),
NOT full uppercase. Body text, status text, banner sentences, and helper/notes
stay **sentence case**. (An earlier version of this file used full ALL CAPS —
that was wrong and is corrected here.)

The following strings are **owner-approved verbatim** — do not reword them
without asking.

> **PLACEMENT SUPERSEDED (July 18–20 2026):** these controls no longer land on
> Memory Controls. The **Memory Backup & Restore** screen now EXISTS on `main`
> (`MemoryBackupRestoreActivity`, a Memory Manager hub row, built July 18 by
> the menu-reorg effort — it already holds the auto-backup toggle,
> import/export, last-backup status, and Reset). Per §15.14 these controls go
> THERE, in its Backups area. The one relative-order rule that remains
> active is: `Check Database Integrity` sits ABOVE `Create Backup`. (The
> old failed-line-above-success-line rule is HISTORICAL, DO NOT IMPLEMENT —
> replaced by the compact one-row-per-type status display, item 3 below.)

**Top-to-bottom order and exact text:**

1. **`Check Database Integrity`** — a button (Title Case). It sits **ABOVE**
   the `Create Backup` button.
   - When pressed, the text beneath the button reads, VERBATIM:
     **`Checking database integrity. Do not close your app. Please wait.`**
   - When the check finishes, that text is **REPLACED** by the result:
     **`Database Check Passed`** or **`Database Check Failed`**.
     (Naming unified to "Database Check" per owner, July 15 2026 — this
     supersedes the earlier "Database Integrity Passed/Failed".)
   - On a failed result, the app offers the §15.2 recovery buttons:
     **`Repair`**, **`Revert to Last Good Database`** (restore from the newest
     verified backup, with the §15.2 loss warning + confirmation — see §15.2b).
     On a passed result, no action is offered.

2. **`Create Backup`** — a button (Title Case), **BELOW** the integrity
   button. (RENAMED by owner, July 20 2026 — was "Create Database Backup";
   the operation also backs up chats, and the owner chose the short form
   `Create Backup`.) Pressing it runs a recovery-backup run immediately.
   Status text appears underneath it: in-progress, then whether it
   completed or failed.

3. **Backup status — COMPACT PER-TYPE ROWS (owner-approved, July 20 2026;
   supersedes both the single lines and the two-line failed-above-success
   layout).** One row per type: current result + last successful date/time
   when relevant, e.g.
   > `Memory: Backup failed today. Last good backup: July 19, 2026, 2:30 PM`
   > `Lorebooks: Backed up July 20, 2026, 2:30 PM`
   > `Chats: Backed up July 20, 2026, 2:31 PM`
   > `Profile image catalog: Backed up July 20, 2026, 2:31 PM`
   Dates `Month D, YYYY`, time 12-hour AM/PM. See §15.13 and the build plan
   (Rev 2) for the full backup architecture.

All of the above are persistent on-screen controls/text — never Toasts,
consistent with the app-wide no-Toast rule.

### 15.10 Decision ledger — resolved vs still open

> **READ FIRST — later rulings supersede entries below (July 20 2026).**
> The two paragraphs after this note record rulings AS MADE on July 15 and
> are kept as history; where they conflict with the July 20 rulings, the
> July 20 versions are the ACTIVE design (full detail:
> `database_health_build_plan.md` Revision 2). Specifically — HISTORICAL,
> DO NOT IMPLEMENT: the failed-line-above-success-line status layout and
> the separate success/failure status lines (→ compact one-row-per-type
> display, §15.9 item 3); the `Create Database Backup` button name (→
> `Create Backup`); the `Database Integrity Passed/Failed` result strings
> (→ `Database Check Passed/Failed`); the five-button A4 dialog, its
> inline integrity-check flow, and its `Save Back Up in New Location`
> action (→ the category-split dialog: `Change Backup Folder | Retry |
> Cancel`, source failures checked then routed by store type); a fixed
> `App Backups/` folder as the primary destination (→ user-selected SAF
> folder, app-private fallback until selected); any reading of "daily" as
> guaranteed scheduling (→ startup/foreground-triggered with a 24-hour
> throttle).

**RESOLVED (owner-directed, do not re-open without the owner):**
degraded mode not force-close (§15.2a); repair-then-replace order (§15.2);
not-recoverable handling — quarantine/rename, walk backups newest-to-oldest,
fresh only as last resort (§15.6); lorebook folded into backup + integrity
(§15.5); 3-strikes backup-failure dialog with a "save elsewhere" escape
(§15.7); backup cap = newest 5 (§15.8); daily frequency (§15.1); Memory
Controls layout with the `Check Database Integrity` button ABOVE the
`Create Database Backup` button (§15.9); the manual-check verbatim strings
(`Checking database integrity. Do not close your app. Please wait.` →
`Database Integrity Passed` / `Database Integrity Failed`) (§15.9); the two
backup status lines and their order — failed ABOVE last-success (§15.9);
date display format `Month D, YYYY` (§15.9); Archivist hard-disabled on any
DB problem (§15.2a); banner is persistent + re-acknowledged per new chat with
Repair / OK (§15.2a).

**APPROVED wording (owner, July 15 2026 — recorded verbatim in §15.12):**
A1 automatic dialog (both variants), A2 degraded-mode banner, A3
blocked-Analyze note + working buttons, A4 repeated-backup-failure dialog
(now a two-stage dialog with an inline integrity check that escalates to the
repair flow on failure). **Resolved this round:** casing is Title Case not
ALL CAPS; the revert button is `Revert to Last Good Database` ("Save"
dropped); the loss noun follows the database ("recent memories" / "recent
lorebook entries"); A1 now says the broken database is "unavailable to use or
save" so a corrupt DB can't be auto-backed-up over good backups; A4's
non-storage path is answered (inline integrity check → repair flow).

**STILL OPEN — final wording not yet written (Phase 2 owner copy):**
5. ~~restore loss-warning~~ — **A5 APPROVED** (§15.12), "Backup" capitalized.
6. ~~(A6) started-fresh message~~ — **A6 APPROVED** (§15.12): title
   `Database Recovery Failed`, body + preserved file path, buttons
   `Open File Location` / `OK`.
7. ~~(A7) button-label standardization~~ — **A7 RESOLVED (owner: yes):**
   `Revert to Last Good Database` is THE label everywhere; the earlier
   `update to the newest best database` phrasing is retired.
8. ~~Check naming consistency~~ — **RESOLVED:** the app uses "Database Check"
   everywhere (`Database Check Passed` / `Database Check Failed`); the button
   stays `Check Database Integrity`. Restore-from-backup is a verified +
   confirmed action (§15.2b); only the A5 confirmation wording is still open.

**STILL OPEN — behavior/placement to confirm at build time:**
8. ~~(B8) one press = both databases?~~ — **B8 RESOLVED (owner July 15
   2026):** one press checks BOTH databases and reports per database; wording
   approved in §15.12 (`Database Check Complete` / `Database Check Incomplete`,
   per-database status lines). Buttons for that result + adopting the format in
   A4/§15.9 still to confirm (see §15.12 B8 notes).
9. ~~(B9) mid-session alerting~~ — **B9 RESOLVED (owner July 15 2026, §15.2c):**
   mid-conversation detection plays a distinct audio warning immediately, then
   shows the existing §15.2a/A2 banner — NO new dialog, no redesign. One build
   detail open: whether the audio plays only in hands-free sessions or always.
10. ~~degraded mode persists across restarts?~~ — **B10 RESOLVED (owner: yes):**
    stored "disabled pending repair" flag; stays off + banner until a repair
    or restore succeeds (§15.2a).
11. ~~one combined backup date?~~ — **B11 RESOLVED, and REDESIGNED (owner July
    15 2026, §15.13; destination + status updated July 20):** backups are
    SEPARATE per-type files (memory / lorebook / profile image catalog /
    chats) in a **user-selected SAF folder** (app-private fallback until
    selected), each written-verified-rotated-restored independently;
    compact one-row-per-type status with date+time; backup errors show the
    folder path as selectable text (`Open Backup Folder` = secondary
    convenience). No combined single-file backup. Supersedes
    §15.1/§15.8/§15.9's single-file wording.
12. The Advanced Memory Settings screen already shows an integrity result and
    row counts. **B12 RESOLVED (owner July 16 2026) — see §15.14, a new
    dedicated area.**
13. ~~ungated health log lines~~ — **B13 RESOLVED (owner July 16 2026) — see
    §15.15.**
14. **NEW (owner July 16 2026) — Profile Images database, §15.16:** a THIRD
    database (`profile_images.db`) discovered mid-design now gets the SAME
    backup, integrity-check, persistent-banner, and repair/replace treatment
    as memory and lorebook — not a lighter version. It differs only in not
    needing full degraded-mode/feature-disable (nothing else depends on it to
    function). Banner wording not yet written (Phase 2).

### 15.14 Screen placement — new "Memory Backup & Restore" area (B12 RESOLVED, owner July 16 2026)

Owner has been rearranging screens independently since this chat began (this
overlaps with the separate menu-reorganization effort — keep that session
aware of this ruling so it doesn't re-decide it differently).

- **New area: "Memory Backup & Restore."** ALL backup and database-restoration
  functionality lives here — this becomes the one home for: `Check Database
  Integrity`, `Create Backup`, the Choose/Change Backup Folder control, the
  compact per-type status rows (§15.9 item 3), `Database Check` results, and
  `Repair` / `Revert to Last Good Database` actions. This REPLACES the
  "Backups" section currently on the Memory Controls screen
  (`memory_controls_section_backups` string) as the home for this
  functionality — Memory Controls no longer holds the backup/restore
  controls once this area exists.
- **Advanced Memory Settings keeps "System Status" at the top**, unchanged in
  position — store health + row counts stay there, first thing on the screen
  (per the existing `advanced_memory_section_status` section).
- **Everything else that deals with backup and repair** (beyond System Status)
  moves into the new Memory Backup & Restore area — e.g. the existing "Setup /
  Repair" section's backup-adjacent content, not just the newly-designed
  controls from this document.
- ~~Exact navigation entry point left to the menu-reorg effort~~ — **ANSWERED
  BY REALITY (July 18 2026):** the menu-reorg effort built it as
  `MemoryBackupRestoreActivity`, a row on the Memory Manager hub, already
  holding the auto-backup toggle, import/export, last-backup status, and
  Reset (moved out of Memory Controls). The §15 controls (`Check Database
  Integrity`, `Create Backup`, the Choose/Change Backup Folder control, the
  compact per-type status rows, repair/revert actions) are ADDED to that
  existing screen, which also keeps the recovery-vs-portable split legible
  (`Export Portable Copy` / `Import Portable Copy` — build plan Rev 2).

### 15.15 Health-failure logging — write once, to the Error Log, timestamp in red (B13 RESOLVED, owner July 16 2026)

Resolves §14.1's open question (whether health-transition lines are ungated in
the Memory log) with a more specific owner ruling that supersedes it:

- **Written regardless of the diagnostics toggle** — a database-health failure
  (corruption found, repair attempted/succeeded/failed, restore performed,
  repeated backup failure) is recorded even when diagnostics logging is off.
  This matches the "recovery information, not optional debug noise" principle
  from §14.1's owner-gate discussion.
- **Once per event, not every turn.** A health problem is logged ONCE when it
  transitions (matches the existing §3.4/§4.1 transition-only design elsewhere
  in this document — no repeat-occurrence spam).
- **Goes to the Error Log** (not the Memory/Voice log) — owner's explicit
  choice of channel. This is a change from §4.2's earlier proposal to reuse the
  Memory Debug Log channel; the Error Log supersedes that for these entries.
- **The date and time of the entry should render in red** so a health-failure
  line visually stands out from ordinary Error Log entries when scanning the
  log. Applies to the timestamp portion of the log line specifically.

### 15.12 Approved verbatim wording — owner, July 15 2026

These strings are **owner-approved verbatim**. Record and implement them
exactly; do not reword without asking. Dates render `Month D, YYYY`.

**CASING (style guide, CORRECTED):** dialog **TITLES** and **BUTTON LABELS**
are **Title Case** (first letter of each major word capitalized), NOT full
uppercase. **Body text stays sentence case.**

**Database name is variable:** every string below is written with the
affected database named ("lorebooks" / "memory"). At build time the app fills
in whichever database is actually affected — and the loss noun matches it
("recent memories" for the memory database; "recent lorebook entries" for the
lorebook database).

**A1 — Automatic database-problem dialog (problem found, could NOT auto-repair).**
Appears at startup after a crash when the integrity check finds a damaged
database and repair was not possible. Blocking; user must choose. (Lorebook
variant shown; memory variant swaps the database name and loss noun.)
> **Title:** `Database Problem Found!`
> **Body (sentence case):**
> `Your lorebooks database is damaged and couldn't be repaired automatically. You can try a repair, or replace it with your latest good backup. This may cause recent lorebook entries to be lost.`
>
> `Until then, lorebooks will be unavailable to use or save to prevent further corruption.`
> **Buttons:** `Repair` | `Revert to Last Good Database` | `Cancel`

- **Button correction (owner, July 20 2026): `Cancel`, never `Not Now`.**
  The owner does not want "Not Now" — or the word "now" generally — in any
  dialog button anywhere in this design; "Cancel" is the standing button
  for declining an action across every dialog in §15. This applies to A1,
  A3, A4/its replacement, and any future dialog — not just the one
  instance already caught in the backup-failure redesign.
- **"...unavailable to use or save..."** (owner addition): the broken database
  is blocked from being SAVED too, not just used. This is deliberate and
  important when automatic daily backups are ON — otherwise the auto-backup
  could copy the corrupt database over the good backups. (Consistent with
  §15.1/§15.6: backups pause while a database is degraded.)

**A1 — Automatic dialog (the app repaired it itself).** Informational.
> **Title:** `Database Repaired`
> **Body (sentence case):** `A problem was found in your [memory / lorebook] database and repaired automatically. Everything should be working normally.`
> **Button:** `OK`

**A2 — Degraded-mode banner (top of every new chat while disabled).**
Persistent, dismissible, re-shown per new chat. Banner text is a sentence
(sentence case); its buttons are Title Case.
> **Memory variant:** `Memory is currently turned off because of a database problem. Tap Repair to fix it.`
> **Lorebook variant:** `Lorebooks are currently turned off because of a database problem. Tap Repair to fix it.`
> **Buttons:** `Repair` | `OK`

**A3 — Blocked "Analyze Conversations" note + working actions.** Shown where
the Analyze/Archivist action would be; the action stays blocked while any DB
problem exists, and these buttons are FUNCTIONAL (owner: "make those buttons
that work"). Note text is sentence case; buttons Title Case.
> **Text (sentence case):** `Unable to analyze conversations due to current memory database corruption. You may try to repair it again or revert to last known good database. Caution reverting may cause recent memories to be lost.`
> **Buttons (working):** `Repair` | `Revert to Last Good Database`

**A4 — Repeated-backup-failure dialog — HISTORICAL, DO NOT IMPLEMENT
(superseded by owner July 20 2026; see the supersession note at the end of
this block).** The whole A4 design below — the five-button layout, the
two-stage inline integrity check, and the `Save Back Up in New Location`
action — is kept ONLY as the historical record of the July 15 ruling. The
ACTIVE design is the category-split dialog (`Change Backup Folder | Retry |
Cancel`; source failures checked then routed by store type; build plan Rev
2, Round 3 item 8). Original July 15 text follows:
> **Title:** `Backup Attempts Failed`
> **Body (sentence case):** `Your device may be low on storage space. Please choose another location or free up space.`
> `If you have enough storage space, try checking the database integrity.`
> **Backup folder location (shown per §15.13):** `[backup folder location]`
> **Buttons:** `Save Back Up in New Location` | `Open Backup Folder` | `Retry` | `Okay` | `Check Database Integrity`

- Per §15.13: any backup-error surface must show the backup folder location and
  an `Open Backup Folder` button — added to A4's buttons above.
- **SUPERSEDED (owner-approved, July 20 2026):** the five-button layout
  above is REPLACED by the category-split design (build plan Rev 2, Round 3
  item 8): destination/storage failures get exactly
  `Change Backup Folder | Retry | Cancel` (owner: `Cancel`, not `Not Now`;
  folder path as selectable text; `Open Backup Folder` as a secondary text
  action); an actual source-damage failure skips the storage dialog and
  enters the A1 repair flow directly. The wording block above is kept as
  the historical record only; the new dialog's body text is still to be
  owner-approved (build plan Round 4).

Inline integrity check inside A4 (under the `Check Database Integrity` button):
- While running (status text, sentence case): `Checking Database...`
- Then it disappears and shows the result: `Database Check Passed` or
  `Database Check Failed`.
- On **`Database Check Failed`**, A4 escalates to the corruption/repair flow
  (same content as A1-could-not-repair):
  > **Heading:** `Database check failed and could not be repaired.`
  > **Body (sentence case):**
  > `Your lorebooks database is damaged and couldn't be repaired automatically. You can try a repair, or replace it with your latest good backup. This may cause recent lorebook entries to be lost.`
  >
  > `Until then, lorebooks will be unavailable to use or save to prevent further corruption.`
  > **Buttons:** `Repair` | `Revert to Last Good Database` | `Cancel` (historical
  > block — `Not Now` corrected to `Cancel` per the owner's July 20 ruling
  > against that button appearing anywhere)

- **Naming (RESOLVED, owner July 15 2026):** the whole app uses the
  **"Database Check"** name — `Database Check Passed` / `Database Check Failed`
  (the Memory Controls result strings in §15.9 were updated to match; the
  earlier "Database Integrity Passed/Failed" is superseded). The button that
  starts it stays `Check Database Integrity`.

**A5 — Restore-from-backup confirmation dialog (APPROVED, owner July 15 2026).**
The §15.2b secondary check: appears when the user taps `Revert to Last Good
Database`, after the app has verified the backup is good, before it overwrites
anything. Blocking confirm. (Lorebook variant shown alongside memory; the app
fills in the affected database, the real backup date in `Month D, YYYY`, and
the matching loss noun.)
> **Title:** `Restore from Backup?`
> **Body — memory (sentence case):** `This replaces your damaged memory database with your last good Backup from [Month D, YYYY]. Any memories added after that date will be lost. Your damaged database will be kept aside, not deleted.`
> **Body — lorebook (sentence case):** `This replaces your damaged lorebook database with your last good Backup from [Month D, YYYY]. Any lorebook entries added after that date will be lost. Your damaged database will be kept aside, not deleted.`
> **Buttons:** `Restore` | `Cancel`

**A6 — Recovery-failed / started-fresh dialog (APPROVED, owner July 15 2026).**
Shown ONLY when a database is corrupt, could not be repaired, AND had no usable
backup — the app started that database over empty and preserved the damaged
file.
> **Title:** `Database Recovery Failed`
> **Body (sentence case):**
> `The [memory / lorebook] database was damaged and could not be repaired. No usable backup was available, so the app created a new, empty database to keep working.`
>
> `The damaged database was not deleted. It was saved here in case it can be recovered later:`
>
> `[full file path or folder location]`
> **Buttons:** `Open File Location` | `OK`

**B8 — `Check Database Integrity` checks BOTH databases in one press; results
are reported per database (APPROVED wording, owner July 15 2026).** One press
runs the check on both the memory and lorebook databases and lists each
result. This **supersedes** the earlier single-line `Database Check Passed` /
`Database Check Failed` result.
> **When the check ran fully:**
> **Title:** `Database Check Complete`
> `Memory database: No problems found`
> `Lorebook database: Damage detected`
> `Your lorebook data may need repair.`
>
> **When a database could not be checked (partial failure):**
> **Title:** `Database Check Incomplete`
> `Memory database: No problems found`
> `Lorebook database: Could not be checked`
> `Try again or view the error log for details.`

- The per-database status lines are examples — each database shows its own real
  result (`No problems found` / `Damage detected` / `Could not be checked`).
- **Since §15.16 there are THREE databases** — a
  `Profile images database: …` line joins the report in the same format
  (agent note; the owner's two example lines above are unchanged).
- ⚠️ **Buttons for this result screen were not specified** — owner to confirm
  (likely `Repair` / `Revert to Last Good Database` when damage is detected;
  `Try Again` / `View Error Log` on incomplete).
- ⚠️ **Consistency:** this per-database report should also replace the simpler
  `Database Check Passed/Failed` strings used by A4's inline check and §15.9's
  Memory Controls check. Owner to confirm they adopt this same format.

### 15.13 Backup architecture — separate per-type files (B11 REDESIGN, owner July 15 2026)

**This SUPERSEDES the single combined-file backup design.** The current app
writes ONE JSON export holding the memory database + chats together (§15.1);
the owner is replacing that with **separate, independent backup files per data
type.** Do NOT implement any combined archive or single-file backup design.

> **TWO SYSTEMS (clarified July 20 2026, second external review):** the
> per-type files below are **automatic same-installation RECOVERY backups**
> (fast rollback after corruption; may depend on this installation's keys).
> They do NOT replace the **portable JSON export/import**
> (`MemorySeedCodec` + `app_chats`), which stays, keeps being developed, and
> remains the key-independent path for reinstalls, other devices, and the
> future Windows sync (Phase 8). Portable chat import/merge is future work,
> not abandoned scope. The Backup & Restore screen distinguishes recovery
> backups from `Export Portable Copy` / `Import Portable Copy`. Full detail:
> `database_health_build_plan.md` (Revision 2), which also carries the SAF
> staging pipeline, failure categories, and honest trigger description that
> govern how these files are actually written.

**Backup structure:**
- A **user-selected SAF backup folder** (Choose/Change Backup Folder,
  permission persisted; app-private fallback until selected — July 20
  2026). ~~One clearly named `App Backups/` folder~~ as the primary
  destination is historical.
- Each automatic backup run creates **separate files** for:
  - Memory database
  - Lorebook database
  - Chats
  - **Profile Images catalog** (`profile_images.db` — added §15.16, owner July
    16 2026; a THIRD real database, discovered mid-design, was found to have
    NO backup coverage at all)
- Example filenames (same timestamp across one run):
  - `memory_backup_2026-07-15_1430.db`
  - `lorebook_backup_2026-07-15_1430.db`
  - `chats_backup_2026-07-15_1430.zip`
  - `profile_images_backup_2026-07-15_1430.db`
- All files from one run share the same timestamp, but each is written,
  verified, and **restorable independently.** Owner's reasoning (July 16
  2026): "if there's a daily or weekly backup its negligible size so it can be
  included" — the catalog is tiny (hashes + timestamps only, no image bytes),
  so it rides along in the same run at effectively no cost.

**Required behavior:**
- Do NOT combine memory, lorebooks, and chats into one backup file.
- Write each backup to a **temporary file first**.
- **Verify** each backup completed successfully before it replaces the
  previous good backup of that type.
- **Never overwrite or delete the last known-good backup** (of a given type)
  until the new one of that type has been verified.
- A failure backing up ONE data type must NOT invalidate or remove the
  successful backups of the others (each type is independent).
- Restoration must allow memory, lorebooks, or chats to be restored
  **separately**.
- **Destination (updated July 20 2026):** a **user-selected SAF folder**
  (Choose/Change Backup Folder, permission persisted), with the app-private
  folder only as the fallback until the user has chosen. A fixed
  `App Backups/` folder as the primary destination is HISTORICAL, DO NOT
  IMPLEMENT. Any backup error shows the current folder path as selectable
  text; `Open Backup Folder` is a secondary convenience action, never a
  requirement.
- **Trigger honesty (July 20 2026):** backups are checked at app start (and
  optionally on return to foreground) with a 24-hour throttle — NOT
  guaranteed background daily scheduling. Nothing may promise "daily" as a
  guarantee.

**Status display — HISTORICAL, DO NOT IMPLEMENT (superseded July 20 2026):**
the separate `Last successful … backup` lines that stood here are replaced
by the **compact one-row-per-type display** in §15.9 item 3 (owner-approved
example format there). Still active from this block: **12-hour AM/PM time**
(`Month D, YYYY, H:MM AM/PM`) for display, while filename timestamps stay
sortable 24-hour (not user-facing).

**Knock-on updates:**
- §15.8 rotation (keep newest 5) now applies **per file type** — 5 memory, 5
  lorebook, 5 profile-image-catalog, 5 chats — not 5 combined.
- §15.9's single-line and two-line status layouts are HISTORICAL — the
  compact rows (§15.9 item 3) are the active design.
- The backup-failure dialog is the category-split design (§15.12 A4
  supersession + build plan Rev 2 Round 3 item 8), showing the folder path
  as text.
- A6's preserved-file location (a quarantined corrupt DB) stays its own
  `Open File Location`; that is a different location from the backup folder.

### 15.16 Profile Images database — SAME treatment as memory/lorebook (RESOLVED, owner July 16 2026)

**Discovery:** a THIRD real database, `profile_images.db`, was found mid-design
(part of the new Profile Images gallery feature, merged to `main` after this
document was started). It is a small catalog (content hash + timestamp per
image; the actual JPEG files live separately on disk) and, until this
section, had **zero backup or integrity coverage** — not even the old
combined export touched it.

> **SCOPE + LABELING (July 20 2026, second external review):** this backup
> artifact is the **catalog only** — `profile_images.db`. **The JPEG image
> files themselves are NOT backed up** (owner ruling, July 16: "we just do
> the database … that's the record"). Every user-facing label and status
> line must therefore say **"Profile image catalog"**, and no wording may
> imply the pictures themselves are protected. (A damaged catalog can also
> be rebuilt by rescanning the image files — the reverse is not true.)

**Owner ruling: give it the full A1/A2/A3 treatment — do NOT water it down.**
An earlier draft of this section proposed a lighter, silent-rebuild-only
path for this database on the reasoning that the catalog is easy to
regenerate from the files on disk. **The owner rejected that as not good
enough** ("why not let the user know it's degraded? ... don't be a dick") —
being easy to fix does not mean the user doesn't deserve to be told. Corrected
design:

1. **Backed up** in the same backup runs as the other databases (§15.13
   above — startup/foreground-triggered, 24-hour throttle, not a guaranteed
   daily schedule), not treated as optional because it's "less important."
2. **Checked** by the same `Check Database Integrity` action (§15.9/B8) — one
   more line in the per-database result, not a separate system. Cheap: a small
   catalog, a standard SQLite integrity check.
3. **On a problem, the SAME persistent, must-be-acknowledged banner as A2** —
   the owner's own words: "There should be a snackbar they have to
   acknowledge." Not a vanishing Toast (per the app-wide rule and the existing
   §15.2a precedent) — the same persistent top-of-new-chat banner pattern,
   reused for this database.
4. **The SAME repair/replace choice as the others** — owner: "potentially
   repair replace just like the others." Two repair paths, offered together
   like A1:
   - **Rebuild from files** — since the catalog only records what already
     exists on disk, the app can regenerate it by rescanning the image files
     and relisting their hashes. This is the SAFE auto-repair path (nothing is
     guessed or invented) and should be attempted first/automatically, the
     same way A1's "app repaired it itself" variant works — but per the
     owner's correction, the user is ALWAYS told when this happens (the
     existing `Database Repaired` dialog, A1), never a silent fix.
   - **Revert to Last Good Database** — same restore-from-backup action as
     the other databases (§15.2b), if a rebuild-from-files isn't possible or
     doesn't fully recover.
5. **What's different from memory/lorebook (owner-confirmed, not a
   downgrade):** losing this database does **not** disable chat and does
   **not** need the full degraded-mode "feature turned off" treatment (§15.2a)
   the way memory/lorebooks do — there's no equivalent of the Archivist-writes-
   to-a-bad-database risk here, since nothing else in the app depends on this
   catalog to function. So no A3-equivalent hard-block is needed. The banner
   still names the problem and offers Repair / OK, same shape as A2, just
   without a feature-disable behind it.

**Wording:** not yet written (Phase 2, same as the other unwritten banner
variants) — but the banner text should follow the A2 pattern
(`[Feature] is currently turned off because of a database problem. Tap Repair
to fix it.` → adapted to name the image catalog) once the owner is ready to
approve it. Owner's own plain-language framing to work from: "let them know
their images are [messed up]."

### 15.11 What §15 does NOT change

No app code, strings, or UI are written by this update — it is design text
only, consistent with the "stop after the design" instruction. Every behavior
and every word above remains subject to owner approval before implementation.

---

*End of Phase 1 design. No implementation, no UI, no strings. Phase 2
(user-facing states, wording, placement) begins only on the owner's
explicit approval of this design.*
