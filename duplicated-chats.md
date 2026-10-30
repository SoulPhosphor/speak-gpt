# Duplicated Chats

Status: **investigation only, October 30 2026.** No code has been changed. The
owner has not approved a fix or a cleanup method yet; both are open decisions
below. This file keeps the findings so they are not lost between sessions.

## What the owner sees

Hundreds of chats appear more than once in the new client. Chats started in
the new client are not duplicated. The duplicated chats are the ones brought
over from the old client.

## Until this is fixed

**Do not run the old-client conversion again.** Each run adds another full copy
of the old-client chats. Restoring a backup made by the new client does not
create copies (see "What is not affected").

## Cause

Three facts combine:

1. **Old-client chats have no real ID.** The old client identifies a chat by a
   code made from its title (`Hash.hash(name)` in the old client's
   `ChatPreferences.kt`; new chats there also get a title code, never a UUID).
2. **The conversion invents a new random ID on every run.** When the new client
   converts an old-client package (`LegacyChatConversion.convert` →
   `ChatLogicalImportPlan.parse`), every chat whose ID is not already a UUID is
   given `UUID.randomUUID()` (`ChatLogicalImportPlan.kt`, the `legacyIdMap` /
   `allocateUuid` step). The same old chat gets a different ID each time the
   conversion runs.
3. **Restore recognizes an existing chat only by its ID.** `ChatMergePlanner.plan`
   looks each incoming chat up by ID (`currentById[source.chatId]`). If the ID
   is not on the phone, the chat is added as new. Conversation content is never
   compared across different IDs.

So the second conversion gave every old chat a new random ID, none of them
matched, and all of them were added again.

The October 18 note in `chat-storage-and-backup-plan.md` ("no additional
identity migration is needed") assumed one transfer. It does not hold for a
repeated transfer: nothing makes the conversion give a chat the same ID twice.

## What is not affected

- **New-client backups** (Recovery Backup) keep each chat's UUID, so restoring
  one matches existing chats by ID and skips identical ones.
- **Restoring the same converted file twice** does not duplicate, because the
  IDs are fixed inside that file. Only running the conversion again does.
- **Chats created in the new client** always had real UUIDs.

## Fix part 1: stop it from happening again (proposed, not approved)

Both changes are recommended together.

1. **Same ID every time.** Derive the new UUID from the chat's old-client code
   (a name-based UUID with a fixed namespace) instead of a random one, so the
   same old chat always converts to the same ID. Collisions inside one file must
   still be resolved deterministically (same approach as
   `ChatMergePlanner.allocateCopyId`, which already uses an attempt counter).
   Limitation: in the old client, renaming a chat that never had a stored ID
   changes its title code, so a chat renamed there between conversions still
   gets a different ID. Item 2 covers that case.
2. **Recognize copies by content.** Before adding an incoming chat as new,
   check whether a chat with an identical message history is already on the
   phone under a different ID, and treat it as the same chat (counted with the
   "identical, skipped" chats). This is what protects against the copies
   already on the phone, which have random IDs that item 1 cannot reproduce.
   - Compare a hash of each history first and then the full history, so
     hundreds of chats are checked quickly.
   - **Empty histories are never matched by content.** Two empty chats are not
     the same chat.

Fit with Feature 0 (`chat-storage-and-backup-plan.md`): stage 4 rebuilds
restore as a streaming path. Whichever lands first, the other must carry these
two rules; the content check must not require holding every history in memory
(hashes per chat are enough).

## Fix part 2: clean up the existing copies (open decisions)

### Finding the copies

- **Exact copies:** chats whose full message history is identical. Found
  reliably by the same hash-then-compare check. Empty histories excluded.
- **Copies that differ:** if a copy was continued (in the old client between
  conversions, or in the new client afterwards), one history is the other
  plus later messages. These are found by a "one history is the start of the
  other" check and need an owner rule (likely "keep the longer one").

### What must be checked before removing any copy

Other parts of the app refer to a chat by its ID. Removing the wrong copy
could break these links, so the keep/remove choice has to consider them, or
move them to the kept copy:

- Memory Assistant review state and memories tied to the chat ID.
- Generated images in the gallery tied to the chat ID.
- Bookmarks, pinned state, folder placement, and per-chat settings (companion,
  model, memory exclusion), which can differ between copies.
- The search index (derived; rebuilt, not a blocker).

### Required safety

- A preview listing each group of copies and which one would be kept, before
  anything is removed. Nothing is removed automatically.
- Removal through the existing chat delete path, never a new one.
- A fresh Recovery Backup before cleanup.

### Decisions the owner still has to make

1. Approve fix part 1 (both items, or item 1 only).
2. For copies that differ: keep the longer one, or ask per group.
3. Which copy is kept when copies differ in folder, pin, bookmarks, companion,
   or memory state, and whether those are merged onto the kept copy.
4. Where the cleanup lives (for example a one-time action in Settings), and its
   wording, which must be approved before code.

## Code locations

- `app/src/main/java/org/teslasoft/assistant/preferences/backup/portable/LegacyChatConversion.kt`
- `app/src/main/java/org/teslasoft/assistant/preferences/backup/portable/ChatLogicalImportPlan.kt`
- `app/src/main/java/org/teslasoft/assistant/preferences/backup/portable/ChatMergePlanner.kt`
- Old client (`main`, read only): `ChatPreferences.kt`, title-code IDs.
