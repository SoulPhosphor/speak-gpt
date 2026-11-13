# Conversation index, recall, and carry-over plan

Written 2026-11-10. This is the only document a builder needs for this
work. Section numbers keep a "G." prefix because this began as Part G of
the memory plan, `Memory System/dynamic_memory_plan.md`, which remains the record for
saved-memory search (its Parts A to F). References below to "Part A" or "Part E" mean that document. Numbered
Steps always mean the work orders in G.16 of this document.

**State on 2026-11-12.** Step 0 is complete. Its 86-sample measurements
are in `memory baseline.md`; they are not duplicated here. No application
feature in this plan is built yet. The next work order is Step 1. The plan
was reviewed and corrected on 2026-11-12: the long-message design (G.10), follow-ups
(G.9, G.9a), the cutter (G.5b), the build steps (G.16), the wording rule
(G.13b), and a comparison with established open-source systems (G.18a).
The open questions are in G.17. Where the "Next steps" list in the memory
plan disagrees with this document, this document is the current one.

## G.0 Status and how to read this plan

- **This plan is a proposal.** It was written on 2026-11-10 from the current
  code, the review of #180, and published research. Step 0 is complete; no
  application feature in it is built.
- **Two labels are used throughout:**
  - **Owner** means the owner said it on the date shown, or on 2026-11-10
    where no date is given. These are decisions.
  - **Proposed** means the agent recommends it. The owner has not approved
    it.
- **Names here are planning names,** not on-screen wording. Every label,
  explanation, and AI-facing text is shown to the owner before it is built.
- **Three kinds of statement, and which one a builder follows:**
  - **A work order** (a numbered Step in G.16) is the instruction. It says what to
    build, what not to build, and when the Step is done.
  - **A design section** (G.5 to G.15) explains the work order. Where it
    is marked Proposed, it becomes binding only for the Steps whose
    "Approve first" entry in G.16 is satisfied.
  - **A decision** (Owner, or a row in G.17) overrides both.
- **If two parts of this plan disagree, or a Step cannot meet all of its
  own conditions, the builder stops and says which parts.** A builder
  never picks one side quietly.
- **Rows and sections have similar names.** "G10" with no dot is row 10 of
  the decisions table in G.17. "G.10" with a dot is section 10.
- **The design covers the whole system,** including parts built later.
  The strict numbered order in G.16 decides the build order. It does not
  leave anything out of the design.

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
    borrowed context out of the first build.

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
- The user's previous message is searched as well, as a separate search
  that counts for less than the newest (G.9a). The number of previous
  messages is 1 by default and can be set from 0 to 3 (G32). Earlier
  messages are never glued onto the search text.
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

The scores and reasons at points 2 and 3 are what the Step 15 inspector
shows, so a wrong or missing recall can be explained, not guessed at.

## G.8b The recall setting and where recall applies (Owner, 2026-11-11)

Decided by the owner on 2026-11-11.

**The setting.**
- It is in **Memory Controls**, is called **Conversation Recall**, and
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
is adjustable in **Advanced Memory Settings**, not only on the test
screen. Its label and explanation are shown to the owner before Step 16.

**What the user sees.** Recalled exchanges are listed on the existing
screen that shows which memories were sent with a reply, as a second
group.

**Follow-ups.** A recalled exchange stays through follow-up messages
under the same rule as saved memories (G.9).

**The recall test screen** is in Advanced Memory Settings, Debug section,
beside Memory Search Test, and is called **Conversation Recall Test**.

**Debug information.** Recall and carry-over may add information to the
existing memory debugging view, so a wrong result can be explained. This
does not authorize any new general application log entries. The exact
lines are shown to the owner before Step 16.

## G.9 Carry-over for saved memories (Proposed)

**The problem.** The saved-memory text is rebuilt for every message and is
not kept in the chat history. A memory found for one message is gone for
the next unless that message finds it again.

**The rule (rewritten 2026-11-12 to use an established method, G.9a).**

- The user's previous message is searched again, on its own, alongside
  the new message. The number of previous messages is 1 by default and
  can be set from 0 to 3 (G32).
- A memory found through an earlier message must pass the same relevance
  gate against that message. Nothing is kept merely because it was sent
  before.
- Matches for the new message come first. Matches for the message before
  it come next, and for the one before that last.
- A memory therefore stays through a follow-up such as "What should I feed
  her?", and falls away after the subject changes: one message later by
  default.
- Only the user's messages are searched. The AI's reply alone can never
  keep a memory alive.
- Carried memories obey scope like any other.

**Two known limits, both measured on the test set (Step 0) and reported
to the owner before Step 4 ships.**
- **A memory can ride into a new subject.** With the setting at 1, the
  first message on a new subject is searched together with the message
  before it. If that earlier message names the old subject, the old
  memory can be sent once more, after the new subject's own matches. It
  stays for as many messages as the setting says, so "gone after one
  message" is only true when the setting is 1.
- **A second follow-up in a row can lose the memory.** With the setting at
  1, "How much should I feed her?" keeps the Pepper memory because the
  message before it named Pepper. A further "And how often?" is searched
  with "How much should I feed her?", which names nothing, so the memory
  can drop. A higher setting covers a longer run of follow-ups and lets
  an old subject linger longer. The two limits pull against each other.

How much less an earlier message counts is measured on the same set.

**Relation to recall.** Carry-over and conversation recall are separate.
They share this way of searching (G.9a) and the final size check (G.8),
and nothing else. Each has its own size limit.

A recalled exchange is carried over under the same rule (Owner,
2026-11-11; G.8b).

## G.9a Searching with the previous messages (Proposed; replaces the subject check, 2026-11-12)

The earlier version of this plan had a "subject check": a new component
with two unmeasured numbers that judged whether a message was still on the
previous subject. No established system does that. It is replaced by what
RisuAI does (G.18a).

- **What is searched:** the new message, and the user's previous
  messages: 1 by default, from 0 to 3 (Owner, 2026-11-12; G32).
- **How:** each is its own search. They are never joined into one text.
  #180 measured that joining recent messages hurt saved-memory search in
  this app; separate searches avoid that.
- **How results combine:** a match for the newest message outranks a match
  for an earlier one.
- **What it adds on the phone:** nothing new for the embedding model to
  read. The numbers the model made for an earlier message are kept while
  the chat is open and used again. Only those numbers are kept. The
  search itself is run again for every message sent, against the memories
  and the scope as they are at that moment, so an edited or deleted
  memory, a change of companion or project, or a different chat is always
  respected. The kept numbers are dropped when the chat is closed, when
  that earlier message is edited or deleted, and when the embedding model
  changes.
- **Used by:** carry-over for saved memories (G.9) and conversation recall
  (G.6 retrieval stage 1).
- **What it never does:** search with the AI's replies, or keep anything
  that does not pass the relevance gate.

## G.10 A small detail inside a larger message (Proposed; rewritten 2026-11-12)

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

**The rule proposed here: overlapping pieces, used only for finding
(G29).**
- The whole message is searched first, exactly as today.
- A message longer than one piece is also searched in pieces. A piece is
  cut at a paragraph or a line where there is one, otherwise between
  words. Periods play no part.
- Each piece overlaps the one before it by half. Any thought shorter than
  half a piece is therefore whole inside at least one piece.
- **A piece is only ever used to find a memory. It is never shown to the
  AI.** What the AI receives is the whole saved memory, or the whole
  earlier exchange.
- A message short enough to be one piece is searched exactly as today.
  There is no line between "long" and "short" beyond that.

**When a piece's match counts.** A wrong memory is worse than a missed
one. Established systems send their best few matches even when none fits
well; this app does not.
- **By meaning:** a piece passes the same gate as a whole message.
- **By keywords:** a keyword match from a piece counts only when meaning
  search agrees: the memory must also be close enough in meaning to that
  piece. "I'm worried about Pepper" is close in meaning to the Pepper
  memory. "It was about spring cleaning" shares the word "spring" with a
  memory about moving house in spring, and is not close to it in meaning.
  This check is this app's own addition and is measured on the test set.
- **With no embedding model, or for a memory that has no vector yet,**
  nothing can confirm the match, so only the whole-message search runs, as
  today.

**Order and room.**
- Everything that matches the whole message keeps its place, in today's
  order. Nothing that is sent today is pushed out.
- Memories found only through a piece come after those, best first, in
  whatever room the memory limit leaves.

**Time.**
- The number of pieces searched has a limit. When a message has more
  pieces than the limit, the pieces holding the rarest words (names and
  unusual terms) are searched first.
- This search has a time allowance. When it runs out, the pieces not yet
  searched are skipped and the message is sent with what was found. The
  whole-message search always runs.
- The words of every memory are read once per message and reused, so the
  keyword work does not multiply.
- **The message is read by the embedding model once.** The whole message
  and its pieces are cut and read one time per message sent. Saved-memory
  search and conversation recall share those results. Neither reads the
  message again.
- When the limit or the time allowance leaves part of a message
  unsearched, the Memory Search Test screen says which part.
- The piece size, the limit, and the time allowance are engineering
  numbers, measured on the test set and on a phone before this ships.

**What the user can see.** The Memory Search Test screen must show which
piece of the message a memory matched, and when the search ran out of
time. Without that, the screen can no longer explain why a memory was
sent. Its wording is shown to the owner before Step 2 is built.

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
| Repair failed | The conversation index could not be rebuilt. Index Conversations and Conversation Recall are off. Your conversations are safe and unchanged. Open Advanced Memory Settings to try indexing again. |

The former rule that every index error raises a persistent snack bar is
superseded by the owner's 2026-11-12 decision above. Step 11 may create a
shared snack-bar style if its approved notices need one; it does not create
or show snackbars for log-only automatic repairs.

## G.13b Plain wording and reviewed message proposals (Owner rule; exact proposals not yet approved)

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
  log: passage, vector, lexical, query, embed, cutter, and re-ranker.
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
| Turning on Index Conversations or Conversation Recall with no model installed | **Title:** On-Device Search Model Needed. **Text:** Conversation Recall needs an on-device search model. Install one under Librarian / Embeddings, then turn this setting on again. Your conversations were not changed. **Buttons:** Open Model Settings, Close |
| The installed model was removed or no longer loads | **Title:** Conversation Recall Is Off. **Text:** Conversation Recall is off because its on-device search model is no longer available. Your conversations and saved memories are safe and unchanged. Reinstall or choose a model under Librarian / Embeddings to use recall again. **Buttons:** Open Model Settings, Close |
| Recall fails for one turn and no user action is available | Conversation Recall could not check earlier messages this time. Your message was sent normally without recalled conversation. No action is needed unless this keeps happening. |
| Recall fails for one turn and the code knows an action | Conversation Recall could not check earlier messages because (plain cause). Your message was sent normally without recalled conversation. (Specific action.) |
| The index cannot be rebuilt | **Title:** Conversation Recall Is Off. **Text:** The conversation index could not be rebuilt, so Index Conversations and Conversation Recall were turned off. Your conversations are safe and unchanged. Open Advanced Memory Settings to try indexing again. **Buttons:** Open Advanced Memory Settings, Close |
| Recall reaches its sending-time allowance | **What was sent:** Conversation Recall did not finish in time, so this message was sent normally without recalled conversation. |
| Android pauses background indexing until the app is reopened | **Notification/status:** Background indexing is paused. Open the app to continue. Finished work was saved. |
| Long-message piece search reaches its allowance | **Memory Search Test:** The additional long-message search reached its time limit. The whole message was still searched. Checked (number) of (number) additional parts. |
| The on-device model cannot read one additional part of a long message | **Memory Debug Log:** Memory search could not read one additional part of this message with the on-device model, so that part was skipped. The whole message was still searched. Cause: (technical cause). |

**Existing saved-memory diagnostic wording reviewed for Step 2:**

| Current wording | Proposed replacement |
|---|---|
| Query embed failed, using lexical fallback: (cause) | **Owner-approved 2026-11-12:** Memory search could not read this message with the on-device model, so it searched by keywords only. Cause: (cause). |
| Vector load failed, using lexical fallback: (cause) | Memory search could not use its saved meaning index, so it searched by keywords only. Cause: (cause). |
| keyword only — no model | keywords only — on-device meaning search unavailable |
| Vector index incomplete — (missing) of (total) eligible memories lack a current vector; those memories use keyword search only this turn | Some memories were not ready for meaning search, so (missing) of (total) used keyword search for this message. The app is preparing them in the background. |
| Embedding self-check FAILED; semantic retrieval disabled, using keyword fallback: (cause) | The on-device memory-search model did not pass its check, so saved-memory search is using keywords only. Cause: (cause). |
| Embedding model failed to load: (cause) | The on-device memory-search model could not be loaded. Saved-memory search is using keywords only, and Conversation Recall is unavailable. Cause: (cause). |

Step 2 must replace the first three lines together so the summary never
claims that no model is installed when the actual cause was a failed read.
The incomplete-index line is log-only because it starts automatic repair.
Step 11 applies the same plain-cause rule to model-load and model-check
failures before it creates any notice.

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

**The situations the sample conversations must cover:**

| Situation | What must happen |
|---|---|
| An exact detail ("Which model did we choose?") | The original decision is recalled |
| A follow-up ("Why did we choose that?") | The same discussion is recalled, by also searching with the previous message |
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

The design covers everything. Building is split into numbered Steps small enough to
hand to an agent one at a time: "build Step 7". Each Step is one pull
request with its own tests, and each leaves the app working.

**How to use this section.** Give the agent this plan and the Step number. The agent reads G.19 first, then the sections the Step names, then
the code files the Step names. It builds only that Step.

**Difficulty** is marked so the owner can choose which model does a Step:
- **Routine:** self-contained, little risk. A lighter model can do it.
- **Careful:** touches shared code or has several cases to get right.
- **Hard:** many moving parts, or touches the chat screen. Use the
  strongest model available, and expect more than one review round.

### Strict execution order

The numbered Steps below are the execution order. A builder completes one
Step and stops. A later Step does not begin until every lower-numbered
unfinished Step is merged and every item in its **Approve first** column is
resolved. A builder never skips ahead because a later Step looks easier.
Only a new owner decision may change this order.

Step 0 is already complete. Do not rebuild it, regenerate its vectors, or
rewrite its expected answers. Its authoritative results are in
[memory baseline](<memory baseline.md>). The next work order is **Step 1**.

| Step | Status | What is built | Difficulty | Model | The user sees | Needs | Approve first |
|---:|---|---|---|---|---|---|---|
| 0 | **Complete** | Saved-memory test set with the real model's vectors | Routine with written instructions | Lighter | Nothing | Nothing | Nothing |
| 1 | **Next** | Token counting, one embedding queue, and the 2,048-token stored-text limit | Careful | Middle | Nothing | Step 0 complete | Nothing (G33) |
| 2 | Waiting | Long and dictated messages find their saved memories | Hard | Strongest | Better memory matches in long messages | Steps 0–1 | Memory Search Test wording; two remaining log lines (G26) |
| 3 | Waiting | Search primitive for previous user messages | Careful | Middle | Nothing | Step 0 | Nothing |
| 4 | Waiting | Saved memories stay through follow-up messages | Careful | Middle | Follow-up continuity | Steps 0 and 3 | Label and placement of its setting (G32) |
| 5 | Waiting | Memory word count and length limit | Careful | Middle | Word count under memory fields | Step 1 | Open parts of G34 |
| 6 | Waiting | Conversation-recall test chats with known answers | Routine with written instructions | Lighter | Nothing | Steps 0–5 complete | Nothing |
| 7 | Waiting | Passage cutter | Routine | Lighter | Nothing | Step 6 complete | Conversation-index design as a whole (G35) |
| 8 | Waiting | Passage stores and per-message updating | Hard | Strongest | Nothing | Steps 1 and 7 | Nothing after G35 |
| 9 | Waiting | Background embedding of passages | Hard | Strongest | Nothing | Steps 1 and 8 | Nothing |
| 10 | Waiting | Keep the index synchronized with chats | Hard | Strongest | Nothing | Steps 8–9 | Nothing |
| 11 | Waiting | Settings, status, deletion, database check, and errors | Careful | Middle | Approved settings section (G.16a) | Steps 9–10 | G20, G28, G31 |
| 12 | Waiting | Progress icon and pop-up with pause/resume | Careful | Middle | Approved icon and pop-up (G.16a) | Step 11 | Nothing |
| 13 | Waiting | Indexing after the app closes | Hard | Strongest | Approved notification (G.16a) | Step 12 | Background mechanism and stopped-for-the-day wording |
| 14 | Waiting | Retrieval | Hard | Strongest | Nothing | Steps 3 and 6–10 | Retrieval and assembly design as a whole (G36) |
| 15 | Waiting | Inspector and measured starting values | Careful | Middle | Conversation Recall Test screen | Step 14 | Screen labels |
| 16 | Waiting | Context assembly in real requests | Hard | Strongest | Conversation Recall works | Steps 14–15 | AI-facing text; "what was sent" wording; recall-room setting; G24 |
| Later | Not numbered yet | Optional re-ranker, live AI worker, and recall across chats | Design later | — | — | Step 16 | Separate owner decisions |

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

**Builder reading rule.** Follow the numbered execution order above. Each checklist highlights the assigned Step; every instruction and test in the rest of that Step and its referenced design sections still applies. A checklist never permits skipping details or prerequisites. Stop before dependent work if an approval is missing. Do not change another Step, the design, or existing decisions to make the assigned task easier.

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

**Agent checklist for this step**
- **Prerequisites:** Steps 0 and 1. **Approval gate:** Obtain the Memory Search Test piece/timeout wording and the remaining G26 diagnostic wordings proposed in G.13b before changes.
- **Change:** G.10's bounded overlapping search pieces for typed and dictated messages, using the shared embedding queue and a minimal hookup. Whole-message search still runs first; a piece's meaning match uses the normal relevance gate, while a piece's keyword match also needs meaning-search agreement.
- **Preserve:** Existing whole-message matches **and their order**, Memory Assistant search, lorebooks, and current sending when no piece can qualify. Do not add a paid rewrite or trust periods as sentence boundaries.
- **Verify and report:** Step 0's per-sample quality improves for long messages **without increased wrong memories**; existing tests pass; report time allowance and any missing on-phone performance proof.

- **Goal:** a small detail in a long message finds its saved memory,
  whether the message was typed or dictated, without more wrong memories
  being sent (G.10).
- **Needs first:** Step 0 and Step 1.
- **Before building, the owner approves:** the Memory Search Test wording
  and the remaining saved-memory diagnostic wording proposed in G.13b
  (G26).
- **Read:** G.2, G.10; `LiveRetrieval.kt`, `Librarian.kt`,
  `SearchTestReport.kt`.
- **Build:** G.10, in its own file, with a small hookup. Every use of the
  embedding model goes through Step 1's queue.
- **Must not change:** the result of the whole-message search; the
  presence and order of anything that is sent today; the Memory
  Assistant's own search; the lorebook.
- **Results:** record the before/after totals and every changed sample in
  `memory baseline.md`. Update `baseline.json` only for verified
  improvements, never to permit a regression.
- **Done when:** on Step 0's set, more right memories are found in long
  messages, dictated ones included, and the number of wrong memories sent
  has not risen; existing saved-memory tests pass unchanged; and the added
  sending time is inside the allowance. Time on a real phone cannot be
  measured in the build environment. It is read once from the Memory
  Search Test screen on the owner's phone, and reported.

### Step 3: searching with the previous messages (Careful)

**Agent checklist for this step**
- **Prerequisite:** Step 0 test cases and baseline. No new UI or owner wording is required here.
- **Change only:** A standalone Kotlin search helper following G.9a, taking 0–3 previous **user** messages as an input. Search each independently and give newer matches priority; do not join messages.
- **Preserve:** Existing live search: this helper is **not connected to sending** in Step 3. Retain earlier-message vectors where applicable and respect changing memory/scope eligibility when the helper is later used.
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

### Step 4: carry-over for saved memories (Careful)

**Agent checklist for this step**
- **Prerequisites:** Step 0 and Step 3. **Approval gate:** After examining Step 3's 0/1/2/3 results, get owner approval for the G32 setting's placement, label, and explanation.
- **Change:** Use Step 3 at send time with **0–3 previous user messages**, default **1**; retain reusable earlier-message vectors while respecting edits, deletes, scopes, and chat changes.
- **Preserve:** Setting **0** must match the pre-step baseline exactly. Do not independently change the default from 1 or invent a separate subject-detection algorithm.
- **Verify and report:** First follow-up retention, old-subject spillover when topics change, no carry-over beyond the configured count, and no carry-over of edited/deleted memories. Report the known second-follow-up limitations rather than hiding them.

- **Goal:** a saved memory stays through follow-up messages and falls away
  after the subject changes (G.9).
- **Needs first:** Step 0 and Step 3.
- **Before building, the owner approves:** the label, explanation, and
  place of the setting for how many previous messages are searched (G32),
  after seeing Step 3's recorded results for 0, 1, 2, and 3.
- **Read:** G.9, G.9a; `enforcer/Enforcer.kt`; the injection record in
  `MemoryStore.kt`.
- **Build:** the hookup that uses Step 3 when a message is sent, in its
  own file with a small connection; the setting (0 to 3, starting at 1);
  and keeping the embedding model's numbers for earlier messages as G.9a
  describes.
- **Must not change:** anything when the setting is 0. With the setting
  at 0 every result is the same as before Step 4.
- **The starting value stays 1 (G32).** The builder does not change it. If
  Step 3's results suggest another value, the builder explains them and
  asks the owner. No other way of telling that the subject has changed,
  and no other search method, is added without the owner's approval.
- **Results:** record the setting comparison and every changed sample in
  `memory baseline.md`. Update `baseline.json` only for verified
  improvements, never to permit a regression.
- **Done when:** on Step 0's conversations, with the setting at 1, a
  memory stays through the first follow-up; the memories carried into the
  first message of a new subject are counted and reported; no memory is
  carried further than the setting allows; an edited or deleted memory is
  never carried; and with the setting at 0 the results match the
  baseline.

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
- **Prerequisites and approval:** Steps 0–5 complete under the strict execution order; no additional approval. Build invented test samples and validation, not the recall engine.
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
- **Preserve:** All other Advanced Memory Settings behavior. Index Conversations starts **off** and cannot turn on without an installed embedding model.
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
- **Build:** exactly what G.16a lists for Advanced Memory Settings, with
  the approved wording; the notification classification in G.13a; and
  one-notice-per-ongoing-condition deduplication. Every visible value comes from a shared style; a
  missing style is created and added to the guide. `ui-style-adoption.md`
  is updated.
- **Must not change:** any other part of Advanced Memory Settings.
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
- **Change:** The current-chat-only retrieval engine behind G.8a interfaces: implement G.6 retrieval stages **1–8** with G.9a/G.10 query handling, scoring, gates, eligibility, and measured time allowance.
- **Preserve:** No cross-chat recall, no request assembly, no paid calls, no forced match when nothing qualifies. **G.6 retrieval stage 9 and the optional re-ranker are not in this build.**
- **Verify and report:** Use Step 6's prewritten questions and the real embedding model. Score each question individually for relevant exchanges found and unrelated exchanges left out; record results as the work order requires.

- **Goal:** given a chat and a message, return the exchanges to offer,
  with every score and reason (G.6, G.9a, G.8a points 1 and 2).
- **Read:** G.6, G.8a, G.9a, G.10; `LiveRetrieval.kt` and
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
- **Change:** The **Conversation Recall Test** inspector in Advanced Memory Settings > Debug (G.8b), showing which exchanges a typed question would find and why; measure the G.15 starting values.
- **Preserve:** This is a debugging and measurement step, **not** live request injection. Do not add recall to outgoing requests until Step 16.
- **Verify and report:** Record the measured starting values in `conversation_recall_baseline.md`, and distinguish build-environment measurements from actual search time on the owner's phone.

- **Goal:** a way to type a message for a chat and see what would be
  recalled and why, and measured starting values for the numbers.
- **Placement and name are decided (G.8b).** Before building, the screen's
  labels are shown to the owner as one list.
- **Done when:** the numbers in G.15 have measured starting values recorded
  in `conversation_recall_baseline.md`, with search time on the owner's
  phone.

### Step 16: context assembly (Hard)

**Agent checklist for this step**
- **Prerequisites:** Steps 14 and 15. **Approval gate:** Obtain the AI-facing text, “what was sent” wording, recall-room control wording, debug lines, and the G24 decision **before** coding any undecided behavior.
- **Change:** One shared assembly implementation connected minimally to **both** request-building paths. Add only eligible original exchanges, within recall's independent limits; label speaker/date/excerpts, show what was sent, and perform the final whole-request size check.
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

**In Advanced Memory Settings,** a new section after Librarian /
Embeddings:

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
| G2 | How large is recall's own size limit, and can the user set it? | **Decided (owner, 2026-11-11):** a starting amount from testing, adjustable in Advanced Memory Settings (G.8b). Its label is shown to the owner before Step 16 | Decided |
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
| G21 | Where the recall test screen goes, and its name | **Decided (owner, 2026-11-11):** Advanced Memory Settings, Debug section; Conversation Recall Test | Decided |
| G22 | May recall and carry-over add to the existing memory debugging view? | **Decided (owner, 2026-11-11):** yes. No new general application log entries | Decided |
| G23 | Which Step is built first? | **Decided (owner, 2026-11-11):** the long-message fix is the first user-visible fix. In the corrected numbering it is Step 2, after completed Step 0 and the Step 1 queue-safety prerequisite (G.16) | Decided |
| G24 | If the user turns Index Conversations off while Conversation Recall is on, what happens to recall? Also undecided: whether turning Conversation Recall on turns Index Conversations on (G.8b says only that it "may"), and what happens to recall when the index is deleted | Open. Indexing is never silently restarted (G.8b) | Step 16 |
| G25 | Withdrawn on 2026-11-12. With no embedding model nothing can confirm a match, so messages are searched as today (G.10). Nothing changes for those users, so there is nothing to decide | Withdrawn | |
| G26 | What replaces the inaccurate or technical saved-memory diagnostic lines? | **Partly decided (owner, 2026-11-12):** "Memory search could not read this message with the on-device model, so it searched by keywords only. Cause: (cause)." is approved. G.13b now names the other affected lines and proposes exact replacements; those remaining replacements still need approval before Step 2 | Step 2 |
| G27 | Which failures interrupt the user? | **Decided (owner, 2026-11-12):** automatic repairs do not raise a notice. Notify the user when the failure affected a turn, stopped a feature, prevented a setting, or has a real action the user can take. Say what happened, what is safe, and the action when one exists. Do not repeat the same ongoing failure every turn | Decided |
| G28 | Chats that have never been reopened cannot be indexed until they are opened (G.5a rule 7). What does the progress count show for them? | Open. Recommended: count only what can be indexed now, and say plainly how many conversations will be indexed when they are next opened | Step 11 |
| G29 | To find a small detail in a long or dictated message, should the app also search with overlapping pieces of the message, cut between words and never at periods (G.10)? | **Decided (owner, 2026-11-12):** yes, on the agent's recommendation | Decided |
| G30 | Is recall offered without an embedding model? | **Decided (owner, 2026-11-12):** no. Conversation recall requires the embedding model. Lorebook keyword matching is separate and keeps working whenever the lorebook is on | Decided |
| G31 | What the user sees when the required on-device model is missing, removed, or unusable | Open for exact approval. G.13b proposes separate wording for trying to enable the feature with no model and for an installed feature becoming unavailable, each with an Open Model Settings action | Step 11 |
| G32 | Can the user choose how many of their previous messages are searched for follow-ups (G.9a)? | **Decided (owner, 2026-11-12):** yes. 0 to 3, and it starts at 1. Still open: where the control goes, and its label and explanation. Recommended place: with the other search numbers on the Memory Search Test screen | Step 4 |
| G33 | Is the embedding model's reading limit for saved memories raised from 512 tokens to the model's real 2,048 (G.2)? | Follows from the owner's decision of 2026-11-12 that a memory may hold 1,500 words (G34): that limit is not true unless the model reads that much. Written into Step 1. Speed and memory use on the phone are checked before it ships | Step 1 |
| G34 | How is a saved memory limited to what the model can read, and how is its length shown? | **Decided (owner, 2026-11-12):** a word count under the memory box, aligned right, for each memory; at 1,500 it turns red and reads "Word Limit Reached: 1,500 Words Maximum"; the Memory Assistant splits a large subject into separate proposed memories and never fails or discards one for length. Still open, asked before Step 5: the count's text before the limit; what happens on saving over the limit; the wording when the model's real limit is reached below 1,500 words; how existing over-length memories are marked; the words added to the Memory Assistant's instructions | Step 5 |
| G35 | The design of the conversation index as a whole (G.5, G.5a, G.5b, G.7): a new encrypted database on the phone holding where each piece of a conversation is, its searchable words, and the embedding model's numbers; what is and is not stored; and how it is kept correct after edits, deletions, and restores | Open. The agent explains it in plain terms and the owner approves it once, before Step 7. No builder treats it as approved before then | Step 7 |
| G36 | The design of retrieval and of what the AI is handed, as a whole (G.6, G.8) | Open. The agent explains it in plain terms and the owner approves it once, before Step 14 | Step 14 |

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
| What to search with | SillyTavern: the last 2 messages joined. RisuAI: the last 3 messages, each searched on its own, later ones counting more. AnythingLLM: the latest message only | The newest message and the previous one or two, each on its own (G.9a) |
| How many are sent | SillyTavern 3, GPT4All 3, AnythingLLM 4 | 3 (G.8) |
| The cut-off | SillyTavern: a fixed 25% score, then the best 3 | A stricter gate, this app's own, because a wrong result is worse than a missed one (G.6) |
| Keywords as well as meaning | SillyTavern, GPT4All, AnythingLLM, RisuAI: meaning only. Open WebUI and Mem0: both, with an optional re-ranker | Both (G.6) |
| No embedding model | None of the chat apps offers recall without one | The embedding model is required (G30) |
| Follow-up messages | AnythingLLM fills empty room with what was used in recent replies, newest first, with no relevance check. Open WebUI and LlamaIndex have an AI model rewrite the follow-up, a paid call | G.9a |
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

- **Read this plan and the files it names for your Step.** The memory
  plan (`Memory System/dynamic_memory_plan.md`) is background for
  saved-memory work and is not needed for the conversation index.
- **Build exactly one numbered Step from G.16, as one pull request.** Do
  not start the next Step. Step 0 is complete; the next Step is Step 1. Every Step follows the code
  workflow in `CLAUDE.md`.
- **Do not start a Step out of order or while its "Approve first" entry in
  G.16 is not satisfied.** Say what is waiting and stop.
- **Write results only to the destination table in G.16.** This plan may
  otherwise be edited only where the Step's work order explicitly says to
  record a confirmed code fact, and in the Build record.
- **Only "Owner" items are decisions.** Anything marked "Proposed" that
  the user would see, read, or pay for is shown to the owner before it is
  built: wording, screens, settings, defaults, and text sent to the AI.
- **Never change:** the stored chats; the Summarizer and its summaries; the
  existing chat search feature; saved-memory search results (except in
  Steps 2 and 4, which exist to change them); the lorebook; what a
  request contains when recall is off.
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
