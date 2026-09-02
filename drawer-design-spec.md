# Drawer Design Specification

**Status:** Owner-approved drawer/navigation/Search design, updated August 30, 2026.

**Authority:** This file is the current product/UI authority for the drawer, folder organization, new-chat Chat/Playground mode selector, full-text Search, and related chat-list interactions. It supersedes conflicting drawer-specific details in `ui-redesign-plan.md` Section 5 and its Phase 3 summary, including the older no-header drawer, inline drawer search, hamburger control, last-chat startup, partial-width drawer assumptions, fixed-bottom Playground row, the earlier three-row New Chat/Search top block, and the legacy title-only arbitrary-substring Search behavior.

The general repository safety, theme, voice, lifecycle, accessibility, and shared-style rules in `ui-redesign-plan.md`, `ui-style-guide.md`, `ui-style-adoption.md`, `image-gallery-spec.md`, and `CLAUDE.md` still apply. When this specification and `image-gallery-spec.md` overlap on image-safe chat/folder deletion, the image-safety rules remain binding.

Unless the owner explicitly specifies otherwise, icons referenced by this drawer/search design come from **Google Icons / Material Symbols**. Visible control/action names and accessibility names use Title Caps according to `ui-style-guide.md`.

---

## 1. Drawer Geometry And Open/Close Controls

- The drawer is a **full-width panel**. It occupies 100% of the available screen width while open.
- It enters from the **left edge of the screen and opens toward the right**, covering the chat while open.
- Full width is a product requirement. Do not silently accept a narrower framework default or leave a permanent strip of the chat visible because a standard drawer component applies a default margin.
- The **existing/approved double-chevron control at the top of the chat** opens the drawer. Do **not** replace it with a hamburger icon.
- The drawer header contains a **right-facing double-chevron** aligned to the right. Tapping it closes the drawer and returns to the chat state that was underneath it.
- **Do not add an edge-swipe or drag gesture to open the drawer.** Navigation between Chat and the drawer is intentionally controlled by the two double-chevron buttons plus normal system Back behavior.
- Returning from the drawer must preserve whichever chat state the user had before opening it: an existing saved chat if one was open, or the current blank/empty chat if that was open. Closing the drawer must not create, replace, or switch chats.
- System Back while the drawer is open closes the drawer first and returns to the underlying chat state.
- Opening or closing the drawer must not disturb mic state, voice/hands-free state, streaming state, draft text, keyboard/IME state, pending attachments, Includes, selected model/provider, Chat/Playground mode, or per-chat preferences.

---

## 2. Exact Drawer Hierarchy

The drawer has three vertical zones: a **fixed top**, a **scrollable middle**, and a **fixed bottom**. The fixed zones must remain visible while the middle scrolls.

Conceptually:

```text
Soul Phosphor                                      >>
[ Contract  New Chat                         ] [ Search Icon ]

Image Gallery
Folders >
Pinned Chats
...folder contents / pinned chats / unfiled chats...

Settings
```

The visual example communicates hierarchy, not literal text glyph sizing. Use the approved Google/Material icons in the actual app.

### 2.1 Fixed Top: App Name / Search / Return Row

**Updated by owner ruling, September 2 2026.** Search moved into this row and
the separate New Chat + Search row was removed; see 2.2.

The first fixed row contains, in order:

- **Soul Phosphor** left aligned;
- an icon-only **Search** control, immediately to the left of the chevron;
- the approved **right-facing double-chevron** aligned to the far right.

This row never scrolls away.

The double-chevron closes the drawer and returns to the underlying chat as defined in Section 1.

**Search control:** the existing magnifying-glass asset (`ic_search`), icon
only, no visible label. Accessibility/control name: **Search**. It opens the
dedicated Search screen in Section 5. It shares the chevron's control size so
the two read as one pair.

### 2.2 The Former New Chat + Search Row Is Removed

**Owner ruling, September 2 2026.** The drawer no longer has a second fixed top
row. New Chat moves to the fixed bottom (2.5) and Search moves beside the
chevron (2.1), because the full-width drawer has room for three bottom actions.

Do not reinstate a New Chat button at the top of the drawer.

### 2.3 Scrollable Middle: Exact Top-Level Order

The scrolling middle begins immediately below the fixed New Chat/Search row.

Top-level order:

1. **Image Gallery**
2. **Folders** accordion
3. **Pinned Chats** section
4. ordinary **unfiled chats** that are not currently displayed in Pinned Chats

Folder contents expand inline inside the Folders accordion as described in Section 4.

The scrollable middle must remain virtualized/efficient with hundreds of chats. It must not push the fixed top or fixed bottom off-screen.

### 2.4 Image Gallery Row

- Visible label: **Image Gallery**.
- Use the same Images icon already used on the Settings screen: `ic_image`.
- Image Gallery is now part of the **scrollable middle**, directly above Folders.
- It is **not** a fixed-bottom item.
- Tapping it opens the Image Gallery defined in `image-gallery-spec.md`.

### 2.5 Fixed Bottom: Settings, New Folder, New Chat

**Owner ruling, September 2 2026.** The fixed bottom carries three actions
across one row, in this exact left-to-right order:

1. **Settings**
2. **New Folder**
3. **New Chat**

Rules for this row:

- All three are the **same text size as the single Settings action always
  used** (17sp, `Widget.App.FlatChatRow.Title`). Do not shrink the text to make
  them fit, and do not enlarge it.
- **No icons inside these actions.** Text only, including Settings, which loses
  the gear it previously carried.
- Equal width, three across the full-width drawer.
- The row stays locked to the bottom. Users never scroll through chats or
  folders to reach any of them.
- Styling, spacing and touch feedback come from the shared
  `Widget.App.DrawerBottomAction` style, not from hard-coded values.

**New Folder** opens the same Add Folder naming dialog and the same validation
rules as Section 4.3. It does not replace or remove the Folders accordion's own
long-press Add Folder action.

**New Chat** performs the behavior previously specified for the top-row New
Chat button, unchanged apart from its position.

### 2.6 Playground Is Removed From The Drawer

**Do not show Playground as a drawer row.**

Playground access moves to the new-chat mode selector in Section 3. Removing the drawer row does not authorize unrelated changes to Playground behavior.

---

## 3. New Chat Chat / Playground Mode Selector

Playground becomes a **new-conversation mode choice** rather than a permanent drawer destination.

### 3.1 Where It Appears

On a genuinely blank/new chat, place a large centered segmented selector **beneath the chat header and above the conversation content**.

Visible labels, exact order:

1. **Chat**
2. **Playground**

**Chat is always the default** for a newly created blank conversation, including ordinary app startup into a blank chat.

The selector appears only before that conversation's first user message has been successfully committed/sent.

- Opening an existing saved chat does not show it.
- Once the first user message is successfully committed/sent, remove the selector from that conversation's UI.
- If the first send fails before the message becomes a committed chat turn, the selector remains available.
- Once a conversation has started, its chosen mode is durable and reopening the chat must not ask again.

### 3.2 Visual Treatment

The selector is a **large outer pill** containing both labels.

- The selected mode sits inside a second, brighter **oblong/capsule** nested inside the outer pill.
- The selected capsule visibly reads as the active choice through the shared selected/accent theme roles.
- When the user switches modes, the selected capsule **slides** to the other label.
- Because `Chat` and `Playground` have different word lengths, the selected capsule may expand/contract smoothly to fit the selected word rather than using two awkwardly oversized equal visual blocks.
- The animation must be cosmetic and must not delay mode selection or interfere with accessibility.
- Do not hard-code colors. The outer pill, selected capsule, selected/unselected text, corner radius, spacing, and animation-related dimensions must be centralized through shared styles/theme resources where practical.
- Expose the control accessibly as two mutually exclusive choices with a clear selected state. Do not rely solely on color/position to communicate selection.

If no reusable segmented/pill selector style exists when implementation begins, add a named shared style/pattern and document it in `ui-style-guide.md` rather than styling this locally in `ChatActivity`.

### 3.3 Switching Before First Send

Switching **Chat <-> Playground** while the conversation is still blank changes only the intended mode.

It must **not** discard or reset:

- typed draft text;
- pending attachments;
- persistent Includes;
- selected model/provider;
- current composer expansion state;
- other unrelated new-chat settings/state.

The mode selection itself must survive normal configuration/activity recreation while the blank chat is still being composed.

### 3.4 Playground Semantics

Selecting **Playground** uses the app's existing Playground behavior/semantics as they exist when implementation begins. The drawer redesign changes how the user enters that mode; it is not authorization to invent new Playground features or silently change provider/request semantics.

The implementation plan should map the current Playground pathway into this new pre-send mode selection with the smallest safe architectural change.

---

## 4. Folders

Folders are a **visual organization system only**.

They do **not** create Projects, memory sandboxes, separate context scopes, separate AI settings, request restrictions, special summarization behavior, or any other semantic boundary.

A folder answers one question only: **where should this chat be organized in the drawer?**

### 4.1 Folder Data Rules

- A saved chat may belong to **zero or one folder**.
- Folders are **one level only**. No folder may contain another folder.
- A folder contains chats only.
- Existing chats migrate naturally as **No Folder / unfiled**. Do not guess assignments.
- Folder membership should be durable metadata keyed by stable folder/chat identity. Moving a chat must not copy/rewrite its transcript simply to change organization.
- Folder identity must be independent of the folder's display name so Rename does not break chat membership.
- Folder pin state is independent of chat pin state.
- Folder expanded/collapsed state is presentation state, not chat content.

### 4.2 Folders Accordion

The top-level row appears exactly as a compact accordion control:

Collapsed example:

```text
Folders >
```

Expanded example:

```text
Folders v
```

Use an outlined Google/Material chevron treatment, such as **Chevron Right** while collapsed and a downward **Expand More** / downward chevron while expanded. The expanded icon should read as a simple V-shaped/downward chevron, not a filled triangle.

- **Short press** on the Folders row toggles expanded/collapsed state.
- **Long press** on the Folders row opens the shared compact action popup with one action: **Add Folder**.
- Tapping **Add Folder** opens the dialog in Section 4.3.
- Persist the Folders accordion expanded/collapsed state across drawer visits/app restarts.
- Collapsing Folders hides the folder rows and their chat children but does not affect Search or folder assignments.

### 4.3 Add Folder Dialog

Long press **Folders** -> **Add Folder** opens a shared text-entry dialog.

**Title:** `Add Folder`

**Input label:** `Folder Name`

**Actions, exact order:**

1. **Cancel**
2. **Okay**

Use `App.MaterialAlertDialog` and the app's shared centered cancel-first dialog conventions.

The text input itself must use a **shared name-entry dialog/input style** that is also used by Rename Folder. The owner specifically wants all rename/name-entry boxes centrally styleable. If the repository does not already have an appropriate named shared text-entry dialog layout/style, add one and document it in `ui-style-guide.md`. Do not independently style Add Folder and Rename Folder.

Validation:

- trim leading/trailing whitespace before validation and persistence;
- reject a blank/whitespace-only name;
- reject a duplicate folder name after trimming and case-insensitive comparison;
- if validation fails, keep the dialog open and present the error through the shared input/error treatment rather than silently closing;
- do not invent an unnecessarily small arbitrary name limit; display long folder names as one line with end ellipsis in the drawer.

Newly created folders are **unpinned** and empty.

Empty folders remain visible in the expanded Folders accordion until the user explicitly deletes them.

### 4.4 Folder Row Appearance And Expansion

A normal collapsed folder row should read visually like:

```text
World Chats >
```

An expanded folder row should read like:

```text
World Chats v
    Chat A
    Chat B
```

Rules:

- The folder name is one line and end-ellipsized when necessary.
- Normal/unpinned folders do **not** need a decorative leading folder icon.
- Use the same outlined right/down chevron behavior as the top-level Folders accordion.
- Short press on a folder row expands/collapses that folder's chats inline.
- Persist each folder's expanded/collapsed state by stable folder ID.
- Chats displayed inside a folder are **slightly indented** relative to the folder row/top-level chat alignment so the containment is immediately legible.
- Keep the indentation subtle. This is hierarchy, not a giant nested navigation tree.
- There are no nested subfolders.

### 4.5 Folder Sorting And Pinned Folders

Folders are alphabetized for predictable navigation.

Within the expanded Folders area:

1. **pinned folders** appear first, sorted alphabetically;
2. **unpinned folders** appear next, sorted alphabetically.

Use a stable, user-visible alphabetical comparison appropriate to the device locale/case-insensitive display expectations. Do not sort folders by last-used chat time.

Pinning a folder:

- never moves it out of the Folders area;
- does **not** pin every chat inside it;
- does **not** move the folder into Pinned Chats;
- only moves the folder into the alphabetized pinned-folder group at the top of the Folders accordion.

A pinned folder gains the Google Material icon **Folder Special** (`folder_special`) immediately before the folder name.

Example:

```text
[folder_special] World Chats >
```

The pinned folder keeps the same chevron and expand/collapse behavior as an ordinary folder.

Unpinning removes the Folder Special marker and returns the folder to the alphabetized unpinned-folder group.

### 4.6 Folder Long-Press Menu

Long-pressing an individual folder opens the shared compact action popup.

Exact menu order:

1. **Pin** if unpinned, or **Unpin** if pinned
2. **Rename**
3. **Delete**

Use the same shared compact-popup visual system used by gallery image and chat long-press menus. Do not invent a folder-only popup palette, spacing system, or shape.

### 4.7 Rename Folder Dialog

Folder long press -> **Rename** opens the shared name-entry dialog.

**Title:** `Rename Folder`

**Input label:** `Folder Name`

- Prefill the input with the current folder name.
- Select/focus the text using normal Android editing behavior so the user can replace it efficiently.

**Actions, exact order:**

1. **Cancel**
2. **Okay**

Use the **same shared dialog/input style as Add Folder**.

Apply the same trim/blank/duplicate validation rules as Section 4.3.

Renaming changes the folder display name only. It must not recreate the folder, change its stable folder ID, move its chats, alter chat timestamps, or affect AI/memory behavior.

### 4.8 Delete Folder

Folder long press -> **Delete** behaves differently depending on whether the folder contains chats.

#### Empty folder

If the folder contains no chats, removing the folder deletes only the organizational folder record. There is no multi-chat-loss warning because there are no chats to destroy.

#### Folder containing one or more chats

Show the standard destructive confirmation.

**Title:** `Delete Folder?`

**Message, exact wording:**

`Deleting this folder will permanently delete all chats inside it. This cannot be undone.`

**Actions, exact order:**

1. **Cancel**
2. **Okay**

Use `App.MaterialAlertDialog` and the approved centered cancel-first two-action layout/style.

`Okay` confirms the intent to delete the folder and all chats owned by its membership. It is **not** permission to bypass generated-image protection.

Folder deletion is a multi-chat deletion operation and must route through the same centralized chat/image deletion policy defined in `image-gallery-spec.md`:

- if **Delete Images with Chat?** is Off, generated images survive according to that spec;
- if the setting is On and the affected chats own generated images, the folder operation must present the equivalent aggregate image choice required by the existing policy before committing destructive image work;
- do **not** display one repetitive image-deletion dialog for every chat in the folder;
- locked generated images survive regardless of folder deletion or image setting;
- shared/referenced images follow origin-ownership rules;
- if an image is deliberately deleted, other chat references retain their historical message and render the missing-image placeholder.

If an additional image-choice step is required after the user presses Okay, cancelling that step cancels the **entire folder deletion**. Do not delete the folder/chats first and ask about images afterward.

The folder record should not disappear while some of its chats remain because a partial deletion failed. Treat folder + contained-chat deletion as one logical coordinated operation and surface/log technical failure through the app's established error handling rather than silently leaving an incoherent hierarchy.

### 4.9 Move A Chat To A Folder

Add **Move to Folder** to the saved-chat long-press popup.

Exact drawer chat long-press menu order becomes:

1. **Pin** / **Unpin**
2. **Move to Folder**
3. **Delete**

Selecting **Move to Folder** opens a compact folder chooser containing:

- **No Folder**;
- available folders, using the same pinned-first then alphabetical folder ordering used by the Folders accordion.

The current folder assignment should be identifiable in the chooser using the app's shared selected/check treatment. Selecting the current assignment is a harmless no-op.

Moving a chat:

- changes only its folder assignment metadata;
- never copies the chat;
- never changes the chat ID;
- never changes message history;
- never changes memory, summarization, Includes, model/provider, images, or other chat semantics;
- must be reflected the next time the drawer hierarchy refreshes.

If the folder list grows beyond what a tiny popup can display comfortably, use an appropriate shared scrollable selector/dialog rather than clipping folder names or making actions unreachable. Do not turn this into a full folder-management screen unless separately approved.

### 4.10 Interaction Between Chat Pinning And Folder Membership

Chat pinning and folder membership are independent.

A chat may simultaneously:

- belong to a folder; and
- be pinned.

While a chat is pinned:

- display it in the top-level **Pinned Chats** section;
- do **not** duplicate the same chat a second time inside its expanded folder;
- retain its folder assignment invisibly/durably.

When that chat is unpinned:

- if it has a folder assignment, it returns to that folder;
- if it has no folder assignment, it returns to the ordinary unfiled chat list.

Moving a pinned chat to another folder changes its retained folder assignment but does not remove it from Pinned Chats until the user unpins it.

### 4.11 Folder Chat Ordering

- The Pinned Chats section keeps the approved pinned-chat ordering: **last used, newest first**.
- Folder membership does not change the existing ordinary chat ordering inside each folder.
- Unfiled unpinned chats keep the existing ordinary chat ordering.
- Moving a chat into/out of a folder must not fake a newer `last used` timestamp merely because organization changed.

### 4.12 Current Chat Highlighting

The current saved chat receives the same restrained selected treatment whether it appears:

- in Pinned Chats;
- inside an expanded folder; or
- in the unfiled chat list.

Do not use a different current-chat styling merely because the row is nested under a folder.

If the containing accordion/folder is collapsed, the hidden chat does not need to force the folder open automatically unless a later owner-approved behavior says otherwise.

---

## 5. Search Screen

Search opens as its own full screen/window.

### 5.1 Header

Use the existing shared header family:

- `Widget.App.ActionBar` for the header container;
- `Widget.App.ActionBar.Title` for the title;
- header text: **Search**;
- **no visual back button and no trailing header action buttons**.

The Search screen still participates in normal system Back/gesture navigation.

### 5.2 Search Box And Quick Options

- Place the search box directly under the header.
- Its visual style should match/reuse the current Chats-list search system (`bg_search`, `field_search`, `btn_search`, `ic_search`) through shared/theme-ready styling.
- Opening Search focuses the field and opens the keyboard. A nonblank query searches as the user types; no submit step or separate options screen is required. The keyboard Search/Enter action may dismiss the keyboard but must not be required to run the query.
- Put two plain checkbox options directly below the field, using the shared `Widget.App.CheckOption.*` family rather than button/tile styling:
  1. **Whole Words**
  2. **Match Case**
- Both options default **Off** every time a new Search screen is opened. Preserve the current query and option values across that screen's activity/configuration recreation, but do not silently turn either option into a persistent app-wide default.
- Changing either option reruns the current query immediately.
- Trimming the field to empty clears the results. Do not populate unrelated recent searches or suggestions in the empty state.

### 5.3 Authoritative Matching Semantics

The old Chats-list implementation only did a case-insensitive substring check against chat titles. It is **not** the behavior to preserve. The owner-approved Search behavior is:

- Search both saved chat titles and persisted, user-visible user/assistant message text.
- Default matching is **case-insensitive token-prefix matching**. A query token must begin at a Unicode word boundary. It may match the beginning of a longer word, but never the middle of one.
- **Whole Words** changes token-prefix comparison to complete-token equality.
- **Match Case** makes the same comparison case-sensitive. It does not imply Whole Words.
- With more than one query token, every token must match within the same title or the same message result. Their order need not match. Boolean operators, wildcard syntax, and quoted-phrase syntax are not part of this phase; typed punctuation is data/separation, not executable query syntax.
- Use Unicode-aware word boundaries. Do not implement Whole Words with ASCII-only `\b`, spaces, or a hand-written English regex.

Required truth table for the query `search`:

| Options | Must Match | Must Not Match |
| --- | --- | --- |
| Default | `search`, `Search`, `searching` | `research` |
| Whole Words | `search`, `Search` | `searching`, `research` |
| Match Case | `search`, `searching` | `Search`, `research` |
| Whole Words + Match Case | lowercase whole-token `search` | `Search`, `searching`, `research` |

Searchable message text is the text the chat presents as the persisted user or assistant message. Do not index hidden Includes/document bodies, internal file directives, generated-image paths or bytes, provider diagnostics, error/debug fields, reasoning metadata, system/settings prompts, transient progress/confirmation rows, or any other non-message metadata merely because it shares the history map.

Search continues to cover **all saved chats**, including chats inside collapsed folders and chats whose Folders accordion is collapsed. Folder membership must never remove a chat from Search, and Search must not be limited to currently inflated/visible drawer rows. Folder names are not search targets in this phase.

### 5.4 Results, Ranking, And Message Navigation

- A matching title produces one chat-title result. A matching message produces one message result; multiple matching messages in one chat remain separately reachable.
- Every result identifies the chat. A message result also shows a short plain-text context snippet with the actual matched range(s) highlighted and shows the stored message date when one exists. Do not invent a message timestamp for legacy rows that have none. A title result may use the chat's last-used date.
- The snippet is match context, not the removed unrelated first-message preview. Collapse display whitespace safely and never interpret arbitrary result text as HTML.
- Rank an exact full-title match first, then other title token matches, then exact-token message matches, then longer token-prefix message matches. Use full-text relevance within those classes and recency only as a deterministic tie-breaker; do not let recency bury a clearly stronger textual match.
- Selecting a title result opens the saved chat normally. Selecting a message result opens that saved chat at the matching message and gives the target a restrained temporary theme-ready emphasis so the user can identify it.
- Message navigation must use a stable message ID when one exists. For a legacy result without one, verify its saved ordinal/fingerprint against the authoritative history before scrolling. If the result became stale because the message was edited/deleted, open the chat normally instead of crashing, scrolling to an unrelated message, or silently manufacturing a match.
- Search results reuse the approved flat chat identity/optional-metadata presentation from Section 6, extended only with the required match snippet/date. They do not inherit the old card layout, swipe gestures, bulk-selection UI, or unrelated first-message preview.

### 5.5 Index Readiness, Recovery, And Privacy

- Message search is backed by a derived encrypted on-device index. Search must not send queries or conversation text to a network service.
- First-use/legacy indexing, rebuilding, and reconciliation run off the main thread. While no complete generation is available, show an explicit preparing/rebuilding state rather than empty results that pretend no matches exist.
- If specific locked/corrupt chats cannot be indexed, any available results must be accompanied by an explicit incomplete/unavailable state. Never silently represent skipped chats as no matches.
- Keep a permanent **Rebuild Search Index** recovery action. Rebuilding may discard and regenerate the derived index, but it must never alter or delete authoritative chat histories.
- Ordinary queries read the index and lightweight chat metadata; they do not parse every full conversation. The index may be paged internally, but scrolling must continue loading results automatically rather than imposing a visible Load More button or permanently hiding older matches.

---

## 6. Chat List Presentation

Reuse the existing chat-list data/behavior, but **do not copy the old chat-list visual design**.

### 6.1 Flat Row Structure

- Chat entries are **not cards, pills, or tiles**.
- Do not put a filled rounded rectangle/elevation behind each chat row.
- Separation comes from whitespace and typography.
- Remove the old unrelated first-message preview/snippet line from drawer rows. Search message results are the explicit exception: they show only the matching context required by Section 5.4.
- Chat name is one line, end-ellipsized when needed.
- Chat-name text is only slightly larger than ordinary body text and is controlled by a shared style.
- Leave approximately one full text-line of empty vertical rhythm between chat entries.
- Chats nested under folders use this same row design plus the subtle indentation from Section 4.4.

### 6.2 Existing Optional Metadata Must Survive

Preserve:

- the existing model-name display preference (`hide_model_names` / positive Show Model Names UI);
- **Show Memory Status on Chat List** and its existing status meanings.

When a preference is Off, omit its line completely and reclaim the space.

Memory-status display remains display-only. Drawer/folder work must not change memory processing.

Optional metadata uses subordinate theme-ready typography.

### 6.3 Companion Image Preference

Add/retain the temporary Chat Settings toggle:

**Show Companion Images in Chat List**

Default: **Off**.

When Off:

- reserve no empty image column.

When On:

- use a two-column row;
- companion/profile image on the left;
- title + enabled metadata on the right;
- vertically center the image against visible text;
- image approximately the visual height of two chat-name lines and not smaller than the current main chat-list image;
- do not stretch it when metadata adds more text;
- do not right-align/place image after the text.

### 6.4 Pinned Chats

Pinned chats remain a distinct top-level section in the scrollable middle.

- Pinned chats appear before ordinary unfiled chat rows and outside the Folders accordion.
- Sort pinned chats by **last used**, newest first.
- Use the Google **Bookmark** icon as the pin marker.
- Companion images Off: small Bookmark immediately beside the title.
- Companion images On: very small Bookmark overlay on lower-right of companion image.
- Reset bookmark/overlay state correctly during row recycling.
- A pinned chat that belongs to a folder appears only once, in Pinned Chats, while retaining its folder ID as defined in Section 4.10.

### 6.5 Current Chat State

- The current saved chat needs a visible selected state without recreating a filled rounded tile.
- Use a restrained theme-ready marker/accent/text treatment.
- Blank startup chat has no saved row highlight until it becomes a saved/current chat under existing persistence behavior.

---

## 7. Drawer/Folders Loading And Performance

The hierarchy must remain responsive with long histories and many folders.

### 7.1 Lightweight Index, Not Full Conversations

Build the drawer from lightweight metadata required for identity/order/display, including where applicable:

- chat ID/name;
- chat pinned state;
- nullable folder ID;
- last-used ordering metadata;
- optional model/provider display data;
- companion-image reference;
- memory-status display state when enabled;
- folder ID/name/pinned state;
- persisted folder/Folders accordion expansion state.

**Do not load/parse complete conversation histories merely to build the drawer or folders.** An explicit off-main Search index build/rebuild may scan authoritative histories; opening the drawer and running an ordinary Search query may not.

Do not perform tokenization, summarization, attachment loading, generated-image decoding, or transcript parsing simply to open the drawer.

Folder rename/move/pin should not require rewriting conversation history.

### 7.2 Virtualized Visible-Row Work

- Use RecyclerView or equivalent virtualization for the scrollable hierarchy.
- Do not inflate hundreds of chat rows at once.
- Companion images load only for bound/visible rows using the existing image-loading mechanism.
- Row binding must reset companion image, bookmark, selection marker, metadata visibility, indentation, folder-related state, and all tint/visibility state so recycled rows cannot leak presentation across chats.

### 7.3 Expensive Optional Metadata

- Memory-status SQLCipher/database work remains off the UI thread and only runs when the display setting is enabled.
- Async metadata updates must be keyed to stable chat identity so recycled/moved rows do not receive stale information.

### 7.4 No Arbitrary Partial Truth

Prefer a lightweight navigation index plus a complete derived encrypted Search index covering the whole accessible chat set so:

- Search sees all chats;
- pinned ordering is complete;
- folder membership is complete;
- folder deletion knows which chats actually belong to the folder.

Do not show only an arbitrary first page if that makes older chats disappear from Search or folder membership. Search may retrieve bounded pages internally only when subsequent pages load automatically and the full corpus remains reachable.

Future storage paging is allowed only if global semantics stay correct. No visible Load More control is required by this spec.

---

## 8. App Launch Behavior

Ordinary app launch immediately presents a **blank chat using the current blank-chat presentation**, enhanced by the Section 3 Chat/Playground selector.

- Default mode is **Chat**.
- Do not reopen the previously viewed conversation on startup.
- Do not first land on the old Chats tab.
- Reuse the current blank/new-chat persistence lifecycle.
- Do not invent a second chat-storage lifecycle solely for startup.

---

## 9. Image Gallery Integration

`image-gallery-spec.md` remains the authority for generated-image gallery behavior, image locking, image-safe chat deletion, and gallery management.

This drawer spec changes only the gallery's navigation placement:

- Image Gallery is a scrollable-middle top-level row;
- it appears above Folders;
- it uses `ic_image`;
- it is no longer grouped with Playground/Settings at the fixed bottom.

Folder deletion must obey all image-safety rules described in Section 4.8 and `image-gallery-spec.md`.

---

## 10. Lower Navigation And Parked Decisions

- **Playground:** no permanent drawer destination. It is selected for a blank new conversation through the Section 3 Chat/Playground segmented selector. Preserve existing Playground semantics unless separately approved.
- **Settings:** fixed bottom and always drawer-accessible. Future Settings transition/animation changes remain parked unless separately approved.
- **Companions / Characters:** no permanent drawer destination. Existing approved access paths remain.
- **Projects:** there is still no Projects behavior. Folders are deliberately not Projects and must not acquire memory/context isolation by implication.

---

## 11. Shared Popup And Dialog Styling

This design introduces several small management interactions. They must be centrally styleable.

### 11.1 Compact Action Popup

Use one shared compact-popup appearance for:

- gallery image long press;
- drawer chat long press;
- Folders long press (`Add Folder`);
- individual folder long press (`Pin/Unpin`, `Rename`, `Delete`).

If no named shared compact popup style/pattern exists when implementation begins, add one and document it in `ui-style-guide.md`.

### 11.2 Shared Name-Entry Dialog

Use one shared name-entry layout/style for:

- **Add Folder**;
- **Rename Folder**;
- future equivalent simple rename/name dialogs where appropriate.

The shared pattern owns text-field appearance, padding, typography, error presentation, and dialog spacing so later visual changes do not require editing every feature separately.

Do not hard-code folder-specific input colors, corner radii, or spacing in Activity/Fragment code.

---

## 12. Implementation Staging Principles

A later implementation plan should map these product rules onto **current `main`** before coding. The exact file/method structure may evolve while unrelated Summarizer/Compact work is active.

The product behavior in this spec should not be weakened because current code differs.

Recommended dependency order for the implementation plan to evaluate:

1. durable lightweight folder metadata + chat folder assignment + migration/defaults;
2. shared drawer hierarchy and fixed/scrollable zones;
3. Folders accordion, folder rows, persistence, pin/sort behavior;
4. chat Move to Folder + folder Add/Rename/Delete management and shared deletion coordinator integration;
5. New Chat/Search compact fixed-top row;
6. Chat/Playground pre-send mode selector and durable mode locking after first send;
7. Search/all-chat hierarchy integration and performance hardening;
8. startup into blank Chat-mode conversation;
9. retire old navigation only after all approved destinations remain reachable;
10. integration/regression sweep for voice, streaming, IME, Includes, images, deletion, and state restoration.

This is guidance for planning, not permission to skip a fresh inspection of current `main` before implementation.

---

## 13. Existing Safety Rules That Still Bind

- Switching to a saved chat from the drawer should preserve the existing one-chat-per-ChatActivity lifecycle unless a later owner-approved architecture changes it. Do not introduce risky in-place live-chat swapping as incidental drawer work.
- Drawer refresh must reflect auto-naming/renaming, chat pin state, folder membership, folder rename/pin state, and last-used ordering rather than caching stale identities.
- Drawer work must not add competing IME/inset listeners to ChatActivity.
- Drawer must handle status/system-bar insets correctly while still meeting the approved 100% visual width requirement.
- The removed `btn_debug_log` top-bar shortcut must not be restored. Logs remains available through the current chat overflow menu.
- Drawer, folders, mode selector, dialogs, Search, and selection visuals must use theme/style attributes and preserve the future custom-theme architecture.
- Search/new drawer UI uses Title Caps for visible control/action labels and accessibility names according to `ui-style-guide.md`.
- Folder organization must never alter memory, lorebook, Summarizer, Compact, Includes, attachment, model/provider, image-generation, or token-accounting behavior merely because a chat moved.

---

## 14. Minimum Acceptance / Regression Coverage

At minimum verify:

1. Drawer opens full width from the left using the approved double-chevron and has no edge-swipe opening.
2. Soul Phosphor + return chevron and the New Chat/Search row remain fixed while the middle scrolls.
3. New Chat is a wide button using the Google Contract icon; Search is an adjacent icon-only magnifying-glass button with accessibility name Search.
4. Scrollable middle begins with Image Gallery, then Folders, then Pinned Chats/unfiled chat content.
5. Settings remains fixed bottom and Playground is absent from the drawer.
6. A blank/new chat defaults to Chat and shows the centered Chat/Playground segmented pill beneath the header.
7. The selected Chat/Playground capsule visibly slides and resizes appropriately without hard-coded palette values.
8. Switching modes before first send preserves draft, attachments, Includes, model/provider, and unrelated composer state.
9. First successfully committed user message hides the selector and durably fixes that conversation's chosen mode; failed pre-commit send does not hide it.
10. Folders is an accordion: short press expands/collapses, right chevron becomes a downward V-shaped chevron, and state persists.
11. Long press Folders opens the shared compact popup with Add Folder.
12. Add Folder uses `Add Folder` / `Folder Name` / `Cancel` / `Okay` through the shared name-entry dialog style.
13. Blank and duplicate normalized folder names are rejected without closing the dialog.
14. Empty folders remain visible.
15. Folder rows use one-line ellipsized names, right/down chevrons, and no normal leading folder icon.
16. Chats inside an expanded folder are slightly indented and no nested folders are possible.
17. Pinned folders appear first alphabetically, unpinned folders next alphabetically.
18. Pinned folder uses Google `folder_special` before its name and remains inside the Folders area.
19. Folder long press menu is exactly dynamic Pin/Unpin, Rename, Delete in that order.
20. Rename Folder uses the same shared text-entry dialog style, preserves stable folder identity, and does not move/modify chats.
21. Deleting a nonempty folder shows `Delete Folder?` with exact message `Deleting this folder will permanently delete all chats inside it. This cannot be undone.` and actions Cancel / Okay.
22. Cancelling folder deletion leaves the folder/chats untouched.
23. Folder deletion routes through the image-safe multi-chat deletion coordinator; locked generated images survive and no per-chat dialog storm occurs.
24. If a required aggregate image-choice step is cancelled, the folder and all chats remain.
25. Saved-chat long press menu is dynamic Pin/Unpin, Move to Folder, Delete.
26. Move to Folder offers No Folder plus folders in the approved pinned/alphabetical ordering and changes only organizational metadata.
27. A chat belongs to at most one folder.
28. A chat may be both folder-assigned and pinned; while pinned it appears only in Pinned Chats and returns to its retained folder when unpinned.
29. Pinning a folder never pins its chats; pinning a chat never pins its folder.
30. Chat current-selection treatment remains correct in Pinned Chats, folder children, and unfiled rows.
31. Search finds titles and persisted visible message text regardless of folder assignment or accordion expansion state; default `search` matches `Search` and `searching` but not `research`.
32. Existing model-name, memory-status, and companion-image display preferences continue to work in all chat locations.
33. RecyclerView recycling never leaks folder indentation, bookmark state, companion images, selected state, or optional metadata to another row.
34. Drawer construction and ordinary Search queries do not load every full conversation, tokenize histories, summarize chats, or decode full-resolution generated images; only an explicit off-main Search index build/rebuild may scan histories.
35. Existing chats migrate as unfiled with no guessed folder membership.
36. Folder rename/move/pin does not change chat last-used timestamps, transcript data, memory, Summarizer/Compact state, Includes, or provider settings.
37. Ordinary app launch opens a blank Chat-mode conversation rather than the last viewed chat.
38. Opening/closing drawer preserves voice/hands-free, streaming, draft, keyboard/IME, pending attachments, Includes, selected model/provider, and blank-chat mode state.
39. Search opens with the field focused, runs while typing, and keeps Whole Words and Match Case directly on the same screen with both Off by default.
40. The four `search` truth-table combinations in Section 5.3 pass, including Unicode-aware word-boundary coverage and safe punctuation/query escaping.
41. Title matches and individual message matches are ranked deterministically; message results show highlighted matching context and navigate to the verified message rather than merely opening the chat at an arbitrary location.
42. New message IDs remain immutable through edits, while legacy ordinal/fingerprint navigation verifies before scrolling and safely falls back when stale.
43. First-use indexing, interrupted rebuild, rename/edit/delete, restore/import, and index corruption never produce silently stale/partial results or modify authoritative chat histories; Rebuild Search Index remains available.
