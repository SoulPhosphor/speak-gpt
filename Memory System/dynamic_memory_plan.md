# Memory Plan: Search Repair, Dates, Fading, and the Standing Briefing

**Written 2026-11-08 at the owner's request. Rewritten the same day as one
step-by-step plan, after a full review for ideas carried over from older
documents.**

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
  - +3 is a boost, not a pass (step 1.10);
  - the memory cooldown is wrong (step 1.11);
  - the lorebook stays strictly keyword-based and must not break (Phase 0);
  - a memory the user picks before sending is never included twice
    (Phase 0);
  - the search test screen is its own screen, with adjustable numbers,
    opened from Advanced Memory Settings, and it replaces the old Debug
    search box (step 1.4).
- **Everything else is a recommendation** waiting for the owner's decision
  (Part E).
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
   - keyword search with BM25, through SQLite's built-in full-text search
     (FTS5). The app already uses FTS5 for chat search with the encrypted
     database. Tags and the names of linked companions, worlds, characters,
     campaigns, and projects are part of the searchable text.
4. **Merge the two result lists**, so a memory found both ways ranks
   highest.
5. **Keep only memories that clearly stand out** from the rest for this
   message. When nothing stands out, no memories are sent.
6. **Order by relevance.** Importance is the only bonus (fading strength is
   added later, in Phase 4). It is capped so it can never beat a clearly
   more relevant memory. A relevant +3 is always included, even past the
   limit.
7. **Skip anything already in this request:** picked memories, briefing
   items (Phase 5), and memories that repeat a lorebook entry.
8. **Send up to the memory limit and size budget.** Empty slots stay empty.
9. **Send every relevant memory every time it is relevant.** No cooldown.

## B.2 What is removed and why

| Removed | Why |
|---|---|
| The fixed 0.30 cut-off | It sits inside the noise for this model (A.3) |
| Keyword search as an all-or-nothing backup | It matched every memory, and one missing embedding switched the whole message to it (A.2) |
| Recent turns glued onto the search | They raised every memory's score (A.4) |
| Scope, freshness, project, and tag bonuses | They outweighed relevance (A.5). Scope still decides which memories are allowed. Tags and names are found by keyword search instead. "Recently edited" does not mean "matters now"; fading (Phase 4) measures that properly |
| The 10-message memory cooldown | It hid relevant memories from the AI (A.6) |
| A separate Strict / Balanced / Broad setting | The test screen tunes the real number. A second control for the same thing adds confusion (D25) |

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

### Step 1.2: The search test set

- **Change:**
  - A set of sample messages, each paired with the memories that should
    come back. It includes messages that should bring back nothing, such
    as "hey how are you", and messages that use names, numbers, and rare
    words.
  - The real model's vectors for these are generated once and stored as
    test files. The model cannot run inside unit tests, but `searchCore`,
    `rank`, and the keyword ranking are pure functions that can be tested
    with stored vectors.
  - A report counts right, wrong, and missed memories for each message.
- **Done when:** the report runs against the current code and records its
  results (today: mostly wrong). Later steps add pass/fail checks for what
  they fix.

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

- **Stop point first:** the screen's labels, explanations, and layout need
  owner approval before the screen is built (D24). Present them, then wait.
- **Change:**
  - A new screen, opened from Advanced Memory Settings in the Debug
    section. The old Debug search box (`runDebugSearch`, its field, button,
    and results text) is removed in the same change.
  - The owner enters a message, picks which chat's context to test in
    (companion, project, roleplay, campaign), and chooses whether to
    include recent turns.
  - For each memory, the screen shows every number from step 1.3, whether
    it passed the relevance gate (and by how much it missed if not),
    whether it would be sent, and if not, why: below the gate, not allowed
    in this chat, over the limit, over the size budget, already picked,
    already in the briefing, or a repeat of a lorebook entry.
  - For the whole search, it shows which searches ran, how many memories
    are missing their embedding, the limits in effect, and the exact memory
    text the AI would receive.
  - **Adjustable numbers:** every tuning number used by the search can be
    changed here. Changes first apply only to the result on screen, and
    the search re-runs at once. **Apply** makes them the real values for
    every chat. **Reset** restores the defaults from step 1.13. Applied
    values are stored in the existing `retrieval_policy` record. Confirm
    every memory backup and restore path carries that record, so tuning is
    never lost.
  - The screen is read-only: it never changes a memory, never counts as a
    mention for fading (Phase 4), and sends nothing to any AI service.
  - Appearance follows `ui-style-guide.md`. Update `ui-style-adoption.md`.
- **Order note:** the screen comes before the fixes, so every later step can
  be checked on the phone with the owner's real memories. Each later step
  adds its own numbers to the screen.
- **Done when:** the screen shows the current search's real numbers for the
  owner's memories, and nothing in real chats has changed.

### Step 1.5: Keyword search with BM25

- **Change:**
  - Add an FTS5 full-text index for memories in the memory database
    (`MemoryStore`). It holds each memory's content, tags, and linked
    target names. It is kept in step with every way a memory is added,
    edited, deleted, archived, restored, or imported, and it is built for
    existing memories on first open.
  - Keyword search uses FTS5's built-in `bm25()` ranking. Common words
    count for almost nothing, and names and rare words count for a lot. A
    memory needs a minimum keyword score, not just one shared word.
  - Replaces word-overlap counting in `Librarian.rankLexical`.
- **Tunable on the test screen:** the minimum keyword score.
- **Done when:** in the test set, common-word matches no longer bring in
  memories, and name and rare-word matches do.

### Step 1.6: Meaning and keyword search together

- **Change:** in `Librarian.searchCore`, run meaning search over every
  memory that has a current vector, and keyword search over all memories,
  every message. Merge the two lists with reciprocal rank fusion, a
  standard way to combine two rankings. A memory found both ways ranks
  highest. A missing vector removes only that one memory from meaning
  search, never the whole message.
- **Tunable:** the balance between meaning and keyword results.
- **Done when:** in the test set, a missing vector affects only its own
  memory, and the memories missed in A.3 (for example the chat-app memory
  for an app crash) are found.

### Step 1.7: Search with the user's message on its own

- **Change:** in `Enforcer.kt`, the main search uses only the user's latest
  message. Recent turns get a separate search with lower weight, and only
  when the latest message is short or refers back ("what about that?",
  "yes", "tell me more"). The results are merged by each memory's best
  score. `Librarian.searchForReconciliation` already searches several text
  windows and merges them; live search uses the same approach.
- **Tunable:** the recent-turn weight, and what counts as a short message.
- **Done when:** in the test set, adding recent turns no longer pulls in
  unrelated memories, and "what about that?" still finds the topic from
  the turn before.

### Step 1.8: A relevance gate that fits each message

- **Change:** replace `MIN_SIMILARITY = 0.30` with a gate measured against
  this message's own scores. A memory must stand clearly above the other
  memories' scores for this message. A strong keyword match also passes.
  When the library is too small for a fair comparison, a fixed floor tuned
  on the test set is used instead. Passing nothing is a valid result.
- **Measured on the sample** (meaning search alone, before steps 1.5–1.7):

  | Rule | Right | Wrong | Missed |
  |---|---|---|---|
  | Today: fixed 0.30 | 6 | 22 | 2 |
  | Fixed 0.38 | 4 | 1 | 4 |
  | At least 2 standard deviations above this message's average | 4 | 0 | 4 |

  The relative rule sent nothing for "hey how are you", which is correct.
  Its misses are the kind keyword search (step 1.5) recovers.
- **Tunable:** the gate's strength and the small-library floor.
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
  cap, A ranks above B whatever their ratings. The Memory Assistant's
  search uses the same ranking code, so it gets the same guarantee.
- **Tunable:** the importance bonus and its cap.
- **Done when:** in the test set, the +3 memory no longer ranks first for
  unrelated messages.

### Step 1.10: +3 as the owner defined it

- **Rule (owner, 2026-11-08):** +3 is a boost, not a pass.
  - A +3 memory must be relevant, like any other memory. It is never sent
    just for being +3.
  - It gets the strongest importance boost.
  - If it is relevant and the memory limit is already full, it is added
    anyway.
- **Change:** the existing rule (`ImportanceRanking.includeMandatory`,
  `RetrievalBackfill.select`) already works this way once the gate works.
  Keep it, and add the tests below.
- **Tunable:** the +3 boost.
- **Tests:** a +3 memory unrelated to the message is not sent ("hey how are
  you"). A relevant +3 memory is sent even when 8 more relevant memories
  fill the limit.

### Step 1.11: Remove the memory cooldown

- **Rule (owner, 2026-11-08):** the cooldown is wrong; it causes amnesia.
- **Change:** in `Enforcer.kt`, stop applying `COOLDOWN_TURNS` to
  memories. A relevant memory is sent every message it is relevant. The
  only skips are items already in this request: picked memories, briefing
  items, and lorebook repeats. Existing rows in `injection_cooldowns` are
  left in place (no data is deleted). Roleplay card entries are **not**
  changed in this step (D26).
- **Log stop point:** the memory assembly log has cooldown lines for
  memories. Changing or removing them needs owner approval first (D27).
- **Done when:** in a chat that keeps talking about one topic, the relevant
  memory is sent on every message.

### Step 1.12: The same memory rules on both send paths

- **Change:** make the memory-side lorebook repeat check (`NearDuplicate`)
  run on both send paths (A.9). Hand the matched lorebook entries to the
  memory system **for comparison only** on the path that passes none
  today.
- **Protection:** the lorebook's own entries, text, order, size limit, and
  placement do not change on either path. The step 1.1 tests prove it.
- **Done when:** on both paths, a memory that repeats a lorebook entry is
  not also sent, and the lorebook tests are unchanged.

### Step 1.13: Variety and starting values

- **Change:**
  - Stop near-identical memories from filling several places, using MMR
    (maximal marginal relevance, the standard method).
  - Choose default values for every tunable number from the test set.
    These are what **Reset** restores on the test screen.
  - Run the int8 model (already in the app's model list) against the test
    set. If it is clearly better than q4, raise it with the owner.
- **Done when:** the test set passes with the defaults, and the owner has
  checked the owner's own messages on the test screen.

### Step 1.14: Optional local re-ranker

A small second model on the phone that re-orders the top results more
carefully. It costs no tokens, but it is another download and adds a short
delay per message. Build it only if the test set and the owner's testing
show steps 1.5–1.13 are not enough (D21).

### Phase 1 is complete when

- the test set passes;
- the lorebook and picked-memory tests from step 1.1 pass unchanged;
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
   Roleplay handling per D3. Wording needs approval first.
3. **Backup:** the setting goes into the settings backup.

## Phase 3: Dates, lasting or fading, and core on memories

**Blocked until D6, D10–D12, D19, and D20 are decided.** Details: Part D,
sections D.2 and D.4–D.7.

1. **Database migration** from version 33 adds: date, one-time or yearly,
   optional year, lasting or fading, briefing core flag, strength and
   strength date, and last-mentioned date. Existing memories become
   lasting, undated, and not in core, so what the AI receives does not
   change.
2. **Backup, export, restore, and import** carry every new field. Restoring
   an older backup fills the defaults, never an error. Round-trip tests
   for each path.
3. **Memory editor:** date, one-time or yearly, lasting or fading, and core.
4. **Memory card:** the date and next occurrence, strength for fading
   memories, last mentioned, and a core marker.
5. **Browser:** filters and sorting per D20.
6. **Possible Match:** a different date on a matching memory counts as a
   change to review, not a duplicate.
7. Wording and layout need approval before each screen is built.
   Appearance follows `ui-style-guide.md`.

## Phase 4: Fading

**Blocked until D7–D9 are decided.** Details: Part D, section D.4.

1. **Strength math:** pure functions for current strength and for a
   mention's boost, with tests for the half-life, the cap, and long gaps.
2. **Mention detection:** when the user's own message strongly matches a
   memory, using the Phase 1 search, its strength and last-mentioned date
   are updated. One mention per memory per message. The memory being sent
   to the AI, the AI's replies, the Memory Assistant, and the test screen
   never count.
3. **Ranking:** if D9 is yes, strength joins importance as a capped bonus.
4. **Test screen:** shows strength and lets it be tuned.

## Phase 5: The standing briefing

**Blocked until D13 and D15–D18 are decided.** Details: Part D, section D.6.

1. **Assembly:** core, then current fading memories, then dated memories
   in their window, in a stable order. Within its own limit (D16).
2. **No repeats:** anything in the briefing is skipped by search, and a
   picked memory is not repeated by the briefing.
3. **Placement:** after the fixed instructions, Model Rules, and card
   material; before searched memories.
4. **Guidance to the AI** for dated reminders, using text the owner
   approved (D13).
5. **Switches:** the master switch (D18), off whenever memory is off for the
   chat, and roleplay per D17.
6. **Test screen:** shows the briefing and why each item is in it.

## Phase 6: The Memory Assistant proposes dates, fading, and core

**Blocked until D14 is decided.** Details: Part D, section D.5.

1. Proposals can carry a date, one-time or yearly, lasting or fading, and a
   core suggestion. The user sees and edits each in Pending before saving.
2. Relative dates ("tomorrow") are worked out from the day the message was
   sent, using the transcript's turn times, never the day the analysis
   runs.
3. Vague timing gets no date.
4. If the Memory Assistant is being changed for another reason at the same
   time, coordinate the two changes, because they touch the same code.

---

# Part D: Design details

## D.1 What the user should experience

Today the AI hears about a memory only when the current message is about the
same thing. It never brings anything up on its own, so it never feels like it
knows the user.

This plan adds three behaviors that work the way people remember things:

- **Some facts always matter.** The user's name, pets, the people in their
  life. The user chooses which of these the AI always hears.
- **Some things matter for a while.** "This problem at work is weighing on
  me." While the user keeps bringing it up, it stays strong. Once the user
  stops mentioning it, it gradually stops being volunteered. It is never
  deleted and can still be found when the topic comes back.
- **Some things matter around a date.**
  - A one-time event, such as an interview. In the days before, the AI
    knows it is coming ("Are you nervous about tomorrow?"). Shortly after,
    it can ask how it went. Then it stops being brought up.
  - A yearly date, such as a birthday. It comes up as the date approaches,
    every year.

**Why anything is sent every time:** the AI has no memory of its own between
messages. If the app doesn't send something, it doesn't exist for the AI.
Search sends only what matches the current message, so it can never bring up
the interview unprompted. The briefing (D.6) does that.

## D.2 The three kinds of memory

Each memory gets two independent properties.

- **How it ages:** lasting (its weight never changes on its own, as every
  memory works today) or fading (D.4).
- **Whether it has a date:** none (as today), one-time, or yearly. A yearly
  date's year is optional.

| Example | Ages | Date | What the AI experiences |
|---|---|---|---|
| The user's name | Lasting | None | Always in the briefing, if the user marked it core |
| A worry the user keeps raising | Fading | None | In the briefing while strong, then found only by search |
| An interview | Lasting or fading | One-time | In the briefing during its window, then found only by search |
| A birthday | Lasting | Yearly | In the briefing during its window, every year |

**Rules for every kind:**

- Nothing is deleted, archived, or changed automatically. Fading and date
  windows change only what the AI is told without being asked.
- Search still finds everything, exactly as after Phase 1.
- Existing memories keep working exactly as before until the user changes
  them.

## D.3 Today's date

- **One source:** the phone's calendar date, in the phone's time zone, at the
  moment a message is sent. Date only, never the time of day.
- **The memory system always uses it,** for fading and date windows. That
  sends nothing anywhere.
- **Sharing it with the AI** is a user setting (D1), written so it can't be
  misread (D2), with roleplay handled per D3.
- **Reminders without date sharing** can still say "tomorrow" or "in 5
  days", worked out by the app (D4).

## D.4 Fading

- **In plain terms:** each fading memory has a strength. When the user
  talks about it, strength goes up. Each day without a mention, it loses a
  fixed share of what is left: after one half-life it is at half, after two
  at a quarter. Above a cut-off, the memory is "current" and goes into the
  briefing.
- **Technical shape:** each memory stores its strength at the last update
  and the date of that update. Current strength = stored strength × 0.5 ^
  (days since update ÷ half-life). On a mention: current strength + boost,
  capped at full. Nothing runs in the background; strength is worked out
  only when needed.
- **What counts as a mention:** only the user's own message strongly
  matching the memory, once per message. Never the memory being sent, the
  AI's replies, the Memory Assistant, the test screen, or opening the
  memory. If being sent counted, briefing items would refresh themselves
  forever and never fade.
- **Editing:** whether editing refills strength is D8.
- **Last mentioned** is recorded for every memory, lasting ones included, so
  the card can show it.

## D.5 Dates

- **Stored:** day and month, plus year for one-time dates (optional for
  yearly), and one-time or yearly. No time of day.
- **Windows:** yearly dates show for some days before through the day.
  One-time dates show for some days before, the day, and a short follow-up
  after (D10, D11).
- **Year boundary:** a 2 January birthday seen from 30 December is 3 days
  away.
- **29 February** in other years: D12.
- **What the AI is told:** the memory text, the app-worked-out timing
  ("today", "in 3 days", "2 days ago"), and approved guidance to raise it
  only when it fits naturally and not to repeat it once discussed (D13).
- **Set by hand** in the editor, or **proposed by the Memory Assistant**
  (Phase 6). Relative dates are worked out from the day the message was
  sent. Vague timing gets no date (D14).
- **Changed plans** go through the existing edit, supersede, and Possible
  Match paths.

## D.6 The standing briefing

- **What it is:** a small block sent with every message, rebuilt each time
  from:
  - **core:** memories the user explicitly marked. The AI never adds to
    core on its own. The Memory Assistant may suggest core only through
    Pending;
  - **current:** fading memories above the cut-off;
  - **upcoming:** dated memories inside their window.
- **User control:** the user chooses core, everything automatic follows
  visible rules, and the whole briefing can be turned off (D15, D18).
- **Size and cost:** its own limit in memories and tokens (D16). Every item
  is sent with every message. As a rough guide, 20 short memories add about
  600–1,200 tokens per message, and 50 add about 1,500–3,000. These counts
  are illustrations, not proposed defaults. Core goes first in a fixed
  order so prompt caching (cheaper repeated prompt starts, on providers
  that offer it) works.
- **Placement:** after fixed instructions, Model Rules, and card material;
  before searched memories. This matches the order the current code uses.
- **No repeats:** search skips briefing items, and the briefing skips
  picked memories.
- **Scope:** the same eligibility check the current search code uses
  (companion memories only for that companion, and the code's existing
  roleplay eligibility). Roleplay per D17.
- **Off** whenever memory is off for the chat.

## D.7 Memory screens

- **Editor:** date, one-time or yearly, lasting or fading, core.
- **Card:** date and next occurrence, strength for fading memories (D19),
  last mentioned, core marker.
- **Browser:** filters and sorting (D20).
- **Pending card:** proposed date, fading choice, and core suggestion, each
  editable before saving.
- **Look:** `ui-style-guide.md`. Wording needs owner approval.

## D.8 Data, backup, privacy

- New columns are added. The retired `always_load` column and
  `owner_profile` table are not reused, so old meanings cannot leak in.
- Every new field goes into every memory backup, export, restore, and import
  path. The tuned search values (`retrieval_policy`) do too.
- Dates such as birthdays stay in the same encrypted database as everything
  else. Today's date leaves the phone only if the user turns on date
  sharing; relative timing in reminders is the only exception.
- What was sent is visible on the test screen.

---

# Part E: Decisions

Asked one at a time in chat. A recommendation is a proposal only.

| # | Question | Recommendation or decision |
|---|---|---|
| D0 | Approve the recommended search design in Part B as the plan for Phase 1? | Yes |
| D1 | Is date sharing off or on by default? | Off; the user turns it on |
| D2 | What date format goes to the AI? | Weekday, day, month name, year, which can't be misread |
| D3 | Does date sharing apply in roleplay chats? | No by default; the story may have its own time |
| D4 | With date sharing off, do reminders carry relative timing ("tomorrow")? | Yes; a reminder is useless without timing |
| D5 | Is the date-sharing setting app-wide, per chat, or both? | App-wide first; per chat only if wanted later |
| D6 | Are manually created memories lasting or fading by default? | Lasting, the same as today |
| D7 | How fast does fading go, and can the user change it? | One app-wide speed, tunable on the test screen |
| D8 | Does editing a fading memory refill its strength? | Yes; editing is deliberate attention |
| D9 | Does strength also nudge search ranking? | Yes, capped like importance; never makes an irrelevant memory eligible |
| D10 | How long are the windows? | Yearly: about a week before through the day. One-time: a few days before through two days after |
| D11 | Can windows be changed per memory? | Not at first |
| D12 | How is a 29 February date handled in other years? | 28 February |
| D13 | What does the guidance to the AI about dated reminders say? | Owner reviews the exact text before it is built |
| D14 | Does vague timing get a date? | No; the user can add one in Pending |
| D15 | Should the briefing be built at all? | Yes; it is the only way the AI can bring things up on its own |
| D16 | Does the briefing get its own size limit control? | Yes, separate from the search limit |
| D17 | Does the briefing run in roleplay chats? | Off by default in roleplay |
| D18 | Is the briefing on or off by default? | Off; it adds a cost to every message |
| D19 | How is strength shown on the card? | A simple level plus the last-mentioned date, not a raw number |
| D20 | Which browser filters and sorts are added? | Upcoming dates, core members, sort by strength |
| D21 | Should a local re-ranker be added? | Only if testing shows Phase 1 is not enough |
| D22 | Does date sharing ship on its own, before the rest? | Yes, if wanted sooner; it doesn't depend on the rest |
| D23 | When is search fixed? | **Decided, 2026-11-08: first** |
| D24 | Where does the search test screen open from? | **Decided, 2026-11-08:** its own screen, from Advanced Memory Settings in the Debug section, with adjustable numbers; the old Debug search box is removed. Still open: its wording and layout |
| D25 | Should the ordinary memory settings also have a Strict / Balanced / Broad match choice? | No; the test screen's tuned numbers are the setting, and a second control adds confusion |
| D26 | Should roleplay card entries also lose their 10-message cooldown? | Probably yes, since it has the same amnesia flaw, but cards are keyword-based and working, so leave them until the owner decides |
| D27 | Which memory assembly log lines change when the cooldown and old search are removed? | Present the exact lines at step 1.11 and wait for approval |
