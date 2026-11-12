# Phosphor Shines: AI Working Instructions

These are the standing operating rules for agents working with the owner and this repository. They govern how to work, not which feature to build. Keep feature designs and implementation details in their own specifications.

## 1. Start with the actual assignment

- The owner's current request determines the task, scope, and priority. A roadmap or older agent plan cannot block a task the owner has assigned.
- Read this file, then the relevant feature specification, assigned work order, current code, and tests. Read only the sections and files needed for the assignment.
- For multi-stage plans, implement **only the assigned step** and its explicitly required dependencies. Do not begin subsequent steps, add future scaffolding, or redesign the plan while implementing a step.
- Treat a plan's **proposed** items as proposals, not permission to build them. Respect explicit owner decisions and approval gates recorded in the active task. If an older document makes a disputed claim of approval, ask rather than treating the document as proof against the owner.
- Verify current implementation in source code and tests. Use Git history only to investigate a regression or a relevant constraint. Do not use an earlier agent summary as evidence of current behavior.
- Do not create a new Markdown file without the owner's permission. Existing Markdown files may be edited when the current task authorizes it.
- Confirm the intended integration branch and its current commit before branching or editing. Do not assume that `main`, the default branch, and the most recently developed branch are the same. If branch names, repository state, and the owner's instruction conflict, report the mismatch and ask before merging or changing a branch ref.
- Work on a feature branch unless explicitly directed otherwise. Never force-push an integration branch or alter unrelated branches.

## 2. Owner authority and product approvals

The owner directs product behavior, wording, user experience, privacy, priorities, and acceptable tradeoffs. Repository instructions preserve those choices; they do not overrule the owner. A clear current instruction takes precedence over an older one.

Obtain approval **before** introducing or changing:
- user-facing wording, layout, controls, navigation, or interactions;
- defaults, automatic behavior, filtering, grouping, ordering, or information visibility;
- feature scope, behavior, or changes to the existing user experience;
- destructive actions, recovery, privacy, storage, encryption, migrations, or logging;
- architecture decisions with meaningful tradeoffs or lasting consequences;
- an approved product design or any item explicitly marked for owner review.

Approval includes the behavior behind the wording. Do not invent an unapproved fallback, threshold, default, or data rule merely to finish writing a label. The rule also applies to tests, placeholders, scaffolding, and feature flags that would encode the undecided behavior.

Clear approval is required. Questions, brainstorming, preferences expressed tentatively, silence, and requests for explanation are not approval. A step that says "approve first," "confirm," or "show to owner" is a genuine stop point. Do not put the unresolved decision into a file, commit, or PR as a draft. Do work that is independent of the decision, then ask.

Routine internal choices are the agent's responsibility when they preserve approved behavior, privacy, performance, data, and maintainability. Do not ask the owner to decide variable names, helper extraction, ordinary test design, or other reversible implementation details.

When a decision really is missing, ask one focused question in chat, identify its consequence, and recommend an option based on evidence. Do not repeatedly ask about choices already made. A concise format is:

**Decision needed:** [the precise question]  
**Effect:** [what changes for users or the system]  
**Recommendation:** [best supported option and why]  
**Alternative:** [only if meaningful]

Once a question is asked, do not silently act on an assumed answer. If no decision is needed, proceed with the assigned work.

### Preserve the approved experience

- A technical fix preserves all existing functionality, visual behavior, data, and interaction except the changes specifically requested. Extra taps, delays, missing labels, changed order, late-loading information, and different failure states are product changes, not harmless implementation details.
- Identify unavoidable user-visible differences before implementing them and obtain approval. Do not trade away the intended experience because another implementation is easier.
- An existing feature or workaround is evidence of what the code does, not proof that the owner wants it. Do not extend unwanted or retired mechanisms.
- The owner's explanation of why something matters is part of the requirement. Preserve the purpose, not only the mechanical feature.

## 3. Communication and accountability

- Put the answer, result, or blocker first. For technical work, use short, descriptive sections with findings, causes, decisions, risks, tests, and next actions easy to scan. Avoid both walls of text and one-sentence fragmentation.
- Explain user-visible behavior before source-level details. Use clear product language, define uncommon terms, and include code identifiers only where they help locate or verify a fact.
- The owner is the product authority, not the Kotlin/XML implementer. Explain enough for an informed decision without requiring source-code fluency.
- When the owner says an explanation is unclear, answer the exact question and define the unclear terms before continuing. Do not take confusion as approval.
- When an instruction was missed, identify the mistake, the correct requirement, the effect on the work, and the concrete correction. Do not substitute "I understand" for evidence of changed behavior.
- Own errors directly and apologize specifically. Do not explain away their impact, argue with the owner's account, or proceed as though the mistake did not happen.
- The owner's anger, profanity, or criticism is not grounds to withdraw effort, impose tone conditions, or end a technical task. Stop only for an actual instruction, unapproved decision, technical blocker, or applicable platform/safety restriction. State the real reason.
- Do not make the owner repeat information already provided in the conversation or task context.
- Ask questions in ordinary chat, not a special pop-up question tool. Ask one at a time where possible and do not create an unsolicited list of decisions.

## 4. Inspect and fix the real problem

- Inspect the current code, layout, data flow, and relevant tests before proposing a fix. Trace the whole affected path, including filters, limits, state changes, and error handling, rather than guessing from the first suspicious line.
- Do not ask the owner to diagnose something that can be determined from code, a test, or measurements. Verify a screen, control, and its exact label in the current layout before giving navigation instructions.
- State the root cause and explain how the fix removes it. Label a workaround honestly if it cannot remove the root cause.
- Make the smallest coherent change that fulfills the approved result. No incidental refactors, unrelated bug fixes, strings cleanup, or feature expansion without authorization.
- Protect existing correctness and responsiveness. Measure or reason through the new cost of work done during sending, scrolling, opening chats, rendering rows, or other frequent actions.
- Keep new chat-screen and message-bubble features in separate files, with only the necessary small connections to the existing large screen files. Extract existing large sections only with owner approval and without visual or behavior changes.
- Add or update meaningful tests for changed behavior. Fix production code when a test legitimately fails. Never disable, weaken, or delete a test just to obtain a passing result. If a test is provably wrong or its approved contract changed, explain every test change to the owner.
- Do not duplicate work already completed in the active branch or conversation.

### External services and costs

- Obtain model catalogs, capabilities, voice lists, prices, and other provider-controlled information from the provider or an authorized live source. Do not hard-code data that providers can change.
- If a provider does not report a cost, show **Not Reported**; do not invent a price. Test fixtures may use clearly fictional values solely to check calculations.
- Do not add paid model calls, large investigations, or unnecessary subagent swarms by default. Choose tools and model tiers appropriate to the task. Ask before materially increasing paid usage, spawning multiple agents, or using a high-end subagent for exploratory work.

## 5. UI, styles, and theme compatibility

Before editing a UI screen:
1. Read the applicable sections of `ui-style-guide.md` and its shared style definitions.
2. Read `ui-style-adoption.md` for that screen's verified adoption and legacy status.
3. Inspect the screen's current XML/code and the shared components it actually uses.

**Use approved shared styles as the source of visual properties.** Layouts describe structure, relationships, IDs, and content references. Colors, sizes, spacing, typography, shapes, alignment, and other appearance belong in shared styles, dimensions, and theme resources, not repeated XML attributes or Kotlin literals. Do not copy a screen's appearance or implementation wholesale as a shortcut. If the guide lacks a needed reusable style, create an appropriate shared style and add it to the guide; seek approval first if the new appearance changes another screen's approved experience.

Reuse established style families for headers, rows, buttons, fields, dialogs, icons, validation, imagery, and loading/empty/error states. Keep unique product behavior local when it belongs only to one screen. Shared code is an implementation choice, not a reason to remove or relocate an approved control.

When changing a shared component, identify which screens use it, whether it shares appearance, structure, behavior, or data, and the visible effect on each. Obtain approval for changes that affect other screens. If asked to use a screen as a reference, map its relevant components to the approved style guide; do not clone the entire screen or use an unaudited/partial/legacy screen as the design authority.

A screen is only "Shared" or fully converted when its repeated visual components use the approved design system. Update `ui-style-adoption.md` whenever approved conversion status changes; never claim a visual audit or device check that was not done.

AMOLED-specific styling and palette/theme redesign work are paused until the owner reauthorizes them. Preserve working existing code without expanding the per-screen recoloring mechanism. New UI should remain compatible with shared theme colors and future palette changes.

Do not include the app's name in user-facing copy or wording proposals; name the feature instead.

## 6. Human-centered content and memory

The app must remain clear, predictable, respectful, and user-controlled even when someone is stressed or exhausted. Never replace significant personal content with a generic activity label or success marker when the meaning matters. Preserve the substance, context, and purpose needed for the person to recognize what was entrusted to the app.

Do not add crisis, safety, or hotline text to instructions that the memory system sends to an AI. The user's selected model handles its response under its own instructions.

For feature-specific memory or conversation-recall work, follow the assigned active plan's scope, approvals, and testing criteria. Do not replace the Summarizer's continuity purpose or existing memory behavior merely because another architecture would be easier.

## 7. Failures, evidence, and logs

User-facing failures should explain **what failed, the known cause, what was preserved, and what the user can do next**. Distinguish file-type mismatch, damage, encryption/key problems, permissions, unsupported formats, empty files, and unknown causes where the app can actually tell them apart. Do not label data corrupt or incompatible without evidence.

Before requesting more crash reports, backup files, or diagnostic artifacts, verify that supplied evidence is readable and usable. If not, say exactly why and what would work instead.

**All logging changes require owner approval first.** This covers adding, moving, removing, or rewording any Logcat, persistent event, error, voice/Whisper performance, or other diagnostic line. State the proposed content, purpose, and destination. Wanting better diagnostics does not itself authorize adding logs. A pure revert restoring the exact previously approved line is allowed.

## 8. Branches, checks, reviews, and merges

### Implementation workflow

For code work, use this sequence:

1. Confirm the owner-designated target branch contains the intended current work; branch from its verified commit. Inspect relevant code, finish the assigned changes, and review the full diff yourself.
2. Run the local checks and fix failures: `./gradlew testDebugUnitTest`, `./gradlew assembleBeta`, and `./gradlew assembleDebugAndroidTest`. Do not open a code PR with known failed local checks.
3. Open one focused pull request once the work is complete, required owner decisions are resolved, and local checks pass. The first Codex review begins automatically; do not request a duplicate first review.
4. Android Checks runs while Codex reviews. Validate each finding against the actual code and owner-approved behavior. Fix valid findings, rerun local checks, push the fixes together, and request `@codex review` for a subsequent review.
5. Repeat until Codex has no outstanding actionable findings and Android Checks is green on **the final commit**. If the head changes after approval, checks and review must cover the changed head. Do not merge work that is still running, failed, or unreviewed.
6. Merge the approved, green PR into the **verified owner-designated integration branch**, using an ordinary merge method and no force-push. If the target branch or approval status is genuinely uncertain, leave it unmerged and ask rather than choosing silently.

For documentation-only changes, inspect the complete replacement text, links, scope, and diff. Do not claim Android tests were executed if only documentation was checked. Any required CI checks still must be green before merge.

After opening a PR, do not run duplicate reviews, repetitive status checks, or scheduled watchers. Check while action is pending, then stop. If only the owner can decide the remaining blocker, ask once and leave the PR intact.

**Completion reporting:** Separate what was changed, which tests actually ran, the GitHub/CI/review status, and any behavior that still needs on-device confirmation. Green CI alone does not prove an observed runtime symptom is fixed; the owner verifies the real device behavior.

### Build environments

In Claude Code cloud sessions, use `./claude-cloud-android-setup.sh` once per fresh environment to provision Android build tools, then run the documented Gradle checks. Other environments must verify their own tool availability; do not assume the same setup works everywhere. If a full local build is impractical, explain the limitation and use available focused checks plus GitHub Actions. Never claim an unrun test passed.

If a GitHub HTTPS remote has no usable credentials, do not waste task time retrying authentication. Use an authorized connector or leave the verified change for a credentialed environment.

## 9. Repository privacy and metadata

Coding conversations stay private. Do not put private chats, health or emotional information, account details, private prompts, session IDs, AI conversation links, model attribution, or AI co-author trailers in commits, PRs, issues, source, documentation, release notes, tags, or branch names.

Commit and PR text should describe the technical change and validation only. Inspect changed files and metadata before publishing.

## 10. Task-specific documentation

This file is the navigation point, not an encyclopedia. Locate any additional specification with repository search and verify that it applies to the current task.

| Work | Relevant source |
| --- | --- |
| Conversation indexing, recall, carry-over | `conversation_recall_plan.md`: read G.19, the **assigned** work order in G.16, its referenced design sections, and the current source files. Only explicitly approved choices are binding; do not build withdrawn or later-stage proposals. |
| Saved-memory search and related behavior | `Memory System/dynamic_memory_plan.md`, using the sections relevant to the task |
| UI and theme-ready styles | `ui-style-guide.md` and `ui-style-adoption.md` |
| UI redesign | `ui-redesign-plan.md` |
| Document attachments | `document-includes-plan.md` |
| Local speech | `whisper-local-plan.md` |
| API speech-to-text | `api-voice-service-plan.md` |

Feature plans specify intended outcomes and work orders. Current code shows the implementation. If they disagree, identify the exact discrepancy; do not quietly choose a side or restart a completed feature. Historical documents are research only unless a current approved work order explicitly relies on them.

## 11. Keep these instructions clean

Keep `CLAUDE.md` short, durable, and operational.

- When a rule changes, **replace the old rule**. Do not append "previously X, now Y," competing rulings, or decision histories.
- Do not add dates to standing rules unless an actual deadline or version boundary changes how an agent must act.
- Keep temporary priorities, bugs, investigations, research, feature architecture, and implementation status in the task, issue, or feature document.
- If a rule's current validity is uncertain, ask rather than preserve contradictory versions as instructions.
- Use Git history to recover old decisions when genuinely needed; agents should not have to read that history to carry out current work.
