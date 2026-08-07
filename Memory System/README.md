# Companion Memory System — Design Package (v1.11)

A layered, model-agnostic memory system for AI companions: global and per-companion memories, protected topics with handling instructions, communication modes, roleplay worlds and characters, and an AI "Archivist" that maintains it all under user-controlled autonomy dials. Design is complete; this package is the full specification for the coding phase.

> **⚠️ DOCUMENT HIERARCHY (2026-08-03, corrected 2026-08-07 — read this
> before anything else in this folder).** The current authority order is:
> 1. **`project-plan.md`** at the repository root — the only active
>    roadmap. It covers all remaining work (unfinished features only) and
>    inlines every still-binding requirement a builder needs; it governs
>    which feature is currently active to work on across the whole app.
>    Old phase lists and work orders (`memory-system-integration-plan.md`,
>    `plan_one_page.md`, `phase5_rework_work_order.md`,
>    `rag_engine_work_order.md`, and other build-plan/work-order/design
>    documents formerly in this folder) are superseded and archived in
>    **`legacy/`** at the repository root — historical record only, not to
>    be followed as a plan. This includes an unrelated older document once
>    also named `external_memory_analysis_counterplan.md`, renamed
>    2026-08-07 to `external_memory_analysis_counterplan_revision_9_legacy.md`
>    so it can never be confused with point 4 below.
> 2. **`owner_approved_rules.md`** (Revision 4) — outranks everything for
>    the memory-system behavior it covers.
> 3. **`roleplay_cards_and_tags_spec.md`** — authoritative roleplay
>    card + tag detail, incorporated into the rules as Revision 4. BUILT.
> 4. **`external_memory_analysis_counterplan.md`** (Revision 24,
>    binding-clarified by `revision_25_binding_clarifications.md`) — the
>    current memory-system plan: the binding record of the owner's
>    approved memory-system product decisions, required implementation
>    order, and completion gates, including everything already built
>    under its Phases 0-2. It governs memory-system product behavior and
>    build order; `project-plan.md` (point 1) governs which feature is
>    currently active to work on.
> 5. The rest of the v1.11 package below — background and still-valid
>    mechanics for what is already built, each file carrying its own ⚠️
>    banner naming what's superseded. (`companion_memory_schema.json` /
>    `seed_public_template.json` are the v1.11 export shape — the codec
>    still reads schema-shaped JSON, but retired concepts inside them —
>    modes, directives, entities, owner_profile, always_load — are
>    dormant fields, not features to build. So are `memories.title`,
>    `memories.kind` and its fixed six-value enumeration, and every
>    `memories.provenance` field: the Phase 1/2 memory-system rework
>    retired all three from the live schema. No Associative Memory has a
>    title or permanent provenance; Types are user-owned via a
>    `memory_types` table and `memories.type_id`, not this fixed `kind`
>    enum. See `MemoryStore.kt`'s `onCreate`/`onUpgrade` for the live
>    shape.)

1. **README.md** (this file) — orientation and glossary.
2. **memory_system_guide.md** — plain-language tour of the concepts.
3. **companion_memory_schema.json** — the canonical data schema. Also the export/import format.
4. **seed_public_template.json** — a neutral starter store demonstrating the schema. New users begin here.
5. **archivist_spec.md** — job description for the AI that reviews conversations and maintains memory.
6. **sqlite_table_plan.md** — the Android storage shape. The DDL has been executed and verified.
7. **enforcer_librarian_spec.md** — the runtime: retrieval, prompt assembly, change-set application, integration with the existing app (characters, activation prompts, lore books, multi-API switching).
8. **app_adaptation_notes.md** (now archived in `legacy/`) — the concrete changes the app needed (settings, sync hooks, new UI areas, transcript capture) — already built.
9. **archivist_prompt.md** — the literal operational prompt sent to the Archivist model, with its JSON output contract.
10. **prompt_assembly_template.md** — the literal system-prompt skeleton the enforcer fills each turn, plus the standing-packet compressor prompt.
11. **troubleshooting_guide.md** — symptom-level guide for the user: what problems look like in daily use, where to look, how to fix.

## Glossary (read this before touching code)

- **Companion** — an AI persona the user has a relationship with. In the app's current UI these live under Characters → Personas (recommended rename: "Companions"); in this store and these documents they are ALWAYS "companions." The app owns their character configuration (name, avatar, greeting, base prompt); the store owns their continuity (ID, memories, relationship history, hard limits).
- **roleplay_character** — a playable persona in a fictional world (e.g., the user's Mage). Deliberately named to avoid collision with the app's "characters." Never conflate the two.
- **Entity** — a durable real-life thing memories point at: a project, a person, a practice, a place.
- **World** — a fictional roleplay setting whose memories are isolated to its sessions.
- **Lore book** — the app's existing lightweight memory feature. Kept as the low-RAM tier; user-authored entries outrank system memories at injection (see enforcer spec).
- **Librarian** — the embedding + retrieval layer. Swappable; embeddings live in a sidecar keyed by model name. Recommended model: EmbeddingGemma (308M) on-device.
- **Enforcer** — the runtime code that assembles prompts, validates and applies changes, and guarantees invariants (e.g., protected memories always travel with their handling).
- **Archivist** — the AI that runs after conversations (manually triggered, queue-based) to write, correct, protect, and compost memories, governed by per-category autonomy dials (auto vs. propose).
- **Seed** — a complete memory store file in schema shape.
- **User persona** — a presentation variant of the one real user ('casual me'); changes appearance handed to the model, never identity, memories, or protections.

## Repo hygiene — read before committing

- Commit: all eleven documents above, kept together in one folder (e.g. /design) so the coding AI reads them as a set.
- **NEVER commit a personal seed.** A real seed contains the most private data a person has. Add your personal store and any `example_seed*.json` derived from real life to `.gitignore` immediately. The public template is the only seed that belongs in the repo.
- Database files, exports, and transcripts are likewise personal: gitignore them.
- The app ships with the neutral template; each user's real seed exists only on their device and in their own encrypted backups.
- Pick a license before publishing (MIT and Apache-2.0 are the common permissive choices; both include no-warranty clauses).

## Instructions for the coding AI

Read all eleven documents, then read the existing app codebase, then **ask your questions before writing code** — the specs intentionally leave code-level decisions (existing character storage format, API-switching internals, transcript capture) to be answered from the code. Non-negotiable invariants to preserve, wherever implementation details flex: protected memories are structurally inseparable from their handling; companion essence and hard limits change only through user action or accepted proposals; the Archivist can never expand its own permissions; every automatic change is logged with a prior-state snapshot and one-action undo; scope isolation (companion-private, world-isolated) is enforced in queries, not by convention; and all failure modes degrade gracefully mid-conversation. Build order suggestion: storage + migrations → librarian (embed/search) → enforcer turn loop → manual memory editor UI → Archivist pipeline → proposals/run-report UI.
