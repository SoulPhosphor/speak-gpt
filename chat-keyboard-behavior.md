# Chat positioning and the keyboard

Approved behavior for how the conversation moves and does not move on the
chat screen. This is the whole contract. Nothing else in this file.

If the chat starts jumping to places the user did not put it, this file is the
reference. Read it before changing anything on the chat screen that touches
scrolling, the message box, or the keyboard.

## Words used here

**Composer** - the app's message box: the rounded bar containing the message
field and its icons. Part of the app.

**Keyboard** - the phone's software keyboard. Not part of the app. It slides up
from the bottom, underneath the composer, and pushes the composer up.

**Conversation** - the scrolling list of messages above the composer.

**Automatic follow** - app-driven movement that follows a newly generated AI
reply upward. This is allowed only after Send, and only until another rule
turns it off.

**Position hold** - keeping the same visible conversation content in the same
place relative to the top of the composer while the composer or keyboard moves.

The composer always sits above the keyboard. That is not in question anywhere
in this file. What this file governs is the **conversation** above the composer.

## Precedence: which rule wins

When more than one rule could apply, use this order:

1. A user touch or manual scroll wins over every automatic movement.
2. Opening or manually closing the keyboard holds the current conversation
   position relative to the composer.
3. Pressing Send is the one intentional exception to that hold. It starts a new
   automatic-follow period for the newly generated reply.
4. Opening the keyboard again, or touching/scrolling the conversation during
   that reply, ends automatic follow immediately.

Do not infer behavior from "a message arrived" or "the keyboard changed." The
cause matters. **Send is special; ordinary keyboard movement is not.**

## The behavior

### 1. The user's finger always wins

While the user is touching the conversation, whether scrolling or just holding
still, nothing happening in the app moves the conversation.

If the user touches the conversation while an AI reply is generating,
automatic follow is disabled for the rest of that reply, even after the user
lifts their finger.

### 2. After the user presses Send

Pressing Send ends the current position hold. The keyboard closes, and the new
turn is allowed to use automatic follow.

As the AI reply is generated, the conversation follows it upward only as far as
needed to bring the top of the AI response to its stopping point: the top edge
of the AI message/profile-image area, directly beneath the user's last message
bubble. Once that point is reached, the conversation stops moving. It does not
keep chasing the growing bottom of a long reply.

This rule applies because the user pressed **Send**, not merely because the
keyboard happened to close or because a new message appeared.

### 3. Touching the screen during a reply stops movement for that reply

If the user touches or scrolls the conversation while a reply is generating,
the conversation stops moving on its own and stays stopped after the finger is
lifted. Automatic follow does not resume for the rest of that reply.

### 4. Opening the keyboard holds the conversation against the composer

When the user taps into the composer, the composer expands. That part is
correct as built and is not what this rule governs.

The conversation stays in exactly the same place **relative to the top of the
composer**. Whatever line was sitting just above the message box remains just
above it once the keyboard is up.

If an AI reply is already streaming when the keyboard opens, opening the
keyboard immediately ends automatic follow and holds the current position.
The reply may continue generating, but it must not drag the conversation.

Opening the keyboard mid-reply stops automatic follow for **the rest of that
reply**. Closing the keyboard again does not resume it. The user opened the
keyboard because something caught their attention, so that content stays in
front of them until they send the next message.

### 5. The user can scroll freely with the keyboard open

The keyboard being open must never disable or interfere with manual scrolling.
The user can move anywhere in the conversation and the app does not pull them
back.

### 6. Manually closing the keyboard holds the conversation too

If the user dismisses the keyboard without pressing Send, the conversation
stays in the same place relative to the composer. As the composer drops back
down, the same visible content comes back down with it.

**This rule does not apply to a keyboard close caused by Send.** A Send-triggered
close follows rule 2 instead: the existing hold is released so the newly
requested reply can begin automatic follow.

### 7. An open keyboard locks out automatic movement

While the keyboard is up, **nothing automatic moves the conversation.** A reply
arriving does not move it. A reply growing does not move it. The user's own
scrolling still works normally. The lock only stops app-driven movement.

If the user presses Send while the keyboard is open, the Send action releases
this lock for the new turn as described in rule 2. If the keyboard is opened
again while that reply is still streaming, automatic follow stops immediately
and the position hold takes over again.

## Important event distinctions

These cases are intentionally different and must not be collapsed into one
"keyboard changed" or "new message arrived" rule:

| Event | Required result |
| --- | --- |
| User opens keyboard | Hold current position relative to composer |
| User manually closes keyboard | Hold current position relative to composer |
| AI reply arrives/grows while keyboard is open | Do not move conversation |
| User presses Send | Release hold and start automatic follow for the new reply |
| User touches/scrolls during generation | Stop automatic follow for the rest of that reply |
| User opens keyboard during generation | Stop automatic follow for the rest of that reply, and hold current position; closing the keyboard again does not resume it |

## Why this matters

The user is in control of what they are looking at. A chat that bounces to
places they did not choose takes that away, especially while they are reading
carefully or composing a message.

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

**Treating every keyboard close the same.** A manual keyboard close preserves
the hold. A Send-triggered keyboard close releases the hold so the new reply
can follow upward. Losing that distinction breaks either rule 2 or rule 6.

**Treating every arriving message as permission to move.** A message arriving
while the keyboard is open does not release the hold. Only Send starts a new
automatic-follow period.

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
the new message, but it breaks the keyboard-open behavior. An incoming or
growing reply must not move the conversation merely because it exists.

**Turning on `stackFromEnd` to get bottom anchoring.** It would pin the bottom,
but it also stacks a short conversation against the composer instead of
starting it at the top. That is a visible change to every new chat and is not
approved.

## The test that holds it

`ChatActionSurfaceSourceContractTest`
-> `everyViewportResizePinsTranscriptWithoutFightingTheImeConstraint`

It asserts that the mechanism above is present, and the build fails without it.
It is a source-contract test, not a full device-level simulation of keyboard
animation and touch interaction. Passing it does not override this behavior
contract.

**If that test fails, the behavior in this file is what is right, not the
code.** Fix the code. Change the test only when this file has been changed
first, with approval.
