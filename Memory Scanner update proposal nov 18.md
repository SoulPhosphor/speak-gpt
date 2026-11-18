# Memory Scanner update proposal nov 18

**Design proposal for review · November 18, 2026 · Associative Memory Scanner and Memory Browser**

Let the scanner revise its own temporary drafts as it reads a conversation. Use the on-device embedding model to find related memories and show the user why two records were brought together. Keep a straightforward short scan to one generative request whenever possible.

## What happens today

On `main` at `a3c0751`, the scanner reads a frozen range of eligible transcript rows. A normal chat scan starts after its bookmark. Before each extraction request it can already search **active memories** using embeddings, with keyword fallback. Each chunk sees a bounded selection of earlier proposals (up to ten within a text budget). The model can write a correction, but the collector combines exact repeats and otherwise keeps proposals in arrival order. The first eligible proposals can fill the user's limit before a later correction is filed.

Embedding support also exists in the Browser's Possible Match review. The current finder scores comparable active memories and can compare archived or superseded records on demand. It returns match identities and categories to the UI. The exact-duplicate check covers pending drafts too. Differently worded **pending** drafts and **temporary drafts from this scan** are the gap in semantic comparison. A user who scans several chats before reviewing can accumulate the same suggestion several times.

## Read in order, with two paths

| Situation | Proposed behavior |
|---|---|
| The eligible text fits in one request | Send it once. Let the scanner see the outcome before writing its proposals. Allow room for instructions, retrieved memory context, and the response. |
| Multiple requests are needed | Send sections chronologically. Clearly mark the new section. Carry earlier transcript text forward when it fits and helps interpretation, along with relevant working drafts. Each request can add a draft, correct one, or withdraw one. |
| Earlier text and drafts outgrow the request | Keep all eligible text in sequence across the scan. For each request, select relevant earlier drafts locally and bring back short excerpts from **this scan** where needed. Keep a little preceding dialogue for replies such as “she finally did.” An older draft can return after a long change of subject. |

Use the estimated size of the transcript being scanned and the **selected scanner model’s** request settings. A chat’s cumulative usage can count the same input many times. Honor the existing average and maximum settings, scene boundaries, output allowance, and context-limit retry. Where the endpoint has a context window recorded for this model, use it. Otherwise start from the configured chunk sizes. For longer scans, retaining more history may be worth the input cost. Keep reusable prefixes stable where the provider supports caching, and measure actual cache hits and charges before choosing a default amount of overlap. Cached input still occupies context space.

## How the AI edits drafts

Kotlin owns the temporary collection and issues short IDs. The model returns new proposals plus just the edits it needs. For example, when a later message confirms a reply, it can replace draft 4 with wording that records the reply and preserves an important delay. A replacement may name two drafts when the evidence supports combining them. An omitted draft keeps its text. Explicit withdrawal covers a mistaken draft with nothing worth retaining. The app checks that each referenced ID belongs to this scan and that placement and scene are compatible before applying the response as a unit.

Keep small, scan-local references to the supporting passages so an ambiguous correction can be checked against the speaker’s actual words. A later message can also describe an earlier event, speculate, or report a second reply. Message order alone cannot establish which fact supersedes which. Keep the editable Associative Memory prompt and per-chat Analysis Note in charge of what is worth retaining and how it reads. Suggested default guidance: *Keep the decisions, reasons, details, and outcomes that will matter later. When the situation changed, preserve meaningful history and make the latest supported status clear.*

Only temporary drafts participate in these automatic edits. Updates involving already-filed active or pending memories go to user review. Keep model-rule proposals on their own filing path and Lorebook extraction on its own contract.

## Where embeddings fit after the change

1. **Before each later chunk**, use its text to find relevant temporary drafts as well as relevant existing memories. This makes a draft from the first chunk available again in the eighth.
2. **After extraction**, use each proposed memory’s own text to look up comparable active memories, pending memories from any chat, and drafts from the current scan. Search memory records locally. Other chats’ transcripts are unnecessary. Reuse compatible active vectors. Compute missing pending or temporary vectors on demand and reuse them while valid. Refresh them when the represented text or embedding model changes. Keep this scanner comparison separate from what is eligible for injection into a normal chat.
3. **Interpret the result according to what matched.** Keep the existing exact-duplicate and rejection checks. A close semantic match between two temporary drafts can go back to the scanner for resolution. A match to a filed record links the new proposal to that record for review. Similarity alone never settles whether two statements repeat, contradict, or extend each other. Preserve scope, Type, and target distinctions.
4. **Without an embedding model**, continue with exact checks, bounded keyword lookup, and validated scanner relationship hints. Label how a match was found. A keyword match has no embedding similarity score. The fallback should also work when only some vectors are ready.

## Memory Browser changes

- On a pending card, show a preview of the closest related memory, its status (for example, Active or Pending), and its similarity score when available. Tapping the match should open the **specific pair** by memory ID. Make additional matches accessible from the same card.
- In Possible Match review, show the full proposed and matched wording together, clearly labeled. Present the score as similarity, such as **0.91 similarity**. It measures closeness in wording or meaning and can be high for opposite claims. Distinguish exact matches, keyword matches, and scanner-suggested relationships. Carry actual scores through the match result so the card can display them.
- Reuse the existing review actions where they fit. For two pending drafts, let the user keep both or keep/edit the better version and discard the redundant proposal without activating either by accident. Recheck IDs, status, and match data after an edit or deletion. Returning from comparison should preserve the user’s place in the review list.
- These changes cover the scanner-to-review workflow. A broader Browser redesign deserves its own proposal. Exact labels and layout remain design choices.

## When to spend another scanner request

Finish directly when there are no unresolved draft conflicts and the eligible set fits the user’s suggestion limit. For unresolved conflicts, send the competing drafts and brief supporting excerpts. If the cap is exceeded, rank distinct proposals across the full scanned range for usefulness and topic coverage before filing. Apply the cap to valid, eligible proposals, allowing the next one through when a duplicate or rejection drops out. Preserve the existing behavior for an unlimited setting and for an accepted memory that must split into parts. Budget the final request too: if necessary, resolve small groups first and rank compact references across groups. Never truncate the final list by arrival order.

Commit the resulting proposals and advance the scan bookmark together. An interrupted scan or invalid required edit/review should use the existing bounded repair and failure handling. An unfinished range stays retryable. A previously completed range stays completed.

## Quick evaluation for a future implementation

Compare the current scanner and the proposal using the same scanner model, settings, and synthetic conversations. Include a short chat, an approximately 80-message multi-topic chat with a late correction, the same correction buried in the middle, and three chats scanned before any drafts are approved. In the three-chat case, repeat the first event in different words and then introduce a genuinely different decision. Expect one proposal for the original event, no redundant suggestion for merely retelling it, and a linked update for the new decision. Repeat with embeddings unavailable and with a suggestion cap below the number of distinct candidates.

Judge the actual prose, chronology, topic coverage, missed or wrongly merged facts, and effort to review on a phone. Compare request count, input/output tokens, cached tokens and cost where reported, elapsed time, and local embedding work. The first review question is whether ongoing edits plus local matching catch stale and repeated memories economically enough to make a full second transcript read unnecessary.

**Implementation references on the reviewed revision**

- [Scanner and filing](app/src/main/java/org/teslasoft/assistant/preferences/memory/archivist/Archivist.kt), [request budgets](app/src/main/java/org/teslasoft/assistant/preferences/memory/archivist/ArchivistRequestBudget.kt), [runtime context](app/src/main/java/org/teslasoft/assistant/preferences/memory/archivist/ArchivistContext.kt), and [editable prompt](app/src/main/java/org/teslasoft/assistant/preferences/memory/archivist/ArchivistPrompt.kt)
- [Memory retrieval](app/src/main/java/org/teslasoft/assistant/preferences/memory/librarian/Librarian.kt), [exact matches](app/src/main/java/org/teslasoft/assistant/preferences/memory/MemoryMatch.kt), and [Possible Match finder](app/src/main/java/org/teslasoft/assistant/preferences/memory/PossibleMatchFinder.kt)
- [Memory Browser](app/src/main/java/org/teslasoft/assistant/ui/activities/memory/MemoryBrowserActivity.kt) and [Possible Match review](app/src/main/java/org/teslasoft/assistant/ui/activities/memory/MemoryPossibleMatchReviewActivity.kt)
