# API Voice Models — Detailed Implementation Plan

## 1. Purpose and authority

This document plans a new API speech-model manager, its model/provider pickers, its connection to Select Voice, and its integration with Clean Up Models.

**This is a planning document only. Publishing this Markdown plan to `main` is authorized; application-code changes are not.** A future implementation task must explicitly authorize code work.

Repository inspected: `SoulPhosphor/speak-gpt`.

Baseline: `main` at `f2bdf0047c0751e14d91a12179ee5ad921f804be`, inspected on 2026-08-27. Recheck relevant files against the implementation branch before editing. Do not overwrite intervening work.

### Implementation note

Before coding an authorized phase, read this note, the assigned phase and its reference sections, `CLAUDE.md`, `ui-style-guide.md`, relevant `ui-style-adoption.md` entries, and any implementation status record. Check the current branch, working tree, and prerequisite code; preserve intervening work and use the owner-designated branch. Continue an existing designated feature branch without creating an extra branch. Then proceed directly into code work. Reading is not a phase, deliverable, or reason to stop. Do not require loading the entire plan each session: a fresh instance can use the assigned phase, referenced contracts, and repository handoff without earlier conversation.

**Cross-phase identity and voice-storage contract:** renaming changes only a chat's title; its existing stored ID never changes. The selected/default TTS voice, previous-selection history and last-known-good record are global to the app, never keyed by chat ID or chosen from the open conversation. These rules supersede earlier per-chat voice-storage assumptions. Remaining work, including Phase 6 cleanup, must use the global selection/recovery interfaces described in Sections 7.1 and 7.7. No chat-ID migration, legacy chat/voice data cleanup or broader backup refactor is authorized by these corrections; Phase 6 retains its separately specified saved-TTS-entry removal scope. Companion-specific voices remain future work.

The product decisions in this plan are settled. Current owner instructions remain authoritative; ordinary internal implementation choices and the specified error wording do not require new questions. Do not add unrelated refactors, palette changes, Google TTS repairs, or logging changes. Unit/build checks do not establish device behavior or remote routing support.

## 2. Definitions

| Term | Meaning in this feature |
| --- | --- |
| Endpoint | A saved API endpoint profile, including its stable ID, display name, base URL, authentication, and timeouts. It is not the model provider selected inside OpenRouter. |
| Text to Speech Endpoint | An editable request path within an API profile, normally `/audio/speech`, appended to that profile's base URL using the app's existing path conventions. |
| Model | The exact TTS model ID offered by the selected endpoint. Preserve its full ID, including any namespace or suffix. |
| Provider | An upstream service that runs the selected model, when the endpoint offers provider routing. Store the routing identifier separately from its display name. |
| Saved TTS entry | One user-added endpoint/model/routing combination in the new management table. This is the API speech equivalent of a saved favorite, but it needs its own storage identity. |
| Routing settings | Automatic/Preferred/Only plus the provider selection and any retained, applicable provider-selection settings. |
| Voice | A voice ID supported by the saved source's model/provider combination. A model and a voice are different selections. |
| Draft | The unsaved selections in the upper part of the new manager. They do not become a saved entry until Add Model succeeds. |
| Active/default voice | The app-wide selected voice used for speech, independent of the open conversation. Browsing a source is not the same action as selecting its voice. |
| Previous selection | The immediately preceding user-activated global voice. This is activation history, not proof that playback succeeded. |
| Last-known-good voice | A separate global fallback record maintained by the existing voice-availability behavior. It must not be replaced merely because a different default is selected or a catalog advertises preview capability. |
| Companion voice | A future Companion-specific override, not a chat-specific preference. It is not implemented in these phases. Its intended fallback order is Companion voice, global default, then last-known-good recovery. |

## 3. Approved product requirements

### 3.1 Entry point and manager

| ID | Requirement |
| --- | --- |
| R01 | Add a row labeled **Select API Voice Models** to Voice & Speech immediately above **Advanced Voice Settings** (`tile_voice_advanced`). |
| R02 | The new manager uses the normal shared header with the destination title **Select API Voice Models**, without a decorative icon. Preserve the normal navigation/back control; do not interpret “without an icon” as removing navigation. Do not add a header Save action to this manager. |
| R03 | Under the header, show an **Endpoint** dropdown populated from the saved API endpoint profiles. |
| R04 | Below Endpoint, show **AI Model** using the Quick Settings model-selector row's appearance and formatting. Before a model is chosen, its value is **Select**. |
| R05 | Tapping AI Model opens directly on the current **View All** model-list presentation, restricted to TTS models belonging to the chosen endpoint. There is no favorites landing, saved-model tab, or favorite/star action in this TTS picker. Back returns to the caller, never to a favorites screen. |
| R06 | Below the model row, show a section title **Select Provider** and the exact subtext specified below. These are section text, not a separate navigation row. Do not add the previously proposed Choose Provider row. |
| R07 | Directly below that title/subtext, place one row containing the **Automatic**, **Preferred**, and **Only** mode dropdown, followed by the tappable provider-selector area. Before a provider is selected, that area reads **Select** and tapping it opens the TTS provider picker. After selection, it shows the selected-provider display; tapping that display reopens the same picker. The dropdown and picker stay synchronized. Reuse the existing selected-provider presentation in this area; do not add a second provider-navigation row or a redundant Model Provider label. |
| R08 | Show provider controls for API TTS endpoints by default; capability/discovery determines whether they function. Do not hide them simply because an endpoint is untested or its hostname is unrecognized. Provider selection is optional. An entry without a provider preference uses Automatic. An explicit incomplete Only selection must not be silently rewritten to Automatic; preserve the text selector's validation distinction. |
| R09 | Place an **Add Model** button below the configuration controls. Successful addition saves the combination, updates the table, and clears the upper fields ready for another entry, including clearing the endpoint selection. Routing returns to its neutral Automatic state. A failed add must not clear the draft. |
| R10 | Below Add Model, show the saved entries as a table: **Endpoint**, **Model**, **Provider**, and a trailing **X** removal action. |
| R11 | Endpoint and Model are fixed within a saved row. Tapping Provider opens the provider picker for that exact entry. Saving there changes that row's routing; cancel/back does not change it. |
| R12 | The upper configuration area and saved table scroll together in one vertical page beneath the normal shared header. Do not pin the add controls or give the saved table a separate vertical scrolling region. Long saved lists must remain usable. |
| R13 | The management table is the saved TTS combination list. Do not add a separate TTS favorites UI or write these entries to the chat-favorites store. Reject duplicate endpoint/model/provider combinations on Add and provider-edit Save; at least one of those three elements must differ. A routing-mode change alone does not create a distinct combination. Use the exact duplicate dialog below. |

The provider section uses this exact title and subtext:

**Select Provider**

> Optional. This may not be available or necessary with all endpoints.

The mode dropdown and provider selector belong together on the single row immediately beneath this text. The title and subtext do not open another screen; the provider-selector value does. On an empty draft, the row shows the mode **Automatic** and the separate provider-selector value **Select**. After a successful Add Model resets the draft, restore those same values. Keep the existing endpoint editor's unrelated wording and row unchanged.

The provider display is a name, using the existing text-model behavior in `app/src/main/java/org/teslasoft/assistant/ui/fragments/dialogs/QuickSettingsProviderDisplay.kt`: Only shows the selected provider; Preferred shows the first/highest-priority provider. Do not add descriptive prose or list every preferred provider in this field. For the new manager's unselected provider-selector area, use **Select** as requested instead of the helper's existing Automatic fallback. Keep Automatic/Preferred/Only in the separate mode dropdown. Adapt the display logic without coupling TTS storage to `FavoriteModelObject`.

Duplicate dialog: use the shared single-action dialog style, title **Combination Already Exists**, the exact message **endpoint model and provider combination already exists.**, and one button labeled **Okay** (`@string/btn_ok`). Do not abbreviate it to OK or add another action. Reject the duplicate without clearing the add draft or replacing the existing saved row.

### 3.2 API profile field

| ID | Requirement |
| --- | --- |
| R14 | Add **Text to Speech Endpoint** immediately below **Chat Completions Endpoint** in both the add-profile and edit-profile layouts. |
| R15 | Use the same editable field pattern as Chat Completions Endpoint, with the normal speech path prefilled and editable. Keep the chat path and speech path independent. |
| R16 | Save and restore the speech path per endpoint profile. Editing a profile must preserve its stable identity, existing credentials, and all unrelated fields. |

### 3.3 Provider picker and filters

| ID | Requirement |
| --- | --- |
| R17 | Open the TTS provider picker in the same manner as the text-model provider picker, but for the selected speech model. |
| R18 | Its information columns are **Provider**, **Price**, **Latency**, **Uptime**, **ZDR**, and **Training/Data Use**. The owner confirmed these are table headings, not a row of inline filters. ZDR is the compact zero-retention status; it is not a synonym for training permission. |
| R19 | For boolean cells, copy the existing table convention exactly: **X** means true/yes, an empty cell means false/no, and **?** means unknown. These are informational cells, not removal buttons. |
| R20 | Format prices using their actual billing basis and appropriate currency/unit formatting. Default to alphabetical provider order. When Price sorting is selected, group comparable billing units and sort prices within each group as specified in Section 5; do not invent token/word/minute conversions or a cross-unit cheapest ranking. |
| R21 | Keep the existing separate **Filters** interaction and selection-control style for Provider alphabetical order, Price, Latency, and Uptime. Do not add privacy filters: ZDR and Training/Data Use are informational provider-table columns only, and users choose providers directly. Do not copy text-only options such as Tool Support, reasoning, quantization, caching, or throughput controls. |
| R22 | Retain Automatic/Preferred/Only in the implementation design now, even though OpenRouter's raw speech routing remains unverified. Keep endpoint-specific request handling adjustable; lack of OpenRouter documentation does not authorize deleting the modes. |

Boolean meaning must remain literal: an X under ZDR is favorable to zero retention; an X under Training/Data Use means training/data use is allowed or performed according to the reported field. Do not invert either meaning silently. Absence of ZDR does not establish that training occurs.

### 3.4 Select Voice and cleanup

| ID | Requirement |
| --- | --- |
| R23 | The existing Select Voice source/provider dropdown obtains its API choices from the saved TTS entries. Each API choice identifies its endpoint, model, and provider/routing selection. |
| R24 | Selecting or reopening a source attempts to load its applicable voices. If the model exists but its voices cannot be loaded, keep the model/source, show the specific error dialog, and show **Voices currently not available.** in the voice-list area. Do not delete the source, disable it permanently, invent voices, or substitute another model's voice list. |
| R25 | Preserve the existing device/Google voice source and its behavior. The requested API list is an addition; this work does not remove device TTS. |
| R26 | A working saved-source list is an allowed development checkpoint before full playback integration. It is not a finished integration. Implement capability/discovery failures and recovery using Sections 7.6–7.7; do not leave ordinary voice-loading behavior or error messages for later owner decisions. |
| R27 | Add **Unavailable TTS Models** at the bottom of **Clean Up Models**, after its current categories. |
| R28 | That category checks the TTS models explicitly saved in the new list, not every speech model advertised by every endpoint. |
| R29 | Cleaning up a model that no longer exists removes its matching saved TTS combinations from the manager and therefore from the Select Voice source list. |
| R30 | Temporary provider outages, failed requests, and inconclusive discovery must not newly establish that a saved model no longer exists. Use the existing text-model cleanup checking and deletion behavior, including its saved-report deletion timing, availability safeguards, and endpoint-specific identity. Do not add a separate TTS fresh-check policy. |
| R31 | Remove the top Google/OpenAI TTS engine-toggle tile (`tile_tts`) from Voice & Speech. The selected voice determines whether speech uses Google/device TTS or an API source and, for API speech, its saved endpoint/model/provider settings. Do not replace the tile with another engine selector. Preserve Select Voice and unrelated speech/input controls. |

If a selected API voice has been deleted, restore the voice selected immediately before it when that selection can still be used. If it cannot be restored, show the permanent-unavailability dialog specified in Section 7.7. A missing or failed voice-list response does not by itself establish that a voice has been deleted and must not trigger this fallback.

### 3.5 Error dialogs and messages

The owner has authorized writing appropriate error messages and actions using these rules. Routine failure wording is implementation work, not a reason to ask the owner to diagnose an API or supply each message.

Use established error-handling practices when deciding what information and wording are useful. Identify the affected operation or field, explain the known problem, and provide a known correction when one exists. These principles are supported by the W3C guidance on [error identification](https://www.w3.org/WAI/WCAG22/Understanding/error-identification.html) and [error suggestions](https://www.w3.org/WAI/WCAG22/Understanding/error-suggestion.html). Apply them within the owner's specified dialog design; they do not authorize replacing the dialogs, changing approved button order, or reopening routine wording questions.

1. Show an error dialog using the shared dialog theme and applicable shared action layout.
2. Use a short, specific title in Title Caps.
3. Beneath it, explain what happened in plain language. Use the actual known cause. Do not patronize the user, blame them, or substitute a vague umbrella message for information the app has.
4. Beneath the explanation, show the applicable provider-error details in the same labeled format used by chat errors. Keep the provider's own error distinct from the app's explanation.
5. Show only useful actions. Labels are **Cancel**, **Retry**, and **Okay**, with initial capitals. Cancel is the leftmost action; Retry or Okay is to its right. The duplicate dialog remains Okay-only.
6. Do not collapse network failure, rejected API access, malformed data, a missing discovery capability, and an empty returned list into one message such as No Voices Found.
7. When the cause is unknown, say which operation failed and what information was not supplied. Do not invent a reason or claim that a provider responded when none did.
8. Error handling must preserve saved data and requested routing. Dismissing a dialog does not change a voice, replace a source, or turn Only into Automatic. The explicitly approved previous-voice fallback for a deleted API voice is a separate selection-recovery rule in Section 7.7, not permission to weaken a failing request's routing.
9. Keep the message useful without requiring the user to interpret the technical block: state what failed, the known cause, any relevant effect on their saved data, and the next useful action. Omit repetition and facts that do not help recovery. Never make an uncertain claim merely to fill every part of that pattern.

Section 7.6 specifies message selection, provider-detail formatting, and action behavior. This applies to the new TTS work; do not redesign existing chat errors or change logging as a side effect.

## 4. Verified starting points and risks

### 4.1 Current app architecture

| Current component | What was verified | Consequence for implementation |
| --- | --- | --- |
| `VoiceSettingsActivity` and `activity_voice_settings.xml` | The screen opens the full-screen Voice Browser and uses shared rows/header. Its top `tile_tts` separately toggles `getTtsEngine()` / `setTtsEngine()` between Google and OpenAI. | Add the requested navigation row and remove the obsolete engine tile and its bindings. Derive the existing Select Voice row's value from the selected voice/source; do not rebuild the screen. |
| `VoiceBrowserActivity` | Its registered provider list currently contains the Google source only. The comment explicitly reserves API services for the coming selection flow. | Build API source registrations from saved entries, not a hardcoded OpenAI entry. |
| `VoiceBrowserController` | Provider IDs key loaded voices and filters; it rejects stale load callbacks. Browsing is separate from activation. | Give each saved API entry a distinct source identity and preserve these separation/race protections. |
| `OpenAiVoiceProvider` | It currently reads the active chat endpoint, chooses a discovered model, can use a generic six-voice fallback, and previews with an OpenAI-compatible SDK client. | Do not plug it in unchanged. The new source must receive an explicit saved entry and must not choose the first model automatically. |
| `ApiSpeechCatalogClient` | It recognizes some speech metadata and names, merges embedded voice lists, and probes voice paths. It does not currently parse OpenRouter's nested architecture and `supported_voices` correctly for this new use. | Add a TTS-specific catalog contract; do not assume the existing discovery is complete. Preserve voice/model associations. |
| `ChatActivity.speak` | The network branch uses the chat's existing API client/key and legacy speech preferences, not a separately resolved saved TTS entry. | Correct voice browsing alone is insufficient. Actual readback must use the same saved source as preview. |
| `FavoriteModelsPreferences` | Upsert identity is endpoint ID + model ID. Routing is attached to that one favorite. | It cannot represent multiple saved provider combinations for the same endpoint/model without overwriting them. Use a separate TTS entry identity. |
| `QuickSettingsProviderDisplay` | Only displays the selected provider identity; Preferred displays the first provider in its priority order. It does not produce descriptive prose or a list of every preferred provider. | Reuse this name-selection behavior, with Select as the new manager's empty selector value. Do not introduce a new provider-display design. |
| `ChooseProviderActivity` | It is connected to chat favorites, can change the model, and can write favorites directly. It includes text-specific columns and tool-capability handling. | Reuse styles and applicable selection logic, not these side effects. A saved TTS row's model must remain locked. |
| `ProviderFilterState` | It is a mutable singleton with chat-specific filters. | TTS filter state must not overwrite the text screen's state. |
| `ProviderRoutingSerializer` | It builds `only`, `order`, `allow_fallbacks`, and `ignore`; its generic body augmentation replaces the provider object. | Reuse the pure routing mapping where suitable, but preserve speech-specific `provider.options` when composing a speech request. |
| `ModelCleanupReferences` | It currently includes chat favorites and model-rule targets only. | Add explicit saved TTS references to collection, report reconciliation, and cleanup. |

### 4.2 Live read-only OpenRouter observations

These are observations from 2026-08-27, not a permanent hardcoded catalog. Sources: [speech catalog](https://openrouter.ai/api/v1/models?output_modalities=speech), [sample provider response](https://openrouter.ai/api/v1/models/deepgram/flux-tts:free/endpoints), and [exact model lookup](https://openrouter.ai/api/v1/model/fish-audio/s1).

- `GET /api/v1/models?output_modalities=speech` returned speech models with `architecture.output_modalities` containing `speech`.
- Some models had `supported_voices`; others returned null. Missing voice metadata is not proof that the model is unavailable.
- A model-specific provider-endpoints response contained provider names/tags, prices, uptime fields, and nullable latency. The inspected response did not include separate training-policy information.
- A model-specific lookup resolved a saved speech-model ID and returned its canonical ID and metadata.

Use these data shapes in tests, but do not freeze the observed list of models, providers, or voices into application logic.

### 4.3 Routing evidence and the owner's decision

The official TypeScript SDK's `SpeechRequestProvider` and its outgoing schema contain `options` only. The separate chat `ProviderPreferences` schema includes `order`, `only`, and fallback fields. Sources: [speech request source](https://github.com/OpenRouterTeam/typescript-sdk/blob/main/src/models/speechrequest.ts) and [chat provider preferences source](https://github.com/OpenRouterTeam/typescript-sdk/blob/main/src/models/providerpreferences.ts). No authenticated speech-generation request was performed during planning.

**The owner has nevertheless explicitly approved keeping all three modes in this feature now.** Therefore:

1. Implement the three modes and persist the requested routing settings.
2. Keep request construction separate from UI and storage, so individual endpoint adapters can be corrected without redesigning the screen or losing selections.
3. Test the intended outgoing routing payload with controlled fixtures.
4. Record OpenRouter raw speech routing as unverified until a live test or authoritative confirmation establishes it.
5. Do not silently remove routing fields on retry, switch an Only selection to Automatic, or claim that a server honored a setting merely because it accepted the request.
6. Do not reintroduce “prove OpenRouter routing first” as a blocker to writing or building the owner-approved UI and storage.
7. If live testing shows ignored or rejected routing, report that precise result. Ask before changing visible controls or choosing alternative fallback behavior.

### 4.4 What cleanup currently does

The existing cleanup feature does identify missing saved models and allows their removal. It is not necessary to redesign its purpose.

- Opening Clean Up Models reads its saved report and reconciles local references. It does not start a network scan.
- **Check Available Models** performs the scan.
- Failed HTTP requests, malformed responses, and empty catalogs are inconclusive.
- OpenRouter IDs absent from its general catalog receive an individual lookup; accepted aliases are protected, a model-specific 404 is treated as absent, and unsuccessful/inconclusive checks do not newly create deletion candidates.
- Missing upstream provider routes are not the same as a missing base model.
- A failed recheck can preserve an earlier warning while marking the endpoint unchecked.
- The current Delete All actions use the saved report and require confirmation. They do not perform a fresh network check at deletion time.

Do not describe this as an inability to remove models that no longer exist. Do not promise that a scan predicts future availability. Extending TTS cleanup does not by itself authorize a new waiting period, repeated-scan threshold, background cleanup, or redesign of the existing categories.

## 5. Final ordering and filtering decisions

Alphabetical provider order is the default. Price sorting groups comparable rates and sorts within those groups. The owner delegated the order of incompatible billing groups; use the deterministic rules below rather than estimating a speech workload or claiming that one billing unit is inherently cheaper.

### 5.1 Controls and default state

1. Reuse the text provider screen's separate Filters panel, automatic application of changes, and existing selection-control styles. Do not add an Apply button or a new sorting toolbar.
2. Keep Provider alphabetical order (A–Z or Z–A) and the Price, Latency, and Uptime sort controls. Each numeric control uses the existing High to Low / Low to High choices and wording. Match the existing internal `SortDirection.NONE` behavior; do not introduce a visible Default option.
3. Start a newly opened TTS provider picker in A–Z order with numeric sorts unset, matching the text screen's reset-on-open behavior. Retain that picker's state while opening and returning from its Filters panel. Keep it separate from the mutable text `ProviderFilterState` singleton.
4. Do not add ZDR, Training/Data Use, or any other privacy filter. Those two values remain informational columns. Omit text-specific quantization, tools, caching, reasoning, and throughput controls.
5. If multiple numeric sorts are active, preserve the text screen's key precedence for the remaining fields: Price, then Latency, then Uptime, followed by the selected alphabetical order as the tie-breaker. Missing numeric values follow known values in either direction. Use stable provider identity as the final internal tie-breaker when display names are identical.
6. Sorting changes the discovery table's display order only. It must not rewrite the user's Preferred provider priority, change a selected Only provider, or alter saved routing merely because a different sort was chosen.

### 5.2 Price display and comparison

1. Preserve the reported currency, charge components, unit, and quantity. Display a rate as a price with its billing basis, for example per minute, per 1,000 characters, or per million tokens, using the existing price-cell typography. Do not label an unspecified unit as tokens.
2. Normalize only mathematically equivalent units for comparison: per 1,000 and per million of the same token type, or per second and per minute of the same audio duration. Do not estimate conversions between tokens, words, characters, and minutes. Do not convert currencies without a separately authorized exchange-rate feature.
3. With Price sorting enabled, place confirmed-free entries first. An entry is confirmed free only when the endpoint explicitly reports every applicable charge component as zero; a missing price or a zero output component with paid input is not free.
4. Place paid groups next, ordered alphabetically by normalized billing-unit name, then currency code, then the charge-component schema where needed. This group order is stable and is not a claim about which incompatible unit costs less. Providers using the same comparable basis stay together. Do not add group headings or controls to the approved six-column table; grouping means adjacent rows in the existing table.
5. Within each comparable group, sort by normalized price ascending for Low to High or descending for High to Low. Apply the other active sort keys and alphabetical tie-breaker when prices match. Keep the group order from steps 3–4 in both directions; the direction changes the order inside a group.
6. Preserve separate input/output or other price components when an endpoint charges more than one. Compare only entries with the same normalized components and units. Use input before output for an otherwise matching input/output pair, and a stable component-key order for other matching schemas. This is an explicit component comparison, not an estimated total bill. Never add unlike units or silently discard a paid component.
7. Place entries whose billing basis or required rates are unknown after known-price groups, with the selected alphabetical order as the tie-breaker. A partially specified paid rate is not comparable to a complete one. Keep the existing unknown-value presentation and retain any known component in the price display without inventing the rest.
8. With Price sorting unset, do not apply price groups: use the other selected sort keys, or plain alphabetical order when none are set. Use decimal-safe formatting so a small nonzero price is never displayed as free or rounded to a misleading zero.

Required examples for comparator tests: mixed minute/token/character rates remain separate; equivalent per-1,000/per-million rates compare correctly; an explicitly free entry precedes paid groups; missing rates appear last; a paid-input/zero-output entry is not free; both directions sort within groups; and opening a fresh picker restores alphabetical order.

## 6. Shared-style contract

The actual style values live in `app/src/main/res/values/themes.xml`; the Markdown guide maps their intended use. Inspect both. New code must not hardcode repeated colors, geometry, typography, or dropdown feedback.

| Component | Existing family or reference |
| --- | --- |
| Manager header | `Widget.App.ActionBar`, `.BackButton`, `.Title` |
| Existing picker's Save header | Use its applicable shared ActionBar variants; do not add a Save icon to the manager itself. |
| Navigation row without subtitle | `Widget.App.Row.TitleOnly`, `.TextColumn`, `.Title`, `.Chevron` |
| Select Provider title/subtext | `Widget.App.Section.Title` and `Widget.App.Section.Hint`; use the exact approved text in Section 3.1, without a navigation-row container or chevron |
| Inline provider selector beside the mode dropdown | Reuse the existing selected-provider value presentation and applicable selector text style (`Widget.App.Row.Selector.Value`); show **Select** until a provider is chosen. Keep the local composition on one row with the canonical mode dropdown; do not recreate the removed navigation row. |
| Endpoint/routing dropdown | `Widget.App.Dropdown.CanonicalLabel`, `.CanonicalValue`, and `AppDropdown` |
| AI Model row | `Widget.App.Row.Selector`, `.Selector.Label`, `.Selector.Value`; inspect `fragment_quick_settings.xml` and preserve its requested visual treatment |
| Speech endpoint input | `Widget.App.Field.Label`, `.Hint` when applicable, `.Box`; match the chat-endpoint input behavior in both profile layouts |
| Add Model | `AppButton.Primary` |
| Table rows/header/cells | `Widget.App.Chart.Row`, `.HeaderCell`, `.Cell` |
| Filter check options | `Widget.App.CheckOption.Row` and `.Label` where that exact interaction is approved |
| Section text | `Widget.App.Section.Title` and `.Hint` |
| Dialog theme | `App.MaterialAlertDialog`; title through `setTitle`, separate explanation/provider detail through `setMessage` |
| Error-dialog actions | `app/src/main/res/layout/dialog_two_actions_cancel_first.xml` for Cancel then Retry/Okay; `app/src/main/res/layout/dialog_single_action.xml` for one Okay action; use the shared button styles and `@string/btn_ok` |
| Permanently unavailable voice dialog | Existing shared two-action dialog composition: dismissing **Okay** on the left and the primary **Select New Voice** navigation action on the right; do not add Cancel or reorder these approved actions |
| Existing voice rows/actions | `Widget.App.VoiceBrowser.*`; do not alter their preview, selection, or long-press geometry |

Additional rules:

1. Keep style adoption truthful. The existing Select AI Model screen has shared headers but still has local search/row styling; do not call every part of it a shared component.
2. A local layout may reuse shared visual styles. Do not force a unique screen into an unsuitable shared scaffold.
3. If a shared style lacks a necessary approved variant, identify the gap and ask before changing its appearance across screens.
4. Use a single column-width definition for each table's header and body. Do not allow labels and values to drift into different columns.
5. The saved-entry X is a removal action. Do not reuse the provider table's Ignore toggle behavior for it.
6. Boolean X marks are not clickable actions. Preserve unknown as `?`; do not show a blank cell for missing data.
7. Preserve shared dropdown sizing, border, chevron, disabled state, and open-menu behavior. Do not replace them with a native Spinner or a new popup design.
8. Use existing theme attributes; do not restart the paused AMOLED/palette project.
9. New labels follow Title Caps; explanatory prose follows sentence case. Use the existing **Okay** resource, never `OK`.
10. Do not introduce decorative icons, headings, cards, help buttons, or extra provider columns without approval.

## 7. Internal data and behavior contracts

The following are implementation choices, not additional UI requirements. Equivalent internal structures are acceptable if they preserve the contracts and tests.

### 7.1 Saved-entry storage

Create a dedicated saved TTS collection. Suggested internal names are `SavedTtsSource`, `SavedTtsSourcesPreferences`, and `TtsRoutingSettings`; these are proposed new types, not claims that those files already exist.

Each entry needs:

- a stable entry ID independent of its displayed text and routing settings;
- the existing stable endpoint-profile ID;
- the exact model ID;
- the selected routing mode;
- the selected Only provider identifier, where applicable;
- the Preferred provider order and applicable fallback setting, matching the approved text-picker behavior;
- any other provider-selection state only if its corresponding behavior is explicitly retained;
- stable list order using the app's appropriate persistence mechanism.

Do not store a second API key in the entry. Resolve credentials from its referenced profile when making a request. A profile rename must update display labels without breaking references. A provider edit must preserve the entry ID so active-voice references and list updates target the same source.

The collection should follow the existing favorites pattern of saved entries available across the app, with endpoint-specific membership. The selected/default voice, previous-selection recovery history, and last-known-good voice are app-wide. They must not depend on chat IDs, chat names, chat deletion, or the open conversation. Adding a source does not activate it.

Persist the immediately previous selected voice globally and separately from the current global selection, including enough source identity to restore it correctly. Record this when the user activates a different voice, not when they browse or preview. The existing LastKnownGoodVoiceRegistry stores one selection and must also use global storage; it does not provide previous-selection history. Preserve its existing Google behavior. Future Companion-specific overrides will resolve before the global default, with last-known-good recovery after that default; implementing Companion overrides is outside this plan. Missing history on older preferences is handled by the explicit dialog in Section 7.7, not by inventing a prior selection.

Current storage interfaces are `AppTtsVoicePreferences.getPreferences(context)`, `PreviousTtsVoicePreferences.getPreferences(context)` and `LastKnownGoodVoiceRegistry(context)`. None accepts a chat ID. Current/default and last-known-good values use the existing encrypted global `settings.` store; previous-selection history uses the existing global/default history file. `Preferences` delegates voice identity to this global store even when other settings belong to a chat. Do not copy voice identity during chat creation, use `tts_history_scope`, adopt an arbitrary chat's old voice as the global default, or delete global records when a conversation is deleted. Preserve old data without a migration or cleanup.

Chat identity and TTS source identity are separate. Preserve every existing stored chat ID, including IDs originally derived from titles. A rename updates only the title and does not move history, settings, attachment directories, memory references or running jobs. Direct readers, including backup readers, use the stored chat ID rather than recomputing it from the new title. The existing missing-ID compatibility read is not permission to regenerate an ID that is present. Saved TTS source IDs remain independent stable entry IDs.

An unreadable saved collection is not an empty collection that may safely be overwritten. Return an explicit failure to the caller, preserve existing bytes, and use the approved recovery/error path.

### 7.2 Add and edit transactions

Add sequence:

1. Capture the current endpoint, exact model, and complete routing draft.
2. Validate that they belong together. Validate required selections and mode constraints.
3. Reject an existing endpoint/model/provider combination using the exact single-Okay dialog in Section 3.1. Do not treat a mode change alone as a distinct combination.
4. Persist the complete entry as one logical operation.
5. Refresh the saved-table source of truth.
6. Only after success, clear the upper draft. Clear endpoint and model, clear provider choices, and reset routing to Automatic.

Provider edit sequence:

1. Open the picker with entry ID, fixed endpoint/model, and a copy of that entry's routing settings.
2. Editing occurs in picker-local state. Do not modify the stored entry while browsing provider rows.
3. Cancel/back returns no mutation.
4. Save validates and returns the complete routing result.
5. Before persisting, reject a change that duplicates another saved endpoint/model/provider combination using the same approved dialog. Otherwise update only the matching entry ID, preserving endpoint, model, ID, and list position.
6. Refresh the row and affected source metadata. Apply the owner-approved active-voice policy if necessary.

The manager's upper draft and a saved row being edited are different targets. Never let a result intended for one update the other.

### 7.3 Routing state

Maintain one routing-state object per draft/entry. Both the manager's mode dropdown and the provider picker read/write that object; do not keep unrelated mode variables that can disagree.

| Mode | Intended request behavior |
| --- | --- |
| Automatic | The endpoint chooses the provider; with no additional routing restrictions, no explicit provider preference is needed. |
| Preferred | Send the selected provider preference/order with the applicable fallback behavior. |
| Only | Send the selected provider restriction. Do not drop it or retry without it. |

For OpenRouter-shaped requests, the existing mapping is `order` for Preferred and `only` plus disabled fallbacks for Only. This is the intended payload mapping, not a claim that OpenRouter's speech server has been verified to honor it.

Use endpoint capability/adaptation code rather than display-name comparisons. A profile named “OpenRouter” is not evidence of a particular API contract; renaming the profile must not change behavior. Equally, do not make TTS routing permanently exclusive to one hostname when another endpoint can support it.

When combining routing with speech-specific provider options, merge the structured objects intentionally. Do not let the existing serializer overwrite and remove `provider.options`.

### 7.4 Discovery and caching

Keep model discovery, provider discovery, and voice discovery separate. A failure of one must not be reported as a different failure.

- Model discovery answers which TTS models the endpoint offers.
- Provider discovery answers which upstream providers offer this exact model and their reported metadata.
- Voice discovery answers which voices apply to the selected source.

Cache and in-flight request keys must include all identity fields that affect the result. At minimum, distinguish endpoint ID, model ID, and routing/source identity. Account for changed base URLs or request configuration without using secrets as visible cache keys.

Use cancellation and generation tokens so old responses cannot overwrite a newer endpoint/model/provider choice. Do not let a response from source A populate source B's voices or provider table.

### 7.5 Speech requests and playback

The active voice is the authority for actual speech routing. A selected Google/device voice uses that existing device source. A selected API voice resolves its saved TTS entry and that entry's endpoint, model, and provider routing. The removed tile must not survive as an invisible independent setting that can override this choice. Browsing a source, changing the manager's add draft, and previewing another voice do not change the active voice or speech source.

Keep any legacy engine preference needed by existing Google/runtime code synchronized as a compatibility value derived from voice activation or approved previous-voice recovery. Do not blindly remove preference keys still used by other code, and do not let a stale `openai`/`google` flag override a complete newer voice selection. Update the existing Select Voice row's display from the same resolved selection; its current OpenAI-versus-Google branch cannot describe multiple API sources correctly.

Preserve existing saved Google selections. If an older API preference can be linked unambiguously to an already saved source using its full identity, retain that selection. A binary engine flag alone is insufficient: do not guess an endpoint from the active chat, invent a saved entry, or call that uncertainty permanent deletion. Keep the old preference data intact and use the Voice Source Unavailable error below if its source cannot be identified; selecting a configured voice supplies the missing identity.

For API speech, preview and full readback must share the same source resolver and transport configuration. Preview supplies its explicit target without activating it; full readback supplies the active voice:

1. Resolve the saved entry or explicitly supplied draft source, as appropriate.
2. Resolve its endpoint profile by stable ID.
3. Build the speech URL using that profile's speech path, not its chat-completions path.
4. Apply that profile's existing authentication mode and timeout settings.
5. Send the exact speech model and voice ID, plus the requested routing settings through the endpoint adapter.
6. Request a format the existing player can actually decode. Do not label raw PCM bytes as MP3.
7. Distinguish an audio success response from a JSON error response; do not save an error body as playable audio.
8. Preserve Stop/cancellation, stale-readback protection, audio-resource cleanup, hands-free completion behavior, and existing lifecycle handling.

Do not add an audio-format picker, new tuning fields, or new logs as part of implementing this transport.

### 7.6 Specific error handling and wording

#### Existing chat-error references

Inspect and reuse applicable pure formatting/parsing behavior from:

- `app/src/main/java/org/teslasoft/assistant/util/GenerationErrorMessages.kt`, especially `providerDetailBlock` and its server-response evidence rules;
- `app/src/main/java/org/teslasoft/assistant/util/ProviderErrorInfo.kt`, which preserves useful inner/outer provider messages;
- `app/src/main/java/org/teslasoft/assistant/util/GenerationErrorClassifier.kt`, for shared network/authentication/limit classifications where their meaning applies to the current operation;
- `app/src/main/java/org/teslasoft/assistant/ui/activities/ChatActivity.kt`, where the short app explanation precedes the provider detail block;
- the `provider_*` resources in `app/src/main/res/values/strings.xml`.

Do not copy a chat-generation explanation into a voice-catalog error unchanged. Loading a voice list, requesting audio, and playing downloaded audio are different operations. Do not call a logging function merely to obtain formatted text, and do not claim details were saved to a log unless that existing behavior actually occurred. Formatting a TTS dialog like a chat error does not authorize inserting settings/catalog failures into the conversation or changing completed chat text.

#### Dialog composition and provider evidence

Use this order: Title Caps heading; concise explanation; applicable provider-detail block; shared action row. Provider details must remain readable for a long server message; use the standard dialog's scrolling content instead of cutting off the reason.

Reuse the existing labels and order: **Provider Error**, **API Provider**, **Requested/Routed Model Provider** when one was requested, **Model Service Provider**, **Model**, **Function**, and the existing status/code/type/request-field lines when supported by evidence. Use the saved TTS endpoint/model and this failed attempt's information, not the active chat's profile or a process-wide latest-request record. Function names should identify the actual operation, such as Model List, Provider List, Voice List, Voice Preview, or Text to Speech.

The requested provider is not proof of the serving provider. Populate Model Service Provider from the response only, otherwise use the existing **Not Reported** value. Preserve the provider's specific message and codes; do not replace them with an opaque application code. Never expose credentials or unrelated request bodies. A local exception is not a provider message and must not be passed off as one. For an API attempt with no server response, use the existing **No response received from the server.** detail. If the server answered without an error explanation, preserve any known status and use the existing no-explanation convention. For purely local validation/storage/player errors, do not invent a Provider Error block.

Capture the reason at the layer that knows it. Carry a structured result with the operation, exact source identity, whether a response arrived, failure category, and relevant provider evidence. Do not convert every caught exception to null, an empty list, or a guessed model-not-found result. A bare 404 for a discovery or speech path is not proof that the model or voice was removed.

A successful empty list or absent optional metadata is not a provider error: do not manufacture a Provider Error line containing a success status. Explain the actual result. Likewise, a failed optional discovery probe is not a user-visible failure if a later supported discovery source succeeds. Report the final relevant outcome once.

#### Message and action table

These are concrete message rules, not an exhaustive list of possible provider errors. Use the more specific established reason when available. Braced names are substituted from the affected source; do not show literal placeholders. The voice-list examples must be adapted accurately for the model/provider list or speech operation that actually failed.

| Evidence / situation | Title | Plain-language explanation | Actions, left to right |
| --- | --- | --- | --- |
| An attempted action requires an endpoint and none is selected | Endpoint Required | Select an endpoint before choosing a model. | Okay |
| An attempted action requires a model and none is selected | Model Required | Select a text-to-speech model before choosing its provider or adding it. | Okay |
| Only has no provider selection | Provider Required | Select a provider to use Only. | Okay |
| Add or provider-edit Save duplicates a saved endpoint/model/provider combination | Combination Already Exists | endpoint model and provider combination already exists. | Okay |
| Device connectivity is confirmed offline | No Internet Connection | The voice list could not be loaded because this device is offline. Reconnect and try again. | Cancel, Retry |
| The saved service address could not be resolved | Service Address Not Found | The address saved for {endpoint} could not be found. Check the Base URL in its API profile and your connection. | Okay |
| The connection was explicitly refused | Connection Refused | {endpoint} refused the connection while loading the voice list. | Cancel, Retry |
| Connecting timed out | Connection Timed Out | A connection to {endpoint} could not be established before the request timed out. | Cancel, Retry |
| Receiving the response timed out | Response Timed Out | The complete voice list did not arrive from {endpoint} before the request timed out. | Cancel, Retry |
| The connection's security could not be verified | Secure Connection Failed | A secure connection to {endpoint} could not be verified. Check the service address and this device's date and time. | Okay |
| A required API key is absent from the configured profile | API Key Missing | {endpoint} requires an API key, but none is saved in its API profile. | Okay |
| The service rejects authentication | API Access Rejected | {endpoint} did not accept the credentials for this request. Check the API key and authentication settings in its API profile. | Okay |
| The service refuses permission; use a more specific explanation if its response identifies one | Access Denied | {endpoint} refused access to the requested voice list. Its response is shown below. | Okay |
| The service explicitly reports a temporary request-rate limit | Too Many Requests | {endpoint} is limiting requests. Wait before trying again. | Cancel, Retry |
| The service explicitly reports a usage/spending limit | Usage Limit Reached | The account's usage or spending limit at {endpoint} has been reached. Check the account's limits before trying again. | Okay |
| The service explicitly reports no credits | No API Credits Remaining | {endpoint} reports that the account has no credits remaining. Add credits with the service before trying again. | Okay |
| The service reports a server failure | Service Error | {endpoint} reported a server error while loading the voice list. Its response is shown below. | Cancel, Retry |
| A complete successful voice-list response contains zero voices | No Voices Returned | {endpoint} returned an empty voice list for {model}. | Cancel, Retry |
| No supported voice-list source is available, rather than a network failure or a known empty list | Voice List Unavailable | No supported way to list voices was found for {model} at {endpoint}. | Okay |
| The response arrived but its voice-list data cannot be parsed | Voice List Could Not Be Read | {endpoint} responded, but its voice list could not be read. | Cancel, Retry |
| The response is readable but required voice identifiers are missing | Voice Information Missing | {endpoint}'s response did not include the voice identifiers needed to list voices for {model}. | Okay |
| Provider discovery is not available through the supported discovery methods | Provider List Unavailable | No supported way to list providers was found for {model} at {endpoint}. | Okay |
| The service explicitly says the selected model is unavailable | TTS Model Unavailable | {endpoint} reports that {model} is unavailable. Choose another model. | Okay |
| The service rejects a voice without establishing permanent deletion | Voice Not Supported | {endpoint} reports that {voice} is not supported for {model}. | Okay |
| The service explicitly rejects the requested provider-routing controls | Provider Routing Rejected | {endpoint} rejected the requested provider routing. The selected routing mode has not been changed. | Okay |
| The service explicitly reports that the requested provider cannot serve this request | Selected Provider Unavailable | The service could not use the selected provider for {model}. The requested provider settings have not been changed. | Okay |
| A speech request returns 404 without identifying the missing item | Speech Request Not Found | {endpoint} returned Not Found for this speech request without identifying what was missing. | Okay |
| A non-error response contains no audio | No Audio Returned | {endpoint} completed the request but returned no audio to play. | Cancel, Retry |
| Audio was returned in a format the player does not support | Audio Format Not Supported | The service returned audio in a format this player cannot play. | Okay |
| Local playback fails, without evidence of a provider fault | Audio Could Not Be Played | The returned audio could not be played. | Okay |
| An older API voice preference lacks enough information to identify its saved source | Voice Source Unavailable | The endpoint, model, and provider for this voice could not be identified. Choose a voice again in Select Voice. | Okay |
| The current API voice or its saved source is deleted and the previous voice cannot be restored | Selected Voice Is Permanently Unavailable | Please select a new voice. | Okay, Select New Voice |
| A non-active saved entry refers to a deleted API profile | API Profile No Longer Exists | The API profile used by this TTS selection was deleted. Choose another saved TTS selection. | Okay |
| The saved TTS list cannot be read | Saved TTS Models Could Not Be Read | The saved text-to-speech list could not be read. It has not been replaced or cleared. | Okay |
| Saving fails and lack of device space is confirmed | Not Enough Storage | There is not enough space on this device to save the TTS selection. The saved list and your current selections have not changed. | Okay |
| Saving fails without a more specific established reason | TTS Selection Could Not Be Saved | The TTS selection could not be saved. The saved list and your current selections have not changed. | Cancel, Retry |
| Deleting saved entries fails | TTS Selection Could Not Be Removed | The selected TTS entries could not be removed. They remain in the saved list. | Cancel, Retry |
| A server rejects the operation but gives no explanation | Request Rejected | {endpoint} rejected the request to load voices but did not explain why. Any details it supplied are shown below. | Okay |
| A local failure has no identified cause and no provider response | Voice List Could Not Be Loaded | The voice list could not be loaded, and the cause could not be identified. No provider response was received. | Cancel, Retry |

For known causes omitted from the table, write a direct explanation of that cause using the same standard. For example, a response that explicitly names an unsupported parameter must identify that parameter in the explanation rather than displaying only Request Rejected. A known region restriction must not be described as an invalid API key. Do not claim a timeout means that the service is down. When the operation is speech generation and no complete response arrived, do not promise the service did no work or incurred no charge.

Review every message for recovery value. Use the screen's actual field/control names when directing the user somewhere, and name the affected endpoint/model/voice only where it disambiguates the problem. Avoid blame, jokes, unnecessary apologies, unexplained technical terms, bare codes, and unsupported advice such as reinstalling the app. A server status is evidence to interpret with its response body, not enough by itself to guess the cause. The table's general fallback wording must not erase a more precise cause already known to the app. Keep raw provider detail below the understandable explanation. Preserve accessible text, focus, and readable action labels through the shared dialog components; do not rely on color alone to communicate the error.

If ordinary search/filters hide all otherwise loaded voices, preserve the existing empty-list presentation with **No voices match the current filters.** Do not misreport this as discovery failure or a missing model, and do not open an error dialog on every filter change. This is an informational state, not a failed request.

Missing voice discovery never licenses invented voice IDs, the generic OpenAI voice fallback for unrelated models, manual-entry controls, or deletion of the model. A valid saved model remains saved and its source stays accessible. Each time the user opens/reopens that source, attempt voice loading. If loading fails, show the specific error dialog and leave the exact text **Voices currently not available.** where the voice rows would normally appear. This is a plain placeholder, not a disabled source or an extra retry control. Use the dialog's applicable Retry action and allow reopening to try again. Do not permanently cache the failure as an unsupported/disabled source. When loading succeeds, replace the placeholder with that source's actual voices. This discovery failure must not trigger the deleted-voice fallback.

#### Actions, state, and successful removals

- **Retry** repeats the failed operation for the same still-valid source and requested routing. It does not switch endpoint/model/provider/voice, remove Only restrictions, clear saved data, or automatically retry paid generation. A new source selection invalidates an older dialog's retry target. Revalidate local existence before retrying.
- **Cancel** dismisses the retry/confirmation decision without starting another attempt or discarding the draft. It is the leftmost action. In an error or informational dialog, **Okay** acknowledges the message and leaves the user on the current screen; it is not an implicit retry or fallback. In a confirmation dialog, Okay performs only the action explicitly described by that confirmation, and Cancel declines it.
- Use Cancel then Retry for a failure that can usefully be attempted again without changing configuration. Use Okay when the user must change configuration/selection or the dialog is informational. Use Cancel then Okay for applicable confirmations. Do not add all three buttons when two or one suffice.
- Preserve atomic Add/Edit/Delete semantics so claims that saved data is unchanged are true. If an operation can partially succeed, reconcile what happened and explicitly report the affected entries; never show a blanket success or failure message that misstates the result.
- Removing a saved source removes its ability to supply future speech. If it supplies the current API voice, apply the approved previous-voice recovery in Section 7.7. Do not retain a hidden playable copy or recreate the removed entry. A transient failure or unreadable voice list is not a deletion and follows the normal error/placeholder flow instead.
- A successful cleanup confirmation keeps the existing text-cleanup behavior. For the new category, use title **Remove Unavailable TTS Models?** and explanation **Remove the saved TTS combinations whose models were reported unavailable by the last check? This removes them from Select API Voice Models and Select Voice.** Actions: Cancel, Okay. It must not claim that deletion rechecked availability.
- Keep the provider picker's explanation factual: **Choose the provider settings for this text-to-speech model. Available providers and routing support depend on the endpoint.** Do not mention chat favorites or guarantee undocumented routing.
- Show one relevant dialog per failed operation. Background/stale callbacks, user-requested cancellation, and switching away from an old source must not create duplicate or irrelevant failure dialogs. Existing cancellation and audio-release behavior remains intact.
- Error display does not authorize new persistent logs, transcript messages, stack-trace dumps, or diagnostics uploads.

Tests must distinguish every discovery-result category above, preserve provider-specific detail, verify Title Caps and Cancel-first ordering, verify Okay spelling, and prove that Retry/Okay/Cancel do not silently alter routing or saved data. Extend the existing error-classification/parser tests where applicable and add focused tests for the new TTS operation-to-message mapping. Do not test only that some dialog appeared.

Review transient failures separately from failures requiring changed settings: Retry must offer a useful repeat attempt, not a loop that predictably fails for the same permanent reason. Keep user cancellation distinct from failure, prevent duplicate dialogs from one operation, and preserve state when dismissing or retrying. Ordinary error handling and wording should be completed using these rules without another per-message owner approval request; new product behavior or destructive recovery remains outside that authority.

### 7.7 Deleted API voice: restore the previous selection

Apply this recovery rule only to a deleted or permanently unavailable current API voice/source, using the immediately previous global selection as specified below. This is the current permanent-removal policy; it does not implement the future Companion fallback chain or automatic fallback for transient provider errors.

1. Trigger this rule when the current API voice is confirmed deleted/unavailable permanently, or its saved source/profile is deliberately removed. An unreachable API, missing voice metadata, failed/empty voice-list request, or incomplete catalog does not prove permanent deletion.
2. Resolve the voice the user selected globally immediately before the deleted voice. Its identity includes the voice and its source, so the same voice ID from another endpoint/model/provider is not interchangeable.
3. If that previous selection can still be used, make it the active/default voice again using its own saved source configuration. Reflect that selection in Select Voice and use it for subsequent speech. It may be a device or API voice if that was the actual previous selection; do not choose an arbitrary device voice.
4. Do not resurrect deleted sources, restore stale credentials from history, or rotate among arbitrary older voices. If the immediately previous selection is absent or cannot be restored, use the dialog below. An automatic restoration must not overwrite history with the deleted voice and create a fallback loop.
5. Read and write the global selection/history only, regardless of which conversation or settings screen is open. Preserve playback cancellation protections. Pending work for a removed selection must not later replace the restored selection or begin stale playback.

Dialog:

- **Title:** Selected Voice Is Permanently Unavailable
- **Explanation:** Please select a new voice.
- **Left action:** Okay — dismiss the dialog without selecting an arbitrary replacement.
- **Right action:** Select New Voice — open the existing Select Voice screen (`VoiceBrowserActivity`) for the app-wide default voice; do not pass a chat-specific storage scope.

Use the shared dialog styling with the approved left-to-right action order. Do not rename Okay to Cancel, abbreviate it, add another button, or send Select New Voice to the API model-management screen. If no replacement is chosen, do not silently make the unavailable selection playable; a later speech attempt uses this same recovery/error rule. Include applicable provider-error evidence under the explanation using Section 7.6, without fabricating provider errors for a local deletion.

Tests:

- Selecting A and then B, followed by confirmed deletion of B, restores A when A remains usable.
- Browsing/previewing C does not replace A in that previous-selection history.
- No usable previous selection produces the exact title/explanation and Okay then Select New Voice actions.
- Select New Voice opens the existing voice browser; Okay does not choose a replacement.
- A deleted previous source is not re-created and does not become a hidden fallback.
- Temporary failures and missing voice lists keep the source, show the correct error/placeholder, and do not trigger permanent-unavailability recovery.
- Reopening a source after failed discovery attempts loading again; later success restores the real voice list.

## 8. Safe implementation phases

Every phase below delivers application code and tests, with a clean stopping point after its completion and handoff. The recommended execution is one phase per session. Verification belongs to that work, not a separate review-only phase. Implement only the phase or phases authorized by the current task; do not automatically begin the next phase or assume the same model instance/conversation will perform it. Multiple phases may be authorized together, but the boundaries and handoff records still apply.

| Phase | Required code already present | Reference sections in this plan | Code delivered |
| --- | --- | --- | --- |
| 1 | Current application baseline; no earlier feature phase | 3.2, 6, 7.1–7.2; history requirements in 7.7 | Saved TTS source storage, previous-selection storage, and the editable speech endpoint field |
| 2 | Phase 1 profile/storage contracts | 3.3, 4.2–4.3, 5, 7.3–7.6 | TTS discovery, metadata, grouped-price comparator, routing, speech transport, and structured errors |
| 3 | Phases 1–2 contracts | 3.1 picker/display rules, 3.3, 5, 6, 7.2–7.4, 7.6 | TTS-only model picker, provider picker, and isolated filter panel |
| 4 | Phases 1–3 contracts | 3.1, 6, 7.1–7.3, 7.6 | The manager, saved table, Add/Edit/Delete flow, and Voice & Speech navigation row |
| 5 | Phases 1–4 contracts | 3.4, 6, 7.4–7.7 | Source-driven voice selection, preview/readback, deleted-voice recovery, and removal of the engine-toggle tile |
| 6 | Phases 1–5 contracts | 3.4, 4.4, 7.1–7.2, 7.6–7.7 | Unavailable TTS Models scanning, report reconciliation, and scoped cleanup |

For any phase, use the relevant verification rules in Section 9 and test paths in Section 10. Read definitions in Section 2 only as needed. Earlier phase narratives need not be reloaded when their implemented contracts and handoff record supply the required context.

For each implementation phase, create or update a small repository handoff file named `tts-api-models-implementation-status.md`. This is a proposed implementation artifact, not an existing file or a separate phase. Record the phase's completed/partial status, implementation branch and tested commit, actual changed/new file paths, public entry points and result contracts needed next, exact test commands/results, and any concrete remaining work. Do not store secrets, private conversation, or model attribution. Record only work actually done; planned classes do not count as implemented prerequisites.

A fresh instance should be able to use this plan, that record, and the code to start its assigned phase immediately. If a required contract is missing, name the missing code and dependency; do not claim success after merely reading documents or create another planning stage. Partial development checkpoints are not permission to publish an incomplete feature.

### Phase 1 — Persistent source data and the speech endpoint field

**Depends on:** the current application baseline; no earlier feature phase.

**Why first:** later screens must use a stable source identity and must not borrow chat preferences as temporary storage.

Primary files to inspect/change:

- `app/src/main/java/org/teslasoft/assistant/preferences/dto/ApiEndpointObject.kt`
- `app/src/main/java/org/teslasoft/assistant/preferences/ApiEndpointPreferences.kt`
- `app/src/main/java/org/teslasoft/assistant/ui/activities/ApiEndpointEditorActivity.kt`
- `app/src/main/res/layout/activity_api_endpoint_editor.xml`
- `app/src/main/res/layout/activity_api_endpoint_editor_new.xml`
- New dedicated TTS-entry model/store and focused tests.

Steps:

1. Add a speech-path property without shifting existing positional constructor arguments. Prefer a trailing defaulted property or another compatibility-preserving change.
2. Add its default, read/write handling, endpoint-copy handling, and any existing profile serialization paths affected by the field. Missing values on older profiles use the normal speech path; preserve explicit custom values.
3. Reuse the chat-path normalization convention. Test leading/trailing slashes and a base URL containing a path prefix. A base ending in `/api/v1/` plus `/audio/speech` must retain `/api/v1/`.
4. Add the approved field beneath Chat Completions Endpoint in both layouts, using shared field styles. Connect load, edit, dirty-state detection, save, and cancel behavior.
5. Add the saved-entry store and stable entry IDs. Do not use chat-favorite upsert identity.
6. Add non-UI store operations to load, add, replace routing by entry ID, and remove explicit entry IDs or exact endpoint/model targets.
7. Keep existing speech preferences intact. Do not automatically manufacture entries from chat favorites, current chat models, or every endpoint's catalog.
8. Keep secrets in the existing endpoint secret store. Do not duplicate them in navigation extras or saved source JSON when an endpoint ID can be used.

Tests:

- Old profiles without the field still load with the normal speech path.
- A custom speech path survives save/reopen and unrelated profile edits.
- Changing the chat path does not change the speech path, and vice versa.
- Endpoint rename preserves saved-entry references and credentials.
- Two entries can internally represent the same endpoint/model with different routing without overwriting each other.
- Editing one entry's route preserves its ID and all other entries.
- Failed persistence preserves the existing collection and does not look like success.
- Malformed stored content does not trigger a destructive empty-list rewrite.
- Global previous-voice identity survives reopening and stays separate from the current global voice and Google last-known-good behavior; no history factory takes a chat ID.

Completion: storage and profile-field tests pass; no active chat selection or Google voice behavior changes. The field must be connected to the speech transport before a release claims it is functional.

Handoff to Phase 2: identify the actual endpoint speech-path property, saved-entry schema/store operations, source-ID rules, previous-selection storage, and tests. The next instance must not need to invent replacements for these contracts.

### Phase 2 — TTS catalogs, metadata, routing, and transport contracts

**Depends on:** Phase 1. This phase is primarily internal and testable without new screens.

Primary files:

- `app/src/main/java/org/teslasoft/assistant/tts/voices/ApiSpeechCatalogClient.kt`
- `app/src/main/java/org/teslasoft/assistant/providers/ProviderDiscoveryResolver.kt`
- `app/src/main/java/org/teslasoft/assistant/providers/ProviderEndpointsParser.kt`
- `app/src/main/java/org/teslasoft/assistant/providers/ProviderRoutingSerializer.kt`
- New TTS-specific metadata, source-resolution, and request-building helpers as needed.

Steps:

1. Introduce a TTS catalog result that retains exact model IDs and capability evidence. Do not reduce the response to guessed model-name matches.
2. For OpenRouter, support its speech-filtered catalog and nested `architecture.output_modalities`. Do not include speech-to-text, audio-input-only, or unrelated audio-chat models simply because their names contain “audio.”
3. Keep model-specific `supported_voices` attached to the correct model. Do not flatten every model's voices into one endpoint-wide list.
4. Represent missing voice discovery separately from an empty known catalog, request failure, and model absence.
5. Discover provider endpoints for the selected TTS model, using the existing endpoint identity/authentication and configurable discovery path where applicable.
6. Parse only metadata needed for the six approved columns. Do not treat an empty `supported_parameters` array as evidence that a TTS model is absent.
7. Keep privacy values nullable. Obtain ZDR from authoritative endpoint-specific data or the existing authoritative ZDR list. Do not assume a missing field is false, or use a provider's general policy as an endpoint-specific guarantee without an explicit basis.
8. Track the billing basis separately from the numeric price. Preserve input/output components if the model charges both. Implement Section 5's normalization and grouped comparator as testable helpers. Use decimal-safe formatting so a small paid rate does not become “free.”
9. Reuse the chosen text-page latency/uptime definitions consistently. Do not mix 30-minute and daily uptime values without identifying the chosen metric internally and preserving its meaning.
10. Implement the routing-state/payload contracts in Section 7.3. Keep raw speech routing adjustable per endpoint. Do not rely on a speech SDK that silently excludes the required fields.
11. Build the shared speech transport in Section 7.5. Keep it independent of the active chat client.
12. Add cancellation and stale-result protection before connecting the transport to views.
13. Preserve structured discovery/transport failures and implement the message rules in Section 7.6. Never turn an API failure into an empty voice list. Share applicable chat-error parsing/detail formatting without copying chat wording or logging side effects.

Tests:

- Nested speech metadata includes valid TTS models even when their IDs contain no “tts.”
- STT and audio-input-only entries are excluded from the TTS picker.
- Voice lists from different models cannot contaminate each other.
- Missing voices do not mark an otherwise present model unavailable.
- Unknown latency/privacy remains unknown; explicit false remains false.
- Prices preserve units, currency, zero-versus-missing distinction, and small nonzero amounts.
- Section 5's comparator examples pass, including equivalent unit scaling, incompatible billing groups, both sort directions, unknown rates, and paid-input/zero-output entries.
- Automatic/Preferred/Only generate their intended payloads; Only never becomes an unrestricted retry.
- Speech provider options survive routing composition.
- Requests use the saved source's endpoint, authentication, model, voice, path, and timeouts even when the chat uses a different profile.
- Audio errors are not passed to the player as MP3; cancellation prevents late playback.
- Offline, connection timeout, response timeout, rejected access, missing discovery, empty results, and malformed data select distinct truthful explanations.
- Provider-error text and response metadata come from the same failed TTS attempt; local errors are not attributed to the provider.

Completion: focused tests verify discovery and outgoing request behavior. Record live-routing support separately; do not mark it verified from these tests.

Handoff to Phase 3: identify the model/provider/voice discovery interfaces, capability/error result types, price comparator, routing builder, and transport entry points with their tests. Include the exact source identity required by every request.

### Phase 3 — TTS model picker, provider picker, and filter panel

**Depends on:** Phases 1–2.

**Why before the manager:** the manager should open working pickers, not temporary or chat-mutating destinations.

References:

- `app/src/main/java/org/teslasoft/assistant/ui/fragments/dialogs/AdvancedModelSelectorDialogFragment.kt`
- `app/src/main/res/layout/fragment_model_selector.xml`
- `app/src/main/java/org/teslasoft/assistant/ui/activities/ChooseProviderActivity.kt`
- `app/src/main/res/layout/activity_choose_provider.xml`
- `app/src/main/java/org/teslasoft/assistant/ui/activities/ProviderFilterPanelActivity.kt`
- `app/src/main/res/layout/activity_provider_filter_panel.xml`
- `app/src/main/java/org/teslasoft/assistant/providers/ProviderFilterState.kt`
- `app/src/main/java/org/teslasoft/assistant/ui/adapters/ModelListAdapter.kt`
- `app/src/main/java/org/teslasoft/assistant/ui/adapters/FavoriteModelListAdapter.kt`
- `app/src/main/res/layout/view_model.xml`

Steps:

1. Add an explicit TTS selection purpose or a focused wrapper around reusable picker components. Do not globally change the existing chat/image/rule picker purposes.
2. Pass an endpoint ID and current TTS draft model explicitly. Never fall back to the active chat endpoint while choosing for another profile.
3. Open directly on the existing View All presentation, using `newAllModelsInstance` / `ARG_START_WITH_ALL_MODELS` as the behavior reference and adding an explicit TTS purpose. Keep the full-screen presentation, search, and model-selection appearance, but show only TTS models. Remove favorite/star actions and any favorites landing from this purpose. The existing all-models mode still has favorite actions, so enabling that flag alone is not sufficient.
4. Route any picker selection back to its caller. Do not call `Preferences.setModel`, add a chat favorite, or invoke chat Only-provider recovery from a TTS pick.
5. Implement the TTS provider screen with picker-local routing state, the existing mode-specific selection interaction, and the six approved columns only. Preserve applicable Preferred ordering/fallback interaction as specified by the existing text behavior; do not add unrelated text capabilities.
6. When opened from a saved TTS row, lock its endpoint/model. Do not expose a model change that would contradict the fixed table columns.
7. Keep the separate Filters panel and its open/close behavior with the exact non-privacy controls and defaults in Section 5. Use isolated TTS filter state. Remove inapplicable text filters only in the TTS purpose. Do not add ZDR or Training/Data Use filtering; those fields are informational table cells only.
8. Apply the approved boolean and price presentation and Section 5's grouped price ordering. An informational X must not behave like a checkbox or delete control.
9. Ensure Save returns a complete routing result and Cancel/back does not save. Do not reuse `persistDirectly` behavior that writes to chat favorites.
10. Keep the manager's approved Select Provider title/subtext separate from the factual picker-body text in Section 7.6. Do not mention chat favorites, guarantee undocumented routing, or restore the removed manager navigation row or its old subtitle.

Tests:

- TTS picker selection never changes chat model/favorites or image-generation settings.
- TTS picker opens directly on View All with a TTS-only catalog; no favorites landing, saved-model tab, or favorite/star action appears. Back returns to the caller.
- Endpoint A's search results never appear in endpoint B's picker.
- Text and TTS filter states do not affect each other.
- The initial provider order is alphabetical. Price sorting groups compatible rates without inventing cross-unit conversions, sorts inside each group, and leaves the six-column layout unchanged.
- ZDR and Training/Data Use appear as informational columns and have no privacy-filter controls.
- All six data headings are present in the required order, without literal separator dots.
- True/false/unknown cells match X/blank/?.
- Provider selection, order, mode, and applicable fallback values survive save/reopen.
- Cancel preserves all previous values.
- An incomplete Only selection follows explicit validation; it is not converted to Automatic.
- A saved-row provider edit cannot change that row's endpoint/model.

Completion: both pickers are usable independently through their final caller contracts. No chat side effects or placeholder routes remain.

Handoff to Phase 4: identify the picker launch arguments, draft-versus-saved-entry targets, Save/Cancel result contracts, isolated filter state, and integration tests. Include how the saved-row caller locks endpoint/model.

### Phase 4 — Manager screen, saved table, and Voice & Speech entry point

**Depends on:** Phases 1–3.

Suggested new screen: `ApiVoiceModelsActivity` with its own layout, using existing shared styles. The exact internal name may differ.

Steps:

1. Assemble the approved vertical sequence: header; Endpoint; AI Model; Select Provider section title and its exact subtext; one row with the mode dropdown and provider selector; Add Model; saved table. Do not insert a separate Choose Provider navigation row.
2. Use the model row's **Select** value until a model is chosen. Do not invent a default speech model.
3. Load endpoint options from saved profiles. A dropdown choice must not change the active chat endpoint.
4. When the draft endpoint/model changes, prevent old dependent provider data from being applied to the new target. Do not keep an old provider bound to a different model merely because its display name matches.
5. Connect the pickers using explicit result targets and lifecycle-safe state restoration. The inline provider-selector area is the entry point: show Select before a provider is chosen, open the TTS provider picker when it is tapped, and replace Select with the selected-provider display after a saved selection. The same area remains tappable afterward.
6. Make the manager dropdown and provider picker reflect one routing state. Saving in one must be visible in the other without reopening the manager.
7. Implement Add Model's transaction sequence. Clear the upper fields only after the saved list has been successfully updated.
8. Render the four approved saved-table columns using shared styles and one width definition. Endpoint/model are display-only; Provider opens that entry's picker; X targets only that row.
9. Put the complete add area and saved rows in the same vertical scroll flow beneath the standard header. Apply the approved duplicate dialog, existing provider-name display, and deletion recovery. Keep provider controls visible by default for API TTS endpoints and use capability/discovery to determine their function. Do not insert another confirmation or undo control unless approved.
10. Add Select API Voice Models immediately above Advanced Voice Settings (`tile_voice_advanced`) in Voice & Speech. Register the finished activity and preserve existing navigation conventions.
11. Handle rotation/recreation and returning from child screens without losing the draft or adding the same entry twice.

The top engine-toggle tile is removed in Phase 5 together with completed voice-driven routing, so the development branch does not lose its existing control before the replacement behavior is connected. Do not publish this intermediate state as the finished feature.

Tests:

- Initial model value is Select; routing is neutral Automatic; the separate inline provider-selector value is Select.
- The manager has one Select Provider section with the exact approved subtext and one mode/selector row beneath it; no separate Choose Provider navigation row remains.
- Tapping Select opens the TTS provider picker, saving updates the inline provider display and mode, and tapping the selected-provider display reopens that picker.
- Add saves one correct combination and resets all specified upper fields, including Automatic mode and the provider-selector value Select.
- Failed Add retains the draft and existing rows.
- Duplicate Add and provider-edit Save preserve existing data and show the exact approved message with one Okay button. A different mode alone does not bypass the duplicate check.
- Provider-name display matches the existing text-model behavior: selected provider for Only, highest-priority provider for Preferred, and Select for the manager's unselected provider value.
- Provider controls remain visible for an untested endpoint; function depends on capability/discovery, not a visibility whitelist.
- Provider edit changes one saved row and preserves endpoint/model/entry ID/list position.
- Editing a saved row does not overwrite the upper add draft.
- X removes only its target; identical model names on other endpoints remain.
- Long names, many entries, landscape, and larger text do not hide the Provider/X actions or break table alignment.
- The upper configuration area scrolls with the saved rows; there is no pinned add area or separate table-only vertical scroll.
- Back navigation does not create a saved entry from an incomplete draft.

Completion: the full source-management flow works. No device or API voice is automatically activated merely because an entry was added.

Handoff to Phase 5: identify the manager/navigation entry points, saved-store observation mechanism, stable row/source IDs, provider-edit results, and persistence tests. State that the legacy engine tile is intentionally removed together with voice-driven routing in Phase 5.

### Phase 5 — Select Voice integration, preview, and real readback

**Depends on:** Phases 1–4. Apply Sections 7.6–7.7 throughout this phase.

Primary files:

- `app/src/main/java/org/teslasoft/assistant/ui/activities/VoiceBrowserActivity.kt`
- `app/src/main/java/org/teslasoft/assistant/tts/voices/VoiceBrowserProvider.kt`
- `app/src/main/java/org/teslasoft/assistant/tts/voices/VoiceBrowserModels.kt`
- `app/src/main/java/org/teslasoft/assistant/tts/voices/OpenAiVoiceProvider.kt`
- `app/src/main/java/org/teslasoft/assistant/tts/voices/VoiceIdentityRegistry.kt`
- `app/src/main/java/org/teslasoft/assistant/tts/voices/LastKnownGoodVoiceRegistry.kt`
- `app/src/main/java/org/teslasoft/assistant/preferences/Preferences.kt`
- `app/src/main/java/org/teslasoft/assistant/ui/activities/ChatActivity.kt`
- `app/src/main/java/org/teslasoft/assistant/ui/activities/VoiceSettingsActivity.kt`

Steps:

1. Preserve the Google provider registration. Append API registrations derived from the saved source collection, with a unique source ID per saved entry.
2. Display the endpoint name, model name, and provider name for each API source. Use the existing provider-name display behavior; do not add descriptive prose. Two entries for one model with different providers must be distinguishable.
3. Construct each API provider with an explicit source reference; remove reliance on the current chat endpoint from that path.
4. On source selection, stop the old preview and load the correct source's voices. Browsing a source alone must not switch the active voice.
5. Refresh source registrations after additions, edits, or removals without using stale list positions as IDs.
6. Keep voice metadata model/source-specific. Do not infer gender, language, or quality merely to populate filters; preserve existing user-assigned identifiers and metadata behavior.
7. Scope API voice overrides, rejected-voice knowledge, loaded lists, and preview caches so identical voice IDs under different sources cannot collide. Preserve existing Google keys and data.
8. Activate a voice with enough identity to resolve its saved source and exact voice. Preserve the immediately previous user-selected voice for Section 7.7 recovery. Store the default, previous-selection history, and last-known-good record globally; browsing and preview do not change activation history.
9. Connect previews and ChatActivity readback to the Phase 2 speech resolver/transport. Do not leave previews correct while real chat speech still uses the chat API client.
10. Remove the `tile_tts` container from `activity_voice_settings.xml` and remove `tileTTS` creation, fragment placement, toggle listener, and resume-time checked-state updates from `VoiceSettingsActivity`. Remove only tile-specific dead references; preserve unrelated tiles and strings still used elsewhere. Update `updateVoiceBrowserRow()` to display the actual selected voice/source. Follow Section 7.5 for engine-preference compatibility, selection-derived routing, and older settings; do not add a replacement engine control.
11. Preserve the editable preview text, metadata filters, separate preview action, checked/unchecked selection marks, long-press editor, Stop behavior, and current device-voice availability behavior.
12. Apply Section 7.7 when the current API voice/source has been deleted: restore the immediately previous usable selection, otherwise show the exact permanent-unavailability dialog. Use Section 7.6 for transient/discovery errors, retaining the source and displaying Voices currently not available. Preserve unrelated legacy/Google behavior.
13. Do not describe a merely preview-capable voice as successfully tested. Keep previous-selection history distinct from catalog-advertised and successfully played states. Extend the existing registry or add a focused history store without changing unrelated Google fallback behavior.

Tests:

- Every saved entry appears once as an API source; unrelated unsaved models do not appear.
- Removing/editing an entry updates the source list on return.
- Source A's late load/preview result cannot update or play for source B.
- Browsing does not activate; selecting a voice activates the exact source/voice.
- Same voice ID on two source entries does not share rejection or override state accidentally.
- Preview and full readback use the same endpoint/model/routing/path/authentication.
- Changing the chat model/endpoint does not redirect the chosen speech source.
- Voice & Speech has no Google/OpenAI engine-toggle tile or replacement engine selector. Selecting a Google voice uses Google; selecting an API voice uses that exact saved source, regardless of a stale legacy engine flag.
- Returning to Voice & Speech shows the current voice/source in its existing Select Voice row. Relaunching, switching conversations, and global previous-voice recovery keep display and actual speech routing consistent.
- Older Google selections survive. An unresolvable older API preference produces the specific source error without creating a saved entry, deleting preference data, or guessing the active chat's endpoint.
- Cancel/Stop prevents late playback and releases audio resources.
- Existing Google browsing, filtering, renaming, selection, and readback continue to work.
- A present model with unavailable voice discovery remains accessible; opening it retries loading and failures show the exact placeholder plus the specific error dialog.
- Deleted-current-voice recovery, previous-selection history, and both permanent-unavailability dialog actions follow Section 7.7.

Permitted development checkpoint: the source list may be completed before full playback integration. Identify it as partial and continue the remaining authorized work. Use Section 7.6 for actual discovery failures; unfinished playback and unverified voices must not appear to work.

Completion: identify exactly which endpoint/model/provider/voice combinations were tested. A build alone does not establish correct voices, playback, or remote routing.

Handoff to Phase 6: identify the global active-voice/source resolver, global activation/history and last-known-good stores, removal-recovery entry point, source-list refresh path, stable-chat-ID contract, and tests. Do not carry forward a per-chat voice-history constructor or any title-derived ID remapping. Report actual playback/routing verification separately from mocked tests so the next instance does not mistake an untested service for a verified one.

### Phase 6 — Unavailable TTS Models in Clean Up Models

**Depends on:** Phases 1–5, including stable saved-source storage and the implemented global active-voice removal/recovery behavior. Read the Phase 5 handoff; chat IDs are immutable across renames, and all default/recovery voice records are app-wide. This phase deletes saved entries.

Primary files:

- `app/src/main/java/org/teslasoft/assistant/preferences/models/ModelCleanupReferences.kt`
- `app/src/main/java/org/teslasoft/assistant/preferences/models/ModelCleanupPolicy.kt`
- `app/src/main/java/org/teslasoft/assistant/preferences/models/ModelCleanupReportStore.kt`
- `app/src/main/java/org/teslasoft/assistant/providers/ModelCatalogAvailabilityClient.kt`
- `app/src/main/java/org/teslasoft/assistant/ui/activities/ModelCleanupActivity.kt`
- `app/src/main/res/layout/activity_model_cleanup.xml`
- `app/src/main/res/values/strings.xml`
- The new saved TTS store and `ModelCleanupPolicyTest.kt`.

Steps:

1. Collect TTS targets only from explicitly saved entries. Deduplicate network checks by exact endpoint ID + model ID; do not scan once for every provider combination.
2. Extend the local reference loader's completeness check. If saved TTS data cannot be read, do not prune its report entries or treat it as an intentionally empty list.
3. Include TTS references in report reconciliation so a TTS-only reference is not discarded merely because it is absent from Favorites and Model Rules.
4. Verify that each availability check covers the appropriate model category. A chat-only or UI-filtered catalog is not an authoritative list of all speech models.
5. Reuse the existing failed/empty/malformed-response and OpenRouter exact-ID/alias safeguards. A voice-list failure or one unavailable provider must not establish that the model is gone.
6. For APIs with pagination or incomplete discovery, complete the required check or mark it inconclusive. Do not turn a partial page into an absence result.
7. Preserve endpoint-specific identity: disappearance from endpoint A does not imply disappearance from endpoint B.
8. Add the **Unavailable TTS Models** category after the existing categories. Reuse the existing category presentation and user-triggered cleanup pattern, with the new category's specific confirmation in Section 7.6.
9. Scope the TTS category's removal operation to matching saved TTS entries. If a missing endpoint/model has multiple provider combinations, remove the matching combinations, not one arbitrary row.
10. Do not remove chat favorites or model-rule targets through the TTS delete action, even if they share an endpoint/model identity. Existing categories retain their own actions.
11. Use the existing text-model cleanup deletion timing: act on the saved availability report with the existing confirmation pattern. Do not introduce a separate fresh network check at TTS deletion time or change the existing categories' behavior.
12. Reconcile the report after a successful removal and refresh the manager/Voice Browser from the same saved store.
13. If cleanup removes the source of the current global API voice, call the global removal-recovery entry point from Phase 5 once, independent of the open chat. Do not enumerate chats or rewrite per-chat voice fields. Apply Section 7.7: restore its immediately previous usable selection; if that is not possible, show the specified dialog with Okay and Select New Voice. Never choose an arbitrary replacement or recreate a removed entry.

Availability decision table:

| Observation | TTS cleanup treatment |
| --- | --- |
| Exact saved model or accepted alias is present | Keep its saved entries; remove an obsolete unavailable status after a conclusive recovery check. |
| Model is conclusively absent under the existing applicable catalog/exact-lookup rules | Eligible for the TTS cleanup category and the approved user-confirmed removal flow. |
| Timeout, auth failure, rate limit, server error, malformed or empty response | Inconclusive; do not newly declare the model missing or remove its entries. |
| Voice discovery missing/failing | Not model absence. Keep saved model entries. |
| Selected upstream provider is down or no longer offered, but model remains | Not model absence. Do not remove the model's saved entries through this category. |
| TTS picker filters hide the model | Not model absence. |
| Endpoint profile cannot be resolved | Do not substitute a default endpoint and check the wrong service; follow the approved missing-profile policy. |
| Existing old warning followed by an inconclusive check | Preserve the existing report's warning/unchecked distinction; do not misrepresent it as a new successful absence check. |

Tests:

- TTS-only saved entries participate in a scan and survive local report pruning.
- Unsaved speech models are not cleanup targets.
- Failed/empty/partial/inconclusive checks do not newly create TTS deletion candidates.
- An accepted alias is kept; an inconclusive alias lookup is kept.
- Provider outage and missing voice metadata do not remove a model.
- The same model on a different endpoint is untouched.
- Multiple saved provider combinations for a removed endpoint/model are all addressed by the TTS cleanup action.
- TTS deletion does not modify chat favorites, rules, Google voice data, or unrelated endpoint profiles. Only the specified global-selection recovery may change the active voice.
- Canceling confirmation removes nothing.
- Removing entries updates both the management table and Select Voice source list.
- Removing the active source recovers the same global default from any conversation or settings entry point. Inactive-source removal leaves the global default and recovery records unchanged.
- An interrupted or failed mutation does not claim completion or erase unrelated entries.
- Previously saved reports remain readable and correctly reconciled.

Completion: destructive scope, inconclusive-check handling, and active-selection consequences have focused test coverage; existing cleanup behavior remains intact except for changes explicitly approved.

Final implementation handoff: identify the TTS scan/reference/report/deletion code and tests, update the implementation status record, and report the applicable verification results below. Do not imply that another model instance must reconstruct completed work from conversation history.

## 9. Verification requirements and regression matrix

### 9.1 Verification included with code work

Apply these requirements to the relevant code changes in each phase and to the combined feature before release. They are not a separate phase or a review-only assignment.

1. Inspect the final diff for unrelated changes, copied raw styles, unapproved strings, new logs, secret handling, and chat-favorite side effects.
2. Run focused existing tests first, extending fixtures where they naturally fit. Add a new test file only for a new behavior with no suitable existing suite.
3. Run the repository's Android Checks workflow when code work is authorized: unit tests, debug APK assembly, and instrumentation-test compilation. Do not spend Work Mode time rebuilding the whole Android SDK environment.
4. Review the screens on a device/emulator for navigation, table alignment, scrolling, larger text, long names, and theme-resource use. Do not claim on-device verification if it was not performed.
5. Verify both API preview and actual chat readback. Record endpoint/model/provider/voice identity and requested routing mode in the test report without exposing credentials or adding application logs.
6. A live Only-routing verification must establish the actual responding provider or demonstrate that a forbidden route is not used. A successful audio response by itself is insufficient.
7. Preferred fallback behavior needs an appropriate controlled test; a model with only one provider cannot prove multi-provider fallback.
8. Live API calls may incur charges. Use owner-authorized test credentials and bounded test inputs; no paid calls were authorized or made during this planning task.
9. Update `ui-style-adoption.md` only for the surfaces actually audited/converted. Do not claim untouched local components are fully standardized.
10. Report completed phases, any concrete implementation blockers, test results, live service verification, and device review separately. Publishing this plan does not authorize a future application-code merge; obtain that authorization for the implementation branch.

### 9.2 Regression scenarios

| Scenario | Required result |
| --- | --- |
| Chat is renamed manually or automatically | Only the title changes; the exact stored chat ID and its data locations stay unchanged. |
| Chat is opened, created, renamed or deleted | Global default, previous-selection history and last-known-good voice remain independent of that chat. |
| Voice is selected from one conversation, then another is opened | Both use the same global selected voice and exact saved source. |
| Default voice is changed | Previous-selection history records the preceding activation; the separate last-known-good record is not overwritten by selection alone. |
| Renamed chat is opened or read for backup | Resolve its stored ID, not a hash of the new title; no backup format or ID migration is introduced. |
| Chat uses endpoint A; speech source uses endpoint B | Chat remains on A; preview/readback use B. |
| Voice & Speech is opened | The Google/OpenAI engine-toggle tile is absent; Select Voice remains and displays the selected voice/source. |
| User activates a device voice, then an API voice | The speech source follows the activated voice; no separate engine toggle is needed and a stale legacy engine flag cannot override it. |
| Same model saved with two different provider settings | Entries remain independently addressable. |
| Provider edited in an existing row | Only that entry's routing changes; model/endpoint remain fixed. |
| Provider picker canceled | No saved or draft routing is changed. |
| Add fails | Existing table and current draft remain intact. |
| Source changes while voices load | Old response cannot populate the new source. |
| Stop pressed during generation/download/playback | No late audio starts after cancellation. |
| Catalog omits a supported alias | Individual lookup protects the valid saved ID where supported. |
| Provider metadata or privacy policy is absent | Show unknown, not a fabricated no/yes. |
| Speech price is character-, byte-, token-, or duration-based | Preserve the actual unit; do not compare unlike units as plain numbers. |
| Provider picker is opened, then Price sorting is selected | Start alphabetically; price sorting groups compatible billing bases and sorts within groups according to Section 5. |
| One upstream provider is down | Do not conclude the TTS model or selected voice was permanently removed. |
| A known model's voices cannot be loaded | Keep the model/source accessible, show the specific error and **Voices currently not available.**, and retry loading when reopened. |
| Current API voice is deleted and its previous selection is usable | Restore that previous selection as the active/default voice. |
| Current API voice is deleted and no previous selection can be restored | Show Selected Voice Is Permanently Unavailable with Okay then Select New Voice; the latter opens Select Voice. |
| TTS cleanup removes a model | Matching TTS entries disappear from manager/source list; unrelated data remains. |
| Existing text/image/rule model pickers used afterward | Their selection, favorite, routing, and filter behavior is unchanged. |
| Existing Google voice used afterward | Its current selection, metadata, preview, and readback behavior is unchanged. |

## 10. Existing tests and reference files

Use these as starting points; recheck current contents before editing:

- `app/src/test/java/org/teslasoft/assistant/preferences/ApiEndpointStableIdTest.kt`
- `app/src/test/java/org/teslasoft/assistant/preferences/dto/ApiEndpointOpenRouterCatalogAuthorityTest.kt`
- `app/src/test/java/org/teslasoft/assistant/tts/voices/ApiSpeechCatalogClientTest.kt`
- `app/src/test/java/org/teslasoft/assistant/tts/voices/VoiceBrowserControllerTest.kt`
- `app/src/test/java/org/teslasoft/assistant/providers/ProviderEndpointsParserTest.kt`
- `app/src/test/java/org/teslasoft/assistant/providers/ProviderDiscoveryResolverTest.kt`
- `app/src/test/java/org/teslasoft/assistant/providers/ProviderRoutingSerializerTest.kt`
- `app/src/test/java/org/teslasoft/assistant/providers/ProviderRoutingResolverTest.kt`
- `app/src/test/java/org/teslasoft/assistant/providers/ProviderRoutingEnforcerTest.kt`
- `app/src/test/java/org/teslasoft/assistant/providers/ProviderFilterStateTest.kt`
- `app/src/test/java/org/teslasoft/assistant/preferences/models/ModelCleanupPolicyTest.kt`
- `app/src/test/java/org/teslasoft/assistant/ui/fragments/dialogs/QuickSettingsProviderDisplayTest.kt`
- `app/src/main/java/org/teslasoft/assistant/ui/fragments/dialogs/QuickSettingsProviderDisplay.kt`
- `app/src/test/java/org/teslasoft/assistant/util/GenerationErrorClassifierTest.kt`
- `app/src/test/java/org/teslasoft/assistant/util/GenerationErrorServerEvidenceTest.kt`
- `app/src/test/java/org/teslasoft/assistant/util/ProviderErrorInfoTest.kt`
- `app/src/test/java/org/teslasoft/assistant/providers/ProviderDiagnosticPipelineTest.kt`
- `.github/workflows/android-checks.yml`

## 11. Evidence references

These support the inspected starting facts, not authorization to change product requirements.

- [Inspected application revision](https://github.com/SoulPhosphor/speak-gpt/tree/f2bdf0047c0751e14d91a12179ee5ad921f804be)
- [UI style guide](https://github.com/SoulPhosphor/speak-gpt/blob/f2bdf0047c0751e14d91a12179ee5ad921f804be/ui-style-guide.md)
- [UI adoption status](https://github.com/SoulPhosphor/speak-gpt/blob/f2bdf0047c0751e14d91a12179ee5ad921f804be/ui-style-adoption.md)
- [OpenRouter TTS guide](https://openrouter.ai/docs/guides/overview/multimodal/tts)
- [OpenRouter speech API reference](https://openrouter.ai/docs/api/api-reference/tts/create-speech)
- [Official speech SDK source](https://github.com/OpenRouterTeam/typescript-sdk/blob/main/src/models/speechrequest.ts)
- [Official chat-routing SDK source](https://github.com/OpenRouterTeam/typescript-sdk/blob/main/src/models/providerpreferences.ts)
- [OpenRouter provider routing](https://openrouter.ai/docs/guides/routing/provider-selection)
- [OpenRouter ZDR policy and endpoint list](https://openrouter.ai/docs/guides/features/zdr)
- [OpenRouter provider logging/training policy](https://openrouter.ai/docs/guides/privacy/provider-logging)
- [Live speech-model catalog used for read-only inspection](https://openrouter.ai/api/v1/models?output_modalities=speech)
- [Inspected speech-provider metadata response](https://openrouter.ai/api/v1/models/deepgram/flux-tts:free/endpoints)
- [Inspected exact speech-model lookup](https://openrouter.ai/api/v1/model/fish-audio/s1)
- [Speech collection showing differing billing bases](https://openrouter.ai/collections/text-to-speech-models)

## 12. Completion checklist for the future implementer

- [ ] All work belongs to the authorized milestone; no application change was inferred from this planning task alone.
- [ ] Each completed phase includes code, tests, and a repository handoff record usable by a fresh instance. Repository reading and verification have not been turned into standalone phases.
- [ ] The implementation follows the recorded product decisions; it does not reopen settled choices or defer ordinary error behavior to the owner.
- [ ] Both endpoint-profile layouts contain the editable speech path in the requested position.
- [ ] The manager follows the approved control order and shared styles.
- [ ] Select Provider uses the exact approved title/subtext above one mode/selector row; the redundant navigation row is absent.
- [ ] The inline provider selector starts at Select, opens the TTS provider picker, and remains tappable after displaying a saved selection.
- [ ] The provider value uses the existing name-only display behavior; provider controls are visible by default for API TTS endpoints.
- [ ] The TTS model picker opens directly on View All, with no favorites landing or favorite/star actions.
- [ ] Duplicate endpoint/model/provider combinations are rejected with the exact message and one Okay button.
- [ ] Add saves a complete entry, then clears the upper fields only on success.
- [ ] Saved endpoint/model fields remain fixed; provider edits target one stable entry.
- [ ] Automatic/Preferred/Only remain present and synchronized.
- [ ] Provider table columns and X/blank/? meanings match the approved specification.
- [ ] Filters contain only approved, relevant non-privacy TTS options and do not affect chat filters; ZDR and Training/Data Use remain informational columns only.
- [ ] Provider order defaults to alphabetical; Price sorting preserves billing units, groups comparable prices, and sorts within groups without altering routing priority.
- [ ] API source options come from the saved TTS collection; Google remains available.
- [ ] The top Google/OpenAI engine-toggle tile and its listeners are removed from Voice & Speech; no replacement engine selector is added.
- [ ] The active voice determines the speech source, and the existing Select Voice row reports that same selection. Legacy compatibility state cannot override it.
- [ ] Voices, previews, and actual speech use the same exact saved source.
- [ ] Selected/default voice, previous-selection history and last-known-good record are global and separate; no chat ID, rename, deletion or open-conversation choice changes their storage.
- [ ] Chat renaming changes only the title; direct readers use the saved ID, with no migration or broader backup refactor.
- [ ] Phase 6 removal recovery uses the global interfaces. Future Companion overrides and their fallback chain have not been implemented as part of this scope.
- [ ] Unsupported/unknown service behavior is reported honestly; Only is never silently rewritten to Automatic.
- [ ] Unavailable TTS Models is the final cleanup category and targets only saved TTS references.
- [ ] Cleanup preserves inconclusive results, aliases, other endpoints, and unrelated saved data.
- [ ] Deleted API voices restore the immediately previous usable selection; otherwise the exact Okay / Select New Voice dialog is shown.
- [ ] Voice-list failures preserve the model/source and leave **Voices currently not available.** in the voice area; reopening retries discovery.
- [ ] The upper configuration area and saved table scroll together beneath the normal header.
- [ ] Errors use specific Title Caps headings, plain-language causes, the applicable chat-format provider details, and useful shared-style actions.
- [ ] Cancel is leftmost; Retry/Okay follows it; Okay is spelled out. No failure changes routing or discards saved data silently.
- [ ] Missing voices are diagnosed by cause; network/authentication/format/discovery/empty/filter states are not collapsed into No Voices Found.
- [ ] Tests, Android Checks, visual review, and live service tests are reported as separate evidence.
- [ ] No unapproved logs, unrelated refactors, palette changes, or main-branch merge were introduced.

**Final handoff status:** all product decisions for this scope are recorded; no owner questions remain deferred to implementation. This document does not claim that application implementation, device testing, or live speech-routing verification has been performed.
