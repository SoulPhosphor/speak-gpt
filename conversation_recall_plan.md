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
| Index entries | Paragraph-sized entries pointing to the whole exchange, with labels | Partly (G.4) | None |
| Search | Finds entries by meaning and by keywords | Yes, for saved memories and for chat search | None |
| Scope walls | Decide which entries a chat may see | Yes, for saved memories | None |
| Recall | Adds matching exchanges beside the newest message | Partly. Saved memories are placed this way | A little more text per message |
| Carry-over | Keeps what was relevant while the subject continues | No | None |
| Live AI worker | Optional. The Summarizer's model does message-level work during a chat | No. The model connection exists | Paid, only when turned on |
| Upkeep | Keeps the index correct after edits, deletions, restores | Yes, for chat search | None |
| Testing | Test conversations and the Memory Search Test screen | Screen yes. Conversations no | None |

Recall from other chats is not a separate part. It is the same index and
search, with the scope walls deciding how far the search may look.

## G.4 Parts the app already has

Each was checked in the current code on 2026-11-10.

- **A keyword index of every message in every chat.** The chat search
  feature already keeps one entry per message in its own encrypted database
  (`preferences/chatsearch/ChatSearchStore.kt`). Each entry holds the chat,
  the message's permanent ID, who wrote it, the text, a text fingerprint,
  and timestamps.
- **Upkeep for that index.** `ChatSearchIndexManager.kt` refreshes a chat
  when it changes, handles deleted chats, rebuilds from the stored chats,
  and rebases after a restore. `ChatSearchIndexJournal.kt` records which
  chats still need refreshing.
- **Permanent message IDs.** Every message keeps one ID for life, through
  edits, regenerated replies, and renames (`MessageIdentity.kt`).
- **The embedding model and vector storage.** Vectors are stored by which
  model made them, so a model change is detected and missing vectors are
  repaired in the background (`Librarian.kt`, the `embeddings` table).
- **Meaning and keyword search with a relevance gate** (`LiveRetrieval.kt`,
  `KeywordText.kt`).
- **Scope rules,** enforced in the database query itself
  (`RetrievalScope`, `MemoryStore.activeMemoriesForScope`). Roleplay chats
  are walled off from real-life and project memories, and companion
  memories need an explicit door.
- **Placement beside the newest message.** Memory text is added just before
  the newest message so the earlier history stays unchanged and cacheable
  (`ChatActivity.kt`).
- **A size limit for added text** (Memory Space) and the request-size check
  that warns or blocks when a request does not fit the model.
- **A record of what was sent with the previous message** (the injection
  record in `MemoryStore.kt`, kept from the removed cooldown).
- **The Summarizer's model connection and settings,** and its rule that a
  cut never separates a message from its reply (`SummarySections.kt`).
- **The Memory Search Test screen.**

What this means: the conversation index does not start from nothing, but
neither existing store is the index this plan describes.

- The chat search index holds one entry per **message**, for keywords only.
- The vector store holds vectors for **saved memories** only.
- The paragraph-sized conversation entries, their vectors, their scope
  labels, and recall itself are new. G.5 says exactly how they are stored
  and what is reused.

## G.5 Index entries (Proposed)

**What one entry is.** An entry covers about one paragraph of one message.
It points to its message and its exchange. A short message is one entry. A
long message becomes several entries, so a small detail has its own entry
and can be found.

**What an entry records:**

| Field | Why |
|---|---|
| Chat ID | Which conversation. Lets search stay in one chat or cross chats |
| Message ID (permanent) | Points back to the original message |
| Exchange position | Lets recall deliver the whole exchange and its neighbours |
| Position within the message | Which paragraph |
| Who wrote it | The user or the AI |
| Time written | For date-aware search and ordering |
| Text fingerprint | Detects edits, so only changed text is re-indexed |
| Scope labels | The companion, project, world, campaign, or character the chat had (G.7) |
| Meaning vector, and which model made it | Meaning search. A model change is detected |
| Keyword text | Keyword search |
| AI notes (optional, kept separate) | Written only by the live AI worker (G.11) |

**Where a long message is cut.** In this order, until the piece fits the
embedding model's limit:

1. at paragraph breaks;
2. at sentence ends;
3. at clause breaks (commas, semicolons, line breaks);
4. at the nearest space before the limit;
5. when there is no space (a long web address or an unbroken string),
   between two characters, never through the middle of one.

Step 5 is the guaranteed fallback. After every cut the piece is measured
again (G.5a rule 2). Every character of every message ends up in some
entry, so no text is ever silently unsearchable. An entry never spans two
messages.

**How big an entry is.** A starting size is chosen by measurement (G.15),
inside the embedding model's limit. Published starting points are 400
characters (SillyTavern) and 256 tokens. One study found that chunk size
changed results only slightly, and another found that automatic
"semantic chunking" did no better than plain fixed sizes on realistic text
(G.18). So a simple paragraph rule is supported, and the size is tuned, not
guessed.

**Small entries, whole delivery.** Search runs on the small entries. What
the AI receives is always the whole exchange the entry belongs to, never a
fragment.

**What is indexed.** The text of messages as the user sees them. Attached
files, images, and hidden messages are open questions (G.17).

**Storage design.** Three linked stores:

| Store | One row is | Key | Points to |
|---|---|---|---|
| Passages | One entry (about a paragraph) | chat ID, message ID, position in the message | The message, by its permanent ID, with the start and end of the passage inside the message text, and the exchange it belongs to |
| Passage vectors | One vector | the passage's text fingerprint and the model that made it | Nothing. Any passage with that fingerprint uses it |
| Passage keywords | The searchable words of one passage | the passage row | The passage |
| Index state | One row per chat | chat ID | Which saved version of the chat the index describes, how many passages still wait for a vector, and whether the chat is fully indexed |

- The original text is **not copied** into a second permanent home. A
  passage records where it sits inside its message. The message stays the
  only source.
- Vectors are keyed by text fingerprint, not by passage row. Making a
  vector is the slow step, so text that has not changed is never embedded
  twice, including after the passages are rebuilt or a message is edited
  elsewhere.
- Passages and keywords are derived and can be dropped and rebuilt at any
  time. Vectors are kept through a rebuild.
- AI notes (G.11) are their own rows, keyed to a passage or an exchange,
  never mixed into the passage text.

**Reused as it is:** permanent message IDs (`MessageIdentity.kt`); the rule
for which messages are searchable and how their text is read
(`SearchableMessageProjection.kt`); the embedding model, its lock, and
vector maths (`Librarian.kt`, `VectorMath.kt`); the keyword word rules
(`KeywordText.kt`).

**Extended:** the chat index's refresh, journal, rebuild, deletion, and
restore handling (`ChatSearchIndexManager.kt`, `ChatSearchIndexJournal.kt`)
gain the passage stores as a second thing they keep in step. The scoring in
`LiveRetrieval.kt` is written for saved memories and is generalized so it
can score passages too, without changing saved-memory results.

**New:** the three stores above, the passage cutter, background embedding
of passages, and recall.

**Not changed:** the existing chat search feature and its one-entry-per-
message index, the saved-memory vector table, and the stored chats.

## G.5a Rules the index must guarantee (Proposed)

Added after an outside review on 2026-11-10. Each point was checked in the
code and is true today. These are requirements for stage 1, not options.

**1. A message hidden from the AI is never recalled.**
- The app lets the user hide a message from the AI
  (`MessageVisibility.kt`). Everything that sends conversation to a model
  must skip it.
- The chat search index does not apply that rule
  (`SearchableMessageProjection.kt`), because it serves the user's own
  search. It must not be reused for recall as it is.
- Rule: a hidden message is not eligible for recall. Eligibility is checked
  twice: when indexing, and again at the moment of recall against the
  stored message, so hiding a message takes effect at once even when
  background indexing has not caught up.
- The same double check applies to deleted messages, unfinished replies,
  and reply versions that are not the one shown.

**2. No text is ever cut off silently.**
- The tokenizer drops everything past the model's limit without saying so
  (`HfTokenizer.encode`).
- Rule: the passage cutter measures every piece with the real tokenizer,
  counting the instruction prefix and special tokens, and cuts again until
  the piece fits. A passage is recorded as embedded only when all of its
  text was read by the model.

**3. Vectors have an owner and a lifetime.**
- A vector is reused only when the text, the embedding model, the vector
  size, and the way the text was prepared all match.
- When the last passage that uses a vector is deleted or changed, the
  vector is removed.
- Vectors made by a model that is no longer selected are removed once the
  new model's vectors are complete.
- Deleting a chat removes everything derived from it, including vectors no
  other chat uses.

**4. An index update is all or nothing.**
- The chat search index already records which saved version of a chat it
  was built from and refuses stale results (`ChatSearchIndexManager.kt`,
  `ChatSearchIndexJournal.kt`). The passage stores follow the same method.
- Rule: passages and keywords for a chat are replaced together, in one
  database transaction, against a recorded version of the chat. Before
  publishing, the version is checked again. A background job that finishes
  after the chat changed throws its result away.
- A vector that arrives for text that no longer exists is discarded.

**5. Search stays fast as conversations grow.**
- Saved-memory search compares the message against every vector, which
  its own notes say suits up to about 50,000 memories (`Librarian.kt`).
  Conversation passages will outnumber saved memories many times over.
- Rule for stages 1 to 3: meaning search looks only at the current chat's
  passages. Keyword search uses the database's own keyword index. Vectors
  are read in bounded batches, never all chats at once.
- Memory use is bounded too: as each batch is scored, only the best
  candidates so far are kept and the batch is released. The vectors of a
  long chat are never all held in memory together.
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
- Checked in the code on 2026-11-11: every new message receives a
  permanent ID, and an older chat's messages receive theirs the first time
  that chat is opened (`ChatActivity.kt`, `MessageIdentity.kt`). So the
  chat a user is in always has IDs, and recall inside the current chat
  loses nothing.
- **Owner (2026-11-11):** no migration is built to give IDs to old chats
  that have never been reopened. Those chats are simply not indexed until
  the user opens them. Backwards compatibility for them is not wanted.
- Missing scope stays private to its own chat (G.7).

**The indexing steps for one new or changed message:**

1. Check that it may be recalled: visible to the AI, finished, and the
   version shown.
2. Cut it into passages that each fit the model, recording exactly where
   each sits in the message.
3. Replace that message's passages and keywords in one transaction.
4. Reuse any vector that already matches. Queue the rest.
5. Make queued vectors in the background, behind any live request.
6. Mark the chat's index state complete when nothing is waiting.

## G.6 Search (Proposed)

- **Two ways together:** meaning (the embedding model) and keywords. On
  conversation text, published results show keyword search matters as much
  as meaning search (G.18), and it does not depend on the embedding model.
- **Search text:** the user's latest message. For a long message, each
  sentence is also searched on its own (G.10).
- **Follow-up questions** such as "what did we decide?" have no meaningful
  words of their own. They are handled by the subject check (G.9a), not by
  gluing earlier messages onto the search text, which #180 measured as
  harmful. The subject check is its own component, so recall does not wait
  for saved-memory carry-over to be built.
- **A relevance gate:** an entry is used only when it clearly relates. When
  nothing relates, nothing is added.
- **Duplicates:** several entries from the same exchange count once.
- **The gate numbers for conversation entries are set separately** from the
  saved-memory numbers. Conversation text is longer and looser than a saved
  memory, so the same numbers are not assumed to fit.

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
  recall is chosen per chat or per companion (G.17).
- **Where an entry's scope comes from.** A chat's companion, project, or
  roleplay settings can change during its life, so the chat's settings
  today are not a safe label for its older messages. The memory system
  already stamps each captured stretch of conversation with the companion,
  world, campaign, character, and project that were active when it was
  captured (`TranscriptRecord`, used by `ArchivistSceneContext`), and
  never infers them afterwards. The index takes scope from that record
  where one exists, and stamps new messages itself as they are written.
- **Unknown scope is treated as private to its own chat.** An older message
  with no recorded scope can be recalled inside its own chat and is never
  offered to another chat.
- **To verify when stage 1 is built:** how much existing conversation has a
  recorded scope.

## G.8 Recall: what is added to a request (Proposed)

1. The request is built exactly as today: the full chat, or the Summarizer's
   summaries plus recent messages when the Summarizer is on.
2. The index is searched with the user's latest message.
3. Matching exchanges are added, whole and in their original words, in a
   clearly marked section just before the newest message, beside the saved
   memories. The stored conversation is not changed.
4. Each recalled exchange carries when it was said, so the AI can tell old
   from recent.

Why this helps even when the whole chat is already sent: models attend most
to the start and end of what they are given and least to the middle (G.18).
An exchange from far back sits in the middle. A copy beside the newest
message is where the model reads most carefully.

Rules:

- **Its own size limit,** separate from Memory Space, so recall can never
  push out saved memories, and saved memories can never push out recall.
  Separate limits are a way of sharing room. They do not create room: the
  model's total size is still one fixed amount.
- **One final size check for the whole request.** After everything is
  assembled (the history, the Summarizer's text when it is on, saved
  memories, carried-over memories, and recalled exchanges), the existing
  request-size check runs once on the total. When recall would push the
  request over the model's limit, recall is what is left out for that
  message, and the request goes exactly as it does today. Nothing else
  gives way to recall unless the user has chosen that (G3).
- **When an exchange is too large to add whole.** Indexing and delivery are
  separate. Every part of it stays searchable. For delivery, the whole
  exchange is added when it fits recall's limit. When it does not, the
  matching passage and the paragraphs around it are added in their original
  words, marked as an excerpt, with a note that the rest of that exchange
  was left out. It is never cut silently.
- **Not the most recent messages.** An exchange the AI has just read is not
  recalled again.
- **Only true duplicates are skipped.** An exchange is skipped when its own
  text is already in the request close to the newest message. An exchange
  is **not** skipped because a saved memory covers the same fact. A short
  saved fact does not carry what the conversation meant.
- **Neighbours.** The exchange before or after is brought along when the
  matching one cannot be understood alone.
- **How many.** A starting number is set by measurement. SillyTavern starts
  with 3. Redis's tested setup is reported as 5 with one neighbour each
  side; that figure could not be confirmed from its public page.
- **When the chat no longer fits the model:** today the app warns or blocks.
  That stays the default. Any other behaviour is the user's explicit choice
  (G.17).
- **Experimental Mode** sends only the current message by design. Whether
  recall applies there is open (G.17).

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

**Open:** whether a recalled exchange is also carried over (G.17).

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
| Message edited | Entries for that message are replaced. Unchanged paragraphs keep their vectors |
| Reply regenerated or another version chosen | Entries follow the version that is shown |
| Message deleted | Its entries and any AI notes on them are removed, and any vector nothing else uses |
| Message hidden from the AI, or made visible again | Takes effect on recall at once (G.5a rule 1) |
| Chat deleted | All its entries are removed |
| Backup restored | The index is rebuilt from the restored chats |
| Embedding model changed | Keyword search keeps working at once. Vectors are remade in the background |
| Chat settings changed (companion, project, roleplay) | Scope labels on later messages follow the new settings |
| Existing chats, first time | Indexed in the background, a little at a time, newest first |

The index is derived: it can always be rebuilt from the stored chats, and
losing it loses nothing the user wrote.

## G.13 When a part is unavailable (Proposed)

| Missing | What still works |
|---|---|
| The embedding model | Keyword recall. This is also the later path for users who cannot have the model |
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
- **Battery.** Indexing existing chats is heavy work. When it runs is an
  open decision (G.17).
- **Storage.** A 256-number vector is about 1 KB per entry.
- **Privacy.** The index stays on the phone, encrypted like the chat search
  database. Nothing is sent anywhere by indexing. Only the optional AI
  worker sends conversation text to a model, and only when turned on.
- **Backups.** The index can be rebuilt, so whether backups carry it is an
  open decision (G.17).

## G.15 Testing and measurement

Tests are written with the component they test, in the same pull request.
There is no separate testing stage in front of the building.

- **Correctness tests** come with every stage: for example, that every
  character of a message lands in an entry, that an edit replaces only the
  entries it touched, and that a deleted message leaves nothing behind.
- **Sample conversations** are added as each stage needs them: long
  messages with one small important detail, follow-ups, changes of
  subject, a memory sent by mistake, roleplay and companion chats, and a
  library where most memories share one subject. They stay as automated
  tests.
- **Numbers that research does not give** are measured inside the stage
  that needs them, with the real embedding model: the entry size, the
  relevance gate for conversation entries, how many exchanges to recall,
  the subject check's two numbers, the carry-over bar, and speed on the
  phone.
- **These are engineering numbers.** The owner is not asked to choose them.
  Each has a starting value from measurement and is adjustable afterwards.

## G.16 Build stages

The design covers all stages. Each stage works on its own, is its own pull
request, and comes with its own tests. The owner chooses the order and when
each starts.

| Stage | What is built | Depends on |
|---|---|---|
| 1 | The passage stores, the passage cutter, background embedding, scope labels, upkeep, and every rule in G.5a (G.5, G.5a, G.7, G.12) | Nothing |
| 2 | Conversation search, the subject check, and a way to inspect what a message would recall (G.6, G.9a) | Stage 1 |
| 3 | Recall added to real chat requests, with the final size check (G.8) | Stage 2 |
| 4 | Saved-memory fixes: long messages and carry-over (G.9, G.10) | The subject check from stage 2 |
| Later | The optional live AI worker (G.11) | Stage 1 |
| Later | Recall from other chats, behind the scope walls (G.7) | Stage 3 |
| Later | Keyword-only recall for users without an embedding model | Stage 3 |

**One choice for the owner about order.** The two saved-memory gaps in
stage 4 are live on the beta now and affect every chat that uses memory.
The long-message fix depends on nothing and could be built first. Carry-
over needs only the subject check, which could be built with it. Putting
stage 4 first fixes what is broken today sooner; keeping it fourth gets
the index started sooner. Neither blocks the other.

## G.17 Open decisions for the owner

Asked one at a time, and only when the stage that needs the answer is
about to start. Only G5, G6, and G7 are needed before stage 1. Each has a
recommendation.

| # | Question | Recommendation | Needed before |
|---|---|---|---|
| G1 | Is recall on for every chat, or chosen per chat? | Per chat, starting off, so nothing changes for anyone who does not turn it on | Stage 3 |
| G2 | How large is recall's own size limit, and can the user set it? | Adjustable, with a starting value from measurement | Stage 3 |
| G3 | When a chat no longer fits the model, may the user choose to send recent messages plus recalled exchanges? | Yes, as an explicit choice. Warn or block stays the default | Stage 3 |
| G4 | Does recall apply in Experimental Mode? | No. That mode sends only the current message by design | Stage 3 |
| G5 | Are attached files and images indexed? | Not at first. Message text only | Stage 1 |
| G6 | When are existing chats indexed? | In the background while charging, newest first, with progress shown | Stage 1 |
| G7 | Do backups carry the index? | No. It is rebuilt after a restore | Stage 1 |
| G8 | Which jobs does the live AI worker do, and is it chosen per chat? | Per chat. Start with marking what matters | The live AI worker |
| G9 | Across chats, which ordinary chats may see each other? | Same companion or same project only, at first | Recall from other chats |
| G10 | Is a recalled exchange carried over like a saved memory? | Yes, under the same rule | Stage 4 |
| G11 | What does the user see when recall adds something? | Show it where sent memories are shown today, so the user can always tell what the AI was given | Stage 3 |
| G12 | Did the owner make the "seen and not objected to counts as approved" rule recorded in Part E of the memory plan? | If not, relabel those rows as proposed | Any time |

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

## G.19 Rules for whoever builds a stage

A stage may be built by a different agent or model than the one that wrote
this plan. These rules keep that safe.

- **Read this plan and the files it names for your stage.** The memory
  plan (`Memory System/dynamic_memory_plan.md`) is background for
  saved-memory work and is not needed for the conversation index.
- **Build one stage, as one pull request.** Do not start the next stage.
- **Only "Owner" items are decisions.** Anything marked "Proposed" that
  the user would see, read, or pay for is shown to the owner before it is
  built: wording, screens, settings, defaults, and text sent to the AI.
- **Never change:** the stored chats; the Summarizer and its summaries; the
  existing chat search feature; saved-memory search results; the lorebook;
  what a request contains when recall is off.
- **Never remove anything from a request to make room for recall.**
- **Never add or change a log line** without the owner's approval.
- **If the code does not match what this plan says about it, stop and say
  so.** Do not guess, and do not copy another part of the app to fill the
  gap.
- **A stage is finished** when its tests pass, the local checks and Android
  Checks pass, and this plan is updated to say what was built.

