# Drawer Scrolling and Chat Opening Plan

Status, October 30 2026: **section 1 is built as a separate database**
(owner decision; see "As built" below). The earlier sections of part 1
describe the superseded chat-list-field design. Section 2 (window fix) waits for owner approval.
Section 3 is information only.

Related: `duplicated-chats.md` (the duplicated old-client chats, which also
double the length of the drawer list).

---

## 1. Janky drawer scrolling: complete the chat-list index (Approach B)

### Owner requirements (October 30 2026)

- Eliminate the expensive per-chat reads during scrolling; do not just move
  them to a background thread.
- Expand the existing chat-list index; no new database. Keep the existing
  encryption and storage architecture as they are.
- Preserve all existing functionality, appearance settings, and styles.
- Keep the stored information accurate, avoid unnecessary writes, prevent
  backup/restore conflicts and duplicates, and make the one-time fill-in of
  existing chats safe and run only once.
- Do not modify the existing memory-status retrieval unless there is a
  demonstrated reason.

### Root cause

The chat list already has an encrypted index: the `chat_list` store, read once
per drawer refresh. It is missing several things the rows display, so each row
fetches them from that chat's private files while it is being drawn:

| Shown on a row | Source today | Cost per row |
|---|---|---|
| Title, pinned, folder, order | `chat_list` index | none |
| Start date | index `created_at`, filled in only after the chat's conversation was read once | whole conversation read until filled |
| Memory status | one bulk read from the memory store (`chatReviewStates`) | none; **left unchanged** |
| Excluded from memory | the chat's encrypted settings file | settings file opened on the main thread |
| Companion picture | the chat's settings file, then the companion store and an image-file lookup | settings file plus companion parsing on the main thread |
| Model label | the chat's usage log plus the whole conversation (`MostUsedChatModel`) | whole conversation read |
| Bookmark indicator | the whole conversation | whole conversation read |

`FlatChatIdentityBinder.bind` opens the chat's encrypted settings file for
**every** row, whatever the appearance settings are, because it calls
`Preferences.getPreferences(activity, chatId)` unconditionally. Opening an
encrypted file is slow, and the opening step (`SecurePrefs.get`) is shared with
the background conversation reader, so the scroll can stall waiting for it.
Each finished background read also redraws its row during the scroll.

Memory status: the only part that changes is the "excluded" marker, which today
is read from each chat's settings file per row (the demonstrated reason). The
bulk memory-store read stays exactly as it is.

### What gets added to each chat-list row

Internal keys stored inside the existing encrypted `chat_list` row (names are
internal and not user-visible):

- `index_version`: marks the row as filled, and lets a future change refill all
  rows once.
- `index_has_bookmarks`: whether any message is bookmarked.
- `index_model`: the model label exactly as shown today (the most-used model,
  falling back to the chat's current model).
- `index_companion`: the chat's companion ID.
- `index_memory_excluded`: whether the chat is excluded from memory.
- `created_at` already exists and keeps its current meaning; it is now filled
  in for every chat, not only after a conversation read.

The values are stored whether or not the matching appearance setting is on, so
turning a setting on later shows correct values without a reread.

### Keeping the values accurate: where they are updated

Every change goes through a small number of existing chokepoints, so the
updates are added there rather than at each caller:

- **Conversation saves** (bookmarks, start date, older model records):
  `ChatPreferences.saveChatHistory`, which every message save, edit, delete,
  bookmark and import already uses; `commitPendingConversation` (the first save
  of a new chat, which writes the history directly); `NewConversationCoordinator`
  (a new empty chat).
- **Usage log** (most-used model): `UsageLogStore.append` and `putRequest`.
  Only chat-request entries affect the model; text-to-speech and image entries
  are ignored, so they cause no write.
- **Per-chat settings:** `Preferences.setModel`, `setPersonaId`,
  `setChatExcludedFromMemory`, only for a real chat (never for the global
  default profile, whose chat ID is empty).
- **Rename and ID moves:** the row moves with its fields
  (`ChatRenameTransaction`); covered by a test.
- **Delete:** the row is removed; nothing extra.
- **Restore:** handled under "Backup and restore" below.

Every update re-reads the list inside the existing `CHAT_LIST_LOCK` before
writing (the pattern `putMetadataToChatById` already uses), so a pin, rename,
or timestamp change made at the same moment is never lost.

### Avoiding unnecessary writes

The chat list is one encrypted entry, so every write re-encrypts the whole
list. Therefore:

- New values are computed and compared with the stored row; **the list is
  written only when a value actually changed.** Most message sends change
  nothing in the index (same model, no bookmark change), so they add no write.
- When a value does change in a flow that already writes the list (for
  example the timestamp update after a reply), the two are combined into one
  write where the flow allows it.
- The computation needs no extra file reads: the conversation is already in
  memory at save time, and the chat's settings and usage log are already open
  because the chat is open.
- The one-time fill-in writes in batches, not once per chat.

### Drawer and Search display

- `FlatChatIdentityBinder` takes these values from the row and no longer opens
  any per-chat file. Search results use the same binder and get the same
  values from the chat list.
- Companion pictures are resolved once per companion per refresh, not once per
  row.
- The background per-row conversation reader (`ChatListDetailsReader` in the
  drawer, and its generation and redraw machinery in
  `DrawerHierarchyAdapter`) is removed. Rows no longer redraw mid-scroll.
- Appearance settings, styles, layouts, and wording are unchanged.

### Backup and restore

- **Backups do not carry the index fields.** They are left out of the Recovery
  Backup writers (`ChatLogicalSerializer` v2 and `ChatsArtifactWriter` v3,
  which today copy every row key as `list_<key>`) and the Human-Readable
  Backup. Backups made before and after this change have the same shape, so
  existing backup fingerprints and restore behavior do not change.
- `ChatMergePlanner` ignores the index fields when deciding whether two chats
  are identical (added to `COMPARISON_IGNORED_KEYS`), as a second safeguard.
- **Restored rows are refilled from the restored data**, never trusted from a
  file: a restored row has no `index_version`, so the fill-in below picks it up.
- No chat IDs are created or changed, so this cannot add duplicates.

### One-time fill-in of existing chats

- Runs in the background after startup, after the storage health check, and
  not while a restore is in progress (`PortableRestoreProcessGate`).
- Fills only rows without the current `index_version`. Each chat's
  conversation and settings are read **once**; results are collected and
  written in batches under the lock.
- Before writing a batch, rows that changed since they were read (for example
  a message sent during the fill-in) are skipped and filled on a later pass,
  so a fresher value is never overwritten.
- **Runs once:** a filled row is never read again. An interrupted fill-in
  continues at the next start. After a restore, only the restored rows are
  filled.
- **Damaged or locked chats** are skipped and left unmarked. No invented
  values are written; they are retried at a later start. Existing damaged-chat
  recording applies; no new log lines.

### Scaling to thousands of chats (added October 30 2026)

The owner's goal is smooth scrolling and no display delays with thousands of
chats. Two further changes are part of the recommendation:

- **Keep the parsed chat list in memory.** Today every drawer open and every
  folder open/close decrypts and parses the whole chat list again
  (`ChatNavigationRepository.snapshot`). At thousands of chats that becomes a
  visible delay before the list updates. The parsed list is kept in memory and
  reread only when the list has actually changed (a change counter advanced by
  every chat-list write).
- **Fill visible rows first.** The one-time fill-in starts with the rows on
  screen in the drawer, so labels appear within a moment, and fills the rest
  in the background.

Known remaining limit: the chat list is stored as one encrypted entry, so any
change to it re-encrypts the whole list. This already happens today on every
sent message (the sort timestamp). It does not affect scrolling. Per-row
storage would remove it but needs a full storage migration, which is not
required for the scrolling goal.

### As built (October 30 2026): separate database (owner decision)

The owner chose a separate database over extra fields in the chat list. The
chat-list-field approach was removed before merge.

- `chat_list_details.db`: a small encrypted database (own key,
  `DatabaseKeys.KEY_CHAT_LIST_DETAILS`) with one row per chat: bookmark
  indicator, most-used model, selected model, companion, memory exclusion,
  earliest message time. Loaded into memory once; rows are drawn from memory
  (`ChatListIndex`). Drawing a row never opens a chat file.
- Every value is derived from the chats, so the database is never in backups.
  Backups, their format, restore planning and the memory export are unchanged.
- Existing chats are prepared once: when the update installs
  (`MY_PACKAGE_REPLACED` → WorkManager), when the app starts, and, before any
  list is shown, for any chat still missing (drawer, Folders, Search). A list is
  never shown with an unprepared chat.
- A restore rebuilds every restored chat's entry inside the restore
  (`ChatSetReplacementCoordinator` rebase), before it reports complete.
- Changes update the entry right away: saves, chat usage, model, companion,
  memory exclusion (including Archive This Chat and new-chat Quick
  Settings), new chats (including reused IDs), deletions.
- Companion pictures are looked up in the background when a list loads.
- Measured on the JVM: a row lookup takes about 0.0001 ms; preparing 5,000
  chats' entries takes about 10 ms excluding reading the chat files. Reading
  real chats can only be timed on a device.
- Only a chat that cannot be read (locked or damaged) has no entry; it shows
  its title and start date.

### Fit with Feature 0 (Chat Storage and Backup Upgrade)

- The new `chats.db` keeps each chat-list row verbatim (`list_row_json`), so
  the index fields move with the row during migration with no extra work, and
  the stage 8 byte-for-byte comparison is unaffected.
- Stage 6 calls for "list metadata without parsing histories"; this delivers
  it. The update chokepoints above are the same ones stage 6 moves onto
  `ChatStore`.
- The backup exclusion must be kept in every later backup writer.

### Tests (routine, written with the change)

- Each update chokepoint changes only the fields it should, and writes nothing
  when nothing changed.
- A chat-request usage entry updates the model label; text-to-speech and image
  entries do not.
- Rename keeps the fields. Restore output and backup output carry no index fields.
- Fill-in: fills each row once, resumes after interruption, skips damaged and
  locked chats, never overwrites a newer value.
- Drawer rows show the same labels as today for each combination of appearance
  settings.

### Open decision for the owner

**Rows not yet filled.** For the short time the one-time fill-in is running
(and for a restored chat until it is refilled), a row can show either:

- **(Recommended)** what the row already has (title and, where known, start
  date) until it is filled. This is simplest, and the old per-row reading code
  is removed entirely.
- or today's per-row reading for unfilled rows only, as a temporary fallback.
  Nothing is missing even briefly, but the slow code is kept.

---

## 2. Home screen flashing when a chat opens

### Cause

- The chat screen uses a see-through window style (`Theme.Transparent`,
  `windowIsTranslucent`, with a 60% black window background).
- Opening a chat from the drawer, Search, or the image gallery replaces the
  whole app screen stack (`ChatActivity.rootIntent`, `FLAG_ACTIVITY_CLEAR_TASK`).
  The old chat screen is gone before the new one has drawn, and the empty
  see-through window shows the home screen, dimmed.
- App launch had the same flash and was patched for launch only: the window
  is painted solid while the chat loads and set back to see-through
  afterwards (`openedAtLaunch()` in `ChatActivity.onCreate`).

### Does anything depend on the see-through window?

Nothing found in the new client:

- **The chat surface already covers the whole screen** with a solid surface
  color (`expandable_window_root`, `expandable_window_background_24`). Once
  drawn, nothing shows through it.
- **The original app's "chat expands out of the list" animation** was the
  reason for the see-through style. The screen that started it
  (`ChatsListFragment` / `ChatListAdapter`) is no longer reachable: nothing in
  the new client opens it.
- **No overlay entry point:** the chat screen is not exported and has no
  assistant or shared-text launch, so it never opens on top of another app.
- **Blur behind the action menus** uses the window background as its starting
  color, but the full-screen chat surface covers it. Expected no visible
  change; confirm on device.
- **Rounded corners:** the chat surface has 24dp rounded corners. In the
  see-through window, the four screen corners show dimmed content behind the
  app; with a solid window they would show the window color. Confirm on device.
- **Notification taps** (hands-free, reply generation, image generation,
  summarizer) open the chat directly and can show the same brief see-through
  gap over whatever app was open. The fix covers them too.

### Shared style: what else uses `Theme.Transparent`

The style is shared with **Settings** and the **microphone and camera
permission screens**. The permission screens show a system prompt over the
chat and likely need the see-through window, so the shared style must not
change.

### As built (October 30 2026)

- The chat has its **own opaque window style**, derived from the current
  theme. Settings and the permission screens still use the translucent theme.
  Android therefore cannot expose the launcher while a chat is opening.
- The selected chat's screen, title, existing loading surface and drawer are
  created before its encrypted history and settings are read. Storage remains
  off the main thread, but it no longer holds back the first chat frame.
- A drawer selection opens that selected chat underneath the already-open
  drawer and starts the normal drawer-close animation on the first frame. It
  no longer waits for the drawer refresh or conversation read before beginning
  the pull-back.
- Once the encrypted conversation is ready, it is bound into that screen. No
  new loading state, wording or interaction was added.

---

## 3. Left-hand flicks closing the drawer (information only)

- The swipe-to-close setting has not changed since October 3 (swipe-to-close
  enabled while the drawer is open). No recent change touched it.
- Likely explanation, not confirmed on device: the drawer closes once sideways
  finger movement passes a small threshold, even during a mostly vertical
  flick. Normally the list claims the gesture first. When the main thread
  stutters (section 1), touch updates arrive in larger jumps, so the sideways
  part can cross the threshold first.
- Recommendation: change nothing now; re-check on device after the scrolling
  fix.
