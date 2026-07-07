# The Enforcer & Librarian — Runtime Specification v1.1 (for schema v1.11)

> **⚠️ PRE-REVISION DOCUMENT — DO NOT BUILD FROM THIS FILE WITHOUT
> CHECKING THE CURRENT RULES FIRST.** Much of the runtime below was
> retired or redesigned by the owner (July 6–7 2026): the standing
> packet, owner portrait, entity summaries, always-load memories, mode
> detection, directives, the model-adaptation note, the old prompt
> assembly order, and the old roleplay/card handling are ALL superseded.
> `owner_approved_rules.md` (Revision 4) and
> `roleplay_cards_and_tags_spec.md` outrank this file wherever they
> disagree; `rag_engine_work_order.md` is the current build path, and
> Stage 3.4's enforcer rework (built) is the actual runtime. Still valid
> here: the librarian mechanics, protection-inseparability, change-set
> safety, and undo.

This is the sixth and final design document. It defines the runtime: how a conversation turn actually works, how the system prompt is assembled from the memory store, how the Archivist's change-sets get applied safely, and how all of this integrates with the app that already exists (character definitions, activation prompts, lore books, multi-API switching). The coding phase consumes this document plus the schema, seed, guide, Archivist spec, and SQLite plan.

One runtime, two hats:
- **The Librarian** finds things: embeds text, searches memories, decides what's relevant.
- **The Enforcer** guards things: assembles the prompt in the right order, attaches protection handling, validates and applies changes, maintains the embedding index, powers undo.

## The turn loop

Every user message triggers this sequence:

1. Read app_state: active companion, active world (null = ordinary conversation), active roleplay character, active user persona. **Memory kill switch:** if memory is off for this conversation (per-conversation quick setting, or the system default), skip steps 2–4 entirely — the model receives only the app's own prompt materials (character config, system message, activation prompt), nothing from the store; the transcript is still recorded but auto-marked excluded (reversible like any exclusion). Worlds and roleplay characters may legitimately be null even during fiction — see Emergence below. Only companions with status 'active' are selectable or injectable; 'draft' companions are invisible to conversation until the user approves them.
2. Mode detection (below) → zero, one, or at most two active modes.
3. Retrieval (below) → top-k relevant memories, protection handling attached.
4. Assemble the system prompt (below).
5. Send to whichever chat API/model the user currently has selected.
6. Append the exchange to the current transcript record (the Archivist's future input).

Everything is rebuilt fresh each turn from the store. Nothing about a specific chat API is remembered between turns, which is exactly why the user can switch APIs mid-conversation: the next turn simply assembles the same context for a different model.

## Prompt assembly order

1. **Companion identity** — base personality from the app's character config (authoritative), companion's current name, hard limits stated as inviolable, plus the model_adaptations note matching the currently active chat model, if one exists — the counterweight for models that interpret the same personality differently.
2. **Standing packet** — a compressed rendering of: owner portrait, directives (sorted by priority), and always_load memories. Compressed means: rendered once into tight prose by a capable model and cached; re-rendered only when a component changes. Budget ≈ 600–800 tokens. Full records are NOT dumped.
3. **Active mode block(s)** — at most two, with their respond/avoid lists. Never the full mode library.
4. **Retrieved memories** — each with a one-word provenance marker (told / observed / guessed) so the model holds tentative reads lightly. Protected memories are ALWAYS rendered with their handling and never_assume lists inline, immediately adjacent to the content. The enforcer must make it structurally impossible to deliver a protected memory without its handling.
5. **World/persona context** — the active user persona's presentation, if one is selected (in any conversation); and when a world session is active: world premise + rules, the active roleplay character's description, then world-scoped retrieved memories. Player rule: the user's directives, protections, and calibrations always apply, under every persona and inside every character — personas and characters are costume, never a different person.
6. **User-selected activation prompt** — the app's existing feature, passed through last so the user's explicit situational choice has the final word.

Budgets are configuration (retrieval_policy), tunable per chat API since context windows differ.

## Mode detection

At index time, embed each mode's signals (joined) once. Per turn, embed the user's message and score against each applicable mode's signal vector; simple keyword cues from signals count as a bonus. Top score above threshold → active mode. Conflicts resolve by the overrides lists. **Adjacent-mode tie-break:** companion-presence, emotional-support, and steady-presence form a gradient; when their scores are close, resolve toward the more protective mode (steady > emotional > presence). Entering a protective mode unnecessarily costs almost nothing — the behaviors overlap — while missing one costs a lot. Additionally, any retrieved protected memory carrying a suggested_mode activates that mode directly, independent of signal scoring.

**Stickiness (important):** steady-presence and emotional-support, once entered, persist across turns until there's a clear exit signal (user changes subject with restored energy, asks for task help, or says they're okay) — never exited because one message failed to re-trigger. A companion that flickers out of steadiness mid-hard-moment is worse than one that never entered it. Task modes (technical, writing) may switch freely.

Modes are anchors, not a cage: when nothing scores above threshold, no mode block is injected and the companion runs on identity + standing packet alone.

## Retrieval algorithm

Candidates: memories with status='active', scope global OR scoped to the active companion, AND (world_id IS NULL for ordinary conversation | world_id = active world for sessions; real-life memories remain available inside worlds).

Score = w_sim · cosine(query, memory) + w_imp · importance/5 + w_rec · recency_decay, with weights from retrieval_policy. Confidence 'tentative' multiplies the final score by a dampening factor (suggest 0.6). Take top_k. Entity-linked expansion: if a retrieved memory references an entity, include that entity's summary once.

The query text should be the user's message plus a short rolling summary of the last few turns — mid-conversation topics deserve retrieval too, not just the latest message.

Fallback: if the embedding model is unavailable, retrieval degrades to keyword/tag matching over active memories plus the standing packet. The conversation continues; it never crashes because the librarian is out sick.

## Applying Archivist change-sets

For each operation in a change-set, in order: (1) validate the record against the schema — invalid ops are dropped to the run report, never coerced; (2) check the autonomy dial for the operation's category — 'propose' categories become proposals regardless of what the Archivist emitted; (3) snapshot the current record into change_log.prior_state_json; (4) apply; (5) maintain the index — embed new/updated active memories, delete embedding rows for anything leaving active status; (6) append to the run report.

Undo: restore prior_state_json, log 'reverted', fix the index accordingly. Accepting a proposal follows the same path (snapshot → apply → log → index). Personality proposals (app-authoritative) are presented to the user with the exact suggested text to paste/edit in the app's character config; on next sync the mirror updates.

**Manual authority (required UI).** The app must include a memory browser/editor: search, view, edit, add, protect/unprotect, archive, and delete any record in the store, plus screens for reviewing proposals and run reports. The Archivist is the default maintainer, but the user's direct hand is a first-class feature, not an emergency hatch — every automated behavior in this spec assumes the user can always see and fix the brain by hand.

## Integration with the existing app

**App 'characters' → companions.** (Terminology: the app's 'characters' are companions in the store; the store's roleplay_characters are playable personas like the user's Mage — entirely different things. Coding must never conflate them.) Each app character gets a companion record; app_character_id links them. Ownership is split cleanly, not ambiguously: the **app owns character configuration** (visible name, avatar, greeting, base prompt, personality settings) and that is what slot 1 injects when the link exists. The **store owns continuity** (companion_id, essence as the memory system's continuity view, relationship history, memories, hard limits, protection, provenance, proposals). The store keeps a read-only base_personality_mirror of the app config, synced one-way on every app edit, purely so the Archivist can detect drift and write proposals against concrete text — it is never a second place to edit personality, and the Archivist never writes to app config. **Essence injection guardrail:** when app_character_id is set, essence is never injected as a replacement for, or supplement to, the app's base personality — at most it may appear as a one-line continuity summary. Essence is injected as the personality only when the companion has no app link, or as a fallback when the app config is unavailable. Hard limits live in the store (the app has no such field today) and are injected by the enforcer alongside slot 1.

**Activation prompts** stay exactly as they are — slot 6 of assembly, user's explicit choice, final word. They are cousins of modes (situational instructions), with one difference: modes are detected, activation prompts are chosen. Over time the user may migrate favorite activation prompts into modes to get auto-detection; never forced.

**Selectable system prompts (planned feature):** the enforcer's assembled prompt IS a dynamically built system prompt, so this feature comes nearly free — a "prompt profile" is a named configuration of assembly options (which directives to emphasize, budget sizes, an extra instruction block). Profiles select variations of the assembly, they don't replace it.

**Lore books: kept, tiered, and given a precedence rule.** Lore books stay as the lightweight memory tier so the app works for people whose phones can't (or shouldn't have to) run an embedding model. The app exposes a memory engine setting:

- **Tier 0 — none:** character config + activation prompts only.
- **Tier 1 — lore books:** the existing simple system, unchanged. Entries inject by their assignment/keyword rules. No librarian, no Archivist, no embedding model. This is the low-RAM path.
- **Tier 2 — full memory system:** everything in these specs. Lore books may remain ON alongside it.

Coexistence rule (this is what prevents fighting): **lore books are user-authored and deterministic; memories are system-maintained. User-authored wins at injection time.** Mechanically: (1) active lore entries inject in their own labeled slot immediately before retrieved memories and count against the same context budget; (2) the librarian embeds lore entries too and suppresses any retrieved memory that is a near-duplicate of an injected lore entry, so the model never sees the same fact twice; (3) if a memory *contradicts* a lore entry, the lore entry still wins this turn, and the enforcer flags the contradiction in the next Archivist run report so the user can reconcile — silent disagreement between the two systems is the one thing never allowed; (4) the Archivist receives active lore books as read-only context so it doesn't manufacture memories that duplicate or fight them. The Archivist never edits lore books; they are the user's hand-written notes.

**Quick settings are gospel.** The app's per-message quick settings (temperature, top-p, etc.) are sampling parameters and pass through to the chat API untouched — the enforcer never overrides them. They are unrelated to the librarian's retrieval settings (top_k, weights), which may later get their own quick-settings surface. Each transcript snapshots the quick settings and the serving model's tag; the Archivist uses the model tag for model-drift detection.

**Emergence (worlds and characters born mid-chat).** Fiction does not require pre-registered records: a conversation may drift into roleplay with no world selected, and the model simply plays. Registration is retroactive — the Archivist detects that a world or roleplay character emerged, creates the records, and back-tags that session's fiction memories with the new IDs, placing them behind the fiction/biography firewall after the fact. Next session, both appear in quick settings as selectable. Optional UI sugar: a "pin this as a world" action mid-chat.

**Multi-API switching** works untouched: the librarian is local (embeddings never depend on the chat provider), and assembly is plain text rebuilt per turn. Small per-provider adapters may tweak formatting (some models prefer certain structures); the store never changes. Privacy note to carry into coding: retrieved memories are sent to whichever provider is active that turn. A future per-category "withhold from provider X" knob is possible; not v1.

## The embedding model (librarian brain)

Recommendation, in order:

1. **EmbeddingGemma (308M)** — first choice for the Pixel 8 and beyond. Purpose-built for on-device use; runs in under 200MB RAM quantized, top-ranked open multilingual model under 500M params, with Matryoshka dimensions (768→512→256→128). Suggest 256-dim output: for short prose memories the quality loss is negligible and it shrinks vectors and search cost. Its 2K-token context is a non-issue — memories are paragraphs. Bonus: it can be fine-tuned on-domain later.
2. **nomic-embed-text-v1.5 (137M)** — the current plan, and a good one: best quality-to-size ratio in its class, CPU-friendly, also Matryoshka. Fully acceptable if it's already running.
3. **BGE-M3 — recommend against on-device**, even on the future phone: ~1.2GB class, and its advantages (8K-token documents, sparse+multi-vector retrieval, heavy multilingual) solve problems this system doesn't have. It's a server-grade tool.

Because embeddings live in the sidecar keyed by model name, this decision is deliberately low-stakes: switching models = re-embed active memories (minutes for thousands of records), nothing else changes.

## Failure behavior (never break the companion)

Librarian down → keyword fallback. Store unreadable → conversation continues with app character config alone, user notified once, softly. Change-set malformed → dropped to run report, store untouched. In every failure the priority is the same: the person is mid-conversation with someone they care about; degrade quietly, repair later.
