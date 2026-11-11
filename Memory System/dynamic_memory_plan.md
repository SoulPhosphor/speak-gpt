# Memory Plan: Search Repair, Dates, Fading, and the Standing Briefing

**Written 2026-11-08 at the owner's request. Rewritten the same day as one
step-by-step plan, after a full review for ideas carried over from older
documents.**

## Where things stand (read this first)

**Last updated 2026-11-10** (a fresh review of #180; carry-over put on hold;
Part G added for the conversation index and recall). The owner relies on the
agent to lead this work step by step. A new session reads this section
first, then continues from "Next steps". Do not re-diagnose what Part A
already measured. An independent review of the plan and of pull request #180
was folded in on 2026-11-08; its points are reflected below and in Part E.

### 0. Review on 2026-11-10: read this before anything else

A second agent reviewed #180 and this plan with the owner on 2026-11-10.

- **#180 is a sound foundation but unfinished.** Two gaps are live on the
  beta: follow-up messages lose their memories, and long messages miss
  memories. Details: G.2.
- **On hold (owner, 2026-11-10):** carry-over (step 1.12) and everything
  else in section 3b. Nothing there is built until the owner approves
  `conversation_recall_plan.md`.
- **New work wanted by the owner:** recall of earlier conversation, built on
  a proper index. The whole design is in `conversation_recall_plan.md`, at
  the top level of the repository. Its sections are numbered G.0 to G.19.
  It is a proposal for the owner and outside reviewers to read. Only the
  items it marks "Owner" are decisions.
- **Unconfirmed approvals.** Part E marks many rows "Approved" under a rule
  that a recommendation the owner saw and did not object to counts as
  approved. The owner has not confirmed making that rule. Until the owner
  does, treat every row that is not marked "Decided (owner)" as a proposal,
  not a decision (`CLAUDE.md` section 2).
- **Measurements in Part A are small.** The best result quoted in A.10 came
  from a setup that did not ship (a different model variant, full-size
  vectors, hand-written tags). G.18 has the published figures.

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

### 3. Inside Joke and the per-memory vibe (one pull request)

Built in the pull request from branch `ccr-985655d1-x68k8w` (owner approval,
2026-11-10).

- **Inside Joke (D56):** a built-in Type, in the editor's type picker with
  the definition "A joke, nickname, or running gag you share." Database
  version 34 adds it for existing users; restoring an older backup keeps it.
  The AI receives "Inside joke:" before the memory text. The Memory
  Assistant may suggest it.
- **Vibe (D58):** an optional note on each memory, under Importance in the
  memory editor ("Vibe", with the hint "How the AI should treat this memory,
  like playful or gentle."). The AI receives it on its own line under the
  memory ("  Vibe: ..."), it counts toward Memory Space, and it shows on
  browser and pending cards while memory text is shown. Search never reads
  it. Backups, restores, and imports carry it. The Memory Assistant does not
  suggest vibes.
- **The existing "- " before each memory line stays** (owner, 2026-11-10).
- **Follow-up for the owner:** when a Memory Assistant update replaces a
  memory (Save & Supersede or Replace), the new version starts without the
  old one's vibe, as it does today for the title. Whether the vibe should
  carry over is an open question.

### 3b. The pull request after that (approved direction, not yet built)

**On hold (owner, 2026-11-10).** Nothing in this section is built until the
owner approves `conversation_recall_plan.md`. Carry-over is redesigned
there (G.9).

- **Carry-over** (step 1.12), with the revised rule in B.5: no fixed carry
  length.
- **The lorebook repeat check on both send paths** (step 1.13). Picked
  memories are already protected on both paths.
- **A modest set of representative test conversations** (step 1.2) as
  automated tests.
- **A previous-exchange field on the test screen,** for checking carry-over.
- **The memory-use instruction** (D41), with its exact text shown to the
  owner before it is built. No crisis or safety wording (owner ruling).

### 4. Later phases (approved designs; built only when the owner says)

- **Rest of Phase 1, only if testing shows a need:**
  - vector size (step 1.14);
  - the tag test (step 1.15);
  - variety (step 1.16).
- **Phases 2–6** (Part C), in order:
  - Phase 2, data control: the view dropdown, Retired, Memory Handling
    Preferences, and Delete to Retired;
  - Phase 3, the Always tab and the bring-up setting;
  - Phase 4, today's date, important dates, and the Dates tab;
  - Phase 5, fading;
  - Phase 6, Memory Assistant date proposals.
- **Later features:** the background reviewer, the vibe note, recent-chat
  continuity, the companion journal, and Model Rules adjustments.
- **Decisions:** the designs are approved (Part E). Before each part is
  built, the owner sees its exact wording, screens, and AI-facing text.

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
- **Always memories have their own limit.** They do not count against the
  search memory limit or Memory Space (owner, D16).
- **Picked memories come first, always.** Nothing automatic can displace
  them.
- **Fading stays, as the owner decided** (D34, D43): optional on each
  memory, with markers and the Retired area.
- **The May Bring Up switch is dropped.** Always memories, and memories with
  an important date, carry a bring-up setting instead (D48).
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
3. **Done:** section 3's pull request (#182) is merged.
4. **Owner and outside reviewers:** read `conversation_recall_plan.md` and
   correct it.
5. **Owner:** answer the open decisions in G.17 only as each stage needs
   them. Only G5 and G6 are still needed before stage 1.
6. **Agent:** build nothing until the owner approves that plan. Then build the
   stages in G.16, in the order the owner chooses, each as its own pull
   request with its own tests (G.15, G.19).
7. **Agent, each when the owner asks for it:** Phase 2 (data control),
   Phase 3 (the Always tab), Phase 4 (important dates and the Dates tab;
   settle D55 first), Phase 5 (fading), Phase 6 (Memory Assistant date
   proposals), then the later features. Before each, show the owner its
   exact wording, screens, and AI-facing text.

## How to use this document

- **Part A** explains what is wrong with memory search today, measured
  against the real model.
- **Part B** is the recommended design.
- **Part C** is the build plan, in phases and numbered steps. Each step says
  what changes, where in the code, what it must not break, how it is tested,
  and when it is done.
- **Part D** holds the detailed design for the later phases: Always, the
  bring-up setting, dates, fading, Retired, and the briefing. Phases 2–6
  refer to it.
- **Part E** lists every decision, both decided and open.
- **Part F** summarizes published memory systems.
- **Part G** now lives in `conversation_recall_plan.md`: the design for
  the conversation index, recall, and carry-over (2026-11-10). It is a
  proposal until the owner approves it.

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
- **Recommendations the owner has seen and not objected to count as
  approved** (owner ruling, 2026-11-08). Part E records each one. This
  applies to design choices discussed for this plan only. It is not standing
  permission for unreviewed changes or for building anything. Exact wording
  and AI-facing text still need the owner to see the actual text.
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
  label, explanation, and message the user sees is shown to the owner before
  it is built. The exceptions are names already settled in Part E: Always,
  Retired, Memory Handling Preferences, and the bring-up choices.
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
   items (Always and due dated memories, Phases 3 and 4), and memories that
   repeat a lorebook entry.
9. **Send up to the memory limit and size budget.** Empty slots stay empty.
10. **Send every relevant memory every time it is relevant.** No cooldown.

## B.2 What is removed and why

| Removed | Why |
|---|---|
| The fixed 0.30 cut-off | It sits inside the noise for this model (A.3) |
| Keyword search as an all-or-nothing backup | It matched every memory, and one missing embedding switched the whole message to it (A.2) |
| Recent turns glued onto the search | They raised every memory's score (A.4) |
| Scope, freshness, project, and tag bonuses | They outweighed relevance (A.5). Scope still decides which memories are allowed. Tags and names are found by keyword search instead. "Recently edited" does not mean "matters now"; fading (Phase 5) handles that where the user turns it on |
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

## Phase 2: Data control

**Approved design (D.11, D.12, D44). Changes nothing about what the AI
receives.** It gives the user control and visibility first.

1. **Browser view dropdown:** the "Memories | Pending" words become one
   dropdown, in the canonical dropdown style. This phase adds Memories,
   Pending, and Retired. Always is added in Phase 3 and Dates in Phase 4.
   Memories then lists only memories in use.
2. **Retired view:** everything taken out of use, visible at once:
   - memories the user archived (today they are mixed into Memories, marked
     only by a small badge);
   - deleted memories, when the setting below is on;
   - memories retired by fading, from Phase 5.

   Each can be reinstated, or deleted permanently from there. Nothing in
   Retired is sent to the AI or found by search.
3. **Memory Handling Preferences:** a new settings screen, separate from the
   crowded memory settings screen. In this phase it holds whether Delete
   sends a memory to Retired first (default on, D44). The Always limit
   arrives in Phase 3 and the fade time in Phase 5.
4. **Delete:** with the setting on, Delete moves a memory to Retired. With
   it off, Delete stays permanent, as today.
5. **Backup, export, restore, and import** carry the setting and every
   Retired memory.
6. Wording and layout are shown to the owner before each screen is built.
   Appearance follows `ui-style-guide.md`.

## Phase 3: The Always tab and the bring-up setting

**Approved design (D.2, D.6, D.12, D16, D46–D48, D52, D53).**

1. **Database migration** from version 33 adds, for each memory:
   - Always membership and whether it is checked;
   - the bring-up setting for Always memories, default When it fits;
   - the Custom condition text.

   Existing memories are not in Always, so what the AI receives does not
   change.
2. **The Always tab** in the view dropdown (D.12, D52):
   - a checkbox on each memory; only checked ones are sent;
   - a "checked only" view and a running count;
   - the limit is checked at Save, with the keep-them-all exception;
   - the row menu returns a memory to a normal memory.
3. **Memory Handling Preferences** gains the Always limit: default 10, any
   number allowed, with subtext explaining the cost.
4. **Sending (D.6):** checked Always memories go with every message as
   background, under their own limit, never counting against search's
   limits (D16). Picked memories come first. Search skips memories already
   sent as Always, so nothing is sent twice.
5. **The bring-up setting** for Always memories: one dropdown with Only if
   I bring it up, When it fits, Ask me first, and Custom, which opens a
   write-in field (D.2). The setting goes to the AI with the memory, in text
   the owner approves first.
6. **Memory Search Test screen** shows the Always memories that would be
   sent.
7. **Roleplay:** Always memories are not sent in roleplay chats by default
   (D17).
8. **Backup, export, restore, and import** carry every new field. Restoring
   an older backup fills the defaults, never an error. Round-trip tests for
   each path.

## Phase 4: Today's date and dates on memories

**Approved design (D.3, D.5, D1–D5, D10–D12, D14, D39, D42, D49). The
guidance text to the AI (D13) is shown to the owner first.**

1. **One date source:** today's date from the phone's calendar, in the
   phone's time zone, date only, never the time of day. Every memory
   feature reads it from this one place. Tests cover the time-zone change
   and midnight.
2. **The date-sharing setting:** adds today's date to what the AI receives,
   in the agreed format (D2), off unless the user turns it on (D1).
   Roleplay handling per D3. Its wording must state plainly:
   - this is the AI knowing the date inside a chat, not a notification;
   - the app never sends notifications about dates;
   - relative timing for dated memories ("tomorrow") still reaches the AI
     with date sharing off (D4).
3. **Time since the last chat** with a companion, in days (D39), under the
   same sharing setting.
4. **Database migration** adds, for each memory:
   - an important date: day and month, an optional year, one-time or
     yearly, and its range of days (D.5);
   - whether it is checked on the Dates tab;
   - a "past after" date;
   - the event state: upcoming, happened, or settled (D42);
   - an internal last-discussed date, used by fading in Phase 5.
5. **Sending (D.5):** a checked memory whose date is near is sent with every
   message, past every limit, with timing worked out by the app, while the
   Memory Handling Preferences toggle is on. Once it has come up in the
   chat, it is marked "already discussed" (D32). After its event, or after
   its "past after" date, the AI is told it is in the past, and it leaves
   Always.
6. **The bring-up dropdown** also appears on memories with an important
   date (D48).
7. **The toggle** is added to Memory Handling Preferences, default on.
8. **Pending review:** an "Event passed" label (D50). If the user does
   nothing, the passed event is kept as a past event.
9. **Memory editor:** the optional dates and the range. **Card:** an
   upcoming-date marker.
10. **The Dates tab** in the view dropdown, with its checkboxes (D.11, D54).
11. **Possible Match:** a different date on a matching memory counts as a
    change to review, not a duplicate.
12. **Backup:** every new field, the toggle, and the date-sharing setting,
    with round-trip tests.

## Phase 5: Fading

**Approved design (D.4, D34, D43, D45, D49, D50).**

1. **Fading is an optional choice on each memory,** off unless the user
   turns it on (D.2). It uses the app-wide fade time from Memory Handling
   Preferences (2 weeks, 1 month, 3 months, or 6 months; default 1 month),
   or a custom number of days.
2. **A fading memory retires** after its fade time without being discussed.
   "Discussed" is defined in D.4. Being sent as Always or as a due date never
   counts.
3. **Markers:** a fading memory's card shows when it was last discussed and
   the date it will retire. The browser marks memories as fading, not
   fading, or faded.
4. **Retiring:** a fully faded memory moves to Retired (D43). A reinstated
   memory starts fresh.
5. **Pending review:** a "Retiring soon" label, with one-tap renew. If the
   user does nothing, the memory retires.
6. **The fade time setting** is added to Memory Handling Preferences.
7. **Tests** for the timing, long gaps, and reinstating.

## Phase 6: The Memory Assistant proposes dates

**Approved design (D14, D33).** Details: Part D, section D.5.

1. Proposals can carry a date and whether it is one-time or yearly. The user
   sees and edits it in Pending before saving.
2. **The bring-up setting is shown beside a proposed date,** so the user
   chooses how it may come up when they accept the date. An anniversary of a
   loss, for example, can be set to Only if I bring it up (D33).
3. **Not proposed:** Always, the bring-up setting, and fading stay the
   user's own choices (D33).
4. Relative dates ("tomorrow") are worked out from the day the message was
   sent, using the transcript's turn times, never the day the analysis runs.
5. Vague timing gets no date.
6. If the Memory Assistant is being changed for another reason at the same
   time, coordinate the two changes, because they touch the same code.

## Later features (approved designs; the owner chooses when)

- **The background reviewer** (D51): finds passed events, outdated facts,
  conflicts, and duplicates, with its own owner-approved prompt.
  Suggestions only, through Pending.
- **A vibe note per companion** (D36), and playful memory types (D35).
- **Recent-chat continuity** (D38): short summaries of the last few chats
  with a companion.
- **A companion journal** (D40), after the vibe note.
- **Model Rules adjustments,** per model, once the full system works
  (D.13).
- **Search tuning, revisited** after Phases 2–5, using the test screen and
  the test conversations.

---

# Part D: Design details (approved designs for later phases)

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
- **Always:** memories the user checks on the Always tab are sent with
  every message, so the AI always knows them. Examples: the user's name,
  pets, the people in their life, or a concern that matters right now.
- **Important dates** (owner's design, 2026-11-08): a birthday,
  anniversary, or event, kept in its own field. While the date is near, the
  memory is handed to the AI even when every memory limit is full:
  - before an interview: "Are you nervous about tomorrow?";
  - around a birthday, every year;
  - after an event: "How did Tuesday go?".

  Once it has come up in a chat, the AI keeps knowing it but is told it has
  already been discussed. A toggle in Memory Handling Preferences turns this
  on or off.
- **The bring-up setting:** each Always memory, and each memory with an
  important date, says whether the AI may raise it unprompted. A date alone
  is not permission: the user may want the AI to know an anniversary without
  mentioning it. Painful or private things can be set to Only if I
  bring it up. A painful topic is never volunteered just because it was
  discussed a lot.
- **Fading (optional):** a memory can be set to fade, so a concern that
  stops coming up retires on its own. Retired is the safety net, and
  anything there can be reinstated.
- **No notifications:** all of this happens inside chats. The app never
  notifies.

**Why anything is sent every time:** the AI has no memory of its own between
messages. If the app doesn't send something, it doesn't exist for the AI.

## D.2 What the user decides

For each memory:

| Choice | Meaning | Default |
|---|---|---|
| Always | A checkbox on the Always tab. Checked memories are sent with every message (D.12) | Not in the tab |
| Bring-up setting | Always memories and memories with an important date: whether the AI may raise it unprompted. The four options are listed below | When it fits |
| Important date (optional) | A birthday, anniversary, or event: yearly or one-time, with a range of days before it that counts as near. While near, the memory is handed to the AI past every limit, if it is checked on the Dates tab (D.5, D.11) | None. The range is prefilled, and a new date starts checked |
| "Past after" date (optional) | After this date the memory is treated as past and leaves Always (D49) | None |
| Fading (optional) | Retires after the fade time without being discussed: the app-wide time or custom days (D.4) | Off |

**Only the important-date field hands a memory over by date** (owner,
2026-11-09). A date written in a memory's text is just information. An
important date is one-time or every year, and either way the memory is sent
only within its range, without being checked on the Always tab.

**How the choices work together:**
- **What the AI receives:** search results, as now; checked Always
  memories, every message; and memories checked on the Dates tab whose date
  is near. Nothing else.
- **Whether the AI may raise a memory unprompted:** for an Always memory or
  a dated one, its bring-up setting. A searched memory is already about what
  the user said.
- **How long a memory stays in use:** the "past after" date and fading
  decide that. A memory that retires goes to Retired (D.11).
- **Internal, never a user choice:** the date windows and the last-discussed
  tracking. The Debug test screen shows them.
- **Existing memories** are not in Always and have no date and no fading.
  They behave exactly as after Phase 1.
- **Example:** a user saving an upcoming appointment adds the date. Nothing
  else.

**The bring-up setting** (D48, D53; owner ruling, 2026-11-08) is a dropdown
on Always memories and on memories with an important date:
- **Only if I bring it up:** the AI never raises it on its own;
- **When it fits:** the default. The AI uses its own judgment;
- **Ask me first:** the AI may offer, in a way that is easy to decline;
- **Custom:** a written condition, for example "when I express low
  self-esteem" for a memory about winning an award.

**Why only those:** they are the only memories the AI has when the user has
not brought the subject up.
- **A searched memory** reaches the AI because the user's message is about
  it, so it needs no setting. If the user wants a condition on one, they
  write it into the memory's own text, for example "Mention this only if
  the user brings up their mom."
- **A dated memory** is handed over while its date is near, so it is in
  the same position as an Always memory for that time. Having a date is not
  permission to raise it: for an anniversary of a loss, the user can choose
  Only if I bring it up, and the AI still knows the date.

**Custom is guidance, not search (D53).** An Always memory is already sent
with every message, so its condition only tells the AI when to raise it, and
the model uses its own judgment. Searching conditions by meaning was tested
on 2026-11-08 and dropped as unnecessary.

## D.3 Today's date

- **One source:** the phone's calendar date, in the phone's time zone, at the
  moment a message is sent. Date only, never the time of day.
- **The memory system always uses it,** for date windows and fading. That
  sends nothing anywhere.
- **Sharing it with the AI** is a user setting (D1), written so it can't be
  misread (D2), with roleplay handled per D3.
- **Relative timing** ("tomorrow", "in 5 days") for a due dated
  memory reaches the AI even with date sharing off (D4). The setting's
  wording must say so.
- **This is awareness inside a chat, not a notification.**

## D.4 Fading and retiring (owner decisions D34, D43, 2026-11-08)

**Decided:**
- memories can fade (D34). Fading is an optional choice on each memory, off
  unless the user turns it on (D.2), so someone who chats rarely does not
  come back to find their memories retired;
- when a fading memory has fully faded it is **retired**: it moves to the Retired area
  (D.11) and is out of use until the user reinstates it;
- a reinstated memory starts fresh.

**How fast, in plain terms (D45, D49):**
- a memory retires after a set time without being discussed;
- the time is the app-wide choice in Memory Handling Preferences (D.12), or
  a custom number of days on that memory;
- the exponential "strength" from earlier drafts is dropped, because "last
  discussed plus the fade time" is easier to understand and to show.

**What the user sees on a fading memory's card:** when it was last
discussed, and the date it will retire if it is not discussed again.
Memories that are not fading show nothing extra. Retired memories are listed
under Retired.

**What counts as "discussed":**
- the conversation stays on the topic, through carry-over (B.5), which is
  never refreshed by the AI's replies alone;
- the user edits the memory;
- being sent as Always or as a due date never counts.

**Note on the trade-off:** a retired memory is not found by search either,
even when the user raises the subject, until it is reinstated. That is the
owner's choice, and the Retired area makes it easy to undo.

## D.5 Dates

- **Stored in its own field** (owner's design, 2026-11-08): day and month,
  plus year for one-time dates (optional for yearly), and one-time or
  yearly. No time of day.
- **The Dates tab checkbox** (owner's design, 2026-11-09): each memory with
  an important date has a checkbox on the Dates tab (D.11). Only checked
  memories are sent automatically. Unchecking keeps the date.
- **The Memory Handling Preferences toggle** (owner's design): turns
  automatic sending by date on or off for every memory at once. Default on
  (presented 2026-11-08), so a date the user adds works without hunting for a
  setting. With it off, dates are still saved and shown, and those memories
  are found by search as usual.
- **Past every limit** (owner's design): a checked memory whose date is near
  is sent even when the Always and search limits are full. There is no cap,
  and the app never chooses among them: the checkboxes decide (owner,
  2026-11-09). The Debug test screen shows them.
- **"Past after" date (D49):** optional, on any memory. After it, the AI is
  told the memory is in the past, and it leaves Always.
- **Range** (owner's design, D10, D11): each important date has a field for
  how many days before it counts as near, prefilled so the user does not
  have to set it (presented 2026-11-08):
  - yearly dates: 7 days before, through the day;
  - one-time dates: 3 days before, through 2 days after, so the AI can ask
    how it went.
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
- **What the AI is told:** the memory text, the app-worked-out timing, the
  memory's bring-up setting, and guidance text the owner approves (D13).
- **Set by hand** in the editor, or **proposed by the Memory Assistant**
  (Phase 6). Relative dates count from the day the message was sent. Vague
  timing gets no date (D14).
- **Changed plans** go through the existing edit, supersede, and Possible
  Match paths.
- **Event state, rather than more fading rules** (research, F.2): a one-time
  event moves through three states:
  - **upcoming:** "Are you nervous about Tuesday?";
  - **happened, with the outcome unknown:** "How did Tuesday go?";
  - **settled:** the outcome is saved, and the memory stops being raised.

  The date moves an event from upcoming to happened. Settling it is the
  user's edit, or a Memory Assistant suggestion through Pending (D42).

## D.6 What is sent outside search (the standing briefing)

"The briefing" is the planning name for memories sent when the conversation
is not about them:

- **Always:** checked memories, sent every message as background.
- **Important dates:** memories checked on the Dates tab whose date is
  near, when the toggle is on.
  Each is marked "already discussed" once it has come up in the chat, and
  the AI is told not to raise it again (D.5).

**Permission rule (D30, D48):**
- whether the AI may raise an Always or dated memory unprompted follows its
  bring-up setting. A date alone is not permission;
- a subject is never volunteered just because it was discussed recently.

**Size and cost (D16, owner):** Always memories have their own limit, the
count set in Memory Handling Preferences (default 10, with the exception at
Save, D52). They do not count against search's Maximum Memories or Memory
Space, so they can never crowd out a relevant search result. Memories whose
date is near count against no limit at all (owner's design).

**Priority, in order:**
1. memories the user picked before sending, never displaced;
2. search results, within their own limits;
3. Always memories, within their own limit;
4. memories whose date is near, never held back by a limit.

If the whole request is still too large for the model, the trimming order is
D37.

As a rough guide, 20 short Always memories add about 600–1,200 tokens per
message. They go first in a fixed order so prompt caching (cheaper repeated
prompt starts, on providers that offer it) works.

**Other rules:**
- **Placement:** after fixed instructions, Model Rules, and card material;
  before searched memories.
- **No repeats:** search skips briefing items, and the briefing skips
  picked memories.
- **Scope:** the same eligibility check the current search uses. Roleplay
  per D17.
- **Off** whenever memory is off for the chat.
- **Switches** (D18): Always has none; checking memories is the opt-in.
  Important dates have the toggle in Memory Handling Preferences.
- **Visible:** the Debug test screen shows what the briefing sent and why.

## D.7 Memory screens

- **Editor:** the optional important date with its range, the optional
  "past after" date, and the fading choice. For an Always memory or one with
  an important date, the bring-up dropdown (Custom opens a write-in field). Always membership is set from the Always tab and the row
  menu.
- **Card:** small markers only: Always, an upcoming date, fading with its
  retire date, or faded. No scores or technical numbers.
- **Browser:** the view dropdown: Memories, Pending, Retired, Always, and
  Dates. Always and Dates have checkboxes (D.11).
- **Pending:** review labels (D50), and proposed dates with their bring-up
  setting (Phase 6), editable before saving.
- **Memory Handling Preferences:** D.12.
- **Advanced numbers** stay on the Debug test screen, not in ordinary
  settings.
- **Look:** `ui-style-guide.md`. Wording is shown to the owner before it is
  built.

## D.9 Inside jokes and "vibe" (owner request, 2026-11-08; proposal)

**Owner request, 2026-11-09:** the vibe should be built soon, ahead of the
later phases. The owner also asked whether vibes need categories, whether
Type and Scope need revising, and whether users should make their own.

**Found in the code (2026-11-09):**
- **Scope** decides which chats may see a memory. It is a privacy wall
  enforced in the database query: Real life memories never reach roleplay
  chats, and a companion's memories reach only that companion. There are
  seven fixed scopes, and each has its own rule.
- **Type** says what kind of memory it is. The data is built for
  user-owned types: adding, renaming, and deleting exist behind the scenes,
  seeded with five starters (Fact, Preference, Event, Status, Instruction).
  But no screen offers them. The memory editor lists only the five
  starters, so a user cannot create an "Inside Joke" type today.

**Decided (owner, 2026-11-10; D56, D57):**
- **Users do not add types.** The built-in list stays, with Inside Joke
  added to it.
- **No user-made scopes.** A new scope word would have no rule for which
  chats may see it.
- **Inside Joke is an ordinary Type** (owner, revised 2026-11-10). Scope
  decides where it is seen, as for every memory. There is no extra link
  requirement: the user puts it wherever they want.
- **The AI is told when a memory is an Inside Joke** (D35): the memory line
  starts with "Inside joke:" (approved 2026-11-10).
- **The Memory Assistant may suggest Inside Joke,** with a scope, as it
  already suggests a scope for every memory. Suggestions go through Pending.
- **The vibe is per memory, not per companion** (owner, 2026-11-10): a note
  the user types on a memory, placed under Importance in the memory editor.
  Its label, helper text, what the AI receives, and the related details are
  shown to the owner before it is built (D58).

**Status (2026-11-10):** Inside Joke is being built on the work branch:
the Type, the database step that adds it for existing users, keeping it when
an older backup is restored, and the "Inside joke:" label. Still waiting for
the owner: its definition in the editor's Type Definitions pop-up.

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
  - given the built-in Inside Joke type, with whatever scope the user
    chooses (D56);
  - often on the Always tab, with When it fits.

  Keyword search already finds the joke's phrase when the user uses it.
- **The AI needs to know a memory is a joke.** Today only the memory text is
  sent, not its type. For memories of a type the user marks as playful, the
  type name would be sent with the text, so the AI knows the memory is
  meant to be used lightly (D35).
- **Superseded (2026-11-10): the vibe is per memory (D58).** Earlier
  proposal: **"Vibe" is one short, user-editable note per companion.** It describes
  the tone of the relationship, its running themes, and how the companion
  should talk, and it is always sent with that companion's chats. Companion
  records already have an unused relationship-notes field for this (D36).
  Checked 2026-11-09: it is saved and backed up, but no screen shows it and
  it is never sent to the AI.
  The Memory Assistant could later suggest updates to it through Pending,
  never silently.
- **The trauma rule still holds:** a painful subject never becomes a joke or
  a vibe line unless the user writes it that way.

## D.10 How the AI uses what it is given (research, F.2)

The most useful published guidance is about behavior, not search: people
value natural continuity and dislike announcements of remembering, stale
details, and badly timed or overly broad references. Published research
also found that filtering memories after retrieval does not reliably stop
irrelevant references, repetition, or the AI agreeing too readily with the
user.

**Proposal (D41):** the instructions sent with memories teach four ways to
use a memory:

1. **ignore it** when it does not help right now;
2. **use it silently** to shape the reply, without narrating the past;
3. **mention it** only when it is the answer, or continuity the user would
   expect;
4. **ask first**, with one easy-to-decline question, when it might help but
   permission is unclear.

It should also never announce "I remember…", and never raise a sensitive
memory the user did not bring up.

- **Could help soon:** this instruction applies to ordinary searched
  memories too, so it could improve current chats before any later phase.
- **The exact text is model-facing behavior** and needs the owner's approval
  before it is written.
- **Testing:** check the final replies for irrelevant references,
  repetition, and over-agreement, not only which memories were retrieved.
  Judging replies with an AI costs tokens, so it is a small, occasional set.

## D.11 Retired area and the browser's views (owner request, 2026-11-08)

**Found in the code (corrected 2026-11-09):** archived memories exist, and
they are listed in the Memories view mixed in with memories in use, marked
only by a small "Archived" badge. The Status filter was removed from the
filter panel in July, so there is no way to see only archived memories. An
earlier version of this plan wrongly said a Status filter showed them.

**Owner's design:**
- **A dropdown for the view.** The browser's "Memories | Pending" words
  become one dropdown with Memories, Pending, Retired, and Always (D.12,
  D46), and Dates (D54). It uses the canonical dropdown style.
- **Memories shows memories in use.** Once Retired exists, archived
  memories are listed there instead of being mixed into Memories.
- **A Dates tab** (owner's design, 2026-11-09, D54): one tab for memories
  that are sent automatically because of their important date, so the user
  can review what will be sent before it is sent.
  - **Checkboxes,** as on the Always tab. A checked memory is sent
    automatically while its date is near. Unchecking stops that; the date
    stays saved and shown, and the memory is still found by search.
  - **The user decides, never the system.** There is no cap, and the app
    never picks which dated memories are sent. If 20 are checked and near,
    all 20 are sent.
  - **Presented 2026-11-09:**
    - the tab lists every memory with an important date, checked or not, so
      an unchecked one can be checked again;
    - memories are listed in the order their dates come up next;
    - each shows its date, whether it is one-time or every year, and its
      range;
    - a marker shows the ones being sent to the AI right now;
    - a newly added important date starts checked.

  It is a tab rather than a filter because the filter panel can only narrow
  the list and sort it newest or oldest. A tab can list memories in
  upcoming-date order and hold the checkboxes.
- **Retired** lists everything taken out of use, so it can be seen
  immediately:
  - memories the user archived;
  - memories retired by fading;
  - deleted memories, if the setting below is on.

  Each can be reinstated, and permanently deleted from there.
- **Deleted memories go to Retired first:** a setting in Memory Handling
  Preferences (D44). With it on, Delete moves a memory to Retired. With it
  off, Delete stays permanent, as today.
- **Nothing in Retired is sent to the AI.**

## D.12 Memory Handling Preferences and the Always tab (owner request, 2026-11-08)

**A new settings screen, "Memory Handling Preferences",** separate from the
current memory settings screen, which is crowded. It holds:

- **the most always-sent memories allowed per message.** The default is 10,
  and more is allowed;
- **the fade time** for memories set to fade (D45);
- **whether memories with important dates are handed to the AI** while the
  date is near (D18, D.5);
- **whether deleting sends a memory to Retired first** (D44).

**The Always tab** (owner's design, 2026-11-08; D46, D52):
- **Membership and checking are separate.**
  - Memories in the Always tab each have a checkbox.
  - Only checked memories are sent with every message.
  - Unchecking keeps a memory in the tab without sending it, so the user
    can swap.
- **"Checked only" view:** a way to show just the checked memories, so the
  user can find and uncheck them quickly.
- **A simple count at the bottom,** for example "12 checked · your limit is
  10", so the user always knows where they are.
- **Checking is never blocked.** The user can always check one more.
  Blocking it would make them choose on the spot between two things they
  want remembered.
- **The limit is checked when the user saves.** If more memories are checked
  than the limit, the app shows all the checked memories and offers two
  choices:
  - uncheck some, right there in the list;
  - keep them all: an exception above the normal limit, which does not
    change the preference itself.

  After keeping an exception, the user is asked again only if they check
  even more.
- **The row menu** has an action that makes it a normal memory again: out of
  the tab, still remembered, and found by search when relevant.
- **Always memories have their own cap.** They do not count against
  search's limits (D16).
- **The preference's subtext** explains, plainly, why sending many memories
  every message has a cost:
  - more tokens per message;
  - less room for the conversation;
  - the AI's attention spread thin.

  Any number is allowed.

**Auto mode, where the embedding model picks which always-sent memories to
include (owner question, D47):**
- **Recommendation: no auto mode, checkboxes only.** Ordinary search already
  is that auto mode. Every message, any memory that matches the
  conversation, checked or not, is found and sent.
- So the checkboxes only need to cover what should be present even when the
  conversation is not about it, and that is better decided by the user than
  guessed. It also keeps the behavior predictable and the screen simple.
- Going over the limit is handled at Save, as described above. The app never
  silently chooses.

## D.13 Direction for the later phases (owner ruling, 2026-11-08)

**Design principle (owner):** build the memory system the owner wants,
designed for capable models. Do not simplify it around a weaker model's
habits.

- Per-model adjustments go through the existing **Model Rules**, which are
  sent only for a chosen model and removed when the model is gone. For a
  model that recaps history or cannot handle many memories, a rule changes
  its behavior behind the scenes. The system itself stays the same for
  everyone else.
- If a model needs fewer memories, not just different instructions, Model
  Rules may later gain per-model memory limits. That is an extension to
  propose when it is needed.

**What the user sets on each memory:** D.2 (Always, important dates, the
bring-up setting for Always and dated memories, and fading).

**All the time:** the memory-use instruction (D.10, D41) teaches the AI to
use what it is given naturally.

**Keeping track without extra work (D50):**
- one review place, Pending, with labelled reasons;
- nothing requires action; doing nothing leads to the sensible default;
- renewing is one tap.

**Order, each a separate pull request, foundations first:**
1. Carry-over, both send paths, and the memory-use instruction (D41). These
   are the next pull request.
2. Data control (Phase 2). This changes no AI behavior.
3. The Always tab and the bring-up setting (Phase 3).
4. Today's date and dates on memories (Phase 4).
5. Fading (Phase 5).
6. Memory Assistant date proposals (Phase 6), then the later features,
   including the background reviewer (D51).

**After these are in place,** revisit how search picks memories, using the
test screen and the test conversations. Model Rules adjustments come last,
per model, once the full system works.

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

**How decisions are recorded (owner ruling, 2026-11-08):**
- A recommendation the owner has seen and not objected to counts as
  approved. Those rows say **Approved**.
- Rows marked **Presented 2026-11-08** were first shown to the owner on that
  date. They count as approved unless the owner objects.
- Exact wording and text sent to the AI count as approved only once the
  owner has seen the actual text.
- Approving a design does not start the work. The owner decides what is
  built and when.

| # | Question | Decision |
|---|---|---|
| D0 | Approve the recommended search design in Part B as the plan for Phase 1? | **Decided, 2026-11-08:** the direction is approved; details are left to engineering judgment (B.5) |
| D1 | Is date sharing off or on by default? | **Approved:** off; the user turns it on |
| D2 | What date format goes to the AI? | **Approved:** weekday, day, month name, year, which can't be misread |
| D3 | Does date sharing apply in roleplay chats? | **Approved:** no by default; the story may have its own time |
| D4 | With date sharing off, do dated memories carry relative timing ("tomorrow")? | **Approved:** yes; a reminder is useless without timing |
| D5 | Is the date-sharing setting app-wide, per chat, or both? | **Approved:** app-wide first; per chat only if wanted later |
| D6 | Are manually created memories lasting or fading by default? | **Superseded:** fading is an optional choice on each memory, off by default (D34) |
| D7 | How fast does fading go? | **Superseded** by D45 and D49 |
| D8 | Does editing a fading memory count as it being discussed? | **Approved:** yes; editing is deliberate attention |
| D9 | Does fading affect search ranking? | **Approved:** no. A fading memory stays fully searchable. Once retired, it is out of use (D43) |
| D10 | How long are the date windows? | **Decided (owner), 2026-11-08:** each important date has its own range field. **Presented 2026-11-08:** prefilled with 7 days before for yearly dates (through the day), and 3 days before through 2 days after for one-time dates |
| D11 | Can windows be changed per memory? | **Decided (owner), 2026-11-08:** yes, through the range field (D10) |
| D12 | How is a 29 February date handled in other years? | **Approved:** 28 February |
| D13 | What does the guidance to the AI about dated memories say? | **Open until drafted:** the owner sees the exact text before it is built |
| D14 | Does vague timing get a date? | **Approved:** no; the user can add one in Pending |
| D15 | Should memories be sent outside search (Always and due dates)? | **Decided, 2026-11-08:** yes. The owner designed the Always tab and dates (D46, D49, D52) |
| D16 | How is the size of what is sent outside search limited? | **Decided (owner), 2026-11-08:** Always memories have their own limit, set in Memory Handling Preferences. It does not count against search's Maximum Memories or Memory Space. Memories whose important date is near count against no limit |
| D17 | Are Always and dated memories sent in roleplay chats? | **Approved:** not by default |
| D18 | Is there an on/off switch for sending memories outside search? | **Always:** no switch; checking memories is the opt-in (presented 2026-11-08). **Important dates (owner's design):** a toggle in Memory Handling Preferences for all of them (2026-11-08), and a checkbox for each on the Dates tab (2026-11-09). **Presented:** the toggle defaults to on, and a new date starts checked |
| D19 | What does a memory card show about fading? | **Approved:** no numbers. A fading memory shows when it was last discussed and the date it will retire (D.4) |
| D20 | Which browser filters are added? | **Superseded** by the view dropdown (D.11), the card markers (D.7), and the Dates tab (D54) |
| D21 | Should a local re-ranker be added? | **Approved:** no; tested and it did not work (A.10). Reconsider only if a suitable small model appears |
| D22 | Does date sharing ship on its own, before the rest? | **Approved:** it can, if wanted sooner; it doesn't depend on the rest |
| D23 | When is search fixed? | **Decided, 2026-11-08: first** |
| D24 | Where does the search test screen open from? | **Decided, 2026-11-08:** its own screen, from Advanced Memory Settings in the Debug section, with adjustable numbers. Built in #180 |
| D25 | Should the ordinary memory settings also have a Strict / Balanced / Broad match choice? | **Approved:** no; the test screen's tuned numbers are the setting |
| D26 | Should roleplay card entries also lose their 10-message cooldown? | **Decided, 2026-11-08: yes.** Card entries ride the same rebuilt message, so the cooldown hid them from the AI. Lorebooks were checked and have no cooldown |
| D27 | Which memory assembly log lines change when the cooldown and old search are removed? | **Decided, 2026-11-08:** the summary line, the memory cooldown line, the project wording, and the chat-search missing-vector line. The card-cooldown log line stays in the code (it no longer fires) until its removal is approved |
| D28 | Should the Memory Assistant always add topic tags? | **Decided, 2026-11-08:** not mandatory now. Test independently generated tags first (step 1.15) |
| D29 | Should the int8 model become the default? | **Decided, 2026-11-08 (revised):** no change to the default. The user already picks q4 or int8 in Advanced Memory Settings |
| D30 | Which memories may the AI raise unprompted? | **Decided (owner), 2026-11-08:** an Always memory, or a memory whose date is near, as its bring-up setting allows (D48). A date alone is not permission. A searched memory is already about what the user said. A topic is never volunteered just because it was discussed a lot |
| D31 | What does the user decide per memory? | **Approved:** Always, an optional important date with its range, an optional "past after" date, optional fading, and the bring-up setting for Always and dated memories (D.2). Last-discussed tracking stays internal |
| D32 | How is repetition of a memory whose date is near controlled? | **Decided (corrected), 2026-11-08:** never by withholding it. It is sent every message while near, marked "already discussed" once it comes up, and the AI is told not to raise it again |
| D33 | What does the Memory Assistant propose beyond the memory text? | **Approved:** dates only, at first. Always, the bring-up setting, and fading stay the user's choices. **Presented 2026-11-08:** Pending shows the bring-up setting beside a proposed date |
| D34 | Should memories fade? | **Decided, 2026-11-08: yes,** with Memory Browser markers for fading, not fading, and faded, and with faded memories reinstatable from Retired. **Approved:** fading is an optional choice on each memory, off unless the user turns it on (D.2) |
| D35 | Should the type name be sent with memories of a "playful" type, such as Inside Joke? | **Approved:** yes, for types the user marks playful, so the AI knows to use them lightly |
| D36 | Should each companion have a short, editable "vibe" note that is always sent? | **Superseded (owner), 2026-11-10:** the vibe is a note on each memory, not on each companion (D58) |
| D37 | If a whole request is too large for the model, what is trimmed first? | **Presented 2026-11-08 (revised, because the owner treats important dates as important):** the lowest-ranked search results first, then Always memories, then memories whose date is near. Picked memories are never trimmed. This is only a last resort for a request the model cannot accept at all. Otherwise the app never chooses which Always or dated memories are sent; the user's checkboxes do (owner, 2026-11-09) |
| D38 | Should a companion know what happened in your last few chats with it, through short summaries built from the existing per-chat conversation summaries? | **Approved for design:** it is the main thing ChatGPT does that this app lacks for continuity ("how did the dentist go yesterday?"). It is background knowledge, and the bring-up rules apply |
| D39 | Should the AI know how long it has been since the last chat with this companion (in days, not times)? | **Approved:** yes, as part of Phase 4, under the date-sharing setting |
| D40 | Should a companion keep its own short reflections or journal about the relationship? | **Approved:** later and optional, after the vibe note, and visible and editable |
| D41 | Should the instructions sent with memories teach the four ways of using them (ignore, use silently, mention, ask first)? | **Approved:** yes, in the next pull request. **Owner ruling, 2026-11-08:** no crisis or safety wording; the model uses its own judgment. **Open until drafted:** the owner sees the exact text before it is built |
| D42 | Should one-time events track upcoming, happened, and settled? | **Approved:** yes, instead of more fading rules. Settling is a user edit or a Memory Assistant suggestion through Pending |
| D43 | When a memory fades fully, what happens? | **Decided, 2026-11-08:** it is retired: it moves to Retired, out of use until the user reinstates it |
| D44 | Should deleting a memory move it to Retired first, with Delete Permanently from there? What is the default? | **Decided (owner):** a setting in Memory Handling Preferences. **Approved:** default on, so a mistaken delete can be undone |
| D45 | How fast do memories fade? | **Approved:** one app-wide fade time in Memory Handling Preferences: 2 weeks, 1 month, 3 months, or 6 months. Default 1 month |
| D46 | What is the always-sent tab called, and what does it mean? | **Decided, 2026-11-08:** the dropdown entry is "Always". Checked Always memories are sent with every message |
| D47 | Should an auto mode let the embedding model pick which Always memories to include? | **Approved:** no. Ordinary search already does this every message; checkboxes only |
| D48 | How does the user say how a memory may be brought up? | **Decided (owner), 2026-11-08:** a dropdown on Always memories: Only if I bring it up, When it fits (the default), Ask me first, or Custom, a written condition. For a searched memory, a condition goes into the memory's own text. **Presented 2026-11-08:** the same dropdown on memories with an important date, because a date alone is not permission. Weaker models are handled through Model Rules (owner ruling) |
| D49 | Fade timing, and an optional "past after" date per memory? | **Decided (owner):** custom fade timing, and a per-memory date after which the memory is a past event. **Approved:** the preset times plus a custom number of days, and an optional "past after" date on any memory |
| D50 | How are expiring and passed memories reviewed without overwhelming the user? | **Approved:** one review place, Pending, with a label for each reason ("New suggestion", "Retiring soon", "Event passed"). Nothing needs the user's action: if they do nothing, a fading memory retires and a passed event is kept as past. One tap renews |
| D51 | Should a background reviewer keep memories current (find passed events, outdated facts, conflicts, duplicates)? | **Approved:** later, as its own feature, with its own owner-approved prompt and choices of what it checks. Proposals only, through Pending, never silent changes. It costs tokens, so it runs when the user starts it or on a schedule they choose |
| D52 | How does the Always tab handle more checked memories than the limit? | **Decided (owner's design), 2026-11-08:** checking is never blocked. At Save, the app shows all checked memories and offers to uncheck some or keep them all as an exception, without changing the preference. Includes a "checked only" view and a running count |
| D54 | How does the user see and control memories that are sent automatically because of their date? | **Decided (owner), 2026-11-09:** a Dates tab in the browser's view dropdown, with a checkbox on each memory, so the user reviews what will be sent and unchecks what they don't want sent. No cap, and the app never picks among them. **Presented 2026-11-09:** it lists every dated memory, checked or not, in upcoming order, with a marker on the ones being sent now; a new date starts checked |
| D55 | Should the "past after" date and a one-time important date be one field? | **Open (raised 2026-11-09), to settle before Phase 4.** Both make a memory past after a date, and two date fields may confuse. Recommended: one date field. A one-time date always makes the memory past afterwards, and its Dates tab checkbox decides whether it is also sent while near |
| D56 | Should vibes have their own categories, or should inside jokes be a memory Type? | **Decided (owner), 2026-11-10:** Inside Joke is a built-in Type and works like any other Type: scope decides where it is seen, with no extra link requirement. Users do not add types. The AI receives "Inside joke:" before the memory text. The Memory Assistant may suggest it, with a scope |
| D58 | What does the per-memory vibe look like? | **Decided (owner), 2026-11-10:** an optional note on each memory, typed by the user, under Importance in the memory editor. Label "Vibe", hint "How the AI should treat this memory, like playful or gentle." The AI receives it on its own line under the memory. Empty by default, shown on the memory card, never read by search, and not suggested by the Memory Assistant at first. **Open:** whether a Memory Assistant update that replaces a memory carries the old vibe over |
| D57 | Should users make their own scopes? | **Decided (owner), 2026-11-10:** no. Scope is the privacy rule for which chats may see a memory, and a user-made scope would have no rule |
| D53 | Should a Custom condition also be searched by meaning? | **Decided (owner), 2026-11-08:** no. An Always memory is already sent with every message, so the condition only guides when the AI raises it, and the model uses its own judgment. Condition search was tested and dropped |

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

## F.2 Frontier-lab and academic research (supplied by the owner's second reviewer, verified 2026-11-08)

- **Anthropic, "The components of agent memory"** (6 November 2026):
  - **Layers:** a searchable transcript log, memory notes, organized
    knowledge such as wikis, and a small injected context loaded every
    session.
  - **"Dreaming":** an offline process that checks the notes against the
    original transcripts and corrects them.
  - **The top, always-present layer has to stay small.** In one test, 10
    focused files did far better than 413 stale ones, and bigger memory was
    not automatically better.
  - Source: [anthropic.com/research/components-of-agent-memory](https://www.anthropic.com/research/components-of-agent-memory).
  - **Lesson:** keep Always small, and let a Memory Assistant pass
    correct and update memories through Pending.
- **OpenAI, "Dreaming"** (4 June 2026, confirmed through news coverage
  because OpenAI's page could not be read directly):
  - a background process synthesizes past conversations and keeps memory
    current as circumstances change, for example a planned trip becoming a
    completed one;
  - OpenAI notes its memory summary page may not show everything it
    remembers.
  - Sources: [PCWorld](https://www.pcworld.com/article/3158111/chatgpt-new-dreaming-feature-makes-it-way-better-at-remembering-you.html);
    [Tom's Guide](https://www.tomsguide.com/ai/chatgpts-new-memory-builds-a-profile-of-you-on-its-own-and-openai-admits-you-cant-see-all-of-it).
  - **Lessons:**
    - track an event's state (D.5) rather than adding more fading rules;
    - unlike ChatGPT, keep everything the app remembers visible.
- **"Before Bringing It Up: When and How AI Companions Should Use Memory"**
  (University of Toronto, arXiv 2610.09470, October 2026):
  - interviews with 14 companion users: they valued continuity but objected
    to stale, mistimed, or overly broad uses;
  - it proposes four modes for using a memory: ignore, use silently,
    mention explicitly, or ask first;
  - **caveat:** results were judged by AI models, not people, and were
    inconclusive for GPT and Claude.
  - Source: [arXiv 2610.09470](https://arxiv.org/html/2610.09470v2).
  - **Lesson:** D.10.
- **"OP-Bench: Benchmarking Over-Personalization"** (EMNLP 2026):
  - three failures: irrelevant references, repetition, and sycophancy
    (agreeing too readily);
  - agents over-attend to memories even when they are not needed;
  - the post-processing fixes tested gave only limited improvement.
  - Source: [ACL Anthology 2026.emnlp-main.1168](https://aclanthology.org/2026.emnlp-main.1168/).
  - **Lesson:** good retrieval is not enough. Instruct how to use memories
    (D.10), and test the final replies.

**Design goal these support:** the AI carries shared experiences forward,
recognizes when circumstances change, and uses what it knows naturally,
without announcing what it remembers or dragging in unrelated memories.

## F.1 Companion apps and open-source tools people like (2026-11-08)

Earlier research is saved in the repository:

- `conversation-summary-plan.md` section 3 reviewed SillyTavern Summarize
  and Qvink MessageSummarize for conversation summaries;
- the legacy counterplan's section 3 reviewed Mem0, LangMem, Letta, and Zep.

This section adds what makes companions feel alive. Most details come from
third-party guides and user reports, not official documentation, so they are
patterns to learn from, not exact specifications.

- **SillyTavern** (open source):
  - "World Info" is keyword-triggered entries, which this app's lorebook
    already does.
  - The "Author's Note" is a short standing note inserted near the newest
    message. The docs say the closer it is to the bottom of the prompt, the
    more effect it has on the next reply.
  - Sources: [Author's Note](https://docs.sillytavern.app/usage/core-concepts/authors-note/);
    [World Info](https://docs.sillytavern.app/usage/core-concepts/worldinfo/).
- **Kindroid:**
  - Always-present memory is the backstory, "key memories" (for temporary
    life facts), example messages, and a response directive.
  - "Journal entries" are keyword-triggered. They fire only when the user,
    not the AI, mentions a key phrase, with a few per message at most.
  - Sources: [Types of memory within Kindroid](https://medium.com/@adlerai/types-of-memory-within-kindroid-5659410c07ca);
    [Journal entries on Kindroid](https://medium.com/@adlerai/journal-entries-on-kindroid-fce640dcceea).
- **Nomi:** short-, medium-, and long-term layers.
  - An "identity core" holds stable facts and personality.
  - "Shared Notes" are a visible, editable list that both the user and the
    companion use, for things like backstory.
  - A "Mind Map" organizes people, places, topics, and goals.
  - Users report that summarized information sometimes picks up mistakes.
  - Sources: [Nomi app listing](https://apps.apple.com/app/id6450270929);
    [Kindroid vs Nomi vs Replika memory comparison](https://aicompanionpick.com/ai-companion-memory-kindroid-nomi-replika-long-term-2026).
- **Replika:** layered memory.
  - A "Memory" tab of facts about the user, their pets, and people in their
    life, which the user can review.
  - A diary of short reflective entries.
  - A deeper layer drawn from conversation patterns.
  - Sources: [Replika help: how memory works](https://help.replika.com/hc/en-us/articles/37208679176077-How-does-Replika-s-memory-work).
- **Honcho** (open source, Plastic Labs): builds a "representation" of each
  person from conversations. It is an inferred portrait of what they believe
  and need, kept alongside a short list of concrete facts.
  - Source: [plastic-labs/honcho](https://github.com/plastic-labs/honcho).

**Patterns that recur, and where this app stands:**

| Pattern | Seen in | This app |
|---|---|---|
| Keyword-triggered entries | SillyTavern, Kindroid | Built: lorebook |
| Memories found by meaning | Letta, Mem0, ChatGPT | Built and repaired in #180 |
| Always-present facts, with their own space | ChatGPT, Kindroid, Nomi, Letta | Planned: Always, with its own limit (D16) |
| A relationship or personality note the user can edit | Kindroid directive, Nomi Shared Notes, Claude summary | Proposed: vibe note (D36) |
| Continuity across recent chats | ChatGPT recent-conversation summaries | Gap: proposed as D38 |
| Awareness of time and time since the last chat | Companion apps generally | Planned as date awareness (Phase 4); time since last chat is D39 |
| A standing note placed near the newest message | SillyTavern Author's Note | Memory already goes just before the newest message; the vibe note could join it (D36) |
| Triggers only from the user's own words | Kindroid journals | Matches the no-self-refresh rule (B.5) |
| The companion's own reflections or diary | Replika diary, Generative Agents reflections | Gap: optional idea, D40 |
| Everything visible and editable | Replika, Nomi, Claude, ChatGPT | Built: Memory Browser, Pending, test screen |

**Common complaints worth designing against:**
- Forgetting when the conversation outgrows the context: handled by
  compaction and carry-over.
- Summaries drifting into mistakes: anything generated goes through Pending
  and stays editable.
- Memory silently breaking after updates: the test screen and assembly
  log make it visible.

---

# Part G: The conversation index, recall, and carry-over

Moved on 2026-11-10 to its own document at the top level of the repository:
`conversation_recall_plan.md`. Section numbers there keep the "G." prefix,
so "G.9" in this document means section G.9 of that one.
