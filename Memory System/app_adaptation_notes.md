# App Adaptation Notes (schema v1.11)

Changes the existing app needs so the memory system can plug in. This is the app-side companion to the enforcer/librarian spec; it is repo-safe. Sections marked (user-specified) record UI decisions made by the app's owner and are not up for reinterpretation by the coding AI.

## Terminology mapping (from the real app UI)

The app's "Characters" screen is a container holding: Personas (the AI beings — Slate, Stolas, Monday...), Activation prompts, System message, Lorebooks. Mapping and renames:

- App **Personas → rename to "Companions"** (recommended): these map 1:1 to companion records in the store. Do NOT rename them to "Characters" — that recreates the collision with roleplay characters.
- New **"My Personas"** list: user_personas (presentation variants of the user — nothing currently defines the user in the app).
- New **"Roleplay Characters"** list: user-played fictional characters, kept fully separate from both of the above.
- **System message field**: user-authored, gospel like activation prompts. In tier 2 it is injected verbatim with the standing packet (see prompt assembly template); over time its contents naturally migrate into directives, but the field is always honored while non-empty.

## Bootstrap migration (first tier-2 enable)

When the full memory system is switched on for the first time, automatically create a companion record for every existing app persona: generate companion_id, set app_character_id, sync base_personality_mirror, empty relationship_notes, status **active** (user-created entries are canon — unlike Archivist drafts, these need no approval). Without this, day one starts with a store that doesn't know the user's existing cast.

## Tab structure (user-specified)

The app organizes conversation variables as tabs, each feeding quick settings. Additions:

- **User personas tab** — ALL presentation variants of the primary user ("casual me", "ball gown me") live here, in one place. Same person, same memories, different appearance text.
- **Roleplay characters tab** — SEPARATE from user personas, so fiction never mixes with primary personas. Contains only user-played fictional characters (the Mage, the Druid). Each entry shows two parts: the **definition** (traits, appearance, voice — user-editable; this is the user's field) and the **arc** (story-so-far — Archivist-maintained, displayed read-only with edits flowing through the memory editor if ever needed).
- Characters the Archivist registered retroactively (emergence) MUST appear in this tab even though the user never created them — they arrive with an Archivist-written definition the user can then reshape.

## Worlds UI (user-specified direction, recommended shape)

One **Worlds tab containing a list** — not a tab per world, which doesn't scale. Tapping a world opens its page: editable premise and rules, the characters played in it, and a browser of its world-scoped memories. **No structured worldbuilding fields** (no city/culture/species/language forms): cities, cultures, and languages are simply world-scoped memories in prose, created by the Archivist during play or written by hand on the world page. This matches how established roleplay tools handle world info (freeform keyed entries), except retrieval here is semantic rather than keyword-exact. Emergent worlds appear in the list automatically; hand-built worlds are just premise + optional starting memories.

## Conversation review markers (user-specified)

Every conversation displays its review_status as a small marker: **pending** (not yet in memory), **processed** (reviewed and stored), **excluded** (do-not-review). The user can toggle exclusion on any conversation, before or after it ends; excluded conversations are never read by the Archivist and contribute zero memories — for tiny conversations not worth storing, or anything the user wants kept out of memory entirely. Exclusion is reversible: flipping back to pending re-queues it. Excluded is a state, not a deletion; deleting a conversation remains a separate user action.

## Memory kill switch and ephemeral companions (user-specified)

- **Memory off**: a per-conversation quick-settings toggle plus a system-wide default (on/off) that bypasses the memory system entirely for experiments — the model gets only bare-bones app prompt materials, nothing injected from the store, and the conversation's transcript is auto-marked excluded (flip it back to pending later if the experiment turned out to matter).
- **Per-companion memory participation** (on the companion's settings page): full / global-only / none. Global-only is for throwaway companions (e.g., test personas like "fortune cookie"): they behave normally and global facts still get filed, but no relationship history, lore, or arc ever accumulates for them.

## Librarian model management

- Reuse the existing Whisper pattern (multiple downloadable/removable model variants, user picks what fits their phone) for the embedding model: offer EmbeddingGemma quantization variants; tier 2 requires one installed.
- **Rebuild memory index**: a settings button that (re)embeds all active memories with the currently installed embedding model. This same routine runs at first tier-2 enable (initial indexing) and automatically whenever the installed model's tag differs from the tag on stored vectors — so librarian migration is built in from day one, not deferred.
- Note for the coding flow: development is AI-driven via GitHub with GitHub Actions handling all compilation — keep every commit buildable and let CI be the test gate.

## Settings and quick settings

1. **Memory engine setting**: none / lore books / full system (the tiers in the enforcer spec). Low-RAM phones never load the embedding model.
2. **Quick settings stay gospel.** Temperature, top-p, and other sampling settings pass through to the chat API untouched; the memory system never overrides them. (These are unrelated to the librarian's retrieval settings, which may get their own quick-settings surface later.)
3. **Quick settings additions** (tier 2 only): selectors for active world, active roleplay character (from the roleplay tab), and active user persona (from the personas tab) — all optional, all nullable. Records created retroactively by the Archivist must appear here even though the user never created them in the UI.

## Characters area

4. Each app character maps to a companion record via app_character_id. On every character edit, fire a sync hook that refreshes the store's base_personality_mirror (one-way, app → store).
5. Draft companions: the store's 'draft' status needs a visible badge and an approve/activate action. Draft companions never appear as selectable in conversation.

## New areas

6. **User personas**: define presentation variants of the user ("casual me"). Same person, same memories, different appearance text handed to the model.
7. **Worlds and roleplay characters**: a space to view, edit, archive, and tear down worlds and the characters played in them — including ones the Archivist registered retroactively. Teardown (archive-all or delete-all for a world/character) is a user-only, one-action operation.

## Required memory UI (tier 2)

8. Memory browser/editor: search, view, add, edit, protect/unprotect, archive, delete any record. Proposals review screen (accept/reject with the plain-language summary). Run report screen with one-tap undo per auto-applied change.
9. Archivist trigger button ("process conversations") with a count of unprocessed transcripts.

## Transcript capture

10. Every conversation is recorded to the transcripts queue with: companion_id, world_id / roleplay_character_id / persona_id where known (null is fine — emergence is handled downstream), model_tag of the serving model, a snapshot of quick settings, timestamps, and review_status (pending / processed / excluded).

## Data care

11. Database encrypted at rest (SQLCipher). SQLite runs in WAL mode; run PRAGMA integrity_check on app launch and surface any failure loudly instead of continuing on a corrupt store.
11a. **Automatic rotating backups**: scheduled JSON export in schema shape to user-accessible storage (and optionally the user's own cloud folder), keeping the last several exports rotated — degradation protection is the export, not hope. One-tap manual export always available. Exports never include embeddings (rebuilt on import).
11b. **New-phone / computer migration is a first-class flow**: export on old device → install app on new device → import the export → the index rebuild routine runs automatically. Because the export is the plain JSON schema shape, any future computer-side system reads the same file directly; nothing about the store is phone-locked.
11c. **Ultimate recovery**: retained transcripts are the raw source of most memories — in a worst case the store can be substantially re-derived by re-running the Archivist over the transcript archive. Transcripts should therefore be included in exports.
12. Personal seeds, databases, exports, and transcripts are gitignored. The repo ships only the neutral template. (Details in README.)
