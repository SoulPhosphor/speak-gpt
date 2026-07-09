# Memory Settings Reorganization (owner-sanctioned spec, July 9 2026)

*The owner had an AI write this up and handed it over in chat. It governs the
split of the old Memory Settings screen into **Memory Controls** (normal
users), **Memory Assistant Advanced Settings** (AI extraction tuning), and
**Advanced Memory Settings** (diagnostics/repair). Its own boundary rule
applies: no extra fields beyond what's listed; anything unclear gets an owner
ruling BEFORE implementation. Where wording is given, use it verbatim
(title-style caps for headings/rows, sentence-style for helper text).*

**Key wording law: the user-facing name is "Memory Assistant" — never
"Archivist" in UI unless explicitly approved.** (Archivist stays fine as the
internal/code name.)

**Documentation note (owner):** the old "NO Advanced screen" rule referred to
the cancelled Memory Browser hub/navigation design. It does NOT apply to
Memory Assistant Advanced Settings or Advanced Memory Settings — do not
remove or block these screens because of that note.

---

## 1. Memory Controls (page title: "Memory Controls")

Top description: "Choose how memory works in chats, roleplay, backups, and
memory review." Most-changed settings near the top, backups lower,
destructive actions at the bottom. No developer/debug terms (seed, bootstrap,
row counts, entities, directives, modes, raw database status) on this page.

**Section: Memory Defaults**
- Toggle **"Use Memory in New Chats"** — "Turn memory on by default for new
  chats. You can still change this per chat."
- Toggle **"Allow Companion Memories in Roleplay"** — "Let the active
  companion's approved memories be eligible during roleplay. Off keeps
  companion memories out of roleplay unless another rule allows them."

**Section: Memory Assistant**
- Row **"Memory Assistant"** — "Find possible memories in finished
  conversations, then review them in Pending before saving."
- Row **"Maximum Suggestions Per Conversation"** — toggle + number field,
  default Off (Off = no cap). "Limit how many memory drafts Memory Assistant
  can create from each conversation." The cap must be enforced in code, not
  only in the extraction prompt. (Lives here, not in Advanced: it controls
  Pending-queue clutter, a normal-user concern.)

**Section: Memory Engine**
- Row **"Memory Engine"** — None / Lore Books / Full. "Choose how much memory
  support the app uses." Optional value explanations: "None: Memory stays
  off." / "Lore Books: Uses trigger-based lore notes." / "Full: Uses the full
  memory system."

**Section: Memory Assistant Model**
- Row **"Memory Assistant Endpoint"** — "Choose which saved AI endpoint
  analyzes history and drafts memory items."
- Row **"Memory Assistant Model"** — "Model name used by Memory Assistant."

**Section: Backups** (never "Seed & Backup")
- Toggle **"Automatic Daily Backup"** — "Save a rotating local backup once a
  day."
- Button **"Import Backup"** — "Restore memories from an exported JSON
  backup."
- Button **"Export Backup"** — "Save a JSON backup you can store or move to
  another device."
- Status line **"Last Backup: [date/time or Never]"**
- Create Companions From Personas / bootstrap does NOT belong here — it is
  setup/repair (Advanced Memory Settings).

**Section: Reset** (bottom)
- Button **"Reset Memories"** — "Delete memory content. Offer to export a
  backup first." Requires confirmation; backup-first option checked by
  default.

## 2. Memory Assistant Advanced Settings (page title as named)

Top description: "Tune how Memory Assistant analyzes conversation history and
creates pending memory drafts. Most users should keep the defaults." Save
button; toast "Advanced settings saved." Nothing else moves here (no general
memory controls, backups, embeddings, browser filters, debug search,
import/export).

- **Temperature** — number field/slider 0.0–2.0, default 0.3, label
  "Recommended: 0.3", always a Reset to Recommended option. Helper: "Controls
  how literal or creative the AI is when analyzing conversations. Lower
  values are stricter; higher values may find more but can be less reliable."
- **Minimum Importance** — 1–5 picker on the existing importance scale
  (Low/Minor/Notable/High/Critical), default Low. Helper: "Only create memory
  drafts at or above this importance level." Default lets everything through;
  higher = more selective.
- **Extraction Prompt** — large editable text field. Label "Extraction
  Prompt"; helper "Instructs AI how to analyze conversation history." Button
  "Reset Prompt" with confirm "Restore default extraction prompt?". Always a
  reset option.

## 3. Advanced Memory Settings (diagnostics; page title as named)

Top description: "Technical tools for memory diagnostics, indexing, model
files, and repair." Moves here from the old Memory Settings screen:

- **System Status**: store health/integrity, row counts (companions,
  memories, entities, modes, directives, worlds, personas, roleplay
  characters, proposals), chats since last backup, pending memory review
  count, bootstrap status, last automatic backup timestamp (if not shown
  elsewhere).
- **Setup / Repair**: Create Companions From Personas + persona-linking
  repair. Plain wording — "Bootstrap" only inside a technical note.
- **Librarian / Embeddings**: embedding model list, download/delete/cancel +
  progress, Rebuild Index, index health.
- **Debug**: debug search field + button + retrieval results.
- **Danger Zone**: Reset Memories (if not kept in Memory Controls) + any
  destructive repair actions.

## Boundary rule (owner)

Do not add extra advanced fields unless explicitly approved. If another
tuning option seems useful, list it as a question instead of implementing it.
If any placement, wording, behavior, or screen structure is unclear, stop and
ask for an owner ruling before implementing.

## Placement rulings (owner, July 9 2026 — all questions answered)

1. **Hub row**: the Memory Manager hub's fourth row becomes **"Memory
   Controls"**, subtitle **"Set memory defaults, models, backups, and review
   limits."** — it opens the normal user-facing controls page.
2. **Advanced Memory Settings** door: a row at the BOTTOM of Memory Controls
   (never on the hub — the hub stays clean). Label **"Advanced Memory
   Settings"**, subtitle **"Diagnostics, indexing, model files, and repair
   tools."**
3. **Memory Assistant Advanced Settings** door: inside the Memory Assistant
   SECTION of Memory Controls (not on the Memory Assistant action screen,
   which stays focused on Analyze History / queue status / recent runs /
   Pending). Label **"Memory Assistant Advanced Settings"**, subtitle
   **"Tune extraction temperature, importance, and prompt behavior."**
4. **Maximum Suggestions Per Conversation**: when the toggle switches ON the
   number field defaults to **10**. Off still means no cap.
5. **Reset Memories lives in Memory Controls ONLY** — never duplicated in
   Advanced Memory Settings (one clear home for a destructive action).

## Same-message ruling: "Associated Lore Card" is REMOVED as a concept

(Owner, July 9 2026.) A roleplay item is either (1) a standalone memory in
the browser/archive — with normal ownership links to world/campaign/RP
character/NPC and normal archive rules — or (2) card content on a lore card,
having MOVED there and stopped being a standalone memory ("If something is on
the card, it follows the card. If something is in the archive, it follows
archive memory rules."). There is NO second lore-card relationship where a
memory stays in the archive but mirrors a card's archive/delete status — do
not build one. This retires the "Associated Lore Card" dropdown and its
lifecycle sentence from the July 8 editor design; the "Associated Companion"
picker REWORK (label + dropdown + small-curve ×-boxes replacing the chips)
still stands — it was a visual redesign of the normal target links, not a
new relationship.
