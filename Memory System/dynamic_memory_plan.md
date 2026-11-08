# Memory Plan: Search Repair, Dates, Fading, and the Standing Briefing

**Written 2026-11-08 at the owner's request. Rewritten the same day as one
step-by-step plan, after a full review for ideas carried over from older
documents.**

## Where things stand (read this first)

**Last updated 2026-11-08.** The owner relies on the agent to lead this
work step by step. A new session reads this section first, then continues
from "Next steps". Do not re-diagnose what Part A already measured. An
independent review of the plan and of pull request #180 was folded in on
2026-11-08; its points are reflected below and in Part E.

### 1. Built (pull request #180 into `beta/new-client`)

1. **Search fixes** (Phase 1, steps 1.5–1.11):
   - meaning and keyword search together;
   - common words ignored;
   - search uses the user's message alone;
   - a fixed-minimum-plus-stand-out gate, where sending nothing is allowed;
   - relevance-first ranking with a capped importance nudge;
   - +3 orders like +2 and is guaranteed a place when it matches;
   - no memory cooldown.

   Code: `librarian/LiveRetrieval.kt`, `librarian/KeywordText.kt`, the live
   branch in `Librarian.searchInternal`, and the query and cooldown changes
   in `enforcer/Enforcer.kt`.
2. **The Memory Assistant's own search is unchanged.** Its reconciliation
   search (`Librarian.searchCore`, `searchForReconciliation`) still uses the
   older ranking. Only live chat search changed.
3. **No cooldown for roleplay card entries** (D26). Lorebooks never had one
   and were not touched.
4. **Approved memory assembly log wording** (D27). The card-cooldown log line
   is still in the code but no longer fires. Removing it needs owner
   approval.
5. **Memory Search Test screen** (step 1.4), opened from Advanced Memory
   Settings → Debug:
   - test a message as an ordinary chat or as a companion;
   - see which memories would be sent and why, with every number;
   - adjust the numbers, then Use in Chats or Reset to Defaults.

   Code: `ui/activities/MemorySearchTestActivity.kt`,
   `librarian/SearchTestReport.kt`, `librarian/SearchTuning.kt`, and
   `MemoryStore.setRetrievalPolicyJson`. Tuned values are stored in the
   `retrieval_policy` record under `live_search`, `top_k`, and
   `memory_char_budget`. The old Debug search box was removed.

   **What the screen does not do:**
   - roleplay and campaign chats cannot be tested;
   - lorebook entries, roleplay cards, and memories picked before sending
     are left out;
   - it shows the memory text only, not the whole request.

   The screen says so, and it is not described anywhere as a full chat
   simulator. Expanding it into one needs owner approval.
6. **Tests:** `LiveRetrievalTest`, `SearchTestReportTest`, and
   `SearchTuningTest`. No test that existed before this work was changed.
7. **Instructions:** the `CLAUDE.md` rulings of 2026-11-08 (old documents
   are not approvals, the owner decides the work, diagnose from the code
   first).

### 2. Corrections made in #180 after review (2026-11-08)

- **Stale test results:** a test result from an older request is discarded
  (Codex finding).
- **Tuned numbers:** they are bounded exactly as chats bound them, so the
  screen tests and saves what chats will use (Codex finding).
- **Keyword strength measures coverage** of the message's informative words,
  weighted by rarity, instead of giving full strength to one rare word. The
  default Keyword Match Needed drops from 0.6 to 0.5. Measured result: A.11.
- **Heading renamed:** "What the AI Would Receive" became "Memory Text the
  AI Would Receive".
- **This plan:** the Memory Assistant and test-screen descriptions were
  corrected to match the code.

### Starting numbers

Chosen from the measurements in Part A, on the default q4 model with
256-number vectors:

| Number | Value |
|---|---|
| Minimum Match | 0.35 |
| Stand-Out Strength | 2.5 |
| Keyword Match Needed | 0.5 |
| Importance Influence | 0.1 |
| Small Library Size | 8 |
| Small Library Minimum Match | 0.40 |
| Full-Strength Match | 0.55 |
| Agreement Bonus | 0.1 |
| Minimum Spread | 0.02 |
| Maximum Memories | 8 (unchanged) |
| Memory Space | 6,000 characters (unchanged) |

They are educated guesses, tunable on the test screen.

### 3. Next pull request (approved direction, not yet built)

- **Carry-over** (step 1.12), with the revised rule in B.5: no fixed carry
  length.
- **The lorebook repeat check on both send paths** (step 1.13). Picked
  memories are already protected on both paths.
- **A modest set of representative test conversations** (step 1.2) as
  automated tests.
- **A previous-exchange field on the test screen,** for checking carry-over.

### 4. Future proposals only (not authorized to build)

- **Rest of Phase 1, only if testing shows a need:**
  - vector size (step 1.14);
  - the tag test (step 1.15);
  - variety (step 1.16).
- **Phases 2–6:** today's date, the "always know" and "may bring up"
  choices with dates, fading, the standing briefing, and Memory Assistant
  date proposals. The design in Part D was simplified after the review.
- **Every open decision in Part E** needs the owner's answer, asked one at a
  time, before the phase that depends on it is built.

### Design corrections after the second review (2026-11-08)

#180 is merged. The owner and a second review found design mistakes in the
future phases. Part D now follows one rule:

- **Availability, relevance, and permission to mention unprompted are three
  separate decisions.**
- Never limit what the AI receives in order to stop it behaving
  annoyingly. Tell it how to behave instead.

The specific corrections:

- **The "once per chat per day" delivery limit is removed.** A due memory
  stays available every message, and only how often the AI raises it is
  guided (D.5).
- **Always Know has its own cap.** It does not count against the search
  memory limit or Memory Space (owner, D16).
- **Picked memories come first, always.** Nothing automatic can displace
  them.
- **Automatic fading is proposed for removal** (D34, owner decision).
- **Carry-over must not refresh itself** from the AI's own replies (B.5).
- **Inside jokes and "vibe"** have a first proposal (D.9).
- **Published research** is summarized in Part F.

### Next steps, in order

1. **Done:** #180 merged into `beta/new-client` after Codex passed it and
   Android Checks was green.
2. **Owner:**
   - install the new beta and chat normally;
   - if a chat gets wrong or missing memories, open Memory Search Test, type
     the same message, and see why;
   - adjust the numbers there if needed;
   - tell the agent what looked wrong.

   Nothing else is needed from the owner for this step.
3. **Agent:** build the "Next pull request" group above.
4. **Agent:** before any Phase 2–6 work, walk the owner through the open
   decisions in Part E, one at a time, starting with D31.

## How to use this document

- **Part A** explains what is wrong with memory search today, measured
  against the real model.
- **Part B** is the recommended design.
- **Part C** is the build plan, in phases and numbered steps. Each step says
  what changes, where in the code, what it must not break, how it is tested,
  and when it is done.
- **Part D** holds the detailed design for dates, fading, and the briefing.
  Phases 2–6 refer to it.
- **Part E** lists every decision, both decided and open.

## Status

- **The owner decides what is built and when.** Build only the phase or
  step the owner tells you to build. No roadmap file overrides that.
- **Owner decisions so far (2026-11-08):**
  - search is fixed first (D23);
  - +3 ranks like +2 and is guaranteed a place when it matches; it is never
    sent when it does not match (step 1.10);
  - the memory cooldown is wrong (step 1.11);
  - the lorebook stays strictly keyword-based and must not break (Phase 0);
  - a memory the user picks before sending is never included twice
    (Phase 0);
  - the search test screen is its own screen, with adjustable numbers,
    opened from Advanced Memory Settings, and it replaces the old Debug
    search box (step 1.4).
- **Owner decisions after the technical assessment (2026-11-08):**
  - the direction of the search repair is approved (D0);
  - the default embedding model stays as it is; the user already picks q4
    or int8 (D29, revised the same day);
  - tags are not made mandatory. Independently generated tags are tested
    first (D28, step 1.15). Tags stay what they were meant to be: a way for
    the user to find their own memories;
  - the vector size is whichever performs better in meaningful testing, not
    768 for its own sake (step 1.14);
  - conversation carry-over is wanted, but it must recognize topic changes,
    must not keep carrying irrelevant memories, and must not forget a topic
    that clearly continues (step 1.12);
  - scoring, thresholds, keyword handling, and other retrieval details are
    left to engineering judgment, designed and tested properly (B.5);
  - priorities: fix the confirmed failures, keep existing memory
    protections, prove other memory features still work, build in
    manageable testable stages, and add no complexity or scope without a
    concrete reason.
- **Everything else is a recommendation** waiting for the owner's decision
  (Part E).
- **Stress-tested (2026-11-08):** the design was tested against the real
  model before being recommended, and changed where the test showed a
  weakness (A.10).
- **Old documents are not approvals.** This plan was written from the
  current code and real measurements. Older planning documents were often
  written by AI agents and contain choices the owner never made. None of
  them counts as an owner decision here (`CLAUDE.md` section 2).
- **Review for carried-over ideas (2026-11-08):** every section was checked.
  Where the plan mentions an existing feature (Pending, Possible Match, the
  Memory Assistant, scope rules, Model Rules, cards), it describes what the
  current code does, verified in the code. It does not rely on any older
  document's description of it.
- **Names in this plan are planning names, not on-screen wording.** Every
  label, explanation, and message the user sees needs owner approval before
  it is built.
- **Deleted on 2026-11-08** so they cannot compete with this plan:
  `legacy/rag_engine_work_order.md` and
  `legacy/memory-system-v1.11/enforcer_librarian_spec.md`. Older always-on
  designs in `legacy/` ("owner portrait", "standing packet", "always load")
  are dead and are not inputs to this plan.

---

# Part A: What is wrong today

The owner reports that memory search hands the AI nearly every memory
instead of the relevant ones, and that the AI seems to get amnesia. The code
and a measurement confirm both.

## A.1 How it was measured

- The app's default embedding model (EmbeddingGemma 300M, q4) was run off
  the phone with the same prompt prefixes, the same 256-dimension
  truncation, and the same normalization as `OnnxEmbeddingModel.kt`.
- 20 sample personal memories, written the way the Memory Assistant writes
  them ("The user ..."), and 6 sample messages, each with its expected
  memories. For cause 4, sample importance ratings were added (one +3, two
  +2, two +1, the rest 0) and the app's own scoring formula was applied.
- This is a small sample. It shows the direction and size of each problem.
  Final numbers are tuned on the test set (step 1.2) and on the owner's real
  memories (step 1.4).

## A.2 Cause 1: the keyword backup matches every memory

- **Where:** `Librarian.rankLexical` and `lexicalTokens`, with
  `MIN_TERM_LENGTH = 3`.
- **What happens:** one shared word of three or more letters makes a memory
  eligible. "the", "and", "you", "was", "had" all count. Memories are
  written "The user ...", so any message that contains "the" matches every
  memory.
- **Measured:** 20 of 20 memories matched for 3 of the 6 messages on their
  own, and 20 of 20 matched for all 6 messages once recent turns were added.
- **When the backup runs:** whenever the embedding model is missing, and
  also whenever **any single** memory lacks a current vector, because
  `Librarian.searchCore` uses meaning search only when every memory has
  one. That happens after a memory is added or edited, until the background
  repair finishes, and after a model change.

## A.3 Cause 2: the meaning-search cut-off sits inside the noise

- **Where:** `Librarian.MIN_SIMILARITY = 0.30`. Its own code comment calls
  it a guess to be tuned on the device. It was never tuned.
- **Measured, message alone:**
  - unrelated memories scored 0.10 to 0.39 (middle value 0.25);
  - relevant memories scored 0.28 to 0.49 (middle value 0.39);
  - at 0.30, 6 memories were right and 22 were wrong;
  - "hey how are you", which should bring back nothing, passed 4 memories.

## A.4 Cause 3: recent turns glued onto the search raise every score

- **Where:** `Enforcer.kt` builds the search text as the user's message plus
  the last 4 turns (up to 200 characters each), from
  `ChatActivity.recentTurnsContext`, all turned into one vector.
- **Measured:** adding one short recent-turn passage about the user's week
  raised unrelated scores as high as 0.43. 9 to 14 of the 20 memories then
  passed 0.30 for every message, including "hey how are you" (9 of 20).

## A.5 Cause 4: importance, freshness, and scope bonuses outweigh relevance

- **Where:** `Librarian.rank` and `Librarian.rankLexical`. A memory's score
  is 0.6 × similarity + 0.3 × importance + 0.1 × freshness + scope, project,
  and tag bonuses (`Librarian.retrievalBoost`, `SCOPE_BOOSTS`,
  `PROJECT_SELECTED_BOOST`, the tag bonus). The weights come from
  `DefaultOperatingData.kt` and are stored in the `retrieval_policy` row.
- **The values:** importance +1 adds 0.15, +2 or +3 add 0.30, and −2
  subtracts 0.30. Freshness (based on when the memory was created or last
  edited) adds 0 to 0.10. Scope adds up to 0.12, a selected project 0.08,
  and matching tags up to 0.06.
- **The problem:** the weights assume similarity runs from 0 to 1. This
  model's real scores sit between about 0.10 and 0.50, and the gap between
  a strong match and noise is about 0.15. After × 0.6, that gap is worth
  about 0.09. A +2 or +3 rating adds 0.30, more than three times the gap.
  The code comment above `SCOPE_BOOSTS` claims the bonuses cannot let a weak
  match beat a strong one. That is false for this model.
- **Measured:** with the sample ratings, the +3 memory ranked first in 11 of
  12 searches, including "hey how are you", "my cat keeps scratching the
  couch", and "the app keeps crashing". The memory that actually matched
  best ranked first in only 1 of 12.
- **The +3 rule itself:** `ImportanceRanking.includeMandatory` and
  `RetrievalBackfill.select` add a +3 memory beyond the 8-memory limit only
  after it passes the relevance gate. Because the gate lets almost
  everything through, every +3 is sent with almost every message.

## A.6 Cause 5: the cooldown gives the AI amnesia

- **Where:** `Enforcer.kt`, `COOLDOWN_TURNS = 10`, stored in the
  `injection_cooldowns` table. Roleplay card entries have the same cooldown.
- **What happens:** after a memory is sent, it is blocked for the next 10
  messages in that chat.
- **Why it is wrong:** the memory text is not kept in the chat history.
  `ChatActivity` places the memory block just before the newest message and
  rebuilds it every message, so on the next message the AI no longer sees
  it. The cooldown assumes the conversation still carries the memory, and
  it doesn't. While the user keeps talking about the same thing, the AI
  loses the fact for 9 messages, unless its own earlier reply happened to
  repeat it.

## A.7 The combined effect

- The limit is 8 memories and 6,000 characters (`DefaultOperatingData.kt`).
  It is a maximum, but because most of the library passes the gate, all 8
  slots are filled every message, mostly with unrelated memories.
- Highly rated memories take the top places whether or not they are
  relevant, and +3 memories are added beyond the 8.
- Then a memory that actually matters disappears for 10 messages after it
  is sent once.

## A.8 What works and must be kept

- **The lorebook.** It is strictly keyword-based: trigger words written by
  the user (`LoreBookStore`, `LoreBookTriggerMatcher`), duplicate removal
  (`LoreDedup`), and its own size limit (`LoreBookBudget`). It does not use
  the embedding model to choose entries.
- **Picking a memory before sending.** The user can attach memories to a
  message (`MemoryAttachmentController`, `ManualMemoryPriority`). The
  memory system puts picked memories first, reserves their room before
  anything else, and removes them from the search results, so search never
  adds them a second time (`Enforcer.kt`: the search pool excludes the
  picked IDs).
- **Scope eligibility:** which memories a chat may use (companion, project,
  roleplay). This is decided before ranking, in the store query.

## A.9 A difference between the two ways a message is sent

`ChatActivity` sends messages through two paths:

- `regularGPTResponse` passes the matched lorebook entries to the memory
  system;
- `buildFrozenRegularRequest` passes none.

So the check that drops a memory when it repeats a lorebook entry
(`NearDuplicate`, in `Enforcer.kt`) only runs on one path. On the other,
the AI can receive the same fact twice, once from the lorebook and once from
memory. The lorebook side is correct in both paths. Only the memory side
differs.

## A.10 Stress test of the recommended design

The design in Part B was tested with the real model before being
recommended. Four more sample messages were added, including messages where
several memories are relevant at once ("How is my family doing?", "What do I
like to do for fun?"), for 10 messages and 17 expected memories in total.

**Meaning search alone is not enough.** Even with the best gate rule, it
missed 6 of the 17 relevant memories. In half the messages, at least one
unrelated memory scored higher than a relevant one.

**Tags and the stored vector size matter most.** "Clean" means every
relevant memory scored above every unrelated one for that message.

| Setup | Clean messages (of 9 with relevant memories) |
|---|---|
| Today: q4 model, 256-number vectors, no tags | 4 |
| q4, full 768-number vectors | 5 |
| q4, 256, with short topic tags on each memory | 7 |
| int8 model, 768, no tags | 6 |
| int8 model, 768, with topic tags | 8 |

- Topic tags are short words such as "family", "health", "money", "pets",
  "food", "hobbies". The memory's searchable text already includes its tags
  (`RetrievalDocument.semanticDocument`), so tags help meaning search and
  keyword search at the same time.
- The Memory Assistant's instructions make tags optional
  (`ArchivistPrompt.kt`), so many memories may have none.
- The app stores only the first 256 of the model's 768 numbers
  (`EmbeddingModels`, `dimensions = 256`). Keeping all 768 costs about 3 KB
  per memory instead of 1 KB.
- Removing the repeated "The user ..." wording from memories did not help.

**A small on-phone re-ranker did not work.** A 23 MB English re-ranker
(MiniLM, trained on web search) separated cleanly in only 4 of 9 messages,
and no cut-off worked. It does not understand short personal statements.
Re-rankers that might are several hundred megabytes, too large to add on
top of the embedding model.

**Combined result.** int8 model, 768 numbers, topic tags, meaning search
with a relative gate, plus keyword search with BM25:

| Search | Messages | Right | Wrong | Missed |
|---|---|---|---|---|
| Today's search (fixed 0.30 cut-off) | first 6 | 6 | 22 | 2 |
| Recommended design | all 10 | 13 | 2 | 4 |

The 4 misses were judgment calls, for example the job memory for "How
should I prep for the interview?". The remaining tuning is done on the
owner's real memories with the test screen (step 1.4).

**Limits of this test (technical assessment, 2026-11-08).** These results
show direction, not measured improvement:

- **The two rows are not a fair comparison.** They used different message
  sets and changed five things at once: model, vector size, tags, keyword
  search, and gate rule. The gate rules were also picked on the same data
  they were scored on.
- **Only the relevance gate was compared,** not what the full app actually
  sends after importance, limits, and +3 handling.
- **The samples are small and favorable.** One message is about 10% of the
  result. The memories and messages were written by the same person, with
  shared wording, and no roleplay, companion, or long multi-fact memories
  were included.
- **The tag gain is an upper bound.** The tags were written knowing the test
  messages ("family" for "How is my family doing?"), which builds the
  answer into the memory.
- **The q4 and int8, and 256 and 768, differences** were about one message
  each, which is within chance.

Step 1.2 replaces this with a fair test.

## A.11 Keyword scoring correction (review, 2026-11-08)

The first version gave full keyword strength to any memory sharing one rare
word with the message. In testing, an accidental word in a longer message
brought in an unrelated memory: "spring" brought in the moving memory,
"balcony" the garden memory, "milk" the coffee memory. Keyword strength now
measures how much of the message's informative wording a memory covers,
weighted by rarity. A word no memory contains counts against coverage as
much as the rarest library word.

Whole search (meaning and keyword together), on 23 test messages:

| Keyword scoring | Right | Wrong | Missed |
|---|---|---|---|
| One rare word gives full strength, minimum 0.6 | 21 | 6 | 5 |
| Coverage of the message, minimum 0.5 | 18 | 2 | 8 |

- The trade is 4 fewer wrong memories for 3 more missed ones. The new misses
  are in long messages that cover several subjects at once.
- Requiring support from meaning search did not separate accidental matches
  from real ones, because the accidental word raises the meaning score too.
- Coverage was chosen because the owner ranks wrong memories, especially
  painful ones, as worse than missed ones. Carry-over and picking a memory
  cover misses, and the threshold is adjustable on the test screen.

---

# Part B: Recommended design

This is the design recommended from a fresh look at the code and the
measurements. It is not a repair of the old design. Where it keeps something
the app already has, that part holds up on its own.

## B.1 For each message

1. **Decide which memories this chat may use** (companion, project,
   roleplay). This is the only job scope does. Scope no longer changes
   ranking.
2. **Put the user's picked memories in first.** They are always sent, take
   their room first, and are excluded from everything below.
3. **Search two ways at once**, using the user's latest message:
   - meaning search with the on-phone embedding model;
   - keyword search that weights rare words (the IDF part of BM25),
     computed in the app (B.5). The memory's content, its tags, and the
     names of its linked companions, worlds, characters, campaigns, and
     projects are the searchable text.

   The model and vector size are chosen by testing (step 1.14).
4. **Combine both searches into one relevance score per memory** (B.5), so
   a memory found both ways ranks highest.
5. **Keep only memories that pass a fixed minimum and also stand out**
   from the rest for this message, or that cover enough of the message's
   keywords (B.5). When nothing qualifies, no memories are sent.
6. **Order by relevance.** Importance is the only bonus. It is capped so it
   can never beat a clearly more relevant memory. A relevant +3 is always included, even past the
   limit.
7. **Carry the conversation forward.** Memories sent with the previous
   message stay while the user's latest message or the AI's previous reply
   still relates to them, with no fixed carry length, and drop when neither
   does (B.5). This is what the cooldown should have done.
8. **Skip anything already in this request:** picked memories, briefing
   items (Phase 5), and memories that repeat a lorebook entry.
9. **Send up to the memory limit and size budget.** Empty slots stay empty.
10. **Send every relevant memory every time it is relevant.** No cooldown.

## B.2 What is removed and why

| Removed | Why |
|---|---|
| The fixed 0.30 cut-off | It sits inside the noise for this model (A.3) |
| Keyword search as an all-or-nothing backup | It matched every memory, and one missing embedding switched the whole message to it (A.2) |
| Recent turns glued onto the search | They raised every memory's score (A.4) |
| Scope, freshness, project, and tag bonuses | They outweighed relevance (A.5). Scope still decides which memories are allowed. Tags and names are found by keyword search instead. "Recently edited" does not mean "matters now"; fading (Phase 4) measures that properly |
| The 10-message memory cooldown | It hid relevant memories from the AI (A.6) |
| A separate Strict / Balanced / Broad setting | The test screen tunes the real number. A second control for the same thing adds confusion (D25) |
| An on-phone re-ranker | Tested; it did not understand personal memories (A.10) |

## B.3 What is kept

- the on-phone embedding model;
- scope rules for which memories each chat may use;
- the importance scale, with the owner's +3 rule;
- the memory limit and size budget, now tunable;
- the user's picked memories, first and never duplicated;
- the lorebook, completely unchanged.

## B.4 Not used: techniques that cost tokens

Rewriting the message with an AI before searching, writing a "pretend
answer" to search with (HyDE), and paid AI re-ranking all add a paid call
to every message. They are not part of this design.

## B.5 Engineering specification

The owner left these details to engineering judgment (2026-11-08). Each one
is tested in the step named, and the numbers are tunable on the test screen.

**Owner's priorities for every choice below:** Fix the confirmed failures first, keep every existing memory protection, prove other memory features still work, and add no complexity without a concrete reason.

**One relevance score (steps 1.6, 1.8, 1.9).**
- **Meaning strength:** how far a memory's similarity stands above this
  message's typical score, measured with the median and the median absolute
  deviation, not the mean and standard deviation. These are not pulled
  upward when several memories are relevant at once, which fixes the
  "family" problem from the assessment. The spread has a minimum, so a
  small or uniform library cannot make tiny differences look large.
- **Keyword strength,** from 0 to 1: the share of the message's informative
  words that the memory contains, weighted by how rare each word is in the
  library (the IDF part of BM25). A message word that no memory contains
  weighs as much as the rarest library word. So "tell me about Pepper"
  gives full strength, and one accidental shared word in a longer message
  does not (A.11). Unlike raw BM25 totals, it can be compared across
  messages.
- **Relevance:** the stronger of the two, plus a small bonus when both
  agree. This replaces rank-position merging (reciprocal rank fusion),
  because a rank-based merge cannot support the importance cap or the +3
  test.
- **Importance:** a bonus on the same scale, capped below a "clear lead".
  The +3 property test uses that same lead.

**The gate (step 1.8).** A memory passes if:
- its similarity clears a **fixed minimum** for the model **and** it stands
  out by the meaning-strength measure; or
- its keyword strength (coverage) clears a minimum.

The fixed minimum stops the relative rule from always letting the top one
or two through. Below a minimum library size, only the fixed minimum is
used.

**Keyword handling (step 1.5).**
- **Computed in the app** over the memories this chat may use, which
  search already loads every message (`activeMemoriesForScope`). For a
  personal library of hundreds or a few thousand memories this takes
  milliseconds. It needs no new database index, no migration, and nothing
  that can fall out of step.
- An FTS5 index is added only if a library ever grows large enough for this
  to be slow. The test screen's timing shows that.
- Simple word-form matching ("crashing" matches "crash", "cats" matches
  "cat").
- A built-in list of common words ("the", "how", "doing") that never count
  as informative. With a small library, a common word that appears in one
  memory would otherwise look rare.
- Searchable text is the memory's content, its tags, and the names of its
  linked companions, worlds, characters, campaigns, and projects, as the
  current keyword search already uses.

**Carry-over (step 1.12), revised after review.**
- **Candidates:** the memories sent with the previous message only.
- **The check:** each carried memory is compared, with a lower bar than new
  memories, against the user's latest message and the AI's previous reply.
  It stays while either one still relates to it.
- **Short replies:** "Exactly", "and then she said…", or "that's what
  bothered me" keep a memory as long as the AI's reply is still on that
  subject.
- **No fixed carry length.** A memory is never dropped just because it has
  been carried for a number of messages. Dropping it then would be
  artificial forgetting during a long conversation.
- **Wrong memories:** a memory sent by mistake drops on the next message,
  because neither the AI's reply nor the user's message relates to it.
- **No self-refreshing** (second review): the AI's reply can keep a memory
  only when the user's latest message is a short continuation with no new
  subject of its own. If the user writes about something else, the memory
  drops even if the AI keeps mentioning it. The AI cannot keep a topic
  alive without the user taking part.
- **Topic change:** when the user moves to a new subject, the latest
  message and the AI's reply both stop relating to the carried memories,
  and they drop.
- **Picked memories** are carried like any other sent memory, under the same
  rules.
- **The same "still on topic" signal** later tells fading (D.4) that a
  subject is still being discussed.
- Carry-over replaces the separate recent-turns search planned earlier,
  which depended on a brittle "short message" rule.

**Wrong versus missed.** Automatic memory leans toward precision: a wrong
memory costs tokens and misleads the AI. Missed memories are covered by
carry-over, by the user's ability to pick a memory, and by tuning on the
test screen.

**Other features that share the embedding space (step 1.14).** If the model
or vector size changes, the lorebook-repeat check (`NearDuplicate`, 0.85)
and Possible Match (`PossibleMatchFinder`, 0.80) are re-checked against
labelled pairs, and changed only if they no longer give the same results.

**The Memory Assistant** keeps its own, unchanged search
(`searchCore`, `searchForReconciliation`). Live chat search does not share
it. Changing the Memory Assistant's search would be a separate, approved
change.

**Speed.** Search time, embedding time, and memory use are shown on the
test screen and measured on the owner's Pixel 8. A step that makes sending
noticeably slower is not finished.

---

# Part C: Build plan

## Phase 0: Rules for every step

These apply to every step in every phase.

1. **The lorebook does not change.** No step may change:
   - `LoreBookStore`, `LoreBookTriggerMatcher`, `LoreBookMatch`,
     `LoreDedup`, `LoreBookBudget`, `LoreBookInjectionLog`,
     `ChatLoreBookSelection`, or `LoreBookEncryption`;
   - how lorebook entries are matched, which entries are chosen, their
     text, their order, or where they are placed in the request, in either
     send path;
   - the lorebook screens.

   The lorebook stays strictly keyword-based and never uses the embedding
   model to choose entries. Step 1.1 adds tests that prove this, and they
   must pass unchanged after every step.

2. **Picked memories are never sent twice.** A memory the user picks before
   sending:
   - is always included in that message;
   - takes its room first, before search results;
   - is never added again by search or by the briefing in the same request.

   Step 1.1 adds tests for this, and they must pass after every step.

3. **No log changes without the owner's approval** (`CLAUDE.md` section 8).
   Some steps change what the memory assembly log can truthfully say. Those
   steps stop and ask before changing any log line, naming the exact line
   and its new text (D27).

4. **Nothing visible changes beyond the step.** Any extra visible difference
   is raised with the owner first (`CLAUDE.md` section 3).

5. **Tests are never rewritten just to pass.** Existing tests that check
   removed behavior (for example the cooldown, scope bonuses, or word-overlap
   matching) are changed only in the step that removes that behavior, and
   each one is listed in the report to the owner with the reason.

6. **New code goes in its own files**, with only small hookups in the chat
   screen (`CLAUDE.md` section 11).

7. **Local checks before every pull request:** `testDebugUnitTest`,
   `assembleBeta`, and `assembleDebugAndroidTest` (`CLAUDE.md` section 0).

## Phase 1: Search repair

**Ready to build once the owner approves the recommended design (D0).**
Steps run in this order.

### Step 1.1: Safety tests for the lorebook and picked memories

- **Change:** tests only.
  - Lorebook: for a fixed set of lorebook entries and messages, the chosen
    entries, their text, and their order are recorded exactly as the
    current code produces them.
  - Picked memories: a picked memory is sent; it is not repeated when search
    also finds it; it takes its room before search results.
- **Done when:** the tests pass on the current code, before any other step.
  They must keep passing unchanged through every later step.

### Step 1.2: A modest set of test conversations (next pull request)

- **Change (proportionate, per review):** a small automated set of
  representative messages and conversations, each with the memories that
  should come back. It covers:
  - irrelevant messages that should bring back nothing;
  - multi-subject messages;
  - short follow-ups ("exactly", "and then?");
  - topic changes;
  - a memory sent by mistake;
  - ordinary and companion scopes.
- **How:** the real model's vectors are generated once and stored as test
  files, so the pure search functions run in normal unit tests.
- **Not required:** the owner is not asked to do repeated manual testing.
  Device checks are kept for meaningful changes such as a different model.
- **Done when:** the set runs in the normal unit tests and passes.

### Step 1.3: Explanations from the ranking code

- **Change:** the ranking functions return, for each memory, every number
  that went into its result: meaning score, keyword score, relevance,
  importance bonus, final score, rank, and the reason it was or was not
  sent. `Enforcer` returns the same for the whole message.
- **Rule:** one code path. The chat and the test screen (step 1.4) use the
  same functions, so the screen always shows what the chat actually does.
- **Done when:** for every test-set message, the memories sent are exactly
  the same as before this step. This step changes nothing the user sees.

### Step 1.4: The search test screen

- **Built (2026-11-08).** The owner said to build it directly and asked that
  it be easy to use and understand, so its wording was written to that
  standard.
- **What it does:**
  - opens from Advanced Memory Settings → Debug, replacing the old Debug
    search box;
  - the owner types a message and tests it as an ordinary chat or as a
    companion;
  - for each memory considered, it shows the meaning match and stand-out,
    the keyword match and matched words, the importance nudge, the score,
    and whether it would be sent, and why not;
  - it shows which searches ran, how many memories lack an embedding, the
    memory text the AI would receive, and how long the search took;
  - every search number is adjustable, with Use in Chats to save and Reset
    to Defaults;
  - read-only: it never changes a memory and sends nothing to any AI.
- **Limitations (stated on the screen, not a full chat simulator):**
  - roleplay and campaign chats cannot be tested;
  - lorebook entries, roleplay cards, and picked memories are left out;
  - it shows memory text only, not the whole request.
- **Not built:**
  - a previous-exchange field, which comes with carry-over in the next pull
    request;
  - saved test cases from real use, which are future and only if wanted.

### Step 1.5: Keyword search with BM25

- **Change:**
  - Keyword search scores every memory this chat may use with the keyword
    strength in B.5, computed in the app. Common words count for nothing, and names and
    rare words count for a lot. A memory needs a minimum keyword strength,
    not just one shared word.
  - Word-form matching and the common-word list are as specified in B.5.
  - Replaces word-overlap counting in `Librarian.rankLexical`.
- **Tunable on the test screen:** the minimum keyword score.
- **Done when:** in the test set, common-word matches no longer bring in
  memories, and name and rare-word matches do.

### Step 1.6: Meaning and keyword search together

- **Change:** in `Librarian.searchCore`, run meaning search over every
  memory that has a current vector, and keyword search over all memories,
  every message. Combine them into the single relevance score in B.5. A
  memory found both ways ranks highest. A missing vector removes only that
  one memory from meaning search, never the whole message.
- **Tunable:** the balance between meaning and keyword results.
- **Done when:** in the test set, a missing vector affects only its own
  memory, and the memories missed in A.3 (for example the chat-app memory
  for an app crash) are found.

### Step 1.7: Search with the user's message on its own

- **Change:** in `Enforcer.kt`, search uses only the user's latest message.
  Recent turns are no longer glued on. Follow-up messages such as "what
  about that?" are handled by carry-over (step 1.12), which uses the
  previous exchange.
- **Done when:** in the test set, recent turns no longer pull in unrelated
  memories.

### Step 1.8: A relevance gate that fits each message

- **Change:** replace `MIN_SIMILARITY = 0.30` with the gate in B.5: a
  fixed minimum for the model plus standing out from this message's other
  scores, or a strong keyword match. Below a minimum library size, only the
  fixed minimum is used. Passing nothing is a valid result.
- **Measured on the sample** (meaning search alone, before steps 1.5–1.7):

  | Rule | Right | Wrong | Missed |
  |---|---|---|---|
  | Today: fixed 0.30 | 6 | 22 | 2 |
  | Fixed 0.38 | 4 | 1 | 4 |
  | At least 2 standard deviations above this message's average | 4 | 0 | 4 |

  The relative rule sent nothing for "hey how are you", which is correct.
  Its misses are the kind keyword search (step 1.5) recovers.
- **Tunable:** the fixed minimum, the stand-out strength, the keyword
  minimum, and the small-library size.
- **Done when:** in the test set, "nothing relevant" messages send nothing
  and wrong memories drop sharply.

### Step 1.9: Relevance decides, bonuses only nudge

- **Change:**
  - Remove the scope, freshness, selected-project, and tag bonuses from
    ranking (`retrievalBoost`, `SCOPE_BOOSTS`, `PROJECT_SELECTED_BOOST`,
    the tag bonus, and the recency weight). Scope stays only as the filter
    for which memories a chat may use.
  - Importance is the only bonus. It is measured on the same scale as
    relevance and capped below a clear relevance lead.
  - Correct the false comment above `SCOPE_BOOSTS`, or remove it with the
    code.
- **Test:** if memory A is more relevant than memory B by more than the
  cap, A ranks above B whatever their ratings. This applies to live chat
  search; the Memory Assistant's search is unchanged.
- **Tunable:** the importance bonus and its cap.
- **Done when:** in the test set, the +3 memory no longer ranks first for
  unrelated messages.

### Step 1.10: +3 as the owner defined it

- **Rule (owner, 2026-11-08, clarified the same day):** +3 ranks like +2,
  and is guaranteed a place when it matches.
  - A +3 memory must be relevant, like any other memory. It is never sent
    just for being +3.
  - In ordering it counts exactly the same as +2. It gets no extra boost.
  - If it is relevant and other memories would fill the memory limit ahead
    of it, it is added anyway.
- **Change:** the existing rule (`ImportanceRanking.includeMandatory`,
  `RetrievalBackfill.select`, and `normalizedRankingImportance`, which gives
  +3 the +2 contribution) already works this way once the gate works. Keep
  it, and add the tests below.
- **Tunable:** the importance influence, which applies to +2 and +3 alike.
- **Tests:** a +3 memory unrelated to the message is not sent ("hey how are
  you"). A relevant +3 memory is sent even when 8 more relevant memories
  fill the limit. A +3 memory and a +2 memory get the same ordering nudge.

### Step 1.11: Remove the memory cooldown

- **Rule (owner, 2026-11-08):** the cooldown is wrong; it causes amnesia.
- **Change:** in `Enforcer.kt`, stop applying `COOLDOWN_TURNS` to
  memories. A relevant memory is sent every message it is relevant. The
  only skips are items already in this request: picked memories, briefing
  items, and lorebook repeats. Existing rows in `injection_cooldowns` are
  left in place (no data is deleted). Roleplay card entries lose their
  cooldown too (owner decision D26). Lorebooks have no cooldown and are not
  changed.
- **Log stop point:** the memory assembly log has cooldown lines for
  memories. Changing or removing them needs owner approval first (D27).
- **Done when:** in a chat that keeps talking about one topic, the relevant
  memory is sent on every message.

### Step 1.12: Carry the conversation forward (next pull request)

- **Change:** carry-over as revised in B.5:
  - only the previous message's memories are candidates;
  - each is re-checked, with a lower bar, against the user's latest message
    and the AI's previous reply;
  - it stays while either relates, with no fixed carry length;
  - it drops when neither relates, which covers topic changes and memories
    sent by mistake;
  - picked memories follow the same rules.
- **Why:** the memory block is rebuilt every message and is not kept in the
  history (A.6). Without this, a short reply can lose the memory the
  conversation is about.
- **Keep it modest:** it uses the same scoring functions as live search,
  needs no new storage beyond the existing record of what was sent last,
  and adds no new system.
- **Tunable:** the lower bar.
- **Done when,** in the test conversations (step 1.2):
  - a memory stays through a long run of short replies while the topic
    continues;
  - it drops after a clear topic change;
  - a memory sent by mistake drops on the next message;
  - a memory the AI keeps mentioning drops once the user writes about
    something else.

### Step 1.13: The same memory rules on both send paths

- **Change:** make the memory-side lorebook repeat check (`NearDuplicate`)
  run on both send paths (A.9). Hand the matched lorebook entries to the
  memory system **for comparison only** on the path that passes none
  today.
- **Protection:** the lorebook's own entries, text, order, size limit, and
  placement do not change on either path. The step 1.1 tests prove it.
- **Picked memories:** already protected on both paths. Both hand the picked
  IDs to the memory system, which keeps search from adding them again. The
  tests confirm it for both paths.
- **Done when:** on both paths, a memory that repeats a lorebook entry is
  not also sent, and the lorebook tests are unchanged.

### Step 1.14: Choose the vector size and the model by testing

- **Vector size:** run the fair test set (step 1.2) with 256 and 768. Keep
  256 unless 768 is clearly better on the held-back messages and the
  owner's saved cases. If 768 is chosen, it ships only after step 1.6, so
  the full rebuild never sends a message to keyword-only search, and the
  `NearDuplicate` and `PossibleMatchFinder` thresholds are re-checked
  (B.5).
- **Model:** the default stays as it is (owner decision D29, revised
  2026-11-08). The user already chooses q4 or int8 in Advanced Memory
  Settings. If int8 is ever proposed as the default, it needs evidence that
  it improves retrieval, not just that it runs. A pass on one device does
  not prove every supported phone, and q4 stays available as a working
  fallback.
- **Done when:** only if this step is taken, the chosen vector size is backed
  by the test conversations.

### Step 1.15: Test whether automatic tags help

- **Owner decision (D28):** tags are not made mandatory. Tags stay what
  they were meant to be: a way for the user to find their own memories.
- **Test:** in the fair test set, generate tags for the memories
  independently, without seeing the test messages, and compare search with
  and without them on the held-back messages.
- **Only if the gain is real:** propose to the owner that the Memory
  Assistant suggest topic tags, with the exact instruction text for
  approval. Any suggested tags appear in Pending for the user to edit, as
  today. A one-time pass for existing memories is a separate later
  decision, because it uses tokens.
- **Done when:** the test result is reported to the owner.

### Step 1.16: Variety (deferred)

Deferred (review, 2026-11-08). MMR (maximal marginal relevance) is added only
if testing shows near-identical memories actually crowding out distinct
relevant ones. The starting numbers in "Where things stand" are the defaults
that Reset restores.

### Not included: an on-phone re-ranker

Tested and rejected (A.10). Reconsider only if a small re-ranker made for
short personal text becomes available. Any candidate is tested against the
test set and the owner's saved cases first (D21).

### Phase 1 in pull requests

1. **#180 (built):** safety-protected bug fixes (steps 1.5–1.11), card
   cooldown removal, and the test screen (step 1.4), with the review
   corrections.
2. **Next:** carry-over, both send paths, and the modest test conversations
   (steps 1.12, 1.13, 1.2).
3. **Only if testing shows a need:** vector size (1.14), tags (1.15), and
   variety (1.16).

### Phase 1 is complete when

- the test conversations (step 1.2) pass;
- the lorebook and picked-memory protections hold on both send paths;
- a memory stays with the conversation while the topic continues (step
  1.12);
- on the phone, the owner can see on the test screen that unrelated
  messages send nothing, relevant memories are sent every time they are
  relevant, and +3 memories are sent only when relevant;
- the owner confirms the symptoms are gone in real chats.

## Phase 2: Today's date

**Blocked until D1–D5 and D22 are decided.** Details: Part D, section D.3.

1. **One date source:** today's date from the phone's calendar, in the
   phone's time zone, date only, never the time of day. Every memory
   feature reads it from this one place. Tests cover the time-zone change
   and midnight.
2. **The date-sharing setting:** adds today's date to what the AI receives,
   in the agreed format (D2), off unless the user turns it on (D1).
   Roleplay handling per D3. Wording needs approval first, and must state
   plainly:
   - this is the AI knowing the date inside a chat, not a notification;
   - the app never sends notifications about dates;
   - relative timing for dated memories ("tomorrow") can still reach the AI
     with date sharing off (D4).
3. **Backup:** the setting goes into the settings backup.

## Phase 3: Always Know, May Bring Up, and dates on memories

**Future proposal. Blocked until D31, D10–D12, and D20 are decided.**
Details: Part D, sections D.2, D.5, D.7.

1. **Database migration** from version 33 adds:
   - an Always Know flag;
   - a May Bring Up flag;
   - date, one-time or yearly, and optional year;
   - an internal last-on-topic date (used by fading, D.4).

   Existing memories get neither flag and no date, so what the AI receives
   does not change.
2. **Backup, export, restore, and import** carry every new field. Restoring
   an older backup fills the defaults, never an error. Round-trip tests for
   each path.
3. **Memory editor:** the two choices and an optional date. No fading,
   strength, or scoring controls. Those stay internal or on the Debug test
   screen.
4. **Memory card:** at most a small marker for Always Know, May Bring Up,
   and an upcoming date. No technical numbers.
5. **Browser:** the filters in D20.
6. **Possible Match:** a different date on a matching memory counts as a
   change to review, not a duplicate.
7. Wording and layout need approval before each screen is built.
   Appearance follows `ui-style-guide.md`.

## Phase 4: Fading (proposed for removal)

**Recommended not to build (D34).** If the owner wants fading anyway, it is
redesigned as a per-memory choice first. Details: Part D, section D.4.
The steps below are kept only for reference.

1. **What fading decides:** only how long an undated May Bring Up memory
   stays eligible to be raised unprompted after its subject was last
   discussed.
2. **What it does not touch:** search ranking (D9), and what is stored.
3. **"Discussed" means the conversation, not one message.** A memory counts
   as discussed while carry-over (step 1.12) keeps it on topic, so a long
   run of short replies counts as continued discussion. Being sent by the
   briefing alone never counts.
4. **Math:** pure functions with tests for the half-life and long gaps.
5. **Debug test screen:** shows each May Bring Up memory's remaining
   eligibility.

## Phase 5: The standing briefing

**Future proposal. Blocked until D13, D15–D18, D30–D32, and D37 are decided.**
Details: Part D, section D.6.

1. **Two separate parts:**
   - **Background:** Always Know memories, sent every message as context,
     with an instruction not to raise them on their own.
   - **May mention:** May Bring Up memories that are currently due. They
     are sent every message while due and marked "already discussed" once
     they come up (D32).
2. **Its own cap:** background has a separate cap that does not count
   against search's limits (D16). Picked memories come first and are never
   displaced. The request's total size check still applies, with the
   trimming order in D37.
3. **No repeats:** search skips briefing items, and the briefing skips
   picked memories.
4. **Placement:** after the fixed instructions, Model Rules, and card
   material; before searched memories.
5. **Guidance to the AI** for may-mention items, using text the owner
   approved (D13).
6. **Switches:** a master switch (D18), off whenever memory is off for the
   chat, and roleplay per D17.
7. **Debug test screen:** shows the briefing and why each item is in it.

## Phase 6: The Memory Assistant proposes dates

**Future proposal. Blocked until D14 and D33 are decided.** Details: Part D,
section D.5.

1. Proposals can carry a date and whether it is one-time or yearly. The user
   sees and edits it in Pending before saving.
2. **Not proposed:** Always Know and May Bring Up stay the user's own
   choices until their behavior is established (D33). This keeps Pending
   simple.
3. Relative dates ("tomorrow") are worked out from the day the message was
   sent, using the transcript's turn times, never the day the analysis runs.
4. Vague timing gets no date.
5. If the Memory Assistant is being changed for another reason at the same
   time, coordinate the two changes, because they touch the same code.

---

# Part D: Design details (future proposals)

Simplified after the independent reviews (2026-11-08). One rule runs through
this part:

**Availability, relevance, and permission to mention unprompted are three
separate decisions.**

- **Availability:** what the AI has in front of it this message.
- **Relevance:** whether it matters to what is being said now.
- **Permission:** whether the AI may raise it unprompted.

A memory can be available without being relevant, and the AI can know
something without talking about it. Never limit what the AI receives in
order to stop it behaving annoyingly. Instead, tell it how to behave. The
cooldown and the "once per day" idea both made that mistake, and both are
gone.

## D.1 What the user should experience

- **Search, as built:** a memory reaches the AI when the conversation is
  about it.
- **Always Know:** a few memories the AI always has as background, such as
  the user's name, pets, and the people in their life. Knowing is not
  raising: the AI uses them when relevant and does not bring them up on its
  own.
- **May Bring Up:** memories the user has given permission for the AI to
  raise unprompted at a suitable moment, such as an upcoming interview or a
  birthday. Nothing is ever raised unprompted without this permission
  (D30). A painful topic the user has discussed a lot is never volunteered
  just because it was discussed.
- **Dates:** an optional date decides when a May Bring Up memory is due. A
  one-time event is due in the days before and briefly after. A yearly date
  is due around the date each year. An anniversary of a loss is raised only
  if the user allowed it. Once it has come up in a chat, the AI keeps
  knowing it but is told it has already been discussed (D.5).
- **No notifications:** all of this happens inside chats. The app never
  notifies.

**Why anything is sent every time:** the AI has no memory of its own between
messages. If the app doesn't send something, it doesn't exist for the AI.

## D.2 What the user decides

For each memory, at most three things:

| Choice | Meaning | Default |
|---|---|---|
| Always Know | Sent every message as background; never raised on its own | Off |
| May Bring Up | The AI may raise it unprompted when it is due | Off |
| Date (optional) | When a May Bring Up memory is due: one-time or yearly | None |

**Every combination, defined:**

| Always Know | May Bring Up | What the AI gets | May it raise the memory unprompted? |
|---|---|---|---|
| Off | Off | Only when search finds it relevant (as today) | No |
| On | Off | Every message, as background | No. It uses it when relevant |
| Off | On | While due: every message, as "may mention". Not due: only through search | Yes, while due |
| On | On | Every message, as background | Yes, while due |

- **When "due" applies:**
  - with a date, a memory is due inside its window;
  - without a date, a May Bring Up memory is due until the user turns
    permission off (D34).
- **Internal, never a user choice:** windows and scores. They are visible
  only on the Debug test screen.
- **Example:** a user saving an upcoming appointment turns on May Bring Up
  and sets the date. Nothing else.
- **Existing memories** have neither choice on and no date, so they behave
  exactly as after Phase 1.
- **Nothing is deleted, archived, or changed automatically.** Search still
  finds every memory whenever it becomes relevant.

## D.3 Today's date

- **One source:** the phone's calendar date, in the phone's time zone, at the
  moment a message is sent. Date only, never the time of day.
- **The memory system always uses it,** for date windows and fading. That
  sends nothing anywhere.
- **Sharing it with the AI** is a user setting (D1), written so it can't be
  misread (D2), with roleplay handled per D3.
- **Relative timing** ("tomorrow", "in 5 days") for a due May Bring Up
  memory reaches the AI even with date sharing off (D4). The setting's
  wording must say so.
- **This is awareness inside a chat, not a notification.**

## D.4 Fading (proposed for removal, D34)

The second review found that automatic fading of undated May Bring Up
memories would drop exactly the follow-ups people want. For example, "did
you hear back about the job?" matters most after a long silence.

**Recommendation:** no automatic fading. An undated May Bring Up memory stays
due until the user turns permission off or the matter is settled (the user
edits or supersedes it). Repetition is handled by "already discussed in this
chat" (D.5), not by withholding.

If fading is ever wanted, it is a per-memory choice the owner approves.
"Discussed" would then follow the conversation, through carry-over, and
never the AI's own replies alone. Search is never affected either way (D9).

## D.5 Dates

- **Stored:** day and month, plus year for one-time dates (optional for
  yearly), and one-time or yearly. No time of day.
- **Windows:** yearly dates are due for some days before through the day.
  One-time dates are due some days before, on the day, and briefly after
  (D10, D11).
- **Year boundary:** a 2 January birthday seen from 30 December is 3 days
  away.
- **29 February** in other years: D12.
- **Not raised repeatedly, without being withheld (D32, corrected):**
  - a due memory is sent every message while it is due, so the AI never
    loses it;
  - once the subject has come up in the chat, the memory is marked "already
    discussed in this chat";
  - the AI is told not to raise an already-discussed memory again, but it
    keeps the information and can use it when the user returns to it.
- **"Come up" is decided from the user's messages,** or a short user
  continuation of an exchange about it, not from the AI's replies alone.
- **What the AI is told:** the memory text, the app-worked-out timing, and
  approved guidance to raise it only when it fits naturally (D13).
- **Set by hand** in the editor, or **proposed by the Memory Assistant**
  (Phase 6). Relative dates count from the day the message was sent. Vague
  timing gets no date (D14).
- **Changed plans** go through the existing edit, supersede, and Possible
  Match paths.

## D.6 The standing briefing

- **Background** (Always Know): context the AI keeps. The instruction tells
  it not to raise these on its own.
- **May mention** (May Bring Up, currently due): sent every message while
  due. Each is marked "already discussed" once it has come up in the chat,
  and the AI is told not to raise it again (D.5).
- **Permission rule (D30):**
  - nothing is volunteered without the user's May Bring Up permission;
  - a subject is never volunteered just because it was discussed recently;
  - a painful topic is found by search only when the user raises it.
- **Size and cost (D16, owner):** background has its own cap, in memories
  and characters. It does not count against search's Maximum Memories or
  Memory Space, so it can never crowd out a relevant search result.
- **Priority, in order:**
  1. memories the user picked before sending, never displaced;
  2. search results, within their own limits;
  3. background, within its own cap;
  4. may-mention items.

  If the whole request is still too large for the model, the trimming order
  is D37.

  As a rough guide, 20 short background memories add about 600–1,200 tokens
  per message. Background goes first in a fixed order so prompt caching
  (cheaper repeated prompt starts, on providers that offer it) works.
- **Placement:** after fixed instructions, Model Rules, and card material;
  before searched memories.
- **No repeats:** search skips briefing items, and the briefing skips
  picked memories.
- **Scope:** the same eligibility check the current search uses. Roleplay
  per D17.
- **Off** whenever memory is off for the chat, and optional overall (D18).
- **Visible:** the Debug test screen shows what the briefing sent and why.

## D.7 Memory screens

- **Editor:** Always Know, May Bring Up, and an optional date. Nothing else.
- **Card:** small markers only. No strength, scores, or technical numbers.
- **Browser:** filters in D20.
- **Pending card:** a proposed date only (Phase 6), editable before saving.
- **Advanced numbers** stay on the Debug test screen, not in ordinary
  settings.
- **Look:** `ui-style-guide.md`. Wording needs owner approval.

## D.9 Inside jokes and "vibe" (owner request, 2026-11-08; proposal)

Published systems keep two kinds of memory that matter here (Part F):

- specific facts and events, which is what this app already stores;
- a short summary of how the user likes to talk and what the relationship
  is like. ChatGPT keeps "response preferences"; Claude keeps an editable
  summary of the user.

Inside jokes need both: the joke itself, and the relationship it lives in.

**Proposal, built from existing pieces rather than a new system:**

- **Inside jokes are ordinary memories:**
  - written with the joke and its origin ("we call the cat the gremlin
    since the couch incident");
  - given a user-made Memory Type such as "Inside Joke";
  - normally both Always Know and May Bring Up.

  Keyword search already finds the joke's phrase when the user uses it.
- **The AI needs to know a memory is a joke.** Today only the memory text is
  sent, not its type. For memories of a type the user marks as playful, the
  type name would be sent with the text, so the AI knows the memory is
  meant to be used lightly (D35).
- **"Vibe" is one short, user-editable note per companion.** It describes
  the tone of the relationship, its running themes, and how the companion
  should talk, and it is always sent with that companion's chats. Companion
  records already have an unused relationship-notes field for this (D36).
  The Memory Assistant could later suggest updates to it through Pending,
  never silently.
- **The trauma rule still holds:** a painful subject never becomes a joke or
  a vibe line unless the user writes it that way.

## D.8 Data, backup, privacy

- New columns are added. The retired `always_load` column and
  `owner_profile` table are not reused, so old meanings cannot leak in.
- Every new field goes into every memory backup, export, restore, and import
  path. The tuned search values (`retrieval_policy`) do too.
- Dates such as birthdays stay in the same encrypted database as everything
  else. Today's date leaves the phone only if the user turns on date
  sharing; relative timing for due memories is the only exception.

---

# Part E: Decisions

Asked one at a time in chat. A recommendation is a proposal only.

| # | Question | Recommendation or decision |
|---|---|---|
| D0 | Approve the recommended search design in Part B as the plan for Phase 1? | **Decided, 2026-11-08:** the direction is approved; details are left to engineering judgment (B.5) |
| D1 | Is date sharing off or on by default? | Off; the user turns it on |
| D2 | What date format goes to the AI? | Weekday, day, month name, year, which can't be misread |
| D3 | Does date sharing apply in roleplay chats? | No by default; the story may have its own time |
| D4 | With date sharing off, do reminders carry relative timing ("tomorrow")? | Yes; a reminder is useless without timing |
| D5 | Is the date-sharing setting app-wide, per chat, or both? | App-wide first; per chat only if wanted later |
| D6 | Are manually created memories lasting or fading by default? | **Retired (review, 2026-11-08):** fading is internal, not a user choice (D31) |
| D7 | How fast does fading go? | One app-wide speed, tunable only on the Debug test screen |
| D8 | Does editing a May Bring Up memory count as it being on topic? | Yes; editing is deliberate attention |
| D9 | Does fading affect search ranking? | No (review, 2026-11-08). Old memories stay fully searchable; reconsider only if testing shows a real need |
| D10 | How long are the windows? | Yearly: about a week before through the day. One-time: a few days before through two days after |
| D11 | Can windows be changed per memory? | Not at first |
| D12 | How is a 29 February date handled in other years? | 28 February |
| D13 | What does the guidance to the AI about dated reminders say? | Owner reviews the exact text before it is built |
| D14 | Does vague timing get a date? | No; the user can add one in Pending |
| D15 | Should the briefing be built at all? | Yes, after D30–D32 are settled; it is the only way the AI can bring things up on its own |
| D16 | How is the briefing's size limited? | **Owner's view (2026-11-08):** Always Know must not count against the per-message memory amount. So background gets its own cap, separate from search's Maximum Memories and Memory Space |
| D17 | Does the briefing run in roleplay chats? | Off by default in roleplay |
| D18 | Is the briefing on or off by default? | Off; it adds a cost to every message |
| D19 | Is fading strength shown on memory cards? | No; cards stay free of technical numbers. The Debug test screen shows it (review, 2026-11-08) |
| D20 | Which browser filters are added? | Upcoming dates, Always Know, May Bring Up |
| D21 | Should a local re-ranker be added? | No; tested and it did not work (A.10). Reconsider only if a suitable small model appears |
| D22 | Does date sharing ship on its own, before the rest? | Yes, if wanted sooner; it doesn't depend on the rest |
| D23 | When is search fixed? | **Decided, 2026-11-08: first** |
| D24 | Where does the search test screen open from? | **Decided, 2026-11-08:** its own screen, from Advanced Memory Settings in the Debug section, with adjustable numbers; the old Debug search box is removed. Still open: its wording and layout |
| D25 | Should the ordinary memory settings also have a Strict / Balanced / Broad match choice? | No; the test screen's tuned numbers are the setting, and a second control adds confusion |
| D26 | Should roleplay card entries also lose their 10-message cooldown? | **Decided, 2026-11-08: yes.** Card entries ride the same rebuilt message, so the cooldown hid them from the AI. Lorebooks were checked and have no cooldown |
| D27 | Which memory assembly log lines change when the cooldown and old search are removed? | **Decided, 2026-11-08:** the summary line, the memory cooldown line, the project wording, and the chat-search missing-vector line, as proposed. The card-cooldown log line stays in the code (it no longer fires) until its removal is approved |
| D28 | Should the Memory Assistant always add topic tags? | **Decided, 2026-11-08:** not mandatory now. Test independently generated tags first (step 1.15) |
| D29 | Should the int8 model become the default? | **Decided, 2026-11-08 (revised):** no change to the default. The user already picks q4 or int8 in Advanced Memory Settings |
| D30 | Which memories may the standing briefing bring up unprompted? | Only memories the user has marked May Bring Up. Dates and fading then decide when. Never volunteer a topic just because it was discussed a lot. The independent review strongly supports this |
| D31 | What does the user decide per memory? | Only Always Know, May Bring Up, and an optional date. Fading, windows, and scores stay internal (review, 2026-11-08) |
| D32 | How is repetition of a due May Bring Up memory controlled? | **Corrected (2026-11-08):** never by withholding it. It is sent every message while due, marked "already discussed" once it comes up, and the AI is told not to raise it again |
| D34 | Should undated May Bring Up memories fade automatically? | No. They stay due until the user turns permission off or settles the matter. Fading, if ever wanted, becomes a per-memory choice |
| D35 | Should the type name be sent with memories of a "playful" type, such as Inside Joke? | Yes, for types the user marks playful, so the AI knows to use them lightly |
| D36 | Should each companion have a short, editable "vibe" note that is always sent? | Yes, using the existing unused relationship-notes field. Memory Assistant suggestions only through Pending |
| D37 | If a whole request is too large for the model, what is trimmed first? | May-mention items, then background, then the lowest-ranked search results. Picked memories are never trimmed |
| D33 | What does the Memory Assistant propose beyond the memory text? | Dates only, at first. Always Know and May Bring Up stay the user's choices until their behavior is established |

---

# Part F: What published memory systems do (2026-11-08)

A short summary for design, not a specification. The sources are linked so
the owner or another reviewer can check them.

- **ChatGPT** has two layers: saved memories, which are explicit facts the
  user can view and delete, and "reference chat history".
  - Saved memories are placed in the model's instructions for later chats.
  - Chat history adds summaries: response-style preferences, past topics,
    and short summaries of recent conversations.
  - Only the saved-memory behavior comes from OpenAI's own help pages. The
    breakdown of what is sent is reverse-engineered by a third party, who
    notes it may be inaccurate.
  - Sources: [OpenAI help: how reference saved memories works](https://help.openai.com/en/articles/11146739-how-does-reference-saved-memories-work);
    [OpenAI: memory and new controls](https://openai.com/blog/memory-and-new-controls-for-chatgpt);
    [Embrace The Red: how ChatGPT remembers you](https://embracethered.com/blog/posts/2025/chatgpt-how-does-chat-history-memory-preferences-work).
  - **Lesson:** a set of always-available facts plus a summary of the user's
    style is what makes it feel like it "knows" you. That matches Always
    Know and the vibe note (D.9).
- **Claude** can search and reference past chats when asked, and can keep an
  automatically generated, user-editable summary of what it has learned.
  - Sources: [Engadget](https://engadget.com/ai/claude-can-now-reference-past-chats-if-you-want-it-to-211806343.html);
    [The Decoder](https://the-decoder.com/anthropics-claude-can-now-remember-past-conversations/).
  - **Lesson:** the summary is visible and editable, which supports keeping
    the vibe note user-editable.
- **Letta (formerly MemGPT)** splits memory into "core memory" blocks that
  are always in the model's context, each with its own size limit, and
  "archival memory" that is searched when needed.
  - Source: [Letta docs: agent memory](https://docs.letta.com/guides/agents/memory).
  - **Lesson:** always-present memory gets its own budget, separate from
    searched memory. That matches the owner's view on D16.
- **Generative Agents** (Park et al., 2023) ranks memories by relevance,
  recency, and importance. It periodically writes "reflections": higher-level
  insights drawn from many memories.
  - Sources: [arXiv 2304.03442](https://arxiv.org/pdf/2304.03442v2).
  - **Lesson:** reflections are the research name for "vibe". They are
    summaries above individual facts.
- **Mem0** (2025) extracts memories from conversations and compares each one
  against existing memories, then chooses to add, update, delete, or do
  nothing. That is close to this app's Memory Assistant with Possible Match.
  - Source: [arXiv 2504.19413](https://arxiv.org/html/2504.19413v1).
- **MemoryBank** (AAAI 2024) uses a forgetting curve, where memories fade
  unless reinforced, for long-term companion chat.
  - Source: [AAAI](https://ojs.aaai.org/index.php/AAAI/article/view/29946).
  - **Lesson:** fading has precedent, but in a companion app it risks
    dropping wanted follow-ups. Hence D34.
- **Inside jokes:** no published research addresses them directly. The
  closest ideas are relationship-event memory and summaries of the
  relationship, as in D.9.

