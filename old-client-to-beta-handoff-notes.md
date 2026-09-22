# Old client → new client handoff: engineering notes

Status notes for coordinating the one-time data migration from the **old
client** (the `main` branch app) into the **new client** (the
`beta/new-client` branch app). These are working notes, not a product plan;
the owner directs scope and sequencing.

## 1. Boundary between sessions (important)

Two separate work streams, kept apart on purpose so neither accidentally edits
the other's branch:

- **Old-client export** — branch `handoff/old-client-export` (built from
  `main`). Produces a Recovery Backup package the new client can read.
  This stream **exports only**. It must never modify `main` or
  `beta/new-client`.
- **New client** — branch `beta/new-client`. Owns the import/restore side and
  the authoritative backup **format design**. This is the product being kept;
  its design leads. The export conforms to the format the new client defines,
  never the other way around.

**Sequencing rule:** when a new piece of data needs to cross, the new client
decides how it is represented and restored *first*; only then does the export
add the matching field to feed it.

## 2. What the old-client export produces today

The old client's existing manual **Recovery Backup** action now writes a
version-2 portable package (unchanged envelope/format/manifest) whose artifact
list was extended. Entry names and type strings are exactly what the new
client's reader maps to restore categories:

| Content | Entry name | Artifact type | State |
|---|---|---|---|
| Chats + per-chat settings | `chats.json` | `chats-json` | Unchanged; already accepted |
| Memories **and model rules** | `memory.db` | `sqlcipher-db` | Unchanged (model rules live in this DB) |
| Lorebooks | `lorebook.db` | `sqlcipher-db` | Unchanged |
| Profile-image **catalog only** | `user_images.db` | `sqlite-db` | Unchanged |
| Companions, glamours/personas, roleplay, activation prompts, system prompts | `companion_roleplay.zip` | `companion-roleplay-archive` | **Added** |
| Endpoints, favorites, provider/routing (no credentials) | `model_endpoint_settings.json` | `model-endpoint-settings` | **Added** |
| Global settings & preferences (no credentials) | `app_settings.json` | `app-settings` | **Added** |

**Out of scope by owner decision:** profile-image files and generated-image
files (and the generated-image catalog). Images are not worth migrating for
this owner. The catalog artifact above is pre-existing and left untouched.

## 3. Verified facts about the receiving (new client) side

Confirmed by reading `beta/new-client`, to save the new-client session time:

- `PortablePackageFormat.kt` is **byte-identical** between `main` and
  `beta/new-client`. The old client keeps the version-2 package/manifest; the
  new client accepts it and infers categories from artifact names/types
  (`PortableRestoreInventory.from(...)`). No new migration format is needed.
- `PortableChatRestorePlan.parse(...)` accepts **both** `chat-logical-v1` and
  `chat-logical-v2`. For v1 it treats folders as empty and does not require a
  `folders` array. The old client has **no chat-folder feature**, so its v1
  chat export is complete and restorable as-is.
- The companion archive format (`CompanionBackupFormat`) is identical on both
  branches: marker `companion-roleplay-backup`, `FORMAT_VERSION = 1`, manifest
  `backup.json`, images under `images/`. The old client's existing
  `CompanionBackupExporter.buildBackupZip(context, staged)` produces an archive
  the new client reads.
- The companion codec decode (`CompanionBackupCodec`) is **lenient**: it reads
  known keys via `optString`/`opt*` and ignores unknown ones. Additive fields
  in a future archive version will not break older/newer readers.
- Model rules are tables inside `memory.db` (`model_rules`,
  `model_rule_tags`, `model_rule_tag_links`, `model_rule_profiles`). They ride
  in the existing `memory.db` artifact — no separate export needed.
- Credential-free-by-construction artifacts: the model-endpoint codec's
  `Endpoint` model has no credential field (per-endpoint API keys are dropped
  at the mapping boundary); app settings use `AppSettingsPortabilityPolicy`,
  which denies api keys/tokens/secrets and runtime state.

## 4. OPEN ITEM — companion multi-prompt versions (needs the new client first)

**The gap (a real, pre-existing defect on both branches):** a companion
(`PersonaObject`) can hold multiple prompt versions in
`promptVariants: ArrayList<CompanionPromptVariant>` (each has `id`, `name`,
`text`, `isDefault`). But:

- `CompanionBackupData.CompanionProfileEntry` carries only a single `prompt`
  string.
- `CompanionBackupExporter` maps `prompt = p.prompt` (the default variant's
  text) and **drops `promptVariants`** — on `main` *and* `beta/new-client`.
- `CompanionBackupCodec` encodes/decodes only `prompt`.
- The new client's restore (`CompanionRoleplayRestoreManager`) does **not**
  write `promptVariants` back onto the persona.

So today, migrating keeps each companion's default/current prompt but loses its
alternate versions. The owner has confirmed companions have multiple versions
and they must be preserved.

**Why it can't be solved from the export alone:** the backup format has no slot
for the versions, and the restore side ignores them. This is an import-first
problem.

**What a full fix requires (to be designed on the new client, then mirrored in
the export):**

1. **Format:** add a per-companion `prompt_variants` array to the companion
   archive — each entry `{ id, name, text, isDefault }`. Additive and optional
   so older backups (no field) still restore as a single default prompt. A
   `CompanionPromptVariant` DTO already exists on both branches and has
   `toJson`/`fromJson`/`migrateFromSinglePrompt` helpers to reuse.
2. **New-client restore:** on import, if `prompt_variants` is present, write it
   to the persona's `_prompt_variants` store and keep `prompt` consistent with
   the default variant; if absent, fall back to
   `CompanionPromptVariant.migrateFromSinglePrompt(prompt, personaId)` (current
   behavior). Decide format-version handling on the new-client side.
3. **Old-client export (this branch, afterwards):** capture
   `p.promptVariants` into the archive using the same field shape the new
   client defined.

**Sequencing:** do steps 1–2 on `beta/new-client` first (a dedicated session
auditing the receiving side), then step 3 here so the shipment matches the
receiving dock.

## 5. First task for the new-client audit session

Before any export work on multi-prompts:

1. Audit the new client's companion import/restore path end to end
   (`CompanionBackupCodec` decode → `CompanionRoleplayRestoreManager` →
   `PersonaPreferences`/`_prompt_variants`), and confirm exactly how a restored
   companion's prompt(s) are written today.
2. Decide the receiving format for prompt versions (field name, shape,
   version/compat handling) driven by the new client's design.
3. Record that decision so the export can conform. Then this branch adds the
   matching capture.

## 6. Files changed on `handoff/old-client-export`

Added (new client codecs copied verbatim + a thin old-store adapter + tests):

- `.../backup/portable/AppSettingsPortableData.kt` (verbatim from new client)
- `.../backup/portable/AppSettingsPortableStore.kt` (capture/write only; restore
  `replace()` removed — export branch)
- `.../backup/portable/ModelEndpointPortableCodec.kt` (verbatim)
- `.../backup/portable/ModelEndpointPortableBackup.kt` (**adapter**: reads the
  old client's `ApiEndpointPreferences` + `FavoriteModelsPreferences` instead of
  the new client's `ModelEndpointStateGenerationStore`, which does not exist on
  `main`; drops API keys and favorites pointing at deleted endpoints)
- `.../backup/portable/PortableRecoveryLimits.kt` (verbatim)
- `.../test/.../portable/AppSettingsPortableCodecTest.kt`,
  `ModelEndpointPortableCodecTest.kt` (verbatim JVM tests)

Modified:

- `.../backup/portable/PortablePackage.kt` — added `TYPE_*` artifact-type
  constants (no format/envelope change).
- `.../backup/portable/PortableRecoveryWriter.kt` — added the companion,
  model-endpoint, and app-settings artifact blocks before inner-zip assembly.
- `.github/workflows/android-checks.yml` — `android-actions/setup-android`
  `v3 → v4` (v3 installs the removed `tools` SDK package and broke CI setup;
  this matches the fix already on `beta/new-client`).

## 7. Do-not list

- Do not modify `main` (read-only; the old client must keep working).
- Do not modify `beta/new-client` from the export session (avoid crossovers).
- Do not add a new migration format; keep the version-2 package.
- Do not export profile-image or generated-image files (owner decision).
- Do not add prompt-version export until the new client defines the receiving
  format.
