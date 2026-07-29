# UI Style Adoption Map

This file records which screens currently use the app's shared visual system.

It is separate from `ui-style-guide.md`:

- `ui-style-guide.md` defines the available style families and when to use them.
- this file records where those styles are currently adopted, partially adopted, intentionally custom, legacy, or not yet audited.

This is a current-status map, not a rollout history.

## Status meanings

### Shared

Every repeated visual component on the screen uses the appropriate approved shared style or shared layout.

This includes applicable headers, action icons, buttons, fields, hints, validation text, counters, image treatments, rows, sections, selectors, switches, checkboxes, dialogs, loading states, and spacing patterns.

Feature-specific content and behavior may remain local. A genuinely unique control may remain local while using shared visual styles. A screen does not need to share its whole XML layout to be theme-ready.

### Partial

The screen uses some shared styles but still contains copied visual attributes, an old component pattern, a repeated element with no shared style, or a mixture of shared and local implementations.

A Partial screen must not be copied as a whole-screen reference.

### Unconverted

The screen still uses local or copied visual definitions for repeated components that already have an approved shared style.

### Custom approved

The screen intentionally uses a different approved pattern because its function or structure is materially different.

Custom approved does not mean it should become the reference for ordinary screens.

### Legacy / remove

The component or mechanism is scheduled for removal or replacement.

Do not preserve, expand, restyle, repair for appearance, or create new dependencies on it unless the owner explicitly approves a temporary safety fix.

### Unaudited

The current implementation has not yet been checked against `ui-style-guide.md`, `themes.xml`, and any shared layouts it should use.

Do not guess its status from appearance or old documentation.

## Rules for maintaining this map

- Verify the current XML and relevant code before assigning or changing a status.
- Audit the whole screen, not only the component named in the current task.
- Update this map in the same approved change that converts or intentionally exempts a screen.
- Record the current state only. Do not add dates, rollout stories, old bugs, branches, or completed-work narratives.
- Keep notes limited to the remaining shared-style gap or the reason for an approved exception.
- Do not call a screen Shared merely because it looks similar to another screen or because several shared styles appear in its XML.
- Do not use a Partial, Unconverted, Legacy / remove, or Unaudited screen as a visual template.
- When the owner asks one screen to match another, decompose both screens by component and use the approved shared style for each component.
- If a repeated component has no adequate shared style, stop before copying attributes. Explain the missing style family and obtain approval for the shared solution.
- Do not force a unique product requirement onto every screen that shares a style, layout, scaffold, or code path.
- When a shared layout blocks an approved screen-specific control, record whether the solution is a local addition, optional variant, shared-layout extension, or separate layout. Shared implementation is not a veto.

## Current verified entries

| Screen | Layout | Header | Repeated body components | Status | Current gap or exception |
|---|---|---|---|---|---|
| Edit Companion | `activity_edit_persona.xml` | Partial | Field labels, hints, boxes, and the main Add button use shared styles | Partial | The additional Delete header icon repeats the shared icon geometry locally. The 96dp editor image treatment, inline field-error text, and final checkbox styling remain local repeated patterns. The dynamically added lorebook rows have not yet been audited. Do not copy this screen as a complete style reference. |
| Edit Persona | `activity_edit_user_persona.xml` | Partial | Field labels, hints, and boxes use shared styles | Partial | The additional Delete header icon repeats geometry locally. The matching 96dp editor image treatment, inline validation text, and text counter remain local repeated patterns. Do not call this screen fully converted or copy it as a complete reference. |
| Memory Backup & Restore | `activity_memory_backup_restore.xml` | Unconverted | Partial: shared section styles and some shared button variants are present | Partial | The top back-and-centered-title header is still copied XML instead of the shared simple-header styles, including a local 20sp title. Remaining status text, progress rows, controls, and other body components require a full current audit before the screen can be treated as Shared. |
| API Endpoint Editor | `activity_api_endpoint_editor.xml` | Partial | Text fields (Label, Host, Model, etc.) use Material's own floating-hint TextInputLayout pattern, not the app's `Widget.App.Field.Label`/`.Box` shared field style; the five section titles (Temperature, Top P, Frequency Penalty, Presence Penalty, Context Window) use `Widget.App.Section.Title`/`.Hint`; delete confirmation uses the shared two-button dialog shape (`dialog_two_actions.xml`) | Partial | Header now uses `Widget.App.ActionBar`/`BackButton`/`Title.NearBack`/`SecondaryButton`. The additional Delete header icon repeats the shared icon geometry locally — same unresolved two-trailing-icon gap as Edit Companion/Edit Persona, not yet a promoted shared style. The text fields have not been converted to `Widget.App.Field.*`. |
| Quick Settings (Summoning Circle) | `fragment_quick_settings.xml` | N/A (bottom sheet, no ActionBar header) | The four sampling-parameter sections (Temperature, Top P, Frequency Penalty, Presence Penalty) use `Widget.App.Section.Title`/`.Hint` with the same `sampling_label_*`/`sampling_hint_*` strings as the API Endpoint editor, so both change together with any future theming pass | Partial | Only those four sections were audited/converted. The rest of the sheet (usage/cost rows, Save to Profile, other quick controls) has not been audited. Do not copy this screen as a complete reference. |
| Memory Controls | `activity_memory_controls.xml` | Shared | All: action bar (`ActionBar`/`BackButton`/`Title`), intro (`Screen.Intro`), three section titles (`Section.Title`), three Memory Defaults toggles + card suggestions toggle (`Row.Toggle`/`TextColumn`/`Title`/`Subtitle`/`Switch`), Memory Engine row (`Row.WithSubtitle`/`Dropdown.Value`), engine-needs-model guidance (`Section.Hint` with intentional colorPrimary/bold overrides) | Shared | None. The engine-needs-model text overrides `Section.Hint` color and weight for emphasis per owner ruling — this is an approved local override, not a gap. |
| Advanced Memory Assistant Settings | `activity_memory_assistant_advanced_settings.xml` | Partial | Action bar, intro, max suggestions toggle + number box (`Field.NumberBlank`), two section titles, archivist endpoint/model rows (`Row.WithSubtitle`/`Dropdown.Value`), temperature slider label + hints, min importance row, reset buttons, save button | Partial | One remaining gap: the Extraction Prompt box still uses Material's default outlined text field instead of the app's own field style — not converted yet. The header title is long enough to run under the back button under the shared centered-title style, so this screen locally reserves a margin on both sides of the title (matching the back button's footprint) — a one-screen override, not a shared-style change. `Dropdown.Value` instances also override `layout_width="wrap_content"` because the parent is a LinearLayout (the style's 0dp is ConstraintLayout-oriented). |
| Activation Prompts List | `activity_activation_prompt_list.xml` | Shared | Manager-mode row (`view_activation_prompt_item_row.xml`): `Row.TitleOnly`/`TextColumn`/`Title`/`Chevron` | Shared | None for the header or the manager-mode row. The separate pick-mode tile (`view_activation_prompt_item.xml`, used only when choosing a prompt from Quick Settings) intentionally keeps its own "checked tile" look, matching the same owner-approved pattern used by Quick Settings pick tiles elsewhere — not audited as part of this row style. |
| Edit Activation Prompt | `activity_edit_activation_prompt.xml` | Partial | Field labels and boxes (`Field.Label`/`Field.Box`), inline field error, save/discard flow all use shared styles and the same house header as Edit Companion | Partial | Same unresolved two-trailing-icon header gap as Edit Companion/Edit Persona/API Endpoint Editor — the Delete icon's 48dp geometry is repeated locally. Uses `ic_delete` (trash can) for its header Delete icon rather than Edit Companion/Edit Persona's `ic_remove_moderator`, matching the icon already used the same way in API Endpoint Editor's header. No hint text under either field (owner ruling, July 28 2026). |
| Edit System Prompt | `activity_system_prompt_editor.xml` | Partial | Field labels and boxes (`Field.Label`/`Field.Box`), inline field error, header Save/Delete icons | Partial | Same unresolved two-trailing-icon header gap as the other full-screen editors — the Delete icon's 48dp geometry is repeated locally. Uses `ic_delete`, same as Edit Activation Prompt. The old bottom Save/Delete buttons are removed; both actions now live only in the header. No hint text under either field. |
| System Prompts List | `activity_system_prompts_list.xml` | Shared | Manager-mode row (`view_system_prompt_item_row.xml`): `Row.TitleOnly`/`TextColumn`/`Title`/`Chevron` | Shared | None for the header or the manager-mode row. The separate pick-mode tile (`view_system_prompt_item.xml`, used only when choosing a prompt from Quick Settings) intentionally keeps its own "checked tile" look, the same owner-approved pattern as the Activation Prompts and Personas pickers — not audited as part of this row style. |
| Characters Hub | `activity_characters.xml` | Shared | Three navigation rows (`Row.WithSubtitle`/`TextColumn`/`Title`/`Subtitle`/`Chevron`) | Shared | None. Full layout read: every view uses a shared style. |
| Default Images | `activity_default_images.xml` | Shared | Two navigation rows | Shared | None. Full structure verified: header and rows only, all shared. |
| Summarizer Settings | `activity_summarizer_settings.xml` | Shared | `Screen.Intro`, toggle row, two `Field.NumberBlank` fields with `Field.Label`/`Hint`, section titles and hints, endpoint/model rows with `Dropdown.Value`, prompt `Field.Box`, three `AppButton.Primary.Inline` actions; its dialogs use `App.MaterialAlertDialog` and shared-styled dialog layouts | Shared | None. |
| Profile Image Properties | `activity_profile_image_properties.xml` | Shared | Three navigation rows, all shared | Partial | The screen itself is fully shared; its Default Shape and Fine Rotation dialogs (`dialog_default_shape.xml`, `dialog_fine_rotation.xml`) are unconverted local layouts. |
| Memory Filters Panel | `activity_memory_filter_panel.xml` | Shared | Close-panel header (`Title.LeftAligned`/`CloseButton`); filter sections are appended in code | Partial | The code-built filter sections and selection pills have not been audited against shared styles. |
| Memory Manager | `activity_memory_manager.xml` | Unconverted | Navigation rows use the shared row family throughout | Partial | Header is copied local XML (raw back button, local 20sp title) instead of the shared header styles. |
| Roleplay Hub | `activity_roleplay_hub.xml` | Unconverted | `Screen.Intro` and navigation rows are shared | Partial | Header is copied local XML instead of the shared header styles. |
| AI System Settings | `activity_ai_system_settings.xml` | Unconverted | Navigation rows and toggle rows are shared | Partial | Header is copied local XML; the intro paragraph is local 14sp text instead of `Screen.Intro`. |
| Logs viewer | `activity_logs.xml` | Shared | Header with one trailing icon (`Title.NearBack`/`SecondaryButton`) | Partial | Two buttons use stock Material button styles instead of the app's semantic `AppButton` styles; the log body has not been audited. |
| Lorebook Entries | `activity_lorebook_entries.xml` | Partial | Header `Title.NearBack` plus end icon | Partial | Same unresolved two-trailing-icon header gap as the full-screen editors (second icon geometry is local); the description text is local 14sp; list item layout unconverted. |
| Lorebooks List | `activity_lorebooks_list.xml` | Shared | Header shared | Partial | List item layout and the add/search controls have not been audited. |
| Logit Bias Profiles | `activity_logit_bias_config_list.xml` | Shared | Header with trailing icon | Partial | List item layout unconverted. The floating Add button has no approved shared style family (none exists yet for FABs). |
| Logit Bias Entries | `activity_logit_bias_list.xml` | Shared | Header with trailing icon | Partial | Entry input fields and item layout not audited; same FAB note. |
| Personas List | `activity_persona_list.xml` | Shared | Header shared | Partial | List item layout unconverted; same FAB note. |
| Local Whisper Models | `activity_local_whisper_models.xml` | Shared | Header shared | Partial | Body rows and download controls not audited. |
| Local Whisper Storage | `activity_local_whisper_manage.xml` | Shared | Header shared | Partial | Body not audited. |
| Voice Settings | `activity_voice_settings.xml` | Shared | Header plus several shared rows | Partial | Remaining body components not audited; a few local attributes remain. |
| Advanced Voice Settings | `activity_voice_advanced.xml` | Shared | Header shared | Partial | Deliberately plain row structure is kept by design; ~48 local text attributes remain — convert gently without changing the row structure or removing any control. |
| Control Center | `activity_settings.xml` | Shared | Extensive shared row adoption (largest shared-style user in the app) | Partial | A handful of local hardcoded attributes remain; full component audit pending. |
| Audio Debugging | `activity_audio_debugging.xml` | Shared | Header shared | Partial | Body largely local. Debug-only screen, low priority. |
| Memory list scaffold (Worlds, User Personas, Roleplay Characters, Campaigns, Party Members, Tags) | `activity_memory_list_simple.xml` | Shared | One shared list scaffold reused by six list screens | Partial | Scaffold header is shared; list item layouts and the search field have not been audited. |
| Character Card | `activity_character_card.xml` | Partial | Many shared field/section styles | Partial | Header only partially on shared styles; several local attributes; needs a full component audit. |
| Campaign Detail | `activity_campaign_detail.xml` | Partial | A few shared pieces | Partial | Fields and buttons largely local. |
| World Detail | `activity_world_detail.xml` | Partial | A few shared pieces | Partial | Fields largely local. |
| Recovery Backup | `activity_recovery_backup.xml` | Unconverted | Shared button variants are present | Partial | No shared header; many local attributes remain. |
| Profile Images | `activity_profile_images.xml` | Unconverted | A couple of shared references | Partial | Mostly local; grid and controls not audited. |
| Chat | `activity_chat.xml` | Unconverted | Attachment strip uses the shared `Include` family | Partial | Everything else awaits the dedicated chat restyle phase (redesign plan Phase 4) with its view-ID contracts — do not restyle piecemeal. |
| Memory Browser | `activity_memory_list.xml` | Unconverted | None | Unconverted | Header and controls local. |
| Memory Assistant | `activity_memory_assistant.xml` | Unconverted | None | Unconverted | Header, buttons, and rows local. |
| Memory Editor | `activity_memory_editor.xml` | Unconverted | None | Unconverted | Fields and buttons local. |
| Advanced Memory Settings | `activity_advanced_memory_settings.xml` | Unconverted | None | Unconverted | Fields and buttons local. |
| Model Rule Editor | `activity_model_rule_editor.xml` | Unconverted | None | Unconverted | Fields and buttons local. |
| Card Entry Editor | `activity_card_entry_editor.xml` | Unconverted | None | Unconverted | Large editor (30+ inputs), all local field styling. |
| Companion Detail | `activity_companion_detail.xml` | Unconverted | None | Unconverted | Buttons and image treatment local. |
| API Endpoints List | `activity_api_endpoint_list.xml` | Unconverted | None | Unconverted | Header local; list item layout unconverted. |
| About | `activity_about_new.xml` | Unconverted | None | Unconverted | Buttons rely on the theme-default `App.Button` shape only. |
| Documentation | `activity_documentation.xml` | Unconverted | None | Unconverted | Header local. |
| Lorebook Debug | `activity_lorebook_debug.xml` | Unconverted | None | Unconverted | Debug-only screen, low priority. |
| Main (tab host) | `activity_main.xml` | Unconverted | None | Unconverted | Scheduled for structural replacement by the drawer (redesign plan Phase 3) — convert only what survives that change. |
| Onboarding (Welcome, Purpose, Activation, Terms) | `activity_welcome.xml`, `activity_purpose.xml`, `activity_activation.xml`, `activity_terms.xml` | Unconverted | None | Unconverted | Keep the flow and manifest entries intact when converting. |
| Crash Reporter | `activity_crash.xml` | Unconverted | None | Unconverted | Buttons rely on the theme default only. |
| Chat Storage Locked | `activity_chat_storage_locked.xml` | Unconverted | None | Unconverted | Buttons rely on the theme default only. |
| Translator | `activity_translator.xml` | Unconverted | None | Unconverted | — |
| Image Browser | `activity_imageview.xml` | N/A | Minimal viewer UI | Unconverted | — |
| AI Photo Editor | `activity_ai_photo_editor.xml` | Unconverted | None | Unconverted | — |
| Photo Variations | `activity_variations.xml` | Unconverted | None | Unconverted | — |
| Fine-tune (Jobs, New Job, Job Info) | `activity_fine_tune_jobs.xml`, `activity_fine_tune_new_job.xml`, `activity_fine_tune_job_info.xml` | Unconverted | None | Unconverted | — |
| Profile Image Framing | `activity_profile_image_framing.xml` | Unconverted | None | Unconverted | Its rotation dialog is also unconverted. |
| Component gallery (developer) | `activity_material.xml` | Custom approved | Developer-only Material component gallery | Custom approved | Redesign plan §7.3: leave untouched. |
| Chats List tab | `fragment_chats_list.xml` | N/A | Chat rows (`view_chat_name*.xml`), search, FABs | Unconverted | Row design is reused by the future drawer — convert together with the drawer work. |
| Playground tab | `fragment_playground.xml` | N/A | Fields | Unconverted | — |
| Settings tile | `fragment_tile.xml` | N/A | The tile component used by tile grids | Unconverted | The redesign plan replaces tiles with rows/cards in its Phase 5; convert or retire with that decision. |
| Add Chat dialog | `fragment_add_chat.xml` | N/A | Fields, action buttons | Unconverted | — |
| Message Edit dialog | `fragment_message_edit.xml` | N/A | Field, action buttons | Unconverted | — |
| Report Content sheet | `fragment_report_content.xml` | N/A | Five stock-styled buttons, field | Unconverted | — |
| Edit API Endpoint sheet | `fragment_edit_api_endpoint.xml` | N/A | Large floating-hint field set | Unconverted | Possibly superseded by the API Endpoint Editor screen — confirm which is live before converting. |
| Edit Lorebook / Edit Lorebook Entry sheets | `fragment_edit_lorebook.xml`, `fragment_edit_lorebook_entry.xml` | N/A | Fields | Unconverted | — |
| Edit Bias Config sheet | `fragment_edit_bias_config.xml` | N/A | Fields | Unconverted | — |
| Picker sheets (Language, Voice, Activation Prompt, System) | `fragment_select_language.xml`, `fragment_select_voice.xml`, `fragment_activation_prompt.xml`, `fragment_system.xml` | N/A | Pick tiles / option lists | Unconverted | The owner-approved "checked tile" pick pattern exists for some pickers; audit each against it before converting. |
| Card dialogs | `dialog_add_to_card.xml`, `dialog_delete_companion.xml` | N/A | Dialog bodies | Unconverted | — |
| List item layouts (`view_*_item*.xml` and similar) | various | N/A | Rows rendered inside list screens | Unconverted | 26 of 34 item layouts contain no shared styles; convert each together with its parent list screen (gaps noted per screen above). |
| Image Generation | `activity_image_generation_settings.xml` | Shared | Action bar (`ActionBar`/`BackButton`/`Title`), three toggle rows (`Row.Toggle`/`TextColumn`/`Title`/`Switch`), Image Service and Image Model navigation rows (`Row.WithSubtitle`/`TextColumn`/`Title`/`Subtitle`/`Chevron`), Default Shape and Default Quality dropdown fields (`Dropdown.Label`/`Dropdown.Value` with anchored ListPopupWindow); model selection reuses the shared searchable model picker dialog in its image variant | Shared | `Dropdown.Value` carries a `layout_weight` override for its LinearLayout parent (the style's width is ConstraintLayout-oriented) — the same accommodation already accepted on Summarizer Settings and Advanced Memory Assistant Settings. |
| Image Confirmation Card (in-chat) | `view_image_confirmation_card.xml` | N/A (inline chat row, no header) | Title (`Row.Title`), collapsed prompt (`Row.Subtitle` with a maxLines override for long prompts), Create (`AppButton.Primary.Inline`), Cancel (`AppButton.Destructive.Inline`), View Prompt (`AppButton.Primary.Inline`) | Shared | None. View Prompt is a secondary-role action correctly using `AppButton.Primary.Inline` per the standing rule that Secondary defers to Primary until a distinct style is approved (owner ruling, July 29 2026 — see `ui-style-guide.md`). |
| Creating Image Row (in-chat) | `view_image_progress_card.xml` | N/A (inline chat row, no header) | Title (`Row.Title`), Cancel (`AppButton.Destructive.Inline`); container reuses the assistant bubble background drawable, the same composition as the confirmation card | Shared | None. Transient row — shown only while a generation is running, never persisted. |
| Alerts, Errors & Logs | `activity_alert_debug_menu.xml` | Partial | Toggle rows, section headings, log rows, and chevrons use the page's own local composition (it predates the shared row styles); the retention inputs — including the new Image Generation Log pair — use the shared `Widget.App.Field.NumberBlank`; the over-ceiling notice uses the shared single-action dialog | Partial | The image-generation additions deliberately mirror the page's established local toggle/retention/row composition so the page stays uniform; converting the whole page to `Row.Toggle`/`Row.TitleOnly`/`Section.Title` is a separate audit-and-convert pass, not something one feature's rows should do alone. |

All screens or layouts not listed above are **Unaudited** in this map until their current layouts and relevant code are checked. Note on buttons: `MaterialButton`s without an explicit style still inherit the shared semi-square `App.Button` default from the theme; "Unconverted" rows above mean no explicit shared-style adoption, not that buttons render with the old pill shape.

## Orphaned and unreachable code

The orphaned layouts, dead screens, and unused wiring identified by the audit (old About layout, data-sources screens, hollow debug activity, Remove Ads, thanks/donation screen, legacy API host dialogs, network-error dialog, web-view dialog, the PWA theme and colors, the Tips screen and tab, the Tools tab, the store-reviewer instructions screen, and the unused beta bottom menu) were removed with owner approval. Git history preserves them. No orphaned candidates are currently known; add any newly discovered ones here as a pending owner decision before deleting anything.

## Current legacy direction

| Component or mechanism | Status | Direction |
|---|---|---|
| Legacy per-screen AMOLED recoloring and its dedicated control | Legacy / remove | Existing code does not make this mechanism a requirement. It is not part of the future shared theme architecture. Do not spend work preserving, expanding, restyling, repairing for appearance, or routing new shared components through it. The replacement theme system should use shared semantic colors, styles, and theme or palette definitions instead of per-screen recoloring. |

## Audit record format

Add one concise row per screen or genuinely shared layout family:

| Screen | Layout | Header | Repeated body components | Status | Current gap or exception |
|---|---|---|---|---|---|
| Screen name | `layout_name.xml` | Shared / Partial / Unconverted / Custom approved / N/A | Short component summary | Status | Only the current gap or approved exception |