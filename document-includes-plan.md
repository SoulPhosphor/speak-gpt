# Document & Image Includes — Build Plan

Status: **Step 3 in progress.** Steps 1 and 2 are complete. Step 3 data
model, image importer, capability store, kind-aware UI wording, and legacy
vision-path deletion have landed and are CI-green (all tests pass, debug APK
builds). Four integration pieces remain before Step 3 is complete (Steps
3a–3d, fully documented in the Build order section).

This document records the current implementation baseline and build boundary.
It does not turn an old discussion, uncertainty, or existing code into user
approval. Current instructions in chat override stale passages here. If a
product choice is not settled, stop and ask before building it.

Decisions explicitly reconfirmed during the Step 1 repair:

- The paperclip opens a vertical icon-and-word menu in this order:
  **Camera**, **Image**, **Document**.
- Tapping anywhere outside that menu dismisses it. The paperclip itself
  continues to toggle the menu open and closed.
- Upload failures use a pop-up dialog with the file name and a descriptive
  error. Successful attachment size/cost notices remain inline.
- Removing a pending document before Send detaches it completely. It is never
  sent to the model, written into chat history, or converted to an artifact.
- The composer Includes strip contains pending, unsent attachments only and
  collapses at **4 or more** documents.
- Full-document history uses the under-name metadata record. A condensed
  document uses the bookmark-with-plus; a fully removed sent document uses
  the empty bookmark. Both bookmarks reopen editable text with Cancel/Save.
- Sent full-document rows stay open at one to three items and collapse only
  at **4 or more** documents/images. Their three-dot menus contain the
  post-send actions.
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
   version that takes effect when the request completes and is optionally
   viewable/editable by the user at any time afterward. The two terms remain distinct:
   condensing makes the same kind of thing smaller; reducing an
   image *removes* the visual data entirely and keeps only words. Never merge
   these terms.
3. **Artifact** — a very short bookmark, no more than three sentences,
   so the AI never faces replies to something that no longer exists.
   **Removing an item that was already sent converts it to an
   artifact. Removing a still-pending item detaches it without a trace.**
   The artifact is AI-written at removal time (bounded, via the chat's own
   endpoint/model), falls back to a filename-based line if
   the AI is unreachable, and is user-editable afterward like everything else.

Moving DOWN the ladder is always user-initiated. Nothing automatic ever
changes an include's form.

## Scope

- **Step 1 — documents:** attach `.txt`, `.md`, `.csv`, `.docx`. The Includes
  strip, pending detach, sent-item Remove→artifact, the artifact bookmark
  popup, the history accordion, token estimates, size guards, and exact-source
  duplicate protection within one pending message.
- **Step 2 — Condense** for documents, with automatic application and
  optional later editing from the bookmark-with-plus icon.
- **Step 3 — images** join the same system ("Reduce to Text Only"); the old
  broken image path (hard-coded `gpt-4o`, bypasses history/memory) is
  **deleted**, not left behind. Accepted formats: JPEG, PNG, and HEIC
  (HEIC is converted to JPEG at import time — no HEIC kind persists).

**Current non-goals:** no PDF (deferred), no legacy `.doc` (only
`.docx`), no image generation changes, no writing edited documents back to
disk (`.docx` is read-only — the AI's editing help arrives as chat text; the
extraction is words-only, formatting is not preserved), and no summarization
unless the user explicitly chooses Condense or Remove.

## Current wording inventory

All strings live in `res/values/strings.xml` only, per house rule.

| Where | Words |
|---|---|
| Strip label, full form | **Includes** |
| Strip label, condensed form | **Includes condensed** |
| Pending document helper | **File will be sent to AI every turn. Condensing produces a summary of the document and reduces token count.** |
| Pending image helper (Step 3) | **Images will be sent every turn to the AI. Reducing it will produce a small text summary to save tokens.** |
| Menu item | **Remove** |
| Menu item (documents) | **Condense** |
| Menu item (images) | **Reduce to Text Only** |
| Menu item (condensed/reduced state) | **Edit** |
| Edit dialog buttons | **Cancel**, then **Save** — right-aligned, in that order |
| Condense hint buttons | **Condense**, **Cancel**, then **Never show this hint again.** underneath |
| Condense progress | **Document is being condensed.** → **Complete** → **Okay** |
| Weight display | **~N tokens** (tilde always shown — it is an estimate) |
| Too-big note | *"This file is too large to send in full. The beginning was included, up to about ~30,000 tokens."* |
| Oversized CSV note | *"Large spreadsheet — sent the column names and first 500 rows of 47,000."* (real numbers substituted) |
| Duplicate pending source | **Document already attached.** with an **Okay** button |
| History box label | **Includes** |
| Artifact default shape | AI-written reminder, no more than 3 short sentences |
| Collapse line (4+ docs) | **Includes N Documents** — "Documents" capitalised per the app's Title Case rule, followed by a **downward-facing chevron** |
| Collapse line (4+ images) | **Includes N Images** |
| Collapse line (4+ mixed) | **Includes N Files** |
| Show accessibility (docs) | **Show Attached Documents** |
| Hide accessibility (docs) | **Hide Attached Documents** |
| Show accessibility (images) | **Show Attached Images** |
| Hide accessibility (images) | **Hide Attached Images** |
| Show accessibility (mixed) | **Show Attached Files** |
| Hide accessibility (mixed) | **Hide Attached Files** |
| Duplicate image popup | **Image already attached.** with an **Okay** button |
| Camera display name | **{ChatName} MM-DD-YY HH-mm.jpg** |

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

### Image import failure dialogs

Each dialog has a title and body. The dialog has a Close action.

| Condition | Title | Body |
|---|---|---|
| Unsupported image type (not JPEG/PNG/HEIC) | **Format Not Supported** | **Only JPEG, PNG, and HEIC images can be attached.** |
| HEIC-to-JPEG conversion failure | **Conversion Failed** | **This HEIC image could not be converted. Try converting it to JPEG or PNG first.** |
| Read or decode failure | **Image Not Readable** | **This image could not be read. It may be damaged or incomplete.** |
| Exceeds device processing limits | **Image Too Large** | **This image is too large to process on this device.** |

### Image capability dialogs (at Send time)

| Condition | Title | Body | Actions |
|---|---|---|---|
| Model known unsupported | **Images Not Supported** | **{modelName} does not support image input. Remove the images or switch to a model that supports vision.** | **Okay** |
| Model capability unknown | **Image Support Unknown** | **It is not known whether {modelName} supports image input. The message may fail if the model cannot process images.** | **Cancel**, **Send Anyway** |

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
- One row per pending item. Immediately after Send, the row leaves the
  composer and appears in the transcript under the user name.
- The helper sits inside the pending Includes box, above the attachment rows.
  Documents use the approved pending-document helper. In Step 3, an image
  uses **"Images will be sent every turn to the AI. Reducing it will produce
  a small text summary to save tokens."** instead. If documents and images are
  pending together, show each applicable helper once.
- Row anatomy: **Includes** label → document/picture icon → name →
  **~N tokens** → trailing action.
  - Before Send, the trailing action is an **X** and its only action is
    **Remove**. Condense, Reduce to Text Only, and Edit are unavailable.
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
- **Per-row persistent notes** (never toasts): the too-big and CSV notes
  appear directly under the affected row because they disclose omitted
  content. The visible ~token count is sufficient for files sent whole; there
  is no redundant "Large file" note. Styled in the hint family
  (13sp, `@color/text_subtitle`).
- **Collapse at 4+:** with four or more rows the strip becomes a single line
  with a **downward-facing chevron at the end** (this supersedes the earlier
  upward-facing note; the chevron glyph points down even though the list
  opens upward). The noun adapts to content: **Includes N Documents** (all
  documents), **Includes N Images** (all images), or **Includes N Files**
  (mixed). Tapping expands the full list **upward as an overlay
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
  for substantially shorter Cliff Notes or a structured outline (internal
  prompt, configured bounded output).
- Before the first request, show the approved hint with **Condense**,
  **Cancel**, and **Never show this hint again.** underneath. Checking it
  suppresses the hint globally from then on.
- Condense opens a non-toast progress dialog with a rotating indicator and
  **Document is being condensed.** On success, the summary takes effect
  automatically, the dialog says **Complete**, and the user closes it with
  **Okay**. A failed or non-shorter result leaves the full form unchanged.
- The bookmark-with-plus next to the user name opens a small **Edit** /
  **Remove** menu. Edit opens the active summary; Remove converts it to the
  plain artifact bookmark. Editing is not a prerequisite for Condense.
- The full original is gone from what gets SENT once condensed (that is the
  point), but the original file is untouched on the user's device.
- The Condense prompt must preserve, when relevant: document identity and
  purpose; main sections, subjects, arguments, or sequence; important facts,
  findings, decisions, instructions, and conclusions; notable names, dates,
  numbers, examples, relationships, and distinctive details; and warnings,
  limitations, uncertainty, caveats, and unresolved issues. It adapts to the
  document type (including résumés, reports, and plans), and must not return a
  vague description, invent information, erase uncertainty, or produce
  something as long as the original.
- Phase 2 verification covers the exact prompt, selected endpoint/model, and
  output limit used in the request. Condense does not add a chunking system.

#### Reduce to Text Only (images — Step 3)

- Reduce replaces an image with an AI-written text description. The visual
  data is deleted from on-disk storage once the text takes effect. The
  original file on the user's device is never touched.
- Before the first Reduce, show a first-use hint dialog (separate suppress
  flag from the Condense hint: `neverShowReduceHint`). The hint dialog uses
  the same structure as the Condense hint: **Reduce**, **Cancel**, and
  **Never show this hint again.** checkbox.
- Reduce hint body: **Reducing an image replaces it with a short written
  description. The image will no longer be sent to the model.**
- Reduce progress: **Image is being reduced.** On success, the text takes
  effect automatically. A failed or non-shorter result leaves the full
  image unchanged.
- The Reduce request sends the image to the chat's configured endpoint/model
  as a vision request. The accompanying user message (what the user typed
  that turn) is included as context so the description can reflect the
  user's purpose. No separate token cap — uses the chat's configured
  maxTokens.
- Reduce prompt (approved): describes the image in detail — what it shows,
  any text visible in it, layout, colors, important details — so someone
  who cannot see the image understands its content and purpose. Includes
  the user's message and file name as context. Instructs: describe only,
  never invent, keep to a few clear paragraphs.
- After Reduce, the image uses the `bookmark_add` glyph with **Edit** /
  **Remove** menu, identical to the condensed-document bridge. Edit opens
  the reduced text; Remove converts to artifact.

### 4. Remove

- Before the message is sent, Remove detaches the pending item completely.
  Nothing from that file is sent, retained in chat history, or converted to
  an artifact. The pending row exposes this as a direct X, not a menu.
- After the item has been sent, Remove asks the AI for a very short reminder
  of what the document was, its general subject/purpose, and at most one or
  two especially important details. Use no more than three short sentences.
- Remove/artifact generation is distinct from Condense and does not try to
  preserve a discussable outline of the full document. Verification covers
  its exact prompt, selected endpoint/model, and output limit too.
- The reminder takes effect automatically; there is no required review
  dialog. The plain bookmark (without the plus) appears in the same
  after-name icon area and opens the reminder only when the user chooses to
  view or edit it.
- If the line can't be fetched (offline, endpoint error): filename-based
  fallback line, immediately, never a blocked removal, never an error dialog
  for this — the fallback IS the success path. The line is editable later
  either way.
- Artifact access point: the empty Material Symbols `bookmark` glyph, tinted
  `?attr/colorPrimary`, shown after the user name. One item opens its
  Cancel/Save Edit dialog directly. If several removed items share a message,
  an anchored file picker appears first; tapping outside closes it.

### 5. The history record (inside the chat transcript)

- A full document gets a row directly under the user label containing its
  type icon, name, explicit format, ~N tokens, and three-dot menu. One to three
  documents/images remain shown. At four or more, the collapsed accordion
  header adapts to content: **Includes N Documents** (all documents),
  **Includes N Images** (all images), or **Includes N Files** (mixed).
- Sent-item menus are anchored to those row three-dots: a full document has
  **Remove** and **Condense**; a full image (Step 3) has **Remove** and
  **Reduce to Text Only**.
- Once condensed, that document leaves the full-document box and uses the
  bookmark-with-plus Material Symbols `bookmark_add` glyph after the user
  name. Tapping opens an anchored **Edit** / **Remove** menu. Edit opens the
  condensed text in the Cancel/Save dialog; Remove creates the artifact. If
  several condensed items share a message, an anchored file picker appears
  first.
- Step 3 uses this same completed bridge for reduced images: after **Reduce to
  Text Only**, the image uses `bookmark_add`; its menu offers **Edit** and
  **Remove**; Edit opens the reduced text, and Remove creates the plain
  artifact bookmark. Do not build a separate image-only bookmark interaction.
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
- Threshold: at or below **~30,000 tokens**, send whole with the visible
  ~token estimate and no redundant size warning. Above **~30,000**, cut at
  the cap with the too-big note stating so plainly. No silent truncation,
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
- Attached source files (documents) are not retained beyond extraction; what
  persists is the extracted text (chat content storage is already encrypted
  at rest).
- Image bytes persist on disk (per-chat content-hash files) as long as the
  image is in FULL form. Reducing or removing the image deletes the on-disk
  bytes; only the AI-written text description or artifact line survives.
  The user's original image file is never modified or deleted.

## Image-specific decisions (Step 3)

### Accepted formats and conversion

JPEG and PNG are accepted directly as stored `IncludeKind.JPEG` and
`IncludeKind.PNG`. HEIC is accepted at import but converted to JPEG — no
HEIC kind persists in the data model. Multiple images can be attached to a
single message.

### Downsample policy

Images are downsampled to a longest edge of **2048 px**, preserving aspect
ratio. This is an app-level policy to keep payloads manageable, not a
provider limit. Images already within the cap are sent at original
resolution (never upscaled). `ImageDecoder` (API 28+) handles decoding and
applies EXIF orientation natively — no manual orientation step.

### Token estimate

Images use a dimension-based estimate:
`max(85, ceil(pixels / 750))` on the transmitted (post-downsample) dimensions.
Always displayed with tilde (**~N tokens**). This is a warning-only
estimate, never a hard send block. The floor of 85 covers the provider
overhead for any image regardless of size.

### On-disk storage

Image bytes are stored per chat under
`getExternalFilesDir("chat_includes")/<sanitized-chatId>/`. File names are
content hashes (SHA-256 prefix), so the same image bytes always produce the
same file. This means:

- **Deduplication**: identical images in the same chat share one file.
- **Deletion safety**: a file is only deleted when no other FULL include in
  that chat (pending or saved) still references the same hash. The check
  (`imageBytesStillReferenced`) scans both pending and history includes.
- **Chat rename**: `ImageImporter.moveChatImages()` is called after the
  rename transaction's pointer flip. It tries `renameTo` first, falls back
  to copy, and merges if the destination directory already exists.
- **Chat delete**: `ImageImporter.deleteChatImages()` removes the entire
  per-chat directory.

### Orphan cleanup

Three layers prevent leaked files:

1. **Lifecycle-scoped imports**: each import runs in a coroutine scoped to
   the Activity. If the Activity dies mid-import, the scope is cancelled.
2. **Immediate cleanup on Activity death**: `onDestroy()` cancels pending
   import scopes and deletes any files that were written but never attached
   to a ChatInclude.
3. **Load-time reconciliation**: `reconcileChatImages()` runs when a chat is
   loaded, deleting any files on disk that are not referenced by any saved
   include's `imageFileHash`.

### Duplicate detection

At import time, a source fingerprint (URI + size + lastModified) is checked
against `pendingImageImports`. If a duplicate is detected, the
**Image already attached.** dialog appears and import is skipped.

### Image capability (three-state per endpoint × model)

Each endpoint stores a per-model image capability as one of three states:

- **UNKNOWN** — no data. Default for any model not yet tried.
- **SUPPORTED** — the model has successfully processed a vision request.
- **UNSUPPORTED** — the model has clearly rejected a vision request.

Storage is a JSON map in `ApiEndpointPreferences` under the key
`_image_capability_by_model`. Setting UNKNOWN removes the entry (empty maps
are not stored).

**Auto-learning**: when a vision request succeeds, the store records
SUPPORTED for that endpoint/model pair. When a provider returns a clear
rejection (not a transient error), it records UNSUPPORTED.

**Manual override**: a collapsed section in the endpoint editor shows all
recorded models with their current state. Each model's state can be cycled
manually. A "Clear image capability history" action with confirmation
dialog resets the store for that endpoint.

### Sending mechanics for images

- Full images produce NO text-side content — `IncludeRenderer` skips them
  in the text wrapper. They contribute `RenderedImagePart` objects instead.
- `IncludeMessageProjection.userMessageParts()` returns a
  `ProjectedUserMessage` with `text` (user words + document/reduced wrappers
  + bookmarks) and `imageParts` (list of `RenderedImagePart`).
- Multi-part user messages order text content first, image content parts
  last (for prefix caching). An image-only message (no typed text, no
  document includes) must not emit an empty text part.
- Reduced images render as `<image name="..." form="reduced">...</image>`
  (distinct from `<document>` wrappers). Removed images use `<bookmark>`
  like documents.
- Image bytes are loaded from disk as base64 on an IO thread at send time,
  not held in memory between turns.

### Camera naming

Camera captures use the display name format:
**{ChatName} MM-DD-YY HH-mm.jpg** — the chat's human name, date, and
time, with `.jpg` extension (camera always produces JPEG).

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
- **Step 2:** Automatic Condense + optional bookmark editing, first-use hint,
  progress/completion dialog, distinct Condense and Remove prompts, selected
  endpoint/model and output-limit verification, and safe failure/race handling.
- **Step 3 — IN PROGRESS.** Images join the same system. Landed and
  CI-green: image data model (`ChatInclude` with JPEG/PNG kinds, image
  fields, `isImage()` helpers), `ImageImporter` (pick/capture, HEIC→JPEG,
  downsample to 2048 px, content-hash per-chat storage, orphan cleanup),
  `ImageCapabilityStore` (three-state per endpoint×model, auto-learn ready),
  `IncludeRenderer` and `IncludeMessageProjection` multi-part output,
  `IncludeAuxiliaryRequestPolicy.reduceImage()`, kind-aware collapse lines
  and accessibility labels, reduce hint layout and strings, all image
  error dialogs, camera display naming, legacy vision-path deletion
  (hard-coded `gpt-4o` branch, `attachedImage` view, old gallery picker,
  old `chatMessage["image"]/["imageType"]` fields — all removed).
  **Remaining before Step 3 is complete (4 integration pieces):**

### Step 3a — Wire the multi-part sending path

The pure-data layer is complete: `IncludeMessageProjection.userMessageParts()`
returns a `ProjectedUserMessage` with text + a list of `RenderedImagePart`
records, and `IncludeRenderer.imagePartsFor()` enumerates FULL images that
need to accompany a message. The library types `ContentPart`, `ImagePart`,
and `TextPart` are imported in `ChatActivity`. What remains is wiring the
callers.

**What to change:**

1. **Parallel includes list.** Add a `chatMessageIncludes: ArrayList<String?>`
   field alongside `chatMessages`. In `rebuildModelProjection()`, populate
   it in lock-step: each entry is the includes JSON for user messages,
   `null` for assistant messages. This keeps the projection itself fast
   (no IO, no base64) while preserving the information needed to resolve
   images at send time.

2. **Send-time resolution.** Add a suspend helper
   `resolveImagePartsForSend(textMessages, includesList)` that runs on
   `Dispatchers.IO` and returns a new `List<ChatMessage>` where every user
   message with FULL image includes becomes a multi-part `ChatMessage`:
   - If the text side is non-blank, emit a `TextPart(text)` first.
   - For each `RenderedImagePart`, load bytes via
     `ImageImporter.imageFile(context, chatId, include)?.readBytes()`,
     base64-encode with `Base64.NO_WRAP`, and emit
     `ImagePart("data:$mime;base64,$encoded")`.
   - An image-only message (no typed text, no documents, no reduced/artifact
     wrappers) must not emit an empty `TextPart`.
   - If bytes are missing on disk (already Reduced/Removed), skip that
     image part silently — the text side already omits it.
   - Images are always last in the content-part list (text, then documents
     inline in the text, then image parts), so prefix caching covers
     everything before the image bytes.

3. **Prepared-turn path** (`prepareTypedTurn` → `buildFrozenRegularRequest`):
   use `userMessageParts()` instead of `userContent()` for the current
   turn's message. Append the current turn's includes JSON to the history
   snapshot's includes list. Call the resolution helper on the full
   `requestMessages` + `requestIncludes` pair before they enter the
   `ChatCompletionRequest`.

4. **Legacy path** (`regularGPTResponse`): call the resolution helper on
   `chatMessages` + `chatMessageIncludes` before adding them to `msgs`.

5. **Payload measurement** (`FrozenPayloadMessage`): multi-part messages
   carry image bytes that do not appear in `message.content?.toString()`.
   Update the payload builder so images contribute their estimated token
   count rather than their base64 length.

6. **History rebuild** (`modelFacingContent`): no change needed — it
   continues returning text-only content. Image parts are resolved only
   at send time.

### Step 3b — Wire the Reduce flow

The policy (`IncludeAuxiliaryRequestPolicy.reduceImage()`), the hint dialog
layout (`dialog_include_reduce_hint.xml`), the preference
(`neverShowReduceHint`), and all strings exist. The condense flow
(`condenseInclude` → `showCondenseHint` → `startCondensing` →
`requestCondensedText`) serves as the pattern. What remains is the
image-specific version.

**What to change:**

1. **Add `onIncludeReduce` callback** to `ChatAdapter.OnChatItemClickListener`
   so the adapter can distinguish Reduce from Condense. In the adapter's
   sent-item three-dot menu, route the "Reduce to Text Only" tap to
   `onIncludeReduce` instead of `onIncludeCondense`.

2. **`reduceInclude(include)`** in `ChatActivity` — mirrors
   `condenseInclude`:
   - Guard: `include.form == IncludeForm.FULL && include.kind.isImage()`
     and no reduce job already running.
   - Check `preferences?.getNeverShowReduceHint()`. If suppressed, go
     straight to `startReducing`. Otherwise show the reduce hint dialog.

3. **`showReduceHint(include)`** — inflate
   `dialog_include_reduce_hint.xml`, wire **Reduce** / **Cancel** /
   **Never show this hint again** using the same dialog pattern as
   `showCondenseHint`.

4. **`startReducing(include)`** — show progress dialog with
   **Image is being reduced.** and a spinner. Launch a coroutine that
   calls `requestReducedText(include)`.

5. **`requestReducedText(include)`** — the key difference from
   `requestCondensedText`: this is a **multi-part vision request**. It
   sends the reduce prompt as the text side and the image bytes as an
   `ImagePart` in the same user message. Uses the chat's own configured
   endpoint/model and maxTokens. Steps:
   - Build the `RequestSpec` via
     `IncludeAuxiliaryRequestPolicy.reduceImage(...)` with the
     accompanying user message from the turn the image was attached to.
   - Load image bytes from disk via `ImageImporter.imageFile(...)`.
   - Construct a `ChatCompletionRequest` with a single multi-part user
     message containing `TextPart(spec.prompt)` + `ImagePart(dataUrl)`,
     using the spec's model and maxTokens.
   - Send via `ai!!.chatCompletion(request)`.
   - Return the result text.

6. **On success**: apply the reduced text with
   `include.copy(form = IncludeForm.CONDENSED, condensedText = result)`
   and `.withoutImageBytes()`. Then call `maybeDeleteImageBytes(include)`
   to remove the on-disk bytes once no other FULL include in the chat
   references the same hash.

7. **On failure or non-shorter result**: leave the image unchanged (same
   as the condense failure path).

8. **Remove path for images**: the existing `removeInclude` already
   handles image includes with `withoutImageBytes()` +
   `maybeDeleteImageBytes()` and uses the filename fallback. Once the
   Reduce flow is wired, update Remove for sent images to also request an
   AI-written artifact (same as documents), with the image bytes sent as
   a vision request so the model can see what it is describing. If the
   vision request fails, the filename fallback stands immediately (same
   as documents).

### Step 3c — Wire capability validation at Send

The `ImageCapabilityStore` and `ImageCapability` enum are complete, persisted
per endpoint in `ApiEndpointPreferences`, and unit-tested. What remains is
checking them at send time and recording results.

**What to change:**

1. **Pre-send check.** Before the message enters the generation path,
   check whether the current turn (or any history message) contains FULL
   image includes. If so, read the capability for the current
   endpoint + model:
   - **SUPPORTED**: proceed normally.
   - **UNSUPPORTED**: show the approved blocking dialog (**Images Not
     Supported** / body / **Okay**). Do not send.
   - **UNKNOWN**: show the approved warning dialog (**Image Support
     Unknown** / body / **Cancel** + **Send Anyway**). On **Send
     Anyway**, proceed. On **Cancel**, return to the composer.

2. **Placement**: the check runs in `prepareTypedTurn` for typed messages,
   after includes are snapshotted but before `parseMessage` commits the
   turn. For voice messages, the check runs before
   `generateResponse`. The check is a UI dialog, so it must run on the
   main thread. The dialog suspends the coroutine until the user acts.

3. **Auto-learn on success.** After `regularGPTResponse` completes
   successfully and the request included image parts, record
   `ImageCapability.SUPPORTED` for the current endpoint + model.

4. **Auto-learn on clear rejection.** In the error handler, when the
   provider returns an error that `GenerationErrorClassifier` identifies
   as a vision-specific rejection (not a transient error, not a rate
   limit, not a context-length error), record
   `ImageCapability.UNSUPPORTED` for the current endpoint + model. The
   classifier already distinguishes error categories; a new
   `isVisionRejection` flag or category may be needed if one does not
   exist.

5. **Scope of the check.** The check is per-turn: if the conversation
   already contains sent images from earlier turns (whose bytes are still
   FULL), those will be resent this turn, so the model must support
   vision for ANY turn that re-sends them. The check therefore fires
   whenever the resolved message list contains any image content parts,
   not only when the current turn's pending includes have images.

### Step 3d — Manual override section in endpoint editor

Data plumbing (`ApiEndpointObject.imageCapabilityByModel`,
`ApiEndpointPreferences` getters/setters) and string resources are complete.
What remains is the UI section in `ApiEndpointEditorActivity`.

**Approved UI (owner decision, July 28 2026):** a dropdown per model. Model
name on the left, dropdown on the right, using the app's
`Widget.App.Dropdown.Label` / `Widget.App.Dropdown.Value` styles and an
anchored `ListPopupWindow`. The section is collapsed by default with a
chevron, consistent with other expandable sections. The three dropdown
options are **Unknown**, **Supported**, and **Unsupported**.

**What to change:**

1. **Layout.** Add a collapsible section to the bottom of
   `activity_api_endpoint_editor.xml`:
   - Section header: `Widget.App.Section.Title` with the approved title
     string, plus a trailing chevron that rotates on expand/collapse.
   - Section description: `Widget.App.Section.Hint` with the approved
     description string.
   - A `LinearLayout` container (initially `GONE`) that holds
     dynamically-inflated model rows.
   - A **Clear image capability history** button
     (`AppButton.Destructive`) at the bottom of the section, visible
     only when entries exist.

2. **Model rows.** Each recorded model is one horizontal `LinearLayout`
   with:
   - `Widget.App.Dropdown.Label` — the model id string.
   - `Widget.App.Dropdown.Value` — shows the current state label;
     tapping opens a `ListPopupWindow` with the three options.
   - Selecting an option writes back via `ImageCapabilityStore.set()`.

3. **Clear action.** Tapping the clear button shows a confirmation
   dialog (title: approved string, body: approved string, actions:
   **Cancel** + approved confirm button). On confirm, calls
   `ImageCapabilityStore.clear()`, persists, and removes all model rows
   from the section.

4. **Empty state.** When no models are recorded, the section shows the
   approved empty-state string and hides the clear button.

5. **Lifecycle.** The section is populated in `loadValues()` from the
   endpoint's stored `imageCapabilityByModel` JSON. Changes are
   persisted in `buildEndpointObject()` so they save with the rest of
   the endpoint profile. `buildEndpointObject` must include the
   `imageCapabilityByModel` field.

Each piece: static verification per the `CLAUDE.md` checklist, push, and
watch Android Checks to green. A piece is not complete until its own
acceptance boundary is verified; later-piece placeholders do not block an
earlier piece.

## Documentation upkeep when building

- New shared styles/layouts → documented in `ui-style-guide.md` with rollout
  notes (never back into CLAUDE.md).
- CLAUDE.md's feature list gains an Includes section when Step 1 lands (and
  gets updated at Steps 2/3), including the old-vision-path deletion at
  Step 3.
- This plan file gets a status line per step as steps complete.
