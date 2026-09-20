# Portable settings and preferences audit

The portable recovery category `settings` is a Replace-only snapshot stored as
`app_settings.json` (`app-settings`, schema 1). It participates in the existing
selected-category transaction and stages both the exact pre-restore snapshot
and the desired snapshot. A rollback therefore restores the same classified
configuration that existed before the transaction.

## Store classification

| Store | Classification | Portable treatment |
|---|---|---|
| Plain `settings` SharedPreferences | Portable user configuration by default | Include every supported typed value except the explicit exclusions below. This covers Appearance, Chat defaults, Summarizer, Memory Assistant, Voice and Speech, alerts, errors, logging policy, image settings, gallery presentation, and future durable preferences. |
| Encrypted default `settings.` SharedPreferences | Portable user configuration by default | Include selected TTS engine/voice/model and other durable defaults. Exclude credentials and runtime/history fields. Per-chat `settings.<chatId>` stores remain owned by the Chats category. |
| `files/tts/saved_sources.json` | Portable user configuration | Validate and include the complete credential-free saved TTS source and provider-routing document. |
| `logit_bias_config` and `logit_bias_config_<stableId>` | Portable user configuration | Include the validated catalog and every stable-ID configuration store. |
| User-choice subset of `storage_health` | Portable user configuration | Include backup protection/frequency and readable-backup format/scope/category choices. |
| Remaining `storage_health` keys | Transient, device-specific, or bookkeeping | Exclude destinations and SAF URIs, enabled/status/history timestamps, failure state, health state, snapshots, migrations, and journals. |
| `api`, credential fields in `api_endpoint`, OAuth/session stores, Keystore material | Secret or credential | Never include. Endpoint definitions remain credential-free in their existing category. |
| Companion, prompt, roleplay, memory, lorebook, endpoint/favorite, chat, and image stores | Content already handled by another category | Do not duplicate in Settings and Preferences. In particular, `system_message` stays owned by System Prompts. |
| Logs and diagnostic history | Historical content, not configuration | Exclude. Durable logging and alert preferences in `settings` are included. |
| Local Whisper/embedding/model files | Large redownloadable asset | Exclude binaries. Stable selected model IDs in preferences are included. |
| Previous/last-known-good/unavailable voice records, playground text, summarizer working state | Transient runtime state | Exclude. |

## Compatibility and safety policy

- Preference values retain their Android type: string, boolean, int, long,
  float, or string set.
- General settings are included by default. Secret-shaped keys and the explicit
  runtime/duplicate keys are denied during both export and import.
- The mixed-purpose `storage_health` file uses an explicit allowlist because
  most of that file is device-bound or operational bookkeeping.
- JSON object shapes, schema versions, key uniqueness, saved TTS routing, logit
  catalog IDs, artifact type/name, decoded size, and manifest counts are
  validated before restore.
- Unknown or malformed payload fields fail validation. They are never silently
  interpreted as a setting.
- Restore never clears credentials or device-specific state because Replace
  removes and rewrites only keys classified as portable.
- Manifest version 4 adds the category. Versions 2 and 3 remain readable;
  backups without Settings and Preferences simply do not offer that category.

When adding a persistent user preference, place it in the general `settings`
store when appropriate so it follows the portable-by-default path. Any new
store or exclusion must be classified here and covered by policy/codec tests.
