# Phase 5 Rework — Work Order for Stages 1 & 2

**For the implementing agent. Read, in this order, before writing any
code:**
1. The ⛔ OWNER APPROVAL GATE at the top of `CLAUDE.md` (you have already
   seen it if you read CLAUDE.md — reread it anyway).
2. `Memory System/owner_approved_rules.md` — the source of truth. It
   OUTRANKS this work order; if they ever disagree, the rules win and you
   stop and ask the owner.
3. This file, fully, before starting.

## Absolute rules for this job

- **Do ONLY the tasks listed for your stage.** No improvements, no
  refactors, no "while I'm here." If you believe something adjacent is
  broken, tell the owner in chat; do not fix it unbidden.
- **Never author user-visible words the owner hasn't approved.** Labels,
  hints, dialog text used here must come from the rules document or this
  work order; anything else — ask in chat first, in plain words.
- **Stop and ask** when: a task seems to require changing the meaning of a
  rule; a schema change isn't obviously additive; you're tempted to add a
  screen, field, or setting not listed; or two instructions seem to
  conflict.
- All strings in `res/values/strings.xml`. All per-chat settings added to
  the auto-naming copy block in `ChatActivity` (see CLAUDE.md — silent
  data loss when missed).
- The owner's device already runs DB v3. Whatever you ship must open the
  existing store without crashing. Single user; no backwards-compat
  gymnastics beyond that.
- Push each numbered task (or small coherent group) as its own commit;
  confirm the `Android Checks` workflow is green before the next. Commit
  messages explain *why*, in plain prose, per CLAUDE.md.
- Do not touch: the voice/VAD pipeline, lorebook matching, Ktor version,
  the generation funnel's message ordering, anything in the CLAUDE.md
  "Do not touch / fragile" list.
- Stages 3 (retrieval engine: cooldown, priority ladder, scope
  eligibility rewrite, RP-ledger indexing, RP character card zones) and 4
  (Model rules) are RESERVED for another session. Do not start them. Do
  not "prepare" for them beyond what your tasks say.

---

## Stage 1 — Trust repairs (do these first, in this order)

**1.1 Remove the unapproved fields from the companion page.**
`CompanionDetailActivity` + `activity_companion_detail.xml`: remove the
Essence, Relationship notes, and Hard limits fields/sections and their
save logic, and remove the read-only model-adaptations section. The page
keeps: companion name (read-only), the draft badge + Approve action, the
memory-participation selector, Save. Database columns stay; nothing
writes to them anymore. Delete the now-unused strings (`mem_comp_essence*`,
`mem_comp_relationship*`, `mem_comp_limits*`, `mem_comp_adaptation*`).
The Companions list row (`MemoryCompanionsActivity`) must stop showing
essence in the subtitle — show the participation label only.

**1.2 Companion delete.**
Add `MemoryStore.deleteCompanion(companionId, deleteMemories: Boolean)`
following the existing delete patterns (`deleteRoleplayCharacter` is the
closest model): tombstone into `deleted_ids`, and either delete the
companion's memories or leave them in place per the flag. Add a Delete
button on the companion page with a Material confirm dialog that states
what will happen and lets the user choose per-deletion whether that
companion's memories are deleted too (two checkboxes/options, not a
buried policy). Wording to use: title "Delete companion?", body
"This removes [name] from the memory system. Its persona/character card
is not affected." with the choice "Also delete this companion's
memories". After delete, finish back to the list and refresh.

**1.3 Delete the pre-written modes, forever.**
- Delete `DefaultOperatingData.defaultModes()` and every call that
  provisions default modes (keep `DEFAULT_POLICY_JSON` — it is neutral
  retrieval machinery, not content).
- One-time cleanup at store open (guarded by a meta flag so it runs
  once): delete all rows in `modes` WHERE origin='system'. User-authored
  rows (origin='user') are untouched.
- Verify the enforcer runs correctly with ZERO modes: `ModeSelection`
  and `Enforcer.assembleTurn` must degrade to "no mode section" with no
  crash and no log spam. If any code special-cases the five mode names,
  it must tolerate their absence (do not delete the recognition code in
  stage 1 — just make absence safe).

**1.4 Retire the Modes, Directives, Entities, and Owner profile screens.**
Remove `MemoryModesActivity`, `MemoryDirectivesActivity`,
`MemoryEntitiesActivity`, `OwnerProfileActivity`, their edit dialogs,
layouts, strings, and manifest entries, and their tiles from the hub.
Tables and store CRUD stay (dormant). Owner ruling (July 6 2026): people
in the user's life are real-world information — ordinary memories under
the scope/type system — NOT "entities" with their own cards; and what the
system knows about the user is Preference/Fact memories, NOT a profile
form. Neither concept exists in this app's memory system.

**1.5 Collapse the hub.** *(Corrected twice July 6 2026 by the owner —
this version is authoritative; there is NO "Advanced" screen.)*
- **The Memories browser is the single GLOBAL browser over ALL scopes and
  ALL types — roleplay included.** No memory anywhere is unreachable from
  it. (Its full filter/sort rebuild is stage 2.3; stage 1 only rewires
  navigation.)
- `MemoryManagerActivity` is removed. The "Browse & edit" button in
  Memory settings opens the Memories browser directly. The browser's
  action bar gains a link to **Companions**. (In stage 2.4 the Pending
  banner is pinned at the very top of the browser — pending first, then
  browsing.)
- **ONE Roleplay card on Settings** (the existing `RoleplayHubActivity`)
  holds ALL roleplay areas — Worlds, Campaigns, Roleplay characters. Do
  not duplicate them anywhere else, and do not scatter them.
- **Every individual page links into the main browser**: the companion
  detail page gets a **Memories** section/button at its bottom opening
  the browser pre-filtered to that companion; each world page, campaign
  page, and RP character page likewise has its Memories button into the
  SAME central browser pre-filtered (Phase 5 built scoped browser
  openings — keep them, but they must all be the one browser, and every
  page must have its button).
- Entities and Owner profile are REMOVED in task 1.4 — they get no rows,
  no home, nothing. Memory settings gains no new navigation.
- "My Personas" LEAVES the memory area entirely: its tile moves to the
  Characters hub (`CharactersActivity`), sitting with the other identity
  tiles.
- Do not build any navigation beyond the above.

**1.6 Update the docs.**
Update CLAUDE.md's memory bullets and the integration plan's Phase 5
note to describe the post-surgery state, briefly. Do not rewrite the
rules document.

## Stage 2 — Structure (after Stage 1 is green)

General: this stage restructures the memory record and rebuilds the
browser + pending flow per `owner_approved_rules.md` §§1–9 and §14. Read
those sections again before starting. DB changes are one additive
migration (bump `DATABASE_VERSION`, new `onUpgrade` step, fresh-install
path updated to match).

**2.1 Schema: scope, type, projects, statuses.**
- Repurpose `memories.kind` as the **Type** field holding exactly:
  `fact | preference | event | status | instruction | lore`. Migrate
  existing values: anything unrecognized becomes `fact` (single user;
  owner approved lossy simplicity — note it in the migration comment).
- Primary scope category per §1/§2:
  `global | real_life | companion | project | world | campaign |
  rp_character`, stored in `memories.scope`; targets reuse the existing
  link columns/join table (`memory_companions`, `world_id`,
  `campaign_id`, `roleplay_character_id`) plus a new `project_id`.
  Migrate existing rows: scope='companion' with links stays companion;
  scope='global' becomes `global`. Existing campaign/world-linked rows
  take the matching scope category.
- New `projects` table: id, name, status(active|archived), timestamps —
  nothing more elaborate (§4).
- `memories.status` gains `draft` as a legal value (§9). Existing rows
  keep their status.
- Remove all reads/writes of the `always_load` flag (§10) — column
  stays, dead.
- Source (§7) is DERIVED for display from existing provenance:
  `provenance_source == "user_entered"` → "Entered by hand"; imported
  rows → "Imported"; else the raw value. No schema change needed; if
  imported rows can't be distinguished reliably, say so in chat before
  inventing a marker.

**2.2 The memory editor (add/edit form).**
Per §2, §5, §6, §8: primary-scope dropdown (seven categories, labels:
Global / Real life / Companion / Project / World / Campaign / RP
character); when the category has named targets, a multi-select target
picker with an explanatory heading and removable pills (Global and Real
life show no picker); Type dropdown (six, with the §5 meanings as their
hint lines; Instruction's hint: "Applies when its topic comes up — for
always-on rules, write them on the card."); tags editor (§6); importance
dropdown ("1 Low importance / 2 Minor / 3 Notable / 4 High /
5 Critical"); title required. Protection/handling editing stays as is.

**2.3 The browser rebuild.**
Per the approved browser design — the FULL filtering system, not a
subset: search + sort (newest/oldest) + filters for **scope, type, tag,
source, AND status**, all at the top; active sort/filter state always
visible; state remembered when entering a memory and coming back and
when leaving/returning; a Reset control returning to defaults (newest,
no filters). The browser is global over ALL scopes and types (roleplay
included). Row layout, top to bottom: title → status → tags → first line
of the content that would be injected. Scoped doors keep working
(browser opened pre-filtered for a companion/world/campaign/RP
character — the buttons wired in 1.5).

**2.4 The Pending screen.**
Per §14 in full: pinned banner ("Pending memories (N) ›", only when N>0)
in the browser (scoped browsers open it pre-filtered); drafts grouped
under collapsible destination-scope headers each with select-all;
checkboxes; Select all / Select none; Accept and Delete on checked items
with a count confirm; tap-to-edit opens the editor pre-filled, keeps
draft on save, offers Accept; row = checkbox · title → destination
(scope · type) → first content line. (No Archivist exists yet, so this
screen will usually be empty — build it against status='draft' rows and
test by hand-setting a row to draft.)

**2.5 Memory settings additions.**
- **Reset memories** button: empties every memory-content table (memories,
  entities, modes, directives, worlds, campaigns, roleplay characters,
  user personas, transcripts, proposals, embeddings, change log —
  companions too) and reprovisions ONLY empty structure. Confirm dialog
  wording: "This will delete all your memories. It cannot be undone. Are
  you sure?" plus a checkbox "Save a backup file first" (starts checked;
  if unchecked, no backup — trust the user).
- **Remove everything imported** action (per §7), with a confirm dialog
  stating the count it will remove. If imported rows can't be reliably
  identified (see 2.1), skip this and tell the owner.
- Remove the "Suggest roleplay card updates" toggle from scope — that is
  stage 3+ material; do NOT add it yet.

**2.6 Quick Settings: Project selector.**
Optional per-chat Project dropdown (§4): none by default; listed from the
projects table; stored per chat; ADDED TO THE AUTO-NAMING COPY BLOCK.
Selection has no retrieval effect yet (stage 3 wires it) — say so in a
code comment, not in the UI.

**2.7 Docs.** Update CLAUDE.md and the integration plan to the new state.

## Verification (both stages)

Before every push: every new `R.*` reference resolves; new ids match the
inflated layout; XML well-formed; no `getSharedPreferences` in feature
code; migration bumps version + fresh-install path matches; per-chat
settings in the copy block. After push: `Android Checks` green. If CI
fails, fix forward on the same branch — never force-push.
