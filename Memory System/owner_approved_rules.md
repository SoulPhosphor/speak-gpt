# Memory System — Owner-Approved Rules (SOURCE OF TRUTH)

**Approved by the owner, in plain language in chat, July 6 2026.**
**Revision 2 — same day, after the owner's section-by-section review and
redesign of the rules/cards/roleplay structure. This revision supersedes
revision 1 where they differ.**

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

1. **The great split is roleplay vs. not-roleplay.** Outside roleplay, the
   system's job is remembering a life. Inside roleplay (worlds, campaigns,
   RP characters) the material is volatile game state, and the rules below
   differ there — explicitly, never by analogy.
2. **The card is the person; the memories are the relationship.** A card
   says who someone IS (or what a world is). Memories are everything that
   happens and everything that's true — the living, changing material.
   **When in doubt, it's a memory.** Memory is the default bucket; card
   material has a high bar (would you write it into the description of who
   they are?).
3. **Companion cards and user personas have one author: the user.** The
   Archivist never touches them — not to add, not to transcribe, not to
   propose. If the user wants a rule or hard limit on a card, they type it
   themselves. (Roleplay cards have a narrow suggestion path — §13.)
4. **Always-on rules are system-prompt material, not memory-system
   material.** "Don't use corporate language" belongs in the system prompt /
   card, written by the user, riding every message. An "every turn" rule
   managed inside the RAG layer is a design flaw: it makes the machine
   monitor itself and drags baggage into every message. The memory system
   does not manage always-on rules and the Archivist never suggests them.
5. **Context rules live inside the RAG pool.** A context-triggered rule
   ("don't pity her when her mom comes up") is an **Instruction memory**: it
   activates only as a direct, automated reaction to its trigger surfacing
   in retrieval, and costs nothing otherwise.
6. **The Archivist always proposes; the user always decides.** It may draft
   memory proposals, roleplay card-update suggestions (§13), and model-rule
   drafts — nothing becomes active until the user approves it. The user can
   accept, edit, delete, archive, or move any proposal. The Archivist never
   invents companions, never rewrites identity, never promotes a memory
   onto a card — promotion is a human act.
7. **Storage is not injection.** A memory, rule, or proposal can exist in
   the app without being sent into a conversation. The system may file,
   rank, and suggest items, but it only injects active items when the
   user's settings, scope rules, and approval allow it. Who writes a thing
   and when it is delivered are independent questions.
8. **No AI pre-authors memory content.** No default rows, no example data,
   no pre-written modes. Empty until real use fills it.

## 1. Scope

Every memory has a scope. The scopes are:

| Scope | Meaning |
|---|---|
| **Global** | Allowed everywhere, including inside roleplay — but still only shows up when it is active and relevant. |
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
| **Instruction** | A handling rule for the assistant, activated only when its trigger/topic appears (a context rule — law 5). |
| **Lore** | Fictional/world/roleplay information. |

**Instruction boundary:** an Instruction memory is context-triggered — it
fires only when its topic surfaces in retrieval. A rule that must apply
always, in every message, is not a memory at all; it is system-prompt
material the user writes on the card (law 4) — or a model rule if it names
a model.

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
  approval; never injected. Drafts can sit in draft forever; until approved
  or deleted they are never applied.
- **Active** — in play, retrievable.
- **Archived** — kept but out of retrieval; visible in the browser via
  filter.
- **Superseded** — replaced by a newer version, kept for history.

## 10. Retrieval mode

- Memories enter conversations **by relevance only**: semantic match
  against what is being said, ranked per §12. A memory costs nothing until
  its topic arises.
- **There is no per-memory "always load" flag.** If something deserves to
  be in every message, it is system-prompt material on a card (law 4) or a
  model rule — visible, deliberate, user-written. The existing schema flag
  is retired.
- **Instruction memories (context rules) activate only as a direct,
  automated reaction to their trigger surfacing in retrieval** — no
  standing self-monitoring.
- **Roleplay ledger entries (§13) are indexed individually** and retrieved
  the same way: talk of lockpicks retrieves the lockpicks entry, not the
  inventory. A section's name acts as a trigger for the whole (bounded)
  section — "what's in my inventory?" injects the full Items list for that
  turn.
- Eligibility is decided by scope rules (§1, §3) first; ranking (§12) only
  orders what is eligible. Selection happens in app code before anything is
  sent — the model never sees the mess.

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
- Model-rule drafting is **always on** (it touches nothing intimate — the
  rules are about the machine's own defects). Model rules are the accepted
  special case of "injected every turn," because the user explicitly
  selects the profile per chat.

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

## 13. Roleplay cards and Archivist suggestions

Roleplay state is volatile and hard to hold by hand — that is why the
suggestion path exists HERE and nowhere else.

**The RP character card has two zones:**

- **Zone 1 — the core. Always injected.** Name, species, class, core
  personality. Small, stable, identity.
- **Zone 2 — the ledger. Card-owned, retrieved on relevance (§10).** Six
  sections, each a list of named entries:
  **Spells · Skills · Special abilities · Items · Special items · Weapons**
  - Every entry: **name (required) + description (optional)**.
  - **Items, Special items, and Weapons entries carry a quantity
    (number).**
  - Each entry is indexed individually; section names trigger their whole
    section (§10).

**Archivist suggestions for roleplay (worlds, campaigns, RP characters):**

- The Archivist may suggest card/ledger updates from play: acquired X
  equipment, X building burnt down, character joined a guild — **including
  noticing that a tool was used up, broken, or lost, and suggesting the
  matching quantity change, edit, or removal.**
- Governed by one toggle in Memory settings — **"Suggest roleplay card
  updates," ON by default**, user can turn it off.
- Every suggestion gives the user the full choice:
  **add to the card permanently · keep as a memory as-is · modify it
  first · delete it.**
- This path exists ONLY for roleplay cards. Companion cards and user
  personas remain untouchable (law 3), always.

**World and campaign card sections: ⏸ AWAITING THE OWNER'S DESIGN.** The
owner will specify the world card's sections next. Do not design or build
world/campaign card structure before that. (Open sub-question for that
conversation: whether campaign "story so far" maintenance rides the same
roleplay-suggestions toggle or stays a per-campaign opt-in.)

## 14. The Pending screen (memory approval)

- **The door:** a banner pinned at the top of the Memories browser —
  "Pending memories (N) ›" — visible only when drafts exist. Same visual
  pattern as Model rules' "Needs review": one language for "things waiting
  on your judgment."
- **Scoped doors:** the pending banner inside a scoped browser (e.g. a
  companion's memories, opened from their card) opens the Pending screen
  **already filtered to that scope** — help people who get overwhelmed by
  too much stuff.
- **Grouping:** drafts are grouped under collapsible headers by destination
  scope, each header with its own select-all.
- **Checkboxes** down one side; top bar has **Select all · Select none**;
  actions on checked items: **Accept** and **Delete**. "Accept all" =
  select-all + accept, with a count confirm ("Accept 12 memories?").
- **Tap a draft to edit** before accepting: the normal memory editor opens
  pre-filled with everything proposed (scope, targets, type, tags,
  importance, text, title). Saving keeps it a draft with the user's
  corrections; the editor also has an Accept button. The editor shows
  provenance — which chat it came from, when.
- **Roleplay card-update suggestions** appear in their own section with the
  four-way choice (§13) — decided individually, never bulk-accepted.
- **Model-rule drafts** stay homed in Model rules; the Pending screen shows
  a pointer row ("N model rules need review ›").
- **Titles:** every memory has one. The Archivist proposes a title with
  each draft (user-editable); hand-entered memories ask for one.
- **Pending row layout:** checkbox · title → proposed destination
  (scope · type) → first line of the content. (Status would always read
  "draft" here, so the destination line takes its place.)
- **Sort/filter state is always visible** (chips/line showing current sort
  and filters), **remembered** when the user clicks into a memory and back
  out or leaves and returns, and a **Reset button** clears back to default
  (newest first, no filters) in one tap.
- Drafts can sit forever (§9); the only pressure is the quiet count.

## 15. Retired machinery

- **Modes:** the five pre-written modes are deleted and never
  auto-installed. The hardcoded default-mode text is removed from the app;
  existing origin='system' mode rows are removed from the store; the modes
  machinery sits dormant and empty until Phase 6 proposes modes the user
  approves. (User-authored modes are untouched.)
- **Directives:** retired. "Directives are just instructions that are
  context triggered now" — i.e. Instruction memories (§5). Always-on rules
  live in the system prompt (law 4). The directives table stays but goes
  dormant; its screen leaves the hub.
- **Always-load flag:** retired (§10).
- **Canon proposals outside roleplay:** never existed, never will (law 3).
- **Rule-suggestion toggle:** not built — the Archivist does not suggest
  always-on rules at all (law 4).

---

## Approved UI decisions (July 6 2026)

- **Companion page surgery:** the essence / relationship-notes / hard-limits
  fields are REMOVED from the companion page. It keeps: name (read-only),
  draft/approve, memory participation, and gains **Delete** (with a Material
  confirm that says what happens to that companion's memories — the user
  chooses per-deletion whether they go too). The underlying columns stay but
  nothing writes to them until the owner approves a design.
- **The canon companions are the app's character cards** (Characters →
  Personas). The Archivist never invents new companions.
- **My Personas moves out of the memory system.** It is the user's
  self-presentation: edited under Characters, selected per chat in Quick
  Settings, changeable mid-conversation. User personas carry zero memories.
- **Hard limits** live on the character card, user-authored, always-on
  system-prompt material. The user writes them in personally — the
  Archivist never proposes card content outside roleplay (law 3).
- **The memory hub collapses.** The Memory manager opens straight into the
  Memories browser; Companions one tap away; everything else behind a single
  quiet "Advanced" entry.
- **One real browser, many doors:** character cards, world cards, campaign
  pages, and RP character cards each get a button that opens the SAME
  browser pre-filtered to that thing's memories.
- **Browser layout:** search + sort (newest/oldest) + filters (scope, type,
  tag, source, status) at the top. Each memory row shows, in order:
  **title → status → tags → the first line of what would actually be
  injected.** Sort/filter visibility, persistence, and Reset behave as in
  §14.
- **Target picker UI (§2):** explanatory heading, multi-select with add,
  selected targets as pills below, tap a pill to remove it.
- **Reset memories button** in Memory settings: empties every memory table —
  "reset" means EMPTY, never refilled with anyone's defaults; only invisible
  structure the system needs is reinstalled. Blunt confirm dialog ("This
  will delete all your memories. It cannot be undone. Are you sure?"). The
  dialog offers a **user-choosable backup-first option** (starts checked) —
  the user decides whether a backup is written before the wipe; if they
  decline, trust them.
- **Sensitive inferences about the user** (emotional patterns, mood
  correlations) require an approved proposal before being stored at all.

## Deferred / to revisit with the owner

- **World and campaign card sections** — the owner is designing these next
  (§13). Includes the campaign story-so-far toggle question.
- The "Real-life memory in roleplay" per-chat setting (§3) — later, with
  the owner.
- A converter for pre-restructure backup files — only if the owner asks.
- Merge tooling and campaign→Quick-Settings live wiring (carried over from
  the Phase 5 deferral list; the abilities/spells column is now designed —
  §13 — and no longer deferred).
