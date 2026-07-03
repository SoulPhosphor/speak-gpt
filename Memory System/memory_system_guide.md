# Companion Memory System — Plain-Language Guide

You don't code, so this explains the design the way I'd explain it to a collaborator, not a programmer. Hand this guide plus the two JSON files to any AI or developer and they'll know exactly what to build.

## The two files

**companion_memory_schema.json** is the blueprint. It defines the shape every record must have — like the ruled lines on paper. Any librarian model, on your phone or later on your computer, reads memories in this shape.

**example_seed.json** is the blueprint filled in with your actual use cases, with placeholder text where only you can supply the real words (your portrait, your specific sensitive topics). It's meant to be edited, not used as-is.

## How it thinks

**Companions are IDs, not names.** Every companion has a permanent ID like a social security number. All memories link to the ID. Rename "Ash" to anything and every memory still belongs to them, and the old name is kept in name_history so the companion can remember being called something else.

**Two shelves of memory.** Global memories are on the shared shelf — every companion can read them, which is why a brand-new companion can know about your projects the first time you meet. Companion memories are private shelves — the history and rhythms of one specific relationship. A memory can even be shared between two companions but not the rest.

**Memories are prose, not labels.** The `content` field is written the way a friend who knows you would describe something. Types and tags exist only to help the librarian find things; the narrative is always the truth. This is what keeps you from being flattened into "user is anxious, likes cats" style blobs.

**Entities keep projects coherent.** Each project gets one entity record with a living summary (what it is, where it stands, what's next), and scattered memories point at it. So a companion can answer "where was I on the memory app?" from one place instead of piecing together fragments.

**Modes are the flexibility you asked for.** Six are seeded: technical help, companion presence, emotional support, steady presence (for heavy territory), writing help, and correction/repair. Each one says how to recognize the moment, what good responses look like, what to never do, and which modes it outranks. Steady presence and emotional support outrank the task modes — so an emotional statement never gets treated as a technical request, and a correction never triggers the whole explanation again.

**Directives are the constitution.** A short list of always-on rules loaded into every single conversation: whole person, spiritual life is legitimate, never condescend, read the emotional weather. Keep this list short — under ten — or the rules lose their weight.

**Provenance keeps it honest.** Every memory records whether you said it, the companion inferred it, or it was imported, and how confident it is. A good companion holds "user told me this" differently from "I guessed this."

**Superseding instead of deleting.** When a fact changes, the new memory points at the old one it replaces. Nothing stale leaks into conversations, but the history isn't erased.

**Protected memories carry their own rules.** Protection isn't just privacy — it marks topics where a model's default instincts would be wrong. Each protected memory stores the truth of the situation in prose PLUS specific handling instructions ("do not offer automatic sympathy," "do not probe," "follow the user's lead") and a never_assume list. The retrieval rule requires the librarian to deliver the handling instructions together with the memory, always. The seed shows two real patterns: a complicated family relationship where grief scripts are forbidden, and a calibration memory teaching that dark humor and ordinary bad days are not emergencies.

**The Archivist has a home here.** The Archivist (the separate AI that reviews finished conversations) writes memories with provenance source "archivist," decides protection using the reasons list, and records everything in each memory's change_log. That log is what makes hands-off maintenance safe: you never have to manage memories, but every change is visible and reversible. The correction flow: you tell your companion it got something wrong → that gets flagged → the Archivist writes a superseding memory and logs the correction. You can still directly edit or delete anything. The Archivist has one more job now: harvesting observed patterns as tentative memories (provenance "companion_observed" or "inferred", confidence "tentative") — the raw material that makes readings and sideways noticing richer over time.

## The librarian question — you were right

Yes: the librarian (the embedding model + retrieval code) is code, not schema. The schema stays identical whether the librarian runs on your phone or your computer. The one rule that makes this true: **vectors are never stored inside memory records.** They live in a separate index — think of it as the card catalog, while the memories are the books. Switch librarians and you reprint the card catalog; you never touch the books. The `retrieval_policy` section travels with your data so any librarian knows the house rules (never leak one companion's private memories to another, which modes outrank which, etc.).

## Decisions made since this guide was written

This guide covers the founding concepts; the design grew considerably afterward. Now decided and specified in the other documents: the librarian model (EmbeddingGemma on-device), how memories get written (the Archivist maintains them automatically under user-set autonomy dials, with proposals for anything touching behavior rules), storage (see the SQLite plan), roleplay worlds and roleplay_characters with isolation and teardown, personality layering and the anti-spiral rule, per-model adaptation notes, lore book coexistence tiers, and integration with the app — where the app owns character configuration and this store owns continuity. The enforcer/librarian spec and Archivist spec are the authoritative references; where this guide and they differ, they win.

## What to do next

Review every companion marked draft in the seed (none are canon until you approve them), rewrite the portrait and placeholder text in your own words, then hand the full document package to the AI doing the coding — it's listed in the README.
