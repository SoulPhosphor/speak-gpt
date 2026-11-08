# Dynamic Memory Plan: Dates, Fading, and the Standing Briefing

**Written 2026-11-08 at the owner's request.**

## Status

- **The owner decides what is built and when.** Build only the part of this
  plan the owner tells you to build. No roadmap file overrides that.
- **Search repair (section 10) comes first** (owner decision D23,
  2026-11-08). It is written as concrete changes to the current code.
- **Everything else is proposed, not approved.** Every choice in section 15
  needs the owner's decision first. Recommendations are proposals only.
- **It does not change `ACTIVE_ASSOCIATIVE_MEMORY_REPAIR.md` (Revision 26).**
  The parts of this plan that change the Memory Assistant must be built on
  top of that repair, not alongside it, because both change the same
  Memory Assistant code.
- **Names in this plan are planning names, not on-screen wording.** Terms
  such as "lasting", "fading", "dated", "strength" and "briefing" identify
  ideas in this document. Every label, explanation, and message the user
  sees still needs separate owner approval.
- **Older always-on designs are retired and must not be revived from old
  files.** `legacy/` describes an "owner portrait", "standing packet",
  "standing context" and a per-memory "always load" flag. Those designs are
  dead. This plan replaces them with a different, user-controlled design.
  The old retrieval specifications (`rag_engine_work_order.md` and
  `enforcer_librarian_spec.md`) were deleted on 2026-11-08 so they cannot
  compete with this plan. Git history still holds them.

---

## 1. What the user should experience

Today the AI hears about a memory only when the current message is about the
same thing. The AI never brings up something on its own, so it never feels
like it knows the user.

This plan adds three behaviors that work the way people remember things:

- **Some facts always matter.** The user's name, pets, the people in their
  life. The user chooses which of these the AI always hears.
- **Some things matter for a while.** "This problem at work is weighing on
  me." While the user keeps bringing it up, it stays strong and the AI keeps
  it in mind. Once the user stops mentioning it, it gradually stops being
  volunteered. It is never deleted. It can still be found when the topic
  comes back.
- **Some things matter around a date.**
  - A one-time event, such as an interview. In the days before, the AI knows
    it is coming ("Are you nervous about tomorrow?"). Shortly after, it can
    ask how it went. Then it stops being brought up and becomes an ordinary
    memory.
  - A yearly date, such as a birthday. It comes up as the date approaches,
    every year, and never expires.

Separately, the user can choose to tell the AI **today's date** (never the
time of day) in every chat. The memory system always knows the date
internally so it can work out windows and fading, whether or not the date is
shared with the AI.

## 2. Why anything has to be sent with every message

The AI has no memory of its own between messages. Every message starts
blank. If the app doesn't send something, it doesn't exist for the AI.

Search sends only memories that match the words in the current message. So
search can never bring up the interview unprompted, because the user didn't
mention the interview. The only way the AI can say "your birthday is coming
up" is for the app to send that fact **before** the user raises it.

That is the job of the **standing briefing** (section 8). It is a small
block that the app rebuilds fresh for every message. It is not a fixed list
of everything. It holds only:

1. the facts the user marked as always important;
2. the things that are currently strong (talked about recently);
3. dated things inside their window.

## 3. What exists today

Verified in the code on `beta/new-client`, 2026-11-08.

- **Search (the Librarian, `librarian/Librarian.kt`).** An on-device
  embedding model (EmbeddingGemma) turns each memory and the current
  message into vectors. The closest matches above a fixed similarity of
  0.30 are ranked. There are small bonuses for scope, the selected project,
  matching tags, importance, and recency. The search costs no tokens.
- **Keyword backup.** Used only when the embedding model is missing, or when
  **any** memory lacks a current vector. In that case the whole turn uses
  plain word-overlap matching. Keyword and meaning search never run
  together.
- **The search query** is the user's message joined to a few recent turns,
  turned into one vector (`Enforcer.kt`).
- **Recency** is based on when a memory was created or last edited, not when
  it was last talked about.
- **Importance** is stored on a signed scale from -2 to +3
  (`ImportanceRanking.kt`). +3 gets a guaranteed slot, but only after the
  memory has already passed the relevance check. A +3 memory unrelated to
  the current message is still left out.
- **The freshness cooldown** (`injection_cooldowns` table) stops the same
  memory from being re-sent while it is still fresh in a chat.
- **Dates on memories:** none. A date written in a memory is plain text and
  nothing treats it as an event.
- **Mention tracking:** none. Nothing records when a memory was last talked
  about.
- **Today's date:** the app does not give the AI the current date.
- **Retired leftovers:** the `memories.always_load` column and the
  `owner_profile` table (portrait, standing context) still exist in the
  database. They are only used for import and export, and nothing in them
  is sent to the AI. This plan does not reuse them (section 11).
- **Approved retrieval controls not yet built:** `Memory Match Strictness`
  and the other controls in `memory_retrieval_and_analysis_ui_copy.md` are
  approved, but the code still uses the single fixed 0.30 cut-off.

## 4. The three kinds of memory

Each memory gets two independent properties.

### 4.1 How it ages

- **Lasting.** Its weight never changes on its own. This matches how every
  memory behaves today.
- **Fading.** It has a strength that rises when the user talks about it and
  falls over time when they don't (section 6).

### 4.2 Whether it has a date

- **No date.** This matches every memory today.
- **One-time date.** An event on one day: an interview, an appointment, a
  move.
- **Yearly date.** A date that comes back every year: a birthday or an
  anniversary. The year is optional, so a birthday can be saved without a
  birth year.

### 4.3 How the combinations play out

| Example | Ages | Date | What the AI experiences |
|---|---|---|---|
| The user's name | Lasting | None | Always in the briefing, if the user marked it |
| A worry the user keeps raising | Fading | None | In the briefing while it is strong, then only found by search |
| An interview | Lasting or fading | One-time | In the briefing during its window, then only found by search |
| A birthday | Lasting | Yearly | In the briefing during its window, every year |

### 4.4 Rules that hold for every kind

- **Nothing is deleted, archived, or changed automatically.** Fading and
  date windows change only what the AI is told without being asked. They
  never change what is stored.
- **Search still finds everything.** A faded memory or a past event can
  still be found when the topic comes up again, exactly as today.
- **Existing memories keep working exactly as now.** After the update, every
  existing memory is lasting, has no date, and is not in the briefing. What
  the AI receives does not change until the user changes something.

## 5. Today's date

### 5.1 One source of today's date

The app gets today's date from one place: the phone's own calendar date, in
the phone's time zone, at the moment a message is sent. **Only the date is
used, never the time of day.**

### 5.2 The memory system always uses it

Fading, date windows, and "next birthday" calculations all run on the
phone. They use today's date whether or not the user shares the date with
the AI. Using the date this way sends nothing anywhere.

### 5.3 Optionally giving the date to the AI

This is a user setting that adds today's date to what the AI receives.

- It is off unless the user turns it on (decision D1).
- The date is written so it can't be misread (weekday, day, month name,
  year). Numeric forms such as 03/04 mean different days in different
  countries (decision D2).
- Roleplay may need different handling, because the real date can clash
  with the story's own time (decision D3).

### 5.4 Reminders when the date is not shared

When the user hasn't turned on date sharing, a dated reminder in the
briefing can still give **relative** timing that the app works out, such as
"tomorrow" or "in 5 days". It does not need the calendar date. Without any
timing at all, a reminder is useless (decision D4).

## 6. Fading: how the math works

### 6.1 In plain terms

Each fading memory has a **strength** between empty and full.

- When the user talks about it, strength goes up.
- Each day without a mention, it loses a fixed share of what is left. After
  one "half-life" it is at half, after two at a quarter, and so on. It
  shrinks smoothly. It does not drop off a cliff.
- When strength is above a cut-off, the memory is "current" and goes into
  the briefing. Below the cut-off, it behaves like an ordinary memory.

### 6.2 Technical shape

Each memory stores two values: the strength at its last update, and the date
of that update.

- **Current strength** = stored strength × 0.5 ^ (days since update ÷
  half-life).
- **On a mention:** current strength + boost, capped at full. Then save the
  new strength and today's date.

Nothing runs in the background, so there is no battery or timer cost.
Strength is worked out only when it is needed, from those two numbers.

### 6.3 What counts as a mention

- **Counts:** the user's own message strongly matches the memory. The match
  bar is stricter than the bar for search, so a passing resemblance does not
  count. One message counts at most once per memory.
- **Does not count:** the memory being sent to the AI, the AI's own replies,
  the Memory Assistant analyzing the chat later, or the user opening the
  memory in the browser.
- **Why it matters:** if being sent to the AI counted as a mention, every
  briefing item would refresh itself forever and never fade. Only the user's
  own words count.
- **Editing:** whether editing a fading memory refills its strength is
  decision D8.

### 6.4 Where strength is used

1. **In the briefing:** a fading memory is in the "current" part only while
   its strength is above the cut-off.
2. **In search ranking:** possibly a small extra signal, like importance.
   This is decision D9. If approved, it must follow the approved retrieval
   rule: it can reorder relevant memories but never makes an irrelevant
   memory eligible.

### 6.5 Last mentioned

The app records the date each memory was last mentioned, for **every**
memory, lasting ones included, so the browser can show it. Fading only
applies to fading memories.

## 7. Dates in detail

### 7.1 What a dated memory stores

- the date (day and month, plus year for one-time dates; year optional for
  yearly dates);
- whether it repeats: one-time or yearly;
- no time of day. "Tomorrow afternoon" is stored as tomorrow's date.

### 7.2 Windows

- **Yearly:** shown for a set number of days before the date, through the
  day itself (decision D10).
- **One-time:** shown for a set number of days before, on the day, and for a
  short follow-up period after, so the AI can ask how it went (decision
  D10).
- **Whether windows can be changed per memory** is decision D11.
- **Year boundaries:** a 2 January birthday seen from 30 December is 3 days
  away. The calculation must not wait for the new year.
- **29 February:** in other years, the date is treated as 28 February or 1
  March (decision D12).

### 7.3 What the AI is told

The memory's own text, plus timing the app works out ("today", "in 3 days",
"2 days ago"), plus a short guidance instruction. The guidance's purpose is
to make the AI raise a reminder only when it fits naturally, and not repeat
it once it has already been discussed in the chat. The exact guidance text
is model-facing behavior and needs approval (decision D13).

### 7.4 How dates get set

- **By hand:** a date control in the memory editor (section 9).
- **By the Memory Assistant** (built on top of the Revision 26 repair):
  - When a conversation states a date, or says something like "tomorrow",
    the Memory Assistant includes the date in its proposal. The user sees it
    in Pending and can change or remove it before saving, like any other
    part of a proposal.
  - **Relative dates are worked out from the day the message was sent, not
    the day the analysis runs.** "My interview is tomorrow", said on 3
    November and analyzed on 8 November, means 4 November. The recorded
    transcript already stores when each turn started, which makes this
    possible.
  - Vague timing ("sometime next month") gets no date. The proposal says
    the timing was unclear, and the user can add a date if they want one
    (decision D14).
- **Changed plans:** a rescheduled interview is handled through the
  existing edit, supersede, and Possible Match paths. A different date
  counts as a change to the fact.

## 8. The standing briefing

### 8.1 What it is

A small block sent with every message, rebuilt fresh each time from three
parts:

- **A. Core:** memories the user has explicitly marked for the briefing.
  The AI never adds anything here on its own. The Memory Assistant may
  suggest a memory for core, but only through Pending, so the user decides.
- **B. Current:** fading memories whose strength is above the cut-off.
- **C. Upcoming:** dated memories inside their window.

Parts B and C are chosen automatically by the rules above. Whatever is
chosen is visible to the user: on the memory card (section 9) and in the
existing assembly view, which shows what was sent.

### 8.2 How this fits the owner's earlier ruling

`owner_approved_rules.md` section 10 says there is no per-memory "always
load" flag. Anything that should be in every message must be visible,
deliberate, and user-written. The briefing keeps that spirit:

- the user chooses core;
- everything automatic follows rules the user can see;
- the user can turn the whole briefing off.

It still changes section 10, so it needs an explicit owner ruling that
updates that section before any code is written (decision D15).

### 8.3 Size and cost

- The briefing has its own limit, counted in memories and in tokens. It is
  separate from `Maximum Memories Per Response`, which covers search
  results. Approved retrieval copy (section 4, Design Rule) forbids new
  capacity controls without explicit owner approval, so this limit needs
  that approval (decision D16).
- **Cost:** every briefing item is sent with every message. As a rough
  guide, 20 short memories add about 600–1,200 tokens per message, and 50
  add about 1,500–3,000. The memory-count figures are illustrations, not
  proposed defaults.
- **Prompt caching** (some providers charge less when the start of a prompt
  repeats unchanged) makes a stable briefing cheaper. So core goes first in
  a fixed order, and the parts that change (current and upcoming) go after
  it.

### 8.4 Where it sits in what the AI receives

After the fixed app instructions, Model Rules, and card material. Before
the memories found by search. This keeps the approved order, in which fixed
instructions outrank retrieved memory.

### 8.5 No double sending

If a memory is already in the briefing, search skips it for that message.

### 8.6 Cooldown

The briefing is sent with every message by design, the same exemption that
card cores and Model Rules already have under section 10. Repeated
reminders are kept in check by the guidance instruction (7.3), not by
removing items, because the AI only knows what it is sent.

### 8.7 Which memories are allowed

The briefing follows the same scope rules as search (`owner_approved_rules.md`
sections 1 and 3):

- companion memories only for that companion;
- real-life memories in roleplay only where those rules allow.

Whether the briefing runs in roleplay chats at all is decision D17.

### 8.8 Switches

- A master switch for the whole briefing (decision D18 covers the default).
- If memory is turned off for a chat, the briefing is off for that chat too.

## 9. Memory Browser, Editor, and Pending

### 9.1 Memory editor

New controls:

- the date, and whether it is one-time or yearly;
- lasting or fading;
- whether the memory is in the briefing core.

### 9.2 Memory card in the browser

- the date, and for yearly dates the next occurrence;
- for fading memories, the current strength, shown simply (decision D19);
- the last-mentioned date;
- a marker when the memory is in the briefing core.

### 9.3 Browser filters and sorting

Filters for upcoming dated memories and for briefing members, and sorting by
strength or by next date (decision D20).

### 9.4 Pending card

Shows a proposed date, a proposed lasting or fading choice, and a proposed
core marking. The user can edit each one before saving.

### 9.5 Possible Match

A different date on an otherwise matching memory is treated as a change to
review, not a duplicate.

### 9.6 How it looks

- Appearance follows `ui-style-guide.md`. Never hard-code visual values.
  Where no shared style fits, create a new shared style and add it to the
  guide.
- The Memory Browser and Memory Editor are recorded as **Partial** in
  `ui-style-adoption.md`. Update that file in the same change.
- All new labels, explanations, and messages need owner-approved wording,
  recorded in a copy specification before they are built.

## 10. Search repair: why it hands over almost everything

The owner reports that search hands the AI nearly every memory instead of
the relevant ones. The code and a measurement confirm it. There are four
causes, and they add up. Cause 4 is the main reason +3 memories are sent
with nearly every message.

### 10.1 How this was measured (2026-11-08)

- The app's default embedding model (EmbeddingGemma 300M, q4) was run off
  the phone with the same prompt prefixes, the same 256-dimension
  truncation, and the same normalization as `OnnxEmbeddingModel.kt`.
- 20 sample personal memories, written the way the Memory Assistant writes
  them ("The user ..."), and 6 sample messages, each with its expected
  memories.
- This is a small sample. It shows the direction and size of the problem.
  Final numbers are tuned on the test set (10.7, step 1).
- For cause 4, sample importance ratings were added (one +3, two +2, two
  +1, the rest 0) and the app's own scoring formula was applied.

### 10.2 Cause 1: the keyword backup matches every memory

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
  `Librarian.searchCore` only uses meaning search when every memory has
  one. That happens after adding or editing a memory, until the background
  repair finishes, and after a model change.
- **Result:** the 8 slots are filled by importance and recency, not
  relevance, and every +3 memory is added on top.

### 10.3 Cause 2: the meaning-search cut-off sits inside the noise

- **Where:** `Librarian.MIN_SIMILARITY = 0.30`. Its own code comment calls
  it a conservative guess to be tuned on the device. It was never tuned.
- **Measured, message alone:**
  - unrelated memories scored 0.10 to 0.39 (middle value 0.25);
  - relevant memories scored 0.28 to 0.49 (middle value 0.39);
  - at 0.30, 6 memories were right and 22 were wrong;
  - "hey how are you", which should bring back nothing, passed 4
    memories.

### 10.4 Cause 3: recent turns glued onto the search raise every score

- **Where:** `Enforcer.kt` builds the search text as the user's message
  plus the last 4 turns (up to 200 characters each), from
  `ChatActivity.recentTurnsContext`, all turned into one vector.
- **Measured:** adding one short recent-turn passage about the user's week
  raised unrelated scores as high as 0.43. 9 to 14 of the 20 memories then
  passed 0.30 for every message, including "hey how are you" (9 of 20).
  Recent turns that talk about the user's life pull every personal memory
  upward.

### 10.5 Cause 4: importance, freshness, and scope bonuses outweigh relevance

- **Where:** `Librarian.rank` and `Librarian.rankLexical`. A memory's score
  is 0.6 × similarity + 0.3 × importance + 0.1 × freshness + scope, project,
  and tag bonuses. The weights come from `DefaultOperatingData.kt` and are
  stored in the `retrieval_policy` row.
- **The values:** importance +1 adds 0.15, and +2 or +3 add 0.30 (−2
  subtracts 0.30). Freshness adds 0 to 0.10. Scope adds up to 0.12, a
  selected project 0.08, and matching tags up to 0.06.
- **The problem:** these weights were set as if similarity ran from 0 to 1.
  This model's real scores sit between about 0.10 and 0.50, and the gap
  between a strong match and noise is about 0.15. After × 0.6, that gap is
  worth about 0.09. A +2 or +3 rating adds 0.30, more than three times the
  whole gap. Freshness alone (0.10) is as large as the gap. The code comment
  above `SCOPE_BOOSTS` says the bonuses cannot let a weak match beat a
  strong one. That only holds on a 0-to-1 scale, so the comment is wrong
  for this model.
- **Measured:** with the sample ratings and the app's formula, the +3
  memory ranked **first in 11 of 12 searches**. That included "hey how are
  you", "my cat keeps scratching the couch", and "the app keeps crashing".
  The memory that actually matched best ranked first in only 1 of 12.
- **Result:** any +2 or +3 memory that gets past the broken gate goes
  straight to the top, and +3 is also added beyond the 8. This is the
  "+3 is just handed over" behavior.

### 10.6 The combined effect

The default limit is 8 memories (`DefaultOperatingData.kt`, `top_k`) and
6,000 characters. The limit is a maximum, but because most of the library
passes the gate, all 8 slots are filled every message, mostly with
unrelated memories. Highly rated memories take the top places whether or
not they are relevant, and +3 memories are added beyond the 8
(`ImportanceRanking.includeMandatory`). With a small library, that is
effectively everything.

### 10.7 Required changes, in order

1. **Build the test set first.**
   - Sample messages, each paired with the memories that should come back,
     including messages that should bring back nothing.
   - Store the real model's vectors for these as test fixtures. The model
     cannot run inside the unit tests, but the ranking functions
     (`searchCore`, `rank`, `rankLexical`) are pure and can be tested with
     stored vectors.
   - Every later step must improve the test set's results, or it is not
     kept.

2. **Build the search test screen** (owner request, 2026-11-08). It was
   planned earlier but never built. Today the only tool is a small search
   box in Advanced Memory Settings (`AdvancedMemorySettingsActivity.runDebugSearch`).
   That box shows one blended score per memory, searches without any
   chat's context (`RetrievalScope.NONE`), and hides the gate, the bonuses,
   and why memories were dropped.

   **What the screen does:** the owner enters a message and sees exactly
   what memory search would do with it, with every number shown. It sends
   nothing to any AI service and costs no tokens.

   **For each memory considered, it shows:**
   - meaning-search similarity;
   - keyword score, and which words matched;
   - relevance relative to this message (the step 6 scale);
   - every bonus separately: importance, freshness, scope, selected
     project, tags;
   - the final score and its rank;
   - whether it passed the relevance gate, and if not, by how much it
     missed;
   - whether it would be sent, and if not, why: below the gate, over the
     memory limit, over the character budget, cooldown, or duplicate of a
     lorebook entry;
   - whether it was added as a relevant +3 beyond the limit.

   **For the whole search, it shows:**
   - which search ran (meaning, keyword, or both), and why;
   - how many memories are missing their embedding;
   - the limit, budget, and strictness in effect;
   - the exact memory text the AI would receive.

   **Context:** the owner picks which chat's context to test in (companion,
   project, roleplay, campaign), because scope changes what is eligible.
   Recent turns can be included or left out, to see their effect.

   **Adjustable numbers** (owner request, 2026-11-08). The owner can
   change the search numbers on this screen and immediately see what each
   change does, so the values can be tuned on the owner's real memories instead of
   being guessed by whoever writes the code.
   - **Every tuning number is adjustable:**
     - the relevance gate's strength;
     - each bonus cap: importance, freshness, scope, selected project, tags;
     - the +3 boost;
     - the balance between meaning and keyword search;
     - the weight given to recent turns;
     - the memory limit (today 8) and the character budget (today 6,000).
   - **Try, then apply.** Changed values first apply only to the test
     result on screen, and the search re-runs at once. Nothing in real chats
     changes until the owner applies them.
   - **Applied values become the real values** used by every chat. They are
     stored in the existing `retrieval_policy` record, which the memory seed
     export already carries (`MemorySeedCodec`). Confirm that every backup
     path includes it, so tuning is never lost.
   - **Reset to defaults** puts back the values chosen from the test set.
   - **Raw numbers are allowed here.** The approved retrieval copy forbids
     unexplained raw thresholds and tuning controls in the ordinary
     settings. The owner has explicitly asked for them on this test screen
     (2026-11-08). The ordinary `Memory Match Strictness` choice
     (`Strict`, `Balanced`, `Broad`) stays as approved.
   - Still open: how `Memory Match Strictness` combines with a gate strength
     tuned here (decision D25).

   **Engineering requirements:**
   - **One code path.** The ranking functions return an explanation
     alongside each result, and both the chat and this screen use the same
     functions. The screen never recalculates scores on its own, so what it
     shows is what the chat actually does.
   - **Read-only.** Testing never records cooldown, never counts as a
     mention for fading (section 6.3), and never changes a memory.
   - It opens from Advanced Memory Settings, in the Debug section (owner
     decision D24). Its wording and layout need owner approval. Appearance follows `ui-style-guide.md`.

   Build this before tuning, so every later step can be checked on the
   phone with the owner's real memories, not only in the test set.

3. **Fix keyword search.** Replace word-overlap counting in `rankLexical`
   with BM25, the standard keyword formula. Words found in nearly every
   memory ("the", "user") score close to nothing, and rare, specific words
   (names, places, "Kotlin") score high. A memory then needs a minimum
   keyword score, not just one shared word.

4. **End the all-or-nothing fallback.** In `searchCore`, run meaning search
   over every memory that has a current vector, and keyword search over all
   memories, then merge the two ranked lists (hybrid search, step 8). A
   missing vector removes only that one memory from meaning search, not the
   whole turn.

5. **Search with the user's message on its own.**
   - The main search uses only the user's latest message.
   - Recent turns get their own separate search with a lower weight. The
     two result lists are merged by each memory's best score.
   - `Librarian.searchForReconciliation` already searches several text
     windows separately and merges them, for the Memory Assistant. Live
     search can use the same approach.

6. **Replace the fixed 0.30 cut-off with a rule relative to each message's
   own scores.** A memory must stand clearly above the other memories'
   scores for this message, not just above a fixed number. Measured on the
   same sample:

   | Rule | Right | Wrong | Missed |
   |---|---|---|---|
   | Today: fixed 0.30 | 6 | 22 | 2 |
   | Fixed 0.38 | 4 | 1 | 4 |
   | At least 2 standard deviations above this message's average score | 4 | 0 | 4 |

   - The relative rule sent nothing for "hey how are you", which is
     correct.
   - The memories it missed (the garden for cooking, the job for an
     interview, the "Android chat app" for an app crash) are the ones
     keyword and hybrid search recover. For example, "app" appears in both
     the crash message and the chat-app memory.
   - The approved `Memory Match Strictness` levels (`Strict`, `Balanced`,
     `Broad`) become three strengths of this rule. Final values are tuned on
     the test set. Raw numbers are never shown to the user, as the approved
     copy requires.

7. **Make relevance decide, and bonuses only nudge.**
   - Measure each memory's relevance on the same relative scale as the
     gate in step 6: how far it stands above this message's average score.
   - Express importance, freshness, scope, selected-project, and tag bonuses
     as fractions of that scale, capped so that all bonuses together are
     smaller than a clear relevance lead.
   - **Property to test:** if memory A is more relevant than memory B by
     more than the cap, A ranks above B, whatever their ratings, freshness,
     or scope. Revision 26 already requires this property for the Memory
     Assistant's search. Live search gets the same guarantee.
   - Tune the cap values on the test set, and correct the `SCOPE_BOOSTS`
     comment.

8. **Merge meaning and keyword results** with reciprocal rank fusion, a
   simple, standard way to combine two rankings. A memory found by both
   ranks highest.

9. **Sending nothing is a valid result.** When nothing passes, no memories
   are sent. The memory limit stays a maximum, never a quota to fill.

10. **+3 as the owner defined it (2026-11-08): a boost, not a pass.**
   - A +3 memory must be relevant to the message, like any other memory. It
     is never sent just for being +3.
   - It gets the strongest importance boost, so it ranks higher among the
     relevant memories.
   - If it is relevant and the 8 slots are already full, it is added
     anyway.
   - `ImportanceRanking.includeMandatory` and `RetrievalBackfill.select`
     already add +3 beyond the 8 only after the relevance gate. Causes 1–4
     are what turn that into "every +3, every message". Once steps 3–7 are
     done, the existing rule behaves as the owner describes.
   - **Tests:** a +3 memory unrelated to the message is not sent (for
     example "hey how are you"). A relevant +3 memory is sent even when 8
     more relevant memories fill the slots.

11. **Variety.** Stop near-identical memories from filling several slots,
   using MMR (maximal marginal relevance, the standard method).

12. **Optional local re-ranker:** a small second model on the phone that
    reorders the top results. It costs no tokens, but it is another
    download and adds a short delay per message. Decide only after the test
    set shows whether steps 3–11 are enough (decision D21).

13. **Model quality check.** The int8 model is already in the app's model
    list. Run it against the test set too. If it scores clearly better than
    q4, raise that with the owner.

### 10.8 Excluded: techniques that cost tokens

Rewriting the message with an AI before searching, writing a "pretend
answer" to search with (HyDE), and paid AI re-ranking all add a paid call
to every message. They are not part of this plan.

### 10.9 Relationship to Revision 26

The Memory Assistant repair uses the same Librarian ranking. Every change
here must keep Revision 26's tests passing, including its "relevance comes
first" property.

## 11. Data, backup, and restore

- **Database:** one migration from version 33 adds:
  - date, repeat, and optional year;
  - lasting or fading;
  - briefing-core flag;
  - strength and strength-updated date;
  - last-mentioned date.

  New columns are used. The retired `always_load` column and `owner_profile`
  table are not reused, so old meanings cannot leak in.
- **Existing memories after the migration:** lasting, no date, not in the
  briefing (section 4.4).
- **Backups and exports:** every new field goes into the memory backup, the
  portable export, the seed format, and shared restore rows. It also goes
  into the package used by the future Computer Memory Review. Restoring an
  older backup gives these fields their "unchanged behavior" defaults, never
  an error.
- **Settings:** the date-sharing setting and the briefing settings go into
  the settings backup.

## 12. Privacy and diagnostics

- Dates such as birthdays are personal. They are stored in the same
  encrypted memory database as everything else.
- Today's date leaves the phone only when the user has turned on date
  sharing. Relative timing in reminders (5.4) is the only exception.
- What the briefing sent is visible in the existing assembly view.
- **No new log lines without owner approval** (`CLAUDE.md` section 8). Any
  diagnostics this feature would benefit from are proposed separately, with
  the exact log and location.

## 13. Build order

This is dependency order only. The feature ships whole, and none of these
steps counts as done on its own.

1. **Today's date:** the single date source, and the optional
   date-sharing setting. Whether this ships separately, earlier, is
   decision D22.
2. **Data:** the migration, backup, export, and restore support, with
   round-trip tests.
3. **Editor, card, browser, and Pending:** date, aging, and core controls.
4. **Fading:** the strength math, mention detection, and last-mentioned
   tracking.
5. **Briefing:** assembly, limits, settings, and the assembly view.
6. **Memory Assistant proposals** for dates, aging, and core. Built on top
   of the Revision 26 repair.

Search repair (section 10) comes before all of the above (decision D23).

New code goes in its own files (for example a new folder under
`preferences/memory/`), with only small hookups in the chat screen, per
`CLAUDE.md` section 11.

## 14. Tests and on-device proof

### 14.1 Unit tests

- **Fading:** half-life math, boost cap, one mention per message, and that
  the memory being sent to the AI never counts as a mention.
- **Windows:** before, on, and after the date; the year boundary; 29
  February; yearly dates without a year.
- **Relative dates:** resolved from the day the message was sent, not the
  day of analysis.
- **Migration:** existing memories produce exactly the same AI input as
  before.
- **Backup round-trip:** every new field survives.
- **Briefing:** scope rules, limits, no double sending with search, and
  stable ordering.
- **Search:** the test set (section 10.7) runs as automated tests,
  including messages that must bring back nothing.

### 14.2 On-device proof

- A birthday saved a week ahead appears in the briefing, is raised
  naturally, and does not repeat in the same chat.
- An interview is raised the day before, followed up after, then stops
  being raised, while still being findable by search.
- A worry mentioned daily stays current. After some weeks without mention,
  it leaves the briefing, and the card shows that.
- With date sharing off, the AI cannot state today's date but still
  receives "tomorrow" for a dated reminder.

## 15. Decisions needed from the owner

Asked one at a time in chat, not all at once. Each recommendation is a
proposal only.

| # | Question | Recommendation |
|---|---|---|
| D1 | Is date sharing off or on by default? | Off; the user turns it on |
| D2 | What date format goes to the AI? | Weekday, day, month name, year, which can't be misread |
| D3 | Does date sharing apply in roleplay chats? | No by default; the story may have its own time |
| D4 | With date sharing off, do reminders carry relative timing ("tomorrow")? | Yes; a reminder is useless without timing |
| D5 | Is the date-sharing setting app-wide, per chat, or both? | App-wide first; per chat only if wanted later |
| D6 | Are manually created memories lasting or fading by default? | Lasting, the same as today |
| D7 | How fast does fading go, and can the user change it? | One app-wide speed with a few simple choices, not per memory |
| D8 | Does editing a fading memory refill its strength? | Yes; editing is deliberate attention |
| D9 | Does strength also nudge search ranking? | Yes, bounded like importance; never makes an irrelevant memory eligible |
| D10 | How long are the windows? | Yearly: about a week before through the day. One-time: a few days before through two days after |
| D11 | Can windows be changed per memory? | Not at first |
| D12 | How is a 29 February date handled in other years? | 28 February |
| D13 | What does the guidance instruction to the AI say? | Owner reviews the exact text before it is built |
| D14 | Does vague timing get a date? | No; the user can add one in Pending |
| D15 | Should `owner_approved_rules.md` section 10 be updated to allow the briefing? | Yes, as a new ruling recorded there |
| D16 | Does the briefing get its own size limit control? | Yes, separate from search limits |
| D17 | Does the briefing run in roleplay chats? | Off by default in roleplay |
| D18 | Is the briefing on or off by default? | Off; it adds a cost to every message |
| D19 | How is strength shown on the card? | A simple level plus the last-mentioned date, not a raw number |
| D20 | Which browser filters and sorts are added? | Upcoming dates, briefing members, sort by strength |
| D21 | Should a local re-ranker be added? | Decide after the test set shows a need |
| D22 | Does date sharing ship on its own, before the rest? | Yes, if the owner wants it sooner; it doesn't depend on the rest |
| D23 | When is search quality (section 10) scheduled: before, with, or after this feature? | **Decided by the owner, 2026-11-08: search is fixed first.** |
| D24 | Where is the search test screen reached from? | **Decided by the owner, 2026-11-08: its own screen, opened from Advanced Memory Settings, in the Debug section.** It is its own screen, not part of the Debug box. Still open: whether the old search box is removed, and the screen's labels |
| D25 | How does the ordinary Strict / Balanced / Broad choice combine with a gate strength tuned on the test screen? | The tuned value becomes Balanced; Strict and Broad sit a fixed step either side of it |

## 16. Relationship to other documents

- **The owner** directs what is built and when.
- **`ACTIVE_ASSOCIATIVE_MEMORY_REPAIR.md`** (Revision 26) is unchanged.
  Memory Assistant changes in this plan wait for it.
- **`owner_approved_rules.md`** keeps controlling scope, importance, and
  priority. Section 10 needs an update if the briefing is approved (D15).
- **`memory_retrieval_and_analysis_ui_copy.md`** keeps controlling the
  approved search controls. The briefing limit is a new control and needs
  approval (D16).
- **`legacy/`** designs for an always-on profile (owner portrait, standing
  packet, standing context, always-load) are retired and are not inputs to
  this plan.
