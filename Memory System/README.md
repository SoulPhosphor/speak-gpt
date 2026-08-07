# Speak-GPT Memory System

**Current authority map — 2026-08-07**

## ACTIVE WORK

The one active Associative Memory implementation contract is:

**`ACTIVE_ASSOCIATIVE_MEMORY_REPAIR.md` — Revision 26**

If you are a coding agent assigned the current memory work, read `project-plan.md` at repository root first, then this active contract, then only the narrow supporting documents it names. **Do not read the legacy memory plans as cumulative requirements.**

## What controls what

1. **`project-plan.md` at repository root** — the only app-wide scheduling roadmap. It says which feature is active.
2. **Current code on `main`** — truth about what already exists. `MemoryStore.kt` is the live database-schema authority.
3. **`ACTIVE_ASSOCIATIVE_MEMORY_REPAIR.md` Revision 26** — the binding build contract for the active API Memory Assistant / Associative Memory repair.
4. **Focused active references in this folder** — exact approved behavior/copy that Revision 26 relies on:
   - `memory_controls_and_pending_ui_copy.md`
   - `memory_retrieval_and_analysis_ui_copy.md`
   - `owner_approved_rules.md` where not superseded by later rulings
   - `revision_25_binding_clarifications.md` as a historical decision record already incorporated into Revision 26
5. **`reference/`** — built-system specifications that may be consulted only when the active repair actually touches that subsystem.
6. **`legacy/` at repository root** — historical plans, audits, old schemas, old prompts, and retired design packages. They are not implementation instructions.

## Active repair in one line

```text
conversation chunk
    -> LOCAL Librarian retrieves bounded relevant existing memories
    -> ONE Archivist call receives conversation + those memories
    -> validated additive proposals
    -> existing local duplicate / Possible Match safety checks
    -> Pending
    -> user decides Save / Discard / Edit / Supersede / Replace
```

The paid model does not scan the whole memory database and the normal path does not add a second paid reconciliation call.

## Existing foundations are not new work

Current `main` already has database v26, Memory Types, the on-device Librarian, Pending, Possible Match, and the existing resolution actions. Revision 26 tells the builder how to connect and repair the missing path. Do not rebuild these components merely because an older archived plan describes them.

## Coding-agent rule

When Feature 1 is assigned, **implement Revision 26**. Do not stop after producing another audit, plan, architecture summary, benchmark harness, or proposal. Modify the production code and tests in the dependency order specified there, and use the end-to-end changed-fact proof as the completion gate.

## Historical material

The former top-level v1.11 design-package files and completed implementation audits were moved out of this folder to reduce accidental authority confusion:

- `legacy/memory-system-v1.11/`
- `legacy/memory-system-repair-history/`

Consult those folders only for targeted historical troubleshooting. Their presence in the repository does not make them active requirements.
