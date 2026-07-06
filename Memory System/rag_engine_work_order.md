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

**3.6 RP character card, two zones** (§13). Schema (additive migration):
ledger sections on roleplay characters — Spells, Skills, Special
abilities, Items, Special items, Weapons; entries = name (required),
description (optional); Items/Special items/Weapons entries carry a
quantity number. **The ledger is its own card-owned table** (§13's
card/memory split stays intact — ledger entries are NOT memories rows).
Editor UI: core fields (name, species, class, core personality) + the six
sections as add/edit/delete lists. Retrieval: core always injected while
that RP character is selected; **ledger entries are retrieved by
TRIGGER-MATCHING, not embeddings** (owner decision July 6 2026 — matches
§13's own "name acts as a trigger word" language): reuse
`LoreBookTriggerMatcher` against entry names, and a section's name
triggers the whole bounded section for that turn. **No change to the
`embeddings` schema; the Librarian stays memories-only.** The freshness
cooldown applies via 3.3's composite key (source_type = ledger). NO
Archivist suggestions yet (Phase 6) — do not build the
roleplay-suggestions toggle in this stage.

**3.7 Docs.** CLAUDE.md + integration plan updated to the new engine
behavior, including the prompt-layer contract above (fix CLAUDE.md's
"separate second system message" phrasing). AssemblyLog/debug screen
documented.

## Stage 4 — Model rules (§11 governs; build storage + UI + injection;
Archivist FILING arrives in Phase 6)

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
