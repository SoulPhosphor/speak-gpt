# RAG alteration notes — November 14

> **Historical record (2026-11-16).** Everything in this note is carried
> into the single authoritative memory development plan,
> `conversation_recall_plan.md` (Part M; M.8 maps each section to its new
> place). Status statements below describe November 15 and are outdated:
> the Step 3 repair and the carry-over evaluation are merged (#205, #206),
> and the owner's decisions are recorded as G37–G41 there. Do not use this
> note as a work order.

**Captured:** November 15, 2026, from the owner's November 14–15 design discussion. The requested filename uses November 14.  
**Repository:** `SoulPhosphor/speak-gpt`, active integration branch `main`.  
**Purpose:** Preserve new owner direction before the existing evaluation and later RAG plan revisions. **This is a decision/intent record, not a replacement work order, a production change, or approval for unspecified UI details.**

## 1. Direction established by the owner

### Context assistance must be selectable independently of the embedding model

Design one replaceable context-assistance layer with these intended choices:

| Context assistance | Status / intent |
|---|---|
| **Off** | Available. Search the new message without an additional conversation-context helper. This does **not** mean turning saved-memory search off. |
| **Code-based** | Offer only if the already prepared held-out evaluation demonstrates a meaningful benefit without an unacceptable increase in irrelevant memories. Do not include just because it is free or already written. |
| **API AI** | **Include in the initial context-assistance rollout**, rather than postponing all AI assistance until after conversation recall is built. Give it a **separate user-selectable model choice** from configured API providers. Do not force it to use the regular conversation model or the Summarizer model. |
| **Local AI** | Design for it from the start, implement later. An on-device small model can eventually serve the same role as an API model, without paid calls or network dependence. |

An API helper is **optional**, not mandatory for memory or recall to function. Avoid making extra paid calls the only route to continuity. The user's preference is to ship a useful interchangeable architecture instead of permanently committing to one strategy.

**Role of the helper:** Resolve references in short follow-ups and changes of subject, and prepare a self-contained retrieval query using available conversation context (e.g., who “she” refers to). It is **not** the answer-generating chat model and must not change the user's actual message, invent personal facts, or manufacture remembered details. The search, relevance filtering, memory selection, scopes, and prompt assembly remain downstream responsibilities. An API or local helper should have a shared, validated output contract; exact schema, prompts, timing and failure UI are not yet approved.

**Fallback principle:** If an optional helper is unavailable, ordinary retrieval should remain usable. Precise fallback behavior, notices, privacy disclosure, and timeout limits must be designed and approved before shipping; do not invent silent behavior or a mandatory paid dependency.

### Embedding models are a *different* model selection

EmbeddingGemma 300M q4 remains the present on-device baseline/default. The owner eventually wants **choice of local embedding models and API embedding models**, separately from the context-assistance choice. Do **not** conflate an API language model used to resolve a question with an API embedding model used to compute vectors.

A stronger embedding model was previously considered but not selected because it would not run acceptably on the current phone; the owner wants the ability to upgrade when she gets a more capable phone or better models become available. The exact model is **not identified in this note** and must not be guessed.

- Keep stored original memory/chat text model-independent so vectors can be rebuilt.
- Index/vector identity must include the actual embedding model and materially relevant configuration/provenance (not just a hard-coded EmbeddingGemma assumption). Different models' vectors are not generally comparable; changing model can require re-embedding or recutting text.
- Model-specific tokenizer, prefix, dimensionality, limits, and normalization belong behind an appropriate interface, not scattered through retrieval logic.
- Today's Pixel 8 is a useful performance benchmark, **not a permanent device limit**. Report retrieval quality separately from speed, RAM, battery use and API cost. A future model may be acceptable on newer hardware even if too slow on today's phone.
- No need to implement API embeddings or benchmark a new model merely to unblock current Step 3/4 work.

## 1A. Memory Browser provenance fields — omitted from the first note

**Correction, November 15:** The original note missed a separate discussion about fields the Memory Browser and Memory Assistant need. This is important because the app will eventually generate and combine memories from older conversation messages. Preserve these requirements when revising the saved-memory schema and Memory Assistant plan; this is **not** authorization to run a database migration without inspecting the current implementation and agreeing on behavior.

### Preserve source information separately from memory content

| Field / capability | Owner's intent and boundaries |
|---|---|
| **Original statement date/time** | Record when the source statement was actually made, **separate from** the saved memory's creation and last-edited timestamps. For historical data, leave unknown dates unknown. |
| **Source conversation ID** | Retain a stable ID for the conversation that supplied the statement; a changeable conversation title is not an identity. |
| **Source message ID** | When available, retain the permanent ID of the exact source message rather than linking only to the conversation as a whole. |
| **Source link in Memory Browser** | Place a clickable link **at the bottom of the memory** to open the source conversation, ideally scrolled to the original message. Source identifiers should be used for navigation, not shown as distracting technical IDs in the normal UI. |
| **Original speaker / attribution** | **Consider storing who said the statement**, distinguishing the user from an AI or another speaker. **The owner explicitly clarified that this must not be a mandatory field** for every memory. Unknown provenance remains unknown; do not misattribute assistant suggestions to the user. |
| **Event date/time** | Consider a separate **optional** field for when the event described occurred, where supported by the source. It is not interchangeable with the statement date or creation date; do not fabricate it. |
| **Multiple source messages** | Plan for a single consolidated memory to retain references to more than one supporting source, instead of replacing original provenance when a memory is updated or deduplicated. Exact display/lineage behavior is still to be designed. |

**Do not conflate stored provenance with embedding input.** Storing source IDs, dates and optional attribution in structured metadata does **not** mean all of it should be sent to the embedding model. The owner wants **whether speaker attribution helps retrieval to remain a considered/testable choice**, not an automatic ban or a requirement. Technical UUIDs do not carry useful natural-language meaning; they belong in metadata, not searchable memory prose. A small measured test can decide whether natural-language speaker context belongs in the embedding's document text.

### Database and migration implications

This is more than adding labels to the Memory Browser. Expect a schema change: dates and attribution can be nullable fields, while **multiple source references may warrant a separate memory-to-source relationship table**. Inspect the current schema, existing permanent message identifiers, source ownership/scope, import/export and backup formats before choosing the migration. Existing memories must remain intact; missing historical provenance should remain unset rather than invented. Preserve provenance through future edits, consolidation, backup/restore and embedding-model changes.

The exact data schema, whether the event-date field and speaker metadata are exposed in regular UI or advanced details, and handling deleted/hidden/inaccessible source messages remain implementation decisions for review. **The main decisions are to preserve traceable origins, enable source navigation, and keep speaker information optional and its use for embeddings undecided.**

**Priority:** Plan and implement provenance alongside the relevant saved-memory and Memory Assistant work, before large-scale creation of memories without source lineage. This should **not** block finishing Fable's already-running frozen retrieval evaluation or require re-running it now.

## 2. Relevance and +3 importance: question to resolve with measurements

The current implementation's **+3** status makes an *otherwise eligible* memory mandatory even beyond the Maximum Memories count. It **does not** bypass the relevance gate. A +3 memory can be first by raw cosine similarity and still fail the gate's additional stand-out requirement.

The development test illustrates this:
- User: “The doctor wants to prescribe me an antibiotic for this chest infection.”
- Memory rated +3: “Orla is allergic to penicillin and carries a card in her wallet saying so.”
- This is **Orla's own allergy**, not an allergy belonging to her child. The test expects that memory.
- It was top-ranked by raw cosine similarity and cleared the ordinary absolute similarity floor, but narrowly missed the stand-out threshold and was excluded.
- Cosine similarity `0.363` is **not** “36.3% relevant”; thresholds and ranks are not probabilities.

**Owner's intent:** A relevant, unusually high-ranked +3 memory should not be excluded just because an arbitrary secondary cutoff is slightly missed. But a +3 memory that genuinely fails relevance **should still be rejected**. First place alone never guarantees relevance, since unrelated messages also have a first-place result.

Fable already froze two evaluation-only alternatives in `app/src/test/resources/recall_carryover_holdout/final_methods.json`: lower the stand-out bar for eligible +3 memories, or do so only when the +3 memory is also the single highest-similarity match. **Evaluate existing variants for additional correct and incorrect inclusions; do not adopt a new threshold or blanket +3 bypass by assumption.**

## 3. Finish the existing evaluation, then move forward

**Do not expand this into days of research or more invented algorithm variants.** The owner has already handed the next model the finite Fable evaluation assignment.

At the time of this note:
- Step 3 original implementation is merged in PR #203. Its repair is on `repair/step3-previous-message-search`; `evaluation/carry-over` includes that repair plus the evaluation. Neither has been merged into `main`.
- Existing Android Checks run `43706356535` succeeded and has a `recovery-instrumentation-results` artifact. The evaluation branch still needs the two Android similarity JSON resources and the two recorded `final_results.json` files, followed by a rerun of the frozen test and a concise entry in `memory baseline.md`.
- Freeze the expected answers, case texts, variants and thresholds. Compare the **memories actually selected for sending**, not just candidate retrieval or raw rank.
- The verdict must weigh correct/missed/acceptable/incorrect selections, old-subject carry-over, +3 safety, and ideally clean turns. Distinguish a code helper's benefit from the base embedding and gate quality.
- Development data showed that blindly searching previous messages can find more correct memories but also carry many wrong memories; a code-based subject tracker looked promising in preliminary results, **not yet validated on held-out examples**.
- The AI rewriting method has **not** undergone an equivalent retrieval benchmark. Do not claim it outperforms the code version merely because a small language model handles example follow-ups well.

After the held-out results: decide whether a code helper earns its setting. Then update the implementation plan **once**, including the context-helper interface, initial API model selector, and future local/API embedding compatibility. Do not silently replace approved behavior or modify frozen evidence.

## 4. Build-order and scope guidance

The main document is `conversation_recall_plan.md` (see G.9/G.9a, G.15, G.16–G.19). Its 17 numbered steps (0–16) are **mostly implementation and integration**, not 17 research phases. Tests accompany each step; Step 0 and Step 6 create benchmarks, and Step 15 includes measuring/inspecting retrieval.

The prior plan places an optional live AI worker after Step 16 and assumes previous-user-message searching as the primary carry-over mechanism. **Those portions need reconsideration in light of the owner's new initial-API-helper decision and held-out results.** Do not rewrite work orders blindly before the result or treat the old execution order as permission to ignore this new decision. Keep safety gates and tests, but avoid unnecessary repeated research or one-PR-per-trivial-change bureaucracy where a coherent task can be verified together.

**Next practical path:** complete Fable's existing held-out evaluation; make the code-helper/+3 decisions; integrate the Step 3 repair via its reviewed code-change PR with the owner's approval; update the planned Step 4 architecture and sequencing for initial API context assistance; proceed with implementation. Do not begin Step 4 before its prerequisites/owner decisions are resolved.

## 5. Still open; do not invent approval

- The winning code-based algorithm, if any, and whether to expose it.
- Whether either +3 exception improves held-out quality sufficiently.
- Exact names, placements, defaults and enabled/disabled states of UI settings.
- Which configured API model/provider capabilities qualify for context rewriting, input/output schema, query prompt, cost display, permissions/privacy disclosure, timeout, fallback, and how assistant replies may contribute to reference resolution.
- Which future local language model and API/local embedding models to ship.
- Specific changes to the numbered work orders, decided after evaluation.

## 6. Repository handling

`main` is the active Beta client. Do **not** work from the obsolete `beta/new-client` branch or accidentally produce the old pre-release build. This note is **documentation only**; it does not alter code, application behavior, benchmarks or original RAG plan. Codex reviews are reserved for PRs with actual code changes. Existing repair/evaluation work must be compared with current `origin/main` rather than assuming a stale branch is already current.

**Related evidence:** `app/src/test/resources/recall_carryover_set/`, `app/src/test/resources/recall_carryover_holdout/`, `memory baseline.md`, and Android Actions run `43706356535`.
