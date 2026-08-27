# Chat positioning and the keyboard

Approved behavior for how the conversation moves — and does not move — on the
chat screen. This is the whole contract. Nothing else in this file.

If the chat starts jumping to places the user did not put it, this file is the
reference. Read it before changing anything on the chat screen that touches
scrolling, the message box, or the keyboard.

## Words used here

**Composer** — the app's message box: the rounded bar containing the message
field and its icons. Part of the app.

**Keyboard** — the phone's software keyboard. Not part of the app. It slides up
from the bottom, underneath the composer, and pushes the composer up.

**Conversation** — the scrolling list of messages above the composer.

The composer always sits above the keyboard. That is not in question anywhere
in this file. What this file governs is the **conversation** above the composer.

## The behavior

### 1. The user's finger always wins

While the user is touching the screen — scrolling, or just holding still —
nothing happening in the app moves the conversation.

### 2. After the user sends a message

The keyboard closes. The conversation then follows the reply up the screen as
it is generated, and **stops** when it reaches the top of the AI's own message —
the edge with the profile image, which is directly beneath the user's last
bubble. It does not keep chasing the growing bottom of the reply.

### 3. Touching the screen during a reply stops the movement for good

If the user touches the screen while a reply is generating, the conversation
stops moving on its own — and stays stopped even after they lift their finger.
The movement does not resume for the rest of that reply.

### 4. Opening the keyboard does not move the conversation

When the user taps into the composer, the composer expands. That part is
correct as built and is not what this rule governs.

The conversation stays in exactly the same place **relative to the top of the
composer**. Whatever line was sitting just above the message box is still
sitting just above it once the keyboard is up.

### 5. The user can scroll freely with the keyboard open

### 6. Closing the keyboard does not move the conversation either

Whether the user closes it or a send closes it, the conversation stays in the
same place relative to the composer. As the composer drops back down, the same
lines come back down with it.

### 7. An open keyboard locks the conversation

While the keyboard is up, **nothing automatic moves the conversation.** A reply
arriving does not move it. A reply growing does not move it. The user's own
scrolling still works normally — the lock only stops the screen from moving by
itself.

**The single exception** is the keyboard closing because the user hit Send.
That close is the user asking for the reply, so the conversation is free to
follow it, per rule 2. The exception is used up by the very next keyboard
change: reopening the keyboard while the reply is still streaming locks the
conversation again immediately.

## Why this matters

The user is in control of what they are looking at. A chat that bounces to
places they did not choose takes that away, and it is worst exactly when they
are reading something carefully or composing a difficult message.

## Where it lives in the code

| Behavior | Where |
| --- | --- |
| Notes the position before a resize | `ChatActivity.captureTranscriptAnchor()` |
| Puts it back after the resize | `ChatActivity.restoreTranscriptAnchorAfterResize()` |
| Keyboard reports before it takes or gives back space | `ChatImeInsetLayout.onBottomInsetChanging` (in `ChatComposerLayout.kt`) |
| Whether the keyboard is currently up | `ChatImeInsetLayout.isKeyboardOpen` |
| The lock, and the Send exception | `ChatActivity.scroll()` and `imeClosingForSend` |
| The finger-wins flag | `ChatActivity.disableAutoScroll` |
| Where a growing reply stops | `StreamingBubbleScrollPolicy` |

## What breaks it

These are the specific mistakes that have undone this behavior before. Each one
looks reasonable in isolation.

**Connecting the position hold to the composer only.** The keyboard resizes the
chat area exactly as the composer does. If only the composer reports, rules 4
and 6 break and the keyboard is free to move the conversation.

**Shifting by the height difference instead of restoring a noted position.**
When the chat area grows, the list already corrects part of that move by
itself. Adding a full shift on top doubles it and pushes the newest message out
of sight. Restore an absolute position; it is correct either way.

**Restoring on a timer or from an inset animation callback.** The restore has to
run on the chat area's own resize, or it lands before the resize it is meant to
compensate for and does nothing.

**Letting an arriving message override the hold.** It looks like a courtesy to
the new message. It is rule 7 broken, and it is the one thing the user has been
most explicit about: they do not care that a reply is arriving.

**Turning on `stackFromEnd` to get bottom anchoring.** It would pin the bottom,
but it also stacks a short conversation against the composer instead of
starting it at the top. That is a visible change to every new chat and is not
approved.

## The test that holds it

`ChatActionSurfaceSourceContractTest`
→ `everyViewportResizePinsTranscriptWithoutFightingTheImeConstraint`

It asserts the mechanism above is present, and the build fails without it.

**If that test fails, the behavior in this file is what is right — not the
code.** Fix the code. Change the test only when this file has been changed
first, with approval.
