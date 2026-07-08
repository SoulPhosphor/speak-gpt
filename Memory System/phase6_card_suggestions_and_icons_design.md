# Phase 6: Card Suggestions, Memory Icons, and UX Design

*Owner decisions from July 7-8 2026 conversation. This document captures
the owner's actual phrasing and design intent for the building agent.*

---

## Not being built: the extraction request preview

An earlier planning idea proposed a read-only "preview" screen that
would show the complete final text sent to the Archivist AI — the
editable extraction prompt glued to the actual conversation transcript
being analyzed. This is NOT being built. The user has no say over how
those pieces get combined, and viewing their own instructions pasted
next to a conversation they already had is not actionable. The editable
extraction prompt in Advanced Settings is enough transparency. If a
future need surfaces, it can be added then.

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

## 4. The Pending screen approval flow

**One list, mixed contents.** Regular memory drafts and roleplay memory
drafts (including ones with proposed card placements) live in the same
Pending list, grouped by scope. Roleplay card suggestions are NOT a
separate section with a special multi-option decision — they are just
roleplay memories that also happen to carry a proposed card destination.

**Per-memory actions — three options on every draft:**

- **Accept** — approves the memory. For roleplay memories with a
  proposed card placement, Accept also commits the card entry to the
  proposed section (copy, not link — see section 6).
- **Delete** — discards the draft entirely.
- **Edit** — opens the memory editor pre-filled with what the Archivist
  proposed (scope, targets, type, tags, importance, text, title). For
  roleplay memories, the editor also shows the proposed card and
  section: the user can change the destination, choose a different card,
  or clear it entirely to keep the memory backend-only. Saving keeps it
  a draft with the user's corrections; the editor also has an Accept
  button.

**Two Accept All buttons at the top of the screen:**

- **Accept all memories** — bulk-approves the regular (non-roleplay)
  memory drafts.
- **Accept all roleplay** — bulk-approves the roleplay drafts,
  including their proposed card placements.

Two buttons instead of one because card placements are a bigger deal
than plain memory acceptance. Someone speeding through their real-life
memory queue shouldn't accidentally auto-place things onto roleplay
cards they haven't looked at. Splitting the button lets the user
mass-approve one kind of thing at a time.

Both Accept-all buttons show a count confirm ("Accept 12 memories?" /
"Accept 5 roleplay memories?").

## 5. Memory icon system

> **⚠️ REPLACED July 8 2026.** The original scheme in this section (a "User"
> icon covering real life + global + project, two "roleplay" icons, and
> corner-badge variants for card-linked memories) is **dead** — do not build
> it. The old text has been removed to avoid confusing future agents. The
> authoritative icon system now lives in `owner_approved_rules.md` (Addendum,
> July 8 2026, items 5–6). Summary of what shipped:

Every memory row shows a **leading** icon (left of the row), chosen by scope
in `MemoryBrowserActivity.iconForScope(scope, onCard)`:

| Memory | Icon (Material Symbol) |
|--------|------------------------|
| Real life | `person` |
| Global | `borg` (its own icon — global is NOT real life) |
| Companion | `partner_exchange` (two people + heart) |
| Project | `draft` (folded-corner page) |
| User roleplay character (`rp_character`) | `theater_comedy` (comedy mask) |
| World / Campaign (and any unknown) | `public` (globe) |
| **Any memory placed on a card** | `book_5` (overrides the scope icon) |

- There are **no corner-badge variants** — a card-linked memory swaps to the
  whole `book_5` icon.
- **`book_5` / "on a card" is RESERVED and not reachable yet.** No
  memory↔card link exists in the data (`card_entries` has no source-memory
  column; `memories` has no on-card flag), so `isOnCard()` returns false.
  Building that link + the placement flow is a Phase-6 task (see §6, which is
  only partially built); when done, `isOnCard()` is the single hook.
- The `rp_character` comedy mask is a placeholder for a future dedicated
  icon; its code branch is kept separate from world/campaign so the split is
  cheap.

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

## 7. Pending row: suggestion outline

When a roleplay memory has an Archivist-proposed card placement that
hasn't been acted on yet, the memory row (both in the Pending screen and
anywhere the draft surfaces) gets an outline treatment around it —
distinct from the badge on the icon. The outline is "this is waiting on
your decision about a card placement." Once the user accepts or deletes
the memory, the outline goes away. If accepted, the badge on the icon
stays permanently so the user always knows the memory lives on a card
when browsing later.

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
