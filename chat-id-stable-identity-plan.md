# Chat ID Stable Identity — Audit + Approved Implementation Plan

**Status: APPROVED for implementation, July 21 2026. Not yet built.**
This document is the complete handoff from the audit/planning session. The
plan below was revised three times with the owner and a second reviewing AI;
the version here is final. Read this whole file before writing any code, and
read the "Do NOT build" section twice — earlier drafts contained repair
machinery that was deliberately rejected.

Line numbers cited below are as of `main` @ 927a9f0 (the commit that landed
the Profile Images refresh fix). `ChatPreferences.kt` references are exact;
`ChatActivity.kt` references may drift by a few dozen lines.

---

## 1. The problem (verified)

A chat's ID is `Hash.hash(chatName)` — hex SHA-256 of the **display name**
(`util/Hash.kt`). Renaming a chat therefore changes its identity, and the app
carries three whole subsystems that exist only to survive that:

- `ChatRenameTransaction` — copies `chat_<old>` → `chat_<new>` and the whole
  `settings.<old>` → `settings.<new>` file (verify → pointer flip → clear old).
- `RenameJournal` — durable journal bridging the prefs rename and the memory-DB
  re-point across process death (`planReconcile` is the pure recovery decision).
- `MemoryStore.repointChat` (`MemoryStore.kt:3050`) — moves exactly three
  tables: `transcripts.chat_id`, `injection_cooldowns`, `chat_turn_counters`.

Known live defects caused by name-derived IDs (verified — `repointChat`
touches only those three tables):

- `archivist_runs.chat_ids_json` / `failed_chat_ids_json` are never re-pointed,
  so the Memory Assistant's **Rerun** silently drops a chat renamed since the
  run.
- `rejected_drafts.chat_key` (DB v14) is never re-pointed, so renaming a chat
  defeats its rejected-draft suppression — a rerun can refile drafts the owner
  already deleted.

Both heal automatically for future renames once IDs stop changing. No backfill
for historical renames is possible or attempted.

## 2. The dual-source hazard (verified — this defines the design)

The chat-list entry (in `enc.chat_list`, key `data`, a Gson `ArrayList<HashMap
<String,String>>`) already stores an `id` field alongside `name`, `timestamp`,
`pinned` (`ChatPreferences.addChat`, `ChatPreferences.kt:589-610`). But the
stored `id` is **not authoritative**: many paths recompute `Hash.hash(name)`
at read time. Both forms only agree because every rename rewrites both.

Paths that TRUST the stored `id`:
- `editChat` entry lookup + duplicate check (`ChatPreferences.kt:684,689`)
- `getChatName` (`:638`), `deleteChatById` (`:807`), `checkDuplicate` (`:624`)
- `RenameJournal.reconcile`'s live-ID set (`RenameJournal.kt:151`)

Paths that RECOMPUTE `Hash.hash(name)` — the Stage 0 conversion inventory:

| Site | What it keys |
|---|---|
| `ChatListAdapter` (~10 places: `:122,:172,:225,:345-348,:369,:414,:442,:457`) | opening a chat, avatars (`getAvatarTypeByChatId`/`getAvatarIdByChatId`), pin, timestamp, rename callback |
| `ChatPreferences.getChatListResult` first-message loop (`:233`) | list previews |
| `ChatPreferences.switchPinState` (`:263`), `putMetadataToChatById` (`:292`) | pin, timestamps |
| `ChatPreferences.deleteChat(chatName)` (`:160,:167`) | deleting the history file + read-failure record |
| `ChatsListFragment` pin swipe (`:283`) | pin |
| `TranscriptRecorder.backfillExistingChats` (`:158`) | transcript backfill keys |
| `MemoryExporter.buildAppChats` (`:157,:161`) | backup `chat_id` + history lookup |
| `Archivist.liveChatNamesById` (`:604-611`) | the deleted-chat filter — transcripts whose id isn't the hash of a live name are treated as deleted and never analyzed |

The dangerous failure mode is a build where some code uses the stored ID and
other code recomputes: after one rename the two disagree and identity
splits (history saved under one id, transcripts captured under another, the
Archivist orphans live transcripts). Everything below is shaped to make that
impossible.

## 3. Everything keyed by the chat ID (verified inventory)

1. History: `enc.chat_<id>` prefs (key `chat`).
2. Per-chat settings: `enc.settings.<id>`. The empty-ID file `settings.`
   (`Preferences.getPreferences(context, "")`) is the **global defaults
   file** — never touch its semantics.
3. The chat-list entry's `id` field.
4. Memory DB: `transcripts`, `injection_cooldowns`, `chat_turn_counters`
   (re-pointed on rename today); `archivist_runs` + `rejected_drafts`
   (NOT re-pointed — see §1).
5. Recovery data keyed by historical IDs (`ChatStorageHealth` read-failure
   records, `SnapshotRegistry` entries, `files/storage_recovery/`,
   `*_conflict_*`/`*_recovered_*`/`*_corrupt_*` snapshots). Point-in-time
   records; house rules forbid touching them.
6. Transient: `chatId` intent extra into `ChatActivity` (`prepareChatStartup`),
   `SettingsActivity`, the Summoning Circle sheet, both foreground-service
   notifications (display/reopen only).
7. Exports: memory backups' `app_chats` entries carry
   `chat_id = Hash.hash(name)`. **Chat import from `app_chats` is NOT
   implemented** (`MemoryExporter.kt:135-143`) — the backup is an archival
   precaution, NOT a rollback mechanism. Never describe it as able to restore
   chats.

## 4. Core decision (approved)

**Each existing chat's stored hash becomes its permanent ID verbatim; new
chats get `StableId.newId("c-")` (UUID). No data migration** — no prefs file
renamed, no DB row rewritten, no backup format change. This is the same
playbook `util/StableId.kt` documents for personas/endpoints/activation
prompts/logit-bias configs ("the legacy hashed id IS the permanent id").
A hex hash and a `c-<uuid>` can never collide.

## 5. The identity marker (approved design)

Each chat-list entry gains one new key: **`identity_version = "2"`**.

- Durable and travels inside the same JSON blob as the other entry keys, so
  every existing list write path carries it automatically, and
  `MemoryExporter.buildAppChats` exports it for free (it copies all extra
  entry keys).
- The marker is the **only** durable authority for "this entry's stored ID is
  permanent." It must never be inferred from `id == Hash.hash(name)` — after
  the first legitimate stable-ID rename that equality is false — and never
  held only in memory or a global latch.

## 6. The resolver (approved design)

One funnel — e.g. `ChatIdentity.effectiveId(entry)` — used by **every** site
in the §2 conversion inventory:

- Entry has `identity_version = "2"` → return the stored `id`.
- Entry's `id` starts with `c-` → return the stored `id` even without the
  marker (a `c-` ID can only have been minted by the new code; a marker-only
  rule would fall back to a meaningless name-hash and show an empty chat if
  the marker were ever absent before healing runs). Healing adds the marker to
  such entries so the two authorities converge (reviewer note, adopted).
- Otherwise → return `Hash.hash(name)`: byte-identical legacy behavior.

A CI source-scan unit test (same pattern as `PerChatSettingKeysTest`) pins
`Hash.hash(` on chat names to exactly the resolver's fallback plus the legacy
creation path, so no new recompute site can ever appear.

## 7. The healing pass (approved design — deliberately minimal)

Startup housekeeping thread, under `ChatPreferences.CHAT_LIST_LOCK`, only when
`getChatListResult(context, includeFirstMessage = false)` is authoritative
(`ChatStorageHealth.isAuthoritative`); otherwise skip entirely and retry next
start. Never parse histories here (the July 15 ANR rule). For each entry
WITHOUT the marker, with stored id `S` and `H = Hash.hash(name)`:

1. **`S` present and `S == H`** → add `identity_version = "2"`. That exact
   stored ID is now permanent. No files touched.
2. **`S` blank/absent** → set `id = H` through the normal list
   read-modify-write, then add the marker.
3. **`S` starts with `c-`** → add the marker (convergence case from §6).
4. **`S ≠ H` (and not `c-`)** → **NO repair.** Do not switch history, move
   files, re-point transcripts, merge anything, or touch `RenameJournal`.
   The entry stays unmarked: the resolver keeps it on `Hash.hash(name)` so
   the user keeps seeing exactly what they see today; renames of it are
   REFUSED (existing rename-failed dialog + persistent Error Log line — never
   a toast); one Error Log line per process reports it for manual inspection
   (naming the chat is fine — `editChat`'s failure lines already do; the
   plaintext `storage_health` file, if used at all, holds ids only).

Marker writes are ordinary list mutations committed under the lock. The pass
is idempotent and cheap (marked entries skip instantly) and simply runs every
start — correctness never depends on a settled-latch.

### Do NOT build (explicitly rejected in planning — do not resurrect)

- The four-location automatic repair matrix (probing history/settings under
  both `S` and `H` and picking a winner).
- Automatic "only-S" history reattachment.
- SnapshotRegistry protection of a mismatched entry's side files.
- Any partial/journaled `RenameJournal` transcript move during healing.

Rationale: all of that was machinery for a broken state no device is known to
exhibit, and automatic repair of ambiguous data is guessing with the owner's
data. If rule 4 ever actually fires on the owner's phone, that chat is
analyzed as its own incident FIRST, and any repair is designed then, with the
owner, from the evidence.

## 8. Implementation ordering — three commits, ONE branch, ONE release

These are separate **commits** for reviewability, not separate releases. The
hard sequencing rule: **every read path goes through the resolver before the
commit that changes rename/new-chat semantics exists on the branch.** At
runtime, per-entry safety comes from the marker itself — freeze semantics act
only on marked (or `c-`) entries — so there is no startup race and no
dependence on the healing pass having run first.

**Commit 1 — resolver + reader conversion + guard test.**
`ChatIdentity` resolver; convert every §2 inventory site; retire
`deleteChat(chatName)` in favor of `deleteChatById` (delete flows must pass
the resolved id, and the `ChatStorageHealth.clearReadFailure` call must key
the same file actually deleted); add the CI source-scan guard test. Behavior
is byte-identical (no marker exists yet, resolver returns the hash for
everything).

**Commit 2 — marker + healing pass.**
As specified in §7, wired into `MainApplication`'s startup housekeeping
AFTER `SecurePrefs.reconcileOutageAtStartup` and `RenameJournal.reconcile`
(keep the existing mandatory ordering of those two; never reorder them).

**Commit 3 — stable creation + name-only rename.**
- `addChat` / `AddChatDialogFragment.createChat`: mint `StableId.newId("c-")`,
  write the entry with `identity_version = "2"`, create `chat_<uuid>` /
  `settings.<uuid>`. The import-from-file write in `ChatsListFragment.onAdd`
  (`:129-134`) uses the id `onAdd` receives — keep that consistent.
- `editChat` on a marked entry: name-only list update under `CHAT_LIST_LOCK` —
  no file moves, no journal, no `repointChat`. Same `false`-on-refusal
  contract for callers. Duplicate check becomes a **name** comparison
  (refusal retained — duplicate names stay disallowed; relaxing that is an
  owner decision, not a side effect). `editChat` on an unmarked entry:
  refused (§7 rule 4).
- `ChatActivity` auto-name (`~:5461-5521`) and the manual rename path
  (`AddChatDialogFragment:187-236`) simplify: auto-name no longer changes
  `chatId` at all (the in-place adoption block shrinks to name/title/intent
  updates). This REMOVES risk from the fragile voice path — the whole
  mid-conversation identity swap disappears.
- `MemoryExporter.buildAppChats` writes the resolved stored id.
- KEEP `RenameJournal.reconcile` + `repointChat` running at startup
  indefinitely (drains any pre-upgrade pending entry; idempotent; cheap).
  `ChatRenameTransaction` becomes unreachable from renames — leave the code
  and its tests in place; deletion is later cleanup, not part of this work.

**Later, separate (not in this release):** dead-code removal; CLAUDE.md
updates (the "Chat IDs are deliberately still name-derived" notes in the
storage section and `StableId.kt`'s comment, plus the rename-machinery
paragraphs); any duplicate-name policy change (owner decision only).

## 9. Tests

**Automated (JVM, `app/src/test`, existing patterns):**
- Resolver decision table: marked / `c-` unmarked / unmarked-match /
  unmarked-mismatch.
- Healing rules 1–4 as pure logic, plus: skip-on-non-authoritative-list,
  idempotence (second run is a no-op), marker survives a Gson round trip.
- Rename refusal for unmarked-mismatched entries.
- Name-only rename leaves `id` and both files untouched; new-chat entries
  carry `c-` id + marker.
- The source-scan guard test (fails CI if a new `Hash.hash(` chat-name call
  site appears outside the sanctioned spots).
- Existing `ChatRenameTransactionTest` and `RenameJournal` tests stay green
  and stay in the tree.

**Manual, on the owner's phone (the work is NOT done until the owner
confirms — never report done on any other basis):**
- Rename a chat manually and via auto-naming; verify history, companion,
  lorebooks, memory scene, and per-chat tuning all survive.
- Voice loop survives an auto-name mid-conversation (hands-free, screen off).
- Chat-list preview/avatar/pin correct after rename.
- Memory Assistant still lists and analyzes a renamed chat's conversation.
- New-chat create and delete round trips.
- No chat's visible history changed unexpectedly after installing the update.
- If the Error Log reports a mismatched chat (§7 rule 4), stop and walk the
  owner through it before anything else touches that chat.

## 10. Recovery notes and owner communication

- Nothing pre-existing is deleted or rewritten beyond adding the marker key
  and filling blank `id`s. All snapshots, `storage_recovery/`, `outage.*`-era
  artifacts and orphan files stay untouched; no orphan sweep may run while
  `RenameJournal.hasPending` (standing rule).
- Before the owner installs the build: take a fresh memory backup. Present it
  as an **archival precaution only** — `app_chats` import is not implemented,
  so it cannot restore chats automatically (§3.7).
- Tell the owner in plain words: a chat renamed after this update is not
  readable by older builds (the old build recomputes the name hash and finds
  nothing). One-way door, acceptable on the single `latest` test channel, but
  it must be said before install.

## 11. Repo-practice reminders for the implementing session

- No local Android SDK: statically verify (R.* references, imports, XML,
  signatures), push the branch, and confirm the `Android Checks` CI run.
- Owner rules: no toasts, no `AskUserQuestion` pop-ups (plain chat questions
  only), persistent dialogs/log lines for anything user-facing, all strings in
  `strings.xml`, commit messages are plain prose explaining WHY with no AI/
  session attribution (repo privacy rule in CLAUDE.md).
- The Profile Images work landed on `main` @ 927a9f0 (avatar-refresh
  coordinators in `ChatActivity` + persona editors). It does not touch any
  §2 site; no coordination conflict remains. Recent UI renames: Quick
  Settings is now the "Summoning Circle" sheet; persona screens carry
  "Companion"/"Glamour" wording.
- Model tiers for any subagents: Opus/Sonnet/Haiku only (project rule).
