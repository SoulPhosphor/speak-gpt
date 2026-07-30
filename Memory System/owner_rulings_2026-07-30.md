# Memory UX owner rulings — July 30, 2026

These are current owner decisions. They override any conflicting wording, behavior, or approval claim in `external_memory_analysis_counterplan.md`, older memory plans, source comments, pull-request descriptions, or existing speculative strings.

Nothing in an older document becomes approved merely because it was written down or already implemented. Existing user-facing text must be inventoried and shown to the owner before it is treated as approved wording.

## 1. Re-enabling Archive this chat

There is **no confirmation message, dialog, snackbar, toast, or choice** when the user turns **Archive this chat** back on.

Turning it on means: include every eligible message that has not already been fully processed.

The archive cursor/bookmark must survive while archiving is off. Turning archiving off pauses the feature; it must not erase, reset, advance, or replace the last truthful processing position. When archiving is turned back on, processing resumes from that preserved position and includes the eligible backlog through the present.

Delete the proposed **Include Earlier Messages?**, **Include Earlier Messages**, **New Messages Only**, and **Earlier Messages Unavailable** user flow from implementation planning. It is not wanted.

## 2. Missing embedding model reminder in Memory Browser

When Memory Browser opens and no embedding model is installed, show a dismissible reminder for that visit.

**Text:**

> Saved memories can't be used in chats until an embedding model is installed.

**Action:** **Okay**

Pressing **Okay** dismisses the reminder for the current visit only. It appears again the next time Memory Browser is opened while the model is still missing. It disappears permanently once an embedding model is installed.

This is not a permanent inline banner and must not occupy the screen after the user acknowledges it. Do not describe Memory Browser or memory analysis as unavailable: saved memories remain viewable and editable, and analysis may still create Pending suggestions. The missing model blocks using saved memories inside chats.

## 3. Existing Memory Assistant progress and result wording

The current app already contains progress, completion, interruption, partial-failure, full-failure, nothing-found, and no-new-memory wording. Its presence in code does **not** prove the owner approved each string.

Before adding or replacing any Memory Assistant status copy:

1. Inventory the exact existing text.
2. Record the screen/state that invokes each string.
3. Reuse the existing status surface rather than creating a parallel vocabulary.
4. Stop for owner review only where a genuinely new user-visible state needs words.

Do not invent additional phrases such as alternate processing counters merely because a new internal mechanism needs progress data.

## 4. Lorebook-only analysis and suggestion review

The currently documented lorebook-only analysis and lorebook-suggestion review flow is **unapproved**. Do not build it, present its copy as pending approval, or imply that the owner requested its screen design.

The current proposal is retained only as a question for later discussion: analysis would create proposed lore book entries with trigger keywords; those proposals would be reviewed in the lore book area, assigned to a destination lore book, and added only after individual approval.

No UI structure, labels, destination selector, create-new-book path, approval card, result summary, or wording for this proposal is approved by this ruling.

## 5. Implementation discipline

Internal reliability, database, indexing, and recovery work may proceed without new wording when it does not create a new visible state.

When a visible state already has a surface, extend that surface minimally. Do not replace established owner wording, duplicate it elsewhere, or convert implementation terminology into user-facing copy without explicit approval.
