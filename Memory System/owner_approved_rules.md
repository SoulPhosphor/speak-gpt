# Memory System — Owner-Approved Rules (SOURCE OF TRUTH)

**Approved by the owner, in plain language in chat, July 6 2026.**
**Revision 2 — same day, after the owner's section-by-section review and
redesign of the rules/cards/roleplay structure. This revision supersedes
revision 1 where they differ.**
**Revision 3 — same day, the owner's second-pass rulings given in chat
while resolving the Stage 3/4 spec conflicts. Changes, each marked "rev 3"
in place: §3 gains the "Allow active companion memories in roleplay"
toggle; §4 project eligibility is REVERSED (relevant project memories
retrieve without a selection; selection boosts; blocked in roleplay by
default); §10/§13 clarify that ledger retrieval is trigger-matched, not
embedded; §12/§3's campaign scope and narrator signal go live via the
Stage-3 campaign wiring; §14 names `memories.status='draft'` the single
store for memory drafts; the deferred list moves campaign→Quick-Settings
wiring into Stage 3. This revision supersedes revisions 1–2 where they
differ.**
**Revision 4 — July 7 2026 (conversation began the evening of July 6 in
the owner's timezone): the owner redesigned the roleplay layer in chat
and approved it front to back. The full ruling text lives in
`roleplay_cards_and_tags_spec.md` — Revision 4 incorporates that file by
reference as the authoritative detail for roleplay cards and tags; the
spec's own words were approved item by item and are NOT paraphrased
here. Summary of what changed, each superseding revisions 1–3 where they
differ: §13's card structure is SUPERSEDED — the six-section ledger is
replaced by a four-card system (user RP character / NPC party member /
world / campaign, each two-zone, with revised owner-approved section
lists; NPC party members are a new card type with Alive / Incapacitated
/ Dead / Enemy statuses and a top-level roster campaigns link into).
World and campaign card sections are DESIGNED AND APPROVED — no longer
deferred. A roleplay-wide TAG system is approved: one shared pool, two
REALMS (real-life and roleplay tags never link, even for identical
words), tags act as trigger words with a per-tag browse-only switch
built up front, one-hop budget-capped pull-along, and a Tags index
screen in the Roleplay hub. Quick Settings: selecting a campaign
auto-fills the world/character selectors; changing one asks "has the
story moved?" — confirming edits the campaign itself (superseded
history kept, the transition recorded as a campaign memory); there is
deliberately no per-chat override. New general laws: no card, ledger,
or memory is written by any automatic process mid-conversation (a
user-confirmed dialog is a user edit); user-entered fields are
multi-line with no hard caps (prompt-cost warnings instead of limits);
Zone 1 (always-injected card material) is always visibly labeled in the
UI; every archivable roleplay list has a visible Archive section;
deleting a linked card warns and offers archive, true delete asks
per-deletion whether memories go too (REPLACING the older
always-delete teardown language), and surviving references show
"(archived card)"/"(deleted card)" rather than vanishing. Stage 3.6 of
`rag_engine_work_order.md` is rescoped to implement the spec.**
**Revision 5 — July 7 2026: the owner redesigned Model rules (§11) in
chat and approved it front to back. The profile/group concept is
REPLACED by a model-string-primary model with tags for organization (the
group abstraction created the "same model in two groups, which one wins?"
problem). Model rules now apply automatically to any chat whose model
string matches, on by default, with an "Automatically Apply Model Rules"
global default in AI System Settings and a per-chat "Apply Model Rules"
toggle in Quick Settings — superseding revisions 1–4's "default none, user
picks a profile per chat." §10's exemption line and §11 are rewritten in
place; this revision supersedes revisions 1–4 where they differ.**

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
| **Global** | Standing rules and etiquette for how the AI should treat you — preferences, boundaries, and conduct that apply in every context, roleplay included (shown only when active and relevant). Not facts about your life; those are Real life. |
| **Real life** | Facts about your actual life and world: people, places, your history, your body, your circumstances. Ordinary conversation only; never crosses into fiction (see §3). |
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
- **During RP/campaign mode, companion memories are blocked unless the
  chat is explicitly using that companion as the active narrator/GM/
  context.** (Owner's wording. The relationship history belongs to the
  narrator at the table, not to the fiction — a companion merely voicing
  characters does not drag the real relationship into the story.)
  *(Rev 3: the explicit signal is now defined — the per-chat Campaign
  selector added to Quick Settings in Stage 3; the chat "is using that
  companion as narrator/GM" exactly when the selected campaign's
  GM/narrator companion is the chat's active companion.)*
- **Memory settings toggle (rev 3, owner-added): "Allow active companion
  memories in roleplay." Default: OFF.** OFF means companion memories do
  not enter RP/campaign mode (beyond the narrator/GM path above). ON
  means the active chat companion's approved active memories are eligible
  during RP/campaign mode, subject to normal scope, status, relevance,
  cooldown, and retrieval rules. This does not force all companion
  memories into the prompt — it only allows them to participate in
  retrieval. It does not require narrator/GM status; narrator/GM remains
  a separate eligibility path.

## 4. Folders / Projects

- A **project is a named bucket the user defines**: name it, rename it,
  archive it. Nothing more elaborate.
- **Project** is a scope category (§1).
- **(Rev 3 — this REPLACES the earlier "none selected → project memories
  stay quiet" rule; do not follow the old wording anywhere it survives.)**
  Project-scoped memories are **eligible in ordinary (non-roleplay) chats
  whenever they are semantically relevant, even when no project is
  selected.** The optional per-chat **Project selector** in Quick Settings
  (per-chat → auto-naming copy block) **boosts** the selected project's
  memories in ranking — selection is never required for retrieval.
  **Project memories are blocked during roleplay/campaign mode by
  default.**

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
- *(Rev 4: the roleplay module gains its own tag system — one shared
  roleplay-realm pool spanning card entries, whole cards, and
  roleplay-scoped memories, with tag names acting as trigger words
  (per-tag browse-only switch), one-hop pull-along, and a hard REALM
  WALL between real-life and roleplay tags. Full rules in
  `roleplay_cards_and_tags_spec.md` §3. This section continues to govern
  real-life memory tags.)*

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
  turn. *(Rev 3 clarification: "indexed" here means trigger-matched on
  entry and section names — the name acts as a trigger word, like lorebook
  triggers. Ledger entries are never embedded; the embeddings index stays
  memories-only.)*
- **The freshness cooldown (applies to EVERYTHING delivered by
  relevance — memories, Instruction rules, world entries, ledger items,
  roleplay or not):** an entry is not re-injected while its last injection
  is still fresh in the conversation; it refreshes only after enough turns
  pass or when the conversation outgrows the old mention. The model does
  not need to hear "user hates pineapple" ten turns running — the
  conversation carries it. New entries always inject on first relevance
  (nothing new is ever delayed; only repeats are suppressed). An edited
  entry resets its clock and re-injects fresh. The cooldown is per-entry,
  automatic, tuned by a constant (not a user setting), and visible in the
  debug/assembly view so misbehavior is diagnosable. Exempt: the always-on
  layer the user deliberately chose — card cores, hand-written
  system-prompt rules, and the matching model rules — which rides
  every turn by design.
- Eligibility is decided by scope rules (§1, §3) first; ranking (§12) only
  orders what is eligible. Selection happens in app code before anything is
  sent — the model never sees the mess.

## 11. Model-specific patches (Model rules)

- **Model rules are short instructions the user writes to correct a
  specific AI model's habits** ("stop the therapy-speak," "quit ending
  mid-sentence"). They live under **Settings → AI System Settings → Model
  rules**, alongside the global system prompt.
- **The model string is the primary thing — there are no profiles or
  groups.** Each rule carries **its own list of model strings** it applies
  to. Matching is case-insensitive contains with the provider prefix
  ignored — a rule targeting `glm-5` applies to `glm-5-0502`,
  `openrouter/glm-5-0219`, and every other snapshot; a rule targeting
  `glm-5-0219` applies to that snapshot only. Broad and narrow rules simply
  stack when both match. (The group abstraction was dropped because the same
  model could land in two groups and only one could win — model string as
  the identity removes that.)
- **Tags organize rules for the human, not the machine.** A rule can carry
  any number of tags (plain names, no colors, created inline as you type
  them). Tapping a tag anywhere shows every rule that carries it — the same
  "tap a tag, see everything" browsing as the roleplay tags, but a separate
  pool. Tags never decide what gets injected; the model strings do that.
- **Injection is automatic and on by default.** Every active rule whose
  model string matches the chat's current model is injected, in
  deterministic order (oldest first). A global **"Automatically Apply Model
  Rules"** toggle in AI System Settings sets the default (on); Quick
  Settings gets a per-chat **"Apply Model Rules"** toggle that follows that
  default and overrides it for one chat. Per-chat → auto-naming copy block.
- **Matching rules are never silently dropped.** Because rules are scoped by
  model string, only the handful written for the current model ever land in
  one prompt — the natural limiter. Everything that matches is injected in
  full; nothing is truncated to fit a budget. The rules browser shows the
  real character size of what a given model pulls, and warns softly when
  that gets large, but never blocks the user from saving. (The app runs no
  AI over the rules, so it does not detect contradictions — writing "use
  emojis" and "no emojis" is the user's own to manage.)
- **Injection is its own prompt-layer block** placed after the stable
  persona/system prefix, never inside the memory message; absent entirely
  when nothing matches or the chat has rules turned off (see the
  prompt-layer contract).
- **Filing is automatic by model string (Phase 6).** When the Archivist
  notices repeated corrections to a model, it files a **draft** rule
  carrying that chat's model string (`status='draft'`, `source_model_string`
  set, no tags yet). Drafts land in a **Pending** area, pinned to the top of
  the Model rules screen whenever any draft exists. For each draft the user
  can **edit** it, **accept** it (assigning the model strings and tags it
  should carry, then it goes active), or **delete** it. Model-rule drafting
  is **always on** — it touches nothing intimate; the rules are about the
  machine's own defects. Model rules are the accepted special case of
  "injected every turn," because the user chose to write them and can switch
  them off per chat.

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
4. **The ladder must not become rigid in a stupid way. The rule is: active
   context first, then specificity.** Eligibility (§1, §3) decides which
   scopes are in the room at all — if a campaign is active, campaign/world/
   RP entries matter; if a project is active, project entries matter. The
   ladder only orders what is already in the room, and specificity is a
   strong preference among comparably relevant entries — never a trump
   card that lets a weakly-relevant specific entry beat a strongly-relevant
   broader one. Relevance gates first; global ordering never overrides the
   actual room you're standing in.

*(Rev 3 notes: the Campaign tier becomes live via the Stage-3 per-chat
Campaign selector in Quick Settings — until then no campaign is ever "in
the room." A selected project grants its memories a ranking boost within
this ladder, per §4 as revised.)*

## 13. Roleplay cards and Archivist suggestions

*(Rev 4, July 7 2026: the CARD STRUCTURE below — the two-zone character
card with its six-section ledger, and the deferred world/campaign
sections at the end — is SUPERSEDED by the four-card system the owner
approved in `roleplay_cards_and_tags_spec.md` §6. The PRINCIPLES of this
section stand unchanged: two zones, core always injected, body retrieved
by name/section trigger-matching, quantity tracking on inventory, the
card/memory split, and the entire Archivist suggestion path below —
including its toggle and the four-way choice. Read the spec for the
actual card fields and sections.)*

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
    section (§10). *(Rev 3: retrieval is trigger-matched on names — "name
    acts as a trigger word" is literal; ledger entries are not embedded.)*

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

**World and campaign card sections: ~~⏸ DEFERRED~~ → RESOLVED (Rev 4,
July 7 2026).** The "return to this when ready" moment happened: the
owner supplied and approved the full world and campaign cards in
`roleplay_cards_and_tags_spec.md` §6c/§6d (the world card's section list
was confirmed by the owner, superseding the draft list below — note the
final list groups sections differently and the "People" group is named
**Organized Groups**). The paragraph below is kept for history only: The retrieval machinery is section-agnostic (named entries in
containers), so world/campaign cards plug into the same engine additively
with no rework; deferring them blocks nothing. Directions already given by
the owner for when they ARE built: use the FULL granular section list
(e.g. Regions/Biomes, Settlements, Points of Interest, Organizations/
Guilds, Religions/Pantheons, Historical Events, Species/Races, Languages &
Scripts — final list confirmed with the owner at build time), NOT a
consolidated few — "this isn't just for the model, it's for the user":
sections exist so the user can find things without scrolling and guessing.
World core (premise/vibe + hard cosmology/magic rules) is always-injected
and kept ruthlessly small. Open sub-question for that conversation:
whether campaign "story so far" maintenance rides the same
roleplay-suggestions toggle or stays a per-campaign opt-in.
*(Rev 3: `rag_engine_work_order.md` now has a pause point right before
Stage 3.6 where the implementing agent stops and asks the owner, in a
plain chat message, whether to fold world/campaign card sections into
that pass — since they'd reuse the exact same pattern. That is the
"return to this when ready" moment; the agent still may not design or
build ahead of that ask, and the section list above stays a draft, not
final, until the owner confirms or amends it word by word at that time.)*

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
- *(Rev 3: memory drafts have ONE home — `memories.status='draft'`. The
  Pending screen reads that and nothing else; when the Archivist exists
  (Phase 6) it files memory drafts there too, never duplicated into the
  `proposals` table. The Pending UI must never depend on two competing
  stores for the same item.)*

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
- **Entities:** retired (owner ruling, July 6 2026). People in the user's
  life are real-world information — ordinary memories under the scope/type
  system, tagged and retrieved by relevance — NOT "entities" with their own
  cards or summaries. The Entities screen is removed, the entity-summaries
  injection is removed, the table stays dormant.
- **Owner profile:** retired (owner ruling, July 6 2026). What the system
  knows about the user is **Preference and Fact memories**, retrieved by
  relevance like everything else — never a profile form or an
  always-injected "portrait." The form is removed, the portrait line is
  removed, the table stays dormant. If a "system's picture of you" surface
  is ever wanted, the owner designs it first.

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
- **The memory hub collapses; the browser is global.** The Memory manager
  screen is removed: "Browse & edit" opens straight into the Memories
  browser — the single browser over ALL scopes and ALL types, roleplay
  included, with the Pending banner pinned at its top (pending first, then
  browsing) and the full filter system (scope, type, tag, source, status).
  Companions one tap away. **ONE Roleplay card on Settings** holds all
  roleplay areas (Worlds, Campaigns, Roleplay characters). **Every
  individual page — each companion card, each world, each campaign, each
  RP character — has a Memories button at its bottom opening that same
  central browser pre-filtered to it.** No "Advanced" screen; no new
  navigation invented. (Corrected July 6 2026: an earlier wording
  mentioned an "Advanced" entry — it will never be built.)
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

- ~~**World and campaign card sections**~~ — RESOLVED (Rev 4, July 7
  2026): designed and approved in `roleplay_cards_and_tags_spec.md`
  §6c/§6d. One sub-question stays open for Phase 6: whether campaign
  story-so-far (Plot Ledger) maintenance rides the same
  roleplay-suggestions toggle as card updates or is a per-campaign
  opt-in — ask the owner when building the Archivist, not before.
- The "Real-life memory in roleplay" per-chat setting (§3) — later, with
  the owner. *(Rev 4 adds the reconciliation: when designed, that toggle
  is the ONLY door real-life memories may enter roleplay through — the
  tag system never bypasses it; see the spec §3.)*
- A converter for pre-restructure backup files — only if the owner asks.
- Merge tooling (carried over from the Phase 5 deferral list; the
  roleplay card system — Rev 4's replacement for the old
  abilities/spells column — lands in the rescoped Stage 3.6).
  *(Rev 3: campaign→Quick-Settings live wiring is NO LONGER deferred — the
  owner promoted it into Stage 3, task 3.0 of
  `rag_engine_work_order.md`, because the §3 narrator rule and §12's
  Campaign tier both need it.)*

---

## Addendum — modifications approved in chat, July 8 2026

**Read this first if you are building Phase 6 (the Archivist / Memory
Assistant).** These decisions were approved in conversation on **July 8
2026**, AFTER the Phase 5 ship and the Rev 4 roleplay work. They are
modifications after the fact. Where they differ from ANYTHING earlier in this
file or in any other memory-system document, **these win.** Old directions
that contradict them have been removed from the docs; if you find a lingering
one, it is stale — follow this addendum and fix the stale text.

1. **Scope definitions tightened (§1).** Global = *standing rules and
   etiquette for how the AI should treat the user* (preferences, boundaries,
   conduct) that apply in every context, roleplay included. Real life =
   *facts about the user's actual life and world* (people, places, history,
   body, circumstances), ordinary conversation only, never crosses into
   fiction. They stay SEPARATE scopes — the one real difference is that
   Global may cross into roleplay and Real life may not (§3).

2. **"Protected" is RETIRED as a user-facing concept.** It was a *handling*
   concern, never a scope or type. Rule-like "protections" are now ordinary
   **Global** memories (Instruction/Preference type); a sensitive fact stays
   in its real scope with any care-note written **directly into the memory's
   text**. The enforcer injects each memory whole or not at all (it never
   truncates a memory's content), so a care-note in the text can never be
   sheared off — the old separate handling field is unnecessary. The
   Protect/Unprotect UI is removed. The DB `protection` column, the backup
   codec field, and the inert `HANDLE WITH CARE` render stay DORMANT for
   backup/import compatibility. **The Archivist must NOT propose "protected"
   memories or emit any protection/handling field** — this overrides the
   protection/handling machinery described in `archivist_spec.md`,
   `enforcer_librarian_spec.md`, and `phase6_card_suggestions_and_icons_
   design.md`.

3. **Memory UI was restructured (chunked, owner-directed).** A new,
   lightweight **Memory Manager** hub (`ui/activities/MemoryManagerActivity`,
   reached from the renamed **Memory Manager** tile on the main Settings
   screen) with four rows: **Memory Browser**, **Memory Assistant**,
   **Lorebooks** (moved here out of Characters), **Memory Settings**. The old
   "Memory (experimental)" screen is now **Memory Settings**. This is NOT the
   deleted ten-area `ui/activities/memory/MemoryManagerActivity`.

4. **Memory browser filters** are now a slide-out **Memory Filters** panel
   (`MemoryFilterPanelActivity`) opened by a three-dots button beside the
   search field — the old chip row is retired. Six sections: Sort, Scope,
   Type, Status, Source, Tags. Sort/Source single-select; Scope/Type/Status/
   Tags multi-select (pills, 10dp corners). **Status defaults to Active**;
   `draft` status is shown to the user as **"Pending"**. Rows: a leading
   scope icon, title, a dot-joined capitalised tag line (no hashtags), and a
   content line; the **Active badge is suppressed** (draft/archived/
   superseded still badge); the trailing action is an **edit-square** (not a
   cog).

5. **Memory-row icon system (FINAL — supersedes §5 of
   `phase6_card_suggestions_and_icons_design.md`).** By scope:
   real_life → `person`; **global → `borg`** (its own icon); companion →
   `partner_exchange`; project → `draft`; rp_character → `theater_comedy`
   (comedy mask); world/campaign (and unknown) → `public` (globe); **a memory
   placed on a card → `book_5`** (overrides the scope icon). There are NO
   corner-badge variants — an on-card memory swaps to the whole book_5 icon.
   The rp_character (comedy mask) icon is a placeholder for a future dedicated
   icon; the code keeps its branch separate so the split is cheap.

6. **`book_5` / "on a card" is RESERVED and not reachable yet.** There is NO
   link between a memory and a card in the data (card_entries has no
   source-memory column; memories has no on-card flag). `isOnCard()` returns
   false. **Building the memory→card placement flow and whatever marks a
   memory as card-linked is a Phase 6 task** — section 6 of
   `phase6_card_suggestions_and_icons_design.md` is only partially built (the
   card tables exist; the placement flow and linkage do not). When you build
   it, `isOnCard()` is the single hook and book_5 lights up.

7. **The Memory Assistant page is the Phase 6 Archivist surface, and it is
   NOT built.** Only a "Coming soon" placeholder (`MemoryAssistantActivity`)
   exists. There is NO Archivist pipeline in code. The `proposals` table, the
   `origin='archivist'` column, and the Archivist endpoint/model settings are
   dormant storage only — nothing reads or writes them. Design the Memory
   Assistant with the owner from scratch (a fresh conversation is planned
   before it is built, because the injection/prompt plumbing is fragile).
