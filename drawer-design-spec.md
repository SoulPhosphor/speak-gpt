# Drawer Design Specification

**Status:** Owner-approved drawer/navigation design, August 23, 2026.

**Authority:** This file is the current product/UI authority for the drawer and supersedes conflicting drawer-specific details in `ui-redesign-plan.md` Section 5 and its Phase 3 summary. In particular, it supersedes the older no-header drawer, inline drawer search, hamburger control, last-chat startup, and any partial-width drawer assumptions. The general repository safety, theme, voice, lifecycle, and shared-style rules in `ui-redesign-plan.md`, `ui-style-guide.md`, `ui-style-adoption.md`, and `CLAUDE.md` still apply.

## 1. Drawer Geometry And Open/Close Controls

- The drawer is a **full-width panel**. It occupies 100% of the available screen width while open.
- It enters from the **left edge of the screen and opens toward the right**, covering the chat while open.
- Full width is a product requirement. Do not silently accept a narrower framework default or leave a permanent strip of the chat visible because a standard drawer component applies a default margin.
- The **existing/approved double-chevron control at the top of the chat** opens the drawer. Do **not** replace it with a hamburger icon.
- The drawer header contains a **right-facing double-chevron** aligned to the right. Tapping it closes the drawer and returns to the chat state that was underneath it.
- **Do not add an edge-swipe or drag gesture to open the drawer.** Navigation between Chat and the drawer is intentionally controlled by the two double-chevron buttons plus normal system Back behavior.
- Returning from the drawer must preserve whichever chat state the user had before opening it: an existing saved chat if one was open, or the current blank/empty chat if that was open. Closing the drawer must not create, replace, or switch chats.
- System Back while the drawer is open should close the drawer first and return to the underlying chat state.
- Opening or closing the drawer must not disturb mic state, voice/hands-free state, streaming state, draft text, keyboard/IME state, pending attachments, Includes, selected model/provider, or per-chat preferences.

Unless the owner explicitly specifies otherwise, icons referenced by this drawer/search design come from **Google Icons / Material Symbols**.

## 2. Fixed Top, Scrollable Middle, Fixed Bottom

The drawer has three vertical zones. This structure is a product requirement.

### 2.1 Fixed Top Control Block

All three rows below remain locked at the top while the chat list scrolls beneath them:

1. **Soul Phosphor** left aligned + Google double-chevron facing right aligned to the right.
2. **New Chat**.
3. **Search**.

- The right-facing double-chevron returns to the underlying current chat as described above.
- Header colors, typography, icon tint, and background must be theme-ready and use the shared style/theme system. Do not hard-code drawer colors.
- **Soul Phosphor, New Chat, and Search do not scroll away.** They remain visible and interactive even when the user has scrolled deep into a long chat list.
- Scrolling must not cause chat rows to draw over the fixed top controls or make those controls disappear.

### 2.2 Scrollable Middle

- The **chat list is the scrolling middle region**.
- It scrolls vertically between the fixed top controls and the fixed bottom navigation.
- The scrollable region must remain usable with long histories, including hundreds of chats.
- The list must not push either fixed region off-screen.

### 2.3 Fixed Bottom Navigation

The lower navigation stays locked to the bottom of the drawer rather than appearing after the final chat row.

- **Playground** remains fixed at the bottom while it exists.
- **Settings** remains fixed at the bottom.
- Users must never have to scroll through their chat history to reach Playground or Settings.
- Playground and Settings do not participate in the chat-list scroll position.
- If Playground is removed in a later owner-approved change, Settings remains fixed at the bottom by itself unless a later navigation design replaces it.
- Fixed-bottom styling, spacing, icon tint, and background must use shared/theme-ready styles rather than hard-coded colors.

## 3. Drawer Contents And Navigation

The visual hierarchy is:

1. Fixed top: **Soul Phosphor** + right-facing double-chevron.
2. Fixed top: **New Chat**.
3. Fixed top: **Search**.
4. Scrollable middle: chat list.
5. Fixed bottom: **Playground** (while it exists) and **Settings**.

There is **no Projects section yet**. Do not invent a Projects heading, placeholder, empty area, or project behavior. For now, the chat list begins immediately after the fixed Search row.

**Companions / Characters do not receive a permanent drawer row.** They remain reachable through their existing paths, including Quick Settings. Do not add them to the drawer merely because the older navigation plan listed Characters as a top-level destination.

### 3.1 New Chat

- Use the Google icon named **Contract**.
- Place the icon first, with the words **New Chat** immediately after it.
- The visible label uses Title Caps: **New Chat**.
- Tapping **New Chat** performs the app's **existing New Chat behavior exactly as it works now**. This redesign is not an invitation to reinterpret, replace, or invent a new New Chat lifecycle or UX.
- Preserve the current New Chat appearance, flow, persistence semantics, and any existing dialog behavior unless a separate owner-approved change explicitly modifies them.

### 3.2 Search

- Use the existing magnifying-glass/search icon already present in the app where practical (`ic_search`).
- Place the icon first, with the word **Search** immediately after it.
- The visible label uses Title Caps: **Search**.
- Search is a **navigation row**, not an inline search field inside the drawer.
- Tapping Search opens the dedicated Search screen described in Section 4.

## 4. Search Screen

Search opens as its own full screen/window.

### 4.1 Header

The Search screen uses the existing shared header family and does not invent a new one-off header.

Use:

- `Widget.App.ActionBar` for the header container.
- `Widget.App.ActionBar.Title` for the title.
- Header text: **Search**.
- **No back button and no trailing header action buttons.** The header contains the title only.

`Widget.App.ActionBar` and `Widget.App.ActionBar.Title` already exist in `themes.xml`; this screen composes those existing shared pieces without adding `Widget.App.ActionBar.BackButton`, `SecondaryButton`, or other header controls.

The Search screen still participates in normal system Back/gesture navigation even though the visual header has no buttons.

### 4.2 Search Box And Search Behavior

- Place the search box directly under the header.
- Its visual style should match the search box currently used on the Chats list/main chat-list screen, including the text field plus magnifying-glass action.
- The current implementation reference is the Chats-list search control (`bg_search`, `field_search`, `btn_search`, and `ic_search` in `fragment_chats_list.xml`).
- Reuse or convert that appearance through the current shared-style/theme system at implementation time. Do not copy legacy hard-coded colors into a new screen.
- **Do not redesign or expand Search in this drawer phase.** Reuse the app's existing chat-search behavior and matching semantics rather than inventing full-message search, semantic search, or new filtering rules.
- Existing search logic is the product behavior authority for what constitutes a match during this phase.
- The new Search screen may change *where* the existing search experience lives, but not what it searches or how matches are determined unless separately approved later.
- Search results use the same approved flat chat-row presentation described in Section 5.

## 5. Chat List Presentation

Reuse the existing chat-list data and behavior, but **do not copy the old chat-list visual design**.

### 5.1 Flat Row Structure

- Chat entries are **not cards, pills, or tiles**.
- Do **not** put a filled background, rounded rectangle, elevation, or individual surface color behind each chat entry. The drawer surface remains visible behind the rows.
- Separation comes from whitespace and typography rather than colored containers.
- The old message-preview/snippet line is removed from the drawer and Search results. Do not show `chat_first_message` or an equivalent conversation excerpt under the title.
- The chat name is **one line**, ellipsized at the end when needed.
- The chat-name text is only slightly larger than ordinary reading/body text. It should be visibly a title without becoming oversized. Keep the exact size in the shared style system rather than hard-coding a drawer-only value.
- Leave approximately **one full text-line of empty vertical space** between the bottom of one entry's final visible line and the next chat entry. The intended rhythm is comparable to a blank paragraph line, not a tightly packed list margin.

### 5.2 Existing Optional Metadata Must Survive

Simplifying the row does **not** remove the existing chat-list display preferences or their semantics.

- Preserve the existing model-name display preference (`hide_model_names` / the positive Show Model Names UI). When model names are enabled, show the existing model/provider line underneath the chat title. When disabled, omit that line completely and reclaim the space.
- Preserve **Show Memory Status on Chat List**. When enabled, show the existing memory review/status line underneath the title and any enabled model line. Preserve the existing state meanings, including waiting for review/pending, partially archived, archived, and excluded. When disabled, omit the line completely and reclaim the space.
- The memory-status display setting is display-only. Drawer work must not enable, disable, archive, review, or otherwise change memory processing merely because the line is shown or hidden.
- Do not replace these existing preferences with new drawer-only duplicates. The drawer and Search results should reflect the same stored settings.
- Optional metadata uses quieter/subordinate typography than the chat title and remains theme-ready through shared styles/theme attributes.

### 5.3 Companion Image Preference

Add a temporary Chat Settings toggle labeled:

**Show Companion Images in Chat List**

- Default: **Off**.
- Chat Settings is only the temporary location for this preference. It may be moved later without changing its meaning or stored behavior.

When **Show Companion Images in Chat List** is Off:

- Do not reserve an empty image/icon column.
- The title and any enabled metadata use the available row width.

When **Show Companion Images in Chat List** is On:

- Use a simple two-column row.
- The companion/profile image is in the **left column**, along the left side of the row.
- The text block is in the **right column** and contains the one-line chat title plus whichever optional metadata lines are enabled.
- Vertically center the image against the visible text block.
- The image should be approximately the visual height of two chat-name lines and must not be smaller than the companion image currently shown in the existing main chat list.
- Do not stretch the image simply because optional metadata makes the text block taller.
- Exact image size, column width, and local spacing may use implementation judgment to preserve these visual relationships.
- Do not right-align the image and do not place it after the chat text.

### 5.4 Pinned Chats

Pinned chats remain a supported chat-list behavior.

- **All pinned chats appear before all regular/unpinned chats.**
- Within the pinned group, sort by **last used**, newest first and oldest last.
- Do not intermix pinned and unpinned chats merely because their recent-use timestamps overlap.
- Regular/unpinned chats keep the app's existing ordering unless a separate owner-approved change says otherwise.
- Use the Google **Bookmark** icon as the visible pin marker for this new presentation.
- When companion images are **Off**, show a small Bookmark icon immediately beside the pinned chat's title. It should read as subordinate metadata, not as a second large leading icon.
- When companion images are **On**, place a **very small Bookmark icon overlapping the lower-right corner of the companion image**. The bookmark is an overlay on the image container, not an additional column that pushes the title farther right.
- The overlay must remain legible against arbitrary companion images. Use a theme-ready treatment such as an appropriate contrasting tint/backplate if required, but keep it visually tiny and subordinate.
- A recycled list row must never retain a bookmark from a previously bound pinned chat or lose the bookmark for a pinned chat.

### 5.5 Current Chat State

- The currently open saved chat still needs a visible selected state, but **do not recreate the old filled rounded chat tile** just to show selection.
- Use a restrained, theme-ready non-card selection treatment, such as an accent/marker or text treatment, using the approved selected/drawer-selected theme role. Exact treatment may use implementation judgment as long as it is clearly visible and does not become a per-row background tile.
- A blank startup chat has no saved chat row to highlight until it becomes a saved/current chat according to the app's existing behavior.
- Drawer chat rows must remain palette/theme ready. Use shared typography, theme attributes, and drawer zones rather than hard-coded colors.

## 6. Chat List Loading And Performance

The drawer must remain responsive with long chat histories. This phase does **not** require a visible pagination or "Load More" UX for ordinary histories such as hundreds of chats.

### 6.1 Lightweight Index, Not Full Conversations

- Build the drawer from the lightweight data required to identify, order, and display chats, such as chat ID/name, pinned state, last-used ordering data, optional model/provider display data, companion-image reference, and memory-status display state when enabled.
- **Do not load or parse complete conversation histories merely to populate the drawer.**
- Do not perform full-message tokenization, transcript parsing, summarization, attachment loading, or other conversation-heavy work as part of opening or scrolling the drawer.
- Loading the drawer must not block ChatActivity startup or the UI thread on expensive per-chat work.

### 6.2 RecyclerView / Visible-Row Work

- Keep the chat list virtualized with RecyclerView or an equivalent existing list mechanism that only creates/binds visible and nearby rows. Do not inflate hundreds of chat row views at once.
- Companion images should be loaded through the existing image-loading approach for bound/visible rows rather than eagerly decoding every companion image in the history.
- Row recycling must correctly reset companion image, bookmark, selected state, model/provider line, memory-status line, and all visibility/tint state on every bind.
- Scrolling quickly through a long list must not cause a recycled row to inherit another chat's image or metadata.

### 6.3 Expensive Optional Metadata

- Preserve the existing rule that memory-status work happens off the main thread and only when **Show Memory Status on Chat List** is enabled. Do not move SQLCipher/database work into row binding or the UI thread.
- If optional metadata is not immediately available, the UI may populate/update that metadata asynchronously using the app's established behavior, but it must not reorder the wrong chat or attach stale results to a recycled row.
- Turning an optional display setting Off should avoid doing work whose sole purpose was rendering that hidden metadata where practical.

### 6.4 No Arbitrary Partial Truth

- For the currently approved scale, prefer knowing the lightweight index for the whole chat list so pinned ordering and the existing Search behavior operate over the full set of chats.
- Do **not** silently show only the first arbitrary page of chats if that would make older chats invisible to Search, produce incomplete pinned ordering, or imply that missing chats do not exist.
- If future real-world scale proves that storage-level paging is necessary, it may be added as an implementation optimization only if global pinned ordering, existing Search semantics, chat accessibility, and the fixed navigation structure remain correct. That future optimization does not require a visible "Load More" button unless separately approved.

## 7. App Launch Behavior

Ordinary app launch should immediately present a **blank chat using the same blank-chat presentation the app has now**.

- Do not reopen the previously viewed conversation on startup.
- Do not first land on the old Chats tab.
- Reuse the current blank/new-chat behavior and persistence semantics.
- Do not invent a second chat-storage lifecycle solely to create the startup blank chat.

## 8. Lower Navigation And Parked Decisions

- **Playground:** keep a fixed-bottom link to the current Playground destination exactly as it exists at implementation time. Do not rehost, refactor, restyle, or otherwise change Playground as part of drawer work. The owner may remove Playground entirely in a separate future decision; drawer implementation must not depend on Playground internals.
- **Settings:** keep Settings fixed at the bottom and drawer-accessible, but the future transition/animation replacing the current gear-expansion behavior is **parked**. Do not redesign that transition as an incidental part of drawer implementation unless separately approved.
- **Companions / Characters:** no permanent drawer destination. Existing access paths remain.

## 9. Implementation Staging

Preserve the existing staged rollout principle rather than landing the structural navigation change all at once.

### Step A: Drawer In ChatActivity

- Add the full-width drawer around the existing ChatActivity presentation without rewriting message rows or chat behavior.
- Preserve load-bearing chat/root IDs and transition behavior unless a directly required navigation-control change is made deliberately and all references are updated together.
- Wire the chat's approved **double-chevron** control to open the drawer.
- Do **not** add a hamburger button.
- Do **not** add edge-swipe opening.
- Keep the entire top control block (Soul Phosphor + return chevron, New Chat, Search) fixed.
- Keep Playground (while it exists) and Settings fixed at the bottom.
- Only the middle chat-list region scrolls.
- Drawer opens from the left and covers the full available width.
- Drawer right-facing double-chevron closes it and returns to the underlying chat state.

### Step B: Launch Into Blank Chat

- On ordinary app launch, route directly to the existing blank/new-chat presentation.
- Do not remember/reopen the last chat as the startup destination.
- Keep normal drawer navigation capable of opening existing saved chats.

### Step C: Retire Old Bottom Navigation

- Only after Chats, Playground (if still present), Settings, and the other approved navigation destinations are reachable through the drawer or their approved replacement paths should the old bottom navigation be retired.
- Playground is only linked to its **current destination** while it exists. Do not rehost, refactor, or otherwise modify Playground as part of drawer work.

Ship A, then B, then C rather than combining all three into one large structural change.

## 10. Existing Safety Rules That Still Bind

- Switching to a saved chat from the drawer should preserve the existing one-chat-per-ChatActivity lifecycle unless a later owner-approved architecture changes it. Do not introduce in-place live-chat swapping that risks voice, streaming, or per-chat state.
- The drawer's chat list must refresh appropriately so auto-naming/renaming, pinned state, last-used ordering, and other existing list state are reflected rather than caching stale chat IDs or names.
- Drawer work must not add competing IME/inset listeners to ChatActivity.
- The drawer must handle status/system-bar insets correctly while still meeting the approved 100% visual width requirement.
- The removed `btn_debug_log` top-bar shortcut must not be restored. Logs remains available through the current chat overflow menu.
- Drawer and Search visuals must use theme/style attributes and preserve the future custom-theme architecture.
- Search/new drawer UI must use Title Caps for visible control/action labels and accessibility naming strings according to `ui-style-guide.md`.
