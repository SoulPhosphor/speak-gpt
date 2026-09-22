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
- The companion archive contract (`CompanionBackupFormat`,
  `CompanionBackupData`, `CompanionBackupCodec`, `PortablePromptVariant`) is
  byte-identical on both branches as of Beta commit
  `580da22cd33f605abe6a660307e7230bd67af1a6`: marker
  `companion-roleplay-backup`, `FORMAT_VERSION = 2`, manifest `backup.json`,
  images under `images/`. The old client's
  `CompanionBackupExporter.buildBackupZip(context, staged)` produces a
  version-2 archive the new client reads.
- The companion codec is lenient about most fields, but a version-2 archive's
  `prompt_variants` are read strictly (see §4). Version-1 archives are still
  accepted.
- Model rules are tables inside `memory.db` (`model_rules`,
  `model_rule_tags`, `model_rule_tag_links`, `model_rule_profiles`). They ride
  in the existing `memory.db` artifact — no separate export needed.
- Credential-free-by-construction artifacts: the model-endpoint codec's
  `Endpoint` model has no credential field (per-endpoint API keys are dropped
  at the mapping boundary); app settings use `AppSettingsPortabilityPolicy`,
  which denies api keys/tokens/secrets and runtime state.

## 4. RESOLVED — companion multi-prompt versions

Resolved by companion archive **version 2**, defined and proven on
`beta/new-client` at commit `580da22cd33f605abe6a660307e7230bd67af1a6`
(contract: `companion-roleplay-backup-plan.md` §3.1 on that branch), then
mirrored here.

Each `companion_profiles` entry keeps `prompt` and adds `prompt_variants`:

```json
"prompt": "<default variant text>",
"prompt_variants": [
  { "id": "<string>", "name": "<string>", "text": "<string>", "isDefault": <boolean> }
]
```

The new client rejects a version-2 archive as damaged unless, for every
companion: the array is present and nonempty; each variant has exactly those
four fields with those types; ids are nonblank and unique; exactly one
variant is the default; and `prompt` equals the default variant's text.
Blank names and text are allowed; order and ids are preserved exactly.

The old-client exporter (this branch) now copies every
`PersonaObject.promptVariants` entry — id, name, text, order — without
writing to live preferences. The default flag follows the app's existing
effective-default rule: if a stored list has no default (the app never
creates that state), every prompt is still copied and the first one is made
the default (owner ruling, September 22 2026). A companion with no variant
list is exported as one deterministic "Prompt 1" variant, exactly as the new
client reads a version-1 archive. `CompanionBackupExporterPromptVariantsTest`
checks the emitted manifest against the same synthetic fixture the new
client uses.

The old client's own companion restore is export-branch scope only and was
not changed.

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

Added for the version-2 companion prompt contract (copied verbatim from
Beta `580da22`):

- `.../backup/portable/PortablePromptVariant.kt`
- `.../test/.../portable/PortablePromptVariantTest.kt`
- `.../test/.../companion/CompanionBackupCodecTest.kt` (replaces the old copy)
- `.../test/.../companion/CompanionBackupExporterPromptVariantsTest.kt` (new,
  export-side contract check)

Modified:

- `.../backup/companion/CompanionBackupFormat.kt`, `CompanionBackupData.kt`,
  `CompanionBackupCodec.kt` — replaced with the Beta `580da22` versions.
- `.../backup/companion/CompanionBackupExporter.kt` — prompt-variant mapping
  (`profileEntries`, `portablePromptVariants`) identical to Beta; the old
  client's image handling is unchanged.

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
- Do not change the companion archive contract here; it follows
  `beta/new-client` (currently commit `580da22`).
