## Revised Build Phases

The system should be built in small working stages.

The architecture should be designed for long term expansion, but each phase should produce a visible, testable result.

Do not spend too long building hidden infrastructure before the first memory successfully injects into a prompt.

The first win should be: create one lorebook memory, type a matching trigger, and see that memory appear in the AI context.

### Phase 1: Minimum Working Lorebook

Goal: prove the memory system works.

Build SQLite database storage from the start.

Create the basic memory tables needed for lorebook entries and triggers.

Required features:

• Create memory entry
• Edit memory entry
• Disable or enable memory entry
• Add trigger words or phrases
• Match a trigger against the current user message
• Inject matching memory into the prompt
• Show a simple debug view listing which memory was injected
• Store original source text
• Give every memory a stable ID
• Store created_at and updated_at

This phase does not need full import conflict handling.

This phase does not need vector memory.

This phase does not need ReAct.

This phase does not need advanced sync.

Definition of done:

The user can create a memory like:

Trigger: "complaint draft"
Content: "The user wants complaint drafts to be direct and not softened."

Then the user can type a message containing "complaint draft" and SpeakGPT includes that memory in the prompt.

This proves the engine turns over.

### Phase 2: Basic Portability

Goal: make memories movable before the system grows.

Add JSON export.

Add JSON import.

Add full database backup.

Add full database restore if practical.

The first import version can be simple:

• If imported memory ID does not exist, add it.
• If imported memory ID already exists, skip it and report that it was skipped.
• Do not silently overwrite existing memories.

This is enough for early portability.

Advanced merge conflict handling can wait until a later sync phase.

JSON export should include:

• Export format version
• App name
• Export date
• Stable memory IDs
• Memory content
• Original source text
• Triggers
• Tags if available
• Category if available
• Enabled or disabled state
• Created_at and updated_at

### Phase 3: Stronger Lorebook Controls

Goal: make lorebook memory useful beyond the first crude trigger test.

Add:

• Categories
• Tags
• Priority
• Scope
• Token budget
• Better trigger modes
• Exact phrase matching
• Any trigger matching
• All triggers required
• Case insensitive matching
• Manual pinning for important memories
• Better debug view

The debug view should show:

• Which memories were included
• Why they matched
• Which trigger matched
• Which memories were skipped
• Whether anything was skipped because of token budget

### Phase 4: Searchable Text Memory

Goal: let memory be found even when trigger words do not match exactly.

Add normal text search.

Use SQLite full text search if available and practical.

Search should support:

• Search by phrase
• Search by category
• Search by tag
• Search enabled entries only
• Search disabled entries if user explicitly chooses
• Preview search results
• Manually pin a search result into the current conversation

This is still not vector memory.

This phase is ordinary text search.

### Phase 5: Conversation Summary Memory

Goal: preserve useful conversation context without saving every message as permanent memory.

Add editable conversation summaries.

Summaries should be stored separately from ordinary memory entries.

Summaries should include:

• Conversation ID
• Summary text
• Created_at
• Updated_at
• Optional project or category
• Enabled or disabled state

Conversation summaries should not replace original memory entries.

The user should be able to edit or delete summaries.

Summaries may be included in prompts when relevant to the current conversation.

### Phase 6: Vector Memory

Goal: retrieve memories by meaning, not only by exact trigger or text search.

Add embeddings.

An embedding is a numerical meaning fingerprint generated from memory text.

Vector memory should be added as a retrieval layer, not as a replacement for lorebook or text memory.

Every vector must be connected to original memory text.

Do not store only vectors.

Store embedding metadata separately.

Required fields:

• Memory ID
• Embedding model
• Embedding provider
• Vector data or vector storage reference
• Embedded_at
• Source_hash

Source_hash is used to detect whether the memory text changed and the embedding needs to be rebuilt.

Important design note:

Storing vectors is not the same thing as searching them.

For early personal scale, use brute force similarity search.

Brute force similarity search means:

• Generate an embedding for the current user message.
• Compare it against stored memory embeddings.
• Score similarity.
• Return the closest matching memories.

This is acceptable for hundreds or low thousands of memories.

A specialized vector index is not required for the first vector version.

If the memory database becomes very large later, add a real vector index or external vector database.

Vector search results should be treated as candidates.

They should be ranked, filtered, and limited before being injected into the prompt.

The debug view should show:

• Which vector memories were retrieved
• Their similarity scores if available
• Which were injected
• Which were skipped

### Phase 7: RAG Memory

Goal: combine all memory retrieval methods into one prompt building pipeline.

RAG means retrieval augmented generation.

The app retrieves relevant memories before the AI call and includes selected context in the prompt.

RAG should combine:

• Core memory
• Current conversation summary
• Lorebook trigger matches
• Text search results
• Vector search results
• User pinned memories
• Project specific memories

The app should rank and filter all candidates.

The final memory packet should be model independent.

The selected AI model should receive the prepared memory packet as plain context.

The model should not own the memory database.

The same retrieval system should work with OpenAI, Claude, Gemini, local models, and future providers.

### Phase 8: Advanced Import, Merge, and Sync Planning

Goal: prepare for multi device use.

Add better import behavior.

Advanced import rules:

• If imported ID does not exist, add it.
• If imported ID exists and imported updated_at is newer, offer to update.
• If local version is newer, keep local unless user chooses otherwise.
• If both changed, show a conflict.
• Never silently overwrite local memories.

Add sync ready fields if not already present:

• Device ID
• Version
• Deleted_at
• Last modified source
• Conflict status

Do not build live sync until local export and import are reliable.

### Phase 9: Optional ReAct or Agent Controller

Goal: allow the model to decide when to use tools or memory, but only after deterministic memory works.

ReAct should not be required for basic memory.

ReAct can be added later to decide:

• Search memory
• Save a suggested memory
• Ask user whether to save something
• Search external tools
• Use RAG
• Answer directly

ReAct should sit on top of the memory system.

It should not replace core memory, lorebook memory, text search, vector search, or exportable storage.

### Phase 10: Optional Letta Bridge

Goal: allow future connection to Letta without making Letta the foundation.

Do not build Letta integration first.

Prepare for it by keeping memory structured and exportable.

Possible mapping:

• Core memory maps to Letta style memory blocks.
• Long term memory maps to archival memory.
• Project memory maps to project specific memory.
• Lorebook entries may map to tagged archival memory or custom blocks.

The first Letta feature should probably be export or import, not full live integration.

Keep SpeakGPT memory independent until Letta clearly improves the app.
