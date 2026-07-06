# RAG Engine Work Order — Stages 3 & 4 (retrieval engine + model rules)

**For the implementing agent (a strong-model session; the owner assigned
stage 3 to the session that co-wrote the rules).** Prerequisites: stages
1–2 from `phase5_rework_work_order.md` are merged and CI-green. Read, in
order: the ⛔ gate in `CLAUDE.md`, `Memory System/owner_approved_rules.md`
(it OUTRANKS this file), the stage 1–2 work order (for what already
changed), then this file. The absolute rules from the stage 1–2 work
order apply verbatim here — bounded tasks, no unapproved words, stop and
ask, fragile list untouched, push-and-green per task.

Fragility warning specific to these stages: this work lives inside
`regularGPTResponse` and the enforcer — the prompt-assembly area CLAUDE.md
marks do-not-reorder. The stable first system message (persona + system
message, merged) must remain byte-stable per chat for provider prefix
caching; ALL memory content stays in the single separate second system
message. Never two competing memory messages.

## Stage 3 — Retrieval engine

**3.1 Scope eligibility rewrite** (rules §1, §3). Replace
`activeMemoriesForScope`'s logic with the seven-category model:
- Ordinary (non-roleplay) chat: eligible scopes = Global, Real life,
  Companion (the chat's active persona's companion), Project (if a
  project is selected in Quick Settings).
- Roleplay context (any of world/campaign/RP-character selected):
  eligible = Global, the selected World(s), Campaign, RP character,
  Project (if selected). **Real life is BLOCKED — the fiction wall (§3),
  no exceptions in this stage** (the Off/OOC-only/Always setting is
  future work, not yours). **Companion memories are ALSO blocked in RP
  unless the chat is explicitly using that companion as the active
  narrator/GM/context** (rules §3, owner-decided): use the campaign's
  DM/narrator assignment as the signal; if the data model has no such
  linkage for the chat's RP mode, stop and ask the owner what the
  explicit signal should be rather than inventing one.
- Draft/Archived/Superseded never eligible (§9). Companion draft gate
  and participation levels keep working.

**3.2 Priority ladder** (§12, INCLUDING §12.4 — not rigid). Deterministic
ranking: lorebook notes outrank memories (unchanged, they render inside
the same message, above); then scope specificity Campaign → RP character →
World → Project → Companion → Real life → Global; within a level:
relevance, importance, recency, tag hints. Tags are soft bonuses only —
never gatekeepers (§6). Per §12.4: eligibility defines the room first;
specificity is a strong scoring preference among comparably relevant
entries (implement as a weight/boost blended with relevance), NOT a hard
sort tier — a weakly-relevant specific entry must not beat a
strongly-relevant broader one, and the min-similarity floor still gates
everything.

**3.3 Freshness cooldown** (§10 — the exact approved text governs).
Track per chat, per entry, when each entry was last injected (persist it —
must survive process death; a small table keyed chat_id × entry_id is
fine). Suppress re-injection while fresh; refresh after N turns (a tuned
constant in code, start ~10, NOT a user setting) or when the old mention
falls out of the sent history window. First-time entries always inject;
an edit resets the entry's clock (hook the store's memory-edit path).
Applies to memories, Instruction rules, and (3.7) ledger entries.
Exempt: card cores, user system-prompt text, model-rules profiles.
Every suppression is visible in the AssemblyLog debug view.

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
  "entities"; no special always-injected sections). Prune the
  retrieval-policy `always_include` machinery accordingly — nothing is
  always included.
- KEEP: retrieved memories with provenance markers and HANDLE WITH
  CARE handling, lorebook notes rendered above memories, near-duplicate
  suppression, the one-soft-toast degradation contract (ANY failure →
  classic lorebook message, never blocks generation).
- Instruction-type memories render as handling rules when retrieved —
  same retrieval, distinct render so the model reads them as rules.
- Update AssemblyLog for everything removed/added (cooldown suppressions,
  ladder decisions).

**3.5 Project wiring.** The Quick Settings Project selector (built in
2.6) now affects eligibility per 3.1. Selector stays per-chat + copy
block (verify it is there).

**3.6 RP character card, two zones** (§13). Schema (additive migration):
ledger sections on roleplay characters — Spells, Skills, Special
abilities, Items, Special items, Weapons; entries = name (required),
description (optional); Items/Special items/Weapons entries carry a
quantity number. Editor UI: core fields (name, species, class, core
personality) + the six sections as add/edit/delete lists. Retrieval:
core always injected while that RP character is selected; ledger entries
indexed individually (embed on save like memories; name acts as a
trigger word) and retrieved by relevance with the cooldown; a section's
name triggers the whole bounded section for that turn. NO Archivist
suggestions yet (Phase 6) — do not build the roleplay-suggestions toggle
in this stage.

**3.7 Docs.** CLAUDE.md + integration plan updated to the new engine
behavior. AssemblyLog/debug screen documented.

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
- Injection: the selected profile's ACTIVE rules are appended to the
  stable first system message (they are always-on by explicit user
  selection — rules law 4/§11; per-chat stable, so prefix caching
  holds). Never into the memory message. No profile selected → zero
  bytes changed.
- The enforcer's old per-companion "model-adaptation note" is retired
  when this lands (superseded by model rules; adaptations columns go
  dormant like the others).
