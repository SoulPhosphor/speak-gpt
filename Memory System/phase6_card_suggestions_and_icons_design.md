# Phase 6: Card Suggestions, Memory Icons, and UX Design

*Owner decisions from July 7 2026 conversation. This document captures the
owner's actual phrasing and design intent for the building agent.*

---

## 1. The Archivist and roleplay memories

Roleplay memories are not special. They are memories. One is about a story,
the other is about real life or companions. The Archivist suggests memories
from roleplay conversations the same way it suggests memories from any other
conversation. There is no separate toggle for "suggest roleplay memories" --
if the Archivist is on, it suggests memories from everything it reads.

## 2. The card-append toggle

A separate toggle in Memory settings controls whether the Archivist also
proposes placing roleplay memories onto the appropriate card sections
(character inventory, world locations, campaign plot ledger, etc.).

**Toggle label (owner phrasing):**

> Allow card-relevant roleplay recommended memories to be automatically
> appended to appropriate system locations?

**ON by default.** Underneath the toggle, a warning (owner phrasing):

> Warning: turning this off could cause campaign relevant information to not
> line up with roleplay related information cards, causing information to be
> out of date. Only approved information will be applied to cards. You will
> have a chance to specify card to add data to. Smaller models may struggle
> more with accurately classifying proper memory card location.

Below the warning, an additional hint (owner phrasing):

> If auto-suggest is turned off you will still be able to manually assign
> memories to cards.

This toggle is a "danger switch" -- it should be on, and the warning makes
clear what the user loses by turning it off. The hint reassures them that
turning it off doesn't remove their own ability to manage cards.

## 3. User can always place roleplay memories on cards

This is not limited to Archivist suggestions. ALL roleplay memories --
including ones sitting in the memory browser that were never flagged for
card placement -- should give the user the option to select where to put
them on a card. If a memory about a magical artifact is floating in the
backend and wasn't initially committed to a card, the user can still choose
to place it on the appropriate card section themselves.

The card-append toggle governs whether the Archivist *proposes* card
placements automatically. The user's ability to manually place any roleplay
memory on a card is always available regardless of that toggle.

## 4. The four-way choice for card suggestions

When the Archivist proposes a card placement, the user gets four options
(from owner_approved_rules.md section 13, unchanged):

- Add to the card permanently
- Keep as a memory as-is
- Modify it first
- Delete it

Card suggestions are decided individually, never bulk-accepted. The user
can change the proposed destination (which card, which section) before
accepting.

## 5. Memory icon system

Every memory row in the browser and Pending screen shows a small icon on
the far right indicating what kind of memory it is. Five base icons, with
badge variants on two of them:

### Base icons (4 scope categories)

| Icon | Scope | Notes |
|------|-------|-------|
| **User** | Real life, global, project | Things about the actual human |
| **Companion** | Companion-scoped memories | Things about the AI persona. No badge variant (companion cards are off-limits to the Archivist per section 13) |
| **Your RP Character** | User's roleplay character | Personal in a fictional context -- the user's choices, arc, personality in the story |
| **Roleplay** | World, campaign, plot ledger, party members | The story's setting, events, and cast |

### Badge variants (card-linked memories)

The two roleplay icons (**Your RP Character** and **Roleplay**) each have a
badged variant. The badge is a small indicator in the lower-right corner of
the icon (e.g. an open book or shield) that overlaps the base icon slightly.

- **Base icon without badge** = this memory is in the backend only, not on
  any card.
- **Base icon with badge** = this memory is linked to a card entry.

The badge is the same whether the memory is pending approval or already
approved. The badge means one thing: "this memory lives on a card." Pending
vs approved state is communicated by:
- Being on the Pending screen (context)
- The suggestion outline treatment on the memory row (visual)
- The draft status

Using different badges for pending vs approved would force the user to
remember which badge variant means what -- one badge, one meaning is
cleaner.

### Total visual states

| State | Icon shown |
|-------|------------|
| User memory | User icon |
| Companion memory | Companion icon |
| Your RP character, backend only | Character icon (plain) |
| Your RP character, on a card | Character icon + badge |
| Roleplay, backend only | Roleplay icon (plain) |
| Roleplay, on a card | Roleplay icon + badge |

Six visual states. The user learns four shapes and one concept ("badge
means it's on a card").

## 6. Placing and removing memories from cards

**Copy, not link.** When a memory is placed on a card (whether by Archivist
suggestion or by the user manually), the memory's content is copied into a
new card entry. The memory and the card entry are independent after that.

**Removing from a card:** deleting the card entry removes it from the card.
The original memory stays in the backend, untouched. The badge on the
memory's icon drops off (it's no longer on a card). The knowledge isn't
lost -- it just stops being displayed on the card.

**Why copy, not link:** the user thinks "I put this on my card" and "I took
it off my card" -- simple actions on the card. If they edit the card entry
later (rename the item, update a quantity), they don't expect the backend
memory to change. And if they remove it from the card, the answer is
obvious: card entry goes away, memory stays.

## 7. Pending screen: suggestion outline

When a memory has an Archivist-proposed card placement that hasn't been
acted on yet, the memory row gets an outline treatment (distinct from the
badge). Once the user approves or rejects the placement, the outline goes
away. If approved, the badge stays permanently so the user always knows
which memories are on cards when browsing.

---

## Design principles (from this conversation)

- Memories are memories. Roleplay facts, real-life facts, companion facts --
  they differ in scope, not in nature. The system treats them the same way;
  the UI helps the user see the difference at a glance.
- The Archivist proposes; the user decides. Nothing is ever written directly
  to cards without approval.
- The icon system is for fast scanning. Keep it learnable -- few shapes,
  one modifier concept.
- Be transparent about limitations. The warning text about smaller models
  is there because users deserve to know, not because we want to upsell
  bigger models.
