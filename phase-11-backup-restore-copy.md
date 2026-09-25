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

Per-category mode label: none. Each row shows the category name in the
canonical dropdown label style beside its checkbox, with the Merge/Replace
dropdown right-aligned (owner ruling, Sept 23 2026). Settings and
Preferences has no dropdown; it shows **Replace Only** right-aligned in the
description text style. Rows are listed
alphabetically on screen.

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
- Success title: **Restoration Complete** (no message; action **Okay**)
- Partial-success title: **Restoration Partly Successful**
- Failure title: **Restoration Failed**
- Failure message: **Nothing was changed.**
- Not-a-backup message (the chosen file is not a Recovery Backup package):
  **This file is not a valid recovery backup. Please try again with a valid file.**
- Failure reason with category: **%1$s could not be restored. Reason: %2$s**
- Failure reason without category: **Reason: %1$s**
- Completed-cleanup-pending title: **Restoration Complete**
- Completed-cleanup-pending message: **The selected data was restored, but cleanup could not finish. Choose Okay to finish recovery before starting another restore.**
- Rollback-failed title: **Restore Recovery Needed**
- Rollback-failed message: **The app could not restore all previous data after the restore failed. Some selected data may have changed. Choose Okay to retry recovery before using Backup & Restore again.**
- Pending-recovery message: **A previous restore still has recovery or cleanup work to finish. Complete it before continuing so the app can verify a consistent data state.**
- Pending-recovery action: **Try Recovery Again**
- Retry progress: **Recovering the previous data. Please wait. Do not close the app.**

Only a failure that fully preserved or restored the previous state may say
**Nothing was changed.** A completed restore with pending cleanup and a failed
rollback use their dedicated truthful messages above.

## Partly Successful Results (owner-approved, September 2026)

**Restoration Partly Successful** appears when at least one selected category
was restored and anything was not restored or a missing reference was found.
**Restoration Failed** is reserved for whole-package failures, fatal
write/rollback failures, and restores where nothing selected could be restored.

The dialog body is the applicable sentences, a blank line, then one problem per
line. Long lists scroll. The same lines are written as one Error Log entry per
restore.

- Not-restored sentence: **Some items could not be restored. Details have been saved to the Error Log.**
- Missing-reference sentence: **Some existing data has missing references. Details have been saved to the Error Log.**
- Whole category not restored (Stage 1 punctuation, kept in Stage 2):
  **%1$s could not be restored. Reason: %2$s**
- Missing chat image (dialog):
  **Chat "%1$s" — image generated %2$s with %3$s: "%4$s" — file missing**
  (%4$s is the first 10 words of the image prompt.)
- Missing chat image (Error Log): the same line ending
  **— file %5$s is missing**, naming the stored file.
- Missing identity picture: **%1$s "%2$s" — profile picture missing**, where
  %1$s is **Companion**, **Glamour** or **Roleplay Character**.
- Missing default picture: **Default User Image — profile picture missing**

The reports may show the first 10 words of an image prompt or of a memory, and
a generated image's stored file name, because the owner approved them to help
identify the affected item (September 2026). They still never show full
message or prompt text, credentials, database keys, or file paths.

### Record-level lines (Stage 2, approved wording, not yet built)

Formats:

- Skipped record: **Chat "Evening Walk" was not restored: its ID is invalid**
- Optional link dropped, target failed: **Campaign "Northreach" was restored without its world "Aldra", which could not be restored**
- Optional link dropped, target not in the backup, name known: **Companion "Aria" was restored without its lorebook "Old Tales", which is not in the backup**
- Optional link dropped, target not in the backup, name unknown: **Campaign "Northreach" was restored without its world, which is not in the backup**
- Chat restored without its folder: **Chat "Evening Walk" was restored without its folder "Work", which is not in the backup** (unnamed form when the folder name is unknown)
- Record without its own name, described by its parent: **A trigger phrase for lorebook entry "Tavern" was not restored: its entry could not be restored**
- Memory: named by its first 10 words, for example **Memory "She prefers tea in the morning and…" was not restored: …**
- Setting: named by its internal setting key, exactly as stored (no display names).
- Coupled settings: **Setting group "TTS provider and voice" was not restored: its value is not valid**

Reasons:

| Situation | Reason |
|---|---|
| Invalid ID | its ID is invalid |
| Chat ID does not match its saved data | its ID doesn't match its saved data |
| Duplicate ID with different data | another item has the same ID with different content |
| Invalid message ID inside a chat | one of its messages has an invalid ID |
| Duplicate message ID | one of its messages has the same ID as another message |
| Credential found | it contains a credential, which backups may not include |
| Invalid setting value | its value is not valid |
| Malformed field in a record | one of its details is missing or not valid |
| Unsupported setting type | this version can't restore this kind of setting |
| Unsupported format | it uses a format this version can't read |
| Missing or bad image | its image file is missing or doesn't match |
| Parent was in the backup but failed | its <parent> could not be restored |
| Parent is not in the backup | its <parent> is not in the backup |
| Unknown corruption (fallback only) | its data is damaged |

An exact duplicate record (same ID, identical content) produces no message.

## Conflict Report

- Title: **Restore Report**
- Intro: **The restore completed with these details:**
- Longer-chat note: **A longer version of %1$d chat was kept.**
- Longer-chats note: **Longer versions of %1$d chats were kept.**
- Protected-image note: **%1$d current image was kept because unselected data still uses it.**
- Protected-images note: **%1$d current images were kept because unselected data still uses them.**
- Action: **Okay**

Reports identify items by category and user-visible name when available. They
must never expose message text, prompt bodies, credentials, database keys, or
internal file paths.

The Restoration Complete or Restoration Partly Successful dialog appears
first. If the restore has conflict-report details, its **Okay** action opens the
separate Restore Report dialog.

## Protected Backup Unlock

- Title: **Unlock Recovery Backup**
- Message: **Use the Recovery Code, Recovery Key file, or password for this backup.**
- Recovery Code action: **Enter Recovery Code**
- Recovery Key action: **Choose Recovery Key File**
- Password action: **Enter Password**
- Left action: **Cancel**

The screen does not ask whether the file is encrypted. It reads that from the
selected package and shows unlock choices only when required.
