# Old client to Beta backup handoff plan

## Goal

Add a focused handoff path that lets the installed old client export its existing user data into a portable backup that `beta/new-client` can restore.

The old client remains the safe source of truth while the Beta restore is tried. No live-device test is required during implementation. The first real restore can fail without endangering the old client's data.

## Branch boundaries

- `main` is read-only and remains the old client.
- This plan lives on `beta/new-client`.
- Old-client exporter work must be done on a separate branch created from the current `main`, such as `handoff/old-client-export`. It must never be merged into `main`.
- Any importer correction belongs only on `beta/new-client`.
- Do not merge `main` into `beta/new-client`; the commit unique to `main` intentionally reverts the new client.
- Do not install the new-client code over the old client merely to gain access to its private data.

## Confirmed current state

Both clients use the same `PHOSBKP2` portable-package envelope, and the Beta reader intentionally accepts older manifests. A backup made by the current old client can therefore be opened by the Beta when its artifacts are otherwise valid.

The current old-client recovery writer exports only:

- `chats.json`, including per-chat settings other than credentials;
- `memory.db`;
- `lorebook.db`;
- `user_images.db`, which is only the profile-image catalog.

It does not put the following into the unified recovery package:

- the actual profile-image files;
- generated-image catalog and image files;
- companions;
- glamours/personas;
- roleplay data;
- activation prompts;
- system prompts;
- model endpoint definitions, favorites, and routing preferences;
- global Settings and Preferences.

The side-by-side Beta has Android application ID `com.soulphosphor.phosphorshines.beta`. It cannot read the old client's private preferences, databases, keys, or files directly.

The Beta settings work adds manifest version 4 and a Replace-only `settings` category. Versions 2 and 3 remain accepted, and a package without Settings and Preferences leaves that category unavailable rather than inventing empty settings.

## Reviewed code map — do not rediscover

The following connections were verified directly against the current branch contents before this plan was written.

### Shared package envelope

- Old client: `app/src/main/java/org/teslasoft/assistant/preferences/backup/portable/PortablePackageFormat.kt`
- Beta: the same path on `beta/new-client`
- The file is byte-identical on both branches at the time of review.
- Both use the eight-byte `PHOSBKP2` magic and outer format version 2.
- `PortablePackage.envelope(...)` is therefore already the common outer transport. The handoff does not need a new envelope or a separate file type.

### Existing old-client writer on `main`

The current export path is:

- `app/src/main/java/org/teslasoft/assistant/preferences/backup/portable/PortableRecoveryWriter.kt`
- It builds its artifacts in private staging, calls `PortablePackage.buildInnerZip(...)`, envelopes the ZIP, then reopens and validates the finished file before reporting success.
- Keep that workflow and extend its artifact list.

Its current artifact connections are:

| Artifact | Existing old-client capture path | What Beta infers |
|---|---|---|
| `memory.db` | `MemoryStore.isProvisioned` + `DatabaseKeys.getOrCreate` + `RecoveryBackupManager.snapshotCipher` | Memories and Model rules |
| `lorebook.db` | `LoreBookEncryption.obtainPassword` + `RecoveryBackupManager.snapshotCipher` | Lorebooks |
| `user_images.db` | `RecoveryBackupManager.snapshotUserImageCatalog` | Profile images, but catalog alone is incomplete |
| `chats.json` | `ChatLogicalSerializer.serialize(context)` | Chats and their per-chat settings |

The old writer currently calls:

```kotlin
PortablePackage.buildInnerZip(artifacts, createdAt, innerZip)
```

It supplies no exact category declarations. Beta treats that as a compatible version-2 inventory and infers only the categories represented by recognized artifacts.

### Existing chat connection

- Old client serializer: `app/src/main/java/org/teslasoft/assistant/preferences/backup/portable/ChatLogicalSerializer.kt`
- It serializes chat list/history plus each chat's typed settings.
- It explicitly excludes the legacy per-chat `api_key`.
- Beta's current writer calls `ChatLogicalSerializer.serializeV2(context)`, which also carries the current folder representation.
- The handoff implementation should port/use the V2 chat serialization needed to preserve folders rather than discarding folder organization.

### Exact Beta exporter connections to backport selectively

The current Beta writer already demonstrates how every missing artifact is built:

`app/src/main/java/org/teslasoft/assistant/preferences/backup/portable/PortableRecoveryWriter.kt`

Use these existing connections as the implementation checklist:

| Handoff content | Current Beta capture call | Produced artifact |
|---|---|---|
| Profile catalog and all profile JPEGs | `ProfileImagePortableBackup.buildArtifacts(context, stagedCatalog)` after `snapshotUserImageCatalog` | `user_images.db` plus profile-image assets |
| Generated-image catalog and all active/Gallery-only assets | `GeneratedImagePortableBackup.buildArtifacts(context, staging)` | generated-image catalog plus assets |
| Companions, glamours, roleplay, activation prompts, and system prompts | `CompanionBackupExporter.buildBackupZip(context, staged, validateAssignedImages = true)` | `companion_roleplay.zip` |
| Credential-free model endpoints, favorites, provider preferences, and routing | `ModelEndpointPortableBackup.write(context, staged)` | `model_endpoint_settings.json` |
| Global Settings and Preferences | `AppSettingsPortableStore.write(context, staged)` | `app_settings.json` |
| Chats and folders | `ChatLogicalSerializer.serializeV2(context)` | `chats.json`, schema 2 |

Do not blindly copy the whole Beta writer into the old client. Backport only the serializers, codecs, and asset collectors required for export, using the old client's existing stores.

### Settings and Preferences connection already mapped

The Beta implementation is:

- `AppSettingsPortableData.kt`
  - `AppSettingsPortableCodec`, format `app-settings-v1`, schema 1;
  - `AppSettingsPortabilityPolicy` shared export/import classification.
- `AppSettingsPortableStore.kt`
  - captures portable values;
  - writes `app_settings.json`;
  - replaces only portable keys during Beta restore.
- `AppSettingsRestoreParticipant.kt`
  - stages the exact current and desired settings snapshots for rollback.

The captured old-client sources are already known:

| Source | Portable contents |
|---|---|
| Plain `settings` SharedPreferences | portable user configuration by default, subject to the shared deny policy |
| Encrypted default `settings.` store obtained through `SecurePrefs` / `AppTtsVoicePreferences.STORE_NAME` | durable default TTS/voice/model selections and other allowed defaults |
| `storage_health` SharedPreferences | only the explicit backup/readable-backup user-choice allowlist |
| `files/tts/saved_sources.json` | validated credential-free saved TTS sources and routing |
| `logit_bias_config` and `logit_bias_config_<stableId>` | validated catalog and individual configurations |

The portability policy already denies credential-shaped keys, `api_key`, the duplicate `system_message`, transient summarizer/runtime state, SAF locations, operational history, logs, caches, and downloaded model binaries. The old-client exporter should reuse this exact codec and policy rather than implement a second settings filter.

### Beta manifest and reader connection

The current Beta reader is intentionally backward-compatible:

- `PortablePackage.kt`
  - current `MANIFEST_VERSION = 4`;
  - version 3 is accepted as the pre-Settings exact manifest;
  - version 2 is accepted conservatively and its categories are inferred from recognized artifacts when no exact declaration exists.
- `PortableRestoreInventory.from(...)`
  - maps artifact types/names to available restore categories.
- `PortableRecoverySemanticValidator.validate(...)`
  - parses every artifact, reproduces category counts, and checks cross-category references and required assets.
- `UnifiedPortableRestore.kt`
  - builds the actual selected-category restore participants.
- `PortableRestoreSelectionPlan.kt`
  - distinguishes requested-but-missing categories and supports Restore Available Categories.

The Beta writer obtains exact declarations by:

1. converting the staged artifacts to `ValidatedArtifact`;
2. calling `PortableRestoreInventory.from(...)`;
3. calling `PortableRecoverySemanticValidator.validate(...)`;
4. calling `PortableRecoverySemanticValidator.declarations(...)`;
5. passing those declarations to `PortablePackage.buildInnerZip(...)`.

The old-client handoff writer should follow the same sequence so the file it publishes is already a normal current Beta recovery package.

### Confirmed profile-image incompatibility in old packages

This is the specific existing handoff defect, not a hypothetical concern:

- The old writer's own comment states that `user_images.db` is a catalog-only snapshot and that its JPEGs are not backed up.
- The Beta writer adds `ProfileImagePortableBackup.buildArtifacts(...)` and includes every valid content-addressed gallery JPEG.
- The Beta semantic validator requires catalogs and referenced assets to form a closed, valid set.
- Because the side-by-side Beta has a different Android application ID, the old client's private JPEG files are not present in the Beta sandbox.

Therefore an old package containing a non-empty profile catalog can fail Beta semantic validation even though its chats and databases are structurally readable. The handoff exporter must include the profile assets; this cannot be deferred to the later manual restore attempt.

### Existing verification relevant to this handoff

The settings change currently has codec/package/semantic/UI-contract coverage in:

- `AppSettingsPortableCodecTest.kt`;
- `PortablePackageTest.kt`;
- `PortableRecoverySemanticValidatorTest.kt`;
- `BackupRestoreScreenContractTest.kt`.

These tests establish payload validation and manifest compatibility. They do not replace the single handoff compatibility fixture required later in this plan.

## Phase 1: Inventory the old client's handoff sources

Read the current `main` implementation and make one concrete source map for every Beta restore category.

For each category, record:

- the authoritative old-client store or files;
- the existing old-client exporter or serializer that can be reused;
- any Beta codec that must be backported only for export;
- stable IDs and references that must be preserved;
- the record-count calculation;
- whether the category can be explicitly empty;
- asset files that must accompany its catalog;
- excluded credentials or device-specific values.

The inventory must cover all current Beta restore categories:

1. Chats
2. Generated images
3. Companions
4. Glamours
5. Roleplay
6. Profile images
7. Activation prompts
8. System prompts
9. Model endpoint settings
10. Settings and Preferences
11. Model rules
12. Memories
13. Lorebooks

Do not assume that a database catalog is portable when its referenced files are absent.

## Phase 2: Define one compatible handoff package

Use the Beta's current portable artifact names, codecs, category declarations, and manifest rules. Do not create a second permanent transfer format.

The handoff backup must:

- use the existing `PHOSBKP2` envelope;
- use the Beta's current exact category manifest when feasible;
- declare every category as artifact-backed or explicitly empty;
- preserve chat and content UUIDs byte-for-byte;
- include complete catalogs and their referenced image assets;
- contain category counts the Beta's semantic validator can reproduce;
- preserve the old client's protected or unencrypted backup choice;
- exclude API keys, tokens, recovery/database keys as user data, SAF locations, caches, logs, temporary state, and downloaded model binaries;
- be validated by the same logical readers before it is published.

Database encryption material required solely to decode a protected recovery artifact continues to follow the existing recovery-package design. It is not restored as an API credential or user setting.

## Phase 3: Add export-only handoff support to the old client

Work from a new branch based on the exact current `main`.

Extend the old client's existing `PortableRecoveryWriter` rather than building a parallel backup screen and format.

Requirements:

- Keep the old client UI and storage model.
- Add no database migration.
- Do not rename, rewrite, repair, or reseed live IDs.
- Read live data and write only to private staging plus the user-selected output file.
- Reuse existing serializers where they already produce the Beta artifact.
- Backport only the capture/codec code needed for missing categories.
- Include actual profile and generated image bytes, not only database rows.
- Include the new Settings and Preferences payload using the same schema and portability policy as `beta/new-client`.
- Validate the complete package before reporting success.
- If any required category cannot be read consistently, fail the handoff backup instead of silently omitting it.
- Keep the ordinary old-client data intact after both success and failure.

The branch build must retain the original old-client application ID and use a signing identity compatible with the installed old client so it can update that installation without clearing its data.

## Phase 4: Confirm Beta compatibility

Use the package produced by the old-client exporter as the contract.

On `beta/new-client`:

- verify the envelope, manifest, category inventory, artifacts, counts, and references are accepted;
- verify every included category appears on the restore screen;
- verify explicitly empty categories are distinguished from absent categories;
- verify Settings and Preferences appears as Replace-only;
- verify credentials and device-specific values are neither present nor cleared;
- change Beta production code only if the handoff package exposes a real incompatibility.

If the handoff exporter emits the existing current format correctly, this phase may require no Beta production change.

## Proportionate automated verification

This work does not require a large new test matrix or a live-device migration before a usable backup exists.

Required automated checks are limited to:

1. the old-client handoff branch compiles;
2. `beta/new-client` compiles;
3. one representative handoff package produced through the old-client export path is accepted by the Beta package and semantic validators;
4. that package proves all thirteen categories are artifact-backed or explicitly empty;
5. the fixture includes representative UUID references and image assets;
6. a Settings and Preferences payload is accepted while a credential-smuggling payload is rejected;
7. an induced export failure does not publish a partial handoff package.

Existing backup/restore tests remain in place; do not duplicate them.

## Later practical restore attempt

After the exporter build exists:

1. Keep the installed old client and its data.
2. Create the existing recovery backup as an extra fallback.
3. Install the compatible old-client handoff build without clearing app data.
4. Create one full handoff backup.
5. Install or open the side-by-side Beta.
6. Restore the handoff backup into Beta.
7. Inspect representative chats, memories, identities, images, model settings, and detailed preferences.
8. If the restore fails, keep using the untouched old-client data, record the exact failure, and correct the exporter or Beta reader.
9. After a successful restore, create a fresh Beta recovery backup.

The old client is not retired or uninstalled as part of this work.

## Completion criteria

This handoff is complete when:

- the old-client branch can create one verified comprehensive portable backup from existing old-client data;
- the backup uses the Beta's supported format rather than a separate migration format;
- Beta can inspect it and offer every included or explicitly empty category;
- no old-client live data is changed by export;
- `main` remains unchanged;
- the owner has a backup file that can be used for the first practical Beta restore attempt.
