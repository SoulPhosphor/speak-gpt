# Document & Image Includes — Build Plan

Status: **Steps 1 and 2 BUILT July 24 2026** (attach/extract/strip/
Remove→artifact/history record/size guards, plus Condense and the Edit
dialog). Step 3 (images) not started.
Everything below is the full approved design; see "Build order" at the end for
what each step covers. Every user-facing
word, behavior, and UI decision in this plan was settled with the owner in
plain chat, July 24 2026. Where this plan and that conversation disagree, the
conversation wins. Any fork this plan does not settle → stop and ask in chat
before building (standing owner ruling, July 23 2026). Do not use the pop-up
question tool — plain chat only.

## What this feature is, in the owner's terms

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
   the user at any time afterward. The owner chose two different words
   deliberately: condensing makes the same kind of thing smaller; reducing an
   image *removes* the visual data entirely and keeps only words. Never merge
   these terms.
3. **Artifact** — a one-line bookmark ("User sent a photo of a purple
   amethyst cluster"), so the AI never faces replies to something that no
   longer exists. **Remove converts to artifact; it never erases outright.**
   The artifact line is AI-written at removal time (bounded, ~12 words max,
   via the chat's own endpoint/model), falls back to a filename-based line if
   the AI is unreachable, and is user-editable afterward like everything else.

Moving DOWN the ladder is always user-initiated. Nothing automatic ever
changes an include's form.

## Scope

- **Step 1 — documents:** attach `.txt`, `.md`, `.csv`, `.docx`. The Includes
  strip, Remove→artifact, the artifact bookmark popup, the history accordion,
  token estimates, size guards.
- **Step 2 — Condense** for documents, with the Edit dialog.
- **Step 3 — images** join the same system ("Reduce to Text Only"); the old
  broken image path (hard-coded `gpt-4o`, bypasses history/memory) is
  **deleted**, not left behind.

**Non-goals (owner rulings):** no PDF (deferred), no legacy `.doc` (only
`.docx`), no image generation changes, no writing edited documents back to
disk (`.docx` is read-only — the AI's editing help arrives as chat text; the
extraction is words-only, formatting is not preserved), no automatic
summarization of anything.

## Approved wording inventory (exact strings — do not reword without asking)

All strings live in `res/values/strings.xml` only, per house rule.

| Where | Words |
|---|---|
| Strip label, full form | **Includes** |
| Strip label, condensed form | **Includes condensed** |
| Menu item | **Remove** |
| Menu item (documents) | **Condense** |
| Menu item (images) | **Reduce to Text Only** |
| Menu item (condensed/reduced state) | **Edit** |
| Edit dialog buttons | **Cancel**, then **Save** — right-aligned, in that order (owner-specified layout) |
| Weight display | **~N tokens** (tilde always shown — it is an estimate) |
| Large-file note | *"Large file — adds about ~30,000 tokens to every message while included."* (N is the item's real estimate) |
| Too-big note | *"This file is too large to send in full. The beginning was included, up to about ~30,000 tokens."* |
| Oversized CSV note | *"Large spreadsheet — sent the column names and first 500 rows of 47,000."* (real numbers substituted) |
| History box label | **Includes** |
| Artifact line default shape | "User sent …" — AI-written, ≤ ~12 words |
| Collapse line (4+ items) | **Includes N Documents** — "Documents" capitalised per the app's Title Case rule (owner ruling, July 24 2026), followed by a **downward-facing chevron** |

## UI specification, surface by surface, with the styles each uses

Style authority: `ui-style-guide.md`. Every new shared style or layout this
feature adds gets documented THERE (not in CLAUDE.md), with rollout notes.
No toasts anywhere (standing rule). All notices are persistent inline text.

### 1. The Includes strip (above the message box)

- Sits directly **above the input bar** (`keyboard_input`) at the bottom of
  the chat screen, full-width, on the same surface family as the input bar so
  it reads as part of the composition area, not a floating element.
- One row per included item, visible **at all times** while anything heavier
  than an artifact is included (owner ruling: full/condensed/reduced items are
  the data drain, so they stay plainly shown; only artifacts retire to the
  bookmark popup).
- Row anatomy: leading type icon → name → **~N tokens** → three-dots menu.
  - Leading icon: 36dp slot per `Widget.App.Row.Icon` sizing convention,
    vector drawable tinted `?attr/colorPrimary` (house icon rule). Type
    icons: text-document glyph (`.txt`), markdown-document glyph (`.md`),
    table glyph (`.csv`), Word-document glyph (`.docx`), image glyph
    (Step 3). Distinct glyphs are the owner's requirement ("easy
    identification" as more types arrive); exact glyph picks are reviewable
    on device.
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
  end** (owner ruling, July 24 2026 — this supersedes the earlier
  upward-facing note; the chevron glyph points down even though the list
  opens upward). Tapping expands the full list **upward as an overlay
  covering the chat** (the conversation must not be shoved around),
  scrollable, collapsed again the same way or by tapping outside. With three
  or fewer rows the strip shows them all, no collapse.

### 2. The Edit dialog (condensed/reduced text, and artifact lines)

- Owner chose the pop-up style deliberately for this one case (not a
  full-screen editor).
- Built on `App.MaterialAlertDialog` (`R.style.App_MaterialAlertDialog`) —
  the one standard dialog theme; centered title comes free from the theme.
- Body: a multi-line editable text box skinned with `Widget.App.Field.Box`
  (`bg_field_box`), pre-filled with the current condensed/reduced/artifact
  text.
- Buttons: **Cancel**, then **Save**, right-aligned in that order
  (owner-specified). Button LOOK comes from the AppButton dialog-action
  family (`AppButton.Destructive.DialogAction` look for Cancel,
  `AppButton.Primary.DialogAction` look for Save) so they retheme with every
  other dialog button — but the ARRANGEMENT (right-aligned pair) is this
  dialog's own, per the owner, so it needs its own shared layout file in the
  `dialog_two_actions.xml` family (e.g. an end-aligned variant). That layout
  + any style it needs gets documented in `ui-style-guide.md` when added.
  Do NOT reuse `dialog_two_actions.xml` as-is (its chain is
  primary-start/destructive-end, centered — a different approved shape for a
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

### 4. Remove → artifact

- Remove (from any state) asks the AI for the ≤ ~12-word bookmark line,
  swaps the item to artifact form, and the row leaves the strip.
- If the line can't be fetched (offline, endpoint error): filename-based
  fallback line, immediately, never a blocked removal, never an error dialog
  for this — the fallback IS the success path. The line is editable later
  either way.
- Artifact access point: the **bookmark-with-checkmark icon** (Material
  Symbols `bookmark_added` glyph, added as a vector drawable, tinted
  `?attr/colorPrimary`) shown after the user name on affected messages and
  wherever artifact state needs indicating. Tapping opens an **anchored
  popup** listing the artifact lines; tapping anywhere outside closes it.
  Each line in that popup is tappable to open the Edit dialog on it.

### 5. The history record (inside the chat transcript)

- A user message that carried includes gets a small **Includes** box directly
  under the user label in that message's bubble area. Tap = accordion opens
  listing each item: type icon + name + ~N tokens. Tap again closes.
- This box is a permanent snapshot of what went with THAT message. It does
  not change when the live include later gets condensed/removed — the strip
  shows the present; the history box shows the past.
- Renders in `ChatAdapter` rows (RecyclerView, recycled views — the accordion
  open/closed state must not bleed between recycled rows).

## Behavior specification

### Extraction (on-device, no new libraries)

- `.txt` / `.md` / `.csv`: read directly. UTF-8 assumed; other encodings
  degrade gracefully (charset detection best-effort, never mojibake dumped
  silently — if the text comes out garbled-looking/binary, refuse with a
  persistent inline explanation).
- `.docx`: it is a zip of XML — extracted with the platform's own zip + XML
  parsing. Words only; formatting, images, tracked changes are not carried.
  No third-party document library is added for this.
- **Garbage guard:** a file that is not genuinely text (renamed binary) is
  refused with a persistent inline message, never injected.
- **Oversized CSV rule:** header row + first 500 rows + the owner-approved
  total-count line. A truncated CSV without the header/count would mislead
  the AI into analyzing a fragment as the whole — this rule exists so it
  can't.

### Weight display and limits (tokens, owner ruling)

- Everything displays **~N tokens** (one unit everywhere). Documents estimate
  from text length (a standard characters-per-token heuristic — accurate to
  roughly ±25% across models, hence the tilde). Images (Step 3) estimate from
  pixel dimensions.
- Thresholds: under **~10,000 tokens** send quietly; **~10,000–30,000**
  send with the persistent large-file note; above **~30,000** the item is cut
  at the cap with the too-big note stating so plainly. No silent truncation,
  ever.

### Sending mechanics (results the user cares about; constraints that bind)

- Document text travels inside the user message it was attached to, at that
  message's stable position in history → providers' prefix caching covers it
  automatically on every later turn, on every OpenAI-compatible endpoint
  (GLM, DeepSeek, OpenRouter — nothing provider-specific anywhere).
- **Images are always the LAST content in their message block** (owner
  ruling, Step 3): if a provider can't cache image content, everything before
  the image still caches. Text parts always precede the image part.
- All sends go through the single generation funnel
  (`generateResponse` → `regularGPTResponse`) — no second path. The
  prompt-layer contract in CLAUDE.md is untouched: includes add NO new system
  messages and never reorder the fixed layers; they ride inside user
  messages.
- Any form change (remove, condense, edit, artifact) changes the history the
  provider sees → one full-price turn, then caching resumes. Known and
  accepted by the owner; the UI does not need to warn about it.
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

- **Step 1 — BUILT July 24 2026.** Picker accepts the four document types (a
  Document choice alongside the existing Camera/Gallery in the attach
  selector) → extraction → strip with token counts and notes →
  Remove→artifact → per-message history record → size guards.
  Notes on what shipped:
  - The artifact's bookmark marker appears as the **icon on the message's own
    "Includes" record** (which switches to `ic_bookmark_added` once everything
    that message carried has been reduced to a bookmark), and the accordion
    lists the bookmark LINES. A separate popup hung off the user name was the
    original sketch; folding it into the existing accordion gives the same
    "see what's in there" behaviour with one control instead of two.
  - The accordion shows each item's **current** weight, not the weight
    recorded at send time. The plan's earlier "permanent snapshot" wording
    was self-contradictory: after a document is reduced to a bookmark the
    original figure would overstate what that message still costs every turn.
    `ChatInclude.sentTokens` still records the original for later use.
  - Four draft strings (`include_error_*`) cover the four failure cases. The
    owner has not ruled on the wording yet; they are flagged in `strings.xml`.
- **Step 2:** Condense + the Edit dialog (which also serves artifact-line
  editing from Step 1 — build the dialog in whichever step reaches it first).
- **Step 3:** images join (image icon rows, Reduce to Text Only, ~token
  estimate from dimensions, image-last ordering), and the old vision path —
  the hard-coded `gpt-4o` branch and everything only it used — is deleted.
  No orphaned layouts, strings, or drawables left behind (owner directive:
  no trash).

Each step: static verification per the CLAUDE.md checklist, push, **watch
Android Checks to green** (owner ruling — driving CI green is part of the
job). Feature-level "done" for each step is the owner seeing it work on
their own phone from a test build; report factually (what changed + CI
result) without over-hedging, per the July 23 ruling.

## Documentation upkeep when building

- New shared styles/layouts → documented in `ui-style-guide.md` with rollout
  notes (never back into CLAUDE.md).
- CLAUDE.md's feature list gains an Includes section when Step 1 lands (and
  gets updated at Steps 2/3), including the old-vision-path deletion at
  Step 3.
- This plan file gets a status line per step as steps complete.
