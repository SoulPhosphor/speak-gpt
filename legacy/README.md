# Legacy planning documents

These are superseded roadmaps, work orders, and design documents. None of
them is the current plan. **`project-plan.md`** at the repository root is
the only active roadmap; current code on `main` is the truth about what
already exists.

Nothing here is deleted because troubleshooting a specific already-built
behavior sometimes needs the original design reasoning. If the owner
reports a problem with something already built, and understanding it
requires seeing why it was built that way, look here — by filename below,
not by reading the whole folder.

## Index

- **`memory-system-integration-plan.md`** — the original Phase 0–8 memory
  system roadmap (architecture decisions D1–D10, phase-by-phase landing
  notes through Phase 7). Superseded by `project-plan.md`.
- **`plan_one_page.md`** — the former single-page owner-facing plan and
  ruling record. Every still-binding ruling it held was copied into
  `project-plan.md`. Superseded.
- **`external_memory_analysis_counterplan_revision_9_legacy.md`** (renamed
  2026-08-07 from `external_memory_analysis_counterplan.md` — an unrelated,
  later document reuses that exact filename at
  `Memory System/external_memory_analysis_counterplan.md`; the rename
  keeps the two from ever being confused) — the "Steps 1.1–1.7" roadmap
  for the computer-review and RAG-correctness work, including the full
  technical audit of the retrieval/enforcer engine and the detailed
  `.sgmemory` package/import contract (§9). Its roadmap is superseded by
  `project-plan.md`; its technical detail (package layout, incremental
  full/delta exchange, encryption/lost-key behavior) is the deepest
  existing reference for building Computer Memory Review — consult it if
  `project-plan.md`'s Feature 3 requirements need more implementation
  detail than is inlined there.
- **`phase5_rework_work_order.md`** — completed work order for the Phase 5
  memory-structure/browser/pending rework. Built; historical only.
- **`rag_engine_work_order.md`** — completed work order for the retrieval
  engine, priority ladder, cooldown, campaign wiring, and the roleplay
  card + tag system (Stage 3.6). Built; historical only. Still cited by
  path from a few technical specs describing what was built and why.
- **`memory_health_round5_phase1_design.md`** — an earlier design pass for
  the same area. Superseded by the build; historical only.
- **`memory_settings_reorg_spec.md`** — design for a Memory Settings
  screen reorganization. Built; historical only.
- **`memory_assistant_design.md`** — the original single-screen Memory
  Assistant / Archivist design. Superseded by the three-way split (API
  Memory Assistant / Computer Memory Review / Memory Auditor) specified in
  `project-plan.md`.
- **`archivist_status_wording_spec.md`** — earlier Archivist status wording
  design. Superseded by the wording now inlined in `project-plan.md` and
  by wording already shipped in the app.
- **`roleplay_memory_deletion_fix.md`** — diagnosis and fix design for a
  roleplay memory deletion bug. The fix already landed; historical only.
- **`phase6_card_suggestions_and_icons_design.md`** — earlier design for
  roleplay card suggestions and the memory-row icon system. Partially
  superseded by the FINAL icon system recorded in
  `Memory System/owner_approved_rules.md`; historical only.
- **`phase6_owner_answers_2026-07-08.md`** — a dated record of owner
  answers from a specific planning conversation. Historical only; any
  answer still binding was carried into `owner_approved_rules.md` or
  `project-plan.md` at the time.
- **`phase6_unauthorized_strings.md`** — a dated audit of unapproved
  strings from a specific point in development. Historical only.
- **`app_adaptation_notes.md`** — the original notes on what the existing
  app needed to change (settings, sync hooks, UI areas, transcript
  capture) to support the memory system. Built; historical only.
