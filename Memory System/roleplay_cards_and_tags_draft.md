# Roleplay Cards & Tags — Design Draft (July 7 2026)

> **STATUS: DRAFT — NOT YET OWNER-APPROVED. DO NOT BUILD FROM THIS FILE.**
>
> This document captures a design conversation between the owner and the AI
> on July 7 2026 so that nothing is lost to context limits. It does NOT
> outrank `owner_approved_rules.md`. It has two kinds of content, marked
> throughout:
>
> - **RULED** — the owner said it, in plain language, in chat on July 7 2026.
>   These are real decisions, but they still need to be folded into
>   `owner_approved_rules.md` once the whole package is approved together.
> - **PROPOSED** — the AI's draft, awaiting the owner's word-by-word
>   approval in chat. Not approved. Not buildable.
>
> When the owner approves the package, this file's contents get merged into
> `owner_approved_rules.md` / `rag_engine_work_order.md` and this file is
> retired. Until then, Stage 3.6 of the RAG work order (the six-section
> ledger build) stays PAUSED — the owner explicitly said not to build it
> from the earlier plan while this revision is in progress.

---

## 1. The two-zone principle, generalized — RULED

Every roleplay card type — user roleplay character, party member (NPC),
world, campaign — has the same two-zone shape:

- **Zone 1 — the core.** Small, stable, always given to the AI on every
  turn while that card is active in the chat. This is for facts whose
  absence produces silent contradictions — things the AI would never think
  to ask but must not get wrong (e.g. a world where the sun never rises).
- **Zone 2 — the body.** Everything else, retrieved on relevance:
  entry names act as trigger words, a section's name fires the whole
  section, and tags fire tagged entries (see §3). Not embedded; trigger
  matching only (this part carries over unchanged from the July 6 rulings).

**RULED — Zone 1 is visible and labeled in the UI.** Each card's page
openly shows which part is given to the AI every turn. The user must be
able to see exactly what the AI always knows. No hidden always-on text.

**RULED (implicitly, owner cautioned "smaller or more lean" for party
members):** Zone 1 sizes are budgeted per card type — a running campaign
injects the world core + user character core + party member cores every
turn, so cores multiply. Party member cores are the leanest.

A world is treated as its own entity even though it isn't a person —
same card machinery, same zones.

## 2. Quick Settings & campaigns — RULED

- **No campaign auto-creation, no roleplay detection.** Organic roleplay
  just happens in a normal chat with nothing selected. For a brand-new
  story the "memory" is the conversation itself in the context window;
  retrieval has nothing to retrieve and that is correct. Structure is
  created *afterwards*: the Archivist (Phase 6) reads finished
  conversations and PROPOSES the world/campaign/characters/party as
  drafts through the Pending flow. Mid-session the system only controls
  memory retention (transcript capture), never memory creation.
- **Quick Settings keeps independent, all-optional selectors:** world,
  campaign, user roleplay character. Any combination, including none, is
  valid (e.g. load a world mid-chat with no campaign and no character).
- **Selecting a campaign auto-fills the world and character selectors
  from the campaign's links, visibly.** While a campaign is selected those
  slots become displays, not free selectors.
- **No per-chat override of a campaign's facts.** Changing the world (or
  character) while a campaign is active asks one question: "Has the story
  moved? Update this campaign's world to X?" Yes = the campaign itself is
  edited (the change is real, logged, and part of the story). No = cancel.
  There is deliberately no "just for this chat" option — that would create
  two versions of the truth. Want the same setup run differently? That's a
  new campaign.
- **RULED — campaign changes ride the superseded-memory system.** When the
  story moves from Laguna Bay to Silver Hills, the campaign record updates,
  the old "takes place in Laguna Bay" fact is superseded (kept, out of
  retrieval), and the *transition itself* is recorded as an active campaign
  memory — because the AI needs to remember it used to be Laguna Bay, not
  just the human. Principle stated by the owner: **the memory system is
  for the AI and the human equally.**

## 3. The tag system (roleplay module) — RULED

(Approved by the owner July 7 2026, incorporating the organizer-AI draft
plus the owner's realm ruling.)

- **One shared tag pool across the whole roleplay module.** No separation
  by card type. World entries, campaign entries, character entries, NPC
  entries, ledger items, and roleplay-scoped memories all draw from and
  contribute to the same pool.
- **Tag input:** typing ≥3 characters fuzzy-searches existing tags in a
  dropdown; pick one or confirm the text to create a new tag, which
  immediately joins the pool.
- **Tags apply to anything, no per-entry limit.** An entry can carry many
  tags.
- **Tags as trigger words:** a tag name appearing in the user's message
  fires entries carrying that tag, across all cards in the ACTIVE
  campaign's material. Rides the existing trigger-matching machinery
  (no embeddings).
- **One-hop pull-along, budget-capped:** when an entry fires by name,
  entries sharing its tags are candidates to ride along, ranked by
  importance, capped by the existing injection budget. A broad tag can
  never flood the prompt.
- **Tags travel with injected entries** as a "connected to: …" line so the
  AI knows a connection exists even when the budget didn't pull the full
  related entry.
- **Cross-card tag view for the human:** tapping any tag anywhere shows
  every entry sharing it, grouped by card and section. Pure lookup, no AI.
  Browse view is GLOBAL across the roleplay module even though firing is
  campaign-scoped.
- **The tag bridge spans both storage cabinets** (the memory store and the
  card/ledger store): one pool, one link table that can point at a memory,
  a ledger entry, or a whole card. Existing memory tags on
  roleplay-scoped memories merge into this pool.
- **TWO TAG REALMS — the real-life/roleplay wall (RULED).** Real-life tags
  and roleplay tags are separate realms that never link, even when the
  word is identical ("pineapple" real ≠ "pineapple" fiction). A roleplay
  tag firing can never pull real-life memories into a story; a real-life
  tag can never pull fiction into ordinary conversation. Inside the
  roleplay realm it's a deliberate free-for-all. **Companions are real
  people and live on the real-life side**; when a companion plays in a
  campaign, the character they play is a separate roleplay-side record and
  all fiction attaches there, never to the companion's own card ("Ash
  likes tea" real vs "Ash's character loves ale" fiction). Boundary case:
  "user loves DMing sci-fi" is a real-life preference about the hobby —
  files real-side. The wall separates fiction from fact, not the topic of
  roleplay from everything else.
- **Common-word false triggers:** survivable via the budget cap for now; a
  per-tag "browse only, don't auto-trigger" switch is noted as a FUTURE
  option, not built up front.
- **Archivist tag suggestions** (proper nouns, places, names detected in
  conversations) arrive with Phase 6, through the normal approval flow.

## 4. Party members (NPC cards) — RULED

- **NPC is a card type, not a field.** User character cards are acted on
  by user input; NPC cards are narrated by the AI. No played-by field.
- **Top-level roster:** a "Party members" area in the Roleplay hub holds
  one card per NPC. Campaigns LINK the party members that apply (join,
  not ownership) so an NPC can travel between campaigns without rebuild.
- **Status field, four states, user-editable at any time:**
  Alive / Incapacitated / Dead / Enemy.
  - Enemy = no longer with the party but still exists in the world; card
    and inventory stay logged.
  - The Archivist may SUGGEST status changes later (Phase 6) for things
    the user didn't catch; the user is always able to change them by hand.
- **Status gates Zone 1** (PROPOSED, follows from the owner's lean-core
  instruction): Alive (and Incapacitated) party members inject their core
  every turn; Dead and Enemy drop to a single line ("Rose — dead, session
  12") or out of Zone 1 entirely, with everything still reachable through
  Zone 2 retrieval and tags.
- **Death handling:** the card is never deleted by death. Status → Dead;
  a short summary of who they were and how they died is filed as a
  campaign memory. Consistent with the approval laws, an AI-drafted
  summary lands as a DRAFT for user approval (or the user writes it when
  flipping the status) — never silently inserted. The character existed;
  the story remembers.

## 5. Deletion protection & archives — RULED

- **Warning on deleting anything linked to a campaign**, naming the
  campaign(s), offering "archive instead."
- **On true delete: per-deletion choice** — "delete their memories too?
  yes / no" — same pattern companions already have.
- **Universal visible Archive sections:** anything restorable must be
  visible somewhere, or the user has no way to restore it and no evidence
  it existed. Every archivable card list (worlds, campaigns, characters,
  party members; companions already have resting/retired) gets an Archive
  section at the bottom — hidden from active selection, restorable in one
  tap, clearly separated from active cards.
- **Cards and memories are separate:** archiving or deleting a card does
  not erase what the campaign remembers, unless the user chose "delete
  memories too" at delete time. (This supersedes the older teardown
  language in the schema docs that said deleting a character always
  deletes its memories — per-deletion choice replaces it.)

## 6. Card skeletons — PROPOSED (awaiting word-by-word approval)

> Everything in this section is the AI's draft for the owner to approve,
> amend, or strike, field by field. Nothing here is decided.

Shared rules for all Zone 2 sections: every entry carries **tags**; entry
names and section names are trigger words; entries are individually
retrievable; sections fire whole.

### 6a. User roleplay character card

Revised July 7 2026 from the owner's GLM-assisted draft, with the owner's
two amendments: fears/likes/dislikes/speech-style are NOT always-injected
(they work as trigger-fired Zone 2 traits — a fear of spiders fires when
"spiders" appears), and **Goals & Drives joins Zone 1** because goals and
grudges must influence what the AI writes BEFORE any trigger word appears
(the AI decides whether an orc walks into the tavern; a sworn grudge
against orcs has to already be in its head at that moment). This card
REPLACES the six-section ledger from the July 6 ruling.

- **Zone 1 (always given to the AI while selected — labeled openly in UI):**
  - Name
  - Species
  - Class
  - Core Personality — who they are at a glance
  - Physical Description — short; what they look like
  - Goals & Drives — what they want and what they've sworn, including
    grudges; campaign-independent
- **Zone 2 sections:**
  - **Abilities** (spells, skills, special abilities unified) — Name
    (required), Type (innate / trained / class feature / spell / other),
    Description (optional)
  - **Inventory** (items, weapons, armor unified) — Name (required),
    Type (mundane / magical / quest / weapon / armor / other),
    Quantity (required), Description (optional)
  - **Relationships** (people, organizations, factions) — Name (required),
    Relationship (ally / enemy / family / mentor / rival / member /
    other), Description (optional)
  - **Traits** (fears, likes, dislikes in one section) — Name (required;
    the trigger word, e.g. "spiders"), Type (fear / like / dislike),
    Description (optional)
  - **Backstory** — Title (required), Description (required); fires when
    history or personal context comes up
  - **Languages** — Name (required), Description (optional)

Known, accepted limitation: Zone 2 triggers run on the USER's message, so
if the AI introduces spiders first, the fear entry fires one turn late
(when the user responds). Cost of keeping retrieval cheap and predictable.

### 6b. Party member (NPC) card — RULED (owner finalized July 7 2026)

Identical in structure to the user character card, plus two NPC-only
Zone 1 fields. The earlier "deliberately leaner NPC core" idea is
DROPPED: leanness is the user's choice, not a structural cap — the fields
match the user card and the user decides how much goes in each ("if they
want to make an entire book, that's their decision"). No hard limits.

- **Zone 1 (always given to the AI while this NPC is in the active
  campaign's party):**
  - Name
  - Species
  - Class
  - Core Personality
  - Physical Description
  - Goals & Drives
  - Speech Style (optional; empty = nothing injected) — NPC-only because
    the AI voices NPCs while the user writes their own dialogue; exists
    for the orc with a southern drawl
  - Status (Alive / Incapacitated / Dead / Enemy) — always injected so
    the narrator knows how to treat the character
- **Zone 2 sections:** same six as the user character card (Abilities,
  Inventory, Relationships, Traits, Backstory, Languages).
- **Status gating (stands as proposed):** Alive and Incapacitated party
  members inject their full core each turn; Dead and Enemy members drop
  out of per-turn injection and are represented by the campaign card's
  party roster line (e.g. "Rose (dead)"), with their full card still
  reachable through Zone 2 retrieval, tags, and browsing.

### 6c. World card

- **Zone 1 (kept ruthlessly small):**
  - Name
  - Premise & vibe (short prose — what this world IS)
  - Hard rules (the never-get-this-wrong facts: cosmology, how magic
    works, "the sun never rises," tone constraints)
- **Zone 2 sections** (the owner's earlier draft list from the July 6
  deferral, now surfaced for the word-by-word confirmation that ruling
  required):
  - Regions & Biomes
  - Settlements
  - Points of Interest
  - Organizations & Guilds
  - Religions & Pantheons
  - Historical Events
  - Species & Races
  - Languages & Scripts

### 6d. Campaign card

- **Zone 1:**
  - Name
  - Current world (link; superseded history kept — see §2)
  - User's character (link)
  - Party roster line (names + statuses only, e.g. "Rose (dead), Garrick
    (alive), Thess (enemy)")
  - Story so far (short; Archivist-maintained via proposals, per the
    existing plan)
- **Zone 2 sections:**
  - Key events (the story's timeline, entry per event)
  - Locations visited
  - Notes (freeform user entries)
- **Links (not sections):** party members (join to the NPC roster),
  world, user character.

### Open questions for the owner on §6

1. ~~Character-card sections~~ — RESOLVED July 7: the owner supplied the
   revised structure (6a above) with Goals & Drives promoted to Zone 1 and
   fears/likes/dislikes demoted to a Zone 2 Traits section. Awaiting the
   owner's final word-by-word confirm of 6a/6b as written.
2. World Zone 2 list — confirm/amend the eight sections word by word
   (your earlier ruling explicitly required this at build time).
3. Campaign Zone 2 — is "Key events / Locations visited / Notes" right,
   too much, or missing something?
4. ~~Incapacitated NPCs~~ — RESOLVED July 7 by the finalized 6b: full
   core injects for Alive and Incapacitated; Dead and Enemy drop to the
   campaign roster line.

## 7. What stays untouched

- The lorebook system (independent low-RAM tier) — unaffected.
- The real-life memory system, Pending screen, browser, editor — all
  Stage 1–2 work stands; tags here only touch the ROLEPLAY realm.
- Companion cards and user personas remain untouchable by suggestions
  (law 3); the Archivist path exists only for roleplay cards.
- Stage 3 retrieval tasks (priority ladder, cooldown, scope rewrite)
  proceed as specced EXCEPT 3.6, which is paused pending this revision.
