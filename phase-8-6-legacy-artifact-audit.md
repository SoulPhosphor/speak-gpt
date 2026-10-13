# Phase 8.6.1 — What the working build can actually export

**Branch:** `agent/phase-8-pre-main-safety`
**Source install:** the working pre-release, built from `main` (`0d16b3c3`), on SQLCipher 4.16.0
**Destination:** the side-by-side Beta, `com.soulphosphor.phosphorshines.beta`, on SQLCipher 4.17.0

This is the 8.6.1 audit: what the owner's current app can produce, what each
artifact carries, and every gap between it and the destination model. It records
findings only. No conversion code is written and no owner data is touched.

## The headline

The export side works. The import side did not exist for chats, and has since
been built.

`DatabaseRestoreManager.prepare` refuses `BackupType.CHATS` outright — it returns
`NO_APPROPRIATE_DATABASE` — and the automatic-entry listing filters chats out.
`ChatRestoreManager` has no reachable caller anywhere in the app except
`resumeIfPending` at startup, which only finishes a restore that already
committed. So the app could write `chats.json` into a portable package and had
no way to read one back.

**Status: closed.** `ChatLogicalImportPlan` and `LegacyChatConversion` supply
the missing read side, reached from Convert Legacy Chats in the Beta's backup
and restore screen. Neither refusal above was changed: the converter reads the
old package and saves a verified chat-recovery ZIP. It never reads, writes or
clears the live chat set and never touches `ChatRestoreManager`; the normal
Restore From Backup screen is the only place that applies the saved file.

## Two artifacts, not one

The working build produces two separate exports, and the migration needs both.
Both exist on `main`, so the owner can create them before anything changes.

### Portable Recovery Package (`PHOSBKP2`)

Unlocked by a Recovery Code the owner holds, not by the Android Keystore. That
is the only reason a different package can open it: the older
installation-bound artifacts are wrapped by a non-exportable Keystore key and
can never be restored onto another install.

| Entry | Form | Crosses versions cleanly? |
|---|---|---|
| `chats.json` | Logical JSON — chat list rows, every message of every history, each chat's per-chat settings map | Yes. Chats are not in SQLCipher at all. |
| `memory.db` | Raw SQLCipher file **written by 4.16.0**, with its passphrase carried alongside | **Unproven.** 4.17.0 has to open it. |
| `lorebook.db` | Same, or plaintext if the one-time encryption migration never ran | **Unproven.** Same reason. |
| `user_images.db` | Plain SQLite, profile image catalog only | Yes. |

### Companion & Roleplay Backup

A plain ZIP: a human-readable `backup.json` plus `images/profile_<hash>.jpg`.
No encryption, no Keystore binding, fully portable. Carries `companions`,
`companion_name_history`, `user_personas`, `roleplay_characters`, `worlds`,
`campaigns`, `party_members`, `campaign_party_members`, `card_entries`,
`rp_tags`, `rp_tag_links`, plus the `personas`, `activation_prompts` and
`system_prompts` preference files and the profile image files themselves.

It has a reachable export and restore path in `MemoryBackupRestoreActivity`, so
this half of the migration already works today. Memories are deliberately not in
it; they travel in `memory.db`.

## What neither artifact carries

Nothing here is lost data — it all still exists in the working install — but
none of it arrives in the Beta by itself.

| Not carried | Store | Consequence |
|---|---|---|
| Generated image catalog and the image files | `generated_images.db`, `images/` | The gallery does not migrate. Needs an owner decision before conversion, not after. |
| API endpoints and API keys | `api_endpoint`, `api` | Re-entered by hand. Small. |
| Favorite models and their sampling parameters | `favorite_models` | Re-entered by hand. |
| Logit bias configurations | `logit_bias_config` | Re-entered by hand. |
| Global app settings | `settings` | Re-entered by hand. |

Folders are not a gap: the working build has no drawer and no folder catalog, so
there are none to migrate.

## The version question, narrowed

Only two files in the whole migration are SQLCipher databases written by 4.16.0
and opened by 4.17.0: `memory.db` and `lorebook.db`. Chats, Companions,
roleplay data, personas and prompts all travel as JSON and are unaffected by the
SQLCipher version.

If 4.17.0 cannot open those two, the loss is Companion memory and the lorebook.
It is not the conversations.

## The smallest converter that avoids Phase 9

Phase 8.6.4 forbids this lane from creating a second unsafe restore engine, and
requires the Phase 9 replacement boundary first if the converter would replace a
live authoritative chat set or call `ChatRestoreManager.restoreFromArchive`.

The first version avoided Phase 9 by seeding only a Beta that had never held a
conversation. That path has been removed. The converter now avoids Phase 9 more
simply: it never writes chats into the installation at all. It builds a
chat-recovery ZIP in isolated storage, checks it with the real restore
validator, and hands it to the owner to save. Applying it goes through the
normal Restore From Backup screen.

`ConverterStaysOutsidePhase9Test` asserts this as a source contract rather
than leaving it to review: the conversion code may not restore, seed, clear or
delete chats, and may not touch the live chat storage.
