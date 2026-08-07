# Roadmap

**Updated 2026-08-07. This is the only app-wide scheduling roadmap.**

## Structure of truth

- **Current code on `main`** is the truth about what already exists.
- **This file** is the truth about which feature is active and the order of future features.
- **`Memory System/ACTIVE_ASSOCIATIVE_MEMORY_REPAIR.md`** is the binding technical implementation contract for the active memory repair. It is not a competing roadmap.
- **Narrow technical specifications** preserve exact approved behavior or wording. They do not create additional phase lists.
- The previous 2026-08-03 roadmap, including the complete approved wording for future Features 2-4, is preserved byte-for-byte at `planning/project-plan_2026-08-03_feature-specs.md`. Its old Feature 1 and model-assignment sections are superseded by this file. Its detailed specifications for the three future features below remain binding when those features become active.

## Ground rules

**One feature at a time.** Exactly one feature below may be active. Do not prepare, refactor for, or partially build a later feature before the current feature is complete end to end.

**A feature ships whole.** Internal stages are dependency order, not separate accomplishments. Do not call foundations, schemas, helper classes, green CI, or an isolated screen a completed feature.

**Built pieces stay closed unless the owner reports a specific defect.** Reuse existing Memory Browser, Pending, Possible Match Review, Librarian, Lorebooks, roleplay cards, and other shipped systems rather than reopening them. If the owner identifies a concrete defect, repair that defect narrowly.

**No subscription/model requirement is a completion gate.** Fable, Opus, Sonnet, or any other named model may be used when available, but the roadmap does not require the owner to maintain a particular paid model subscription. Completion is established by inspected code, meaningful automated tests, and the feature's real end-to-end device proof.

**Research before invention.** When an engineering question has established open-source or documented prior art, inspect that implementation before creating a bespoke experiment. A standalone evaluation harness or paid model tournament is not a substitute for building the actual product path unless the owner explicitly asks for one.

**User-facing wording.** Existing approved wording remains binding in the named feature/copy specifications. Do not invent replacement wording merely because a different model is implementing the feature.

**Legacy plans are not active plans.** Historical memory plans, work orders, old schemas, prompts, and completed audits live under `legacy/`. Do not treat those files as cumulative requirements for the current feature.

## Feature order

1. **Existing API Memory Assistant Repair** - ACTIVE
2. **Memory Budget Calculator**
3. **Computer Memory Review**
4. **Memory Auditor**

The ordering after Feature 1 is unchanged from the previous roadmap. Computer Memory Review still comes before Memory Auditor because the Auditor's computer route reuses its package/import machinery.

---

# Feature 1 - Existing API Memory Assistant Repair

**Status: Active.**

The owner reported a concrete defect after the previous roadmap was written: the current Archivist analyzes conversation chunks without first receiving a bounded set of relevant existing Associative Memories. Post-extraction duplicate/Possible Match checks exist, but the model itself cannot know during extraction whether information is already known, changed, contradictory, or a continuation.

The complete binding technical contract is:

**`Memory System/ACTIVE_ASSOCIATIVE_MEMORY_REPAIR.md` - Revision 26**

That document is the dependency-correct repair contract. Do not substitute Revision 24 phases, the old Phase 3 evaluation harness, an archived memory plan, or a different memory architecture.

## Builder execution directive

**This is an implementation order, not a request for another plan, audit, proposal, or architecture discussion.** When assigned Feature 1, make the required code and test changes in the repository.

1. Read current `main`, this roadmap, Revision 26, and the focused active specs named by Revision 26.
2. Confirm only the current code facts needed to begin. Do not stop after inventorying or summarizing the repository.
3. Create or use one Feature 1 implementation branch.
4. Execute Revision 26 Stages B, C, D, E, and F in that dependency order. Stage A is planning/status cleanup and is not a substitute for implementation.
5. Modify the real production code paths, database/migrations where required, prompts/parser/schema, tests, and only the existing UI surfaces explicitly required by Revision 26.
6. After each internal stage, run the relevant focused tests and inspect the actual diff. Continue to the next stage when that stage is technically sound. Do not report an internal stage as a shipped feature.
7. Do not stop because CI is green if the end-to-end behavior is not yet implemented.
8. Do not invent new product features, new user-facing wording, a new retrieval engine, another evaluation harness, or a second paid-model reconciliation path.
9. Continue until the automated Revision 26 checks are implemented and passing. The final on-device changed-fact proof is then performed before Feature 1 is declared complete.

If a genuinely blocking product decision is not answered by the approved documents, stop only that decision path and identify the exact missing decision. Do not turn an implementation question that can be answered from current code or established prior art into a new owner design task.

## Required outcome

The repaired normal API path is:

```text
new frozen conversation range
    ↓
token chunks
    ↓
LOCAL Librarian retrieves a bounded relevant existing-memory set
    ↓
ONE Archivist API call per chunk receives conversation + relevant memories
    ↓
validated additive proposals / related existing-memory IDs
    ↓
temporary complete-run collection
    ↓
existing exact + semantic Possible Match safety layers
    ↓
Pending
    ↓
user-controlled Save / Discard / Edit Old / Supersede / Replace
```

The paid model does not scan the entire memory database and there is no second paid reconciliation call in the normal path.

## Existing foundations are dependencies, not work to redo

Current database v26, Memory Types, Librarian, Pending, Possible Match, and the three existing resolution actions remain in place. The repair may modify those components only where required to connect the missing path or fix a concrete failing test.

The abandoned branch `claude/phase-3-eval-harness-16p9m2` is not a dependency and is not merged for this feature.

## Complete when

Feature 1 is complete only when Revision 26's automated checks pass **and** the actual app proves the changed-fact case end to end on device: an existing memory is locally retrieved before the Archivist request, a later conversation changes that fact, the new proposal reaches Pending with the old memory surfaced for review, the user can apply the intended resolution, and bookmark/failure behavior does not skip or partially file an incomplete frozen range.

CI green by itself is not completion.

---

# Feature 2 - Memory Budget Calculator

**Status: Locked until Feature 1 is complete.**

The full approved requirements and exact wording are preserved in `planning/project-plan_2026-08-03_feature-specs.md`, section **Feature 2 - Memory Budget Calculator**. Follow that section when this feature becomes active. Do not implement or refactor toward it during Feature 1.

---

# Feature 3 - Computer Memory Review

**Status: Locked until Features 1-2 are complete.**

The full approved requirements and exact wording are preserved in `planning/project-plan_2026-08-03_feature-specs.md`, section **Feature 3 - Computer Memory Review**.

When this feature becomes active, its Associative Memory review package must also preserve Revision 26's memory-awareness principle: the external reviewer receives the bounded/searchable read-only memory/target context required to distinguish new information from existing, changed, or conflicting information. Imported proposals still pass through the phone's validation, Possible Match, and Pending review boundary.

Do not pull Computer Memory Review into Feature 1.

---

# Feature 4 - Memory Auditor

**Status: Locked until Features 1-3 are complete.**

The full approved requirements and exact wording are preserved in `planning/project-plan_2026-08-03_feature-specs.md`, section **Feature 4 - Memory Auditor**.

This remains a separate later feature that audits the existing memory catalog. It is not part of normal conversation archiving and is not permission to make the Feature 1 Archivist scan the whole database.

---

## Not scheduled by this roadmap

A Zep/Graphiti-style graph layer is not part of the current repair. It may be considered as a separate future feature after ordinary Associative Memory works end to end. The current repair must preserve stable memory IDs, scope/target links, timestamps, and supersession history so a future graph layer could reference existing data without discarding this implementation.

Other parked app features remain parked unless the owner explicitly adds them to this roadmap.
