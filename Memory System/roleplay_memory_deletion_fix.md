# Fix: roleplay memory deletion cleanup (join tables vs mirror columns)

*Owner-relayed fix, worked through with another AI; verified against the code
by reading `MemoryStore.kt` on **July 8 2026**. This is a **spec for the fix,
not the fix itself** — nothing is built here.*

## Status / scope

This is a **real bug in already-shipped code** (Stage 3.6f card teardown), not
a Phase-6-only concern — it can be fixed independently, any time. It is filed
with the Phase 6 material because it lives in the same memory/roleplay tables.

## What I verified in the code (evidence)

- **Retrieval reads the JOIN tables.** `activeMemoriesForScope` gates
  world/campaign/rp_character/companion eligibility with `EXISTS (SELECT 1 FROM
  memory_worlds …)` etc. (`MemoryStore.kt` ~L2362–2412). A memory linked to
  several targets is eligible under each — the join tables are the real
  ownership.
- **Teardown decides by the MIRROR column, not the join table.** All three
  deletions pick which memories to delete/keep with the single mirror column,
  then manually scrub the join table for the deleted target only:
  - `deleteRoleplayCharacter` (L2990): `deleteMemoriesWhere(… "roleplay_character_id = ?")`
    or `putNull("roleplay_character_id") WHERE roleplay_character_id = ?`, then
    `delete("memory_roleplay_characters", "roleplay_character_id = ?")`.
  - `deleteWorld` (L3057): same shape on `world_id` (plus the
    `keepCharacterMemories` branch, also mirror-based: `roleplay_character_id
    IS NOT NULL`).
  - `deleteCampaign` (L3183): same shape on `campaign_id`.
  - The store's own comment (L352–353) states the mirror is *"used only by the
    target teardown paths"* — i.e. the mismatch is baked in by design.
- **Join rows cascade on memory delete, but NOT on target delete.** The join
  tables declare `memory_id … REFERENCES memories(memory_id) ON DELETE
  CASCADE`, but the `world_id/campaign_id/roleplay_character_id` side has **no**
  cascade — so deleting a target does not auto-clean its join rows; the manual
  `db.delete("memory_<targets>", …)` is what does it.

## Accurate diagnosis (one refinement to the original write-up)

The core claim is right: **retrieval uses join tables, teardown uses the mirror
column, and that mismatch is the bug.** One wording refinement: the link *to
the deleted target* is actually scrubbed (the manual `db.delete`), and a
deleted memory's own join rows cascade away — so the usual failure is **not** a
dangling join row to the deleted card. The real harms are:

1. **Wrong hard-delete.** With "delete memories", a memory is deleted whenever
   the deleted target happens to be its **mirror**, even if it still belongs to
   *other* targets via the join table. Deleting the memory then cascades away
   its links to those still-valid targets too — the memory vanishes from
   targets that should have kept it.
2. **Missed / wrong handling of join-only ownership.** A memory owned by the
   target **only through the join table** (its mirror points at a different
   target — possible after an earlier unlink) is not selected by the
   mirror-keyed query: it won't be deleted when it should be, and its link to
   the deleted target still gets scrubbed, which can leave a memory whose scope
   says (e.g.) `world` but which has no surviving world link — an **orphaned
   memory**.
3. **Stale mirror.** The "keep" path nulls the mirror only when it equals the
   deleted target; a memory linked to A **and** B with mirror = B, when B is
   deleted, ends up with a **null** mirror even though A remains — the mirror
   should be **reassigned to A**.

The owner's ruling fixes all three.

## Ruling (authoritative)

**The join tables are the source of truth for roleplay memory ownership.**
When deleting a world, campaign, or RP character, cleanup must query that
target's join table and handle **every** memory linked to it — not only
memories whose mirror column names it. Never leave a join link pointing at a
deleted card, and never leave a memory's scope pointing at ownership it no
longer has.

## Fix logic to implement (per teardown — world / campaign / rp_character; projects share the shape)

Let `J` = the target's join table (`memory_worlds` / `memory_campaigns` /
`memory_roleplay_characters` / `memory_projects`) and `col` = its id column.

1. **Find owned memories from the join table:**
   `SELECT memory_id FROM J WHERE col = :targetId`.
2. For **each** owned memory, compute whether the target is its **only owner of
   that scope type** — i.e. it has no other row in `J` for a different target.
3. **If "keep memories":**
   - Delete just this link: `DELETE FROM J WHERE memory_id = ? AND col = :targetId`.
   - If the memory's mirror `col` == `:targetId`, reassign the mirror to any
     one remaining `J` row for that memory, or set it null if none remain.
   - Keep the memory.
4. **If "delete memories":**
   - If the target is the memory's **only** owner in `J` → hard-delete the
     memory (its join rows across all tables cascade automatically).
   - Otherwise (still owned by another target of that type) → do **not**
     hard-delete; just remove this link and fix the mirror as in step 3.
5. **World `keepCharacterMemories` special case:** make it join-based, not
   mirror-based — a world memory is "a character's too" iff it has a row in
   `memory_roleplay_characters` (any character), not iff
   `memories.roleplay_character_id IS NOT NULL`. Those are kept (world link
   removed, mirror fixed); pure-world memories follow step 4.
6. Keep a final safety sweep `DELETE FROM J WHERE col = :targetId` (harmless
   after steps 3–4), then the existing card-entry / tag-link / target-row
   deletion and tombstone — those are already correct.

## Required tests (owner's list + the mirror case)

Run each for **World, Campaign, and RP character**:
1. Memory linked only to Target A; delete A + **keep** → memory remains, A link
   removed, mirror cleared (null).
2. Memory linked only to Target A; delete A + **delete** → memory is deleted
   (and all its join rows cascade).
3. Memory linked to A and B; delete B (**either** keep or delete) → B link
   removed, memory remains linked to A, **no orphan link**, and if the mirror
   was B it is now A. In the delete-memories variant the memory is **not**
   hard-deleted because A remains.
4. (Added) Memory linked to A and B with mirror = B; delete B + keep → mirror
   reassigned to A (not left null).

## Minor open question

The ruling mentions "unless the user explicitly chooses a broader delete
behavior." The current UI is a single yes/no ("also delete this card's
memories?") with no "delete even memories shared with other cards" option. The
logic above treats shared memories as kept-by-default on delete. If the owner
wants a third, explicit "delete shared too" choice, that's a separate UI
decision — not assumed here.
