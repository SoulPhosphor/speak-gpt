# RAG Engine Work Order — Stages 3 & 4 (retrieval engine + model rules)

**Revised July 6 2026 after the owner's second-pass review resolved the
spec conflicts a reviewer instance found. Every "stop and ask" that had an
owner answer is resolved inline below; the owner's rulings are also folded
into `owner_approved_rules.md` (Revision 3). This file is written assuming
Stages 1–2 are COMPLETE** — i.e. the Stage-2 record structure already
exists: seven-category `memories.scope`, the six-type `kind` field, the
`projects` table + `project_id`, `memories.status` including `draft`, the
rebuilt global browser + Pending flow, and the Quick Settings Project
selector (per-chat, in the auto-naming copy block, no retrieval effect
yet).

**Revised again July 7 2026 (Revision 4 of the rules).** Build state at
this revision: **tasks 3.0–3.5 plus a 3.7 docs pass are BUILT on branch
`claude/memory-work-stage-3-r13ca4`** (commits `afa192e`→`e1aa873`; not
yet merged to `main`), which honored the pause point before 3.6. That
pause is now RESOLVED: the owner designed and approved the complete
roleplay card + tag system — **`roleplay_cards_and_tags_spec.md`, which
is the authoritative spec for everything 3.6 builds; read it in full,
including its §9 agent rules, before starting.** Task 3.6 below is
RESCOPED to implement that spec (the old six-section-ledger 3.6 is
superseded); 3.7 must be re-run after it. **Merge order for the 3.6
agent's base:** the stage-3 branch merges to `main` first, then the
spec/docs branch `claude/character-card-structure-ypmm8l`, then branch
for 3.6 from the result — 3.6 needs BOTH the built 3.0–3.5 engine and
these documents.

**Build state, end of July 7 2026: Stage 3 is COMPLETE.** The merge
order above was carried out, and **the rescoped task 3.6 — all
sub-tasks 3.6a–f — plus the re-run 3.7 docs pass are BUILT on branch
`claude/stage-3-6-rag-engine-9f0gc2`** (each sub-task pushed and
CI-green individually). The spec gained two mid-build addenda holding
owner rulings that supersede parts of its earlier text — §8a (3.6b
on-screen wording; the old pre-card free-text columns are DORMANT,
never shown or migrated; DB v8 added fresh world-core columns) and
§8b (3.6c campaign-selector wording, the Plot Ledger history note, the
locked character slot, and the standing dialogs-not-toasts ruling) —
read both before touching anything 3.6 built. Stage 4 (Model rules,
below) has NOT been started.

**For the implementing agent (a strong-model session; the owner assigned
stage 3 to the session that co-wrote the rules).** Prerequisites: stages
1–2 from `phase5_rework_work_order.md` are merged and CI-green. Read, in
order: the ⛔ gate in `CLAUDE.md`, `Memory System/owner_approved_rules.md`
(it OUTRANKS this file — read Revision 3's changes), the stage 1–2 work
order (for what already changed), then this file. The absolute rules from
the stage 1–2 work order apply verbatim here — bounded tasks, no
unapproved words, stop and ask, fragile list untouched, push-and-green per
task.

## Prompt-layer contract (fragility warning — read before 3.4 and Stage 4)

This work lives inside `regularGPTResponse` and the enforcer — the
prompt-assembly area CLAUDE.md marks do-not-reorder. The prompt layers,
every turn, are FIXED and deterministic:

1. **The stable persona/system prefix** — byte-identical, same position
   every request.
2. **The selected model-rules block** (Stage 4) — its own prompt-layer
   block, stable per chat/model-profile; absent entirely when no profile
   is selected.
3. **The assembled memory message** — turn-variable; ALL memory content
   (enforcer output, lore notes, ledger, instruction rules) lives here
   and ONLY here.
4. **Chat history + the current turn.**

Same blocks, same order, same wording every turn. Never reorder by
convenience, by retrieval results, by object iteration order, by
timestamps, or by UI display order. Never two competing memory messages.
(Note: when a model-rules block is present the memory message is the
THIRD system block, not the second — older docs' "single separate second
system message" phrasing describes the pre-Stage-4 layout; three or more
system messages are fine. Update CLAUDE.md's phrasing in the doc passes.)

**Caching reality:** explicit cache breakpoints are Anthropic-API-only;
z.ai/OpenAI-compatible endpoints auto-cache the longest identical prefix.
Implement the ordering + byte-stability discipline unconditionally — that
is what earns the caching — and use explicit breakpoints ONLY if a
provider supports them. Do not promise a breakpoint the endpoint can't
honor.

## Stage 3 — Retrieval engine

**3.0 Campaign wiring (prerequisite for 3.1 — OWNER-CONFIRMED July 6
2026; this was previously on the deferred list and is deferred no
longer).** Quick Settings gains a per-chat **Campaign** selector
(following the existing world/RP-character selector pattern): none by
default; listed from active campaigns; stored per chat via a
`Preferences` getter/setter; **ADDED TO THE AUTO-NAMING COPY BLOCK.**
Thread the selected `campaignId` — and the already-selectable
`roleplayCharacterId`, which today is NOT threaded into retrieval either —
through `TurnInput` → `Enforcer.assembleTurn` →
`activeMemoriesForScope` / `Librarian.search` (both already accept
`campaignId`; `MemoryStore.campaignScope` already resolves a campaign's
world / RP character / GM companion).
**The narrator signal, defined:** the chat is "explicitly using that
companion as the active narrator/GM/context" (rules §3) exactly when the
selected campaign's GM/narrator companion == the active chat companion
(the chat's active persona's companion record). The old "stop and ask
what the signal should be" is resolved — the owner chose Quick-Settings
campaign selection as the explicit signal.

**3.1 Scope eligibility rewrite** (rules §1, §3, and §4 AS REVISED in
Revision 3). Replace `activeMemoriesForScope`'s logic with the
seven-category model:
- Ordinary (non-roleplay) chat: eligible scopes = Global, Real life,
  Companion (the chat's active persona's companion), **and Project —
  project-scoped memories are eligible whenever semantically relevant,
  EVEN WHEN no project is selected** (owner revision of §4; selection is
  a ranking boost, not a gate — see 3.2 and 3.5).
- Roleplay context (any of world/campaign/RP-character selected):
  eligible = Global, the selected World(s), Campaign, RP character.
  **Real life is BLOCKED — the fiction wall (§3), no exceptions in this
  stage** (the Off/OOC-only/Always setting is future work, not yours).
  **Project memories are BLOCKED in roleplay by default** (§4 as
  revised). **Companion memories are blocked in RP unless EITHER** (a)
  the narrator signal from 3.0 matches, **OR** (b) the new global Memory
  settings toggle is ON — build it in this task:
  **"Allow active companion memories in roleplay"**, a global pref in
  Memory settings, **default OFF.** OFF: companion memories do not enter
  RP/campaign mode (beyond the narrator path). ON: the active chat
  companion's approved active memories are eligible during RP/campaign
  mode, subject to normal scope, status, relevance, cooldown, and
  retrieval rules. The toggle allows participation in retrieval; it never
  forces memories into the prompt, and it does NOT require narrator/GM
  status (narrator/GM stays a separate eligibility path via 3.0).
- Draft/Archived/Superseded never eligible (§9). Companion draft gate
  and participation levels keep working.

**3.2 Priority ladder** (§12, INCLUDING §12.4 — not rigid). Deterministic
ranking: lorebook notes outrank memories (unchanged, they render inside
the same message, above); then scope specificity Campaign → RP character →
World → Project → Companion → Real life → Global; within a level:
relevance, importance, recency, tag hints. Tags are soft bonuses only —
never gatekeepers (§6). **A selected project grants its project's
memories a ranking boost** (§4 as revised — implement like the
specificity boost, blended with relevance). Per §12.4: eligibility
defines the room first; specificity is a strong scoring preference among
comparably relevant entries (implement as a weight/boost blended with
relevance), NOT a hard sort tier — a weakly-relevant specific entry must
not beat a strongly-relevant broader one, and the min-similarity floor
still gates everything.

**3.3 Freshness cooldown** (§10 — the exact approved text governs).
Track per chat, per entry, when each entry was last injected (persist it —
must survive process death). **The table key is
`(chat_id, source_type, entry_id)`** — `source_type` distinguishes
memories from ledger entries (3.6) so ids from different tables can't
collide. Suppress re-injection while fresh; refresh after N turns (a
tuned constant in code, start ~10, NOT a user setting). **Ship the
after-N-turns half only.** The "when the old mention falls out of the
sent history window" half is DEFERRED with a code comment explaining why:
`TurnInput` today carries only a short recent-context digest (~4 turns /
200 chars), never the real sent window — do not widen `TurnInput` for
this in this stage. First-time entries always inject; an edit resets the
entry's clock (hook the store's memory-edit path). Applies to memories,
Instruction rules, and (3.6) ledger entries. Exempt: card cores, user
system-prompt text, model-rules profiles. Every suppression is visible in
the AssemblyLog debug view.

**3.4 Enforcer rework to match the rules.** In `Enforcer.assembleTurn`:
- REMOVE from assembly: the directives section (retired), always-load
  memories (flag dead), the modes section and mode detection
  (machinery dormant — no mode ever renders until the owner designs
  their return), suggested_mode handling, the companion hard-limits
  render (hard limits are card text the user writes; the store columns
  are dormant), **the entire standing packet — the owner-portrait line
  and entity summaries are DEAD** (owner ruling July 6 2026: what the
  system knows about the user is Preference/Fact memories retrieved by
  relevance, and people in the user's life are ordinary memories, not
  "entities"; no special always-injected sections). **Tear down
  `StandingPacketManager` USAGE entirely, not just its render — it fires
  a background Archivist-model call that compresses the packet into a
  meta cache, and killing only the render would run the Archivist on
  empty input every turn.** (The global Archivist-model *setting* stays —
  Phase 6 uses it.) Prune the retrieval-policy `always_include`
  machinery accordingly — nothing is always included.
- **Also retire the per-companion "model-adaptation note" render HERE**
  (moved forward from Stage 4): its data columns have been dormant since
  Stage 1 and the render would emit an empty section between stages;
  Stage 4's model rules supersede it.
- KEEP: retrieved memories with provenance markers and HANDLE WITH
  CARE handling, lorebook notes rendered above memories, near-duplicate
  suppression, the one-soft-toast degradation contract (ANY failure →
  classic lorebook message, never blocks generation).
- Instruction-type memories render as handling rules when retrieved —
  same retrieval, distinct render so the model reads them as rules.
- Update AssemblyLog for everything removed/added (cooldown suppressions,
  ladder decisions, project boosts, companion-in-RP eligibility path).

**3.5 Project wiring (semantics REVISED — §4 Revision 3).** The Quick
Settings Project selector (built in 2.6) is a **ranking boost, NOT an
eligibility gate**: with no project selected, project-scoped memories
still retrieve on semantic relevance in ordinary chats; selecting a
project boosts that project's memories (3.2); project memories stay
blocked in roleplay by default (3.1). Verify the selector's per-chat pref
is in the auto-naming copy block, and verify no UI string from Stage 2
promises the old "none selected → project memories stay quiet" behavior
(Stage 2 was told to keep retrieval semantics out of the UI — check
anyway; neutralize any such wording, keeping the label and "None"
option).

**~~⛔ PAUSE POINT~~ — RESOLVED July 6–7 2026.** The ask this pause
existed for happened: the owner designed the full roleplay layer —
world and campaign cards included — and approved it front to back.
`roleplay_cards_and_tags_spec.md` is the result and the authority. The
old 3.6 text (six-section ledger on RP characters only) is superseded by
the rescoped 3.6 below.

**3.6 Roleplay cards + tag system (RESCOPED July 7 2026 — implements
`roleplay_cards_and_tags_spec.md` in full).** Read the spec first, all
of it, especially §9 (binding agent rules: build exactly what's written;
no new fields/sections/renames; gaps = stop and ask in plain chat; no
pre-written content; no live-write paths). What carries over unchanged
from the old task: **card/ledger retrieval is TRIGGER-MATCHED
(`LoreBookTriggerMatcher`), never embedded — the embeddings schema and
the Librarian stay memories-only**; card content lives in card-owned
tables, NOT memories rows; the freshness cooldown rides 3.3's composite
key with a distinct `source_type` per entry table; **NO Archivist
anything yet** (suggestions, auto-summaries, the roleplay-suggestions
toggle — all Phase 6); every per-chat selection goes in the auto-naming
copy block. Two laws bind every sub-task: **no automatic process writes
any card/ledger/tag/memory mid-conversation** (a user-confirmed dialog
is a user edit and is fine), and **nothing ships pre-populated** — no
sample cards, no starter tags. Sub-tasks, each bounded and
push-and-green in order:

- **3.6a Schema (additive migrations).** Four card shapes per spec §6:
  the REVISED user RP-character card (Zone 1: name, species, class, core
  personality, physical description, goals & drives; Zone 2 sections:
  Abilities / Inventory / Relationships / Traits / Backstory / Languages,
  with the spec's per-section type lists; Inventory entries carry
  quantity); **NPC party-member cards** — same shape plus speech style
  (optional) and status `alive|incapacitated|dead|enemy` — as a
  top-level roster with a campaign↔NPC join table; the world card
  (Zone 1: premise/vibe, cosmology, magic rules; grouped Zone 2 sections
  per §6c including parent-chained geography — Settlements carry a
  Region parent, Points of Interest a Settlement-or-Region parent); the
  campaign card (quest anchor with side objectives, active scene, plot
  ledger, campaign cast overlays — world-NPC link + this-campaign
  disposition — campaign locations, reliquary, notes). Tag storage: one
  roleplay-realm tag pool + a polymorphic link table
  (`tag_id, target_type, target_id`) reaching card entries, whole cards,
  and roleplay-scoped memories (the bridge between the two stores), plus
  a per-tag `auto_trigger` flag defaulting ON. Tombstones on delete,
  same store patterns as everything else. All user-entered fields
  multi-line, no length caps.
- **3.6b Card editors + rosters (UI).** Full-screen activities (the
  `MemoryEditorActivity` pattern). On EVERY card: Zone 1 visibly labeled
  as what the AI is told every turn (spec §1), a prompt-cost estimate
  with a large-core warning (never a limit), and tag chips on every
  entry (≥3-char fuzzy dropdown, create-on-confirm). Party-members
  roster + visible Archive sections at the bottom of every roleplay card
  list; NPC status control (user-editable anytime); the world-NPC →
  party-member promotion flow (party card becomes source of truth, the
  world entry becomes lightweight lore pointing at it). Findability per
  spec §8.
- **3.6c Quick Settings campaign behavior.** Selecting a campaign
  auto-fills the world/character selectors from its links — visibly,
  and they become displays while the campaign is selected. Changing one
  asks: "Has the story moved? Update this campaign's world to X?"
  Confirm = edit the campaign itself (an explicit user edit): supersede
  the old fact, keep it as history, record the transition as a campaign
  memory. Cancel = nothing. **There is no just-this-chat override — do
  not add one.** All-optional selectors stay: any combination including
  none is valid (organic roleplay needs no setup; structure arrives
  later via Phase 6 proposals).
- **3.6d Injection wiring.** Everything renders inside the enforcer's
  SINGLE memory message (the prompt-layer contract above governs;
  deterministic order, byte-stable rendering). Zone 1 cores render in a
  FIXED order ahead of lore notes and retrieved content: world core →
  campaign bookmark (quest anchor + active scene) → user character core
  → active party-member cores → the generated roster line for
  dead/enemy members ("Rose — dead"). Cores are cooldown-exempt (§10);
  **status gates NPC cores** (alive/incapacitated inject; dead/enemy get
  the roster line only). Zone 2 retrieval: entry names and SECTION names
  trigger-match the latest user message (**group headers do NOT
  trigger**); tag names with `auto_trigger` ON fire their tagged entries
  — active campaign's material only; **one-hop pull-along**: entries
  sharing a fired entry's tags join as importance-ranked, budget-capped
  candidates (browse-only tags still count for pull-along and for the
  "connected to:" line rendered with every injected entry — browse-only
  silences ONLY message-text matching, spec §3). Cooldown applies per
  `source_type`; AssemblyLog records every fire, suppression, and
  pull-along so retrieval is diagnosable.
- **3.6e Tags screens.** The per-tag browse-only switch (built NOW, not
  future — owner promotion July 7); the Tags index screen in the
  Roleplay hub (roleplay realm only; user-created tags only, nothing
  preloaded; per-tag trigger mode visible); the cross-card tag view
  grouped by the PREDEFINED card/section categories, including
  roleplay-scoped memories via the link table. **REALM WALL everywhere:**
  roleplay tag machinery never touches real-life memories or their tags,
  and vice versa — real-life tags keep the Memories browser as their
  only door.
- **3.6f Deletion + archive behavior.** Deleting anything linked to a
  campaign warns, names the campaign(s), offers archive. True delete
  asks per-deletion: "delete their memories too? yes/no". Archive keeps
  links intact (card hidden from active selectors); delete scrubs links;
  any surviving reference renders "(archived card)" / "(deleted card)" —
  never a silent hole. NPC death is a status change, never a delete;
  its summary memory is user-written (or later an approved Phase 6
  draft), never auto-inserted.

**3.7 Docs (RE-RUN after 3.6 — the first pass on the stage-3 branch
covered only 3.0–3.5).** CLAUDE.md + integration plan updated to the
built roleplay layer (cards, tags, campaign behavior, injection order,
new DB version), including the prompt-layer contract above (fix
CLAUDE.md's "separate second system message" phrasing if the stage-3
branch pass hasn't already). AssemblyLog/debug additions documented.
Note the spec file's build status in its §7.

## Stage 4 — Model rules (§11 governs; build storage + UI + injection;
Archivist FILING arrives in Phase 6)

> **BUILT — July 2026 (branch `claude/stage-4-rescoped-wqib79`). §11 was
> REDESIGNED in chat to Revision 5 before/during the build, so the shipped
> shape differs from the pre-Revision-5 bullets below: the model STRING is the
> primary identity (no profiles/groups), TAGS organize rules (a separate
> pool), rules apply AUTOMATICALLY and are ON by default (global
> "Automatically Apply Model Rules" default + per-chat "Apply Model Rules"
> toggle), and matching rules are NEVER truncated. Storage is DB v10
> (`model_rules` with its own `model_strings_json`, `model_rule_tags`,
> `model_rule_tag_links`); UI is the AI System Settings screen + the Model
> rules browser/editor/tag screens; injection matches by model string
> (`ModelRuleMatcher`). "Needs review"/Pending is built but empty until Phase
> 6. See `owner_approved_rules.md` §11 (Revision 5) and CLAUDE.md for the
> authoritative built shape. The bullets below are the ORIGINAL pre-redesign
> plan, kept for history.**

- Storage: profiles (id, user nickname, list of model strings, timestamps)
  + rules (id, profile_id nullable — null = Needs review/unassigned,
  text, status draft|active, timestamps). Encrypted store, tombstones on
  delete, same patterns as the rest.
- Settings: a "Model rules" card → profiles list with **"Needs review"
  pinned at top whenever any unassigned/draft rule exists** (build the
  section now; it stays empty until Phase 6 files into it). Profile page:
  nickname, model strings (add/remove), rules list (add/edit/delete);
  per draft: accept / delete / move (move offers to add the source model
  string to the destination profile).
- Quick Settings: optional "Model rules" dropdown per chat, default
  none — **by default no model rules apply**. Optional "(matches this
  chat's model)" hint next to a profile whose string matches the chat's
  model id (case-insensitive contains, provider prefix ignored) — never
  auto-applied. PER-CHAT → AUTO-NAMING COPY BLOCK.
- Injection (REPLACES the earlier "append to the stable first system
  message" wording, which could have mutated the cached prefix — owner
  wording follows): **"Keep the stable persona/system prefix
  byte-identical and in deterministic order every turn. Place selected
  model rules after the cached stable prefix as their own prompt-layer
  block, or only after a cache breakpoint that preserves the stable
  prefix. Model rules must be ordered deterministically. Never put model
  rules in the memory message."** Same blocks, same order, same wording
  every turn (see the prompt-layer contract at the top of this file).
  No profile selected → the block is absent entirely; zero bytes of the
  request change.
- (The enforcer's old per-companion "model-adaptation note" was already
  retired in 3.4 — nothing further to remove here; the adaptations
  columns stay dormant like the others.)
