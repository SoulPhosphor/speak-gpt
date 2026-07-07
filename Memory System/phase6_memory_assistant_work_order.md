# Phase 6 — Memory Assistant — Work Order (owner-directed, this is the authority)

**This file is the current authority for the rest of Phase 6.** It was written
from the owner's own words given in chat. Obey it together with
`owner_approved_rules.md` (the source of truth). If the two ever disagree, the
rules win and you STOP and ask the owner in plain chat.

## ✅ STATUS (July 7 2026): tasks 6.1 + 6.2 are BUILT

The first Memory Assistant shipped on branch
`claude/memory-system-phase-6-j9gg0y` (which carries the prep branch's four
commits): the extraction prompt (authored in the owner's high-end session,
approved in chat, including an owner-requested "and" → "&" token trim —
`MemoryAssistantPrompt.BASELINE`, unloseable by design), the backend runner
(`preferences/memory/assistant/MemoryAssistantRunner`, safety laws in code,
law layer unit-tested), the Memory Assistant page + Advanced Settings screen
(`ui/activities/memory/MemoryAssistant*`), and the entry row in Memory
settings. Owner wording decisions made during the build (approved in chat,
July 7 2026, use verbatim): the prompt field label is **"Extraction Prompt"**
with helper **"Instructs AI how to analyze conversation history."**; the
reset button is just **"Reset"**; its confirm is **"Restore default
extraction prompt?"**; the optional read-only prompt-preview screen is
DROPPED (not wanted); and a general house style now recorded in CLAUDE.md —
labels in Title Case, wording concise and professional, never wordy. The
owner also confirmed the temperature shape: keep the 0.0–2.0 range (room to
make a mistake) with "Recommended: 0.3" always visible (the way back). The
Deferred section below still stands: no roleplay-card suggestions (the
Plot Ledger question resolves under that deferral — the ledger is a campaign
card section, so ledger maintenance is deferred WITH card suggestions) and
no model-rule drafting yet.

## ⛔ Read this before touching anything

- **Act only from fact, not assumption** (the rule now at the top of
  `CLAUDE.md`). Verify the actual code before claiming or changing anything.
- **DO NOT read or build from anything in
  `Memory System/OUTDATED_do_not_reference/`.** Those documents are retired.
  Everything the owner threw out lives there: autonomy dials, "run reports,"
  modes, directives, entities, owner profile, the old Archivist spec/prompt,
  the old enforcer/prompt-assembly specs. They are the reason previous
  sessions kept building things the owner never asked for. Never open them.
- **Never use the pop-up question tool** in this project (it breaks on the
  owner's phone). When you need the owner, write a plain chat message and wait.
- **The owner is the only user.** Nothing is pre-populated; nothing is
  auto-applied. Every output of the Memory Assistant is a **draft the owner
  approves**. It never writes to companion or persona cards. It never writes
  mid-conversation. It holds the real-life / fiction wall.

## What Phase 6 actually is (one feature — not a pile of sub-systems)

The **Memory Assistant** reads finished conversations and **proposes memory
drafts**. The owner reviews them in the memory browser that already exists.
That is the whole feature. There is no "review conversations" screen and no
"report" screen — those were mistakes from a retired plan and must not be
built.

## Already done (committed on branch `claude/memory-system-phase-6-9o2zwg`, CI green)

- **Transcript scene stamping (DB v11):** every captured turn records its
  scene (world / campaign / RP character / user persona); a selected campaign's
  links outrank the chat's picks; scene change closes the transcript row. This
  is what lets the assistant hold the fiction wall and attribute campaign turns.
- **`story_so_far` and `arc` removed completely (DB v12).** A character's
  history is the **Backstory** Zone 2 card section; a campaign's story is the
  **Plot Ledger** Zone 2 card section. Do NOT reintroduce a per-card
  story/arc/summary field.
- **"Active Scene" renamed to "Current Plot"** everywhere the user and the AI
  see it (the DB column stays `active_scene`; label only).
- CLAUDE.md "act from fact" rule; the OUTDATED docs quarantined.

## What already exists to build ON (do not rebuild these)

- **Capture:** finished conversations land in the `transcripts` table with
  `review_status` pending / processed / excluded, plus the scene columns.
- **Draft destination:** `memories.status='draft'` already exists, and the
  **Pending screen + memory browser are built** (Phase 5). Archivist-filed
  memory drafts go there — `status='draft'`, `origin='archivist'`. Do NOT
  invent a second store for drafts (`owner_approved_rules.md` §14).
- **Model/endpoint setting:** the Archivist endpoint + model name already
  exist as a global setting (`getArchivistEndpointId` / `getArchivistModel`).
  Reuse them. (They may be surfaced on the new page; do not duplicate.)
- **Model-rule drafts:** the "needs review" area exists, empty, ready for
  Phase 6 to file model-rule drafts (later — see Deferred).

## The Memory Assistant page — EXACT owner-approved words (this session)

Use these words verbatim. They are the owner's own.

- Page title: **Memory Assistant** (never "Archivist").
- The run action: a control labeled **Analyze History**. It triggers a run
  over the pending conversations. Manual only — nothing runs on its own.
- A plain row at the bottom (a row, **not** a button): **Advanced Settings ›**
- Suggestions produced by a run appear in the **existing memory browser**.
  The page has no review screen and no report screen.

### Advanced Settings screen — EXACT owner decisions

Purpose (owner's words): knobs revealed for advanced users in case they are
having trouble with whatever model they use mishandling the memory extraction.

1. **Temperature**
   - Default **0.3** (a reasonable steady-extraction value the owner asked me
     to pick; changeable).
   - Directly under the field show **"Recommended: 0.3"** so a user who
     changes it can always get back.
   - Range **0.0 – 2.0** (deliberate extra room to push it).
   - Show real numbers, not a vibes slider.
2. **Maximum suggestions per conversation**
   - Ships **Off** (no limit) by default.
   - When on, it caps how many suggestions one conversation may produce.
3. **The extraction prompt — editable, with Reset**
   - The user can edit the prompt text that is sent to the model.
   - A **Reset** restores the original. The original is kept in code and can
     **never** be destroyed by editing — a user who breaks it can always
     recover it. This is the entire point; build it so the baseline is
     unloseable.
   - (Optional per owner intent: a read-only preview of the full assembled
     prompt — instructions + the real pending transcript — so a user can see
     exactly what the model receives. Confirm wording with the owner before
     adding any preview screen.)

## The extraction prompt itself — NOT authored here

**The high-end model (Fable) writes the extraction prompt.** Do not write it in
a mechanical session and do not lift one from the OUTDATED folder. The editable
field + Reset simply wraps whatever the high-end session produces as the
baseline. Until that baseline exists, the runner has nothing correct to send —
so prompt authoring is a prerequisite input, not an afterthought.

## Safety laws the runner MUST enforce in code (not just trust the model)

- Everything the model returns becomes a **draft**. Nothing is auto-applied.
- Only allowed operations are accepted (memory drafts). Anything else the model
  emits is dropped, never applied.
- **Never** write to companion cards or user-persona cards — those have one
  author, the owner (`owner_approved_rules.md` law 3).
- **Fiction wall:** a transcript row's scene columns decide whether its content
  is fiction; real-life memory never crosses into fiction and vice versa.
- Excluded transcripts are invisible to the assistant. Draft-status companions'
  memories are never surfaced.
- A run that finds nothing is a success. Never manufacture suggestions.

## Open questions — bring to the owner in plain chat, never assume

1. **Plot Ledger maintenance** (`owner_approved_rules.md` deferred list): may
   the assistant suggest updates to a campaign's Plot Ledger (its story
   record)? If yes, one toggle or per-campaign opt-in? Not decided.
2. **Exact wording** of the editable-prompt / Reset screen and any prompt
   preview — get the owner's words before putting them on screen.

## Deferred — do NOT build now (owner decision this session)

- **"Suggest roleplay card updates"** — the ability for the assistant to
  suggest changes to roleplay cards (world/campaign/character/party). The owner
  chose to add this **later**, as its own designed feature, not as a mystery
  toggle now. Leave it out entirely for now.
- **Model-rule drafting** by the assistant (filing into the model-rules "needs
  review" area) — a later addition, not part of the first Memory Assistant.

## Suggested phase order for what's left (each pushed + CI-green separately)

- **6.1 — Backend runner (high-end).** Prerequisite: Fable's extraction prompt.
  Read pending transcripts → call the model via the existing endpoint/model
  setting (with the Advanced temperature + max-suggestions settings) → parse
  the response → enforce the safety laws above in code → file memory drafts
  (`status='draft'`, `origin='archivist'`) → mark transcripts processed. Plus
  the prefs storage: temperature, max-suggestions (+off), the edited prompt
  text (baseline in code).
- **6.2 — The Memory Assistant page + Advanced Settings (mechanical).** The
  page with **Analyze History** wired to 6.1's runner, and the **Advanced
  Settings ›** screen (temperature with "Recommended: 0.3", max-suggestions
  Off by default, editable prompt + Reset). Follow the existing
  `MemoryScreenActivity` / full-screen-activity patterns. New strings only in
  `res/values/strings.xml`, using the owner-approved words above.
- Suggestions already surface in the built memory browser — no new review UI.

## Model assignment (owner's instruction)

- **High-end → the chosen high-end model (Fable):** the extraction prompt, and
  the safety/validation layer of the runner (6.1). This is where a mistake
  becomes the exact violation the owner fears.
- **The rest → an ordinary session:** the page + Advanced Settings UI, the
  prefs, the wiring (6.2).
