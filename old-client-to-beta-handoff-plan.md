# Old client to Beta handoff — implementation instructions

## This is an implementation task

Do not produce another inventory, proposal, architecture document, or phased plan.

Read these instructions, inspect the named files only as needed to adapt them, implement the old-client exporter, run the normal build/tests, and commit the working code to the handoff branch.

Stop and ask the owner only if the old client genuinely lacks enough stored information to produce one of the required Beta artifacts. Ordinary compile errors, dependency differences, and old-versus-new storage adapters are implementation work, not reasons to return another plan.

## Goal

Make the old client create one portable Recovery Backup containing its existing non-secret user data in artifacts that `beta/new-client` already understands.

The owner will later create that backup from the real old-client data and try restoring it into the side-by-side Beta. No live-device restore is required in this implementation task. A failed later Beta restore does not endanger the data still held by the old client.

## Branch rules

1. Treat `main` as read-only.
2. Create `handoff/old-client-export` from the current `main`.
3. Commit all old-client exporter work only to that branch.
4. Never merge the handoff branch into `main`.
5. Do not merge `main` into `beta/new-client`.
6. Do not install the new-client code over the old client.
7. Do not change `beta/new-client` during the exporter implementation unless an actual package produced by the finished exporter exposes a real Beta incompatibility.

## Compatibility decision already made

Do not invent a new migration format.

Keep the old client's existing `PHOSBKP2` outer package and existing version-2 inner manifest behavior. The following file is already byte-identical between `main` and `beta/new-client`:

`app/src/main/java/org/teslasoft/assistant/preferences/backup/portable/PortablePackageFormat.kt`

The Beta intentionally accepts older version-2 packages and infers available restore categories from recognized artifact names and types when exact category declarations are absent. Therefore the old client does not need the Beta's complete restore engine, transaction system, manifest version 4, or semantic planner backported into it.

Extend the artifact list passed to the old client's existing:

```kotlin
PortablePackage.buildInnerZip(artifacts, createdAt, innerZip)
```

Then keep its existing envelope and reopen-and-verify flow.

## Existing old-client writer to modify

Modify:

`app/src/main/java/org/teslasoft/assistant/preferences/backup/portable/PortableRecoveryWriter.kt`

Keep its current staging, degraded-store refusal, encryption choice, envelope creation, output verification, failure cleanup, and these existing artifact blocks:

| Existing artifact | Existing capture |
|---|---|
| `memory.db` | `MemoryStore`, `DatabaseKeys`, and `RecoveryBackupManager.snapshotCipher` |
| `lorebook.db` | `LoreBookEncryption.obtainPassword` and `RecoveryBackupManager.snapshotCipher` |
| `user_images.db` | `RecoveryBackupManager.snapshotUserImageCatalog` |
| `chats.json` | `ChatLogicalSerializer` |

Extend this writer with the missing artifacts below.

## Required output artifacts

The finished old-client Recovery Backup must use these exact Beta-recognized names and types:

| Content | Entry name | Artifact type |
|---|---|---|
| Chats and per-chat settings | `chats.json` | `chats-json` |
| Memory and model-rule database | `memory.db` | `sqlcipher-db` |
| Lorebooks | `lorebook.db` | `sqlcipher-db` |
| Profile-image catalog | `user_images.db` | `sqlite-db` |
| Profile-image files | `profile_images/assets/profile_<sha256>.jpg` | `profile-image-asset` |
| Generated-image catalog | `generated_images/catalog.json` | `generated-images-catalog` |
| Generated-image files | `generated_images/assets/<validated filename>` | `generated-image-asset` |
| Companions, glamours, roleplay, activation prompts, and system prompts | `companion_roleplay.zip` | `companion-roleplay-archive` |
| Model endpoint definitions, favorites, and routing | `model_endpoint_settings.json` | `model-endpoint-settings` |
| Global Settings and Preferences | `app_settings.json` | `app-settings` |

A `memory.db` artifact represents both Memories and Model rules. A `companion_roleplay.zip` artifact represents Companions, Glamours, Roleplay, Activation prompts, and System prompts.

If a content group is genuinely empty, follow the relevant Beta exporter behavior. Do not invent fake records. Artifacts such as settings, chats, endpoint settings, and the companion archive may still validly describe an empty logical collection.

## Direct code changes

### 1. Complete profile-image portability

The old writer currently exports only `user_images.db`. Its own comment confirms that JPEGs are omitted. That is not usable for a side-by-side Beta because the Beta cannot see files in the old application's private directory.

Copy or adapt from `beta/new-client`:

`app/src/main/java/org/teslasoft/assistant/preferences/backup/portable/ProfileImagePortableBackup.kt`

After the existing `snapshotUserImageCatalog` succeeds, call the equivalent of:

```kotlin
ProfileImagePortableBackup.buildArtifacts(context, stagedCatalog)
```

Add the catalog artifact and every returned profile-image asset. If a catalog row requires an image that cannot be read or validated, fail the backup instead of publishing a catalog-only package.

Do not alter image assignments or live catalog rows.

### 2. Add generated images

Copy or adapt from `beta/new-client`:

- `GeneratedImagePortableBackup.kt`
- `GeneratedImagePortableCatalog.kt`

Use the old client's generated-image stores as the source and emit the exact Beta logical catalog plus every active or Gallery-only asset required by that catalog.

If the Beta exporter references new-client-only catalog classes that do not exist on `main`, write a narrow old-store adapter that produces the same `generated_images/catalog.json` schema. Do not change the Beta schema and do not migrate the old live store.

Add every artifact returned by the generated-image exporter. Treat a genuine empty gallery as empty; treat an unreadable required file as backup failure.

### 3. Add companions, prompts, and roleplay

Use the existing companion archive machinery. The Beta writer's required call is:

```kotlin
CompanionBackupExporter.buildBackupZip(
    context,
    stagedArchive,
    validateAssignedImages = true
)
```

The target entry is `companion_roleplay.zip` with type `companion-roleplay-archive`.

Start with the `CompanionBackupExporter`, format, codec, and validator already present on `main`. Bring over only Beta corrections needed to produce the current validated archive. The archive must carry companions, glamours/personas, roleplay characters and related records, activation prompts, system prompts, and its required assigned profile images.

Any missing required assigned image, unreadable Memory data, or unreadable Lorebook relationship must fail the backup visibly.

### 4. Add model and endpoint settings

The Beta target encoder is:

- `ModelEndpointPortableBackup.kt`
- `ModelEndpointPortableCodec.kt`

Produce `model_endpoint_settings.json` with type `model-endpoint-settings`.

The old client may not contain the Beta's `ModelEndpointStateGenerationStore`. If it does not, read the authoritative old-client endpoint, favorite-model, provider, and routing stores directly and adapt them into the Beta codec's data model.

Include definitions, IDs, labels, URLs, supported model configuration, favorite parameters, provider preferences, and routing choices handled by the Beta codec. Preserve stable IDs.

Never include API keys, bearer tokens, OAuth values, or other credentials.

### 5. Add Settings and Preferences

Copy or adapt these files from `beta/new-client`:

- `AppSettingsPortableData.kt`
- `AppSettingsPortableStore.kt`

Use the same `AppSettingsPortableCodec`, `AppSettingsPortabilityPolicy`, format `app-settings-v1`, and schema version 1. Emit `app_settings.json` with type `app-settings`.

Capture the already-mapped sources:

| Source | Required treatment |
|---|---|
| Plain `settings` SharedPreferences | Include portable user settings through the shared policy |
| Encrypted default `settings.` store through `SecurePrefs` and `AppTtsVoicePreferences.STORE_NAME` | Include allowed durable defaults |
| `storage_health` | Include only the existing explicit user-choice allowlist |
| `files/tts/saved_sources.json` | Include after the existing strict validation |
| `logit_bias_config` and `logit_bias_config_<stableId>` | Include catalog and matching configurations |

Reuse the exact Beta deny policy. Do not make a second list. Credentials, API keys, tokens, recovery keys, SAF locations, logs, caches, transient work state, runtime history, and downloaded models remain excluded.

### 6. Preserve chats and folders

The existing old serializer already exports chat UUIDs, histories, and typed per-chat settings while excluding the per-chat `api_key`.

Compare the old `ChatLogicalSerializer.serialize(context)` with Beta's `ChatLogicalSerializer.serializeV2(context)`. Port the V2 folder serialization so chat-folder organization is not discarded.

Preserve every existing chat UUID byte-for-byte. Do not generate replacement UUIDs during export.

### 7. Add the artifacts to the existing writer

Insert the new capture blocks before the writer assembles `inner.zip`.

Use the exact Beta artifact constants where they can be copied without dragging in restore-only code. Otherwise use the exact type strings in the Required output artifacts table.

Do not port `UnifiedPortableRestore`, restore participants, restore journals, UI category selection, or database replacement code into the old client. This branch exports only.

Keep the existing behavior that deletes the output and reports failure when finished-package verification fails.

## Existing reader behavior that this relies on

The Beta's current reader already provides the receiving side:

- `PortablePackage.kt` accepts version 2 and infers artifact-backed categories.
- `PortableRestoreInventory.from(...)` maps the artifact names/types above to categories.
- `PortableRecoverySemanticValidator.validate(...)` validates the logical content, category relationships, counts, and image closure.
- `UnifiedPortableRestore.kt` creates the selected-category restore participants.
- Packages without exact version-4 declarations do not invent empty categories.
- Packages without Settings and Preferences remain readable; the new handoff package will include `app_settings.json`, so that category will be offered.

Do not reimplement this receiver in the old-client branch.

## Old-client UI

Do not design a new migration wizard.

The old client already has the Recovery Backup creation flow. Make that existing action produce the comprehensive compatible package.

Only add or change visible text if the existing screen explicitly claims that the backup omits data now being added. Preserve the existing protected versus unencrypted choice and normal Save As flow.

## Data-safety rules

- Add no database migration.
- Make no writes to live user databases, preferences, catalogs, UUIDs, or image assignments.
- Write only to private staging and the user-selected backup destination.
- Do not delete or rename live assets.
- Do not silently omit an unreadable required artifact.
- On failure, remove the incomplete output and leave the old client's data untouched.
- Keep API credentials local.
- Keep `main` untouched.

## Proportionate verification

The owner does not yet have a handoff file in the Beta, so do not require a live restore for this task.

Required now:

1. Run the existing unit-test suite on `handoff/old-client-export`.
2. Build the old-client APK successfully.
3. Confirm the APK retains the original old-client application ID, not the Beta suffix.
4. Exercise package creation with test/fixture data if the repository already provides a usable fixture.
5. Reopen the produced test package with the existing package reader and confirm every produced artifact passes hash/extraction validation.
6. Run existing Beta package/semantic tests only if Beta code had to be changed.

Do not build a broad new instrumentation matrix. The real practical test comes after the exporter exists: create the backup from the old client, restore it into the side-by-side Beta, and correct any actual failure while the old client's data remains safe.

## Completion requirements

Do not finish with an audit report or another plan. Finish with implemented code on `handoff/old-client-export`.

The final report must provide:

- branch name;
- commit SHA;
- exact artifacts now exported;
- any required old-store adapters written;
- tests/build commands and results;
- APK or workflow artifact location;
- confirmation that `main` and `beta/new-client` were not merged or overwritten;
- any specific category that could not be exported, only if the stored old-client data genuinely lacks the information required by the Beta artifact.
