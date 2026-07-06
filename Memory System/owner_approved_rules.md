# Memory System — Owner-Approved Rules (SOURCE OF TRUTH)

**Approved by the owner, in plain language in chat, July 6 2026.**

This document OUTRANKS every other document in this folder and the
integration plan wherever they disagree. The wording below was reviewed and
corrected by the owner section by section; it is not AI elaboration. Per the
approval gate in CLAUDE.md:

> If implementation requires changing the meaning of anything here,
> STOP and ask the owner first. Do not reinterpret, simplify, or extend.

Context that shapes engineering choices: **this app has a single user (the
owner).** Backwards compatibility is not of utmost importance; the owner has
a backup and considers the old seed expendable. Do not over-engineer
migration/compat paths — but whatever ships must open the owner's existing
store without crashing.

---

## 0. Governing laws (apply to everything below)

1. **A definition never changes by itself; a memory can change or
   progress.** Character cards, hard limits, world premises, directives, and
   model rules are definitions. They are written by the user or changed only
   through a proposal the user approves. Memories are the living, changing
   material.
2. **The Archivist always proposes.** It may draft memory, definition, or
   model-rule proposals, but nothing becomes active until the user approves
   it. The user can accept, edit, delete, archive, or move any proposal.
3. **Storage is not injection.** A memory, rule, or proposal can exist in
   the app without being sent into a conversation. The system may file,
   rank, and suggest items, but it only injects active items when the
   user's settings, scope rules, and approval allow it.
4. **No AI pre-authors memory content.** No default rows, no example data,
   no pre-written modes. Empty until real use fills it.

## 1. Scope

Every memory has a scope. The scopes are:

| Scope | Meaning |
|---|---|
| **Global** | Allowed everywhere, including inside roleplay — but still only shows up when active and relevant. |
| **Real life** | Ordinary conversation only. Never enters fiction (see §3). |
| **Companion: _name_** | Tied to the relationship with a specific companion. Companion implies real life — never stack "real life + companion". |
| **Project: _name_** | Belongs to a named project bucket (see §4). |
| **World: _name_** | True in that fictional world, across its campaigns. |
| **Campaign: _name_** | True in one playthrough only. |
| **RP character: _name_** | Tied to a specific roleplay character. |

## 2. Primary scope

Every memory has one primary scope category. If that category has named
targets, the user may select one or more targets. Global and Real life have
no target selector. Companion, Project, World, Campaign, and RP character
open a multi-select target picker, with selected targets shown as removable
pills.

If the system needs a secondary connection under the hood, the machinery
handles it invisibly; the user never manages secondary links.

## 3. Real-life memory in roleplay

The clean default: **real-life memories are blocked during roleplay/campaign
mode unless the user clearly allows them.** The failure mode of real-life
memory leaking into fiction is worse than the failure mode of missing
real-life memory for a brief tangent.

- **Default (ships this way): Off.** Campaign/RP context blocks real-life
  memories entirely.
- **Future per-chat setting** — "Real-life memory in roleplay":
  **Off / OOC only / Always allowed.** Added later, designed with the owner;
  per-chat, so it goes on the auto-naming copy block.
- **OOC only means explicit markers only** (a message marked `OOC:` or
  `((…))`). Deterministic string detection — the boundary opens only on the
  user's explicit signal, never on the model inferring tone. Forgetting the
  marker degrades to the safe default: blocked.
- Global-scoped memories remain eligible inside roleplay (that is what
  Global means), subject to being active and relevant.

## 4. Folders / Projects

- A **project is a named bucket the user defines**: name it, rename it,
  archive it. Nothing more elaborate.
- **Project** is a scope category (§1).
- Quick Settings gets an optional **Project selector** per chat. Project
  selected → that project's memories join retrieval in that chat. None
  selected → project memories stay quiet. Per-chat → auto-naming copy block.

## 5. Types

One type per memory, from a dropdown. Approved list and meanings:

| Type | Meaning |
|---|---|
| **Fact** | Stable information. |
| **Preference** | Likes, dislikes, style needs, response preferences. |
| **Event** | Something that happened. |
| **Status** | Something currently true but expected to change. |
| **Instruction** | A handling rule for the assistant when relevant. |
| **Lore** | Fictional/world/roleplay information. |

**Instruction boundary:** an Instruction memory is *context-triggered* —
retrieved when its topic comes up. A rule that must apply always, in every
message, is not a memory; it is a definition (hard limit on the character
card, a directive, or a model rule). The Instruction type's hint text says
so.

## 6. Tags

- Tags are applied to every memory. Users can change them and create their
  own.
- The global memory browser can search and filter by tag.
- **Tags help search, filtering, and retrieval ranking, but they do not
  override scope/type and do not force injection by themselves. Tags are
  like softer lorebook trigger words: useful hints, not absolute rules.**
  (Owner's wording, kept verbatim.)

## 7. Source

- Every memory shows where it came from: **Entered by hand** / **Imported**
  / **Learned from chat** (the last exists once the Archivist does).
- Source is visible on the memory and is a filter in the browser — so
  imported seed material can be isolated and cleaned in one sitting.
- A one-time **"remove everything imported"** cleanup action is available.

## 8. Importance

Single dropdown, five steps, short defined meanings:

**1 Low importance · 2 Minor · 3 Notable · 4 High · 5 Critical**

Importance feeds retrieval ranking.

## 9. Status

Memories have four statuses: **Draft · Active · Archived · Superseded.**

- **Draft** — proposed (e.g. by the Archivist), awaiting the user's
  approval; never injected.
- **Active** — in play, retrievable.
- **Archived** — kept but out of retrieval; visible in the browser via
  filter.
- **Superseded** — replaced by a newer version, kept for history.

## 10. Retrieval mode — ⏸ DEFERRED

**The owner will revisit this section.** Do not implement retrieval-mode
changes (including the fate of the existing per-memory "always load" flag)
until the owner has settled this section's wording. What is already settled
elsewhere still applies: eligibility is decided by scope rules (§1, §3)
first; ranking (§12) only orders what is eligible; selection happens in app
code before anything is sent — the model never sees the mess.

## 11. Model-specific patches (Model rules)

- A **Model rules** card in Settings holds **profiles the user creates**. A
  profile = the user's nickname (e.g. "Model 5") + a **list of model
  strings** that count as that model (snapshots like `5-0502` and `5-0219`
  can share one profile). Endpoints are irrelevant — the same model is the
  same model from any provider.
- **Filing is automatic by model string.** When the Archivist notices
  repeated corrections to a model, it files a draft rule into the profile
  carrying that chat's model string. **No matching profile → the draft goes
  to "Needs review" (unassigned)** — the system never invents a profile.
- **"Needs review" is pinned at the top of the Model rules screen, always,
  whenever any draft exists.** Other surfaces may point to it; this is its
  home.
- For every draft the user can **accept, delete, or move** it to another
  profile. Moving offers to **add that model string to the destination
  profile**, so future drafts with that string file there automatically.
- **Injection is user-decided:** Quick Settings gets an optional **Model
  rules dropdown** per chat. **Default: none — by default no model rules
  apply.** The dropdown may show a "(matches this chat's model)" hint next
  to a matching profile; it never auto-applies. Per-chat → auto-naming copy
  block.

## 12. Priority order

When more memories are eligible than the injection budget allows, ranking is
deterministic, in app code:

1. **User-authored lorebook notes outrank system memories** (standing rule,
   unchanged).
2. **More specific scope outranks broader scope.** Approved ladder, most
   specific first:
   **Campaign → RP character → World → Project → Companion → Real life →
   Global**
3. Within the same scope level: relevance, importance, recency, tag hints —
   exact weights are implementation detail and must never change the meaning
   of the rules above.

---

## Approved UI decisions (same approval, July 6 2026)

- **Companion page surgery:** the essence / relationship-notes / hard-limits
  fields are REMOVED from the companion page. It keeps: name (read-only),
  draft/approve, memory participation, and gains **Delete** (with a Material
  confirm that says what happens to that companion's memories — the user
  chooses per-deletion whether they go too). The underlying columns stay but
  nothing writes to them until the owner approves a design.
- **The five pre-written modes are deleted and never auto-installed.** The
  hardcoded default-mode text is removed from the app; existing
  origin='system' mode rows are removed from the store; the modes machinery
  sits dormant and empty until Phase 6 proposes modes the user approves.
  (User-authored modes are untouched.)
- **The canon companions are the app's character cards** (Characters →
  Personas). The Archivist never invents new companions.
- **My Personas moves out of the memory system.** It is the user's
  self-presentation: edited under Characters, selected per chat in Quick
  Settings, changeable mid-conversation. User personas carry zero memories.
- **The memory hub collapses.** The Memory manager opens straight into the
  Memories browser; Companions one tap away; everything else behind a single
  quiet "Advanced" entry.
- **One real browser, many doors:** character cards, world cards, campaign
  pages, and RP character cards each get a button that opens the SAME
  browser pre-filtered to that thing's memories.
- **Browser layout:** search + sort (newest/oldest) + filters (scope, type,
  tag, source, status) at the top. Each memory row shows, in order:
  **title → status → tags → the first line of what would actually be
  injected.**
- **Target picker UI (§2):** explanatory heading, multi-select with add,
  selected targets as pills below, tap a pill to remove it.
- **Reset memories button** in Memory settings: empties every memory table —
  "reset" means EMPTY, never refilled with anyone's defaults; only invisible
  structure the system needs is reinstalled. Blunt confirm dialog ("This
  will delete all your memories. It cannot be undone. Are you sure?"). The
  dialog offers a **user-choosable backup-first option** — the user decides
  whether a backup is written before the wipe; if they decline, trust them.
- **Hard limits** live on the character card, user-authored, always-on. The
  Archivist may *propose* adding one it noticed in conversation ("you said
  never mention X — add as a hard limit?"); one tap yes writes it to the
  card, no and it's gone. Never silently learned.
- **Directives** are behavior corrections (definitions), distinct from hard
  limits (content boundaries) and from Instruction memories
  (context-triggered). Archivist-noticed directives arrive as proposals.
- **Sensitive inferences about the user** (emotional patterns, mood
  correlations) require an approved proposal before being stored at all.

## Deferred / to revisit with the owner

- §10 Retrieval mode (including the "always load" flag's fate).
- The **memory approval screen** (how Archivist proposals are reviewed) —
  owner wants to design this next, in chat.
- The "Real-life memory in roleplay" per-chat setting (§3) — later, with
  the owner.
- A converter for pre-restructure backup files — only if the owner asks.
- Merge tooling, abilities/spells column, campaign→Quick-Settings live
  wiring (carried over from the Phase 5 deferral list).
