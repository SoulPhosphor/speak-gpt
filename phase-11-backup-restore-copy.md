# Phase 11 Backup & Restore Copy

**Status:** Owner-authorized wording record, September 8, 2026.

This file contains the user-facing wording for the unified portable Recovery
Backup and Restore Data flow. Labels and actions use Title Caps. Explanatory
text uses sentence case. Cancel is always the farthest-left action.

## Settings Navigation

- Row: **Backup & Restore**
- Subtitle: **Back up and restore chats, memories, settings, and user-created data.**

Place this row immediately above **Alerts, Errors & Logs** on the main Settings
screen. Remove the old Memory Manager row so the destination has one permanent
entry point.

## Screen and Sections

- Screen title: **Backup & Restore**
- Section: **Backup Status**
- Section: **Database Integrity**
- Section: **Backup**
- Section: **Restore Data**

Existing Automatic Backups, Recovery Backup, and Human-Readable Chat Backup
copy remains unchanged unless this file explicitly replaces it.

Temporary migration action at the end of Backup: **Convert Legacy Chat Backup**

This action remains visible until the owner's pre-change installation has been
safely migrated. Its existing dialog wording may remain unchanged during Phase
11.

The existing **Type of Database to Restore** selector and **Restore Database**
button remain at the bottom of Restore Data, beneath **Restore From Backup**.

## Restore Categories

- **Chats** — Chats, folders, and the generated images used in those chats.
- **Generated Images** — The complete generated-image gallery, including images whose original chats were deleted.
- **Companions** — Companions and the pictures assigned to them.
- **Glamours** — Glamours and the pictures assigned to them.
- **Roleplay** — Roleplay data and assigned Roleplay Character pictures. Glamours are not included.
- **Avatar/Profile Images** — The complete avatar-image gallery, including unused images. Image assignments are not changed.
- **Activation Prompts** — Saved Activation Prompts and their stable identities.
- **System Prompts** — Saved System Prompts and the current selection.
- **Model & Endpoint Settings** — Endpoint and model definitions, favorites, model settings, and preferred provider routing. Credentials are not included.
- **Model Rules** — Model Rules, their tags, and their stable endpoint and model links.
- **Memories** — Saved memories and their supported relationships.
- **Lorebooks** — Lorebooks, entries, triggers, and their supported relationships.

Per-category mode label: **Restore Mode**

When Model & Endpoint Settings is selected: **Credentials are not restored or removed. Existing credentials stay on this device. Newly restored endpoints need credentials before use.**

Mode choices:

- **Merge**
- **Replace**

## Missing Categories

This dialog is useful when the user chooses an older Recovery Backup created
before one or more current categories existed, or another valid backup format
that never carried those categories. It lets the user deliberately recover the
usable categories without pretending the missing data was restored.

- Title: **Some Selected Data Is Not in This Backup**
- Message: **This backup does not contain: %1$s. You can cancel and change your selections, or restore the selected categories that are available.**
- Left action: **Cancel**
- Right action: **Restore Available Categories**

Nothing is removed from the selection without the right action being chosen.

## Folder Name Collision

- Title: **Folder Name Already Exists**
- Message: **The backup contains a folder named “%1$s,” but the current folder with that name has a different identity. Choose how to restore its chats.**
- Left action: **Cancel**
- Middle action: **Merge**
- Right action: **Create New Folder**

If Create New Folder needs a different name:

- Title: **Name Restored Folder**
- Input label: **Folder Name**
- Left action: **Cancel**
- Right action: **Create Folder**
- Duplicate-name error: **A folder with this name already exists.**
- Blank-name error: **Enter a folder name.**

## Confirmation

- Title: **Restore Selected Data?**
- Replace message: **Replace will remove the current data in these categories and restore the backup versions: %1$s. Unselected categories will not be changed.**
- Merge message: **Merge will add backup data to these categories without removing current items: %1$s. Identity conflicts will keep the current item and appear in the restore report.**
- Mixed message: **This restore will use the selected mode for each category. Unselected categories will not be changed. Review the category list before continuing.**
- Left action: **Cancel**
- Right action: **Restore**

## Progress and Results

- Progress: **Restoring selected data. Please wait. Do not close the app.**
- Success title: **Restore Complete**
- Success message: **The selected data was restored.**
- Success with report: **The selected data was restored. Some current items were kept because their identities conflicted with different backup content.**
- Failure title: **Restore Failed**
- Failure message: **Nothing was changed.**
- Failure reason: **Reason: %1$s**

## Conflict Report

- Title: **Restore Report**
- Intro: **These current items were kept because the backup used the same identity for different content:**
- Longer-chat note: **A longer version of %1$d chat was kept.**
- Longer-chats note: **Longer versions of %1$d chats were kept.**
- Protected-image note: **%1$d current image was kept because unselected data still uses it.**
- Protected-images note: **%1$d current images were kept because unselected data still uses them.**
- Action: **Okay**

Reports identify items by category and user-visible name when available. They
must never expose message text, prompt bodies, credentials, database keys, or
internal file paths.

## Protected Backup Unlock

- Title: **Unlock Recovery Backup**
- Message: **Use the Recovery Code, Recovery Key file, or password for this backup.**
- Recovery Code action: **Enter Recovery Code**
- Recovery Key action: **Choose Recovery Key File**
- Password action: **Enter Password**
- Left action: **Cancel**

The screen does not ask whether the file is encrypted. It reads that from the
selected package and shows unlock choices only when required.
