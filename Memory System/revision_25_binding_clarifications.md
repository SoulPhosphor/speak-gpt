# Speak-GPT Memory System: Revision 25 Binding Clarifications

**2026-08-04**

This document records decisions approved after Revision 24 of `external_memory_analysis_counterplan.md` and after the Phase 0 audit began. It is binding wherever older wording in the canonical plan or `memory_implementation_audit.md` conflicts with it. It adds no new feature beyond the approved decisions below.

## 1. Phase 0 Status

Phase 0 is complete. `Memory System/memory_implementation_audit.md` is the implementation map for the existing code.

The audit identifies existing behavior and risks. Existing code is not approval. Items listed by the audit as unresolved are resolved below where the owner has already ruled.

## 2. Companion Deletion

Deleting a companion permanently deletes every memory targeted specifically to that companion.

- orphaning companion memories is not approved;
- an unchecked or optional “delete memories” choice is not approved;
- every companion-deletion path must use the same confirmed cascade;
- the confirmation must name the companion, state that companion memories will be permanently deleted, show the count where available, and retain any other existing deletion consequences;
- General memories are not deleted merely because they were extracted from conversations with that companion.

The separate `CompanionDetailActivity` path may remain only if it performs the same unconditional companion-memory cascade. Its exact screen location is an implementation detail, not a product decision.

## 3. Companion Target

A Companion memory targets one specific companion.

Do not create new multi-companion memories. If the audit finds existing records with several companion links, preserve them during migration and report them rather than deleting data, but new candidate validation and ordinary editing follow the one-companion rule.

## 4. Importance Migration

Existing stored importance values are preserved unchanged.

- new memories begin at 0;
- 0 is neutral;
- while `Use Importance Ratings` is Off, every stored importance value is ignored by retrieval but remains stored;
- turning the feature back On reveals the preserved values;
- do not add a conversion flag, warning system, or second legacy-rating mechanism unless later approved.

## 5. Provenance

Permanent memory provenance is rejected.

- source chat, chat name, transcript rows, stated/inferred markers, confidence markers, excerpts, run IDs, and chunk IDs do not belong in Pending or saved Associative Memories;
- do not render stated/inferred provenance markers to the model as part of retrieved memory;
- only minimal temporary run bookkeeping outside the memory object is allowed for safe completion, retry, and duplicate prevention.

## 6. Bookmark Target Architecture

The target architecture remains one durable bookmark per chat plus minimal temporary run state.

The Phase 0 audit may recommend the safest migration from the current transcript-row queue, but it may not treat whether the product wants the bookmark architecture as undecided.

## 7. Analysis Chunk Sizes

The approved initial choices are:

- `Auto`
- `Small · About 4,000 Tokens`
- `Standard · About 8,000 Tokens`
- `Large · About 16,000 Tokens`
- `Custom`

The evaluation harness may recommend later adjustments, but these are no longer an unresolved product decision.

When no verified model/provider limit exists, a selected Small, Standard, Large, or Custom target remains in effect. Do not replace it with an invented hidden fallback. Context rejection or truncation uses the approved bounded shrink-and-retry behavior.

## 8. Associative Memory Retrieval Budget

`Maximum Memories Per Response` and `Maximum Memory Context` apply only to retrieved Associative Memories.

They do not limit how many memories are stored on the device.

They are separate from:

- fixed app, system, and developer instructions;
- fixed companion identity and companion-profile instructions;
- Model Rules;
- Lorebooks;
- roleplay cards and other existing roleplay context systems.

Do not combine these systems into one shared budget as part of the memory-system implementation. Lorebooks and roleplay cards retain their existing independent priority and budget behavior. Applicable roleplay context takes priority over filling the separate Associative Memory allowance.

## 9. General and Companion Competition

`Memory Priority` is the only approved General-versus-Companion competition mechanism:

- `Balanced`
- `General Memories First`
- `Companion Memories First`

Semantic relevance remains primary. Priority is used only when relevant General and Companion memories compete for limited Associative Memory space.

Do not add:

- a separate protected companion capacity;
- a reserved companion quota;
- a General/Companion percentage slider;
- separate per-pool count limits;
- a second balance mechanism.

Unused Associative Memory space may be filled by any other enabled and relevant pool.

## 10. Model-Aware Limits

`Use Model-Aware Limits` is optional and may be turned Off.

When On:

- the app may reduce selected Associative Memory limits only when a verified reported limit or a valid manual override is available;
- it must show the source and any applied reduction.

When Off:

- the selected user limits are used;
- the app does not proactively reduce them based on metadata.

When context information is unavailable:

> **Model Context:** Unknown

The app uses the user’s selected limits. It does not invent a conservative live-retrieval fallback.

## 11. Scope Control

Do not expand the memory project with additional retrieval controls or tuning systems beyond the approved documents.

Specifically, do not add protected-capacity controls, raw threshold controls, subtype budgets, dynamic per-model presets, automatic tuning, or a generalized context allocator unless the owner explicitly approves them later.

The immediate implementation sequence remains Phase 1 after the completed Phase 0 audit. Later-phase retrieval details must follow `memory_retrieval_and_analysis_ui_copy.md` and these clarifications without reopening product design.