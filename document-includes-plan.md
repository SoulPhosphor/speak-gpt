# Document & Image Includes — Build Plan

Status: **Step 1 repaired and verified.** The first implementation reached
`main` with working pieces and material defects; the repair now passes its
tests and Android Checks. Step 2 code exists but remains outside the Step 1
acceptance boundary. Step 3 (images) has not been built.

This document records the current implementation baseline and build boundary.
It does not turn an old discussion, uncertainty, or existing code into user
approval. Current instructions in chat override stale passages here. If a
product choice is not settled, stop and ask before building it.

Decisions explicitly reconfirmed during the Step 1 repair:

- The paperclip opens a vertical icon-and-word menu in this order:
  **Camera**, **Image**, **Document**.
- Upload failures use a pop-up dialog with the file name and a descriptive
  error. Successful attachment size/cost notices remain inline.
- Removing a pending document before Send detaches it completely. It is never
  sent to the model, written into chat history, or converted to an artifact.
- The live Includes strip collapses at **4 or more** documents.
- Full-document history uses the under-name metadata record. A condensed
  document uses the bookmark-with-plus; a fully removed sent document uses
  the empty bookmark. Both bookmarks reopen editable text with Cancel/Save.
- Model-facing order is the user's text, then documents in stable attachment
  order, with images last in Step 3. Document boundaries use a compact
  semantic wrapper rather than decorative punctuation.
- Visible but provisional Condense controls do not block Step 1 completion.

Other details below describe the current baseline being repaired. They must
not be cited as independently user-approved merely because they appear here.

## Feature intent

The user can attach documents (and later images) to a chat so the AI can read
them and discuss them over multiple rounds. Everything currently being sent is
always visible; the user always knows the weight of what they're sending and
can shrink or remove it at any time. Nothing is ever silently truncated,
silently dropped, or silently kept.

## The Includes ladder (core concept)

Every attached item exists in exactly one of three forms, heaviest to lightest:

1. **Full** — the real thing (extracted document text, or the actual image).
2. **Condensed** (documents) / **Reduced** (images) — an AI-written text
   version, always shown to the user before it takes effect, and editable by
   the user at any time afterward. The two terms remain distinct:
   condensing makes the same kind of thing smaller; reducing an
   image *removes* the visual data entirely and keeps only words. Never merge
   these terms.
3. **Artifact** — a one-line bookmark ("User sent a photo of a purple
   amethyst cluster"), so the AI never faces replies to something that no
   longer exists. **Removing an item that was already sent converts it to an
   artifact. Removing a still-pending item detaches it without a trace.**
   The artifact line is AI-written at removal time (bounded, ~12 words max,
   via the chat's own endpoint/model), falls back to a filename-based line if
   the AI is unreachable, and is user-editable afterward like everything else.

Moving DOWN the ladder is always user-initiated. Nothing automatic ever
changes an include's form.

## Scope

- **Step 1 — documents:** attach `.txt`, `.md`, `.csv`, `.docx`. The Includes
  strip, pending detach, sent-item Remove→artifact, the artifact bookmark
  popup, the history accordion, token estimates, size guards.
- **Step 2 — Condense** for documents, with the Edit dialog.
- **Step 3 — images** join the same system ("Reduce to Text Only"); the old
  broken image path (hard-coded `gpt-4o`, bypasses history/memory) is
  **deleted**, not left behind.

**Current non-goals:** no PDF (deferred), no legacy `.doc` (only
`.docx`), no image generation changes, no writing edited documents back to
disk (`.docx` is read-only — the AI's editing help arrives as chat text; the
extraction is words-only, formatting is not preserved), no automatic
summarization of anything.

## Current wording inventory

All strings live in `res/values/strings.xml` only, per house rule.

| Where | Words |
|---|---|
| Strip label, full form | **Includes** |
| Strip label, condensed form | **Includes condensed** |
| Menu item | **Remove** |
| Menu item (documents) | **Condense** |
| Menu item (images) | **Reduce to Text Only** |
| Menu item (condensed/reduced state) | **Edit** |
| Edit dialog buttons | **Cancel**, then **Save** — right-aligned, in that order |
| Weight display | **~N tokens** (tilde always shown — it is an estimate) |
| Large-file note | *"Large file — adds about ~30,000 tokens to every message while included."* (N is the item's real estimate) |
| Too-big note | *"This file is too large to send in full. The beginning was included, up to about ~30,000 tokens."* |
| Oversized CSV note | *"Large spreadsheet — sent the column names and first 500 rows of 47,000."* (real numbers substituted) |
| History box label | **Includes** |
| Artifact line default shape | "User sent …" — AI-written, ≤ ~12 words |
| Collapse line (4+ items) | **Includes N Documents** — "Documents" capitalised per the app's Title Case rule, followed by a **downward-facing chevron** |

### Upload failure dialogs

The dialog title is the selected file name. The body uses the approved
descriptor below, and the dialog has a Close action.

| Detectable condition | Dialog body |
|---|---|
| Unsupported extension or MIME type | **This file type is not supported.** |
| Read permission rejected | **File is unable to be read. Permission has expired or been revoked, and the file is no longer available.** |
| Content provider is unavailable | **File is unable to be read. The source app is not responding.** |
| Live provider reports file not found | **File is unable to be read. File is no longer available. It may have been moved or deleted.** |
| Read fails after the file opened | **File could not be read completely. The storage or connection may have been interrupted.** |
| Encrypted OOXML package detected | **File is unable to be read. Content is password protected and unreadable. Please try again with a non-protected file.** |
| Content does not match the declared file type | **File is unable to be read. Content does not match file type.** |
| A DOCX document part is present but unreadable | **File is unable to be read. File is corrupted.** |
| File contains no data | **File contains no data.** |
| No more specific condition is proven | **File could not be attached due to an unknown error.** |

## UI specification, surface by surface, with the styles each uses

Style authority: `ui-style-guide.md`. Every new shared style or layout this
feature adds gets documented THERE (not in CLAUDE.md), with rollout notes.
No toasts. Size and cost notices are persistent inline text. A failed upload
has no attachment row to hold an inline notice, so it uses a readable modal
dialog containing the approved error descriptor.

### 1. The Includes strip (above the message box)

- Sits directly **above the input bar** (`keyboard_input`) at the bottom of
  the chat screen, full-width, on the same surface family as the input bar so
  it reads as part of the composition area, not a floating element.
- One row per included item, visible **at all times** while anything heavier
  than an artifact is included (full/condensed/reduced items are
  the data drain, so they stay plainly shown; only artifacts retire to the
  bookmark popup).
- Row anatomy: **Includes** label → document/picture icon → name →
  **~N tokens** → three-dots menu.
  - The icon uses the document glyph for document formats and the picture
    glyph for images (Step 3), tinted `?attr/colorPrimary`.
  - Text styling follows the row vocabulary: name in the
    `Widget.App.Row.Title` role, token count in the subtitle role
    (`@color/text_subtitle`, 13sp — the `Widget.App.Row.Subtitle` /
    `Widget.App.Field.Hint` size/color family).
  - The strip rows are a NEW row shape (not one of the five chevron-row
    shapes — no chevron, trailing menu instead). If a shared style is minted
    for it, it goes into `ui-style-guide.md` as its own named entry; do not
    silently extend the five-phrase chevron-row vocabulary.
- **Menus by state** (anchored popup attached to the three-dots, dismissed by
  tapping outside — the app's anchored-popup pattern, never a centered picker
  dialog):
  - Full document: **Remove**, **Condense**.
  - Condensed document / reduced image: **Remove**, **Edit**.
  - Full image (Step 3): **Remove**, **Reduce to Text Only**.
- **Per-row persistent notes** (never toasts): the large-file note appears
  directly under a row whose estimate crosses the warning threshold; the
  too-big and CSV notes likewise. Styled in the hint family
  (13sp, `@color/text_subtitle`).
- **Collapse at 4+:** with four or more rows the strip becomes a single line
  reading **Includes N Documents** with a **downward-facing chevron at the
  end** (this supersedes the earlier upward-facing note; the chevron glyph
  points down even though the list
  opens upward). Tapping expands the full list **upward as an overlay
  covering the chat** (the conversation must not be shoved around),
  scrollable, collapsed again the same way or by tapping outside. With three
  or fewer rows the strip shows them all, no collapse.

### 2. The Edit dialog (condensed/reduced text, and artifact lines)

- The current baseline uses a pop-up rather than a full-screen editor.
- Built on `App.MaterialAlertDialog` (`R.style.App_MaterialAlertDialog`) —
  the one standard dialog theme; centered title comes free from the theme.
- Body: a multi-line editable text box skinned with `Widget.App.Field.Box`
  (`bg_field_box`), pre-filled with the current condensed/reduced/artifact
  text.
- Buttons: **Cancel**, then **Save**, right-aligned in that order.
  Button LOOK comes from the AppButton dialog-action
  family (`AppButton.Destructive.DialogAction` look for Cancel,
  `AppButton.Primary.DialogAction` look for Save) so they retheme with every
  other dialog button — but the ARRANGEMENT (right-aligned pair) is this
  dialog's own, so it needs its own shared layout file in the
  `dialog_two_actions.xml` family (e.g. an end-aligned variant). That layout
  + any style it needs gets documented in `ui-style-guide.md` when added.
  Do NOT reuse `dialog_two_actions.xml` as-is (its chain is
  primary-start/destructive-end, centered — a different shape for a
  different dialog).
- Save commits the edited text as the item's active condensed/reduced/
  artifact text; Cancel changes nothing. Editable again at any time.

### 3. Condense / Reduce flow (Step 2 for docs, Step 3 for images)

- Menu action → one request to the chat's configured endpoint/model asking
  for a compact self-reference version (internal prompt, bounded output).
- The result is ALWAYS shown to the user in the Edit dialog before it takes
  effect — Save applies it, Cancel discards it and the full form stays.
  Nothing replaces the full form without the user seeing the words.
- After applying: row label flips to **Includes condensed** (or the reduced
  image equivalent), token count re-estimated, three-dots now carries
  Remove/Edit.
- The full original is gone from what gets SENT once condensed (that is the
  point), but the original file is untouched on the user's device.

### 4. Remove

- Before the message is sent, Remove detaches the pending item completely.
  Nothing from that file is sent, retained in chat history, or converted to
  an artifact.
- After the item has been sent, Remove asks the AI for the ≤ ~12-word
  bookmark line, swaps the item to artifact form, and the row leaves the
  strip.
- If the line can't be fetched (offline, endpoint error): filename-based
  fallback line, immediately, never a blocked removal, never an error dialog
  for this — the fallback IS the success path. The line is editable later
  either way.
- Artifact access point: the empty Material Symbols `bookmark` glyph, tinted
  `?attr/colorPrimary`, shown after the user name. One item opens its
  Cancel/Save Edit dialog directly. If several removed items share a message,
  an anchored file picker appears first; tapping outside closes it.

### 5. The history record (inside the chat transcript)

- A full document gets a small **Includes** box directly under the user label
  in that message's bubble area. Tap = accordion opens listing each full item:
  small type icon + name + explicit document format + ~N tokens. Tap again
  closes.
- Once condensed, that document leaves the full-document box and uses the
  bookmark-with-plus Material Symbols `bookmark_add` glyph after the user
  name. Tapping opens the condensed text in the Cancel/Save Edit dialog. If
  several condensed items share a message, an anchored file picker appears
  first.
- Once fully removed, the item uses the empty artifact bookmark described
  above. Mixed full, condensed, and removed items can therefore show the
  full-document box and either or both bookmark markers without conflating
  their states.
- Renders in `ChatAdapter` rows (RecyclerView, recycled views — the accordion
  open/closed state must not bleed between recycled rows).

## Behavior specification

### Extraction (on-device, no new libraries)

- `.txt` / `.md` / `.csv`: read directly. UTF-8 assumed; other encodings
  degrade gracefully (charset detection best-effort, never mojibake dumped
  silently — if the text comes out garbled-looking/binary, refuse with the
  approved upload-error dialog).
- `.docx`: it is a zip of XML — extracted with the platform's own zip + XML
  parsing. Words only; formatting, images, tracked changes are not carried.
  No third-party document library is added for this.
- **Garbage guard:** a file that is not genuinely text (renamed binary) is
  refused with the approved upload-error dialog, never injected.
- **Oversized CSV rule:** header row + first 500 rows + the defined
  total-count line. A truncated CSV without the header/count would mislead
  the AI into analyzing a fragment as the whole — this rule exists so it
  can't.

### Weight display and limits

- Everything displays **~N tokens** (one unit everywhere). Documents use a
  fast, model-independent estimate that treats non-Latin text more
  conservatively than ASCII. The tilde is required because tokenization
  differs by model. Images (Step 3) estimate from pixel dimensions.
- Thresholds: under **~10,000 tokens** send quietly; **~10,000–30,000**
  send with the persistent large-file note; above **~30,000** the item is cut
  at the cap with the too-big note stating so plainly. No silent truncation,
  ever.

### Sending mechanics (results the user cares about; constraints that bind)

- Document text travels inside the user message it was attached to, at that
  message's stable position in history → providers' prefix caching covers it
  automatically on every later turn, on every OpenAI-compatible endpoint
  (GLM, DeepSeek, OpenRouter — nothing provider-specific anywhere).
- Inside that user message, the user's own words come first, followed by
  Includes in stable attachment order. Full and condensed documents use the
  compact `<document name="…">…</document>` wrapper; only a short
  `form`, `partial`, or `rows` attribute is added when the model needs that
  fact. Artifacts use a compact `<bookmark name="…">…</bookmark>` line.
  There are no repeated decorative `---` delimiters.
- **Images are always the LAST content in their message block** (Step 3): if
  a provider can't cache image content, everything before
  the image still caches. Text parts always precede the image part.
- All sends go through the single generation funnel
  (`generateResponse` → `regularGPTResponse`) — no second path. The
  prompt-layer contract in CLAUDE.md is untouched: includes add NO new system
  messages and never reorder the fixed layers; they ride inside user
  messages.
- Any form change (remove, condense, edit, artifact) changes the history the
  provider sees → one full-price turn, then caching resumes. This is accepted
  behavior; the UI does not need to warn about it.
- Voice pipeline untouched. Includes apply to typed and spoken turns
  identically because they live in history, not in the turn path.

### Persistence

- Include records (form, text, artifact line, token estimate, per-message
  snapshot) ride with the chat's stored data so they survive app restarts
  and chat renames. Any new per-chat preference key is registered in
  `PerChatSettingKeys` (test-enforced). Rename safety goes through the
  existing wholesale-copy rename transaction — no hand-maintained copy
  blocks.
- Attached source files are not retained beyond extraction; what persists is
  the extracted text (chat content storage is already encrypted at rest).

## Build order and done-ness

- **Step 1 — REPAIRED AND VERIFIED.** Acceptance covers:
  - Camera, Image, Document in that order in the paperclip menu, shown as a
    vertical stack of icon-and-word rows.
  - `.txt`, `.md`, `.csv`, and `.docx` extraction on device.
  - Correct size guards, logical CSV row handling, token estimates, and
    approved upload-error dialogs.
  - The live Includes strip, Remove→artifact, bookmark popup and editing,
    per-message Includes accordion, persistence, and model-request projection.
  - Regression tests proving attached text remains in the model request and
    that truncation/counting disclosures are accurate.
- **Step 2:** Condense + the Edit dialog. Partial code may remain visible
  while Step 1 is repaired; it is not part of Step 1 completion and must not
  be used to claim Step 1 is unfinished merely because Condense is provisional.
- **Step 3:** images join (image icon rows, Reduce to Text Only, ~token
  estimate from dimensions, image-last ordering), and the old vision path —
  the hard-coded `gpt-4o` branch and everything only it used — is deleted.
  No orphaned layouts, strings, or drawables are left behind.

Each step: static verification per the `CLAUDE.md` checklist, push, and watch
Android Checks to green. A step is not complete until its own acceptance
boundary is verified; later-step placeholders do not block an earlier step.

## Documentation upkeep when building

- New shared styles/layouts → documented in `ui-style-guide.md` with rollout
  notes (never back into CLAUDE.md).
- CLAUDE.md's feature list gains an Includes section when Step 1 lands (and
  gets updated at Steps 2/3), including the old-vision-path deletion at
  Step 3.
- This plan file gets a status line per step as steps complete.
