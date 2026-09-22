# Multi-prompt backup and restore — implementation plan

## Status and scope

This is the implementation plan for `beta/new-client`. The new client defines and proves the receiving contract first. Only after that contract is committed and green may `handoff/old-client-export` emit companion prompt variants in the same format.

This work must preserve every companion prompt variant, including its stable id, name, text, list order, and default status. It must also prove that the prompt systems already stored in the new client survive its normal portable backup and restore cycle.

This plan does **not** require a backup made from the owner's live data. Tests use synthetic fixtures. The original old-client data and any backup made from it remain untouched and retryable if a Beta import fails.

Media transfer is outside this prompt task. Do not add, remove, or redesign profile-image or generated-image behavior here, and do not treat the existing handoff's intentional media omissions as a defect.

## Owner-established boundaries

- Work on `beta/new-client`; never put this work on `main`.
- The new client's format and restore behavior lead. The old-client exporter conforms afterward.
- Companion multi-prompts must make the full old-client → new-client trip.
- The owner's old-client summarizer/compaction and Memory Assistant prompts are still defaults. The handoff exporter needs no special old-client prompt migration for them.
- New-client backups must preserve user-edited summarizer and Memory Assistant prompts.
- No on-device test using the owner's real data is a completion gate for this work.
- A failed restore must not alter the source backup or make it unusable for a later retry.

## Confirmed current implementation

### Companion prompts: real loss defect

A companion is stored by `PersonaPreferences` with:

- `<personaId>_prompt`: the legacy/default prompt mirror;
- `<personaId>_prompt_variants`: the ordered JSON list of `CompanionPromptVariant` values;
- each variant has `id`, `name`, `text`, and `isDefault`.

The companion archive does not currently represent that list:

- `CompanionProfileEntry` contains only `prompt`;
- `CompanionBackupExporter` copies only `PersonaObject.prompt`;
- `CompanionBackupCodec` reads and writes only `prompt`;
- `CompanionRestorePlanner` writes only `<personaId>_prompt`.

Therefore every non-default companion prompt is silently lost in both the standalone Companion & Roleplay Backup and the complete portable Recovery Backup.

### Summarizer/compaction prompts: data path already exists

The new client stores the current five-slot summarizer configuration in the global `settings` preferences. The prompt-related keys are:

- `summarizer_selected_slot`;
- `summarizer_slot_name_0` through `summarizer_slot_name_4`;
- `summarizer_slot_prompt_0` through `summarizer_slot_prompt_4`;
- `summarizer_slot_recency`;
- `image_summary_prompt`.

Related summarizer configuration such as `summarizer_on_new_chats`, model, endpoint, limits, and behavior is stored in the same portable global settings store.

`AppSettingsPortableStore` captures portable keys from `settings`, and `AppSettingsRestoreParticipant` restores the Settings and Preferences category by replacement with rollback staging. The portability policy excludes summarizer runtime state but does not exclude the slot names, prompt bodies, selection, recency, or image-summary prompt.

The current codec test uses the non-production example key `summarizer_prompt_1`. That does not prove the actual keys above remain portable and must be corrected by this work.

Untouched shipped defaults are code, not user data. If a default prompt has never been written to preferences, a backup should not freeze a copy of that shipped default. Once the user edits, names, selects, or reorders prompt slots, the stored values must round-trip exactly.

### Memory Assistant prompts: data path already exists

The new client currently has two independently editable analyzer prompts rather than a prompt-variant collection:

- `archivist_custom_prompt`: Associative Memory analysis;
- `archivist_lorebook_prompt`: Lorebook Memory analysis.

Both are global `settings` keys and already pass the Settings and Preferences portability policy. An empty value means use the shipped prompt in `ArchivistPrompt`.

The two prompts must remain separate because they require different output contracts. This work verifies their backup/restore behavior; it does not combine them or add a new Memory Assistant UI.

### Other prompt libraries

Activation Prompts and the ordered System Prompts library already ride in the companion archive and already restore through the companion restore transaction. They remain unchanged by this task.

## Receiving format: Companion archive version 2

Keep the outer portable package format unchanged. Change only the nested Companion & Roleplay archive from format version 1 to format version 2.

`CompanionBackupFormat.FORMAT_VERSION` becomes `2`. The reader must continue accepting version 1. A version above 2 remains a newer unsupported format.

### Shared portable prompt-variant model

Add a small, Android-free portable DTO and strict rules that can be reused when other systems gain variable prompt collections:

```kotlin
data class PortablePromptVariant(
    val id: String,
    val name: String,
    val text: String,
    val isDefault: Boolean
)
```

Place it with portable backup data rather than in an Activity. Provide explicit mapping to and from `CompanionPromptVariant`; do not make the backup decoder call the lenient UI/storage `fromJson` method, because that method can generate replacement ids.

The portable rules are:

- preserve variants in list order;
- require at least one variant;
- require every id to be nonblank and unique within its companion;
- require exactly one default variant;
- permit blank names and blank prompt text so intentional user content is not rejected;
- never generate, rewrite, deduplicate, sort, or rename variant ids while decoding a version-2 backup;
- require the companion's legacy `prompt` mirror to equal the default variant's text.

These are data-integrity rules, not new UI behavior.

### Version-2 JSON

Each object in `companion_profiles` keeps the legacy `prompt` field and adds `prompt_variants`:

```json
{
  "id": "p-example",
  "label": "Example",
  "prompt": "Default prompt text",
  "prompt_variants": [
    {
      "id": "variant-main",
      "name": "Main",
      "text": "Default prompt text",
      "isDefault": true
    },
    {
      "id": "variant-alt",
      "name": "Alternate",
      "text": "Alternate prompt text",
      "isDefault": false
    }
  ]
}
```

The legacy `prompt` field stays for clear backward meaning and for code paths that still consume the effective default. It is not a second independent value.

### Version-1 compatibility

When reading a valid version-1 archive with no `prompt_variants` field:

1. keep the existing `prompt` text;
2. synthesize exactly one variant;
3. use the existing deterministic legacy id rule based on `personaId + "_prompt_1"`;
4. name it `Prompt 1`;
5. mark it default.

This conversion exists only in the in-memory parsed representation. Reading or restoring an old backup never rewrites the backup file itself.

A version-2 archive must contain a structurally valid `prompt_variants` array for every companion. Invalid version-2 variant data is damaged input; it must not be repaired by minting new identities during restore.

## Exact new-client code changes

### 1. Portable data and codec

Modify:

- `app/src/main/java/org/teslasoft/assistant/preferences/backup/companion/CompanionBackupFormat.kt`
- `app/src/main/java/org/teslasoft/assistant/preferences/backup/companion/CompanionBackupData.kt`
- `app/src/main/java/org/teslasoft/assistant/preferences/backup/companion/CompanionBackupCodec.kt`

Add the reusable portable prompt-variant DTO/rules in a pure Kotlin file under the portable or companion backup package.

Changes:

- bump the nested companion archive writer/current reader version to 2;
- add `promptVariants` to `CompanionProfileEntry`;
- encode `prompt_variants` for version 2;
- parse versions 1 and 2 using the compatibility rules above;
- strictly validate version-2 ids, default count, order-preserving data, and the legacy-prompt mirror;
- keep unknown future format versions rejected as newer.

Do not bump the outer `PHOSBKP2` envelope merely because the nested artifact schema changed. `PortableRecoveryWriter` already records the nested archive's schema version from `CompanionBackupFormat.FORMAT_VERSION`.

### 2. New-client export

Modify:

- `app/src/main/java/org/teslasoft/assistant/preferences/backup/companion/CompanionBackupExporter.kt`

For each `PersonaObject`:

- map every `promptVariants` entry to the portable DTO without changing its id, name, text, default flag, or position;
- derive `prompt` from the single effective default;
- for a genuine legacy in-memory companion with no variant list, use the same deterministic one-variant conversion as the version-1 reader;
- do not write back to `PersonaPreferences` during export.

The standalone Companion & Roleplay Backup and the complete portable Recovery Backup already call this same exporter, so one exporter change must fix both. Do not build a second multi-prompt export path.

### 3. New-client restore

Modify:

- `app/src/main/java/org/teslasoft/assistant/preferences/backup/companion/CompanionRestorePlanner.kt`

For every restored companion, write both:

- `<personaId>_prompt_variants` using the existing `CompanionPromptVariant` storage JSON shape;
- `<personaId>_prompt` using the default variant's text.

The restore must preserve variant order and ids exactly. Version-1 input arrives at the planner already converted to one deterministic variant.

No special crash-journal schema is required. `CompanionSettingsPayload` already snapshots and journals the entire `personas` preference map, so the new key is included automatically in apply, rollback, and interrupted-restore recovery. Add tests proving that instead of adding a parallel journal field.

### 4. Selected-category merge and replace

Review and test:

- `CompanionCategoryPlanner.kt`
- `CompanionCategoryRestoreParticipant.kt`
- `UnifiedPortableRestore.kt`

Keep the existing companion-level semantics:

- Replace uses the incoming companion, including its complete variant list.
- Merge adds an incoming companion whose stable companion id is absent.
- If the same companion id exists with different content, the current companion wins and the existing conflict report records the collision.
- Do not attempt to merge individual prompt variants inside a conflicting companion in this task.

Because prompt variants become part of `CompanionProfileEntry` equality and the identity token, changes to any variant must count as changed companion content and as a source-generation change.

### 5. Current summarizer and Memory Assistant coverage

Do not duplicate these settings into the companion archive. Keep them in the replace-only Settings and Preferences category.

Update or add focused tests around:

- `AppSettingsPortabilityPolicy`;
- `AppSettingsPortableStore`;
- `AppSettingsPortableCodec`;
- `AppSettingsRestoreParticipant`.

The test fixture must use the actual production keys listed earlier, not `summarizer_prompt_1`. It must prove exact preservation of:

- all five summarizer slot prompt values;
- all five slot names;
- selected slot;
- slot recency;
- image-summary prompt;
- Associative Memory custom prompt;
- Lorebook Memory custom prompt;
- an intentionally empty custom prompt;
- Unicode, line breaks, and long prompt text within the existing settings artifact limit.

It must also prove that summarizer runtime summaries, folded state, errors, pending work, and similar operational keys remain excluded.

### 6. Future multi-prompt rule

When Summarizer or either Memory Assistant analyzer later changes from fixed/current storage to a variable prompt collection:

- use the shared portable variant shape and stable ids;
- store ordering explicitly;
- store default and current selection explicitly rather than inferring them from names;
- keep the Associative and Lorebook collections separate;
- put the collection in portable global Settings and Preferences unless product scope creates a distinct category;
- add its production keys to the prompt-portability contract test in the same change;
- keep shipped defaults in code and serialize only persisted user state;
- never derive identity from an editable prompt name.

The generic Settings and Preferences map can carry a JSON string for a future collection without a new outer package format. If its structured schema changes incompatibly, version that prompt collection's own JSON instead of silently changing its meaning.

## Required automated verification

No test in this section needs the owner's real data.

### Companion codec tests

Extend `CompanionBackupCodecTest` to cover:

- version-2 round trip of at least three ordered variants;
- exact preservation of ids, names, text, default flag, blank text, Unicode, and line breaks;
- version-1 single-prompt conversion with the deterministic variant id;
- rejection of a version-2 profile with a missing array;
- rejection of an empty array;
- rejection of blank or duplicate variant ids;
- rejection of zero or multiple defaults;
- rejection when `prompt` differs from the default variant text;
- continued rejection of format versions above 2.

### Export tests

Seed synthetic `PersonaPreferences` with a companion containing multiple variants, build the archive, parse it, and compare the complete ordered variant list.

The exporter test must assert that capture did not alter the live persona keys or mint replacement ids.

### Restore-planner and rollback tests

Extend `CompanionRestorePlannerTest` to assert:

- `_prompt_variants` is written;
- `_prompt` equals the restored default;
- the stored variant JSON preserves order and ids;
- a version-1 parsed profile restores as one variant;
- `CompanionSettingsPayloadCodec` preserves the variant preference key through journal serialization;
- restore rollback returns the old variant list exactly.

Update field-count assertions that currently expect only the single `_prompt` key.

### Merge/replace tests

Extend `CompanionCategoryPlannerTest` so:

- Replace takes the full incoming variant list.
- Merge of a new companion keeps its full variant list.
- A same-id companion whose only difference is a variant is reported as a conflict and the current complete companion remains.
- The identity/source token changes when a prompt variant changes.

### New-client export → restore → export round trip

Using only synthetic fixtures:

1. create a companion with multiple variants and stable ids;
2. export archive A;
3. validate and restore it into clean test preferences;
4. reload through `PersonaPreferences`;
5. export archive B;
6. compare the logical companion profile content after excluding only `app_version` and `exported_at`.

The comparison must include the legacy prompt mirror and every variant field in order. This is the proof that the new client can receive, use, and re-export the data the old client will send.

Run the same logical fixture through the complete portable Recovery Backup decode/restore path so standalone archive success does not hide an outer-package integration defect.

### Settings and Preferences round trip

Seed the real production summarizer and Memory Assistant keys in a test preferences store, capture with `AppSettingsPortableStore`, encode/decode, replace a clean store, and compare exact values. Confirm the replace participant's staged rollback restores the prior values.

## Documentation updates during implementation

Update `companion-roleplay-backup-plan.md` to describe archive version 2, `prompt_variants`, and version-1 conversion. Do not rewrite unrelated approved product behavior.

Add a short prompt-portability section to the relevant backup documentation listing the production summarizer and Memory Assistant keys and the future-extension rule. Do not put transient implementation history in `CLAUDE.md`.

## Required sequence

### Phase A — new client

On `beta/new-client`:

1. add failing version-2 codec, planner, merge/replace, rollback, and settings-key tests;
2. add the portable prompt-variant model and strict validation;
3. implement version-2 companion export and version-1/2 parsing;
4. write both prompt preference representations during restore;
5. prove standalone and complete-package synthetic round trips;
6. update the relevant backup documents;
7. run the focused unit/instrumentation tests available to CI and the normal Android build;
8. commit and verify Android Checks.

Do not begin the old-client prompt-variant export until this phase has a commit SHA and a green receiving contract.

### Phase B — old-client exporter

On `handoff/old-client-export`, starting from its then-current head:

1. copy/adapt the finalized portable prompt-variant DTO and version-2 companion archive contract from the exact green Beta commit;
2. update only the companion data model, codec, exporter mapping, format constant, and exporter tests needed to emit the contract;
3. map the old client's `PersonaObject.promptVariants` without changing live preferences;
4. keep the legacy `prompt` mirror equal to the default variant;
5. keep the outer portable package/envelope unchanged;
6. do not port new-client restore code into the export-only branch;
7. do not add special old-client summarizer or Memory Assistant prompt migration, because the owner has no customized old-client values there;
8. do not change media inclusion or exclusion as part of the prompt work;
9. update `old-client-to-beta-handoff-notes.md` from OPEN ITEM to the exact resolved version-2 contract and record the Beta source commit;
10. run the old-client unit tests/build and verify its emitted synthetic manifest against the same version-2 contract fixture.

Never merge this handoff branch into `main`.

## Completion criteria

This plan is complete only when:

- a Beta backup preserves and restores every companion prompt variant exactly;
- Beta still restores existing version-1 companion archives;
- standalone Companion & Roleplay Backup and complete portable Recovery Backup use the same corrected path;
- merge/replace, rollback, and interrupted-restore behavior include the variant list;
- real summarizer and Memory Assistant prompt keys are protected by tests in Settings and Preferences;
- the old-client exporter has an exact, green Beta contract to copy;
- no test or completion claim depends on the owner's live data;
- no source backup is modified during validation or restore;
- `main` remains untouched.
