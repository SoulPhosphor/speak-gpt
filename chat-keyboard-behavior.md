# Chat positioning and the keyboard

Approved behavior for how the conversation moves and does not move on the
chat screen. The behavior sections are the contract. The engineering notes
below explain how to preserve and repair it, not new movement rules.

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

#### 4a. At the end of a chat, the last message is sticky (owner ruling, Oct 30 2026)

When the user is at the very end of the chat (the bottom of the last message
is sitting right above the composer), the last message sticks to the composer.
It rises with the composer and the keyboard so the composer never covers it,
and it comes back down with them when the keyboard closes or the composer
shrinks.

This is the same hold as the rest of rule 4, measured by the bottom of the
last message. A last reply taller than the screen keeps its own end against
the composer. If the last message is already partly cut off, the user is not
at the end, and the ordinary hold applies instead.

#### 4b. A short chat moves only when it has to (owner ruling, Oct 30 2026)

If the whole conversation fits on the screen, opening the keyboard does not
move it as long as the last message is still above the composer.

Once the composer would cover the last message, the conversation moves up
only as far as needed to keep the bottom of the last message just above the
composer. It does not jump to some other place. When the keyboard closes or
the composer shrinks, the conversation comes back down with it, all the way
back to where it started if everything fits again.

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

### 8. A finished AI turn never opens the keyboard (owner ruling, Aug 29 2026)

When an AI turn ends, the app must not bring the keyboard up on its own. This
is required behavior, not a preference, and it holds in every mode with no
exception, Desktop mode included.

"Ends" covers every finish: a streamed reply, a non-streamed reply, an image
result, and every failure of those, plus the hidden auto-title request that can
run right after a reply. In all of these the composer is left exactly as it was
and the keyboard stays closed. The keyboard comes up only when the user taps the
composer themselves, which is rule 4.

A finished turn must not focus the message box at all. Focusing it is what runs
the composer's focus listener and raises the keyboard, so no completion path
requests focus on the composer, in any mode.

## Important event distinctions

These cases are intentionally different and must not be collapsed into one
"keyboard changed" or "new message arrived" rule:

| Event | Required result |
| --- | --- |
| User opens keyboard | Hold current position relative to composer |
| User manually closes keyboard | Hold current position relative to composer |
| User opens or closes keyboard at the very end of the chat | Last message stays right above the composer and moves with it |
| User opens keyboard on a short chat that still fits | Nothing moves |
| User opens keyboard on a short chat that no longer fits | Moves up only enough to keep the last message above the composer; comes back down when the keyboard closes |
| Keyboard or composer changes size while the user's finger is on the conversation | Nothing moves the conversation (rule 1 wins over the hold) |
| AI reply arrives/grows while keyboard is open | Do not move conversation |
| User presses Send | Release hold and start automatic follow for the new reply |
| User touches/scrolls during generation | Stop automatic follow for the rest of that reply |
| User opens keyboard during generation | Stop automatic follow for the rest of that reply, and hold current position; closing the keyboard again does not resume it |
| AI turn ends (reply or image finishes or fails, including the auto-title request after it) | Keyboard stays down; do not focus the composer to open it, in any mode |

## Why this matters

The user is in control of what they are looking at. A chat that bounces to
places they did not choose takes that away, especially while they are reading
carefully or composing a message.

## Where it lives in the code

| Behavior | Where |
| --- | --- |
| Notes the position before a resize | `ChatActivity.captureTranscriptAnchor()` / `ChatTranscriptRecyclerView.captureResizeAnchor()` |
| Puts it back during the new-size layout | `ChatTranscriptRecyclerView.onSizeChanged()` |
| At the end of a chat, keeps the last message's bottom on the composer | The same hold in `ChatTranscriptRecyclerView.onSizeChanged()`; there is no separate end-of-chat path |
| Keyboard reports before it takes or gives back space | `ChatImeInsetLayout.onBottomInsetChanging` (in `ChatComposerLayout.kt`) |
| Whether the keyboard is currently up | `ChatImeInsetLayout.isKeyboardOpen` |
| The lock, and the Send exception | `ChatActivity.scroll()` and `imeClosingForSend` |
| The finger-wins flag | `ChatActivity.disableAutoScroll` |
| Where a growing reply stops | `StreamingBubbleScrollPolicy` |
| A finished turn stops after hiding the progress ring, never focuses the composer | `ChatActivity` completion paths (each `setGenerationProgressVisible(false)` end) |

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

**Posting the restore until after the resize callback.** Composer promotion and
the next keyboard-inset frame can both arrive before that runnable. They can
overwrite the saved anchor or apply restores out of order. The transcript must
arm the absolute anchor during its new-size layout, before laying out the new
viewport height.

**Letting an arriving message override the hold.** It looks like a courtesy to
the new message, but it breaks the keyboard-open behavior. An incoming or
growing reply must not move the conversation merely because it exists.

**A separate end-of-chat path.** The end of a chat once had its own
placement and an extra scroll after the layout. The ordinary hold worked on
the device while that end-only path did not. The end of a chat now uses the
same hold as everywhere else.

**Trusting an old note over a fresh one.** A position noted before the resize
can go stale if the conversation changes before the resize happens. While the
rows still sit at the old height, the resize measures them again and that
fresh measurement wins.

**Turning on `stackFromEnd` to get bottom anchoring.** It would pin the bottom,
but it also stacks a short conversation against the composer instead of
starting it at the top. That is a visible change to every new chat and is not
approved.

**Focusing the composer when a turn finishes.** Calling `requestFocus()` on the
message box at the end of a turn runs the composer's focus listener, which shows
the keyboard. No completion path may focus the composer, in any mode. Putting a
`messageInput?.requestFocus()` back into a completion path is exactly what makes
the keyboard pop up on its own again (rule 8).

## Engineering repair guide: calculations and failure diagnosis

This is a repair map for the existing user-approved rules, **not** permission
to change them. Verify code against the current branch before repairing; if
method names change, preserve the behavior and its invariants, not old names.

### 1. The actual resize math (pixels, not dp)

The conversation is a `ChatTranscriptRecyclerView` constrained above the
composer host in `activity_chat.xml`. Its viewport height changes when the
IME, composer mode, or multiline draft changes. Capture the **last visible
row** in its *old* layout, before the viewport changes:

```text
p         = adapter position of last visible row
H_old     = old transcript viewport height
T_old     = top of row p in transcript-local coordinates
D         = T_old - H_old              (distance from bottom/composer)
H_new     = new viewport height
T_target  = H_new + D
          = T_old + (H_new - H_old)

layoutManager.scrollToPositionWithOffset(p, T_target)
```

The equality on the fourth line describes geometry; **do not implement it
as an extra scrollBy(H_new - H_old)**. RecyclerView may have already
moved children while measuring, so a relative scroll doubles the correction.
An **absolute row + offset** is applied by
`ChatTranscriptRecyclerView.onSizeChanged()` before the new-height child
layout. Do not `post` the restore, run it after the layout, or add an
IME animation scroll. The next keyboard frame can overtake either.

Example: row top 300px in a 1000px viewport gives `D = -700px`.
An 800px viewport needs top 100px. On return to 1000px it needs top
300px. The *same row* must be measured, not a newly chosen visible row.

At the real end of a long conversation, the last row's bottom is normally
`H - paddingBottom`, so this same hold moves that bottom with the composer
by precisely the viewport-height change, even when the final reply is
taller than the screen. Do not invent a separate end-only correction or
use `stackFromEnd`: short conversations must start at the top, stay there
while they fit, shift upward only by the overflow when necessary, and
return to top alignment as the keyboard closes. `scrollToTranscriptEnd()`
is an intentional initial chat-open navigation helper, **not** a general
keyboard-resize repair.

**Freshness is critical.** Before the new layout, if
`layoutManager.height == oldh`, the child rows still describe the old
height: `onSizeChanged()` makes a fresh capture and replaces a potentially
stale pending capture. If layout/measurement has *already* put children at
another height, keep the earlier valid pre-resize capture. Recapturing from
wrong-height children poisons the anchor.
`aFreshMeasurementReplacesAnAnchorThatWentStaleBeforeTheResize` covers this.

**Check physical screen coordinates, too:**

```text
rowFromComposerOnScreen =
  transcriptTopOnScreen + rowTopLocal - composerTopOnScreen

gapToComposer =
  composerTopOnScreen - (transcriptTopOnScreen + transcriptHeight)
```

When the viewport bottom meets the composer, `gapToComposer = 0` and
`rowTopLocal - transcriptHeight` equals the screen metric. If the local
hold is perfect but the screen metric jumps, inspect the real constraints,
insets, translations, and view geometry instead of changing anchor math.
`ChatTranscriptRecyclerView.screenDistanceFromComposer()` and
`screenGeometry()` report that distinction.

### 2. One inset owner; two kinds of keyboard close

`ChatImeInsetLayout` in `ChatComposerLayout.kt` is the single owner:

```text
bottomPadding = baseBottom + max(navigationBarInset, imeBottomInset)
keyboardOpen  = imeBottomInset > 0
retreating    = imeBottomInset < previousImeBottomInset
```

`onBottomInsetChanging` invokes `ChatActivity.captureTranscriptAnchor()`
**before** applying the new padding. Composer
promotion/expansion/collapse calls `beforeResize` as well. Simply typing
another line can resize the composer without either callback, so
`ChatTranscriptRecyclerView.onSizeChanged()` is also a capture backstop.
Do not install another IME owner, duplicate navigation padding, or add
a second keyboard-height offset.

A **manual keyboard close** holds the conversation. A **Send close** does
not: the Send path releases the anchor, resets reply-follow permission,
sets `imeClosingForSend`, and calls `dismissImeForSend()`. During the
retreating IME frames `releaseResizeAnchorForNextLayout()` prevents even
the final zero-inset layout from reviving the old hold. The Send exception
ends when the keyboard is actually gone, **not on the first closing
frame**. Reopening mid-reply immediately cancels following and establishes
a new hold. A keyboard opening during request preparation cancels
following too; the app must not wait for the first streamed token.

### 3. Touch priority and queued scrolls

A user's finger takes priority over automatic position holds. On
`dispatchTouchEvent(ACTION_DOWN)`, the transcript releases any pending
anchor and notifies `ChatActivity` **before a message child can consume
the touch**. While a finger is on the transcript, preserve the first
visible row's position from the **top** of the viewport, not the bottom:

```text
topOffset = firstVisibleRow.top - paddingTop
targetTop = paddingTop + topOffset
correctionAfterLayout = targetTop - actualRow.top
```

`onLayout()` uses `offsetChildrenVertical(correctionAfterLayout)` to undo
LinearLayoutManager's automatic bottom-gap fill. A `scrollBy` may clamp
and cannot reliably do this. `retainPositionAfterTouch` protects the
stopped location from a later adapter relayout, until a new Send or an
intentional jump releases it.

`ChatAutoScrollGate` maintains a permission revision. A posted scroll
must recheck that revision, `disableAutoScroll`, and keyboard-hold state
**at execution time**, not merely when queued. Touching or opening
the keyboard during an AI turn disables following for the rest of that
turn; manual keyboard close and finger-up do not restart it.
Only a new Send begins another follow period.

The allowed streaming follow uses
`StreamingBubbleScrollPolicy.distance()`:

```text
bottomClipped = max(itemBottom - viewportBottom, 0)
spaceAbove    = max(itemTop - viewportTop, 0)
scrollPixels  = min(bottomClipped, spaceAbove)
```

Once the response top reaches the viewport top, `spaceAbove = 0`:
stop following even if the long response keeps growing.
`ChatActivity.scroll()` and `scrollX()` must respect the gate.

### 4. Known regression that the anchor math could not catch

A keyboard-opening jump can happen **before any viewport resize**.
In the Oct 31 fix (PR #164), moving the same focused composer `EditText`
between compact and editing parents temporarily focused a message row.
RecyclerView then invoked an ordinary focus-driven `smoothScrollBy`
(about 1750px in the recorded incident). The resize hold subsequently
preserved the **already wrong** location. Therefore a trace ending with
`ROW HELD` does not necessarily prove that the user's place was held
through the whole tap-to-type gesture.

`ChatTranscriptLayoutManager.onRequestChildFocus()` now suppresses
automatic bring-into-view scrolling for ordinary message-row focus,
while deliberately allowing it for an `EditText` inside a row (the
image-summary editor). `ChatComposerLayout` also defers editor
reparenting until after the focus tap and preserves the editor/caret.
Do not undo these protections or treat focus scrolling as an IME
offset problem. Investigate the **first** unintended scroll, including
`smoothScrollBy` *before* `IME OPENING` or anchor capture.

### 5. Diagnose the first wrong event, not the final log title

When investigating, turn on **Turn On Keyboard Chat Interaction
Debugging** under **Alerts, Errors & Logs**. The geometry-only trace
is in `app/src/main/java/org/teslasoft/assistant/ui/chat/ResizeTrace.kt`.
Read the full chronological record:

1. Find `composer tapped`, first `IME OPENING` or `IME CLOSING`,
   `onSizeChanged`, adapter updates, and the earliest `scrollBy`,
   `smoothScrollBy`, or `scrollToPosition`. The call site identifies
   whether focus, explicit navigation, reply follow, or resize moved first.
2. If movement precedes the first capture, inspect focus handoff,
   message-row focus, unexpected initial chat navigation, and queued
   auto-follow before touching the resize equation.
3. If IME changes, verify the inset owner changes bottom padding once,
   captures before doing so, and the chat viewport actually resizes.
   `KEYBOARD OPEN / NO CHAT RESIZE` is **not a passing keyboard hold**.
4. Compare captured `p` and `D` with the requested
   `scrollToPositionWithOffset(p, H_new + D)`. Check
   `childrenAtOldHeight` and any stale capture.
5. Compare local drift with the screen row-to-composer measurement.
   Inspect `touching`, `retainAfterTouch`, `disableAutoScroll`,
   `imeClosingForSend`, and queued movement before blaming geometry.

A `COMPOSER/OTHER (no keyboard motion recorded) / ROW HELD` log is
**not a keyboard-opening test**. `ROW HELD` means the observed anchor
met the logger's drift criterion after its baseline; a prior jump
may already have happened. `DRIFT` and `SPIN-OUT` indicate failures
worth tracing back to their *first* cause. Logging tolerance is for
classification, not approval to move the user's content.

### 6. Regression checks required before declaring repair

Keep all existing tests and run the targeted
`ChatTranscriptResizeAnchorTest`,
`ChatKeyboardPositionIntegrationTest` (Android 28 and 36),
`ChatAutoScrollGateTest`, `StreamingBubbleScrollPolicyTest`,
`ChatComposerLayoutTest`, and `ChatActionSurfaceSourceContractTest`.
The source-contract test checks mechanisms and string patterns;
it cannot simulate the real keyboard or focus transitions.

On a **real device**, repeat keyboard opening and manual closing
several times in a long chat, at its end, mid-chat, after a large last
reply, and on both kinds of short chat. Check one line adjacent to the
composer before, during, and after animation. Also test typing enough
to grow the composer, draft expansion/collapse, touch and scroll while
IME frames run, finger-up before all frames finish, a reply streaming
with keyboard open, keyboard reopening during generation, and Send
while the keyboard is open. Test ordinary compact-composer tap focus
and a message-row EditText separately: the first must never
auto-scroll; the second still needs its intended focus behavior.
Test turn completion without reopening the keyboard.

If the focus handoff changes again, add targeted automated coverage
for unauthorized focus-driven scrolling; resize-only tests cannot
fully protect it. Passing CI or one successful open/close cycle
does not establish that an intermittent on-device bug is resolved.
Never alter a behavior test merely to make a new implementation pass
unless the owner explicitly changes the behavior contract.

---

## The test that holds it

`ChatActionSurfaceSourceContractTest`
-> `everyViewportResizePinsTranscriptWithoutFightingTheImeConstraint`

Rules 4a and 4b are checked by `ChatTranscriptResizeAnchorTest`. It lays the
chat out at the old and new heights and checks where the messages land: at the
end of a chat, during a keyboard that arrives in several steps, with a last
reply taller than the screen, in the middle of a chat, with short chats that
do and do not still fit, with a position note that went stale before the
resize, and with a finger on the chat while it resizes. It cannot simulate a real keyboard
animation or a real finger moving on the screen.

Rule 8 has its own guard in the same class:
`ChatActionSurfaceSourceContractTest` -> `aFinishedTurnDoesNotOpenTheKeyboard`.
It fails the build if any completion path focuses the composer, so a keyboard
that pops up after a turn cannot be mistaken for intended behavior.

It asserts that the mechanism above is present, and the build fails without it.
It is a source-contract test, not a full device-level simulation of keyboard
animation and touch interaction. Passing it does not override this behavior
contract.

**If that test fails, the behavior in this file is what is right, not the
code.** Fix the code. Change the test only when this file has been changed
first, with approval.
