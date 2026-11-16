# Memory development plan

**The single authoritative plan for all memory work:** saved memories and
their search, the Memory Browser, the Memory Assistant, context assistance,
and conversation recall. Consolidated on 2026-11-16 at the owner's request.
The file keeps its old name, `conversation_recall_plan.md`, because code
comments and tests cite its section numbers.

- **Part M** (below) is the build order, the status, the provenance design,
  where each setting goes on screen (M.11), and the record of what was
  superseded. It is the only place that decides
  what is built next.
- **Sections G.0 to G.19** are the design of context assistance and
  conversation recall, the work orders for the numbered Steps (G.16), and
  the open decisions (G.17). "Part A" or "Part E" in those sections means
  the memory plan below. Numbered Steps always mean the work orders in G.16.
- **`Memory System/dynamic_memory_plan.md`** is reference material: the
  measured diagnosis and design of saved-memory search (Parts A and B), the
  designs for later saved-memory features (Part C Phases 2–6, Part D), the
  D-decisions (Part E) and research (Part F). Its status and ordering
  sections are historical. Where it disagrees with this plan, this plan is
  current (M.7).
- **`rag alteration notes November 14.md`** is a historical record of the
  owner's November 14–16 direction. Everything in it is carried into this
  plan (M.8).
- **`memory baseline.md`** remains the evidence record for every
  saved-memory measurement. Results are never copied into this plan.

**State on 2026-11-16.** Steps 0–3 are built, Step 3's repair is merged in
#205, and the carry-over evaluation is merged in #206 with its results in
`memory baseline.md`. The owner's decisions of 2026-11-16 replace searching
with the previous messages by a selectable context assistance (G.9, G.9b),
approve a narrow +3 exception (G.10), and keep embedding models replaceable
(G.9c). The next stages are memory provenance (M.3) and Step 4a, in the
order M.2 recommends; both wait for owner decisions listed in M.10.

# Part M: Roadmap, status, and consolidation

## M.0 How to read Part M

- **M.2 is the only build order.** G.16's former execution table, the memory
  plan's "Next steps", Part C phase list and D.13 order, and section 4 of the
  RAG notes no longer set the order.
- **Labels are the same as G.0.** **Owner** is a decision the owner made,
  with its date. **Proposed** is an agent recommendation the owner has not
  approved. **Open** is a question waiting for the owner. Nothing becomes a
  decision because an earlier document called it approved.
- **A stage is a coherent piece of work** that is normally delivered as one
  pull request (`CLAUDE.md`: one session's changes go in one PR). A stage
  may contain several numbered Steps. The Steps keep their numbers because
  code cites them; their work orders in G.16 still apply in full.
- **Order and independence.** Stages are built in M.2 order. When the next
  stage is waiting only for an owner decision, the owner may assign a later
  stage whose "Needs" column is already satisfied. No builder skips ahead on
  their own.
- **Verification is kept, research is not repeated.** Every stage keeps its
  tests and its on-device checks. The investigations closed in M.1 are not
  run again unless the owner reopens them.

## M.1 Already built and closed

| Work | Where | Record |
|---|---|---|
| Saved-memory search repair: meaning plus keyword search, common words ignored, the message searched alone, the relevance gate, importance as a capped nudge, the +3 rule, no cooldown; the Memory Search Test screen (memory plan Phase 1, steps 1.3–1.11 and 1.4) | #180 | Memory plan, section 1 of the historical status |
| Inside Joke type and the per-memory vibe (D56, D58); memory database version 34 | #182 | Memory plan, section 3 of the historical status |
| Step 0: saved-memory benchmark, 86 samples | — | `memory baseline.md` |
| Step 1: token counting, one embedding queue, stored memories read up to 2,048 tokens | #199 | `memory baseline.md` |
| Step 2: long and dictated messages searched in sections; repairs restore the original quality gate and calibrate Stand-Out Strength 2.53 and Minimum Spread 0.034 | #200, #201, #202 | `memory baseline.md` |
| Step 3: previous-message search helper, not connected to sending; repair applies the owner's +3 rule to section matches | #203, #205 | `memory baseline.md` |
| Carry-over evaluation on a development and a held-out set with Android similarities | #206 | `memory baseline.md`; decisions G37, G40 |

**Closed investigations. Do not repeat them:**
- Android ONNX Runtime 1.29 and forced FP32 activations: no accuracy
  benefit, rejected. The app stays on Android ONNX Runtime 1.20.0.
- Whole-message gate calibration: done (2.53 and 0.034). Further retuning
  is not part of any stage below.
- Searching with the previous messages (G.9a), "similar to new message",
  and the plain lower +3 bar: measured and not used (G37, G40).
- The 23 MB on-phone re-ranker on saved memories (memory plan A.10): did
  not work. A re-ranker for conversation passages stays a separate later
  question (G.6 retrieval stage 5a).

## M.2 The build order

| Order | Stage | Contains | Needs | Approve first | The user sees |
|---:|---|---|---|---|---|
| 1 | **Memory provenance** | M.3: source storage and database migration; sources recorded by the Memory Assistant; backups, imports, restores and merges; source carried through supersede and merge; source link and dates in the Memory Browser | Nothing unbuilt | Nothing (G43 decided) | A link at the bottom of a memory back to the message it came from, and its dates |
| 2 | **Context assistance: Off and Code-based** | Step 4a: the context-assistance interface, Off, Code-based subject tracking, the +3 top-match rule, the app-wide setting | #205 and #206 merged (done) | G38; G45 if the lorebook check is included | Follow-ups keep their memories; the context-assistance setting |
| 3 | **Context assistance: API AI** | Step 4b, with the dedicated configuration screen of M.4 | Stage 2 | G39, G44 | The API AI choice and its configuration screen |
| 4 | **Memory length limit** | Step 5: word count under every memory editor, the saving limit, Memory Assistant splitting | Step 1 (done) | Open parts of G34 | Word count under memory fields |
| 5 | **Data control** | Memory plan Phase 2: the view dropdown, Retired, Memory Handling Preferences, Delete to Retired, with every new field in backups | Stage 1 (both are expected to change the memory database; building them one after the other avoids competing migrations) | Wording and layout; D44 default | Retired view and Memory Handling Preferences |
| 6 | **Conversation test chats** | Step 6 | Nothing unbuilt | Nothing | Nothing |
| 7 | **Conversation index: cutter and stores** | Steps 7 and 8 | Stage 6; Step 1 (done) | G35 | Nothing |
| 8 | **Conversation index: embedding and upkeep** | Steps 9 and 10 | Stage 7 | Nothing after G35 | Nothing |
| 9 | **Conversation index: settings, progress, after close** | Steps 11, 12 and 13 | Stage 8 | G20, G28, G31; Step 13's mechanism and stopped-for-the-day wording | Conversation Index settings, progress icon and pop-up, notification |
| 10 | **Recall retrieval and inspector** | Steps 14 and 15 | Stages 2 and 8 | G36; inspector labels | Conversation Recall Test screen |
| 11 | **Conversation Recall in real requests** | Step 16 | Stage 10 | AI-facing text, "what was sent" wording, recall-room setting, debug lines, G24 | Conversation Recall works |
| 12 | **The Always tab and bring-up setting** | Memory plan Phase 3 | Stage 5 | Wording, screens, AI-facing text; D17 | Always tab |
| 13 | **Today's date and dates on memories** | Memory plan Phase 4 | Stage 12 | D55 first; D1–D5, D12–D14 wording and AI-facing text | Dates tab, important dates |
| 14 | **Fading** | Memory plan Phase 5 | Stage 13 | D8, D19, D45, D49, D50 details | Fading markers |
| 15 | **Memory Assistant date proposals** | Memory plan Phase 6 | Stage 13 | D33, AI-facing text | Proposed dates in Pending |
| Later | Not ordered | Optional re-ranker; live AI worker (G.11, G8); recall across chats (G9); Local AI context assistance; other embedding models (G41); background reviewer (D51); recent-chat continuity (D38); companion journal (D40); Model Rules adjustments (D.13); search tuning revisited; vector size, automatic tags and variety (memory plan steps 1.14–1.16, only if testing shows a need) | The re-ranker, live AI worker and cross-chat recall need stage 11 | Separate owner decisions | — |
| Any time | Not a stage of its own | **Automatic companion linking** (M.6, Owner 2026-11-16): every companion the user creates is linked to the memory system automatically | Nothing unbuilt | Nothing; the behavior is decided | Nothing new; companions appear in the memory system as soon as they are saved |
| Any time | Not a stage of its own | The memory-use instruction (D41) can be added to any stage that touches the memory instructions once its exact text is approved | — | D41 text | Changed AI behavior |

**Why this order (Owner approved, 2026-11-16, G42):**
- **Provenance first.** The owner wants to build a memory collection now.
  Every memory saved before provenance exists has no recorded source, and
  that source cannot be recovered later. Provenance does not depend on any
  search or recall work, and Step 4a does not depend on it, so swapping
  stages 1 and 2 causes no rework.
- **Context assistance next,** because it fixes a measured live problem
  (follow-ups lose their memories) and its prerequisites are merged.
- **Step 5 and data control before the conversation index,** because they
  protect and improve the memory collection the owner is building, are
  smaller, and touch only the memory editor, the Memory Browser, the
  Memory Assistant and the memory database.
- **Conversation recall before Phases 3–6.** The owner asked for recall
  (G.1) and it has been the active roadmap since 2026-11-10; Phases 2–6 were designed but each waits until the owner
  asks for it (memory plan, Next steps item 7). Phase 2 is the exception
  above because it directly serves the Memory Browser. If the owner prefers
  Always and dates sooner, stages 12–15 can move ahead of stages 6–11:
  they use different databases and files.
- **Fewer, larger stages.** Steps 7–8, 9–10, 11–13 and 14–15 are grouped
  because each group is one coherent part of the system that can be
  reviewed and tested together. Each Step's tests and approval gates are
  unchanged. A builder may still split a stage if one PR would be too
  large to review safely, and says why.

## M.3 Memory provenance (Owner decisions of 2026-11-15 and 2026-11-16)

### What the owner decided (2026-11-15, recorded in the RAG notes section 1A)

- **Original statement date and time,** separate from the memory's creation
  and last-edited times. Unknown historical dates stay unknown.
- **Source conversation ID,** a stable ID, never the changeable chat title.
- **Source message ID,** the permanent ID of the exact message, when
  available.
- **A clickable source link at the bottom of the memory** in the Memory
  Browser, opening the conversation, ideally at the original message.
  Technical IDs are used for navigation and are not shown in the normal
  view.
- **Speaker attribution is optional, never mandatory.** Who said it (the
  user, the AI, or another speaker) is stored when known. Unknown stays
  unknown, and an AI suggestion is never attributed to the user.
- **An optional event date,** when the source supports it. It is not the
  statement date or the creation date, and is never invented. **Owner,
  2026-11-16:** this is the same thing as the important date of memory plan
  Phase 4 (D.5): one field, called the **important date**, holding the date
  the thing actually happens, not when it was said.
- **Several sources per memory.** A consolidated, updated or deduplicated
  memory keeps references to every supporting source rather than replacing
  them.
- **Stored provenance is not embedding input.** Source IDs and dates are
  metadata. Whether speaker wording should be part of the text the
  embedding model reads remains a measured, open question, neither banned
  nor required.
- **Existing memories stay intact,** and provenance survives edits,
  consolidation, backup and restore, and embedding-model changes.
- **Priority:** before large-scale creation of memories without source
  lineage.

### What exists today (read in the code on 2026-11-16)

A builder re-reads these before relying on them.

- **The memory record** (`MemoryStore.kt`, table `memories`, database
  version 34) has `created_at`, `updated_at`, `origin` and `supersedes`, and
  no source, statement-date, speaker or event-date field.
  `memory_supersessions` records which memory replaced which.
  `change_log` records edits with an actor.
- **The Memory Assistant reads stored turns** (table `transcripts`). Each
  turn in a transcript row holds its role (user or assistant), its text, the
  time it was captured, and, for turns captured since message IDs were
  recorded, the permanent message ID (`TranscriptTurnVisibility.stamp`).
  Each row holds the chat ID.
- **Memory Assistant drafts carry no source.** Proposed memories record
  neither the chat nor the message they came from. Lorebook suggestions
  record only the source chat ID and name (`lorebook_suggestions`).
- **The chat can already open at a message.** Chat search passes a message
  ID (`SearchTargetResolver.EXTRA_MESSAGE_ID`) and the chat screen scrolls
  to it (`ChatActivity.jumpToMessage`). The source link can reuse this.
- **Chat IDs of older chats change on rename.** A chat that never had a
  stored ID uses a hash of its title (`ChatPreferences.storedChatId`), and
  `ChatRenameTransaction` does not update anything in the memory database.
  Message IDs do not change.
- **Backups are table-driven.** The memory backup, import and restore read
  and write the tables listed in `MemoryPortableRows.memorySpecs`. A new
  table is carried only when it is added to that list. Imports can remap
  memory IDs (`MemoryIdRemap`, `MemoryIdImport`).
- **The embedding text** is built by `RetrievalDocument` from a memory's
  content and tags. New metadata columns do not change it, so provenance
  requires no re-embedding.

### What the Memory Browser shows (Owner, 2026-11-16)

At the bottom of a memory, each source is shown as separate labeled lines,
never run together on one line. For a source that is a user message:

> **Speaker:** Lena
> **Glamour:** Midnight
> **Date:** November 10, 2025
> **Source Message**

- **Speaker** is the username the chat listed for that message: the name
  shown above that message in the chat. That name can come from the
  chat's own username, a Glamour's in-chat name, or a Roleplay Character.
  It is the name as it was for that message, recorded when the source is
  recorded. A later name change never relabels it.
- **Glamour** appears on its own line under Speaker **only when a Glamour
  was in use for that message**. It always shows the Glamour's **profile
  name** (its assigned name in the Glamour list), even when that Glamour
  uses a different name in chat, because the profile name is the firm
  identifier. The Glamour's ID is stored with it.
- **Date** is the date the message was sent, always fully spelled out:
  month name, day, comma, four-digit year ("November 10, 2025"). Never
  numeric, abbreviated, or relative ("yesterday"). With no known date the
  value reads **Unavailable**.
- **Source Message** is the tappable line that opens the chat at that
  message. When only the conversation is known, it reads **Source
  Conversation** and opens the chat.
- **Source and speaker are separate (Owner, 2026-11-16).** The **source**
  is the message the memory was taken from (the Source Message line). The
  **speaker** is who said it (the Speaker line, and Glamour for the user's
  own messages). The speaker name is saved when the source is recorded and
  is never deleted: it always shows a name, even when no profile exists for
  it, when that profile was deleted, or when the source message was
  deleted or is unavailable. Only the Source Message line can read
  **Deleted** or **Unavailable**; Speaker, Glamour and Date stay.
- **No source, no lines (Owner, 2026-11-16).** A memory with no recorded
  source (every memory saved before this stage, a memory the user wrote,
  an import without sources) shows no source lines at all. The source lines
  are a reminder for the user of where a memory came from; they are never
  sent to the AI and nothing else depends on them.
- **Unavailable and deleted sources (Owner, 2026-11-16).** When a recorded
  source cannot be found or opened (an error case), the source line reads
  **Unavailable**. When the
  source message, or the chat holding it, was deleted, it reads
  **Deleted**. The memory is never removed or changed because its source
  was deleted. (This is different from a deleted Glamour, companion or
  Roleplay Character, whose last known name is kept.)
- The appearance of the labels and values comes from `ui-style-guide.md`;
  a missing style is created there.

**Rules for the builder.**
- Every value comes from the stored message and the app's own records,
  never from the Memory Scanner's text. The scanner only names which
  message a memory came from.
- **Checked in the code on 2026-11-16:** each user message is stamped when
  sent with the name used and, when that name came from a Glamour or a
  Roleplay Character, the source and its ID (`ChatSpeakerNames`:
  `userName`, `userNameSource` with `glamour:` or `roleplay:`). A chat's
  own username, when set, replaces every user name that chat displays
  (`ChatSpeakerNames.displayedUserName`). Companion replies carry the
  companion's name (`companionName`). Messages sent before stamping existed
  show only "User".
- **Verify before building, and stop if it does not hold:** whether the
  stamp records the Glamour whenever one is in use, or only when the
  Glamour's own name is the name used (for example, when the chat's own
  username replaces it). The owner's rule is "show the Glamour whenever one
  was in use for that message". If the existing stamp cannot tell, propose
  how to record it for new messages; older messages show only what was
  recorded and nothing is guessed.
- **The speaker is whoever said the message (Owner, 2026-11-16).** When a
  companion said it, **Speaker** is that companion's name, from the name
  recorded on its reply (`companionName`), and nothing else: no Glamour or
  second line, because companions have no sub-profiles. The Glamour line
  belongs only to the user's own messages. The same principle applies when
  a Roleplay Character speaks: **Speaker** is that character's name.
- **Editing (Owner, 2026-11-16).** The user can never edit the memory's
  creation date or a source message's date, and cannot add, change or
  remove sources: sources come only from the app. The user can add, change
  and clear the memory's **important date** (the former "event date"), so a
  memory about something at a specific time can be brought up at the right
  time.
- **Possible later (Owner, 2026-11-16; not now):** letting the user add a
  source to a memory. Not built unless the owner asks. The sources table
  already allows more sources per memory, so adding this later needs no
  database change.
- **Renamed or deleted profiles (Owner, 2026-11-16).** A Glamour, a
  companion and a Roleplay Character are referenced by their permanent ID,
  never by name. The source stores that ID with the profile's name as last
  known.
  - **Renamed:** the line shows the profile's current name, found by its
    ID, so a rename updates every memory that cites it. The stored name is
    refreshed to match.
  - **Deleted:** the line keeps the last known name. It never says
    "deleted" or "unavailable" and is never removed.
  - This covers the Glamour line, a companion speaker, and a Roleplay
    Character speaker. The user's **Speaker** username is the name the chat
    showed for that message and stays as recorded.
  - **Verify before building:** which ID each record holds. A user message
    names its Glamour or Roleplay Character in `userNameSource`; a
    companion reply is stamped only with `companionName`, so the companion's
    ID must come from the chat's companion at the time (the transcript row's
    `companion_id`) or be stamped on new replies. Nothing is guessed for
    older messages.

### The design (G43 decided, 2026-11-16)

**Storage.**
- A new table of sources, one row per supporting source of a memory:
  - the memory it supports, removed with the memory;
  - the kind of source: a chat message, a conversation with no known
    message, a user's own entry, an import, or unknown;
  - the chat ID and the permanent message ID, each when known;
  - the speaker, when known: the user, the AI, another speaker (such as a
    roleplay character), or unknown. Never required;
  - for a user message: the username the chat displayed for that message,
    and, when a Glamour was in use for that message, the Glamour's ID and
    its last known profile name; for a companion or Roleplay Character
    speaker: its ID and last known name (see "What the Memory Browser
    shows" below);
  - the statement time, when known;
  - when and how the source was attached: cited by the Memory Assistant,
    or carried from a memory it replaced.
- **No event-date field in this stage.** The event date is the important
  date (Owner, 2026-11-16), one field, built with Phase 4 (stage 13) in the
  form D.5 designs, so the memory database gains one date field, not two.
- **No copy of the message text** is stored with the source (Owner,
  2026-11-16: no excerpts). The chat stays the only home of the
  conversation. If the chat is deleted, the memory keeps its date and
  speaker, and the source line reads **Deleted**.
- The memory's "original statement date" shown to the user is the earliest
  known statement time among its sources. It is derived, not stored twice.
- The migration adds the table and the column in the next database
  version. Existing memories receive no source rows and no dates; nothing
  is guessed. Creation and edit times are unchanged.

**How sources are recorded.**
- **Memory Assistant.** The chat of the analyzed conversation is known with
  certainty and is always recorded. To record the exact message, the
  assistant's instructions ask it to cite the turns each proposal came from.
  The app checks every citation against the turns it actually sent: a
  citation to a turn that was not in the batch is dropped, and the source
  falls back to the conversation alone. **The speaker and the statement
  time are read by the app from the cited turn, never from the model's
  words,** so an AI suggestion can never be recorded as the user's
  statement.
- **The Memory Scanner's citing instruction** (the owner left the wording to
  the agent, 2026-11-16). Added to both analysis prompts, adapted to the
  turn numbering and output format the builder finds in `ArchivistPrompt`:

  > Each turn of the conversation is numbered. For every memory you
  > propose, list the numbers of the turns where that information was
  > actually stated, in `source_turns`. Cite only turns from this
  > conversation that state it directly. If it comes from several turns,
  > list each one. If you are not sure which turn it came from, leave
  > `source_turns` empty rather than guessing.

  The app ignores any cited number that was not in the batch.
- **Pending to saved.** Accepting, editing or filing a proposal keeps its
  sources.
- **Replacing and merging.** Save & Supersede, Replace and a Possible Match
  merge give the resulting memory every source of the memories it replaces,
  without duplicates (owner direction above).
- **A memory the user writes** has no source unless the user adds one. There
  is no action today for saving a chat message as a memory; adding one is a
  separate interface decision, not part of this stage.

**Navigation.**
- The link opens the source chat at the source message through the
  existing message jump.
- When the stored chat ID no longer exists (an older chat that was
  renamed), the chat is found again by the permanent message ID. The rename
  path also updates source references where it can. A test covers a renamed
  older chat.
- A source message that was deleted, or belongs to a deleted chat, reads
  **Deleted**; one that cannot be found or opened reads **Unavailable**. The
  memory itself is never changed or removed because its source changed.

**Backups, imports, restores and merges.**
- The sources table is added to `MemoryPortableRows.memorySpecs`, so every
  backup, export, import and restore path carries it.
- Restoring an older backup that has no sources restores memories without
  sources, never with an error.
- A memory ID remapped during import keeps its sources under the new ID.
- A chat merge or restore that changes chat IDs keeps source links working
  through the message ID.
- Round-trip tests for each path, including the legacy conversion path.

**Privacy.** IDs, times and speaker stay in the encrypted memory database.
Nothing new is sent to any AI model and nothing new is placed in the
embedding text.

**Not in this stage:** the important date (Phase 4, stage 13) and the
Memory Assistant proposing it (Phase 6, stage 15); user-added sources; speaker wording in the embedding text (measured
later, when a test set with speakers exists); filling in sources for
memories saved before this stage.

**Tests.** Migration from version 34 with existing memories unchanged;
recording and validating Memory Assistant citations, including an invalid
citation and an AI-written turn; sources kept through Pending, edit,
supersede, replace and Possible Match; every backup path round-trips;
older backups restore without sources; renamed older chat, deleted chat
and deleted message links; embedding text and stored vectors unchanged.

## M.4 Context assistance: correction for API AI (Owner, 2026-11-16)

**Owner, 2026-11-16 (this consolidation's assignment):** the initial
rollout of context assistance supports **Off, Code-based and API AI**.
Local AI is supported by the architecture and built later. **API AI has a
dedicated configuration screen,** not merely a model picker. That screen
holds **its own API model selection, independent of the chat, Summarizer
and embedding models.**

This corrects G.9b and the Step 4b work order, which described only "its
own model selector". The interface, the helper's role, and the rules every
choice keeps are unchanged (G.9, G.9b).

**What the screen needs, for the owner's approval before Step 4b (G39).**
None of these is decided. A builder does not choose them:
1. **Which provider connection** the helper uses, from the user's
   configured API providers, and whether it can differ from the
   Summarizer's and Memory Assistant's.
2. **The model list,** read from the provider's live catalog, never a
   built-in list. Which models qualify.
3. **What the helper reads:** how many recent turns, and whether the AI's
   replies are included or only the user's messages.
4. **Its instruction text:** fixed, or viewable and editable with a way to
   restore the default.
5. **Model settings** such as response length and temperature: fixed by the
   app, or adjustable.
6. **Time allowance:** fixed, or a setting.
7. **Failure behavior and notice** when the helper is offline, errors, runs
   out of time, or returns unusable output. Proposed in G.9b: the turn goes
   ahead as Off.
8. **Cost display:** what the provider reports, otherwise "Not Reported".
9. **Privacy disclosure:** that recent conversation is sent to the chosen
   provider.
10. **What happens when API AI is chosen but no model is configured.**
11. **Its title and labels.** It opens from a row in **AI Settings**,
    beside Memory Scanner Tool API and Embedding Model (M.11), and the API AI
    choice in RAG Active Memory Settings may point to it.
12. **Whether a test action** is offered on the screen.
13. **Logging:** whether failures go to the Provider Failure Log under a
    function name of their own (all logging needs approval).

**Ship timing (Open, G44).** Step 4a can ship the setting with Off and
Code-based before Step 4b adds API AI, or the setting can stay hidden
until all three choices exist. **Recommendation:** ship Step 4a first. Its
benefit is measured and free, and adding API AI later changes nothing
already built.

## M.5 Embedding models stay replaceable

G.9c is the owner's decision. EmbeddingGemma 300M q4 is the current
default, not a permanent restriction. In addition:

- **Already true in the code:** each stored memory vector is stamped with
  its model's tag, and the `embeddings` table is keyed by memory and
  model, so vectors from two models can exist side by side while a change
  is made. The passage index of Steps 8–9 follows the same rule (G.5,
  G.5a rule 3).
- **Memory text, provenance and the Memory Browser never depend on the
  embedding model.** Changing the model can require re-embedding, never
  rewriting or losing memories or sources.
- **A gap to close before any change of model files or runtime** (found in
  the Step 2 audit, `memory baseline.md`): the stored vector identity holds
  the model ID, dimensions and document format, but not the runtime version
  or the model files' hashes, and the model download address is not pinned
  to a version. A change of runtime or model files would otherwise mix old
  and new vectors. No stage in M.2 changes either, so this is a requirement
  for that future work, not a current stage.
- **Matching numbers belong to a model** (G.9c, Proposed): another model is
  offered only after its own values are measured on the Step 0 and
  carry-over sets.
- **API embedding models** send memory and message text to a provider; their
  privacy and cost are decided first (G41). Local and API embedding models
  are Later in M.2.
- **An API language model that writes a search query** (context assistance)
  is a different choice from an API embedding model. They are configured
  separately.

## M.6 Saved-memory features carried in from the memory plan

- **Phases 2–6** keep their designs in the memory plan (Part C, Part D) and
  their decisions in Part E. They are built in M.2 order. Before each, the
  owner sees its exact wording, screens and AI-facing text, as Part E
  requires.
- **Database versions.** Phase 3 in the memory plan says "from version 33".
  The database is at version 34, and provenance adds the next version. Each
  stage takes the next version when it is built.
- **Provenance and later phases.** Retired memories keep their sources.
  Fading and Retired never delete sources. Provenance's event date and
  Phase 4's important date are one field (Owner, 2026-11-16), built in
  Phase 4.
- **Step 1.13 is still unbuilt** (checked 2026-11-16): the frozen request
  path (`buildFrozenRegularRequest`) passes no lorebook matches to the
  memory system, so the check that drops a memory repeating a lorebook entry
  runs only on the other path (memory plan A.9). **Proposed (G45):** include
  it in Step 4a, which already verifies lore overlap with carried memories.
- **Step 1.2** is replaced by the Step 0 set and the two carry-over sets.
- **Step 1.12 and B.5's carry-over rule** are replaced by context assistance
  (G.9, G.9b).
- **Steps 1.14–1.16** stay conditional: only if testing shows a need.
- **D41, the memory-use instruction,** stays Proposed. Its exact text is
  approved first.
- **Automatic companion linking (Owner, 2026-11-16).** Creating a companion
  must link it to the memory system automatically; the user never has to
  press a setup button. The "Create Companions From Personas" button and its
  "Setup / Repair" heading were removed from Memory Repair Settings the same
  day. **Checked in the code on 2026-11-16:** `MemoryCompanionSync.onPersonaSaved`
  creates the memory-system companion for a newly saved companion only when
  that button has been used once (`META_BOOTSTRAP_DONE`); otherwise the
  companion is created the first time it is chatted with
  (`ensureCompanionForPersona`, from `Enforcer` and `TranscriptRecorder`).
  Nothing is created before the memory database exists. **To build:** saving
  a companion creates its memory-system record whenever the memory database
  exists, without that flag, and companions saved before the memory database
  existed are linked when it is first set up, without the button. The owner's
  own installation is already linked. The System Status line "Personas linked
  to companions" reports the old flag and is reviewed with this change.
- **D58's open question** (whether a replacing memory keeps the old vibe)
  stays open. Sources are carried by owner direction (M.3); the vibe is a
  separate question.

## M.7 Superseded and corrected instructions

| Earlier instruction | Where | Now |
|---|---|---|
| "Where things stand", sections 0–4, and "Next steps, in order" | Memory plan, top | Historical. Status is M.1; order is M.2. References to `beta/new-client` are obsolete: `main` is the integration branch |
| Carry-over, step 1.12 and B.5's carry-over rule (previous message's memories re-checked against the AI's reply) | Memory plan | Replaced by context assistance (G.9, G.9b; G37) |
| Section 3b "on hold" list | Memory plan | Each item placed in M.6 |
| D.13's build order and the "Phase 1 in pull requests" list | Memory plan | Replaced by M.2 |
| Phase 3's migration "from version 33" | Memory plan | The next free version when built (M.6) |
| Strict execution order table | G.16 | Replaced by M.2. The work orders stay in G.16 |
| "Each Step is one pull request" and "build exactly one numbered Step" | G.16, G.19 | One stage, normally one PR, per assignment (M.0; `CLAUDE.md`) |
| Step 6 needing Steps 0–5 complete | G.16 | Step 6 needs nothing unbuilt; Step 5 is an independent stage |
| API AI "chosen in its own selector" | G.9b, Step 4b | A dedicated configuration screen (M.4) |
| Searching with the previous messages as the carry-over method, and "the previous one or two" messages | G.1 item 18, G.15, G.18a | Measured and not used (G.9a, G37) |
| An optional live AI worker after Step 16 as the only AI help | RAG notes section 4 | The API AI context helper is in the initial rollout (stage 3); the live AI worker stays Later |
| Screen names and placements: Memory Controls, Advanced Memory Settings (Librarian / Embeddings, Debug), Advanced Memory Scanner Settings | This plan before 2026-11-16, memory plan | The 2026-11-16 screens and placements in M.11 |
| Code-based offered only if the held-out evaluation shows a benefit | RAG notes section 1 | Evaluated; the owner included it (G37) |
| Step 3 repair and evaluation "not merged" | RAG notes section 3 | Merged in #205 and #206 |

## M.8 Where the RAG notes went

| RAG notes section | Now |
|---|---|
| 1, context assistance choices and the helper's role | G.9, G.9b, M.4; decisions G37–G39, G44 |
| 1, embedding models are a separate choice | G.9c, M.5; G41 |
| 1A, Memory Browser provenance | M.3; G43 |
| 2, +3 relevance | G.10; G40 |
| 3, finish the evaluation | Done (#206); results in `memory baseline.md` |
| 4, build order and scope | M.0, M.2 |
| 5, still open | G38, G39, G41, G43, G44 |
| 6, repository handling | `CLAUDE.md` |

## M.9 Documents

| Document | Role |
|---|---|
| This plan | Authoritative: order, status, designs, work orders, decisions |
| `memory baseline.md` | Evidence for every saved-memory measurement |
| `conversation_recall_baseline.md` | Created by Step 14 for conversation-recall results (G.16) |
| `Memory System/dynamic_memory_plan.md` | Reference: saved-memory search design and later saved-memory feature designs, D-decisions, research |
| `rag alteration notes November 14.md` | Historical record of owner direction, fully carried into this plan |

Where settings go on screen is M.11.

## M.10 Decisions needed, in the order they are needed

Each is also a row in G.17.

| # | Needed before | Question | Recommendation |
|---|---|---|---|
| G45 | Stage 2 | Include the frozen-path lorebook check (step 1.13) in Step 4a | Yes |
| G38 | Stage 2 | Context-assistance setting (on RAG Active Memory Settings, M.11): label, explanation, starting choice, rebuilding the subject after restart, whether Local AI is shown before it is built | Start on Code-based; do not show Local AI until it exists |
| G44 | Stage 2 | Ship Off and Code-based before API AI is ready | Yes |
| G39 | Stage 3 | The API AI configuration screen (M.4, items 1–13) and the model and cost of its scored run | Each item approved before Step 4b; recommendations are made when Step 4b is prepared |

## M.11 Where settings go (Owner, 2026-11-16)

The owner reorganized the memory screens on 2026-11-16. Every new setting
in this plan is placed by this rule: **choosing an AI model or provider
belongs in AI Settings; how the memory system behaves belongs on its screen
in the Memory Manager; checking and fixing the memory system belongs in
Memory Repair Settings.** A stage that adds a setting puts it on the screen
below. Its exact position on that screen, label and wording are still shown
to the owner first.

**The screens as of 2026-11-16:**

| Screen | Opened from | Holds |
|---|---|---|
| Memory Browser | Memory Manager | Memories, Pending, the memory editor |
| Lorebook Browser | Memory Manager | Lorebooks |
| Memory Scanner Tool | Memory Manager | Running the Memory Scanner (Memory Assistant) |
| Memory Scanner Tool Settings | Memory Manager, Settings section | How scans behave: suggestion limit, token amounts, Minimum Importance, the Associative and Lorebook Memory Prompts |
| RAG Active Memory Settings (formerly Memory Controls) | Memory Manager, Settings section | How memories are searched and sent in chats: Memory Defaults, Memory Engine; Memory Search Test last |
| Memory Repair Settings (formerly Advanced Memory Settings) | Memory Manager, Settings section, last | System Status and Rebuild memory index |
| Memory Scanner Tool API | AI Settings | The Memory Scanner's endpoint, model, provider routing and parameters |
| Embedding Model | AI Settings | Downloading and removing on-device embedding models |
| Memory Backup & Restore | Main Settings | Memory backups, restores, Check Database Integrity |

**Where this plan's settings go:**

| Setting or screen | Screen | Status |
|---|---|---|
| Context-assistance choice (G38) | RAG Active Memory Settings | Placement by the rule; wording open |
| API AI configuration screen (M.4) | Its own screen, opened from AI Settings | Placement by the rule; title and wording open (G39) |
| Conversation Recall setting (G.8b) | RAG Active Memory Settings | Owner decision of 2026-11-11; the screen was renamed |
| Room for recalled text (G.8b) | RAG Active Memory Settings | Moved from Advanced Memory Settings by the 2026-11-16 reorganization |
| Conversation Index section: Index Conversations, status, Delete Conversation Index (G.16a) | RAG Active Memory Settings, above the test rows | Moved from Advanced Memory Settings by the 2026-11-16 reorganization; wording unchanged |
| Conversation Recall Test (G21) | A row beside Memory Search Test at the bottom of RAG Active Memory Settings | Moved from Advanced Memory Settings, Debug, by the 2026-11-16 reorganization |
| Where the user installs a model in no-model notices (G.13b, G31) | AI Settings, Embedding Model | Location updated; the wording is still a proposal |
| Conversation index line on Check Database Integrity (G.13a) | Memory Backup & Restore | Unchanged |
| Provenance link and dates (M.3) | Memory Browser, on the memory | Owner decision of 2026-11-15 |
| Word count and length limit (Step 5) | Every memory editor | Owner decision of 2026-11-12 |
| Memory Handling Preferences (memory plan Phase 2) | Its own screen, opened from a row in the Memory Manager's Settings section | Placement by the rule; position and wording shown first |
| Always, Retired and Dates views (Phases 2–4) | Memory Browser view dropdown | Owner design |
| Other embedding models, local or API (G41) | AI Settings, Embedding Model | Placement by the rule; Later |
| Live AI worker's model (G.11) | AI Settings | Placement by the rule; Later |

# Part G: Context assistance and conversation recall

Written 2026-11-10 as Part G of the memory plan. Order and status are in
Part M.

## G.0 Status and how to read this plan

- **This plan is a proposal.** It was written on 2026-11-10 from the current
  code, the review of #180, and published research. Steps 0–3 are built;
  the status and order of every Step are in M.2.
- **Two labels are used throughout:**
  - **Owner** means the owner said it on the date shown, or on 2026-11-10
    where no date is given. These are decisions.
  - **Proposed** means the agent recommends it. The owner has not approved
    it.
- **Names here are planning names,** not on-screen wording. Every label,
  explanation, and AI-facing text is shown to the owner before it is built.
- **Three kinds of statement, and which one a builder follows:**
  - **A work order** (a numbered Step in G.16, or a stage design in Part M)
    is the instruction. It says what to build, what not to build, and when
    the work is done.
  - **A design section** (G.5 to G.15) explains the work order. Where it
    is marked Proposed, it becomes binding only for the stages whose
    "Approve first" entry in M.2 is satisfied.
  - **A decision** (Owner, or a row in G.17) overrides both.
- **If two parts of this plan disagree, or a Step cannot meet all of its
  own conditions, the builder stops and says which parts.** A builder
  never picks one side quietly.
- **Rows and sections have similar names.** "G10" with no dot is row 10 of
  the decisions table in G.17. "G.10" with a dot is section 10.
- **The design covers the whole system,** including parts built later.
  M.2 decides the build order. It does not leave anything out of the
  design.

Words used in this plan:

- **Embedding model:** the on-phone model that turns text into numbers that
  stand for its meaning, so texts with similar meaning can be found.
- **Exchange:** one message from the user and the reply or replies that
  follow it, up to the user's next message.
- **Index:** a separate list of entries stored beside the chats. Each entry
  points to a place in a conversation. The conversation itself is never cut,
  moved, or rewritten.
- **Index entry:** one searchable piece, about a paragraph long, with labels.
- **Recall:** finding an earlier exchange through the index and adding a copy
  of it to what the AI reads for the current message.
- **Carry-over:** keeping what was relevant to the previous message while
  the conversation stays on that subject.
- **Scope:** the rule for which chats may see something (ordinary, companion,
  project, or roleplay).

## G.1 What the owner asked for (2026-11-10)

1. **Owner:** recall only ever adds. The full chat keeps being sent as it is
   today. Nothing is removed from what the AI receives in order to recall
   something else.
2. **Owner:** the Summarizer is a continuity engine, not a fact compressor.
   It carries the tone and depth of the conversation in the form the user
   chose. This work does not change it, does not use its summaries as raw
   material, and does not replace it.
3. **Owner:** recalled conversation is handed over in its original words.
   Tone is never stripped out.
4. **Owner:** chunking and the index must be designed properly, from
   research, before anything is built.
5. **Owner:** an extra paid AI model is optional. The system must work fully
   without one. The foundation must still be able to support the
   Summarizer's model doing message-level work while the user is chatting.
   The same model can do several jobs; the summarizing job stays its own
   job.
6. **Owner:** keep it cheap. Future AI prices are unknown. The Memory
   Assistant is not to be run live just to build the index.
7. **Owner:** recall within the current chat comes first. Recall from other
   chats is wanted later, and it must be controlled by scope: a roleplay
   chat must not reach companion-only material.
8. **Owner:** users with an embedding model come first. Support for users
   without one can come later.
9. **Owner:** no carry-over work until this plan is solid.
10. **Owner:** rely on published research where it exists. Spend effort on
    our own testing only where research gives no answer.
11. **Owner (2026-11-11):** when a chat is summarized or compacted, recall
    must still be able to bring back the original exchanges, even though
    the chat AI is otherwise given only the summary. This makes up for
    what a summary or compaction leaves out.
12. **Owner (2026-11-11):** existing chats are indexed in the background.
    The user never waits for indexing when opening a chat.
13. **Owner (2026-11-11):** a deeper system for giving the AI access to
    larger documents is wanted some day, not now. The foundation should
    not rule it out.
14. **Owner (2026-11-11):** recall is turned on with one setting in a
    settings menu. It is not something the user turns on in each
    conversation.
15. **Owner (2026-11-11):** conversations can be indexed without an
    embedding model. Index Conversations can be turned on with no model
    installed; the index is then built for keyword search. When a model is
    installed later, meaning search is added to the same index without
    rebuilding it.
16. **Owner (2026-11-12):** conversation recall requires the embedding
    model. This replaces item 15. Lorebook keyword matching is separate,
    is not changed by this work, and keeps working whenever the lorebook
    is on, with or without a model.
17. **Owner (2026-11-12):** where the right decision is not known, use
    what established, proven systems do to decide (G.18a). Custom design
    is acceptable where it is needed.
18. **Owner (2026-11-12):** the agent's recommendations of that day are
    accepted: overlapping pieces for long and dictated messages (G29),
    the simpler cutter, searching with the previous messages, and leaving
    borrowed context out of the first build. **Superseded in part
    (Owner, 2026-11-16):** searching with the previous messages was
    measured and is not used (G.9a, G37).

## G.2 What the review of #180 found

What #180 got right, checked in the code:

- common words no longer match every memory;
- importance is a small nudge and cannot outrank a real match;
- removing the cooldown was correct: the memory text is attached only to
  the message being sent and is never kept in the chat history
  (`ChatActivity.kt`, both send paths);
- the tuning numbers are stored, bounded, and adjustable on the test
  screen;
- the Memory Assistant's own search is untouched.

Gaps that are live on the beta:

- **Follow-up messages lose their memories.** Search uses only the latest
  message. "What should I feed her?" does not find the Pepper memory that
  the previous message found. Carry-over was planned to cover this and is
  not built.
- **Long messages miss memories.** A memory passes on keywords only when it
  covers half of the message's meaningful words, so one name in a long
  message never passes that way. Meaning search weakens too, because a long
  message blends its subjects into one average. Part A.11's own numbers
  show it: 8 missed of 26, up from 5.
- **The original numbers were guesses.** Step 0 now measures the current
  saved-memory search on 86 invented samples with the real q4 model. The
  results are in `memory baseline.md`. The separate conversation-recall
  test set is still built later in Step 6.

Smaller issues:

- "move" does not match "moving", and "run" does not match "running";
- when more than half of a library relates to the message, meaning search
  can reject all of it, because none stands out;
- every message re-reads the words of every memory. Nobody has measured
  this on a phone with thousands of memories;
- one log label says "no model" when the real cause was that the message
  failed to embed;
- meaning search reads only the first 512 tokens of a memory
  (`EmbeddingModels.kt`), about 375 words, so a longer memory is partly
  invisible to it. A memory's tags are placed last in the text the model
  reads (`RetrievalDocument.kt`), so on a long memory they are cut off
  too. The model itself can read 2,048 tokens, about 1,500 words. No
  reason for 512 is recorded in the code. See "The reading limit" below;
- the embedding model must run one request at a time, and `Librarian.kt`
  has a lock for that (`embedLocked`). The live chat search embeds the
  user's message without the lock (`searchInternal`), so it can run at the
  same moment as a background repair. Found in review on 2026-11-10; Step 1
  fixes it under G.5a rule 6.

**The reading limit, measured on 2026-11-12.** The model file the app
downloads (the q4 variant) was run directly, outside the app, on a cloud
machine. Phone times will be slower and are not measured yet.

| Text length | Result | Time on the cloud machine |
|---|---|---|
| 32 tokens | Read | 35 ms |
| 128 tokens | Read | 123 ms |
| 512 tokens (today's limit) | Read | 478 ms |
| 1,024 tokens | Read | 1,060 ms |
| 2,048 tokens | Read | 2,532 ms |
| 2,049 tokens | Refused by the model | |

- The model file accepts any length up to 2,048 tokens. The 512 limit is
  set in the app's own code, not by the model.
- Time follows the real length of the text. A short text costs the same
  whatever the limit is set to.
- **Proposed (G33):** raise the limit to 2,048 for stored text, meaning
  saved memories. They are read in the background, never while a message
  is being sent. The message being sent keeps a short whole-message pass
  and is covered by pieces (G.10), because one pass over a very long
  message is slow and blurs its details.
- **Owner (2026-11-12):** a memory the model cannot read in full must not
  be allowed silently. Memories are limited to what can be read, and the
  user is shown how long a memory is (G34).

## G.3 The whole system at a glance

| Part | What it does | Exists today? | Cost to the user |
|---|---|---|---|
| Conversation store | Keeps every chat whole and in order | Yes. Untouched | None |
| Chunking | Cuts each message into passages the embedding model can read in full (G.5b) | No | None |
| Index entries | One entry per passage, pointing to the whole exchange, with labels (G.5) | No. Useful parts exist (G.4) | None |
| Retrieval | Finds the right passages by meaning and by keywords, and rejects what is not relevant (G.6) | Parts exist for saved memories and for chat search | None |
| Scope walls | Decide which entries a chat may see | Yes, for saved memories | None |
| Context assembly | Chooses which exchanges are sent and writes them, labelled, beside the newest message (G.8) | Partly. Saved memories are placed this way | More text per message, up to recall's own size limit (G.8). With the starting values, up to 3 earlier exchanges |
| Carry-over | Keeps what was relevant while the subject continues | No | None |
| Live AI worker | Optional. The Summarizer's model does message-level work during a chat | No. The model connection exists | Paid, only when turned on |
| Upkeep | Keeps the index correct after edits, deletions, restores | Yes, for chat search | None |
| Testing | Saved-memory and conversation-recall test sets, plus the Memory Search Test screen | Saved-memory baseline and screen yes. Conversation-recall set no | None |

Recall from other chats is not a separate part. It is the same index and
search, with the scope walls deciding how far the search may look.

## G.4 What the app already has, and what it means for this work

Every point was read in the current code (2026-11-10 and 2026-11-11). A
builder must re-read the named files before relying on a point, because
code changes.

**Where chats are stored.**
- Two stores exist. Today every installation uses the older one: each chat
  is one encrypted file holding all its messages. A newer encrypted
  database with one row per message (`chatstore/ChatStore.kt`) is built
  but not switched on yet (`chatstore/ChatStorageAuthority.kt`).
- **Consequence:** the index must read chats through the app's existing
  chat-reading layer and identify a message only by its permanent message
  ID. It must never depend on which store is in use, on a database row
  number, or on a message's position in a file.

**How messages and chats are identified.**
- Every message has a permanent ID that survives edits, regenerated
  replies, and renames (`MessageIdentity.kt`). New messages get one when
  written. Older chats get theirs the first time they are opened.
- A chat has an ID too. For older chats that never had a stored ID, the ID
  is derived from the chat's title, so **renaming such a chat changes its
  chat ID** (`ChatPreferences.storedChatId`, `ChatRenameTransaction.kt`).
- **Consequence:** a rename must move the chat's index entries to the new
  chat ID (G.12). An index that ignores this would orphan the entries.

**What a stored message holds.**
- Its text, who wrote it, a time (missing on some old messages), and for
  AI replies a completion state: still streaming, done, or failed
  (`MessageCompletionState.kt`). A failed reply's error text is stored
  apart from the reply's words.
- A reply can have several versions (the stored fields `variants`,
  `displayVariant`, `canonicalVariant`).
- A message can be hidden from the AI (`MessageVisibility.kt`).
- Attachments are separate from the user's typed words.

**What the AI is actually sent for each message.**
- The Summarizer already builds, for every stored message, its permanent
  ID and its "model-facing" text: the exact words the AI would be sent, or
  nothing for a message that is not sent (`SummarizerController.Snapshot`,
  `Entry`).
- The chat search feature builds a different list for the user's own
  search (`SearchableMessageProjection.kt`). It skips unfinished replies
  but does **not** skip hidden messages.
- **Consequence:** the index is built from the model-facing text, not from
  the chat search list. What may be recalled is exactly what the AI could
  have been sent.

**The existing chat search index.**
- One entry per message, keywords only, in its own encrypted database
  (`chatsearch/ChatSearchStore.kt`).
- It is kept in step by `ChatSearchIndexManager.kt`: a chat is refreshed
  after it is saved, deleted chats are removed, a restore triggers a
  rebuild, and stale results are refused using a per-chat revision and a
  journal (`ChatSearchIndexJournal.kt`).
- When a chat changes, it **replaces every entry of that chat**. That is
  cheap for keywords. It would be far too slow for passages, which need
  cutting and embedding.
- **Consequence:** the triggers and the revision method are reused. The
  "replace the whole chat" method is not. Passages are updated one message
  at a time (G.5a rule 4).

**The embedding model.**
- EmbeddingGemma, in a q4 or int8 variant the user picks, producing 256
  numbers per text (`EmbeddingModels.kt`). It reads at most 512 tokens per
  text; the model itself allows 2,048.
- Text is given an instruction prefix before it is read
  (`OnnxEmbeddingModel.kt`): one prefix for a search, another for a stored
  text. The stored-text prefix has a title slot that is always "none"
  today.
- The tokenizer (`HfTokenizer.kt`) cuts off anything past the limit without
  saying so, and has no way to report how many tokens a text needs.
- The model must run one request at a time. `Librarian.kt` has a lock for
  that, and one call skips it (G.2).
- The model is downloaded by the user. It can be absent, and it can be
  changed.
- **Consequences:** a token-counting function must be added, and
  the cutter uses it (G.5b cutter rule 4). Vectors must record which model made
  them.

**Saved-memory search.** Meaning and keyword search with a relevance gate
(`LiveRetrieval.kt`, `KeywordText.kt`). It is written for saved memories.
Its keyword word rules are for English.

**Scope.**
- Saved memories are filtered by scope inside the database query
  (`RetrievalScope`, `MemoryStore.activeMemoriesForScope`).
- When a turn finishes, the chat's companion, world, campaign, character,
  and project at that moment are recorded with the turn, together with
  the message IDs, for the Memory Assistant (`TranscriptRecorder.recordTurn`,
  called from one place in `ChatActivity.kt`). This happens only when the
  memory system is set up, and the record groups several turns in one row.
- **Consequence:** the index stamps scope itself, at that same point, from
  the same values (G.7). The turn record is a source for older messages
  only where it exists.

**An exchange already has a definition.** A finished turn is one user
message and the reply to it. The Summarizer also never separates a message
from its reply (`SummarySections.kt`).

**Restore and merge.** A backup can replace the phone's chats or be merged
into them (`ChatMergePlanner.kt`). A restore changes a "generation" value
that the chat search index watches to know it must rebuild
(`ChatSetSourceGeneration`).

**Existing text splitters.** `ConversationTokenChunks.kt` splits text at
sentence ends for compaction and the Memory Assistant, using an estimate of
tokens, and deliberately leaves an oversized sentence whole.
`CompactionBatches.kt` groups exchanges into large batches. Neither fits
this work: one estimates, and both are built for a different size and
purpose. They are not changed.

**Other parts reused later:** placement of added text just before the
newest message, the Memory Space limit, the request-size check, the record
of what was sent with the previous message, the Summarizer's model
connection, and the Memory Search Test screen.

**Summary.** Neither existing index is the one this plan describes. The
chat search index holds one keyword entry per message and includes hidden
messages. The vector store holds saved memories only. The passages, their
vectors, their scope labels, the cutter, and recall are new.

## G.5 Index entries (Proposed)

**What one entry is.** An entry, called a passage, covers a stretch of one
message: usually a few short paragraphs or one long one. It points to its
message and its exchange. A short message is one passage. A long message
becomes several, so a small detail has a passage of its own and can be
found.

**Two texts for every passage.** This distinction runs through the whole
design:

- **The original text** is the stretch of the message itself. It is never
  changed. It is what the AI is given.
- **The search text** is what is handed to the embedding model to find the
  passage. It is the original text plus, where it helps, a little
  surrounding context (G.5b). It is never shown to the user and never sent
  to the chat AI.

**What a passage records:**

| Field | Why |
|---|---|
| Source kind | Today always "chat message". Lets a later document system share the stores |
| Chat ID | Which conversation. Lets search stay in one chat or cross chats later |
| Message ID (permanent) | Points back to the original message |
| Start and end inside the message text | Exactly which characters the passage covers |
| Order within the message | First, second, third passage of that message |
| Exchange it belongs to | The user message ID that opens the exchange |
| Previous and next passage | So nearby context can be added on a match |
| Who wrote it | The user or the AI |
| Time written, and position in the chat | Time for dates. Position because some old messages have no time |
| Fingerprint of the original text | Detects edits |
| Fingerprint of the search text | Finds the right vector |
| Messages the search text borrowed from | Not in the first build (G.5b cutter rule 6). Needed only if borrowed context is built later |
| Scope labels | Companion, project, world, campaign, character, or "unknown" (G.7) |
| Cutter version | Which version of the cutting rules made it (G.5b) |
| Vector state | Waiting, made, or failed, and for which model |

AI notes (G.11) are separate rows that point to a passage or an exchange.
They are never mixed into a passage.

**What is indexed.**
- The model-facing text of each message: what the AI would be sent (G.4).
- Only finished replies. A reply that is still streaming, or that failed,
  is not indexed. A failed reply's error text is never indexed.
- Only the reply version the AI is sent. Which stored field that is must be
  confirmed in the code during Steps 7–13.
- Never a message hidden from the AI (G.5a rule 1).

**Attached documents and images are not indexed in this work (Owner,
2026-11-11).** An attached document is sent to the AI with every message
until the user condenses it, reduces it, or removes it
(`document-includes-plan.md`), so the AI already has it in full. Once the
user condenses or removes a document, recall does not bring the removed
text back. The owner noted that some documents may deserve to become saved
memories; that belongs to the Memory Assistant and is not part of this
plan.

**Small passages, whole delivery.** Search runs on passages. What the AI
receives is the whole exchange the passage belongs to, or a marked excerpt
when the exchange is too large (G.8).

**Storage design.** Four linked stores, in an encrypted database on the
phone, separate from the stored chats and from the saved-memory database:

| Store | One row is | Key | Points to |
|---|---|---|---|
| Passages | One passage | chat ID, message ID, order within the message | The message, by its permanent ID |
| Passage vectors | One vector | search-text fingerprint, model, vector size | Nothing. Any passage with that fingerprint uses it |
| Passage keywords | The searchable words of one passage | the passage row | The passage |
| Index state | One row per chat | chat ID | Which saved version of the chat the index describes, how many passages wait for a vector, and whether the chat is fully indexed |

- The stored chat stays the only home of the conversation. A passage holds
  positions, not a copy of the message.
- The keyword store does have to hold the passage's words to be searchable,
  as the chat search index already does. It is encrypted, derived, and
  removed with its passage. Where the database allows it, only the word
  index is kept, not a readable copy of the text.
- A vector is keyed by the search text's fingerprint, not by the passage
  row. Making a vector is the slow step, so the same search text is never
  embedded twice, including after passages are rebuilt.
- Passages, keywords, and index state can be dropped and rebuilt from the
  chats at any time. Vectors are kept through a rebuild.

**Reused as it is:** permanent message IDs; the Summarizer's model-facing
text for each message; the embedding model and vector maths; the keyword
word rules; the chat index's triggers, revision check, and journal method.

**Extended:** the embedding model gains a token count. The scoring in
`LiveRetrieval.kt` is generalized so it can score passages as well as
saved memories, without changing any saved-memory result. Every use of the
embedding model moves onto one queue (G.5a rule 6).

**New:** the four stores, the passage cutter (G.5b), background embedding
of passages, and recall.

**Not changed:** the stored chats, the chat search feature and its index,
the saved-memory vector table, the Summarizer, and the existing text
splitters.

## G.5a Rules the index must guarantee (Proposed)

Added after outside reviews and an audit of the code on 2026-11-10 and
2026-11-11. Each point was checked in the code. This section, with G.5,
G.5b, and G.7, is the design of the conversation index. It is Proposed
until the owner approves it as a whole (G35), which is needed before
Step 7. Once approved, these are requirements for Steps 7–13, not options.

**1. Only what the AI may be sent can be recalled.**
- The app lets the user hide a message from the AI
  (`MessageVisibility.kt`). Everything that sends conversation to a model
  must skip it. The chat search index does not apply that rule, so it is
  not reused for recall as it is.
- Rule: a message is eligible only when it is visible to the AI, finished,
  and the version the AI is sent. Eligibility is checked twice: when
  indexing, and again at the moment of recall against the stored message.
  Hiding a message therefore takes effect at once, even when background
  indexing has not caught up. The same holds for a deleted message.
- No search text and no vector may contain words from a message the AI
  may not be sent. In the first build a passage's search text holds words
  from its own message only, because borrowed context is not built (G.5b
  cutter rule 6).
- **Text is never taken by position without a check.** A passage records
  positions inside the message's text, not the text itself. At the moment
  of recall, the stored message's text fingerprint is compared with the
  passage's. If they differ, the passage is not used and the chat is
  marked for a refresh. A change in how message text is prepared can
  therefore never cut an excerpt in the wrong place.
- A test proves that the set of messages the index may recall is the same
  as the set the chat request may send.

**2. No text is ever cut off silently.**
- The tokenizer drops everything past the model's limit without saying so
  (`HfTokenizer.encode`).
- Rule: before a vector is made, the search text is measured with the real
  tokenizer, counting the instruction prefix and special tokens. The
  cutter already cut to real sizes (G.5b cutter rule 4), so this is a safety
  check: a search text that does not fit is recorded as failed, never
  embedded cut off. A passage is recorded as embedded only when all of its
  search text was read.

**3. Vectors have an owner and a lifetime.**
- A vector is reused only when the exact text handed to the embedding
  model (the search text, with its speaker label), the embedding model,
  and the vector size all match. The cutter version is not part of this:
  when the cutting rules change and a passage's search text comes out the
  same, its vector is kept (G.5b, Versions).
- When the last passage that uses a vector is deleted or changed, the
  vector is removed.
- Vectors made by a model that is no longer selected are removed once the
  new model's vectors are complete.
- Deleting a chat removes everything derived from it, including vectors no
  other chat uses.
- The user must be able to delete the whole index. Its wording and place
  are recorded in G.16a.

**4. An index update is correct or it does not happen.**
- Passages are updated **one message at a time**, never by replacing the
  whole chat. When a chat is saved, its messages are compared with the
  index by permanent ID and original-text fingerprint. Only messages that
  are new, changed, or gone are touched.
- The passages and keywords of one message are replaced together, in one
  database transaction, against a recorded version of the chat. Before
  publishing, the version is checked again. A background job that finishes
  after the chat changed throws its result away.
- A vector that arrives for search text that no longer exists is
  discarded.
- A failure leaves the previous correct state in place and the chat marked
  as needing a refresh. It never leaves half-updated entries.

**5. Search stays fast as conversations grow.**
- Saved-memory search compares the message against every vector, which its
  own notes say suits up to about 50,000 memories (`Librarian.kt`).
  Conversation passages will outnumber saved memories many times over.
- Rule for the first conversation-recall build, Steps 7–16: meaning search looks only at the current chat's
  passages. Keyword search uses the database's own keyword index. Vectors
  are read in bounded batches, never all chats at once.
- Memory use is bounded too: as each batch is scored, only the best
  candidates so far are kept and the batch is released.
- Passage counts, indexing progress, and search time are recorded so they
  can be shown and measured.
- Recall from other chats needs a different search method for large
  collections. That choice is made when that later work is designed, and the
  storage is shaped so it can be added without rebuilding.
- Size, for reference: one vector is about 1 KB, so 100,000 passages are
  about 100 MB before overhead. Smaller vector storage is measured during Steps 7–13.

**6. One path runs the embedding model.**
- Rule: every use of the model goes through one queue: the live message,
  saved memories, and conversation indexing. A live chat request goes
  ahead of every background job that is waiting.
- The model reads one text at a time, so a live message that arrives
  while a background text is being read waits for that one read. It never
  waits for more than one. Background work is done one text at a time for
  that reason.
- That wait is measured (Step 1). One read of a full-length saved memory
  took 2.5 seconds on a cloud machine and will be slower on a phone. If
  the wait is long enough to feel, the background read is stopped when a
  live message arrives and done again afterwards, where the model's
  runtime allows a read to be stopped.
- This also fixes the unlocked call noted in G.2.

**7. Only messages with a permanent ID are indexed.**
- Every new message receives a permanent ID, and an older chat's messages
  receive theirs the first time that chat is opened. So the chat a user is
  in always has IDs, and recall inside the current chat loses nothing.
- **Owner (2026-11-11):** no migration is built to give IDs to old chats
  that have never been reopened. Those chats are simply not indexed until
  the user opens them.

**8. Restored and merged chats are checked, never assumed.**
- **Owner (2026-11-11):** after a restore, every message must be checked
  for whether it is already indexed. A user may also combine the history
  of two phones.
- Rule: after any restore or merge, each affected chat is compared with
  its index state, message by message, using the permanent message ID and
  the text fingerprint:
  - already indexed and unchanged: left alone, and its vector is reused;
  - new to this phone: indexed;
  - same ID but different text: re-indexed, and the old entries removed;
  - indexed here but no longer in the chat: its entries are removed.
- A message is never indexed twice, and a merged chat never ends up with
  entries from both phones for the same message.

**9. The index never depends on how chats are stored.**
- Two chat stores exist and the app will move from one to the other
  (G.4). The index reads through the app's chat-reading layer and keys on
  permanent IDs only, so the move needs no change to the index.
- When a rename changes a chat's ID, its entries move to the new ID in the
  same step, or the chat is marked for a refresh.

**10. Indexing never harms the chat.**
- Indexing runs off the main thread, never delays typing, scrolling,
  sending, or opening a chat, and never blocks a save.
- If the index is missing, damaged, or behind, the chat is sent exactly as
  it is today.
- An index failure is never shown as a chat error.

**The indexing steps for one new or changed message:**

1. Check that it may be recalled: visible to the AI, finished, the version
   the AI is sent, and holding a permanent ID.
2. Take its model-facing text and cut it into passages (G.5b), recording
   exactly where each sits in the message.
3. Stamp scope and the exchange it belongs to.
4. Replace that message's passages and keywords in one transaction.
5. Build each passage's search text. Reuse any vector that already
   matches. Queue the rest.
6. Make queued vectors in the background, behind any live request,
   measuring each with the real tokenizer first.
7. Mark the chat's index state complete when nothing is waiting.

## G.5b Chunking: how a message is cut into passages (Proposed)

Chunking decides what each index entry means, so it is settled before the
index is built. It runs on the phone, costs the user nothing, and uses no
AI model beyond the embedding model.

**What the research supports.** A simple rule based on paragraphs, lines,
and words. A study of automatic "semantic chunking" found it did no better
than plain sizes on realistic text, and splitting by topic only helped when
a capable paid model did it (G.18). So the cutter is a plain rule, made
careful, not a clever one.

**The goals, in order.**
1. Every character of the message is in exactly one passage.
2. Every passage can be read in full by the embedding model.
3. A passage is large enough to mean something on its own.
4. A small detail is not buried in a passage about something else.
5. Nothing that belongs together is lost at a boundary.

**Cutter rule 1: split the message into natural pieces.** In this order:
paragraphs (a blank line); then lines, which keeps code, lists, and tables
intact; then between words; and last, between two characters, never
through the middle of one. A piece is only split further when it is too
large for a passage.

Periods are not used as cut points. Speech-to-text leaves them out of
whole paragraphs and also puts them in the middle of a thought (Owner,
2026-11-12). SillyTavern and LangChain's standard splitter cut the same
way: paragraph, line, then between words (G.18a).

**Cutter rule 2: join short neighbouring pieces.** Short pieces next to each other
in the same message are joined, in order, until the passage reaches the
target size. Four one-line paragraphs become one passage, not four vague
ones. Pieces are never joined across two messages.

**Cutter rule 3: respect the two sizes.**
- **The target size** is what the cutter aims for. Starting value: about
  256 tokens, the figure most often used in published work. A smaller
  target finds small details better and a larger one carries more meaning;
  the right value for this app is measured in Steps 14–15 and is adjustable.
- **The hard limit** is the embedding model's limit for stored text (512
  tokens today, 2,048 after Step 1), counting the instruction prefix and
  special tokens. No passage's search
  text may exceed it.

**Cutter rule 4: sizes are counted with the model's own counter.**
- Indexing is only on when an embedding model is installed (G30). So the
  cutter is given that model's real token counter (Step 1) and cuts to
  real sizes the first time.
- An earlier version estimated sizes and cut a message again when the
  estimate proved wrong. That was removed on 2026-11-12: it meant reading
  and decrypting the chat a second time, and on text with no spaces (a
  long link, packed code) the estimate could stay wrong however small the
  target became.
- A stretch with no spaces that is still too large is cut between
  characters until it fits.
- Before a vector is made, the search text is counted once more as a
  safety check. One that does not fit is recorded as failed (G.13a). It is
  never embedded cut off, and never retried in a loop.
- When the user changes to a model with a different limit or a different
  way of counting, chats are re-cut in the background (G.12).

**Cutter rule 5: carry meaning across boundaries.** Two protections, both wanted:
- **Overlap in the search text only.** The search text of a passage begins
  with the last words of the passage before it, up to a set number, when
  both are in the same message and it fits. The original text is not
  overlapped, so no character is delivered twice.
- **Neighbour links.** Each passage records the one before and after it, so
  recall can add the neighbour when a match cannot be understood alone.

**Cutter rule 6 is not in the first build (2026-11-12).** No established chat
app adds a neighbouring message's words to a short reply before indexing
it (G.18a), and it causes most of the upkeep work in this plan: every
hide, edit, deletion, or scope change has to rebuild its neighbours. Cutter
rule 6 and the borrowed-context rules below stay here as the design to use if
the Steps 14–15 measurements show short replies are being missed. Until then,
a short reply is found through its exchange, and the only overlap is
between passages of the same message (cutter rule 5).

**Cutter rule 6: give short replies their question.** "It needs to survive. Don't
clear the composer." means little alone. When a passage is short, its
search text also includes the start of the message it answers: for an AI
reply, the user's message; for a short user message, the end of the AI
reply before it. This uses no AI call. The context is only in the search
text; the original text and what the AI receives are unchanged.
- It applies only below a size threshold. Adding context to a long passage
  would blur it.
- Because the search text then depends on the neighbouring message, an
  edit to that message also remakes the search text of the passages that
  borrowed from it.

**Borrowed context obeys the same rules as the passage itself.** This
covers the overlap in cutter rule 5 and the context in cutter rule 6.
- Text is borrowed only from a message that is itself eligible: visible to
  the AI, finished, the version the AI is sent, and not deleted. A passage
  can be eligible while the message beside it is not; then nothing is
  borrowed from that message.
- Text is borrowed only from a message with the same scope. If the chat's
  companion, project, or roleplay settings changed between the two
  messages, nothing is borrowed across that change.
- When a message is hidden, edited, deleted, or changes scope, every
  passage that borrowed from it has its search text rebuilt without the
  old text, and its vector remade. The old vector is removed under G.5a
  rule 3.
- Until that rebuild finishes, a passage whose borrowed context is no
  longer allowed is not matched by its old vector. It can still be found
  by its own words.
- Each passage records which messages its search text borrowed from, so
  these passages can be found at once when one of those messages changes.

**Cutter rule 7: record who is speaking.** The search text states whether the
user or the AI said it, using the embedding model's own title slot, so
"I moved to Leeds" from the user is not confused with the AI saying it.
Whether this improves results is measured in Steps 14–15; it is dropped if it
does not.

**Very short messages.** "ok", "thanks", and "exactly" have no meaning to
search for. Each is still a passage, so every character is covered, and it
is found through its exchange. On its own it has no informative words and
cannot pass the relevance gate.

**Versions.** The cutting rules have a version number, stored on every
passage. When the rules or the target size change, chats are re-cut in the
background and the old passages are replaced. Unchanged search text keeps
its vector.

**Languages.** Word boundaries are found with the platform's
own language-aware tools, so cutting works in any language. The keyword
word rules are written for English; other languages rely more on meaning
search. That limit is stated, not hidden.

**What the cutter never does:** change a message; drop a character; join
two messages; call a paid model; or leave a passage the embedding model
cannot read in full.

**Tests, and the Step each belongs to.**

With the cutter, in Step 7:
- joining every passage of a message, in order, gives back the message
  exactly, for ordinary text, very long text, text with no spaces, text
  with no punctuation, code, tables, emoji, and non-English text;
- no passage is larger than the size it was asked for, using the size
  function it was given;
- passages come out in order and never overlap;
- short neighbouring paragraphs share a passage;
- a detail in the middle of a long message has a passage that contains it;
- the same message always cuts the same way.

With the search text, in Step 8:
- no search text exceeds the model's limit, measured with the real
  tokenizer;
- no search text contains words from any message other than its own
  (borrowed context is not in the first build, cutter rule 6);
- the overlap holds only words from the passage before it in the same
  message.

Before a vector is made, in Step 9:
- a search text that does not fit is recorded as failed and is never read
  cut off.

## G.6 Retrieval: how the right conversation is found (Proposed)

A good index can still give bad recall if this part is loose. It is
specified here step by step. The numbers named are starting values,
measured and adjusted in Steps 14–15. They are engineering numbers; the owner
is not asked to choose them.

**What the existing code offers, and what is not copied.**
- Saved-memory search (`LiveRetrieval.kt`) scores every memory in the app's
  own code, re-reading every memory's words for each message. That suits a
  few hundred short memories. It is not copied for conversation passages,
  which number in the thousands and are longer.
- The chat search database already ranks keyword matches with the
  database's own keyword ranking (`ChatSearchStore.kt`). Passage keyword
  search uses that same database feature.
- Chat search orders results for a person browsing: titles first, then
  exact words, then newest. That ordering is for the search screen and is
  not used for recall.
- The ideas kept from saved-memory search are the relevance gate, "nothing
  qualifies" as a real result, and the rule that common words never count.

**Retrieval stage 1: decide what to search with.**
- The user's latest message, on its own.
- A message longer than one piece is also searched in overlapping pieces
  (G.10), up to a limit. Periods play no part.
- Follow-ups are handled by the chosen context assistance (G.9b): what
  it supplies is searched as well and counts for less than the newest
  message. Earlier messages are never glued onto the search text. The
  details for recall are part of G36.
- Each of these is a separate search. A passage qualifies when it passes
  for any one of them.

**Retrieval stage 2: read a time reference, when there is one.**
- "Yesterday", "last week", "on Tuesday", "in March", or a written date is
  turned into a date range by plain rules, with no AI call.
- A recognized range favours passages written inside it. It does not
  exclude others, so a misread date cannot hide the right answer.
- Only clear phrases are read. Anything unclear is ignored, and time then
  plays no part. Published results show date-aware search helps time
  questions, and that a weak reader of dates does not (G.18), so this is
  kept simple and is measured before it is relied on.
- Time comes from the message's stored time. A passage with no stored time
  is not affected by a date range.

**Retrieval stage 3: gather candidates two ways, inside the current chat.**
- **Keywords:** the database's keyword index returns the best-matching
  passages for the message's informative words. Common words are dropped
  first, by the same rule saved-memory search uses.
- **Meaning:** the message's vector is compared with the chat's passage
  vectors, in bounded batches, keeping only the best so far (G.5a rule 5).
- A passage with no vector yet is not a candidate until its vector is
  made (G30).
- Each way returns a bounded list. Starting value: the best 50 of each.

**Retrieval stage 4: the relevance gate. Being the best match is not the same as being
relevant.** A candidate passes only when one of these is true:
- **Meaning:** its similarity clears a fixed minimum **and** stands clearly
  above this chat's typical similarity for this message. A chat about one
  project has a high typical similarity, so only a passage that is closer
  than the general run of the chat passes.
  - "Typical" is worked out over **every passage of the chat** as its
    vectors are read, never over the 50 best. Measuring it over the best
    matches would raise the bar and reject good ones.
  - **A certain match always passes.** When a chat is about one subject
    throughout, nothing stands out, and the rule above would reject
    everything. That is the fault G.2 lists for saved-memory search. So a
    passage whose similarity clears a second, higher bar passes whether
    or not it stands out. That bar is measured in Steps 14–15.
- **Keywords:** it contains enough of the message's informative words,
  weighted by how rare each word is in this chat. One accidental shared
  word in a long message does not pass.

When nothing passes, nothing is recalled. Empty is a correct result.

**Two bars, not one.** Candidates are sorted into three groups:
- **clearly relevant:** passes the gate above;
- **borderline:** misses the gate narrowly, by a set margin;
- **not relevant:** everything else, dropped here.

Without a re-ranker, only the clearly relevant go forward and the
borderline are dropped, so behaviour is exactly the gate above. The
borderline group exists so that a better judge, when one is present, can
look again at a near miss (retrieval stage 5a).

The gate uses absolute measures on purpose. A method that only compares
ranks cannot say "nothing here is relevant", because something is always
ranked first.

**Retrieval stage 5: combine the two ways into one order.**
- Each passing candidate gets one relevance score: a weighted blend of its
  meaning score and its keyword score, each first put on the same 0 to 1
  scale. Starting weights: 0.7 meaning, 0.3 keywords, the split reported
  for Redis's tested system.
- Why a blend and not rank fusion. Reciprocal rank fusion, which merges
  two ranked lists by position, is widely recommended because it needs no
  tuning. A published comparison found the weighted blend more accurate,
  both on familiar and unfamiliar text, less sensitive to its settings, and
  tunable from a handful of examples (G.18). The blend also keeps real
  scores, which the gate and the size decisions need.
- Rank fusion is run alongside during the tests in Steps 14–15 as a comparison. If it
  does better on this app's conversations, it replaces the blend for
  ordering. The gate stays either way.
- A passage found both ways usually scores higher than one found one way,
  but the blend does not guarantee it: a strong match one way can
  outscore a weak match both ways. No bonus is added to force it.

**How each score is put on the 0 to 1 scale.** The two scores are not
comparable as they come, so each is converted by a fixed rule, never by
comparing against the other candidates in the list (which would make the
best candidate look perfect even when it is poor):
- **Meaning score:** 0 at the fixed minimum similarity, 1 at a "full
  strength" similarity, in a straight line between, and capped at both
  ends. Both similarities are settings for the embedding model in use.
- **Keyword score:** the share of the message's informative words the
  passage contains, weighted by rarity. It is already between 0 and 1.
  The database's raw keyword ranking number is used only to pick
  candidates, never as the score, because it cannot be compared from one
  message to the next.

**When a passage has no vector yet.** It is not recalled until its vector
is made (G30). The inspector shows it as waiting, so a missing result is
never mistaken for a poor match.

**Retrieval stage 5a: an optional re-ranker (a place is kept for it; not built
yet).**
- A re-ranker is a second model that reads the user's message and one
  candidate passage **together** and judges how well they fit. The
  embedding model never does that: it turns each text into numbers
  separately and compares the numbers. A re-ranker is slower and usually
  more accurate.
- It does not have to be an outside or paid model. It can run on the
  phone, downloaded by the user like the embedding model.
- **What is known.** The memory plan (A.10) records one test: a 23 MB
  on-phone re-ranker trained on web search did poorly on short saved
  memories. That test used a handful of invented memories and one model,
  and it was not run on conversation passages, which are longer and closer
  to the text such models are trained on. So it does not settle the
  question for this work. Published results do show a re-ranker helping:
  in Anthropic's test it took missed retrievals from 49% fewer to 67%
  fewer (G.18).
- **What it costs.** A download of tens to hundreds of megabytes, and
  time: it runs once per candidate. One small model is reported at about
  39 pairs a second on a desktop processor, so on a phone, re-ranking 20
  candidates could add a second or more to sending.
- **The design.** A re-ranker, when present, runs here, on a bounded
  number of the best candidates: the clearly relevant **and the
  borderline** (retrieval stage 4). It reads each with the message and makes the
  final relevance decision. It can reorder, it can reject a candidate the
  first search liked, and it can accept a borderline candidate the first
  search narrowly missed.
- It never sees the "not relevant" group, so it cannot bring back
  something far off, and its work stays bounded.
- "Nothing is relevant" remains a possible answer after re-ranking.
- With no re-ranker, this retrieval stage is skipped: the clearly relevant go
  forward and the borderline are dropped. Nothing else changes.
- **When it is decided.** After Steps 14–15 show what retrieval gets wrong on
  this app's conversations. If the mistakes are ones a re-ranker fixes,
  candidate models are tested on those same conversations, on the phone,
  and the owner decides whether to offer it as an optional download.

**Retrieval stage 6: group passages into exchanges.**
- Passages are grouped by the exchange they belong to. An exchange's score
  is its best passage's score, with a small addition when several of its
  passages matched.
- Several matching paragraphs of one exchange therefore count once and
  cannot crowd out other exchanges.

**Retrieval stage 7: remove what should not be recalled.**
- An exchange the AI is already reading close to the newest message: the
  messages the Summarizer keeps in full, or the last few exchanges when
  the whole chat is sent.
- An exchange containing a message that is no longer eligible, checked
  against the stored message at this moment (G.5a rule 1).
- The current message itself.

**Retrieval stage 8: avoid near-repeats.**
- When two exchanges say nearly the same thing, the higher-scoring one is
  kept and the other dropped, so the limit is not spent on repeats.
- Two exchanges that **differ** on the same subject are both kept.

**Retrieval stage 9 is not in the first build (2026-11-12).** No established system
does it (G.18a), and review found two faults in it as written:
- its extra exchange was not put through retrieval stage 7, so it could add an
  exchange the AI is already reading, or one that is no longer eligible;
- it compares one stored passage with another and applies the gate from
  retrieval stage 4. That gate is set for comparing a message with a passage. The
  embedding model prepares a message and a stored text differently, so
  the two kinds of comparison give different numbers and need separate
  bars.

Steps 14–15 measure how often a changed decision is missed without it. If it
is built, its candidates go through retrieval stage 7 and it gets bars of its own.
The text below is kept as that design.

**Retrieval stage 9: follow a subject to its later turns.** A decision is often made
in one place and changed in another: "Let's use model A for summaries",
then much later, "Actually, switch the summarizer to model B". A question
about it may match only one of the two. Keeping both when both happen to
be found is not enough, so retrieval looks for the other on purpose:
- For each exchange that will be offered, the chat is searched once more
  using that exchange's own matched passage as the search text. This
  finds other exchanges on the same subject, however the user's question
  was worded.
- From those, the **most recent** one that clears the relevance gate
  against the passage, and is not a near-repeat of it, is added as a
  related exchange. Most recent, because the latest word on a subject is
  what the AI most needs.
- At most one related exchange is added for each offered exchange, and it
  counts against recall's size limit.
- Both are delivered in date order with their dates (G.8), so the AI sees
  what was said first and what was said later.
- **What this is and is not.** It is a rule that actively looks for later
  turns on the same subject. It is not a guarantee: it finds what the
  embedding model and keywords judge to be the same subject, and a change
  worded very differently can be missed. Its hit rate is measured in
  Steps 14–15 on the changed-decision test conversations, and reported.
- It uses vectors already stored, so it adds a comparison pass and no new
  embedding work.

**What retrieval hands on.** A short list of exchanges, best first, each
with its message IDs, which passages matched and where, its scores, the
matched words, and its date. Nothing is written into the request at this
point. G.8 decides what is sent.

**What retrieval must never do:** return something because the list was
empty; search another chat (until that later work is built); read a hidden,
deleted, or unfinished message; make a paid call; or delay sending beyond
the time measured and accepted in Steps 14–15.

**A time limit.** Retrieval has a fixed time allowance, set from the
Steps 14–15 measurements. Recall is never skipped merely because searching
takes some time. Only if it has not finished within the allowance, the message is
sent exactly as it is today, without recall, and the "what was sent" view
shows that recall was skipped. A slow phone or a very long chat can
therefore make recall miss a message, but can never hold a message back.
This matters most in hands-free voice chat, where a delay is felt at
once.

## G.7 Scope walls (Proposed, on the owner's requirement)

**Owner:** recall across chats must be controlled. A roleplay chat must not
reach companion-only material.

- **Every entry is stamped with scope labels from the first day,** even
  while recall only looks inside the current chat. Adding other chats later
  is then a change to where search looks, not a rebuild.
- **Recall inside the current chat needs no wall.** The chat is already
  sending that conversation.
- **Recall across chats uses the same wall saved memories use,** enforced in
  the database query, not afterwards:
  - a roleplay chat sees only entries from chats with the same world,
    campaign, or character;
  - companion entries are seen only with that companion, and in roleplay
    only through the existing door (the narrator match or the existing
    setting);
  - real-life and project entries never enter roleplay.
- **Open:** which ordinary chats may see each other, and whether cross-chat
  recall is chosen per chat or per companion (G9).
- **Where a passage's scope comes from.** A chat's companion, project, or
  roleplay settings can change during its life, so the chat's settings
  today are not a safe label for its older messages.
  - **New messages:** scope is stamped when the turn finishes, at the one
    place in `ChatActivity.kt` that already passes the chat's companion,
    world, campaign, character, and project to
    `TranscriptRecorder.recordTurn`, using those same values. The index
    does this itself, so it works whether or not the memory system is set
    up.
  - **Older messages:** scope is taken from the stored turn record where
    one names that message. That record exists only for turns captured
    while the memory system was set up.
  - **Everything else:** scope is "unknown".
- **Unknown scope is private to its own chat.** Such a passage can be
  recalled inside its own chat and is never offered to another chat.
- **Scope is never guessed** from the chat's present settings.
- **To verify during Steps 7–13:** how much existing conversation has a
  recorded scope.

## G.8 Context assembly: what the AI actually receives (Proposed)

Retrieval finds exchanges. Assembly decides which of them are sent, in what
form, and where.

**The order of work.**
1. The request is built exactly as today: the full chat, or the summary
   plus recent messages when the Summarizer or Compact has condensed the
   older ones.
2. Retrieval (G.6) returns its list of exchanges for the latest message.
3. Assembly chooses from that list, within recall's limits.
4. The chosen exchanges are written into a clearly marked section just
   before the newest message, beside the saved memories. The stored
   conversation is not changed.
5. The whole request is checked once for size.

**Summarized and compacted chats (Owner, 2026-11-11).** This is where
recall matters most. Checked in the code: summarizing or compacting changes
only what is sent. The original messages stay stored on the phone, with
their permanent IDs. The index is built from those stored originals, so an
exchange that the chat AI now sees only as part of a summary can still be
found and added in its original words. The summary itself is not changed
and is not searched.

**When the whole chat is already sent.** The certain benefit of recall is
in summarized and compacted chats, above. When the whole chat is sent, a
recalled exchange is a second copy of text the AI already has, and it is
paid for twice. The evidence that the copy helps is mixed: one study found
that models attend least to the middle of what they are given, and another
found that moving passages to "preferred" positions did no better than
random order (G.18). So this benefit is measured in Step 16 before it is
relied on, and reported to the owner together with its cost.

**Choosing which exchanges are sent.** When more exchanges qualify than
fit:
- Exchanges are taken in order of relevance score, best first.
- An exchange is taken only if it fits what is left of recall's size limit
  and count limit.
- An exchange that does not fit whole is added as an excerpt (below) when
  an excerpt fits. Otherwise it is skipped and the next one is tried, so
  one very large exchange cannot block smaller ones.
- A neighbouring exchange is added only when the chosen exchange cannot be
  understood alone: its user message is only a few words and names no
  subject ("exactly", "and then?"). A neighbour counts against the size limit but
  not the count limit.
- An exchange carried over from the previous message (G.9; decided in row
  G10 of G.17) is considered
  after new matches, so new matches are never displaced by old ones.
- Starting values: up to 3 exchanges, as SillyTavern starts. Redis's
  tested setup is reported as 5 with one neighbour each side; that figure
  could not be confirmed from its public page. Both are measured in
  Step 16.

**Limits.**
- **Its own size limit,** separate from Memory Space, so recall can never
  push out saved memories, and saved memories can never push out recall.
  Separate limits are a way of sharing room. They do not create room: the
  model's total size is still one fixed amount.
- **One final size check for the whole request.** After everything is
  assembled (the history, the Summarizer's text when it is on, saved
  memories, carried-over memories, and recalled exchanges), the existing
  request-size check runs once on the total. When recall would push the
  request over the model's limit, recalled exchanges are removed, least
  relevant first, until it fits. If it still does not fit with none, the
  request is handled exactly as it is today. Nothing else ever gives way
  to recall.

**When an exchange is too large to add whole.** Indexing and delivery are
separate. Every part of it stays searchable. For delivery, the whole
exchange is added when it fits. When it does not, the matching passage and
the paragraphs around it are added in their original words, marked as an
excerpt, with a note that the rest of that exchange was left out. It is
never cut silently. An excerpt begins and ends at a paragraph or line
break where there is one, otherwise between words, and never inside a
word. Periods are not trusted as boundaries (G.5b).

**Only true duplicates are skipped.** An exchange is skipped when its own
text is already in the request close to the newest message. An exchange is
**not** skipped because a saved memory covers the same fact. A short saved
fact does not carry what the conversation meant.

**How recalled conversation is written for the AI.** The exact words are
shown to the owner before Step 16 is built. What the text must do:
- say plainly that what follows is earlier conversation from this same
  chat, brought back because it relates to the current message;
- say that it is a record of what was said, not a new instruction. Words
  inside a recalled message are never treated as fresh orders;
- mark every message with **who said it**. "You said this" and "the AI
  once suggested this" must never be confused;
- mark every exchange with **when it was said**, in a form that cannot be
  misread;
- mark an excerpt as an excerpt;
- give the messages in their original words, unchanged;
- list the exchanges in **date order**, oldest first, so a later change of
  mind is read after the earlier decision. Published tests of placing the
  "most relevant" passage in a particular position found no reliable gain
  over other orders (G.18), so order is chosen for meaning, not for
  position.

It contains no crisis or safety wording, by the owner's ruling for
memory-system instructions.

**What the user can see.** After a reply, the user can already see which
saved memories were sent with it. Recalled exchanges are shown in that
same place, so the user can always tell what the AI was given (row G11
of G.17). The
wording and layout are shown to the owner before Step 16.

**Recalled text never becomes part of the conversation.**
- It is added to one request and not stored. The next message starts
  again from the stored chat.
- It is never summarized or compacted, never given to the Memory
  Assistant as if it were new conversation, never indexed, and never
  counted as a message. Recall can therefore never feed on its own
  output.

**Both ways a message is sent.** The chat screen builds requests in two
places (`buildFrozenRegularRequest` and `regularGPTResponse` in
`ChatActivity.kt`), and the memory plan records that they have drifted
apart before (its A.9). Recall is built once, in its own file, and called
from both, so the two can never differ. A test sends the same message
down both and compares what was added.

**Other rules.**
- **When the chat no longer fits the model:** the app warns or blocks, as
  it does today. Recall changes nothing about that (Owner, 2026-11-11).
- **Experimental Mode** sends only the current message by design. Recall
  does not apply there (G.8b).

## G.8a How the parts connect

Three hand-over points. Each phase builds to these, so a later phase does
not force an earlier one to be rebuilt.

**1. Index to retrieval.** For one chat, the index answers:
- which passages best match these words (keyword candidates, with scores);
- the chat's passage vectors, a batch at a time;
- which exchange a passage belongs to, and its neighbours;
- whether given messages may still be recalled right now;
- whether the chat is fully indexed, and how much is waiting.

Retrieval never reads the index's storage directly.

**2. Retrieval to assembly.** A list of exchanges, best first. For each:
its message IDs in order, who wrote each, when, which passages matched and
where in the message, the meaning score, how far it stood out, the keyword
score and matched words, and the combined score.

**3. Assembly to the request.** The text to add; the message IDs that were
included, for the "what was sent" view and for carry-over; how much room
was used; and what qualified but was left out, with the reason (no room,
duplicate, limit reached).

The Step 15 inspector shows the retrieval scores and reasons from point 2.
Point 3 comes from assembly, which is built only in Step 16: that Step
extends the same inspector with what was included, what was left out
because of no room, a duplicate, or a count limit, and what was actually
sent. Step 15 must not pretend to know assembly outcomes or implement
assembly early. This staging lets wrong or missing recall be explained
without duplicating assembly logic.

## G.8b The recall setting and where recall applies (Owner, 2026-11-11)

Decided by the owner on 2026-11-11.

**The setting.**
- It is in **RAG Active Memory Settings** (the screen formerly called
  Memory Controls; M.11), is called **Conversation Recall**, and
  starts **off**. It is one setting for all chats, not chosen per
  conversation.
- Its explanation reads: "Brings back earlier parts of the current chat
  when they relate to your message, including parts that were summarized.
  Recalled text is included in your normal AI requests."
- Turning it on may also turn on Index Conversations, so one switch is
  enough.
- It **never downloads an embedding model by itself.** With no model
  installed it cannot be turned on, and the user is told why and where to
  install one (G30, G31).
- If the user later turns Index Conversations off, indexing is **not
  silently restarted**, by recall or by anything else.

**Where recall applies.**
- **A chat whose memory is turned off** with the existing per-chat switch:
  recall is off in that chat too. That switch stays the one way to keep
  the memory system out of a chat.
- **Roleplay chats:** yes. Recall only ever reads the same chat.
- **Experimental Mode:** no. That mode sends only the current message.
- **Hands-free voice chat:** yes. It has a small, measured time allowance.
  Recall is not skipped merely because searching takes some time; it is
  skipped only when the allowance is exceeded.
- **A chat that no longer fits the AI model:** nothing changes. The app
  warns or blocks as it does today.

**Room for recalled text.** A starting amount is set through testing. It
is adjustable in **RAG Active Memory Settings** (M.11; placed in Advanced
Memory Settings before the 2026-11-16 reorganization), not only on the test
screen. Its label and explanation are shown to the owner before Step 16.

**What the user sees.** Recalled exchanges are listed on the existing
screen that shows which memories were sent with a reply, as a second
group.

**Follow-ups.** A recalled exchange stays through follow-up messages
under the same rule as saved memories (G.9).

**The recall test screen** is opened from a row beside Memory Search Test at
the bottom of **RAG Active Memory Settings** (M.11; it was in Advanced
Memory Settings, Debug section, before the 2026-11-16 reorganization), and
is called **Conversation Recall Test**.

**Debug information.** Recall and carry-over may add information to the
existing memory debugging view, so a wrong result can be explained. This
does not authorize any new general application log entries. The exact
lines are shown to the owner before Step 16.

## G.9 Carry-over for saved memories (Owner, 2026-11-16)

**The problem.** The saved-memory text is rebuilt for every message and is
not kept in the chat history. A memory found for one message is gone for
the next unless that message finds it again.

**How it is solved.** Through context assistance (G.9b): one app-wide
choice of Off, Code-based, API AI (in the initial rollout) or Local AI
(later). Searching with the previous messages (G.9a) was measured and is
not used (Owner, 2026-11-16).

**Rules every option keeps.**
- The new message is searched as today. Its own results come first and
  are never changed by assistance; assistance only adds candidates after
  them.
- Every added memory goes through the enforcer's selection like any
  other: scope, Maximum Memories, the +3 rule, lore overlap and memory
  space. Nothing is kept merely because it was sent before.
- A carried memory is read again from the store when the message is sent:
  an edit applies at once, and a deleted, disabled or out-of-scope memory
  is never carried.
- **Off means no context assistance (Owner, 2026-11-16).** It does not
  freeze other approved retrieval improvements, such as the +3 top-match
  rule (G.10), which apply under every option.

**Code-based: subject tracking (Owner, 2026-11-16, as evaluated).**
- A message sets the subject when it has a confident match of its own: a
  memory it matched by meaning and keywords together, by meaning with a
  stand-out of at least 4.0, or through a section of a long message
  (G.10). Those memories become the chat's current subject.
- A message with no confident match of its own is sent its own matches,
  then the current subject's memories it did not already find.
- A later confident match replaces the subject. There is no turn limit;
  the three-turn variant gave no benefit.
- This is exactly the method `subject_anchor` fixed in
  `app/src/test/resources/recall_carryover_holdout/final_methods.json`.
  Its numbers are not changed. Results are in `memory baseline.md`.
- Only the user's messages set the subject. A carried memory is not tested
  again against the follow-up; it passed the relevance gate against the
  message that set the subject.
- **Known limit, measured.** A message that names nothing and is not a
  follow-up ("Tell me a joke about penguins") receives the previous
  subject's memory. Code cannot tell it apart from a follow-up.
- **Proposed:** a +3 memory matched only through the top-match rule
  (G.10) does not by itself set the subject, so Code-based stays as
  evaluated. The combined effect is measured in Step 4a.

**API AI and Local AI** resolve the follow-up into a search query instead
(G.9b).

**Relation to recall.** Carry-over and conversation recall are separate.
They share the context assistance (G.9b) and the final size check (G.8),
and nothing else. Each has its own size limit.

A recalled exchange is carried over under the same rule (Owner,
2026-11-11; G.8b).

## G.9a Searching with the previous messages (built in Step 3; not used, Owner 2026-11-16)

Step 3 built `PreviousMessageSearch`, which searches the new message and
0–3 previous user messages separately. On both carry-over sets it found
more expected memories but carried many wrong ones (held-out set, one
previous message: 15 more correct, 45 more incorrect; `memory baseline.md`).
The owner decided it is not the carry-over method and it is not one of the
context-assistance choices. Step 4a does not connect it to sending.
Removing it from the code is a separate owner decision.

These rules from its design still apply wherever a later Step keeps
earlier-message embeddings:

- **Prepare in the background, through Step 1's shared embedding queue,**
  once per unchanged message, never on every keystroke, never the
  unfinished draft.
- **Keep only in a small, bounded in-memory cache** for the app process's
  lifetime. Navigating to settings or another chat does not clear it.
  Never persist it to disk. Key entries to the chat, the user message's ID
  and content, and the embedding model tag. Discard an entry when its
  message is edited or deleted, its chat is deleted, or the embedding
  model changes.
- **Never cache a search result.** The search runs again for every message
  sent, against the memories, scope and chat as they exist then.

## G.9b Context assistance (Owner, 2026-11-16)

Context assistance helps a follow-up find what it refers to. It is
optional: saved memories and recall work in full with it Off, and no
choice makes a paid call the only route to continuity.

| Choice | What it does | Built in |
|---|---|---|
| Off | Nothing. Each message is searched on its own, as today | Step 4a |
| Code-based | Subject tracking (G.9). On the phone, no cost | Step 4a |
| API AI | An AI model writes a self-contained search query from the recent conversation. It has a dedicated configuration screen with its own model selection from the user's configured API providers, separate from the chat, Summarizer and embedding models (M.4) | Step 4b, part of the initial rollout |
| Local AI | The same role and contract as API AI, with an on-device model | Later; the interface must accept it without change |

**One interface.** Each choice is its own implementation of one contract,
called once for each message sent, before saved-memory search:
- **Input:** the new message, and the recent turns of the same chat that
  the choice may read (for API AI, G39).
- **Output:** nothing (Off, or when the choice has nothing to add);
  memories to carry (Code-based); or one self-contained search query (API
  AI, Local AI).
- **Unchanged downstream:** the search, relevance gate, enforcer
  selection, scope, Maximum Memories, the +3 rule and prompt assembly.
- Conversation recall (Step 14) uses the same output for its stage 1
  (G.6).

**What the API AI helper does (Owner, 2026-11-16).** It resolves
references in short follow-ups and subject changes ("she", "that one",
"the same thing") from the recent conversation and writes one
self-contained search query. It never answers the user's message, never
changes the message or what the chat model receives, and never adds a fact
that is not in the conversation it was given.

**Proposed for the API AI helper, decided in G39 before Step 4b:**
- Its query is searched in addition to the new message: the new message's
  own results stay first and unchanged, and memories found only through
  the query follow, through the same relevance gate.
- Its output is checked before use: one query of plain text, within the
  embedding model's message limit. Anything else is discarded and the turn
  goes ahead as Off.
- When it fails (offline, provider error, time allowance exceeded, or
  output discarded) the turn goes ahead as Off. The time allowance, the
  notice wording and the privacy disclosure are approved first.
- Cost is shown as the provider reports it, otherwise "Not Reported".
- Which turns it reads (the user's messages only, or the AI's replies
  too), its instruction text, and which provider models qualify.
- Before a starting choice is set for anyone, it is scored once on the two
  frozen carry-over sets exactly as `CarryOverFinalEvaluationTest` scores
  the other methods, with a model and cost the owner approves. It is not
  described as better than Code-based until then.

**The setting.** One app-wide choice. Its place, label, explanation and
starting choice are approved before Step 4a (G38). The API AI
configuration screen (M.4) is approved before Step 4b (G39).

## G.9c Embedding models stay replaceable (Owner, 2026-11-16)

The embedding model is a separate choice from context assistance. An API
language model that rewrites a question is not an API embedding model that
computes vectors.

- EmbeddingGemma 300M q4 stays the default. Other local and API embedding
  models must be possible without redesign. None is built in this plan's
  current Steps.
- **Already in the code:** the `EmbeddingModel` interface, whose `tag`
  stamps every stored memory vector (`embeddings.embedding_model`); a new
  tag re-embeds. Model-specific tokenizer, prefix, dimensions, token
  limits and normalization stay behind that interface, not in retrieval
  code.
- **Every new store or cache of vectors** (the earlier-message cache, the
  passage index of Steps 8–9) records the model tag, keeps the original
  text so vectors can be rebuilt, and never compares vectors from
  different models.
- **Proposed:** the matching numbers (similarity floor, stand-out, the
  section threshold, and the 4.0 and 2.0 stand-outs of G.9 and G.10) were
  measured for EmbeddingGemma and belong to that model. Another model is
  offered only after its own values are measured on the Step 0 and
  carry-over sets.
- An API embedding model sends memory and message text to a provider. Its
  privacy and cost are decided before it is built (G41).
- Quality is reported separately from speed, memory use, battery and cost.
  Today's phone is a benchmark, not a permanent limit.

## G.10 A small detail inside a larger message (Step 2 built; repair merged in #202)

**The problem.** A small detail in a message about several things does not
find its saved memory or its earlier exchange (G.2).

**Why it happens.** Long and short messages are not handled differently.
One rule is applied to every message, and it weakens as a message covers
more subjects:
- **On keywords,** a memory is used only when it covers about half of the
  message's meaningful words. In "Tell me about Pepper", Pepper is all of
  them. In a message of two hundred words that mentions Pepper once,
  Pepper is a small fraction, so the memory fails.
- **On meaning,** the whole message is turned into one summary of what it
  means. A message about one thing gives a sharp summary. A message about
  five things gives a blur that is not close to any one memory.

**The model reads only the start of a long text.** The embedding model
reads about the first 350 to 400 words of a text and ignores the rest
(G.4). So today, meaning search sees only the beginning of a longer
message; only keyword search reads all of it. Searching in pieces is what
fixes this: every word of the message is inside at least one piece.

**The owner's concern (2026-11-12).** Periods cannot be trusted in
dictated text: speech-to-text leaves them out of whole paragraphs and also
puts them in the middle of a thought. Cutting a thought in half changes
what it means.

**What established systems do (G.18a).** None has a clean answer. They do
one of two things:
- search with pieces of the message as well as the whole. Pieces are cut
  at a paragraph, a line, or between words, never at periods, and each
  piece overlaps the one before it;
- or have an AI model rewrite the message into a search, at the cost of a
  paid call for every message.

**Approved rule: overlapping sections, used only for finding (G29).**
Step 2 applies this to saved memories only. Earlier-exchange recall remains future work.
- The whole message is searched first using the shared saved-memory matcher. The current repair may recalibrate its existing defaults under the frozen quality requirements below.
- A message longer than one piece is also searched in pieces. A piece is
  cut at a paragraph or a line where there is one, otherwise between
  words. Periods play no part.
- Each piece overlaps the one before it by half. Any thought shorter than
  half a piece is therefore whole inside at least one piece.
- **A piece is only ever used to find a memory. It is never shown to the
  AI.** What the AI receives is the whole saved memory, or the whole
  earlier exchange.
- A message short enough to be one piece uses only that whole-message pass.
  There is no line between "long" and "short" beyond that.

**When a piece's match counts.** A wrong memory is worse than a missed
one. Established systems send their best few matches even when none fits
well; this app does not.
- A section must pass the existing relevance gate (meaning OR keywords),
  AND have semantic similarity at least **0.48**, AND share at least one
  meaningful normalized word with the memory. All three requirements apply.
  Common words are excluded by `KeywordText`. A high semantic similarity
  alone, a shared word alone, or the usual whole-message gate alone is not enough.
- This owner-approved combined rule replaced the original proposal after
  that proposal added 29 incorrect matches on the original test set.
- The **80-word size, eight-section maximum, and 0.48 threshold are all
  provisional**: selection and evaluation used the same 32-memory set.
  This is not independent validation and does not guarantee zero additional
  incorrect matches on other libraries. Do not retune during repairs.
- **With no embedding model, or for a memory that has no vector yet,**
  nothing can confirm the match, so only the whole-message search runs, as
  today.

**Order and room.**
- Whole-message matches under the current tuning retain their order. Sections never push them out. The quality repair preserves every originally correct whole-message match and may remove incorrect matches; gold answers are unchanged.
- Memories found only through a piece come after those, best first, in
  whatever room the memory limit leaves. Entire memories are sent.
- **+3 importance (Owner, 2026-11-15; replaces the earlier section-only
  exception).** Maximum Memories applies normally to every matching memory,
  +2 and +3 included, and +2 and +3 rank alike. After the highest-ranked
  memories up to that limit are selected, every further matching +3 memory
  is added, each once. +2 never passes the limit. +3 always passes the count
  limit, subject to the existing relevance, scope and physical
  request-capacity requirements. This is the same for whole-message,
  section and previous-message matches: a +3 memory found only through a
  section is no longer held to Maximum Memories. Example: Maximum Memories
  5, ten matching +2 memories and three further matching +3 memories send
  eight.
- **+3 top match (Owner, 2026-11-16; G40).** A +3 memory that fails the
  relevance gate in a message's own whole-message search still matches
  when all three hold: its similarity is at least the ordinary similarity
  floor, its stand-out is at least 2.0, and it is the single most similar
  memory to that message. It is then a matching +3 memory under the rule
  above. The unrestricted lower bar, without the top-match condition, is
  rejected. Measured as `lower_bar_top_match_only` (`memory baseline.md`).
  It applies under every context-assistance choice, Off included.

**Time.**
- The number of pieces searched has a limit. When a message has more
  pieces than the limit, the pieces holding the rarest words (names and
  unusual terms) are searched first.
- The provisional allowance is **1,500 ms**. Queue waiting is bounded;
  during inference the queue requests cooperative ONNX termination at the
  deadline. Native inference must unwind before the model is released.
  This is **not a guaranteed hard deadline**: tokenization, native cancellation
  latency, section preparation, and scoring can extend total elapsed time.
  No detached embedding task may race a later message. Preserve whole-message
  results and successfully completed sections on timeout or section failure.
- The words of every memory are read once per message and reused, so the
  keyword work does not multiply.
- **The message is read by the embedding model once.** The whole message
  and its pieces are cut and read one time per message sent. Saved-memory
  search and conversation recall share those results. Neither reads the
  message again.
- When the limit or the time allowance leaves part of a message
  unsearched, the Memory Search Test screen says which part.
- Size, cap, similarity threshold and allowance are provisional engineering
  numbers. The CI x86_64 Android emulator measured a 1,507 ms additional
  search (one attempt, no completed section) after a 7,899 ms whole-message
  embedding. These are emulator observations, not physical-phone timings.
  The test screen separates queue waiting, model work and total section-read
  elapsed time (including section preparation and relevance scoring); report-only diagnostics and rendering are outside that line.

**Runtime validation.** The original Python ORT 1.29 fixture baseline remains
69 correct / 6 missed / 19 incorrect. Android ORT 1.20 measured 54/21/21 for
the whole message and 69/6/21 with sections, with no additional incorrect
matches from sections. All 313 input-token hashes matched independent
references. Small native-runtime vector differences change three whole-message
results; the original gold answers and fixtures remain unchanged. Recorded
Android observations are evidence only: accepting the worse result in PR #201
was an acceptance error. The repair branch `repair/step2-contract-audit` restores
original per-message quality checks for every ABI without changing gold labels
or acceptance requirements; newly wrong memories must fail. The initial repair
control, isolated Android 1.29 comparison, and explicit FP32-activation comparison
on Android 1.20 all failed that gate with 69/6/21. Neither experimental change
was adopted.

After reviewing later-step scope, the existing whole-message defaults were
calibrated to Stand-Out Strength 2.53 and Minimum Spread 0.034. Real Android
1.20 validation at `4d4c646` (run `43669339130`) now passes the original
per-message quality gate: **54/21/17 whole only; 69/6/17 with sections**. Every
original required correct match is retained and no newly incorrect ID is sent.
The independent set stays 4/5/0. All 85 Android tests passed, including telemetry
opt-out, queue/store/prompt and screen checks. Runtime, original model/weights,
tokenizer and dimensions remain unchanged; saved custom tuning is retained.
Quality evaluation searches all sections without a time allowance; a separate
timed emulator example completed one section before the cooperative limit.
Physical-phone timing remains unmeasured. Details and every changed sample are
in `memory baseline.md`. This repair is merged in #202.

**What the user can see.** The Memory Search Test screen must show which
piece of the message a memory matched, and when the search ran out of
time. Without that, the screen can no longer explain why a memory was
sent. Approved wording is recorded in G.13b. The repair also shows evaluated
section near-misses, all failed relevance requirements, attempted/successful
counts, processing failures and deadline cancellation. Unsearched sections are
not labelled as relevance failures.

**When the embedding model cannot read a piece.** That piece adds nothing.
This is reported, never silent, using the existing Memory Debug Log line
for this failure with its plain wording (G26). No log line is added or
changed without the owner's approval.

**The paid rewrite** is not built. It can be offered later as an optional
setting if the pieces prove not to be enough.

**The same rule serves recall** (G.6 retrieval stage 1).

## G.11 The optional live AI worker (Proposed, on the owner's requirement)

**Owner:** the foundation must support the Summarizer's model doing
message-level work while the user chats, as an option.

**Not the context helper.** The API AI context helper (G.9b) is a
separate job, run once for each message sent with its own model, and is
built in Step 4b. This worker stays Later.

- **What it is.** A background job that reads new exchanges as they are
  indexed and writes notes about them. It is off unless the user turns it
  on. With it off, everything else in this plan works in full.
- **Which model.** The connection and settings the Summarizer already has.
  It is a separate job with its own instruction. It never reads or writes
  the Summarizer's summaries.
- **Jobs it could do,** each one the owner's decision:
  - mark which exchanges hold something that matters (a decision, a date, a
    promise), which the embedding model cannot judge;
  - label the topic of a stretch of conversation. Published results favour
    topic-sized pieces, but only when a capable model does the grouping
    (G.18);
  - propose saved memories into Pending during the chat, for the user to
    review later.
- **What the foundation provides for it:** a place on each index entry for
  AI notes, kept separate from the original text and always marked as
  AI-written; a queue of exchanges waiting to be read; and upkeep that
  discards a note when its exchange is edited or deleted.
- **What is always delivered** is the original exchange. An AI note only
  helps find it.
- **What Steps 7–13 must leave in place for it,** so adding the worker later
  changes nothing already built: every passage belongs to an exchange with
  a stable ID; every passage records its source kind; the stores can gain
  a notes table without altering the existing ones; and upkeep already
  knows when an exchange was edited or deleted.
- **What is designed only when the owner starts that later work:** which jobs
  it does, the exact instruction it is given, the cost limits, and what
  the user sees. None of that is fixed here.
- **Cost.** It runs once per stretch of conversation, not once per message
  sent. The app shows what the service reports and never a built-in price.
- **Cost controls, required before it is built:** off by default; chosen by
  the user; a limit the user sets on how much it may read; it stops, and
  says so, when the limit is reached; it never re-reads an exchange that
  has not changed; and existing chats are never sent to it in bulk without
  the user asking.
- **Everything about it that the user sees or the AI is told** is shown to
  the owner before it is built.

## G.12 Upkeep (Proposed)

| Event | What happens to the index |
|---|---|
| New message | Its entries are added in the background |
| Message edited | Passages for that message are replaced. Unchanged search text keeps its vector |
| Reply regenerated or another version chosen | Entries follow the version that is shown |
| Message deleted | Its entries and any AI notes on them are removed, and any vector nothing else uses |
| Message hidden from the AI, or made visible again | Takes effect on recall at once (G.5a rule 1) |
| Chat deleted | All its entries are removed, and any vector nothing else uses |
| Chat renamed | For older chats a rename changes the chat's ID. Its entries move to the new ID (G.5a rule 9) |
| Reply finishes streaming | Indexed then, not while streaming. A failed reply is not indexed |
| Cutting rules or target size changed | Chats are re-cut in the background. Unchanged search text keeps its vector |
| The user deletes the index | Everything derived is removed. The chats are untouched |
| Backup restored, or a backup merged into the phone's chats | Each affected chat is checked message by message (G.5a rule 8). Nothing is indexed twice |
| Embedding model changed | Vectors are remade in the background, and chats are re-cut first when the new model's size limit or way of counting differs (G.5b cutter rule 4). A chat is recalled from again once its vectors are ready |
| Chat settings changed (companion, project, roleplay) | Scope labels on later messages follow the new settings |
| Existing chats, first time | **Owner (2026-11-11):** indexed in the background, a little at a time, newest first. Opening a chat never waits for it |
| Already indexed text | Stays indexed. It is redone only when the message is edited, the embedding model is changed, or the index is rebuilt after a restore |

The index is derived: it can always be rebuilt from the stored chats, and
losing it loses nothing the user wrote.

## G.13 When a part is unavailable (Proposed)

**Owner (2026-11-12):** an automatic repair that succeeds does not need a
snack bar, dialog, or other interruption. The repair is recorded in the
Error Log and may appear in an existing status or progress view. The user
is notified when a failure changes what happened for their message, stops
a feature, prevents a setting from being used, or gives the user something
real they can do to fix it.

| Condition | What still works | User interruption |
|---|---|---|
| The embedding model is not installed | Ordinary chat and lorebook matching work as today. Index Conversations and Conversation Recall are unavailable | When the user tries to turn either feature on, explain what is needed and provide a direct way to open the model settings |
| The embedding model was removed or cannot be loaded | Ordinary chat still works. Saved-memory search may use keywords; Conversation Recall stops because it requires the model | One notice for the ongoing condition, with a direct settings action. Do not repeat it every turn |
| Recall fails for one message | The message is sent normally without recalled conversation | Tell the user that this turn was affected, why in plain words, and what to do if an action exists. If no action is needed, say so |
| Some index work is waiting or is being retried | Chatting continues. The app repairs or resumes it in the background | No snack bar or dialog. Show it only in the Error Log and existing progress/status views |
| Index damage is found and the rebuild succeeds | Chatting continues; the derived index is rebuilt from stored chats | No snack bar or dialog. A user who started Check Database Integrity still receives that check's result |
| The index cannot be rebuilt or indexing cannot continue | Chatting continues, but Index Conversations and Conversation Recall stop | A persistent notice explains what stopped, confirms that conversations are safe, and gives a real recovery action |
| Search or indexing reaches its time allowance | The message is sent or finished work is kept | Not an error. Record the effect in the relevant test/status or "what was sent" view; interrupt only when the user can take an action, such as reopening the app to continue indexing |
| The optional live AI worker or its network is unavailable | Everything except the optional AI notes | Follow that feature's separately approved failure behavior when it is designed |

Failures are never shown as a generic error. The same ongoing failure does
not create a new notice on every message. It may be shown again only after
the condition recovered and failed again, or when the user explicitly asks
for a check.

## G.13a Damage, failures, logs, and notices (Owner direction, 2026-11-11 and 2026-11-12)

**Owner direction.** A damaged index must be detectable without the user
having to start anything; it also belongs on the existing database check
button; and whatever repair is possible follows. Errors are written to the
Error Log. Provider logs stay accurate when a request fails. A user-facing
notice follows G.13: automatic repairs stay quiet, while affected turns,
stopped features, and actionable failures are explained.

**How damage is found.**
- **By itself.** The database reports damage whenever the app opens or
  reads it, the way the app's other encrypted databases already do. The
  index is also compared with the stored chats during upkeep; a chat whose
  index does not match is re-indexed.
- **On request.** The existing Check Database Integrity button (Backup &
  Restore, Database Health section) also checks the conversation index and
  reports it as its own line. Checked in the code on 2026-11-11: today that
  button checks the memory, lorebook, and user image databases.

**How it is repaired.** The index is derived from the stored chats, so
repair is a rebuild, started automatically. No backup is needed, no choice
is asked of the user, and nothing the user wrote can be lost. Stored
vectors are kept when they can still be read, so a rebuild does not always
mean embedding everything again.

**Where failures are written.**
- **Error Log:** failures and automatic repairs of indexing and recall:
  damage found, rebuild started or failed, a chat that could not be
  indexed, meaning search that could not be prepared, and recall failing
  for a message.
- **Provider Failure Log:** unchanged. Indexing and recall make no provider
  requests of their own. Recalled text travels inside the ordinary chat
  request; if that request fails it is logged exactly as today, under the
  chat's own function name. The optional AI worker, when it is built, logs
  its failures under a function name of its own.
- **Never logged:** the text of any message or recalled conversation.
- A search time limit is not an error. It is shown in the relevant test,
  status, or "what was sent" view instead.

**Error Log wording.** These entries explain the effect in plain words.
"(technical cause)" is the real technical detail and belongs in the log,
not in the snackbar or dialog.

| When | Error Log entry |
|---|---|
| Damage found; automatic rebuild begins | Conversation index problem found: (technical cause). Automatic rebuild started. Conversations are unchanged. |
| Automatic rebuild succeeds | Conversation index rebuilt successfully. Conversations were not changed. |
| Rebuild fails | Conversation index could not be rebuilt: (technical cause). Index Conversations and Conversation Recall were turned off. Conversations are unchanged. |
| One chat could not be indexed but will be retried | A conversation could not be indexed: (technical cause). The app will try it again automatically. |
| Meaning search could not be prepared for some indexed text | Meaning search could not be prepared for (number) parts of conversations: (technical cause). The app will try them again automatically. |
| Recall fails for one message | Conversation Recall could not check earlier messages for this turn: (technical cause). The message was sent normally without recalled conversation. |

**What produces a notice.**

| Situation | Notice behavior |
|---|---|
| Automatic rebuild, retry, or missing-vector repair succeeds or is still progressing | No snack bar or dialog |
| One message is sent without recall | One plain notice for the affected turn or ongoing condition; the "what was sent" view also records it |
| The model is missing, removed, or unusable | Notice when the user tries the setting, or once when an installed feature becomes unavailable; include a direct model-settings action |
| Indexing or recall is turned off because recovery failed | Persistent notice with a direct recovery/settings action |
| The user runs Check Database Integrity | Its result reports healthy, rebuilding, or stopped, because the user requested the check |
| Android pauses background indexing until the app is reopened | The indexing notification/status explains that opening the app continues it; this is not logged as damage |

The exact snackbar, dialog, notification, and button wording below remains
subject to the owner's approval before Step 11 or Step 13 builds it. A
notice is shown once per unchanged condition, not once per message.

**Check Database Integrity wording:**

| Part | Wording |
|---|---|
| Hint under the button, replacing today's | Checks the memory, lorebook, user image, and conversation index databases for problems. Chats are not included. |
| Name in the report | Conversation index |
| Healthy | No problems found. |
| Automatic repair running | The conversation index is being rebuilt. Your conversations are safe and unchanged. |
| Repair failed | The conversation index could not be rebuilt. Index Conversations and Conversation Recall are off. Your conversations are safe and unchanged. Open RAG Active Memory Settings to try indexing again. |

The former rule that every index error raises a persistent snack bar is
superseded by the owner's 2026-11-12 decision above. Step 11 may create a
shared snack-bar style if its approved notices need one; it does not create
or show snackbars for log-only automatic repairs.

## G.13b Plain wording, approved Step 2 messages, and later-step proposals

**Owner (2026-11-12):** wording must be plain English, direct, and
understandable. When something cannot happen, the user is told why it
cannot happen and what they can do to fix it.

Every user-facing failure message follows this order:

1. what did not work;
2. what changed for this message or feature;
3. what is safe and unaffected;
4. the exact action the user can take, when one exists;
5. when no action is needed, say that instead of inventing one.

Additional rules:
- Planning words never reach the user on a screen or in a user-readable
  log when they obscure the operation. Embedding, embedding model and embedding
  index are explicitly allowed for accurate diagnostics. Prefer context-specific
  language over obsolete implementation shorthand.
- Raw exception text is never placed in a snackbar, dialog, notification,
  or inline setting message. It goes in the Error Log. User-facing text
  uses a plain cause the code can actually distinguish.
- "See the Error Log" may be offered for technical detail, but is never the
  only recovery instruction.
- A button opens the exact screen that can help. Do not say "check your
  settings" when the app can take the user there.
- Identical ongoing failures are not repeated every turn. Per-turn effects
  remain visible in the "what was sent" record.

**Proposed user-facing wording for review:**

| Situation | Proposed wording and actions |
|---|---|
| Turning on Index Conversations or Conversation Recall with no model installed | **Title:** On-Device Search Model Needed. **Text:** Conversation Recall needs an on-device search model. Install one in AI Settings, under Embedding Model, then turn this setting on again. Your conversations were not changed. **Buttons:** Open Model Settings, Close |
| The installed model was removed or no longer loads | **Title:** Conversation Recall Is Off. **Text:** Conversation Recall is off because its on-device search model is no longer available. Your conversations and saved memories are safe and unchanged. Reinstall or choose a model in AI Settings, under Embedding Model, to use recall again. **Buttons:** Open Model Settings, Close |
| Recall fails for one turn and no user action is available | Conversation Recall could not check earlier messages this time. Your message was sent normally without recalled conversation. No action is needed unless this keeps happening. |
| Recall fails for one turn and the code knows an action | Conversation Recall could not check earlier messages because (plain cause). Your message was sent normally without recalled conversation. (Specific action.) |
| The index cannot be rebuilt | **Title:** Conversation Recall Is Off. **Text:** The conversation index could not be rebuilt, so Index Conversations and Conversation Recall were turned off. Your conversations are safe and unchanged. Open RAG Active Memory Settings to try indexing again. **Buttons:** Open RAG Active Memory Settings, Close |
| Recall reaches its sending-time allowance | **What was sent:** Conversation Recall did not finish in time, so this message was sent normally without recalled conversation. |
| Android pauses background indexing until the app is reopened | **Notification/status:** Background indexing is paused. Open the app to continue. Finished work was saved. |
| Long-message section search reaches its allowance | **Approved Step 2:** Time limit reached while searching your message in sections. The full-message search still ran, but only {checked} of {total} additional sections were checked. |
| The embedding model cannot process one section | **Approved Step 2:** The embedding model couldn't process one additional section. That section was skipped, but the full-message search still ran. Error: {cause} |

**Approved Step 2 wording and implemented locations:**

| Situation | Final approved wording / location |
|---|---|
| Section match | Match found while searching section {number} of {total}, starting with “{first few words}…” — Memory Search Test result card |
| Section cap | Section search limit reached. Checked {checked} of {total} additional sections. The full-message search still ran. — Memory Search Test |
| Section timing | Additional section search: {checked} sections in {ms} ms. — Memory Search Test; Android plurals use “1 section” |
| Index load failure | Couldn't load the embedding index. Used keyword search instead. Error: {cause} — Librarian, Memory Debug Log |
| Model unavailable | Keyword search only (embedding model unavailable) — Enforcer summary fragment; punctuation belongs to the containing sentence |
| Live incomplete index | {missing} of {total} eligible memories aren't ready for embedding search. They were searched by keyword instead. — Librarian, Memory Debug Log |
| Preparation suffix | The app is preparing them in the background. — append only when a preparation pass is running or scheduled |
| Live query failure | Memory search could not read this message with the on-device model, so it searched by keywords only. Cause: {cause}. — Librarian |
| Background query failure | Memory Assistant could not process its background search with the on-device model, so it searched by keywords only. Cause: {cause}. — Librarian reconciliation |

The repair aggregates multiple section failures with section numbers and causes,
separates failed processing from timeout/cap, and uses context-specific Memory
Assistant complete-set fallback wording. The complete-set Assistant search is
unchanged. Diagnostics do not claim that an unsearched section failed relevance.
Near-miss labels explain the existing gate, similarity and meaningful-word rules;
they do not change those rules. Physical-phone timing remains pending;
the emulator measurements are recorded in `memory baseline.md`.

The two model-load/self-check messages remain assigned to Step 11; their
proposed replacements are not implemented by Step 2:
- self-check: The on-device memory-search model did not pass its check, so
  saved-memory search is using keywords only. Cause: {cause}.
- model load: The on-device memory-search model could not be loaded.
  Saved-memory search is using keywords only, and Conversation Recall is
  unavailable. Cause: {cause}.

Before Step 11, the builder lists every failure cause the code can
distinguish and maps each one to: automatic repair with no notice; affected
turn with a one-time notice; or stopped/actionable feature with a recovery
action. A builder never fills a user-facing "(plain cause)" or "(specific
action)" with a guess.

## G.14 Cost, speed, storage, privacy (Proposed)

- **Tokens.** A recalled exchange is sent once more in that request. Its
  own size limit bounds the cost. With the starting values, up to 3 earlier
  exchanges can be added to every message. The size limit is not set yet
  (G.8b). Until it is, the cost per message is not known and is not
  described as small.
- **Speed.** Indexing runs in the background and must not slow typing,
  scrolling, or sending. Search time is added to sending, and is measured
  on the owner's Pixel 8. No public figure exists for this model on a
  phone's ordinary processor.
- **Battery.** Indexing existing chats is heavy work. **Owner
  (2026-11-11):** it runs in the background, the user never waits for it,
  and it may keep running after the app is closed. On Android that
  requires a visible notification while it runs. It is mostly a one-time
  job, repeated only after a large restore or a change of embedding model.
  **Owner (2026-11-11):** indexing does not pause for battery saver. Some
  phones are always in battery saver.
- **Storage.** A 256-number vector is about 1 KB per entry.
- **Privacy.** Three separate things, stated separately:
  - **Indexing is entirely on the phone.** The index is encrypted like the
    chat search database, and building it sends nothing anywhere.
  - **Recall sends text to the chat AI.** When recall is on,
    the recalled exchanges are part of the ordinary request to whichever
    AI service that chat uses. For a summarized chat this means original
    wording is sent that the summary alone would not have sent. Any
    wording shown to the user must say this plainly.
  - **The optional AI worker sends conversation text to its model,** and
    only when the user has turned it on.
- **Backups.** **Owner (2026-11-11):** backups do not carry the index. It is
  rebuilt from the chats after a restore.

## G.15 Testing and measurement

Correctness tests are written with the component they test, in the same
pull request. The samples for the quality tests are built first, so no
Step's code is judged by samples written after it: saved-memory samples
in Step 0, and conversation samples in Step 6.

**Three kinds of test.**

- **Correctness tests** come with every Step. They either pass or the
  Step is not finished. Examples: every character of a message lands in a
  passage; an edit replaces only what it touched; a deleted message leaves
  nothing behind; a hidden message is never recalled and its words are in
  no neighbour's search text.
- **Quality tests** use written sample conversations with known right
  answers. They are scored, and the scores are tracked across measurement Steps
  so a change that makes recall worse is seen at once.
- **Measurements** set the numbers research does not give, with the real
  embedding model: the passage target size, the gate, the blend weights,
  how many exchanges to recall, how much less an earlier message counts,
  and speed on the phone.

**What every quality test checks, at three points:**
1. **What was found:** the right exchange is in retrieval's list.
2. **What was left out:** nothing unrelated is in it.
3. **What was sent:** the text added to the request is the right exchange,
   in its original words, correctly labelled, within the limits.

Step 14 measures retrieval results; Step 15 displays and measures the
retrieval results (points 1 and 2). Point 3 and its assembly-specific
reasons are measured and added to the inspector only in Step 16, when
context assembly exists. Do not claim a real "sent" result in Step 15.

**The situations the sample conversations must cover:**

| Situation | What must happen |
|---|---|
| An exact detail ("Which model did we choose?") | The original decision is recalled |
| A follow-up ("Why did we choose that?") | The same discussion is recalled, through the chosen context assistance (G.9b) |
| A time reference ("What did we change last week?") | Exchanges from that period are favoured |
| A summarized or compacted chat | The original exchange is recalled although the AI otherwise has only the summary |
| Nothing relevant | Nothing is added |
| A long message with one small important detail | The exchange about that detail is recalled |
| A long dictated message with no periods and one small important detail | The exchange or memory about that detail is found |
| A short sentence that shares one accidental word with an unrelated memory | That memory is not sent |
| A hidden message | It is never recalled |
| An edited message | The new text is recalled, never the old |
| A deleted message | It is never recalled |
| A chat that is about one subject throughout | Only exchanges closer than the general run of the chat are recalled |
| A roleplay chat and a companion chat | Each recalls only its own conversation |
| A very long exchange | An excerpt is sent, marked as one, and nothing is cut silently |
| More matches than fit | The most relevant are sent, and the rest are reported as left out |
| A decision made early and changed later, asked about in words that match only one | How often both are recalled is measured and reported. The result decides whether G.6 retrieval stage 9 is built |
| The same message sent down both request paths | The same text is added by both |
| Retrieval runs out of time | The message is sent as today, and recall is shown as skipped |
| A passage with no vector yet | It is not recalled, and is shown as waiting for its vector |
| No embedding model installed | Index Conversations and Conversation Recall cannot be turned on, and the user is told why |
| The model is changed after indexing | When the new model counts and limits text the same way, nothing is re-cut and recall resumes once the new vectors are made. When it does not, the affected messages are re-cut first (G.5b cutter rule 4). Both cases are tested |

**How quality is judged.** No pass mark is invented in advance. The first
run at each measurement Step sets the baseline. After that, a change must not lower
"found" or raise "wrongly added" without the owner being told and
agreeing. The owner ranks a wrong memory as worse than a missed one, so
"wrongly added" is watched first.

**These are engineering numbers.** The owner is not asked to choose them.
Each has a starting value from measurement and is adjustable afterwards.

## G.16 Build order and work orders

The design covers everything. Building is split into numbered Steps small
enough to hand to an agent: "build Step 7". **The order, status, grouping
into stages, and approval gates of every Step are in M.2.** A stage is
normally one pull request with its own tests, and each leaves the app
working.

**How to use this section.** Give the agent this plan and the stage or Step.
The agent reads Part M and G.19 first, then the sections the Step names,
then the code files the Step names. It builds only what was assigned.

**Difficulty** is marked so the owner can choose which model does a Step:
- **Routine:** self-contained, little risk. A lighter model can do it.
- **Careful:** touches shared code or has several cases to get right.
- **Hard:** many moving parts, or touches the chat screen. Use the
  strongest model available, and expect more than one review round.

### Steps, difficulty, and model

The former strict execution table is replaced by M.2 (2026-11-16). Step 0
is complete: do not rebuild it, regenerate its vectors, or rewrite its
expected answers. Its authoritative results are in
[memory baseline](<memory baseline.md>). Steps 1 and 2 were implemented in PRs #199 and #200; the first Step 2 repair is merged in #201, whose checks incorrectly accepted worse native accuracy. The follow-up, merged in #202, restores the original quality gate and passes real Android validation at 69/6/17 with the original runtime/model; the original quality target remains binding. Step 3 was merged in #203; its repair, merged in #205, corrects how its helper relates to the enforcer's selection and applies the owner's +3 rule (results in `memory baseline.md`).

| Step | What is built | Difficulty | Model |
|---:|---|---|---|
| 0 | Saved-memory test set with the real model's vectors | Routine with written instructions | Lighter |
| 1 | Token counting, one embedding queue, and the 2,048-token stored-text limit | Careful | Middle |
| 2 | Long and dictated messages find their saved memories | Hard | Strongest |
| 3 | Search primitive for previous user messages | Careful | Middle |
| 4a | Context assistance with Off and Code-based; the +3 top-match rule | Careful | Strongest |
| 4b | API AI context helper with its own model selector | Hard | Strongest |
| 5 | Memory word count and length limit | Careful | Middle |
| 6 | Conversation-recall test chats with known answers | Routine with written instructions | Lighter |
| 7 | Passage cutter | Routine | Lighter |
| 8 | Passage stores and per-message updating | Hard | Strongest |
| 9 | Background embedding of passages | Hard | Strongest |
| 10 | Keep the index synchronized with chats | Hard | Strongest |
| 11 | Settings, status, deletion, database check, and errors | Careful | Middle |
| 12 | Progress icon and pop-up with pause/resume | Careful | Middle |
| 13 | Indexing after the app closes | Hard | Strongest |
| 14 | Retrieval | Hard | Strongest |
| 15 | Inspector and measured starting values | Careful | Middle |
| 16 | Context assembly in real requests | Hard | Strongest |

**Difficulty and model labels.** Lighter means a small, inexpensive model is
enough. Middle means a mid-range model. Strongest means the most capable
available model and likely more than one review round.

**Result destinations are fixed:**

| Work | Authoritative record |
|---|---|
| Step 0 and all saved-memory benchmark comparisons in Steps 1–4 | `memory baseline.md` |
| Exact per-sample saved-memory acceptance state | `app/src/test/resources/recall_test_set/baseline.json`; change only for a verified improvement, never to hide a regression |
| Step 6 sample counts | Step 6 pull-request description and the Build record only; no search has run yet |
| Conversation-recall quality results from Steps 14–16 | `conversation_recall_baseline.md`, created by Step 14 and updated by Steps 15–16 |
| Every completed Step | One concise line in the Build record at the end of this plan |

Do not put measurement tables into the work-order text. Do not copy
`memory baseline.md` results into this plan. A builder reads the relevant
record before changing the code and writes new results back to that same
record.

**Phase names used in the design sections.** The index-foundation phase is
Steps 7–13. The retrieval-measurement phase is Steps 14–15. The
context-assembly phase is Step 16. These names describe groups; they do not
change the numbered execution order.

For the conversation index, nothing changes for any user until Step 11.
Steps 7–10 add code that stays switched off because Index Conversations
starts off and cannot be turned on until Step 11.

**The embedding model is required (Owner, 2026-11-12; G30).** Index
Conversations and Conversation Recall require an installed embedding
model. Keywords are still used together with meaning (G.6), but
keyword-only conversation recall is not built. The no-model and
model-removed wording is approved before Step 11 (G31).

**Why the first user-visible fix is Step 2.** The owner chose the
long-message saved-memory fix first. Step 0 had to establish the benchmark
and is complete. Step 1 must put every embedding request through one queue
before Step 2 runs the model several times for one message. This preserves
the owner's priority without skipping either safety prerequisite.

**Builder reading rule.** Follow the build order in M.2. Each checklist highlights the assigned Step; every instruction and test in the rest of that Step and its referenced design sections still applies. A checklist never permits skipping details or prerequisites. Stop before dependent work if an approval is missing. Do not change another Step, the design, or existing decisions to make the assigned task easier.

### Step 0: saved-memory test set — COMPLETE (Routine with these instructions)

**Completed result.** This work order is retained as the audit record for
Step 0. Do not run it again unless the owner explicitly reopens Step 0.
The authoritative measurements are in `memory baseline.md`.

**Goal.** Sample memories and messages with known right answers, run in
the normal unit tests with the real embedding model's numbers. Steps 2–4 are judged by this set, so it is written before any of their
code. Conversation recall has its own set, Step 6.

**Rules for the completed Step 0 audit record.**
- Add new files only. Change no app code and no existing test.
- **Step 0 results go in `memory baseline.md`.** This plan is changed
  only to point its Step 0 results instructions to that file.
- Create no Markdown file.
- Step 0 adds test code, so it follows the code workflow in
  `CLAUDE.md` like any other Step.
- Write every expected answer **before** running anything. Never change an
  expected answer to make a result look better. If an expected answer
  turns out to be wrong, say so in the pull request description and leave
  the decision to the owner.
- All sample content is invented. Use a made-up person. Use nothing from
  any real conversation.
- Do not commit the model files. Download them to a temporary folder.
- If any part below cannot be done, stop and say which one. Never make up
  numbers.

**Files to create.**

| File | What it is |
|---|---|
| `app/src/test/resources/recall_test_set/cases.json` | The sample memories and messages, written by hand |
| `app/src/test/resources/recall_test_set/generate_vectors.py` | The script that turns each text into the real model's numbers |
| `app/src/test/resources/recall_test_set/vectors.json` | The script's output |
| `app/src/test/resources/recall_test_set/baseline.json` | How today's search scores on the set |
| `app/src/test/java/org/teslasoft/assistant/preferences/memory/librarian/RecallTestSetTest.kt` | The unit test that runs the set |

**Part 1: write `cases.json`.**

Memories: at least 30, each with an `id`, `content`, and `tags`. One to
three sentences each. Spread them over these subjects: a pet, health, work,
family, home and moving, hobbies, food, and plans with dates. Include
pairs that share one ordinary word but are about different things, such
as a move planned for spring and nothing else about spring.

Messages: each with an `id`, a `kind`, its `text`, `expect` (memory ids
that should be sent), and `must_not` (memory ids that must not be sent).

| Kind | At least | What it is |
|---|---|---|
| `short` | 10 | One short message about one memory |
| `long_typed` | 8 | 150 to 300 words about several things, with normal punctuation, and one small detail that belongs to one memory |
| `long_dictated` | 8 | 150 to 400 words with **no periods at all**, as speech-to-text writes them, with one small detail that belongs to one memory |
| `dictated_bad_periods` | 5 | The same, with periods in the middle of thoughts |
| `very_long` | 4 | More than 450 words, with the detail placed after the 400th word |
| `accidental_word` | 8 | A short message that shares exactly one ordinary word with an unrelated memory. `expect` is empty and that memory is in `must_not` |
| `nothing_relevant` | 8 | About something no memory covers. `expect` is empty |
| `two_subjects` | 5 | About two memories at once. Both are in `expect` |

Conversations: at least 9, each a list of user messages in order. Each
turn has its own `expect` and `must_not`.
- At least 6 have three turns. The first names a subject. The second is a
  follow-up that does not name it ("What should I feed her?"). The third
  changes to a subject that has its own memory. The third turn's
  `must_not` holds the first subject's memory, so a memory carried into a
  new subject is counted.
- At least 3 have four turns: a subject, then two follow-ups in a row that
  do not name it, then a change of subject.

**Part 2: write `generate_vectors.py`.** It must do exactly what the app
does (`OnnxEmbeddingModel.kt`, `EmbeddingModels.kt`,
`RetrievalDocument.kt`). Read those three files first.

1. Download `onnx/model_q4.onnx`, `onnx/model_q4.onnx_data`, and
   `tokenizer.json` from the address in `EmbeddingModels.kt` into a
   temporary folder.
2. Use Python with the `onnxruntime` and `tokenizers` packages.
3. For a memory, the text is its content, then a new line, then its tags
   joined with a comma and a space (`RetrievalDocument.semanticDocument`).
   Put `title: none | text: ` in front of it.
4. For a message, put `task: search result | query: ` in front of it.
5. Turn the text into token ids the way `HfTokenizer.encode` does. The
   tokenizer puts one marker at the start and one at the end. Those two
   markers are always kept. When the text is too long, it is the text's
   own tokens that are cut, to 510, so the whole input is never more than
   512 tokens and always ends with the end marker. Do not simply keep the
   first 512 ids: that drops the end marker on long text.
6. Run the model with `input_ids` and an `attention_mask` of ones. Take
   the output named `sentence_embedding`.
7. Keep its first 256 numbers, then scale them so their length is 1.
8. Write every vector to `vectors.json`, keyed by the memory or message
   id, rounded to 5 decimal places.

**Part 3: prove the script is right before using its output.** The script
must print these checks and stop if any is off by more than 0.01. It must
also check that every input, long ones included, begins with the start
marker, ends with the end marker, and holds at most 512 ids. The
reference values were measured on 2026-11-12 with this recipe:

| Check | Value |
|---|---|
| "I love drinking coffee in the morning." against "My favorite morning drink is coffee." (both as stored text) | 0.9304 |
| The first sentence against "The spaceship landed on the distant planet." (both as stored text) | 0.4371 |
| The message "What should I feed Pepper?" against the stored text "Pepper is the user's cat. She has kidney disease and eats a prescription diet." | 0.6208 |

**Part 4: write `RecallTestSetTest.kt`.**
- Read the JSON files with `org.json`, which the unit tests already have.
- For each message, call `LiveRetrieval.search` with the default
  `LiveRetrieval.Params()`, the message's vector, every memory as a
  candidate with its vector and tags, and a limit of 5. Read
  `LiveRetrievalTest.kt` to see how candidates are made. "Sent" means the
  memory ids in the result's ranked list.
- For conversations, search each turn on its own, as the app does today.
- The test prints, for every message and every conversation turn, the
  expected ids that were sent and every other id that was sent, whether
  or not it is listed in `must_not`.

**Part 5: write `baseline.json`.** It records today's result for every
sample one by one, not only totals. For each message id and each
conversation turn it holds two lists: `found` (expected ids that were
sent) and `wrongly_sent` (every other id that was sent). Fill it from
the test's printed output, without changing any expected answer. Until
`baseline.json` exists, the test prints the results and fails with the
message that the baseline is not recorded yet.

**Part 6: what makes the test pass or fail.** Each sample is checked on
its own. Totals alone can hide one sample getting worse while another
gets better.
- The test fails if, for any sample, an id in the baseline's `found` list
  is no longer sent.
- The test fails if, for any sample, an id it did not expect is sent that
  is not in the baseline's `wrongly_sent` list.
- The failure message names each sample and each memory id that got
  worse.
- The test does not require every expected memory to be found. Today's
  search is known to miss the long messages. Those misses are what the
  later Steps are measured against.
- The test also prints the totals for the whole set and for each kind:
  expected memories found, expected memories missed, and `must_not`
  memories sent.
- A later Step that improves a sample updates that sample's entry in
  `baseline.json` in its own pull request and lists every changed entry
  in the pull request description. An entry is never changed to hide a
  sample that got worse.

**Done when:**
- the script's checks pass;
- `./gradlew testDebugUnitTest` passes with the new test included;
- the pull request description lists the totals for each kind, and any
  expected answer the builder now doubts;
- the totals and per-sample results are recorded in `memory baseline.md`.

**Baseline.** See [memory baseline](<memory baseline.md>) for Step 0
measurements and verification results.

### Step 1: token counting, one embedding queue, and the reading limit (Careful)

**Agent checklist for this step**
- **Prerequisites and approval:** Step 0 complete; see G33. This step adds no screen or other visible control.
- **Change:** Count tokens including prefixes and markers without cutting text; route **every** embedding call (including the unlocked saved-memory search call) through a single priority queue; raise the stored-memory reading limit to 2,048 tokens.
- **Preserve:** A live request outranks queued background work and waits for at most one already-running read. Keep the live whole-message pass at 512 tokens. Preserve vectors and search results for memories that fitted inside the old 512 limit; regenerate only over-limit ones.
- **Verify and report:** Existing tests unchanged; token counts match real encoding; priority/stop/retry behavior is tested; long memories are fully read. Report measured wait and read times, and distinguish build-environment measurements from phone measurements still needed.

- **Goal:** the app can ask how many tokens a text needs, and every use of
  the embedding model goes through one queue where a live chat request
  goes first (G.5a rules 2 and 6).
- **Read:** G.5a rules 2 and 6; G.2's last point;
  `memory/librarian/HfTokenizer.kt`, `OnnxEmbeddingModel.kt`,
  `EmbeddingModel.kt`, `Librarian.kt`.
- **Build:** a counting function on the tokenizer and the model that
  counts the instruction prefix and special tokens and never cuts
  anything off. One queue for all embedding work with two priorities. Move
  the unlocked call in `Librarian.searchInternal` onto it. Raise the
  reading limit for stored text to 2,048 tokens (G33), and read again only
  the saved memories that were longer than the old limit, so no other
  memory loses its place in meaning search while that happens.
- **The two limits.** Stored text (a saved memory) is read up to 2,048
  tokens. The message being sent keeps today's 512 for its whole-message
  pass, because that pass happens while the user waits.
- **Must not change:** the search result or the stored vector of any
  memory that fitted inside the old 512 limit, or what the tokenizer
  returns for text that fits. Only memories longer than the old limit are
  read again, and only their results may change.
- **The wait behind a running read (G.5a rule 6).** A live message can
  wait for one background read that has already started. The builder
  measures that wait for the longest text, reports it, and, where the
  model's runtime allows a read to be stopped, stops the background read
  when a live message arrives and does it again afterwards.
- **How a read might be stopped.** The ONNX Runtime library the app uses
  (1.20.0) offers `OrtSession.RunOptions.setTerminate(true)`; the app does
  not use it today. The builder checks that it stops a read safely with
  this app's model and session before relying on it. If it does not, the
  live message waits for the running read, and the pull request says so.
  A stopped background job goes back into the queue and is done again
  later: no finished work is lost and the queue is never left broken.
- **Measurements.** The builder records in `memory baseline.md` the time
  to read a full-length
  text, how long a live message waits behind a running read, and, where
  stopping works, how long a stop takes. Each number is labelled either as
  measured by the automated tests in the build environment or as one that
  must be measured on the owner's Pixel 8.
- **No screen.** Step 1 adds no screen or visible control without the
  owner's approval.
- **Done when:** existing tests pass unchanged; new tests show the count
  matches the real encoding, that a live request is served before every
  waiting background job and waits for at most one running read, and that
  a memory longer than 512 tokens is now read in full. The time and memory use of reading a 2,048-token memory on a
  phone cannot be measured in the build environment; the pull request
  says so plainly, and the owner is told how to see it on the phone.

### Step 2: long messages in saved-memory search (Hard)

**Status:** Implemented in #200 with an initial repair in #201. The follow-up on `repair/step2-contract-audit` passes the restored original per-message quality gate on real Android 1.20: 69 correct / 6 missed / 17 incorrect, retaining all originally required correct matches. No acceptance requirement or gold answer was weakened. All 85 Android tests passed at `4d4c646`; the repair is merged in #202. Runtime/model stay unchanged, and rejected runtime/precision experiments remain documented in `memory baseline.md`. Physical-phone timing is still unmeasured.

**Repair scope after later-step review:** Steps 3–4 add separate previous-message searches; Steps 14–15 build and measure conversation-passage recall. Neither is a later work order to recalibrate this existing saved-memory gate. The existing gate was already built in #180. The current repair may therefore adjust its existing default calibration while preserving all original correct matches and rejecting newly incorrect matches per sample. Android 1.20, the original q4 model, vector size, keyword rule, section size/cap/0.48 threshold and gold answers remain fixed. No later Step is started here.

**Agent checklist for this step**
- **Prerequisites:** Steps 0 and 1. **Approval gate:** Resolved by the owner; final wording is in G.13b. Routine repair corrections are authorized.
- **Change:** G.10's bounded overlapping search pieces for typed and dictated messages, using the shared embedding queue and a minimal hookup. Whole-message search still runs first; each section must pass the relevance gate, similarity ≥0.48 and meaningful shared-word overlap.
- **Preserve:** Every originally correct whole-message match and the relative order of retained matches; Memory Assistant search, lorebooks, and current sending when no piece can qualify. The existing whole-message defaults may be recalibrated under the repair scope below. Do not add a paid rewrite or trust periods as sentence boundaries.
- **Verify and report:** Step 0's per-sample quality improves for long messages **without increased wrong memories**; existing tests pass; report time allowance and any missing on-phone performance proof.

- **Goal:** a small detail in a long message finds its saved memory,
  whether the message was typed or dictated, without more wrong memories
  being sent (G.10).
- **Needs first:** Step 0 and Step 1.
- **Approved:** Memory Search Test and saved-memory diagnostics in G.13b; G26 is resolved for Step 2. Model-load notices remain Step 11.
- **Read:** G.2, G.10; `LiveRetrieval.kt`, `Librarian.kt`,
  `SearchTestReport.kt`.
- **Build:** G.10, in its own file, with a small hookup. Every use of the
  embedding model goes through Step 1's queue.
- **Must not change:** originally correct whole-message matches or the relative order of retained matches; the Memory Assistant's own search; the lorebook. Removing incorrect matches through the existing default calibration is permitted only under the unchanged per-message quality gate.
- **Results:** record the before/after totals and every changed sample in
  `memory baseline.md`. Update `baseline.json` only for verified
  improvements, never to permit a regression.
- **Done when:** on Step 0's set, more right memories are found in long
  messages, dictated ones included, and the number of wrong memories sent
  has not risen; existing saved-memory tests pass unchanged; and timing, queue/cancellation behavior and verification limitations are
  reported accurately. The provisional allowance is cooperative, not a hard
  wall-clock guarantee. Measure real Android queue/model/total timing when
  hardware or an emulator is available; never substitute Python timings.

### Step 3: searching with the previous messages (Careful)

**Agent checklist for this step**
- **Prerequisite:** Step 0 test cases and baseline. No new UI or owner wording is required here.
- **Change only:** A standalone Kotlin search helper following G.9a, taking 0–3 previous **user** messages as an input. Search each independently and give newer matches priority; do not join messages.
- **Preserve:** Existing live search: this helper is **not connected to sending** in Step 3. Accept/reuse earlier-message vectors where available and respect changing memory/scope eligibility when the helper is later used. Composition-triggered preparation and app-process cache wiring belong to Step 4, not Step 3.
- **Verify and report:** Run Step 0 conversations at 0, 1, 2, and 3 previous messages. Record first and later follow-ups kept, carry-over after subject changes, and every unexpected memory sent.

- **Goal:** the method in G.9a, built on its own, so the fix for follow-up
  messages (Step 4) does not wait for the conversation index.
- **Read:** G.9a; `LiveRetrieval.kt`, `KeywordText.kt`.
- **Build:** plain Kotlin in its own file, connected to nothing yet, with
  no setting and nothing on any screen. It takes the number of previous
  messages as an input. How much less an earlier message counts is
  measured on Step 0's conversations.
- **Done when:** Step 0's conversations have been scored with the number
  of previous messages at 0, 1, 2, and 3, and for each `memory baseline.md`
  records:
  first follow-ups that kept their memory; second and later follow-ups in
  a row that kept it; memories of the old subject sent after the subject
  changed; and any other memory sent that the sample did not expect.

### Step 4a: context assistance, Off and Code-based (Careful)

**Agent checklist for this step**
- **Prerequisites:** the Step 3 repair (#205) and the carry-over
  evaluation (#206) are merged. **Approval gate:** G38, and G45 if the
  frozen-path lorebook check (M.6) is included.
- **Change:** the context-assistance interface (G.9b) in its own files,
  with a small connection where a message is sent; Off and Code-based
  (G.9); the +3 top-match rule (G.10) in the saved-memory search, so the
  Memory Search Test shows the same result; and the app-wide setting (G38).
- **Preserve:** with Off, the request is the same as before Step 4a except
  for the +3 top-match rule. Code-based is exactly the evaluated
  `subject_anchor`; its numbers are not tuned. `PreviousMessageSearch` is
  not connected to sending. Search results are never cached.
- **Verify and report:** see below.

- **Goal:** a saved memory stays through follow-up messages and gives way
  when the subject changes (G.9).
- **Read:** G.9, G.9b, G.9c, G.10; `enforcer/Enforcer.kt`;
  `CarryOverFinalEvaluationTest.kt`; the injection record in
  `MemoryStore.kt`.
- **Build:**
  - The interface, Off and Code-based behind it. The Code-based subject is
    kept per chat in memory for the app process, keyed to the chat and the
    embedding model tag, dropped when the chat is deleted or the model
    changes. Carried memories are read again from the store at send.
  - How the subject is rebuilt after the app restarts is proposed to the
    owner, with its cost, as part of G38, and built only as approved.
  - The +3 top-match rule.
  - The setting, using the shared styles (`ui-style-guide.md`).
- **Tests:**
  - With the +3 top-match rule switched off inside the test, the app's
    Off and Code-based reproduce the recorded `none` and `subject_anchor`
    results of both carry-over sets exactly.
  - With the rule on, the combined results on both carry-over sets and
    Step 0 are recorded in `memory baseline.md`.
  - A deleted, disabled, edited or out-of-scope memory is never carried in
    its old form; switching chats never carries another chat's subject.
- **On Android:** leaving and re-entering a chat, switching chats, a
  restart, Maximum Memories 1 and 8, and lore overlap and memory space
  with carried memories. Send-time cost on a physical phone is reported;
  the owner confirms the device behavior.
- **Done when:** the tests above pass, Off sends what it sent before apart
  from the +3 top-match rule, and the results are recorded.

### Step 4b: API AI context helper (Hard)

**Agent checklist for this step**
- **Prerequisites:** Step 4a merged. **Approval gate:** G39.
- **Change:** the API AI choice behind the Step 4a interface, with its
  dedicated configuration screen (M.4) holding its own model selection
  filled from the user's configured providers (never a built-in model
  list), and every other screen item exactly as approved in G39; the
  approved instruction, the output check, and the approved behavior on
  failure. The screen uses the shared styles (`ui-style-guide.md`).
- **Preserve:** the chat, Summarizer and embedding models and their
  settings are untouched; the user's message is unchanged; no request is
  made unless the user chose API AI.
- **Verify and report:** unit tests with a stand-in provider for a valid
  query, discarded output, timeout, provider error and no network; one
  scored run on the two frozen carry-over sets with the model and cost the
  owner approved, recorded in `memory baseline.md`; send-time delay on a
  physical phone. Local AI is not built, but nothing in the interface
  needs to change to add it.

### Step 5: the memory word count and length limit (Careful)

**Agent checklist for this step**
- **Prerequisite:** Step 1 complete. **Approval gate:** Ask the owner to settle every remaining G34 wording and over-limit behavior choice **before** writing those choices into code or tests.
- **Change:** Add the right-aligned counter to every saved-memory editor, a full-input token limit on saving, and splitting of over-length Memory Assistant proposals without loss.
- **Preserve:** Existing memory text; never shorten it automatically. Count user-visible words separately from the model's complete input (content, tags, extra search text, prefix, and markers).
- **Verify and report:** Counter changes while typing; approved limit and early-token-limit notices work; an oversized proposal is split with nothing discarded. List the affected editors in the PR.

**Owner (2026-11-12).**
- In the Memory Browser, under the box where a memory is typed, aligned
  to the right, show how many words the memory holds. This is shown for
  each memory.
- When the count reaches 1,500 the line turns red and reads:
  "Word Limit Reached: 1,500 Words Maximum".
- The Memory Assistant must split a large subject into separate proposed
  memories. It must not fail, or throw away a proposed memory, because it
  is too long.

- **Goal:** no saved memory is longer than the embedding model can read,
  and the user can always see how long a memory is.
- **Needs first:** Step 1, which raises the model's reading limit to
  2,048 tokens (about 1,500 words) and adds the real token count.
- **Before building, the owner approves (G34):** the exact text of the
  count before the limit is reached; what happens when the user tries to
  save a memory that is over the limit; what is shown in the rare case
  below; how memories that are already too long are marked; and the exact
  words added to the Memory Assistant's instructions.
- **The rare case.** The model's limit is counted in its own units, not
  in words. Ordinary English fits 1,500 words. Text with many unusual
  names, numbers, or another language can reach the model's limit sooner.
  The red line must not say 1,500 when the real limit was reached at a
  lower count, so that case needs its own approved wording.
- **What is counted.** The model does not read only what the user typed.
  It reads the memory's content, any extra search text stored with the
  memory, its tags, and the model's own instruction words and markers
  (`RetrievalDocument.kt`, `OnnxEmbeddingModel.kt`). The check on saving
  counts all of that together, in the model's units, with the counter
  from Step 1. The word count the user sees counts the words they typed.
- **Until Step 5 is built.** Step 1 raises the limit but does not stop
  a memory from being longer than it. Such a memory is still read only up
  to the limit, as today. Step 1 adds no new cutting; Step 5 closes
  the gap.
- **Read:** `ui-style-guide.md`, "Field error and counter lines" (it has
  a right-aligned counter under a field, and a counter for a maximum);
  `ui-style-adoption.md`; `activity_memory_editor.xml` and its screen
  code; the Memory Assistant's instructions in `archivist/`.
- **Build:** the count line in every place a memory's text is typed or
  edited. The builder finds those places in the code and lists them in
  the pull request. The count uses the shared counter style; no
  appearance is written into a layout or into Kotlin. The check on
  saving. The Memory Assistant change, with a test that an over-length
  proposed memory is split and nothing is discarded.
- **Must not change:** the text of any existing memory. Nothing is ever
  shortened automatically.
- **Done when:** the count updates as the user types; the red line
  appears at the limit with the approved wording; a memory over the
  limit cannot be saved without the user being told why and what to do;
  and the Memory Assistant test passes.

### Step 6: the conversation test set (Routine with these instructions)

**Agent checklist for this step**
- **Prerequisites and approval:** Step 0 complete (done); position in M.2; no additional approval. Build invented test samples and validation, not the recall engine.
- **Change only:** Add `conversations.json` and a unit test that validates every situation and minimum below.
- **Preserve:** No application-code changes, existing-test changes, real conversation content, or changed expected answers. Do not create Markdown.
- **Verify and report:** Run the validation test; report each situation's sample count in the PR. Step 14 handles vectors and retrieval scoring later. Follow the code workflow in `CLAUDE.md`.

**Goal.** Sample chats with known right answers for conversation recall,
written before any index or retrieval code, so that code is judged by
samples that existed first. Step 0 covers saved memories only; it cannot
judge recall of earlier exchanges.

**Rules.** The same as Step 0: new files only, no app code changed, no
Markdown file created, all content invented, expected answers written
before anything is run and never changed to improve a result, and the
code workflow in `CLAUDE.md`.

**File to create:**
`app/src/test/resources/recall_test_set/conversations.json`, and a unit
test that only checks the file is well formed: every id is unique, every
expected id exists, every situation below has its minimum number of
samples, and each sample really has what its situation says: for
example, the word counts, no periods in a dictated message, the hidden,
edited, failed, or summarized message it is about, chats of more than 60
messages, and an expected answer that is empty where nothing is
relevant. No search is run in Step 6, because the index does not exist
yet. Step 14 adds the vectors and the scoring.

**What each chat holds.** An `id`, its scope (a companion, a project, a
roleplay world, or none), and its messages in order. Each message has a
permanent-style `id`, who wrote it (`user` or `ai`), its `text`, a
`time`, and its state: `visible` or `hidden` from the AI; `finished`,
`failed`, or `unfinished`. A message may carry an `edited_from` text, to
test that only the new text is found. A chat may carry `summarized_up_to`,
the id of the last message the AI now sees only as a summary.

**What each question holds.** The chat it is asked in, the user's new
message, `expect` (the ids of the user messages that open the exchanges
that should be recalled), and `must_not` (exchanges that must not be).

**Situations, with the least number of questions for each.** These are
the rows of the table in G.15 that concern recall:

| Situation | At least |
|---|---|
| An exact detail from far back in a long chat | 6 |
| A follow-up that does not name its subject | 4 |
| A time reference ("last week", "in March") | 4 |
| A chat with `summarized_up_to`, asking about something before that point | 5 |
| Nothing relevant in the chat | 6 |
| A long typed message with one small detail | 4 |
| A long dictated message with no periods | 4 |
| A hidden message that would otherwise be the best match | 4 |
| An edited message, asked about in the words of the old text | 3 |
| A failed or unfinished reply that would otherwise match | 3 |
| A chat about one subject throughout | 4 |
| A roleplay chat and a companion chat holding similar words | 4 |
| An exchange of more than 1,500 words | 3 |
| More matching exchanges than the limit of 3 | 3 |
| A decision made early and changed later | 4 |

At least 8 chats. At least 3 of them have more than 60 messages, so that
"far back" is real.

**Done when:** the file exists with the minimums met, the well-formed
test passes, and the pull request description lists the number of
questions for each situation.

### Step 7: the passage cutter (Routine)

**Agent checklist for this step**
- **Approval gate:** G35 must be explicitly approved before coding: it covers the full index design in G.5, G.5a, G.5b, and G.7.
- **Change only:** New plain-Kotlin cutter files and their tests. Input is one message, a target size, and a supplied size-measuring function; output is ordered original-text start/end positions and the cutter version.
- **Preserve:** Every original character exactly once, with no changes to chat text. Use paragraphs, lines, words, then characters when necessary; **never split at periods**. No storage, screen, embedding call, search text, or overlap in this step.
- **Verify:** Run every test under G.5b's “With the cutter, in Step 7”; the other G.5b test groups belong to later steps.

- **Goal:** a function that takes one message's text and returns its
  passages, exactly as G.5b describes.
- **Read:** G.5b; `util/ConversationTokenChunks.kt` to see how the
  platform's language-aware boundary tool is used (do not change or call
  it).
- **Build:** new files only, plain Kotlin with no Android screen code. The
  cutter takes the text, a target size, and a function that measures
  size, and returns each passage's start and end. In the app that
  function is the real token counter from Step 1, passed in by Step 8;
  Step 7's own tests pass in a simple one. It includes splitting, joining
  short pieces, the last-resort cut, and the version number.
- **Not in Step 7:** search text and overlap (Step 8), real
  token counts (Step 1), any storage.
- **Done when:** every test listed under "With the cutter, in Step 7" at
  the end of G.5b passes. The search-text tests there belong to Step 8
  and are not part of Step 7.

### Step 8: the passage stores and per-message updating (Hard)

**Agent checklist for this step**
- **Prerequisites:** Steps 1 and 7, with G35 already approved before Step 7.
- **Change:** The separate encrypted index's four stores; per-message ID/fingerprint comparisons and transactional updates; eligibility, same-message search-text overlap, scope stamps, and waiting-vector rows. Confirm the AI-facing reply-version field in code and record it in this plan where required.
- **Preserve:** Stored chats, existing chat search and its database, saved memories. **Do not** implement borrowed context from a neighbouring message (G.5b cutter rule 6), or generate vectors here.
- **Verify:** New/edited/deleted/hidden/unfinished/failed cases, G.5b's step-8 search-text tests, and interrupted updates leaving the previous correct state intact.

- **Goal:** the four stores exist, and one chat's passages can be brought
  up to date one message at a time (G.5, G.5a rules 1, 3, 4, 7, 9).
- **Read:** G.4, G.5, G.5a, G.5b cutter rules 5 and 7, G.7;
  `chatsearch/ChatSearchStore.kt` and `ChatSearchIndexManager.kt` for the
  encrypted-database and revision method; `SummarizerController.kt`
  (`Snapshot`, `Entry`) for the model-facing text; `MessageVisibility.kt`,
  `MessageCompletionState.kt`, `MessageIdentity.kt`.
- **Build:** the encrypted database with the four stores. A function that
  takes a chat's current messages, compares them with the index by
  permanent ID and fingerprint, and replaces only what changed, in one
  transaction per message. Eligibility. Search text, including overlap
  between passages of the same message. Borrowed context from a
  neighbouring message is not built (G.5b cutter rule 6). Scope stamping from
  values passed in.
  Vector rows are created as "waiting"; no vectors are made in Step 8.
- **Confirm in the code and record in this plan:** which stored field
  holds the reply version the AI is sent.
- **Must not change:** the chat search feature or its database, the stored
  chats, the saved-memory database.
- **Done when:** correctness tests pass for new, edited, deleted, hidden,
  unfinished, and failed messages; the tests listed under "With the
  search text, in Step 8" at the end of G.5b pass; and a run interrupted
  halfway leaves the previous correct state.

### Step 9: background embedding of passages (Hard)

**Agent checklist for this step**
- **Prerequisites:** Steps 1 and 8 and their queue/storage interfaces.
- **Change:** Embed waiting passages behind live work, check the complete search text with the real tokenizer, reuse exact-match vectors, remove unreferenced or obsolete-model vectors, and store per-chat progress. Pause/resume and retain work across app close/reopen.
- **Preserve:** A passage too large to embed is marked failed rather than silently truncated. An edit during embedding invalidates the stale result. Running work **continuously after the app closes** belongs to Step 13, not this step.
- **Verify:** Never embed truncated text; identical search text is embedded once; stale vectors are discarded; live work waits behind at most one running background read.

- **Goal:** waiting passages get their vectors in the background, and
  vectors are reused and removed correctly (G.5a rules 2, 3, 5, 6).
- **Read:** G.5a; the Step 1 queue; `Librarian.kt` for how missing
  saved-memory vectors are repaired in the background today.
- **Build:** a background worker that takes waiting passages one at a
  time, checks the search text with the real tokenizer, records as failed
  any that does not fit, makes the vector, and stores it by
  search-text fingerprint. Vector reuse. Removal of vectors nothing uses
  and of an old model's vectors. Index state per chat. It can be paused
  and resumed, and it survives the app being closed and reopened.
- **Not in Step 9:** running after the app is closed (Step 13), any screen.
- **Done when:** tests show no search text is ever cut off, an edit during
  embedding discards the stale vector, identical search text is embedded
  once, and a live request waits for at most one background read (G.5a
  rule 6).

### Step 10: keeping the index in step with the chats (Hard)

**Agent checklist for this step**
- **Prerequisites:** Steps 8–9. Read every event in G.12 before adding hookups.
- **Change:** Minimal background-index connections for save, completed reply, hide/show, delete, rename, restore/merge, and model change; index eligible existing chats newest first. Record scope at turn completion **even when the saved-memory database is not provisioned**, without depending on the early-returning `recordTranscriptTurn()` hookup.
- **Preserve:** Existing behavior and responsiveness of each affected flow; no synchronous indexing in a chat operation. Confirm and record how much older conversation has stored scope.
- **Verify:** A test for each G.12 upkeep event, including both-phone history merges, old-chat renames, and turns with an unprovisioned saved-memory database.

- **Goal:** every event in G.12 updates the index correctly.
- **Read:** G.12, G.5a rules 4, 8, 9, G.7;
  `chatsearch/ChatSearchIndexManager.kt` and where it is called from
  `ChatPreferences.kt`; `ChatRenameTransaction.kt`; `chatdeletion/`;
  `backup/portable/ChatMergePlanner.kt`; `ChatSetSourceGeneration`;
  `TranscriptRecorder.kt` and its one call in `ChatActivity.kt`.
- **Build:** the hookups, each as small as possible and each calling code
  in the new files: after a chat is saved; when a reply finishes; when a
  message is hidden or shown; when a chat is deleted or renamed; after a
  restore or merge; when the embedding model changes. Scope is stamped
  where the finished turn is already recorded, but not inside
  `recordTranscriptTurn()` in `ChatActivity.kt`: that function returns at
  once when the saved-memory database is not set up
  (`MemoryStore.isProvisioned()` is false), so a hookup there would miss
  the companion, project, and roleplay scope of every turn in that case.
  The scope is recorded whether or not the saved-memory database is set
  up, and a test shows it. Indexing existing chats,
  newest first.
- **Confirm in the code and record in this plan:** how much existing
  conversation has a recorded scope.
- **Must not change:** how any of those flows behave or how long they
  take. Indexing is always handed to the background.
- **Done when:** a test for each row of the G.12 table passes, including
  restore, merge of two histories, and rename of an older chat.

### Step 11: the setting, status, delete, and error notices (Careful)

**Agent checklist for this step**
- **Prerequisites:** Steps 9 and 10. **Stop for owner decisions G20, G28, and G31 before building their wording or behavior.**
- **Change:** Only the Conversation Index settings and messages in G.16a and G.13a/G.13b: setting/status, database-integrity line, deletion confirmation, and approved error/no-model explanations. Use shared styles. G27 is decided: automatic repairs do not interrupt the user; deduplicate notices for the same ongoing condition.
- **Preserve:** All other RAG Active Memory Settings behavior. Index Conversations starts **off** and cannot turn on without an installed embedding model.
- **Verify:** Enabling starts indexing while the app is open; deletion removes derived index data and turns indexing off; approved errors explain causes and next actions. Update `ui-style-adoption.md` with the UI change.

- **Goal:** the Conversation Index section of G.16a, the conversation
  index's line on the Check Database Integrity button (G.13a), and the
  error notices (G20).
- **Before building, the owner approves:** the final wording and actions
  for every user-facing notice (G20, G31), written to G.13b; and G28.
  G27 is decided: automatic repairs do not interrupt the user.
- **Read:** G.16a; G.13a; G.13b; `ui-style-guide.md` in full for the parts
  used (toggle rows, section headings, destructive buttons, two-action
  dialogs); `ui-style-adoption.md`.
- **Build:** exactly what G.16a lists for RAG Active Memory Settings, with
  the approved wording; the notification classification in G.13a; and
  one-notice-per-ongoing-condition deduplication. Every visible value comes from a shared style; a
  missing style is created and added to the guide. `ui-style-adoption.md`
  is updated.
- **Must not change:** any other part of RAG Active Memory Settings.
- **With no embedding model:** the setting cannot be turned on, and the
  user is told why and where to install one. That wording is approved
  first (G31).
- **Done when:** turning the setting on starts indexing while the app is
  open; deleting the index removes everything derived and turns the
  setting off.

### Step 12: the progress icon and pop-up (Careful)

**Agent checklist for this step**
- **Prerequisite:** Step 11. The icon and pop-up's placement and wording are already specified in G.16a.
- **Change:** Add the chat-header progress icon and shared-style pop-up in new files, with only the small header hookup needed; allow pause and resume.
- **Preserve:** The rest of the chat header. Show the icon **only** while indexing is active or paused; paused icon is still, running icon spins.
- **Verify:** The percentage reflects actual completed work, the correct running/paused states appear, and pause/resume work.

- **Goal:** the chat header icon and the progress pop-up of G.16a, with
  pause and resume.
- **Read:** G.16a; `ui-style-guide.md` in full for the parts used
  (Progress pop-up, header action icons); `ui-style-adoption.md`.
- **Build:** the icon and pop-up in their own files, with only the small
  hookup the chat header needs.
- **Must not change:** any other part of the chat header.
- **Done when:** the icon appears only while indexing runs or is paused;
  the percentage comes from work actually done; pause and resume work.

### Step 13: indexing after the app is closed (Hard)

**Agent checklist for this step**
- **Prerequisite:** Step 12. **Approval gate:** The user-visible message for indexing stopping for the day must be shown to and approved by the owner before implementation.
- **Before code:** Check **current** Android guidance on foreground-service limits and WorkManager quotas; choose the supported approach and explain the choice and tradeoffs to the owner. The limits recorded below are reference information, not a substitute for current verification.
- **Change:** Continue indexing after the app closes with the G.16a notification and a real percentage; handle Android stop signals and subsequent resumption.
- **Preserve:** Previously finished work. Never crash, discard completed progress, or start a service when its allowed running time is exhausted.
- **Verify:** The app-closed work, visible notification, safe system stop, and restart from the last completed point.

- **Goal:** indexing continues after the app is closed, with the
  notification in G.16a.
- **Read:** G.14 (Battery); `MemoryAnalysisForegroundService.kt` for how
  the app already keeps work running with a notification.
- **Build:** the background service and its notification.
- **Android's limit (checked against Android's documentation,
  2026-11-12).** For apps built for Android 15 or later, as this one is,
  background work of the "data sync" type may run for 6 hours in any 24.
  Android counts each type separately, and the 6 hours are shared by
  every service of that one type. Four parts of this app are already of
  that type and draw on the same 6 hours: Summarizer, image generation,
  Memory Assistant analysis, and restore. When
  the time is up the system tells the service, which must stop within a
  few seconds or the app is crashed. Opening the app resets the clock.
- **So:** indexing must stop cleanly when told, keep everything it
  finished, and continue the next time the app is opened. It must not
  start when the day's time is already used up.
  `MemoryAnalysisForegroundService.kt` already handles this stop signal.
- **To decide at the start of Step 13, before any code:** whether to
  use this kind of service or Android's WorkManager, which Android's
  documentation suggests instead. WorkManager has its own limit (checked
  against Android's documentation, 2026-11-12): from Android 16, a job's
  running time counts against a quota set by how often the app is used,
  even when the job started while the app was open or runs alongside a
  foreground service, and the system stops a job that runs out. The
  builder reads Android's current limits for both and reports the choice
  and the reason to the owner. Whichever is chosen must stop safely when
  told, keep everything finished, and continue later. What the user is told when indexing stops for the day
  is shown to the owner first.
- **Done when:** indexing continues with the app closed; the notification
  shows a real percentage; a stop by the system loses no finished work;
  and a test shows indexing picks up where it stopped.

### Step 14: retrieval (Hard)

**Agent checklist for this step**
- **Prerequisites:** Step 6, Steps 1 and 7–10, and Step 3. **Approval gate:** G36 must approve the complete G.6/G.8 retrieval and delivery design before coding.
- **Change:** The current-chat-only retrieval engine behind G.8a interfaces: implement G.6 retrieval stages **1–8** with G.9b/G.10 query handling, scoring, gates, eligibility, and measured time allowance.
- **Preserve:** No cross-chat recall, no request assembly, no paid calls, no forced match when nothing qualifies. **G.6 retrieval stage 9 and the optional re-ranker are not in this build.**
- **Verify and report:** Use Step 6's prewritten questions and the real embedding model. Score each question individually for relevant exchanges found and unrelated exchanges left out; record results as the work order requires.

- **Goal:** given a chat and a message, return the exchanges to offer,
  with every score and reason (G.6, G.9b, G.8a points 1 and 2).
- **Read:** G.6, G.8a, G.9b, G.10; `LiveRetrieval.kt` and
  `KeywordText.kt`; Step 6's `conversations.json`.
- **Build:** retrieval stages 1 through 8 of G.6 (retrieval stage 9 is not
  in the first build), using the method from Step 3,
  as plain Kotlin behind the G.8a interfaces. The time limit. Nothing is
  added to any request.
- **Done when:** Step 6's questions are scored with the real model's
  numbers, each sample checked on its own as in Step 0, and
  `conversation_recall_baseline.md` is created with every sample's
  "found" and "left out" results plus totals by situation.

### Step 15: the inspector and the measurements (Careful)

**Agent checklist for this step**
- **Prerequisite:** Step 14, with Step 6 quality cases available. **Approval gate:** Show all inspector-screen labels to the owner as one list before implementation.
- **Change:** The **Conversation Recall Test** inspector, opened from RAG Active Memory Settings beside Memory Search Test (G.8b), showing which exchanges a typed question would find and why; measure the G.15 retrieval starting values. Do not show assembly exclusions or "what was sent" as if computed; Step 16 adds those after assembly exists.
- **Preserve:** This is a debugging and measurement step, **not** live request injection. Do not add recall to outgoing requests until Step 16.
- **Verify and report:** Record the measured starting values in `conversation_recall_baseline.md`, and distinguish build-environment measurements from actual search time on the owner's phone.

- **Goal:** a way to type a message for a chat and see what would be
  recalled and why, and measured starting values for the numbers.
- **Placement and name are decided (G.8b).** Before building, the screen's
  labels are shown to the owner as one list.
- **Done when:** the retrieval-related numbers in G.15 have measured
  starting values recorded in `conversation_recall_baseline.md`, with
  search time on the owner's phone. Assembly-related measurements and
  inspector fields wait for Step 16.

### Step 16: context assembly (Hard)

**Agent checklist for this step**
- **Prerequisites:** Steps 14 and 15. **Approval gate:** Obtain the AI-facing text, “what was sent” wording, recall-room control wording, debug lines, and the G24 decision **before** coding any undecided behavior.
- **Change:** One shared assembly implementation connected minimally to **both** request-building paths. Add only eligible original exchanges, within recall's independent limits; label speaker/date/excerpts, show what was sent, and perform the final whole-request size check. Extend Step 15's inspector with actual assembly outcomes, including exclusions and their reasons.
- **Preserve:** When recall is off, the request is **identical** to today's request. Never displace history, summaries, saved memories, or other content to make room for recall; never store recalled text as new chat content.
- **Verify:** Every applicable G.15 “what was sent” test passes, both request paths add identical recall text, and timeout/overflow produce the existing request without losing its other content.

- **Goal:** recall is added to real requests (G.8, G.8a point 3).
- **Before building, the owner approves:** the exact text given to the
  AI; the wording for the "what was sent" group, the recall room setting,
  and the debug view lines; and G24. Everything else for Step 16 is
  decided (G.8b).
- **Read:** G.8, G.8a; both request builders in `ChatActivity.kt`;
  `memory/enforcer/PromptAssembler.kt`; `util/RequestCapacity.kt`.
- **Build:** assembly in its own file, called from both request builders
  with the smallest possible hookup. The size limits, the final size
  check, excerpts, date order, labels, and the "what was sent" view.
  Extend the Step 15 inspector using this shared assembly's real outcomes,
  not a second implementation or guessed pre-assembly exclusions.
- **Must not change:** anything a request contains when recall is off.
- **Done when:** all quality tests in G.15 pass at the "sent" point, both
  request paths add the same text, and the final "sent" results are
  recorded in `conversation_recall_baseline.md`.

## G.16a The approved screens for indexing (Owner, 2026-11-11)

Approved by the owner on 2026-11-11: placement, wording, and that Index
Conversations starts off.

**In the chat header.** A small indexing icon, beside the existing icon
that appears only when needed. It is shown only while indexing is running
or paused. It spins while running and is still when paused. Tapping it
opens the progress pop-up.

**The progress pop-up,** using the shared Progress pop-up style:

| Part | Wording |
|---|---|
| Title | Indexing Conversations |
| Percentage | A real percentage, based on messages processed |
| Status line, running | 128 of 310 conversations indexed (real numbers) |
| Status line, paused | Paused |
| Explanation | This runs in the background. You can keep chatting. |
| Buttons, running | Pause, Close |
| Buttons, paused | Resume, Close |

**The notification while indexing runs after the app is closed:**

| Part | Wording |
|---|---|
| Title | Indexing Conversations |
| Text | 42% done. Tap to open. (real percentage) |

**In RAG Active Memory Settings** (M.11; placed in Advanced Memory Settings
after Librarian / Embeddings before the 2026-11-16 reorganization), a new
section above the test rows at the bottom:

| Part | Wording |
|---|---|
| Section heading | Conversation Index |
| Toggle | Index Conversations |
| Toggle explanation | Builds a searchable index of your conversations on this device. Indexing is entirely local. |
| Status | 310 conversations indexed (real number) |
| Button | Delete Conversation Index |
| Confirmation title | Delete Conversation Index? |
| Confirmation text | This removes the search index. Your conversations are not changed. |
| Confirmation buttons | Cancel, Delete |

**With no embedding model installed.** The earlier "Keyword search only"
status is withdrawn. On 2026-11-12 the owner decided that conversation
recall requires the embedding model (G30), so the toggle cannot be turned
on without one. The wording that tells the user why is G31.

**Behaviour approved with it:**
- Index Conversations starts off. Turning it on starts background
  indexing.
- Deleting the index also turns the setting off, so it is not rebuilt
  against the user's wishes.

**A sentence that was removed.** The approved explanation had a third
sentence saying recall is turned on "for a chat". That described a
per-conversation setting the owner had not chosen, and on 2026-11-11 the
owner ruled that recall is one setting for all chats (G1). The sentence is
removed and is not built. A sentence telling the user that recall sends
earlier conversation to the AI is still needed; its wording is proposed to
the owner with the recall setting (G17).

## G.17 Open decisions for the owner

Asked one at a time, and only when the Step that needs the answer is
about to start. Each has a recommendation.

| # | Question | Recommendation | Needed before |
|---|---|---|---|
| G1 | Is recall on for every chat, or chosen per chat? | **Decided (owner, 2026-11-11):** one setting in a settings menu, applying to all chats. Not chosen per conversation. Which menu, its wording, and whether it starts on or off are G17 | Decided |
| G2 | How large is recall's own size limit, and can the user set it? | **Decided (owner, 2026-11-11):** a starting amount from testing, adjustable in RAG Active Memory Settings (G.8b, M.11). Its label is shown to the owner before Step 16 | Decided |
| G3 | When a chat no longer fits the model, does anything change? | **Decided (owner, 2026-11-11):** no. It warns or blocks as today | Decided |
| G4 | Does recall apply in Experimental Mode? | **Decided (owner, 2026-11-11):** no | Decided |
| G5 | Are attached files and images indexed? | **Decided (owner, 2026-11-11):** no, not in this work | Decided |
| G6 | When are existing chats indexed? | **Decided (owner, 2026-11-11):** in the background, never on opening a chat. Proposed details: newest first, with progress shown and a way to pause | Decided |
| G7 | Do backups carry the index? | **Decided (owner, 2026-11-11):** no. It is rebuilt after a restore | Decided |
| G8 | Which jobs does the live AI worker do, and how is it turned on? | Open. Start with marking what matters. The owner chose one app-wide setting for recall, not a per-conversation one | The live AI worker |
| G9 | Across chats, which ordinary chats may see each other? | Same companion or same project only, at first | Recall from other chats |
| G10 | Is a recalled exchange carried over like a saved memory? | **Decided (owner, 2026-11-11):** yes, under the same rule | Decided |
| G11 | What does the user see when recall adds something? | **Decided (owner, 2026-11-11):** listed on the existing screen that shows which memories were sent, as a second group. Its wording is shown to the owner before Step 16 | Decided |
| G12 | Did the owner make the "seen and not objected to counts as approved" rule recorded in Part E of the memory plan? | **Decided (owner, 2026-11-11):** silence does not count as approval. Explicitly approved decisions are kept; everything else in the memory plan is relabelled as proposed | Decided |
| G14 | Does background indexing of existing chats continue after the app is closed? | **Decided (owner, 2026-11-11):** yes. The notification this requires is part of G15 | Decided |
| G15 | Where does the user see indexing progress, pause it, and delete the index, and what does the notification say? | **Decided (owner, 2026-11-11):** as recorded in G.16a. Index Conversations starts off | Decided |
| G16 | Does recall apply in hands-free voice chat? | **Decided (owner, 2026-11-11):** yes, with a small measured time allowance. Skipped only when the allowance is exceeded | Decided |
| G17 | Which settings screen holds the recall setting, what is it called, what does its explanation say, and does it start on or off? | **Decided (owner, 2026-11-11):** as recorded in G.8b | Decided |
| G18 | Does the per-chat memory switch also stop recall in that chat? | **Decided (owner, 2026-11-11):** yes | Decided |
| G19 | The exact Error Log lines, the database check's line and hint for the conversation index, and the wording that tells the user of a failure | **Decided (owner, 2026-11-11):** as recorded in G.13a | Decided |
| G20 | Which failures receive a user-facing notice, and what does each notice say? | **Partly decided (owner, 2026-11-12):** automatic repairs do not interrupt the user. An affected turn, stopped feature, prevented setting, or failure with a real user action receives a plain notice under G.13a. Exact proposed wording and actions in G.13b are approved before Step 11 or Step 13 builds them | Step 11 and Step 13 |
| G21 | Where the recall test screen goes, and its name | **Decided (owner, 2026-11-11):** Conversation Recall Test. Placement updated by the owner's 2026-11-16 reorganization: a row beside Memory Search Test at the bottom of RAG Active Memory Settings (M.11) | Decided |
| G22 | May recall and carry-over add to the existing memory debugging view? | **Decided (owner, 2026-11-11):** yes. No new general application log entries | Decided |
| G23 | Which Step is built first? | **Decided (owner, 2026-11-11):** the long-message fix is the first user-visible fix. In the corrected numbering it is Step 2, after completed Step 0 and the Step 1 queue-safety prerequisite (G.16) | Decided |
| G24 | If the user turns Index Conversations off while Conversation Recall is on, what happens to recall? Also undecided: whether turning Conversation Recall on turns Index Conversations on (G.8b says only that it "may"), and what happens to recall when the index is deleted | Open. Indexing is never silently restarted (G.8b) | Step 16 |
| G25 | Withdrawn on 2026-11-12. With no embedding model nothing can confirm a match, so messages are searched as today (G.10). Nothing changes for those users, so there is nothing to decide | Withdrawn | |
| G26 | What replaces inaccurate saved-memory diagnostic lines? | **Decided for Step 2:** G.13b records approved wording and its locations, including context-specific repair corrections. Technical embedding terminology is allowed. Model-load and self-check notices remain Step 11 | Decided for Step 2 |
| G27 | Which failures interrupt the user? | **Decided (owner, 2026-11-12):** automatic repairs do not raise a notice. Notify the user when the failure affected a turn, stopped a feature, prevented a setting, or has a real action the user can take. Say what happened, what is safe, and the action when one exists. Do not repeat the same ongoing failure every turn | Decided |
| G28 | Chats that have never been reopened cannot be indexed until they are opened (G.5a rule 7). What does the progress count show for them? | Open. Recommended: count only what can be indexed now, and say plainly how many conversations will be indexed when they are next opened | Step 11 |
| G29 | To find a small detail in a long or dictated message, should the app also search with overlapping pieces of the message, cut between words and never at periods (G.10)? | **Decided (owner, 2026-11-12):** yes, on the agent's recommendation | Decided |
| G30 | Is recall offered without an embedding model? | **Decided (owner, 2026-11-12):** no. Conversation recall requires the embedding model. Lorebook keyword matching is separate and keeps working whenever the lorebook is on | Decided |
| G31 | What the user sees when the required on-device model is missing, removed, or unusable | Open for exact approval. G.13b proposes separate wording for trying to enable the feature with no model and for an installed feature becoming unavailable, each with an Open Model Settings action | Step 11 |
| G32 | Can the user choose how many of their previous messages are searched for follow-ups (G.9a)? | **Superseded (owner, 2026-11-16):** searching with the previous messages is not the carry-over method and is not a context-assistance choice. Replaced by G37 and G38 | Superseded |
| G33 | Is the embedding model's reading limit for saved memories raised from 512 tokens to the model's real 2,048 (G.2)? | Follows from the owner's decision of 2026-11-12 that a memory may hold 1,500 words (G34): that limit is not true unless the model reads that much. Written into Step 1. Speed and memory use on the phone are checked before it ships | Step 1 |
| G34 | How is a saved memory limited to what the model can read, and how is its length shown? | **Decided (owner, 2026-11-12):** a word count under the memory box, aligned right, for each memory; at 1,500 it turns red and reads "Word Limit Reached: 1,500 Words Maximum"; the Memory Assistant splits a large subject into separate proposed memories and never fails or discards one for length. Still open, asked before Step 5: the count's text before the limit; what happens on saving over the limit; the wording when the model's real limit is reached below 1,500 words; how existing over-length memories are marked; the words added to the Memory Assistant's instructions | Step 5 |
| G35 | The design of the conversation index as a whole (G.5, G.5a, G.5b, G.7): a new encrypted database on the phone holding where each piece of a conversation is, its searchable words, and the embedding model's numbers; what is and is not stored; and how it is kept correct after edits, deletions, and restores | Open. The agent explains it in plain terms and the owner approves it once, before Step 7. No builder treats it as approved before then | Step 7 |
| G36 | The design of retrieval and of what the AI is handed, as a whole (G.6, G.8) | Open. The agent explains it in plain terms and the owner approves it once, before Step 14 | Step 14 |
| G37 | How do follow-ups keep their saved memories? | **Decided (owner, 2026-11-16):** through context assistance: Off, Code-based subject tracking, API AI with its own model in the initial rollout, and Local AI later; separate from the embedding model (G.9, G.9b, G.9c) | Decided |
| G38 | The context-assistance setting's label and explanation, which choice it starts on, how Code-based rebuilds the subject after the app restarts, and whether Local AI is shown before it is built | Open. Placement follows M.11: RAG Active Memory Settings. Recommended start: Code-based, the only free method that held up on the held-out set. Recommended: Local AI is not shown until it exists | Step 4a |
| G39 | The API AI configuration screen (M.4): provider connection, model list and which models qualify, which turns it reads, instruction text, model settings, time allowance, failure behavior and notice, cost display, privacy disclosure, behavior with no model configured, where the screen opens from and its labels, a test action, logging; also the output check and the model and cost of its scored run. **Owner (2026-11-16):** the screen is dedicated and its model selection is independent of the chat, Summarizer and embedding models | Open (G.9b, M.4) | Step 4b |
| G40 | May a +3 memory pass without the relevance gate's full stand-out? | **Decided (owner, 2026-11-16):** only under the top-match condition in G.10. The unrestricted lower bar is rejected | Decided |
| G41 | Which other embedding models are offered, local or API, and their privacy and cost | Open. The architecture is required now (G.9c, M.5) | Later |
| G42 | The build order in M.2 | **Decided (owner, 2026-11-16):** as M.2. Provenance first, so the Memory Scanner records sources before the owner builds a memory collection | Decided |
| G43 | The memory provenance design (M.3) and its details | **Decided (owner, 2026-11-16):** separate labeled lines (Speaker; Glamour only for the user's messages when one was in use; Date fully spelled out; Source Message); the speaker is whoever said the message; profile names follow renames by ID and keep the last known name after deletion; an unavailable source reads Unavailable and a deleted one reads Deleted, and the memory is never removed; no excerpts; the user cannot edit sources, the creation date or message dates; the event date is the important date, one field, built in Phase 4; the citing instruction wording was left to the agent (M.3). Adding sources by hand is a possible later feature | Decided |
| G44 | Ship Step 4a's setting with Off and Code-based before Step 4b adds API AI, or hold it until all three exist | Open. Recommended: ship Step 4a first; its benefit is measured and free | Step 4a |
| G45 | Include the frozen-path lorebook repeat check (memory plan step 1.13, A.9) in Step 4a | Open. Recommended: yes. It changes only that a memory repeating a lorebook entry is no longer sent on the frozen path, as it already is not on the other path | Step 4a |

## G.18 Research behind this plan (2026-11-10)

Read by the agent from the sources named. Figures are the sources' own.

| Question | Finding | Source |
|---|---|---|
| Is sending a whole long history best? | No. Whole history scored 54.15; retrieved topic segments scored 71.57. Another study found large drops with a 113,000-token history against a focused excerpt | Microsoft SeCom (ICLR 2025); Chroma, "Context Rot" |
| Where do models attend? | Most to the start and end of the input, least to the middle | "Lost in the Middle" (Liu et al., 2023) |
| What size should a piece be? | Topic segments 71.57, single turns 65.58, whole sessions 63.16 | Microsoft SeCom |
| Who can split by topic? | GPT-4 did. With a small model the score fell to 61.84, and on a second test it lost to single turns | Microsoft SeCom, Table 11 |
| Does automatic semantic chunking help? | Not consistently. On realistic text, fixed-size chunks did as well or better | Qu, Tu and Bao, NAACL 2025 Findings |
| Meaning or keywords? | Both. Keyword search beat meaning search by up to 11.98 points on single turns and sessions; with topic segments the gap was about 2 | Microsoft SeCom |
| Saved facts or the original conversation? | Both. Facts alone 71.2%; with the original conversation 86.1% | Redis, LongMemEval study |
| Does linking facts to conversation help? | Yes. Searching with extracted facts attached raised recall about 9.4% and answers 5.4% | LongMemEval (ICLR 2025) |
| Do dates help? | Date-aware search raised recall 6.8% to 11.3% on time questions, using a strong paid model | LongMemEval |
| Does a line of context on each piece help? | Missed retrievals fell 35%, and 49% with keyword search. It used a paid call per piece | Anthropic, "Contextual Retrieval" |
| Do summary layers help? | Up to 20 points on one long-document test, built with paid summaries | RAPTOR (ICLR 2024) |
| Do knowledge graphs help? | 71.2% against 60.2% in the maker's own test; an independent test measured 63.8% | Zep paper; third-party comparison |
| How good is the app's embedding model? | Ranked first among models under 500 million parameters by its maker. English score 69.67 at full size, 68.37 at the app's 256 numbers, 69.31 for the q4 variant | EmbeddingGemma model card |
| Does a re-ranker help? | Yes in published tests: it took Anthropic's missed retrievals from 49% fewer to 67% fewer. It runs once per candidate, so it adds time | Anthropic, "Contextual Retrieval"; Jina and Mixedbread model reports |
| How should meaning and keyword results be combined? | A weighted blend of the two scores beat reciprocal rank fusion on familiar and unfamiliar text, was less sensitive to its settings, and could be tuned from a handful of examples | Bruch, Gai and Ingber, "An Analysis of Fusion Functions for Hybrid Retrieval" |
| Does the order of retrieved passages matter? | Not reliably. Rearranging passages into "preferred" positions did no better than random order in one study, and results differ between models | "Do RAG Systems Really Suffer From Positional Bias?" (EMNLP 2025) |
| Is there one best system? | No. Top scores are self-reported and no neutral test compares the designs under the same conditions | LongMemEval and LoCoMo comparisons |
| What does a working chat client do? | Searches with the last 2 messages, protects the last 5, inserts 3, cuts long messages at 400 characters, stores each message separately, and places recalled messages prominently even when they are already in the request | SillyTavern documentation |

Two cautions:

- SillyTavern searches with the last 2 messages. #180 measured that gluing
  recent messages onto the search hurt saved-memory search in this app, so
  that setting is not adopted without testing.
- The widely repeated "256 tokens with 50 overlap" figure could not be
  confirmed from its named source, which studied documents, not chats.

Sources:

- Microsoft SeCom: https://arxiv.org/abs/2502.05589
- LongMemEval: https://arxiv.org/abs/2410.10813
- Redis study: https://redis.github.io/redis-ai-research-public/longmemeval-agent-memory/
- Semantic chunking study: https://arxiv.org/abs/2410.13070
- Chroma, "Context Rot": https://research.trychroma.com/context-rot
- Anthropic, "Contextual Retrieval": https://www.anthropic.com/engineering/contextual-retrieval
- RAPTOR: https://arxiv.org/abs/2401.18059
- EmbeddingGemma model card: https://huggingface.co/google/embeddinggemma-300m
- SillyTavern chat vectorization: https://docs.sillytavern.app/extensions/chat-vectorization/
- Fusion functions for hybrid retrieval: https://arxiv.org/abs/2210.11934
- Positional bias in retrieval: https://arxiv.org/abs/2505.15561

## G.18a What established open-source systems do (2026-11-12)

**Owner (2026-11-12):** where the right decision is not known, look at
what established, proven systems do and use that to decide. Custom design
is acceptable. Credit can be given in the app's About screen for anything
shared. The app is never to be sold, by the owner or by anyone else.

Read on 2026-11-12 from each project's documentation or source code.

| Decision | What they do | This plan |
|---|---|---|
| Cutting text into pieces | SillyTavern: 400 characters, cut at a paragraph, a line, or between words, no overlap for chat messages. GPT4All: 512 characters. LangChain's standard splitter: paragraph, line, then between words, with overlap | Same cut points (G.5b) |
| What the AI is handed | SillyTavern finds a piece and hands over the whole original message, labelled with who said it, under "Past events" | The whole exchange (G.8) |
| What to search with | SillyTavern: the last 2 messages joined. RisuAI: the last 3 messages, each searched on its own, later ones counting more. AnythingLLM: the latest message only | The newest message, plus what context assistance supplies (G.9b). Searching the previous messages (G.9a) was measured and not used |
| How many are sent | SillyTavern 3, GPT4All 3, AnythingLLM 4 | 3 (G.8) |
| The cut-off | SillyTavern: a fixed 25% score, then the best 3 | A stricter gate, this app's own, because a wrong result is worse than a missed one (G.6) |
| Keywords as well as meaning | SillyTavern, GPT4All, AnythingLLM, RisuAI: meaning only. Open WebUI and Mem0: both, with an optional re-ranker | Both (G.6) |
| No embedding model | None of the chat apps offers recall without one | The embedding model is required (G30) |
| Follow-up messages | AnythingLLM fills empty room with what was used in recent replies, newest first, with no relevance check. Open WebUI and LlamaIndex have an AI model rewrite the follow-up, a paid call | Context assistance: Code-based subject tracking, or an optional API AI rewrite (G.9b) |
| A long message about several things | RisuAI's experimental mode searches each paragraph of the message separately. Others offer a paid rewrite | Overlapping pieces (G.10) |
| Keeping the index correct | SillyTavern keeps a fingerprint per message; on every edit, deletion, or new message it adds what is missing and deletes what is gone | The same idea (G.5a rule 4) |
| Prompt caching | SillyTavern's documentation warns that recall works against prompt caching, because it changes text early in the request | Recalled text sits beside the newest message, which leaves the earlier text unchanged. To be confirmed with a caching provider in Step 16 |

**Not verified:** Open WebUI's default numbers.

**Licences.** Only approaches are taken from these projects, which no
licence restricts. Code is a different matter:
- SillyTavern and RisuAI are understood to be under licences (AGPL and
  GPL) that would require this whole app to be released under the same
  terms, which allow others to sell it. Their code is never copied.
- AnythingLLM, LlamaIndex, LangChain, GPT4All, and Mem0 are understood to
  be under permissive licences that allow copying with credit.
- These licences were not re-read on 2026-11-12. Before any code is
  copied from any project, its licence file is read and the owner is
  told.

Sources:

- SillyTavern chat vectorization: https://docs.sillytavern.app/extensions/chat-vectorization/
- SillyTavern vector extension source: https://github.com/SillyTavern/SillyTavern/blob/release/public/scripts/extensions/vectors/index.js
- RisuAI HypaMemory V3 source: https://github.com/kwaroran/RisuAI/blob/main/src/ts/process/memory/hypav3.ts
- AnythingLLM chat helper source: https://github.com/Mintplex-Labs/anything-llm/blob/master/server/utils/helpers/chat/index.js
- GPT4All settings: https://docs.gpt4all.io/gpt4all_desktop/settings.html
- LangChain recursive text splitter: https://docs.langchain.com/oss/python/integrations/splitters/recursive_text_splitter
- LlamaIndex chat engines: https://developers.llamaindex.ai/python/framework-api-reference/chat_engines/condense_plus_context/
- Mem0 search: https://docs.mem0.ai/core-concepts/memory-operations/search
- Open WebUI retrieval: https://docs.openwebui.com/features/rag

## G.19 Rules for whoever builds a Step

A Step may be built by a different agent or model than the one that wrote
this plan. These rules keep that safe.

- **Read this plan and the files it names for your stage.** The memory
  plan (`Memory System/dynamic_memory_plan.md`) is background design for
  saved-memory work (its Parts B–E for Phases 2–6) and is not needed for
  the conversation index.
- **Build only the assigned stage or Step from M.2.** Do not start the
  next one. A session's changes go in one pull request (`CLAUDE.md`).
  Steps 0–3 have implementations; their repairs and verification are recorded in `memory baseline.md`. Do not begin Step 4a until G38 is approved; Step 4b also needs G39 and G44; memory provenance needs G43. Every stage follows the code
  workflow in `CLAUDE.md`.
- **Do not start a stage out of order or while its "Approve first" entry in
  M.2 is not satisfied.** Say what is waiting and stop.
- **Write results only to the destination table in G.16.** This plan may
  otherwise be edited only where the Step's work order explicitly says to
  record a confirmed code fact, and in the Build record.
- **Only "Owner" items are decisions.** Anything marked "Proposed" that
  the user would see, read, or pay for is shown to the owner before it is
  built: wording, screens, settings, defaults, and text sent to the AI.
- **Never change:** the stored chats; the Summarizer and its summaries; the
  existing chat search feature; saved-memory search results (except Step 1
  only for memories that exceeded the old 512-token reading limit, and
  Steps 2 and 4 as their work orders specify); the lorebook; what a
  request contains when recall is off. In Step 1, stored vectors and search
  results for memories that fitted inside the old limit remain unchanged.
- **Never remove anything from a request to make room for recall.**
- **Never add or change a log line** without the owner's approval. The
  owner has ruled which logs are used (G.13a). The lines recorded there
  are re-proposed under the plain-wording rule (G.13b) before the Step
  that writes them.
- **Put new code in its own files.** The chat screen's files are too large
  to change safely; add only the small hookup they need.
- **Never copy another screen or file** as a pattern. Follow
  `ui-style-guide.md` for anything visible.
- **If the code does not match what this plan says about it, stop and say
  so.** Do not guess, and do not copy another part of the app to fill the
  gap.
- **A Step is finished** when its tests pass, the local checks and Android
  Checks pass, its results are written to the required record, and one line
  saying what was built is added under "Build record" at the end of this
  plan. That is the only other place a builder
  may edit.

## Build record

One line per finished Step: the Step, what was built, and the pull
request. Detailed results stay in the file named by G.16.

- Step 0 — saved-memory benchmark completed for 86 samples; detailed
  results are in `memory baseline.md`.
- Step 1 — token counting, one priority queue for every embedding read
  (a live read can stop a running background read), and saved memories
  read up to 2,048 tokens with a resumable re-read of memories cut at 512;
  pull request #199. Measurements are in `memory baseline.md`.
- Step 2 — a message longer than 80 words is also searched in overlapping
  80-word sections (at most 8, provisional 1,500 ms allowance) after the whole message; PR #200. The allowance was initially soft; repair requests cooperative termination and bounds queue waiting, not native unwind time.
  By owner decision a section's match counts only when its meaning match
  is at least 0.48 and it shares a meaningful word with the
  memory, in addition to the existing relevance gate. Size, cap and threshold
  are provisional; this replaced the original "same gate as a whole message" proposal, which sent
  more wrong memories on the test set. The Memory Search Test and Memory
  Debug Log wording is the owner-approved wording, not the G.13b proposals.
  Results are in `memory baseline.md`.

- Step 2 repair audit (#201 and follow-up branch) — accurate section thresholds/near-misses/failures, bounded queue waiting with cooperative termination, native model lifetime protection, shared live/test matching and retrieval-to-prompt coverage. ONNX startup/API telemetry opt-outs also cover Silero. Original Python fixture baseline 69/6/19 is preserved. Initial Android ORT 1.20 results were 54/21/21 before sections and 69/6/21 with sections. The follow-up retains the same runtime/model and calibrates existing defaults: real Android results are now 54/21/17 and 69/6/17, with the restored per-message quality gate passing. Independent evaluation stays 4/5/0. All 313 token references and all 85 Android tests pass. Merged in #202; physical-phone timing remains unmeasured. Full evidence and timing limits are in `memory baseline.md`.

- Step 3 — a standalone search helper (`PreviousMessageSearch.kt`) that searches the new message and 0–3 previous user messages separately through the existing saved-memory search and returns candidates for the enforcer's selection: the new message's candidates first and unchanged, then older messages' in order of age, each memory once. It is connected to nothing: no setting, no screen, no change to sending. PR #203.

- Step 3 repair — the helper as merged in #203 described and measured its limit as Maximum Memories; it now only supplies candidates, and Maximum Memories, backfill and the +3 rule stay in the enforcer's selection. The owner's +3 rule (G.10) is applied to section matches in the search, the enforcer and the Memory Search Test, replacing the section-only exception. A message searched without its embedding is reported instead of passing unnoticed. The Step 0 conversations are scored through the enforcer's selection at Maximum Memories 1, 2, 3, 5 and 8 and checked against the expected answers. Results, costs and what Step 4 must verify are in `memory baseline.md`.

- Carry-over evaluation (before Step 4) — methods and +3 rules fixed in advance and scored on a development set and a held-out set with Android ONNX Runtime 1.20 similarities and desktop vectors. Results are in `memory baseline.md`; the owner's decisions are G37 and G40.
