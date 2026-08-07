# Speak-GPT Memory System — Current Orientation

**Updated 2026-08-06 for Associative Memory Repair Contract Revision 26**

This folder contains a mixture of current technical contracts, exact UI copy, implementation audits, and older design-package material. **Do not read every file as one cumulative specification.** Several older files describe concepts that have already been retired from the live app.

## Document authority

Use this order:

1. **`project-plan.md` at repository root** — the only app-wide roadmap and scheduling authority. It decides which feature is active.
2. **Current code on `main`** — truth about what is already built. `MemoryStore.kt` is the live database-schema authority.
3. **`Memory System/external_memory_analysis_counterplan.md` Revision 26** — the binding technical repair contract for the owner-reported Associative Memory / API Memory Assistant defect. It is not a second app-wide roadmap.
4. **Focused exact-copy specifications** — where a control already has approved user-facing wording or layout, these files control that wording:
   - `memory_controls_and_pending_ui_copy.md`
   - `memory_retrieval_and_analysis_ui_copy.md`
5. **`owner_approved_rules.md` and roleplay-focused approved specs** — binding for the behavior they still cover, except where a later owner ruling explicitly supersedes them.
6. **`revision_25_binding_clarifications.md`** — historical decision record. Its still-binding decisions are incorporated into Revision 26; it no longer supplies a separate implementation order.
7. Other older design-package files are reference/history only unless the current repair contract or current code explicitly points to them for a narrow mechanic.

The unrelated old Revision 9 file that once shared the `external_memory_analysis_counterplan.md` name remains archived as `legacy/external_memory_analysis_counterplan_revision_9_legacy.md`.

## Current Associative Memory model

Do not resurrect retired fields or behavior from old schema/design files.

Current rules include:

- no Associative Memory title;
- user-owned Memory Types via stable IDs, with No Type allowed;
- `Lore` is not a fixed Associative Memory Type;
- importance is 0-5, new memories start at 0, and importance is ignored by retrieval while `Use Importance Ratings` is Off;
- no permanent memory provenance/source-chat identity;
- Companion memories target exactly one companion;
- Pending/Active/Archived/Superseded are lifecycle states;
- the AI proposes and the user decides;
- the AI never directly approves, deletes, replaces, archives, or supersedes a memory;
- API and computer-origin proposals ultimately use the same human Pending review boundaries.

## Existing foundations are not work to rebuild

Current `main` already contains substantial memory infrastructure, including:

- SQLCipher memory storage through database v26;
- Memory Types/domain services;
- on-device Librarian embedding retrieval with lexical fallback;
- exact duplicate classification;
- local semantic Possible Match detection;
- Memory Browser/Pending UI;
- Possible Match Review;
- atomic `Save & Edit Old Memory`, `Save & Supersede`, and `Save & Replace` actions;
- recorded supersession relationships and timestamps.

The root roadmap's rule applies: built features stay closed unless the owner reports a specific defect. Do not turn the Revision 26 repair into a general redesign of these systems.

## Current owner-reported defect

The current API Archivist sends conversation chunks to the paid model without first supplying a bounded set of relevant existing memories. It therefore cannot reliably know during extraction that a fact is already known, has changed, contradicts an existing memory, or meaningfully continues one.

Revision 26 repairs that path using the already-built local Librarian:

```text
conversation chunk
    ↓
local Librarian retrieves a small relevant existing-memory set
    ↓
one Archivist call receives both conversation + relevant existing memories
    ↓
validated additive proposals / related existing-memory IDs
    ↓
existing local duplicate + Possible Match safety layers
    ↓
Pending
    ↓
user chooses the resolution
```

Do **not** replace this with a paid model scanning the entire database or a second paid reconciliation call by default.

## Research reference

For the bounded pre-retrieval architecture, Revision 26 uses current Mem0 OSS V3 as an external reference: Mem0 embeds the new messages, retrieves a top-10 existing-memory set, and includes that context in a single LLM extraction call. Speak-GPT borrows the architecture, not the Python package or code.

Speak-GPT keeps its own stricter human-review model. Even if the Archivist identifies a related old memory, it only creates a proposal/hint. Existing memories change only through the owner's approved review actions.

## Coding-agent rules

Before changing Associative Memory behavior:

1. Read `project-plan.md` to confirm the work is actually assigned.
2. Read Revision 26 of `external_memory_analysis_counterplan.md`.
3. Read only the focused copy/spec files required by the part being changed.
4. Inspect current `main` before assuming a component is missing.
5. Reuse current `Librarian`, Pending, Possible Match, lifecycle, and database services where Revision 26 says they are foundations.
6. Do not build a standalone evaluation laboratory, graph database, second LLM reconciliation layer, new provenance system, or whole-memory-store LLM pass unless the owner explicitly adds that as a later feature.
7. Do not call a feature complete because CI is green. Use the completion terminology and end-to-end proof in Revision 26.

## Historical files

Files such as `companion_memory_schema.json`, older Archivist/enforcer specifications, and other v1.11-era design documents may contain useful historical reasoning but also contain retired concepts. They are **not permission to add a field or behavior that conflicts with current code, Revision 26, focused approved copy, or a later owner ruling**.

When historical text and live/current rules disagree, follow the current rules rather than trying to reconcile both into a larger system.
