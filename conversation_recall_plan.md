# Conversation index, recall, and carry-over plan

Written 2026-11-10. This is the only document a builder needs for this
work. Section numbers keep a "G." prefix because this began as Part G of
the memory plan, `Memory System/dynamic_memory_plan.md`, which remains the record for
saved-memory search (its Parts A to F). References below to "Part A",
"Part E", or a step number mean that document.

## G.0 Status and how to read this plan

- **This plan is a proposal.** It was written on 2026-11-10 from the current
  code, the review of #180, and published research. Nothing in it is built.
- **Two labels are used throughout:**
  - **Owner** means the owner said it on 2026-11-10. These are decisions.
  - **Proposed** means the agent recommends it. The owner has not approved
    it.
- **Names here are planning names,** not on-screen wording. Every label,
  explanation, and AI-facing text is shown to the owner before it is built.
- **The design covers the whole system,** including parts built later.
  Building in stages (G.16) decides the order. It does not leave anything
  out of the design.

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
- **The numbers are guesses.** They come from 20 invented memories. The
  test conversations that were meant to check them are not built.

Smaller issues:

- "move" does not match "moving", and "run" does not match "running";
- when more than half of a library relates to the message, meaning search
  can reject all of it, because none stands out;
- every message re-reads the words of every memory. Nobody has measured
  this on a phone with thousands of memories;
- one log label says "no model" when the real cause was that the message
  failed to embed;
- meaning search reads only the first 512 tokens of a memory
  (`EmbeddingModels.kt`), so a very long memory is partly invisible to it.
  The model itself can read 2,048;
- the embedding model must run one request at a time, and `Librarian.kt`
  has a lock for that (`embedLocked`). The live chat search embeds the
  user's message without the lock (`searchInternal`), so it can run at the
  same moment as a background repair. Found in review on 2026-11-10; fixed
  as part of G.5a rule 6.

## G.3 The whole system at a glance

| Part | What it does | Exists today? | Cost to the user |
|---|---|---|---|
| Conversation store | Keeps every chat whole and in order | Yes. Untouched | None |
| Chunking | Cuts each message into passages the embedding model can read in full (G.5b) | No | None |
| Index entries | One entry per passage, pointing to the whole exchange, with labels (G.5) | No. Useful parts exist (G.4) | None |
| Retrieval | Finds the right passages by meaning and by keywords, and rejects what is not relevant (G.6) | Parts exist for saved memories and for chat search | None |
| Scope walls | Decide which entries a chat may see | Yes, for saved memories | None |
| Context assembly | Chooses which exchanges are sent and writes them, labelled, beside the newest message (G.8) | Partly. Saved memories are placed this way | A little more text per message |
| Carry-over | Keeps what was relevant while the subject continues | No | None |
| Live AI worker | Optional. The Summarizer's model does message-level work during a chat | No. The model connection exists | Paid, only when turned on |
| Upkeep | Keeps the index correct after edits, deletions, restores | Yes, for chat search | None |
| Testing | Test conversations and the Memory Search Test screen | Screen yes. Conversations no | None |

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
- **Consequences:** a token-counting function must be added. Cutting
  cannot depend on a model being present (G.5b). Vectors must record which
  model made them.

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
| Messages the search text borrowed from | So the search text is rebuilt when one of them is hidden, edited, or deleted (G.5b) |
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
  confirmed in the code when stage 1 is built.
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

## G.5b Chunking: how a message is cut into passages (Proposed)

Chunking decides what each index entry means, so it is settled before the
index is built. It runs on the phone, costs the user nothing, and uses no
AI model beyond the embedding model.

**What the research supports.** A simple rule based on paragraphs and
sentences. A study of automatic "semantic chunking" found it did no better
than plain sizes on realistic text, and splitting by topic only helped when
a capable paid model did it (G.18). So the cutter is a plain rule, made
careful, not a clever one.

**The goals, in order.**
1. Every character of the message is in exactly one passage.
2. Every passage can be read in full by the embedding model.
3. A passage is large enough to mean something on its own.
4. A small detail is not buried in a passage about something else.
5. Nothing that belongs together is lost at a boundary.

**Step 1: split the message into natural pieces.** In this order:
paragraphs (a blank line); then lines, which keeps code, lists, and tables
intact; then sentences; then clauses; then words; and last, between two
characters, never through the middle of one. A piece is only split further
when it is too large for a passage.

**Step 2: join short neighbouring pieces.** Short pieces next to each other
in the same message are joined, in order, until the passage reaches the
target size. Four one-line paragraphs become one passage, not four vague
ones. Pieces are never joined across two messages.

**Step 3: respect the two sizes.**
- **The target size** is what the cutter aims for. Starting value: about
  256 tokens, the figure most often used in published work. A smaller
  target finds small details better and a larger one carries more meaning;
  the right value for this app is measured in stage 2 and is adjustable.
- **The hard limit** is the embedding model's limit (512 tokens today),
  counting the instruction prefix and special tokens. No passage's search
  text may exceed it.

**Step 4: cutting does not depend on a model being present.**
- The model can be absent or changed, and keyword recall must still work
  without it. So the cutter measures size with a model-independent
  estimate and aims well under the hard limit.
- When a vector is about to be made, the search text is measured with the
  real tokenizer. If it does not fit, that message is cut again with a
  smaller target and its passages are replaced. A passage is marked as
  embedded only when the model read all of its search text.
- A model with a different limit therefore needs no change to the rules.

**Step 5: carry meaning across boundaries.** Two protections, both wanted:
- **Overlap in the search text only.** The search text of a passage begins
  with the last sentence of the passage before it, when both are in the
  same message and it fits. The original text is not overlapped, so no
  character is delivered twice.
- **Neighbour links.** Each passage records the one before and after it, so
  recall can add the neighbour when a match cannot be understood alone.

**Step 6: give short replies their question.** "It needs to survive. Don't
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
covers the overlap in step 5 and the context in step 6.
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

**Step 7: record who is speaking.** The search text states whether the
user or the AI said it, using the embedding model's own title slot, so
"I moved to Leeds" from the user is not confused with the AI saying it.
Whether this improves results is measured in stage 2; it is dropped if it
does not.

**Very short messages.** "ok", "thanks", and "exactly" have no meaning to
search for. Each is still a passage, so every character is covered, and it
is found through its exchange. On its own it has no informative words and
cannot pass the relevance gate.

**Versions.** The cutting rules have a version number, stored on every
passage. When the rules or the target size change, chats are re-cut in the
background and the old passages are replaced. Unchanged search text keeps
its vector.

**Languages.** Sentence and word boundaries are found with the platform's
own language-aware tools, so cutting works in any language. The keyword
word rules are written for English; other languages rely more on meaning
search. That limit is stated, not hidden.

**What the cutter never does:** change a message; drop a character; join
two messages; call a paid model; or leave a passage the embedding model
cannot read in full.

**Tests the cutter must pass, written with it:**
- joining every passage of a message, in order, gives back the message
  exactly, for ordinary text, very long text, text with no spaces, text
  with no punctuation, code, tables, emoji, and non-English text;
- no search text exceeds the model's limit, measured with the real
  tokenizer;
- short neighbouring paragraphs share a passage;
- a detail in the middle of a long message has a passage that contains it;
- the same message always cuts the same way;
- no search text borrows from a hidden, unfinished, deleted, or
  differently scoped message, and hiding a message rebuilds the search
  text of the passages that borrowed from it.

## G.5a Rules the index must guarantee (Proposed)

Added after outside reviews and an audit of the code on 2026-11-10 and
2026-11-11. Each point was checked in the code. These are requirements for
stage 1, not options.

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
- The rule covers borrowed context as well as the passage's own text: no
  search text and no vector may contain words from a message the AI may
  not be sent (G.5b).
- A test proves that the set of messages the index may recall is the same
  as the set the chat request may send, and another proves that hiding a
  message removes its words from every neighbour's search text.

**2. No text is ever cut off silently.**
- The tokenizer drops everything past the model's limit without saying so
  (`HfTokenizer.encode`).
- Rule: before a vector is made, the search text is measured with the real
  tokenizer, counting the instruction prefix and special tokens. If it does
  not fit, the message is cut again (G.5b step 4). A passage is recorded
  as embedded only when all of its search text was read.

**3. Vectors have an owner and a lifetime.**
- A vector is reused only when the search text, the embedding model, the
  vector size, and the cutter version that prepared the text all match.
- When the last passage that uses a vector is deleted or changed, the
  vector is removed.
- Vectors made by a model that is no longer selected are removed once the
  new model's vectors are complete.
- Deleting a chat removes everything derived from it, including vectors no
  other chat uses.
- The user must be able to delete the whole index. Its wording and place
  are shown to the owner before stage 1 is built.

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
- Rule for stages 1 to 3: meaning search looks only at the current chat's
  passages. Keyword search uses the database's own keyword index. Vectors
  are read in bounded batches, never all chats at once.
- Memory use is bounded too: as each batch is scored, only the best
  candidates so far are kept and the batch is released.
- Passage counts, indexing progress, and search time are recorded so they
  can be shown and measured.
- Recall from other chats needs a different search method for large
  collections. That choice is made when that stage is designed, and the
  storage is shaped so it can be added without rebuilding.
- Size, for reference: one vector is about 1 KB, so 100,000 passages are
  about 100 MB before overhead. Smaller vector storage is measured in
  stage 1.

**6. One path runs the embedding model.**
- Rule: every use of the model goes through one queue: the live message,
  saved memories, and conversation indexing. A live chat request always
  goes ahead of background work, and background indexing pauses for it.
- Background indexing works in small steps, one passage at a time, so a
  live message never waits behind a large batch.
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

## G.6 Retrieval: how the right conversation is found (Proposed)

A good index can still give bad recall if this part is loose. It is
specified here step by step. The numbers named are starting values,
measured and adjusted in stage 2. They are engineering numbers; the owner
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

**Step 1: decide what to search with.**
- The user's latest message, on its own.
- A long message is also searched one sentence at a time (G.10), up to a
  limit.
- When the subject check (G.9a) says the message is a short continuation
  with no subject of its own, the previous exchange's user message is
  searched as well, as a separate search. Earlier messages are never glued
  onto the search text.
- Each of these is a separate search. A passage qualifies when it passes
  for any one of them.

**Step 2: read a time reference, when there is one.**
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

**Step 3: gather candidates two ways, inside the current chat.**
- **Keywords:** the database's keyword index returns the best-matching
  passages for the message's informative words. Common words are dropped
  first, by the same rule saved-memory search uses.
- **Meaning:** the message's vector is compared with the chat's passage
  vectors, in bounded batches, keeping only the best so far (G.5a rule 5).
- A passage with no vector yet can still be found by keywords.
- Each way returns a bounded list. Starting value: the best 50 of each.

**Step 4: the relevance gate. Being the best match is not the same as being
relevant.** A candidate passes only when one of these is true:
- **Meaning:** its similarity clears a fixed minimum **and** stands clearly
  above this chat's typical similarity for this message. A chat about one
  project has a high typical similarity, so only a passage that is closer
  than the general run of the chat passes.
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
look again at a near miss (step 5a).

The gate uses absolute measures on purpose. A method that only compares
ranks cannot say "nothing here is relevant", because something is always
ranked first.

**Step 5: combine the two ways into one order.**
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
- Rank fusion is run alongside in the stage 2 tests as a comparison. If it
  does better on this app's conversations, it replaces the blend for
  ordering. The gate stays either way.
- A passage found both ways scores higher than one found one way.

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

**When a passage has no vector yet.** Its meaning score is unknown, not
zero. It can pass the gate on keywords alone. For ordering, its meaning
score is counted as 0, so it ranks below a passage with the same keyword
score that also matched by meaning. This is deliberate: a wrong recall is
worse than a missed one, and the gap closes as soon as the vector is made.
The inspector shows such a passage as "no vector yet", so a low rank is
never mistaken for a poor match.

**Step 5a: an optional re-ranker (a place is kept for it; not built
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
  borderline** (step 4). It reads each with the message and makes the
  final relevance decision. It can reorder, it can reject a candidate the
  first search liked, and it can accept a borderline candidate the first
  search narrowly missed.
- It never sees the "not relevant" group, so it cannot bring back
  something far off, and its work stays bounded.
- "Nothing is relevant" remains a possible answer after re-ranking.
- With no re-ranker, this step is skipped: the clearly relevant go
  forward and the borderline are dropped. Nothing else changes.
- **When it is decided.** After stage 2 shows what retrieval gets wrong on
  this app's conversations. If the mistakes are ones a re-ranker fixes,
  candidate models are tested on those same conversations, on the phone,
  and the owner decides whether to offer it as an optional download.

**Step 6: group passages into exchanges.**
- Passages are grouped by the exchange they belong to. An exchange's score
  is its best passage's score, with a small addition when several of its
  passages matched.
- Several matching paragraphs of one exchange therefore count once and
  cannot crowd out other exchanges.

**Step 7: remove what should not be recalled.**
- An exchange the AI is already reading close to the newest message: the
  messages the Summarizer keeps in full, or the last few exchanges when
  the whole chat is sent.
- An exchange containing a message that is no longer eligible, checked
  against the stored message at this moment (G.5a rule 1).
- The current message itself.

**Step 8: avoid near-repeats.**
- When two exchanges say nearly the same thing, the higher-scoring one is
  kept and the other dropped, so the limit is not spent on repeats.
- Two exchanges that **differ** on the same subject are both kept.

**Step 9: follow a subject to its later turns.** A decision is often made
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
  stage 2 on the changed-decision test conversations, and reported.
- It uses vectors already stored, so it adds a comparison pass and no new
  embedding work.

**What retrieval hands on.** A short list of exchanges, best first, each
with its message IDs, which passages matched and where, its scores, the
matched words, and its date. Nothing is written into the request at this
point. G.8 decides what is sent.

**What retrieval must never do:** return something because the list was
empty; search another chat (until that stage is built); read a hidden,
deleted, or unfinished message; make a paid call; or delay sending beyond
the time measured and accepted in stage 2.

**A time limit.** Retrieval has a fixed time allowance, set from the
stage 2 measurements. If it has not finished in that time, the message is
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
- **To verify when stage 1 is built:** how much existing conversation has a
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

**Why this helps even when the whole chat is already sent.** Models attend
most to the start and end of what they are given and least to the middle
(G.18). An exchange from far back sits in the middle. A copy beside the
newest message is where the model reads most carefully.

**Choosing which exchanges are sent.** When more exchanges qualify than
fit:
- Exchanges are taken in order of relevance score, best first.
- An exchange is taken only if it fits what is left of recall's size limit
  and count limit.
- An exchange that does not fit whole is added as an excerpt (below) when
  an excerpt fits. Otherwise it is skipped and the next one is tried, so
  one very large exchange cannot block smaller ones.
- A neighbouring exchange is added only when the chosen exchange cannot be
  understood alone: its user message is a short continuation with no
  subject of its own (G.9a). A neighbour counts against the size limit but
  not the count limit.
- An exchange carried over from the previous message (G10) is considered
  after new matches, so new matches are never displaced by old ones.
- Starting values: up to 3 exchanges, as SillyTavern starts. Redis's
  tested setup is reported as 5 with one neighbour each side; that figure
  could not be confirmed from its public page. Both are measured in
  stage 3.

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
  request is handled exactly as it is today. Nothing else gives way to
  recall unless the user has chosen that (G3).

**When an exchange is too large to add whole.** Indexing and delivery are
separate. Every part of it stays searchable. For delivery, the whole
exchange is added when it fits. When it does not, the matching passage and
the paragraphs around it are added in their original words, marked as an
excerpt, with a note that the rest of that exchange was left out. It is
never cut silently, and never cut in the middle of a sentence.

**Only true duplicates are skipped.** An exchange is skipped when its own
text is already in the request close to the newest message. An exchange is
**not** skipped because a saved memory covers the same fact. A short saved
fact does not carry what the conversation meant.

**How recalled conversation is written for the AI.** The exact words are
shown to the owner before stage 3 is built. What the text must do:
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
same place, so the user can always tell what the AI was given (G11). The
wording and layout are shown to the owner before stage 3.

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
- **When the chat no longer fits the model:** today the app warns or
  blocks. That stays the default. Any other behaviour is the user's
  explicit choice (G3).
- **Experimental Mode** sends only the current message by design. Whether
  recall applies there is open (G4).

## G.8a How the parts connect

Three hand-over points. Each stage builds to these, so a later stage does
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

The scores and reasons at points 2 and 3 are what the stage 2 inspector
shows, so a wrong or missing recall can be explained, not guessed at.

## G.9 Carry-over for saved memories (Proposed; on hold)

**The problem.** The saved-memory text is rebuilt for every message and is
not kept in the chat history. A memory found for one message is gone for
the next unless that message finds it again.

**The rule.**

- Only the memories sent with the previous message are candidates.
- A candidate stays when the user's new message still relates to it, at an
  easier bar than a new memory must clear.
- A candidate also stays when the new message is a short continuation with
  no subject of its own ("exactly", "and then?") and the AI's previous
  reply still relates to it.
- Otherwise it is dropped. This covers a change of subject and a memory
  that was sent by mistake.
- The AI's reply alone can never keep a memory alive. The user has to still
  be on the subject.
- There is no fixed number of messages after which a memory is dropped.
- New matches rank ahead of carried ones when the memory limit is full.
- Carried memories obey scope like any other.

**What is not known.** The "easier bar" and the test for "a short
continuation" have no published answer. They are measured on the test
conversations before anything is built (G.15).

**Relation to recall.** Carry-over and conversation recall are separate.
They share the subject check (G.9a) and the final size check (G.8), and
nothing else. Each has its own size limit.

**Open:** whether a recalled exchange is also carried over (G10).

## G.9a The subject check (Proposed)

One small component, built once and used by both recall and carry-over. It
does not depend on either of them.

- **What it answers:** is the user's new message still about the subject of
  the previous exchange?
- **What it reads:** the new message, the previous exchange, and nothing
  older.
- **How it decides:**
  - the new message relates to the previous exchange by meaning or
    keywords; or
  - the new message is a short continuation with no subject of its own, and
    so cannot be judged alone.
- **What uses the answer:**
  - recall searches with the previous exchange's subject as well when the
    answer is yes and the new message has no subject of its own;
  - carry-over keeps the previous message's memories when the answer is
    yes.
- **What it never does:** glue earlier messages onto every search, or let
  the AI's reply alone keep a subject alive.
- Its two numbers (how closely related, and how short is "short") are
  measured when it is built.

## G.10 Long messages (Proposed)

- Each sentence of a long message is searched on its own, as well as the
  whole message. A memory or an index entry is used when it matches the
  whole message or any one sentence.
- "I'm worried about Pepper" inside a long message is then judged as its
  own short message.
- An accidental word in a sentence about something else is still only a
  small part of that sentence, so the protection against wrong matches is
  kept.
- **Cost:** each sentence is one more pass through the on-phone model, so a
  long message takes longer to send. The owner said on 2026-11-10 that it
  should take longer. The number of sentences searched is limited, and the
  time is measured on the phone before it ships.
- No published study covers this. It is measured here.

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
- **What stage 1 must leave in place for it,** so adding the worker later
  changes nothing already built: every passage belongs to an exchange with
  a stable ID; every passage records its source kind; the stores can gain
  a notes table without altering the existing ones; and upkeep already
  knows when an exchange was edited or deleted.
- **What is designed only when the owner starts this stage:** which jobs
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
| Message edited | Passages for that message are replaced, and the search text of short neighbouring passages that borrowed from it (G.5b step 6). Unchanged search text keeps its vector |
| Reply regenerated or another version chosen | Entries follow the version that is shown |
| Message deleted | Its entries and any AI notes on them are removed, and any vector nothing else uses |
| Message hidden from the AI, or made visible again | Takes effect on recall at once (G.5a rule 1). Neighbouring passages that borrowed its words have their search text and vectors rebuilt |
| Chat deleted | All its entries are removed, and any vector nothing else uses |
| Chat renamed | For older chats a rename changes the chat's ID. Its entries move to the new ID (G.5a rule 9) |
| Reply finishes streaming | Indexed then, not while streaming. A failed reply is not indexed |
| Cutting rules or target size changed | Chats are re-cut in the background. Unchanged search text keeps its vector |
| The user deletes the index | Everything derived is removed. The chats are untouched |
| Backup restored, or a backup merged into the phone's chats | Each affected chat is checked message by message (G.5a rule 8). Nothing is indexed twice |
| Embedding model changed | Keyword search keeps working at once. Vectors are remade in the background |
| Chat settings changed (companion, project, roleplay) | Scope labels on later messages follow the new settings |
| Existing chats, first time | **Owner (2026-11-11):** indexed in the background, a little at a time, newest first. Opening a chat never waits for it |
| Already indexed text | Stays indexed. It is redone only when the message is edited, the embedding model is changed (the meaning half only; keywords keep working), or the index is rebuilt after a restore |

The index is derived: it can always be rebuilt from the stored chats, and
losing it loses nothing the user wrote.

## G.13 When a part is unavailable (Proposed)

| Missing | What still works |
|---|---|
| The embedding model was never installed | The index is built and kept up to date for keyword search, and recall works by keywords. Passages wait for their vectors. Installing a model later adds meaning search to the same passages, with no re-cutting and no rebuild |
| The embedding model is installed but fails | The same keyword path keeps working. This is reported as a failure, separately from "no model installed", because the user's next step is different |
| A vector for some entries | Those entries are found by keywords until the vector is made |
| The live AI worker, or its network | Everything except AI notes |
| The whole index | The chat is sent exactly as today. Sending is never blocked by recall |

Failures are shown in plain words where the user would otherwise be
misled, and never as a generic error.

## G.14 Cost, speed, storage, privacy (Proposed)

- **Tokens.** A recalled exchange is sent once more in that request. Its
  own size limit bounds the cost.
- **Speed.** Indexing runs in the background and must not slow typing,
  scrolling, or sending. Search time is added to sending, and is measured
  on the owner's Pixel 8. No public figure exists for this model on a
  phone's ordinary processor.
- **Battery.** Indexing existing chats is heavy work. **Owner
  (2026-11-11):** it runs in the background, the user never waits for it,
  and it may keep running after the app is closed. On Android that
  requires a visible notification while it runs. It is mostly a one-time
  job, repeated only after a large restore or a change of embedding model.
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

Tests are written with the component they test, in the same pull request.
There is no separate testing stage in front of the building.

**Three kinds of test.**

- **Correctness tests** come with every stage. They either pass or the
  stage is not finished. Examples: every character of a message lands in a
  passage; an edit replaces only what it touched; a deleted message leaves
  nothing behind; a hidden message is never recalled and its words are in
  no neighbour's search text.
- **Quality tests** use written sample conversations with known right
  answers. They are scored, and the scores are tracked from stage to stage
  so a change that makes recall worse is seen at once.
- **Measurements** set the numbers research does not give, with the real
  embedding model: the passage target size, the gate, the blend weights,
  how many exchanges to recall, the subject check's two numbers, the
  carry-over bar, and speed on the phone.

**What every quality test checks, at three points:**
1. **What was found:** the right exchange is in retrieval's list.
2. **What was left out:** nothing unrelated is in it.
3. **What was sent:** the text added to the request is the right exchange,
   in its original words, correctly labelled, within the limits.

**The situations the sample conversations must cover:**

| Situation | What must happen |
|---|---|
| An exact detail ("Which model did we choose?") | The original decision is recalled |
| A follow-up ("Why did we choose that?") | The same discussion is recalled, using the subject check |
| A time reference ("What did we change last week?") | Exchanges from that period are favoured |
| A summarized or compacted chat | The original exchange is recalled although the AI otherwise has only the summary |
| Nothing relevant | Nothing is added |
| A long message with one small important detail | The exchange about that detail is recalled |
| A hidden message | It is never recalled, and neither is anything that borrowed its words |
| An edited message | The new text is recalled, never the old |
| A deleted message | It is never recalled |
| A chat that is about one subject throughout | Only exchanges closer than the general run of the chat are recalled |
| A roleplay chat and a companion chat | Each recalls only its own conversation |
| A very long exchange | An excerpt is sent, marked as one, and nothing is cut silently |
| More matches than fit | The most relevant are sent, and the rest are reported as left out |
| A decision made early and changed later, asked about in words that match only one | Both are recalled, in date order. The hit rate is reported |
| The same message sent down both request paths | The same text is added by both |
| Retrieval runs out of time | The message is sent as today, and recall is shown as skipped |
| A passage with no vector yet | It can be found by keywords, and is shown as waiting for its vector |
| No embedding model installed | The index is built, and an exchange is recalled by its distinctive words |
| A model is installed after indexing | Meaning search starts working on the existing passages, and nothing is re-cut |

**How quality is judged.** No pass mark is invented in advance. The first
run on each stage sets the baseline. After that, a change must not lower
"found" or raise "wrongly added" without the owner being told and
agreeing. The owner ranks a wrong memory as worse than a missed one, so
"wrongly added" is watched first.

**These are engineering numbers.** The owner is not asked to choose them.
Each has a starting value from measurement and is adjustable afterwards.

## G.16 Build stages and work orders

The design covers everything. Building is split into steps small enough to
hand to an agent one at a time: "build step 1A". Each step is one pull
request with its own tests, and each leaves the app working.

**How to use this section.** Give the agent this plan and the step's
letter. The agent reads G.19 first, then the sections the step names, then
the code files the step names. It builds only that step.

**Difficulty** is marked so the owner can choose which model does a step:
- **Routine:** self-contained, little risk. A lighter model can do it.
- **Careful:** touches shared code or has several cases to get right.
- **Hard:** many moving parts, or touches the chat screen. Use the
  strongest model available, and expect more than one review round.

### Overview

| Step | What is built | Difficulty | The user sees | Depends on |
|---|---|---|---|---|
| 1A | The passage cutter | Routine | Nothing | Nothing |
| 1B | Token counting, and one queue for the embedding model | Careful | Nothing | Nothing |
| 1C | The passage stores and per-message updating | Hard | Nothing | 1A |
| 1D | Background embedding of passages | Hard | Nothing | 1B, 1C |
| 1E | Keeping the index in step with the chats | Hard | Nothing | 1C |
| 1F | The setting, progress icon, pop-up, notification, and delete | Careful | The approved screens (G.16a) | 1D, 1E |
| 2A | Retrieval and the subject check | Hard | Nothing | Stage 1 |
| 2B | The inspector, and the measurements | Careful | A test screen (wording to approve) | 2A |
| 3 | Context assembly: recall in real requests | Hard | Recall works (wording to approve) | Stage 2 |
| 4A | Long messages in saved-memory search | Careful | Better memory matches | Nothing |
| 4B | Carry-over for saved memories | Careful | Memories stay through follow-ups | 2A's subject check |
| Later | Optional re-ranker; live AI worker; other chats | To be designed | | |

Nothing changes for any user until step 1F. Steps 1A to 1E add code that
stays switched off, because Index Conversations starts off and cannot be
turned on until 1F.

**Keyword-only use is part of every step, not a later one (Owner,
2026-11-11).** No step may require an embedding model in order to build,
keep, or search the index. With no model: 1C and 1E work in full, 1D
leaves passages waiting, 1F shows that search is by keywords only, and 2A
and 3 recall by keywords. Each step's tests include the no-model case.

Steps 4A and 4B fix gaps that are live on the beta today. 4A depends on
nothing and can be built at any time, including first.

### Step 1A: the passage cutter (Routine)

- **Goal:** a function that takes one message's text and returns its
  passages, exactly as G.5b describes.
- **Read:** G.5b; `util/ConversationTokenChunks.kt` to see how the
  platform's sentence tool is used (do not change or call it).
- **Build:** new files only, plain Kotlin with no Android screen code. The
  cutter takes the text, a target size, and a size-estimating function, and
  returns each passage's start and end. It includes splitting, joining
  short pieces, the last-resort cut, and the version number.
- **Not in this step:** search text, overlap, borrowed context (1C), real
  token counts (1B), any storage.
- **Done when:** every test listed at the end of G.5b that concerns cutting
  passes, including that the passages of a message join back into the
  message exactly.

### Step 1B: token counting and one embedding queue (Careful)

- **Goal:** the app can ask how many tokens a text needs, and every use of
  the embedding model goes through one queue where a live chat request
  goes first (G.5a rules 2 and 6).
- **Read:** G.5a rules 2 and 6; G.2's last point;
  `memory/librarian/HfTokenizer.kt`, `OnnxEmbeddingModel.kt`,
  `EmbeddingModel.kt`, `Librarian.kt`.
- **Build:** a counting function on the tokenizer and the model that
  counts the instruction prefix and special tokens and never cuts
  anything off. One queue for all embedding work with two priorities. Move
  the unlocked call in `Librarian.searchInternal` onto it.
- **Must not change:** any saved-memory search result, any vector already
  stored, or what the tokenizer returns for text that fits.
- **Done when:** existing tests pass unchanged; new tests show the count
  matches the real encoding, and that a live request is served before
  queued background work.

### Step 1C: the passage stores and per-message updating (Hard)

- **Goal:** the four stores exist, and one chat's passages can be brought
  up to date one message at a time (G.5, G.5a rules 1, 3, 4, 7, 9).
- **Read:** G.4, G.5, G.5a, G.5b steps 5 to 7 and "borrowed context", G.7;
  `chatsearch/ChatSearchStore.kt` and `ChatSearchIndexManager.kt` for the
  encrypted-database and revision method; `SummarizerController.kt`
  (`Snapshot`, `Entry`) for the model-facing text; `MessageVisibility.kt`,
  `MessageCompletionState.kt`, `MessageIdentity.kt`.
- **Build:** the encrypted database with the four stores. A function that
  takes a chat's current messages, compares them with the index by
  permanent ID and fingerprint, and replaces only what changed, in one
  transaction per message. Eligibility. Search text, including overlap and
  borrowed context with its rules. Scope stamping from values passed in.
  Vector rows are created as "waiting"; no vectors are made in this step.
- **Confirm in the code and record in this plan:** which stored field
  holds the reply version the AI is sent.
- **Must not change:** the chat search feature or its database, the stored
  chats, the saved-memory database.
- **Done when:** correctness tests pass for new, edited, deleted, hidden,
  unfinished, and failed messages; for borrowed context; and for a run
  interrupted halfway, which must leave the previous correct state.

### Step 1D: background embedding of passages (Hard)

- **Goal:** waiting passages get their vectors in the background, and
  vectors are reused and removed correctly (G.5a rules 2, 3, 5, 6).
- **Read:** G.5a; the queue from 1B; `Librarian.kt` for how missing
  saved-memory vectors are repaired in the background today.
- **Build:** a background worker that takes waiting passages one at a
  time, measures the search text with the real tokenizer, re-cuts the
  message when it does not fit, makes the vector, and stores it by
  search-text fingerprint. Vector reuse. Removal of vectors nothing uses
  and of an old model's vectors. Index state per chat. It can be paused
  and resumed, and it survives the app being closed and reopened.
- **Not in this step:** running after the app is closed (1F), any screen.
- **Done when:** tests show no search text is ever cut off, an edit during
  embedding discards the stale vector, identical search text is embedded
  once, and a live request is never delayed by indexing.

### Step 1E: keeping the index in step with the chats (Hard)

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
  where the finished turn is already recorded. Indexing existing chats,
  newest first.
- **Confirm in the code and record in this plan:** how much existing
  conversation has a recorded scope.
- **Must not change:** how any of those flows behave or how long they
  take. Indexing is always handed to the background.
- **Done when:** a test for each row of the G.12 table passes, including
  restore, merge of two histories, and rename of an older chat.

### Step 1F: what the user sees for indexing (Careful)

- **Goal:** the approved screens in G.16a, and indexing that continues
  after the app is closed.
- **Read:** G.16a; `ui-style-guide.md` in full for the parts used
  (Progress pop-up, toggle rows, section headings, destructive buttons,
  two-action dialogs, header action icons); `ui-style-adoption.md`;
  `MemoryAnalysisForegroundService.kt` for how the app already keeps work
  running with a notification.
- **Build:** exactly what G.16a lists, with the approved wording. Every
  visible value comes from a shared style; a missing style is created and
  added to the guide. `ui-style-adoption.md` is updated.
- **Must not change:** any other part of the chat header or of Advanced
  Memory Settings.
- **With no embedding model:** the setting can still be turned on. The
  status says search is by keywords only. The wording for that status is
  approved by the owner before this step is built.
- **Done when:** turning the setting on starts indexing; the icon appears
  only while indexing runs or is paused; the percentage comes from work
  actually done; deleting the index removes everything derived and turns
  the setting off.

### Step 2A: retrieval and the subject check (Hard)

- **Goal:** given a chat and a message, return the exchanges to offer,
  with every score and reason (G.6, G.9a, G.8a points 1 and 2).
- **Read:** G.6, G.8a, G.9a, G.10; `LiveRetrieval.kt` and
  `KeywordText.kt`.
- **Build:** all nine steps of G.6 and the subject check, as plain Kotlin
  behind the G.8a interfaces. The time limit. Nothing is added to any
  request.
- **Done when:** the quality tests in G.15 that concern "found" and "left
  out" run and report their scores.

### Step 2B: the inspector and the measurements (Careful)

- **Goal:** a way to type a message for a chat and see what would be
  recalled and why, and measured starting values for the numbers.
- **Before building:** the screen's wording and placement are shown to the
  owner. The natural home is beside the Memory Search Test screen.
- **Done when:** the numbers in G.15 have measured starting values recorded
  in this plan, with search time on the owner's phone.

### Step 3: context assembly (Hard)

- **Goal:** recall is added to real requests (G.8, G.8a point 3).
- **Before building, the owner decides or approves:** G2, G3, G4, G11,
  G16, G17, G18, and the exact text given to the AI.
- **Read:** G.8, G.8a; both request builders in `ChatActivity.kt`;
  `memory/enforcer/PromptAssembler.kt`; `util/RequestCapacity.kt`.
- **Build:** assembly in its own file, called from both request builders
  with the smallest possible hookup. The size limits, the final size
  check, excerpts, date order, labels, and the "what was sent" view.
- **Must not change:** anything a request contains when recall is off.
- **Done when:** all quality tests in G.15 pass at the "sent" point, and
  both request paths add the same text.

### Step 4A: long messages in saved-memory search (Careful)

- **Goal:** a small detail in a long message finds its saved memory
  (G.10).
- **Read:** G.2, G.10; `LiveRetrieval.kt`, `Librarian.kt`.
- **Done when:** the long-message test conversations pass, existing
  saved-memory tests pass unchanged, and added sending time is measured
  and reported.

### Step 4B: carry-over for saved memories (Careful)

- **Goal:** a saved memory stays through follow-up messages and drops on a
  change of subject (G.9).
- **Read:** G.9, G.9a; `enforcer/Enforcer.kt`; the injection record in
  `MemoryStore.kt`.
- **Done when:** the carry-over test conversations pass: stays through
  short replies, drops on a topic change, drops when sent by mistake.

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

Asked one at a time, and only when the stage that needs the answer is
about to start. Nothing more is needed before stage 1. Each has a
recommendation.

| # | Question | Recommendation | Needed before |
|---|---|---|---|
| G1 | Is recall on for every chat, or chosen per chat? | **Decided (owner, 2026-11-11):** one setting in a settings menu, applying to all chats. Not chosen per conversation. Which menu, its wording, and whether it starts on or off are G17 | Decided |
| G2 | How large is recall's own size limit, and can the user set it? | Adjustable, with a starting value from measurement | Stage 3 |
| G3 | When a chat no longer fits the model, may the user choose to send recent messages plus recalled exchanges? | Yes, as an explicit choice. Warn or block stays the default | Stage 3 |
| G4 | Does recall apply in Experimental Mode? | No. That mode sends only the current message by design | Stage 3 |
| G5 | Are attached files and images indexed? | **Decided (owner, 2026-11-11):** no, not in this work | Decided |
| G6 | When are existing chats indexed? | **Decided (owner, 2026-11-11):** in the background, never on opening a chat. Proposed details: newest first, with progress shown and a way to pause | Decided |
| G7 | Do backups carry the index? | **Decided (owner, 2026-11-11):** no. It is rebuilt after a restore | Decided |
| G8 | Which jobs does the live AI worker do, and how is it turned on? | Open. Start with marking what matters. The owner chose one app-wide setting for recall, not a per-conversation one | The live AI worker |
| G9 | Across chats, which ordinary chats may see each other? | Same companion or same project only, at first | Recall from other chats |
| G10 | Is a recalled exchange carried over like a saved memory? | Yes, under the same rule | Stage 4 |
| G11 | What does the user see when recall adds something? | Show it where sent memories are shown today, so the user can always tell what the AI was given | Stage 3 |
| G12 | Did the owner make the "seen and not objected to counts as approved" rule recorded in Part E of the memory plan? | If not, relabel those rows as proposed | Any time |
| G14 | Does background indexing of existing chats continue after the app is closed? | **Decided (owner, 2026-11-11):** yes. The notification this requires is part of G15 | Decided |
| G15 | Where does the user see indexing progress, pause it, and delete the index, and what does the notification say? | **Decided (owner, 2026-11-11):** as recorded in G.16a. Index Conversations starts off | Decided |
| G16 | Does recall apply in hands-free voice chat, where any delay is felt? | Yes, under the time limit in G.6, so a slow search is skipped and never delays a reply | Stage 3 |
| G17 | Which settings screen holds the recall setting, what is it called, what does its explanation say, and does it start on or off? | **Decided (owner, 2026-11-11):** it is in Memory Controls. Its name, explanation, and starting state are still open | Stage 3 |
| G18 | The app already has a per-chat switch for saved memories. Does turning that off for a chat also stop recall in that chat? | Asked before step 3 | Stage 3 |

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

## G.19 Rules for whoever builds a stage

A stage may be built by a different agent or model than the one that wrote
this plan. These rules keep that safe.

- **Read this plan and the files it names for your stage.** The memory
  plan (`Memory System/dynamic_memory_plan.md`) is background for
  saved-memory work and is not needed for the conversation index.
- **Build one step from G.16, as one pull request.** Do not start the next
  step.
- **Only "Owner" items are decisions.** Anything marked "Proposed" that
  the user would see, read, or pay for is shown to the owner before it is
  built: wording, screens, settings, defaults, and text sent to the AI.
- **Never change:** the stored chats; the Summarizer and its summaries; the
  existing chat search feature; saved-memory search results; the lorebook;
  what a request contains when recall is off.
- **Never remove anything from a request to make room for recall.**
- **Never add or change a log line** without the owner's approval.
- **Put new code in its own files.** The chat screen's files are too large
  to change safely; add only the small hookup they need.
- **Never copy another screen or file** as a pattern. Follow
  `ui-style-guide.md` for anything visible.
- **If the code does not match what this plan says about it, stop and say
  so.** Do not guess, and do not copy another part of the app to fill the
  gap.
- **A stage is finished** when its tests pass, the local checks and Android
  Checks pass, and this plan is updated to say what was built.

