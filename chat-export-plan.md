# Chat Export & Backup Plan

Status: **DRAFT — awaiting owner decisions** (see "Open questions" at the bottom).
Author: AI agent. Date: 2026-07-01.

This document plans two related features requested by the owner:

1. **Per-chat export** — from inside any chat, save that one conversation to a
   file the owner chooses, in **JSON / Markdown / TXT** (PDF later), picking the
   format *at save time* and picking *where* it goes (default Downloads, but free
   to navigate anywhere the Android file picker reaches — SD card, Google Drive,
   etc.), remembering the last place used.
2. **Full backup / restore** — a "save every chat" system that writes all chats
   into a folder as machine-readable JSON that the app (and other AIs) can read
   back and re-import.

It is written so a later UI pass can wire the new buttons into the redesigned
layouts without touching the export logic. **Keep feature logic out of layout
code** (per `CLAUDE.md`) — all real work lives in a new `ChatExporter` /
`ChatBackup` helper, and the UI just calls it.

---

## What already exists (don't rebuild this)

There is already working export plumbing — this is almost certainly the
"saving conversations" the owner remembers:

- `ChatActivity` has a top-bar button **`btnExport`** (`R.id.btn_export`, upload
  icon `ic_upload`). Tapping it serializes the current chat's in-memory
  `messages` list to JSON with Gson and launches Android's
  `ACTION_CREATE_DOCUMENT` "Save as…" picker (`ChatActivity.kt` ~line 2014).
- The picker result is handled by `fileSaveIntentLauncher`, which calls
  `writeToFile(uri)` — it writes the bytes in the `fileContents` field to the
  user-chosen location via `contentResolver`. (`ChatActivity.kt` ~line 2053.)
- So: **single-chat JSON export, save-anywhere, already works today.** It's just
  (a) JSON only, (b) uses a raw hashed `chatId` as the filename, and (c) is one
  small icon that's easy to overlook.

The "big yellow button on the main chat screen" is a *different* control and does
nothing for export — we are intentionally **not** touching it.

### How a chat is stored (so we know what to serialize)

- Chat list: SharedPreferences file `chat_list`, key `data` = JSON array of
  `{ name, id, timestamp, pinned }` (`ChatPreferences.getChatList`).
- Each chat's messages: SharedPreferences file `chat_<id>`, key `chat` = JSON
  array of message maps. `id = Hash.hash(name)`.
- A message map is: `{ "message": String, "isBot": Boolean,
  "image"?: String, "imageType"?: String }` (`ChatActivity.putMessage`).
  `message` may contain a `data:image...` base64 blob (generated image) or a
  `~file:` reference.
- Per-chat settings (model, endpoint, persona, sampling, active lorebooks, …)
  live in a separate `settings_<chatId>` file via `Preferences.kt`. **The
  current JSON export does NOT include these** — it's messages only.

---

## Feature 1 — Per-chat export (JSON / MD / TXT)

### UX

Replace the single silent upload icon with an **overflow menu** in the chat's top
bar (the "three dots / down-arrow" the owner described). Tapping it shows a small
Material `PopupMenu` (or bottom sheet) with:

- **Export this chat →** which opens a format chooser (JSON / Markdown / Text),
  then the system "Save as…" picker pre-filled with a sensible filename.

Format is therefore chosen **at save time**, exactly as requested. We keep the
existing button id/behavior working underneath so nothing regresses if the UI
pass is not done yet — the overflow menu is additive.

### Filename

Use the human chat **name**, not the hashed id, sanitized for the filesystem:
`My road trip planning.md`, `My road trip planning.json`, etc. Fall back to the
id if the name is empty. (Today's export uses `"$chatId.json"`, which is why saved
files looked like meaningless hashes.)

### Formats

A new helper `util/export/ChatExporter.kt` turns the messages list + chat name
into a `ByteArray` + MIME type + extension for each format:

- **JSON** — a *structured, versioned* object, not just the raw array, so other
  tools and future re-import can rely on it:
  ```json
  {
    "schema": "speakgpt.chat",
    "schemaVersion": 1,
    "exportedAt": "2026-07-01T12:34:56Z",
    "app": "phosphorshines",
    "chat": { "name": "My road trip planning", "id": "<hash>" },
    "messages": [
      { "role": "user", "content": "…", "image": null },
      { "role": "assistant", "content": "…", "image": null }
    ]
  }
  ```
  We map `isBot` → `role` ("assistant"/"user") for cross-AI readability while
  keeping the original fields recoverable. Base64 image blobs are preserved (or
  optionally elided — see open questions).
- **Markdown** — readable transcript:
  ```markdown
  # My road trip planning
  _Exported 2026-07-01 12:34_

  **You:**
  …user text…

  **Assistant:**
  …assistant text…
  ```
  Generated images rendered as `![image](data:...)` or noted as
  `_[image omitted]_` (open question). `~file:` markers cleaned up.
- **TXT** — same transcript, plain, e.g. `You:` / `Assistant:` prefixes, no
  markdown, images shown as `[image]`.

MIME types: `application/json`, `text/markdown`, `text/plain`; extensions
`.json` / `.md` / `.txt`.

### Save location + "remember last location"

- Keep using `ACTION_CREATE_DOCUMENT` (the same "Save as…" picker) so the owner
  can navigate anywhere — Downloads, SD card, **Google Drive**, etc. This is
  free: any installed document provider (incl. Drive) shows up automatically.
- **Default to Downloads** on first use via
  `EXTRA_INITIAL_URI` pointing at the Downloads tree.
- **Remember last location:** after a successful save, persist the parent
  folder URI (derive it from the returned document URI) in a new global pref,
  e.g. `GlobalPreferences.setLastExportDir(uri)`, and pass it as
  `EXTRA_INITIAL_URI` next time. Note: `EXTRA_INITIAL_URI` is a *hint* — the OS
  may land on a nearby folder — but it's the standard mechanism and works well
  in practice.

### Where the code goes

- `util/export/ChatExporter.kt` — pure formatting (messages → bytes). Unit-
  testable, no Android UI. (There's already a `app/src/test` suite for pure
  helpers — add tests here.)
- `ChatActivity` — the overflow menu, the format chooser dialog, and the
  create-document launcher. Reuse/rename the existing `fileContents` +
  `fileSaveIntentLauncher` + `writeToFile` machinery (already handles the
  content-resolver write). Add the remembered-dir pref read/write around it.

---

## Feature 2 — Full backup / restore of every chat

### Goal

One action that writes **all** chats to a location as JSON, and a matching
**import** that reads them back. Machine-readable so "other AIs can read them."

### Two possible shapes (pick one — see open questions)

**Option A — Single bundle file (recommended for simplicity).**
One `speakgpt-backup-YYYYMMDD-HHmm.json` containing every chat:
```json
{
  "schema": "speakgpt.backup",
  "schemaVersion": 1,
  "exportedAt": "…",
  "chats": [
    { "name": "…", "id": "…", "timestamp": "…", "pinned": false,
      "messages": [ … same message objects as single-chat export … ] }
  ]
}
```
- Save via the same `ACTION_CREATE_DOCUMENT` picker → one file, drop it in
  Drive, email it, whatever.
- **Import** via `ACTION_OPEN_DOCUMENT` → read the bundle → recreate each chat
  through `ChatPreferences` (merge or replace — open question).
- Pros: dead simple, one file to move around, trivially re-importable, easy for
  another AI to parse. Cons: not a browsable "folder of chats."

**Option B — A folder (SAF tree) of one JSON per chat.**
- Use `ACTION_OPEN_DOCUMENT_TREE` to let the owner pick/create a backup
  **folder**, then `takePersistableUriPermission` so the app can re-write it
  later without re-asking. Persist that tree URI in `GlobalPreferences`.
- Write `chat_<name>.json` per chat plus a `manifest.json` (list + ordering).
- **Import** by reading the same tree.
- Pros: matches the owner's "create a folder and all chats live in there" mental
  model; each chat is an individual readable file; supports incremental
  re-export to the same remembered folder. Cons: more moving parts; Drive-backed
  trees can be slower/flakier than a single file.

> Recommendation: **do Option A first** (fastest, robust, meets "re-importable
> JSON other AIs can read"), and add Option B's folder mode in a follow-up if the
> owner wants the browsable-folder feel. Both can share the same per-chat JSON
> object from Feature 1, so Option B is cheap to add later.

### Import safety

- Importing must **never silently clobber** existing chats. On name/id
  collision, default to **skip or rename** (`My chat (imported)`) rather than
  overwrite. A "replace all" mode can be a separate, clearly-labeled,
  confirm-dialog action (destructive → Material confirm, per `CLAUDE.md`).
- Reuse `ChatPreferences.addChat` + writing the `chat_<id>` blob so imported
  chats behave exactly like native ones.
- Should backup include **per-chat settings** (`settings_<chatId>`) and
  **personas/lorebooks**? For round-tripping between devices that matters; for
  "so another AI can read my conversations" it doesn't. See open questions —
  this decides scope significantly.

### Where the code goes

- `util/export/ChatBackup.kt` — build the bundle from `ChatPreferences`, and
  parse a bundle back into chats. Pure logic + unit tests.
- Entry point: a **"Backup all chats" / "Import chats"** pair. Natural home is
  the chat-list screen's overflow or **Settings** (not inside a single chat,
  since it's app-wide). Exact placement is a UI decision to confirm.

---

## Sequencing (functionality first, UI later)

1. **`ChatExporter` + formats + unit tests** (no UI change) — pure logic.
2. **Wire per-chat export** to JSON/MD/TXT with the format chooser + remembered
   dir, reusing existing launcher. Fix the filename to use the chat name.
3. **`ChatBackup` (Option A bundle) + import**, entry point in Settings/chat
   list, with collision-safe import + confirm dialogs.
4. *(Optional later)* Option B folder mode; PDF export.
5. Update docs (`CLAUDE.md` feature list + storage notes, and
   `ui-redesign-plan.md` so the redesign knows about the new overflow menu /
   Settings entries) **as each piece lands**, not at the end.

Everything is verified through CI (`android-checks.yml`) since there's no local
SDK — static-check `R.*`, imports, XML, signatures before each push.

---

## Open questions for the owner (let's go one at a time in chat)

1. **Backup shape:** single bundle file (Option A, simplest) vs. a picked folder
   with one file per chat (Option B)? Recommendation: A now, B later.
2. **Backup scope:** just chats + messages? Or also per-chat settings and
   personas/lorebooks (needed to truly restore a device, but bigger + more
   fragile)?
3. **Images in exports:** conversations can contain big base64 image blobs.
   Keep them in JSON (faithful, large files) and note-only in MD/TXT? Or add an
   "include images" toggle?
4. **Import collisions:** skip duplicates / import-as-copy (safe default) — is a
   separate "wipe and replace all" mode wanted at all?
5. **Backup entry point:** Settings screen, or the chat-list overflow menu?
