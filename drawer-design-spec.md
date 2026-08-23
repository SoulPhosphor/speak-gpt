# Drawer Design Specification

**Status:** Owner-approved drawer/navigation design, August 23, 2026.

**Authority:** This file is the current product/UI authority for the drawer and supersedes conflicting drawer-specific details in `ui-redesign-plan.md` Section 5 and its Phase 3 summary. In particular, it supersedes the older no-header drawer, inline drawer search, hamburger control, last-chat startup, and any partial-width drawer assumptions. The general repository safety, theme, voice, lifecycle, and shared-style rules in `ui-redesign-plan.md`, `ui-style-guide.md`, `ui-style-adoption.md`, and `CLAUDE.md` still apply.

## 1. Drawer Geometry And Open/Close Controls

- The drawer is a **full-width panel**. It occupies 100% of the available screen width while open.
- It enters from the **left edge of the screen and opens toward the right**, covering the chat while open.
- Full width is a product requirement. Do not silently accept a narrower framework default or leave a permanent strip of the chat visible because a standard drawer component applies a default margin.
- The **existing/approved double-chevron control at the top of the chat** opens the drawer. Do **not** replace it with a hamburger icon.
- The drawer header contains a **right-facing double-chevron** aligned to the right. Tapping it closes the drawer and returns to the chat state that was underneath it.
- Returning from the drawer must preserve whichever chat state the user had before opening it: an existing saved chat if one was open, or the current blank/empty chat if that was open. Closing the drawer must not create, replace, or switch chats.
- System Back while the drawer is open should close the drawer first and return to the underlying chat state.
- Opening or closing the drawer must not disturb mic state, voice/hands-free state, streaming state, draft text, keyboard/IME state, pending attachments, Includes, selected model/provider, or per-chat preferences.

Unless the owner explicitly specifies otherwise, icons referenced by this drawer/search design come from **Google Icons / Material Symbols**.

## 2. Drawer Header

The first line of the drawer is a real header and supersedes the earlier no-header decision.

- **Soul Phosphor** is left aligned.
- A Google **double-chevron facing right** is right aligned on the same line.
- The right-facing double-chevron returns to the underlying current chat as described above.
- Header colors, typography, icon tint, and background must be theme-ready and use the shared style/theme system. Do not hard-code drawer colors.

## 3. Drawer Contents, Top To Bottom

The top-level order is:

1. Drawer header: **Soul Phosphor** + right-facing double-chevron.
2. **New Chat**.
3. **Search**.
4. Chat list.
5. Existing lower drawer navigation such as Characters, Playground, and Settings may remain below the chat-list area according to the broader navigation plan.

There is **no Projects section yet**. Do not invent a Projects heading, placeholder, empty area, or project behavior. For now, the chat list begins immediately after Search.

### 3.1 New Chat

- Use the Google icon named **Contract**.
- Place the icon first, with the words **New Chat** immediately after it.
- The visible label uses Title Caps: **New Chat**.
- Tapping the row invokes the app's existing/new-chat flow. Do not redesign unrelated new-chat persistence or dialog behavior merely to implement the drawer.

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

### 4.2 Search Box

- Place the search box directly under the header.
- Its visual style should match the search box currently used on the Chats list/main chat-list screen, including the text field plus magnifying-glass action.
- The current implementation reference is the Chats-list search control (`bg_search`, `field_search`, `btn_search`, and `ic_search` in `fragment_chats_list.xml`).
- Reuse or convert that appearance through the current shared-style/theme system at implementation time. Do not copy legacy hard-coded colors into a new screen.
- The search executes when the user presses the **magnifying-glass button** in the search box.
- After the magnifying-glass button is pressed, matching chats appear underneath the search box.
- Live filtering while the user types is **not required** unless separately approved later.
- Search results should use the same approved chat-row presentation described in Section 5 unless a later Search-specific result design is explicitly approved.

## 5. Chat List Presentation

Reuse the existing chat-list data and behavior, but **do not copy the old chat-list visual design**.

### 5.1 Companion Image Preference

Add a temporary Chat Settings toggle labeled:

**Show Companion Images in Chat List**

- Default: **Off**.
- Chat Settings is only the temporary location for this preference. It may be moved later without changing its meaning or stored behavior.

### 5.2 Toggle Off: Text-Only Rows

When **Show Companion Images in Chat List** is Off:

- Each chat entry shows **only the chat name**.
- Do not show the old snippet/model-label treatment.
- Do not reserve an empty image/icon column.
- The chat name may use up to **two lines**.
- The chat-name text should be slightly larger than ordinary body/message text.
- Keep the exact text size in the shared style system rather than hard-coding a drawer-only value.
- Leave approximately **one full text-line of empty vertical space** between neighboring chat names. The intended visual rhythm is comparable to a blank paragraph line, not a tightly packed list margin.

### 5.3 Toggle On: Companion Image Rows

When **Show Companion Images in Chat List** is On:

- Use a simple two-column row.
- The companion/profile image is in the **left column**, along the left side of the row.
- The chat name is in the **right column** and may still use up to two lines.
- Vertically center the image against the one-to-two-line chat-name text area.
- The image should be approximately the visual height of two chat-name lines.
- It must not be smaller than the companion image currently shown in the existing main chat list.
- Exact image size, column width, and local spacing may use implementation judgment to preserve those visual relationships.
- Do not right-align the image and do not place it after the chat name.

### 5.4 Current Chat State

- Highlight the currently open saved chat using the approved selected/drawer-selected theme role.
- A blank startup chat has no saved chat row to highlight until it becomes a saved/current chat according to the app's existing behavior.
- Drawer chat rows must remain palette/theme ready. Use shared typography, theme attributes, and drawer zones rather than hard-coded colors.

## 6. App Launch Behavior

Ordinary app launch should immediately present a **blank chat using the same blank-chat presentation the app has now**.

- Do not reopen the previously viewed conversation on startup.
- Do not first land on the old Chats tab.
- Reuse the current blank/new-chat behavior and persistence semantics.
- Do not invent a second chat-storage lifecycle solely to create the startup blank chat.

## 7. Implementation Staging

Preserve the existing staged rollout principle rather than landing the structural navigation change all at once.

### Step A: Drawer In ChatActivity

- Add the full-width drawer around the existing ChatActivity presentation without rewriting message rows or chat behavior.
- Preserve load-bearing chat/root IDs and transition behavior unless a directly required navigation-control change is made deliberately and all references are updated together.
- Wire the chat's approved **double-chevron** control to open the drawer.
- Do **not** add a hamburger button.
- Drawer opens from the left and covers the full available width.
- Drawer right-facing double-chevron closes it and returns to the underlying chat state.

### Step B: Launch Into Blank Chat

- On ordinary app launch, route directly to the existing blank/new-chat presentation.
- Do not remember/reopen the last chat as the startup destination.
- Keep normal drawer navigation capable of opening existing saved chats.

### Step C: Retire Old Bottom Navigation

- Only after Chats, Playground, Settings, and the other approved navigation destinations are reachable through the drawer should the old bottom navigation be retired.
- Playground is only linked to its **current destination**. Do not rehost, refactor, or otherwise modify Playground as part of drawer work.

Ship A, then B, then C rather than combining all three into one large structural change.

## 8. Existing Safety Rules That Still Bind

- Switching to a saved chat from the drawer should preserve the existing one-chat-per-ChatActivity lifecycle unless a later owner-approved architecture changes it. Do not introduce in-place live-chat swapping that risks voice, streaming, or per-chat state.
- The drawer's chat list must refresh appropriately so auto-naming/renaming is reflected rather than caching stale chat IDs or names.
- Drawer work must not add competing IME/inset listeners to ChatActivity.
- The drawer must handle status/system-bar insets correctly while still meeting the approved 100% visual width requirement.
- The removed `btn_debug_log` top-bar shortcut must not be restored. Logs remains available through the current chat overflow menu.
- Drawer and Search visuals must use theme/style attributes and preserve the future custom-theme architecture.
- Search/new drawer UI must use Title Caps for visible control/action labels and accessibility naming strings according to `ui-style-guide.md`.
