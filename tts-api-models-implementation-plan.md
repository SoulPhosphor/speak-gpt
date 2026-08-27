# API Voice Models — Detailed Implementation Plan

## 1. Purpose and authority

This document plans a new API speech-model manager, its model/provider pickers, its connection to Select Voice, and its integration with Clean Up Models.

**This is a planning document only. Publishing this Markdown plan to `main` is authorized; application-code changes are not.** A future implementation task must explicitly authorize code work.

Repository inspected: `SoulPhosphor/speak-gpt`.

Baseline: `main` at `f2bdf0047c0751e14d91a12179ee5ad921f804be`, inspected on 2026-08-27. Recheck relevant files against the implementation branch before editing. Do not overwrite intervening work.

### Instructions for the implementing model

1. Read this entire document before editing. Read `CLAUDE.md`, `ui-style-guide.md`, and the relevant rows of `ui-style-adoption.md` on the current branch.
2. Treat the owner's current instructions as authoritative. Repository documents and existing code do not override them.
3. Follow the phases in order. Each phase names its prerequisites, work, tests, and completion conditions.
4. Distinguish approved product requirements, internal implementation choices, verified observations, and unanswered UI questions. They are not interchangeable.
5. Ask the owner about the decision gates in Section 5 before implementing the affected behavior. Do not fill gaps with invented defaults, messages, controls, or fallback behavior.
6. Do not ask the owner to choose private class names, collection types, coroutine structure, or other ordinary internal implementation details.
7. Reuse visual styles without importing unrelated behavior. Reusing a model picker must not switch the active chat model or alter chat favorites.
8. Do not remove requested controls because a particular service has incomplete documentation. The owner explicitly approved retaining Automatic, Preferred, and Only.
9. Do not turn an unverified server behavior into a claimed guarantee. Tests of request JSON prove what the app sends, not what a remote service honors.
10. Do not perform unrelated UI cleanup, palette work, logging changes, Google TTS repairs, or broad refactors. In particular, no new or changed logs are authorized by this plan.
11. Work in a feature branch when implementation is authorized. Development phases are checkpoints, not permission to publish incomplete screens.
12. Never report a device behavior as verified merely because a unit test or Android build passed.

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
| Active voice | The selected voice used for speech in the existing preference scope. Browsing a source is not the same action as selecting its voice. |

## 3. Approved product requirements

### 3.1 Entry point and manager

| ID | Requirement |
| --- | --- |
| R01 | Add a row labeled **Select API Voice Models** to Voice & Speech. Its exact position still requires the decision in Section 5. |
| R02 | The new manager uses the normal shared header with the destination title **Select API Voice Models**, without a decorative icon. Preserve the normal navigation/back control; do not interpret “without an icon” as removing navigation. Do not add a header Save action to this manager. |
| R03 | Under the header, show an **Endpoint** dropdown populated from the saved API endpoint profiles. |
| R04 | Below Endpoint, show **AI Model** using the Quick Settings model-selector row's appearance and formatting. Before a model is chosen, its value is **Select**. |
| R05 | Tapping AI Model opens a screen based on the current Select AI Model screen, restricted to TTS models belonging to the chosen endpoint. |
| R06 | Below the model row, show a section title **Select Provider** and the exact subtext specified below. These are section text, not a separate navigation row. Do not add the previously proposed Choose Provider row. |
| R07 | Directly below that title/subtext, place one row containing the **Automatic**, **Preferred**, and **Only** mode dropdown, followed by the tappable provider-selector area. Before a provider is selected, that area reads **Select** and tapping it opens the TTS provider picker. After selection, it shows the selected-provider display; tapping that display reopens the same picker. The dropdown and picker stay synchronized. Reuse the existing selected-provider presentation in this area; do not add a second provider-navigation row or a redundant Model Provider label. |
| R08 | Provider selection is optional. An entry without a provider preference uses Automatic. An explicit incomplete Only selection must not be silently rewritten to Automatic; preserve the text selector's validation distinction. |
| R09 | Place an **Add Model** button below the configuration controls. Successful addition saves the combination, updates the table, and clears the upper fields ready for another entry, including clearing the endpoint selection. Routing returns to its neutral Automatic state. A failed add must not clear the draft. |
| R10 | Below Add Model, show the saved entries as a table: **Endpoint**, **Model**, **Provider**, and a trailing **X** removal action. |
| R11 | Endpoint and Model are fixed within a saved row. Tapping Provider opens the provider picker for that exact entry. Saving there changes that row's routing; cancel/back does not change it. |
| R12 | The saved list must support long lists through scrolling. Exact vertical scroll behavior is a remaining UX decision. |
| R13 | The saved entries act like TTS favorites and are visibly manageable in this table. They must not be implemented by accidentally adding speech entries to the current chat-favorites store. |

The provider section uses this exact title and subtext:

**Select Provider**

> Optional. This may not be available or necessary with all endpoints.

The mode dropdown and provider selector belong together on the single row immediately beneath this text. The title and subtext do not open another screen; the provider-selector value does. On an empty draft, the row shows the mode **Automatic** and the separate provider-selector value **Select**. After a successful Add Model resets the draft, restore those same values. Keep the existing endpoint editor's unrelated wording and row unchanged.

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
| R20 | Format prices using their actual billing basis and appropriate currency/unit formatting. Do not relabel every speech rate as a token rate. |
| R21 | Keep the existing separate **Filters** interaction and selection-control style. Its contents must be applicable to TTS. Do not copy text-only options such as Tool Support or reasoning controls. Exact filter options not settled by existing applicable behavior require approval. |
| R22 | Retain Automatic/Preferred/Only in the implementation design now, even though OpenRouter's raw speech routing remains unverified. Keep endpoint-specific request handling adjustable; lack of OpenRouter documentation does not authorize deleting the modes. |

Boolean meaning must remain literal: an X under ZDR is favorable to zero retention; an X under Training/Data Use means training/data use is allowed or performed according to the reported field. Do not invert either meaning silently. Absence of ZDR does not establish that training occurs.

### 3.4 Select Voice and cleanup

| ID | Requirement |
| --- | --- |
| R23 | The existing Select Voice source/provider dropdown obtains its API choices from the saved TTS entries. Each API choice identifies its endpoint, model, and provider/routing selection. |
| R24 | Selecting a source loads the voices applicable to that source. Do not display every endpoint's voices or substitute an unrelated model's voice list. |
| R25 | Preserve the existing device/Google voice source and its behavior. The requested API list is an addition; this work does not remove device TTS. |
| R26 | If a source's voice-discovery or playback details need additional product decisions, keep those decisions explicit. The owner allows a checkpoint where saved combinations are listed before all voice-service details are settled. Do not label that checkpoint as completed playback integration. |
| R27 | Add **Unavailable TTS Models** at the bottom of **Clean Up Models**, after its current categories. |
| R28 | That category checks the TTS models explicitly saved in the new list, not every speech model advertised by every endpoint. |
| R29 | Cleaning up a model that no longer exists removes its matching saved TTS combinations from the manager and therefore from the Select Voice source list. |
| R30 | Temporary provider outages, failed requests, and inconclusive discovery must not newly establish that a saved model no longer exists. Preserve the existing availability safeguards and endpoint-specific identity. |

## 4. Verified starting points and risks

### 4.1 Current app architecture

| Current component | What was verified | Consequence for implementation |
| --- | --- | --- |
| `VoiceSettingsActivity` and `activity_voice_settings.xml` | The screen already opens the full-screen Voice Browser and uses shared rows/header. | Add one navigation row; do not rebuild the screen. |
| `VoiceBrowserActivity` | Its registered provider list currently contains the Google source only. The comment explicitly reserves API services for the coming selection flow. | Build API source registrations from saved entries, not a hardcoded OpenAI entry. |
| `VoiceBrowserController` | Provider IDs key loaded voices and filters; it rejects stale load callbacks. Browsing is separate from activation. | Give each saved API entry a distinct source identity and preserve these separation/race protections. |
| `OpenAiVoiceProvider` | It currently reads the active chat endpoint, chooses a discovered model, can use a generic six-voice fallback, and previews with an OpenAI-compatible SDK client. | Do not plug it in unchanged. The new source must receive an explicit saved entry and must not choose the first model automatically. |
| `ApiSpeechCatalogClient` | It recognizes some speech metadata and names, merges embedded voice lists, and probes voice paths. It does not currently parse OpenRouter's nested architecture and `supported_voices` correctly for this new use. | Add a TTS-specific catalog contract; do not assume the existing discovery is complete. Preserve voice/model associations. |
| `ChatActivity.speak` | The network branch uses the chat's existing API client/key and legacy speech preferences, not a separately resolved saved TTS entry. | Correct voice browsing alone is insufficient. Actual readback must use the same saved source as preview. |
| `FavoriteModelsPreferences` | Upsert identity is endpoint ID + model ID. Routing is attached to that one favorite. | It cannot represent multiple saved provider combinations for the same endpoint/model without overwriting them. Use a separate TTS entry identity. |
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

## 5. Remaining owner decisions — do not guess

These questions do not revoke approved requirements. Ask only the questions required for the phase about to be implemented. The owner does not need a wall of implementation questions at once.

| Gate | Decision still needed | Affected phase |
| --- | --- | --- |
| U1 | Where exactly should Select API Voice Models sit among the existing Voice & Speech rows? | 4 |
| U2 | Should the manager's add controls scroll with the saved table, or remain visible while only the table scrolls? How should long provider summaries fit in the approved same-row selector beside the mode dropdown, including a Preferred list with multiple providers? The section title/subtext and single-row layout are already approved; do not reopen those choices. | 4 |
| U3 | For endpoints that offer no provider chooser, should the provider controls be absent or visible but unavailable? Retaining all three modes in the design is already approved; do not ask the owner to approve their existence again. | 3–4 |
| U4 | What should happen if Add Model duplicates an existing complete combination, or changing a saved row's provider makes it identical to another row? Do not silently overwrite, merge, or add a duplicate. | 4 |
| U5 | Which precise privacy-filter choices should appear? What should a price sort do if providers for one model use incompatible billing bases? Do not invent additional groups or a misleading numeric comparison. | 3 |
| U6 | Does the TTS model picker open directly on all TTS models or use a saved-model landing first? What, if any, star/favorite actions belong in that picker? A star must never write to chat favorites. | 3 |
| U7 | How should the app handle a currently selected voice when its saved source is removed or its provider selection no longer supports that voice? This includes the manager's X, cleanup, and deletion of the referenced endpoint profile. Do not choose a replacement voice, switch to Google, or silently keep a deleted source usable without approval. | 4–6 |
| U8 | For a model whose voices cannot be discovered, what should the user see or be allowed to do? Do not add manual voice-ID entry, guess a voice, substitute generic OpenAI voices, or enable unsupported preview/playback without approval. | 5 |
| U9 | Approve TTS-specific explanatory/error/confirmation text where existing copy would be inaccurate: provider-picker intro, cleanup explanation/confirmation, missing-source handling, and any new validation message. Do not copy text that claims speech entries are chat favorites or that routing is guaranteed when it has not been verified. | Before the corresponding UI is connected |
| U10 | Should the new TTS cleanup category use the existing report-time deletion behavior, or refresh availability when deletion is requested? No change to the existing categories is authorized merely by this question. | 6 |

An unanswered gate means stop before that behavior, explain the specific choice in plain language, and wait. It does not authorize a placeholder implementation. All previously approved labels, columns, and routing modes remain approved.

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
| Dialog theme | `App.MaterialAlertDialog`; use existing approved action-layout conventions |
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

The collection should follow the existing favorites pattern of saved entries available across the app, with endpoint-specific membership. Active voice preferences retain their existing scope; adding a source does not activate it.

An unreadable saved collection is not an empty collection that may safely be overwritten. Return an explicit failure to the caller, preserve existing bytes, and use the approved recovery/error path.

### 7.2 Add and edit transactions

Add sequence:

1. Capture the current endpoint, exact model, and complete routing draft.
2. Validate that they belong together. Validate required selections and mode constraints.
3. Apply the approved duplicate policy; do not invent one.
4. Persist the complete entry as one logical operation.
5. Refresh the saved-table source of truth.
6. Only after success, clear the upper draft. Clear endpoint and model, clear provider choices, and reset routing to Automatic.

Provider edit sequence:

1. Open the picker with entry ID, fixed endpoint/model, and a copy of that entry's routing settings.
2. Editing occurs in picker-local state. Do not modify the stored entry while browsing provider rows.
3. Cancel/back returns no mutation.
4. Save validates and returns the complete routing result.
5. Update only the matching entry ID, preserving endpoint, model, ID, and list position.
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

Preview and full readback must share the same source resolver and transport configuration:

1. Resolve the saved entry or explicitly supplied draft source, as appropriate.
2. Resolve its endpoint profile by stable ID.
3. Build the speech URL using that profile's speech path, not its chat-completions path.
4. Apply that profile's existing authentication mode and timeout settings.
5. Send the exact speech model and voice ID, plus the requested routing settings through the endpoint adapter.
6. Request a format the existing player can actually decode. Do not label raw PCM bytes as MP3.
7. Distinguish an audio success response from a JSON error response; do not save an error body as playable audio.
8. Preserve Stop/cancellation, stale-readback protection, audio-resource cleanup, hands-free completion behavior, and existing lifecycle handling.

Do not add an audio-format picker, new tuning fields, or new logs as part of implementing this transport.

## 8. Safe implementation phases

### Phase 0 — Preflight and decisions for the next phase

**Purpose:** establish the current baseline and avoid executing an old plan against new code.

Work:

1. Confirm the repository and current branch. Inspect the working tree before editing.
2. Read the repository instructions and style references listed in Section 1.
3. Compare the named files with the inspected baseline. Record relevant intervening changes without replacing them.
4. Identify the smallest existing test suites covering each phase.
5. Resolve only the owner gates needed for work about to start. Do not reopen the approved decision to retain all three routing modes.
6. State whether the current authorized milestone is complete integration or the owner-permitted saved-list checkpoint.

Completion:

- The implementer can identify where saved sources, model discovery, provider selection, voice playback, and cleanup currently live.
- No code has been changed to settle an unanswered UX question.

### Phase 1 — Persistent source data and the speech endpoint field

**Depends on:** Phase 0.

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

Completion: storage and profile-field tests pass; no active chat selection or Google voice behavior changes. The field must be connected to the speech transport before a release claims it is functional.

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
8. Track the billing basis separately from the numeric price. Preserve input/output components if the model charges both. Use decimal-safe formatting so a small paid rate does not become “free.”
9. Reuse the chosen text-page latency/uptime definitions consistently. Do not mix 30-minute and daily uptime values without identifying the chosen metric internally and preserving its meaning.
10. Implement the routing-state/payload contracts in Section 7.3. Keep raw speech routing adjustable per endpoint. Do not rely on a speech SDK that silently excludes the required fields.
11. Build the shared speech transport in Section 7.5. Keep it independent of the active chat client.
12. Add cancellation and stale-result protection before connecting the transport to views.

Tests:

- Nested speech metadata includes valid TTS models even when their IDs contain no “tts.”
- STT and audio-input-only entries are excluded from the TTS picker.
- Voice lists from different models cannot contaminate each other.
- Missing voices do not mark an otherwise present model unavailable.
- Unknown latency/privacy remains unknown; explicit false remains false.
- Prices preserve units, currency, zero-versus-missing distinction, and small nonzero amounts.
- Automatic/Preferred/Only generate their intended payloads; Only never becomes an unrestricted retry.
- Speech provider options survive routing composition.
- Requests use the saved source's endpoint, authentication, model, voice, path, and timeouts even when the chat uses a different profile.
- Audio errors are not passed to the player as MP3; cancellation prevents late playback.

Completion: focused tests verify discovery and outgoing request behavior. Record live-routing support separately; do not mark it verified from these tests.

### Phase 3 — TTS model picker, provider picker, and filter panel

**Depends on:** Phases 1–2 and the applicable U3/U5/U6/U9 decisions.

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
3. Preserve the owner-approved full-screen model-picker presentation, search behavior/style, and selection appearance, while showing only the TTS catalog.
4. Route any picker selection back to its caller. Do not call `Preferences.setModel`, add a chat favorite, or invoke chat Only-provider recovery from a TTS pick.
5. Implement the TTS provider screen with picker-local routing state, the existing mode-specific selection interaction, and the six approved columns only. Preserve applicable Preferred ordering/fallback interaction as specified by the existing text behavior; do not add unrelated text capabilities.
6. When opened from a saved TTS row, lock its endpoint/model. Do not expose a model change that would contradict the fixed table columns.
7. Keep the separate Filters panel and its open/close behavior. Use isolated TTS filter state. Remove inapplicable text filters only in the TTS purpose.
8. Apply the approved boolean and price presentation. An informational X must not behave like a checkbox or delete control.
9. Ensure Save returns a complete routing result and Cancel/back does not save. Do not reuse `persistDirectly` behavior that writes to chat favorites.
10. Keep the manager's approved Select Provider title/subtext separate from picker-body prose. Obtain approval for picker-body prose that currently mentions chat favorites or guarantees routing; do not restore the removed manager navigation row or its old subtitle.

Tests:

- TTS picker selection never changes chat model/favorites or image-generation settings.
- Endpoint A's search results never appear in endpoint B's picker.
- Text and TTS filter states do not affect each other.
- All six data headings are present in the required order, without literal separator dots.
- True/false/unknown cells match X/blank/?.
- Provider selection, order, mode, and applicable fallback values survive save/reopen.
- Cancel preserves all previous values.
- An incomplete Only selection follows explicit validation; it is not converted to Automatic.
- A saved-row provider edit cannot change that row's endpoint/model.

Completion: both pickers are usable independently through their final caller contracts. No chat side effects or placeholder routes remain.

### Phase 4 — Manager screen, saved table, and Voice & Speech entry point

**Depends on:** Phases 1–3 and U1/U2/U3/U4/U7/U9 where applicable.

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
9. Apply the approved duplicate, removal, active-voice, summary, and scrolling decisions. Do not insert a new confirmation or undo control unless its behavior/copy has been approved.
10. Add Select API Voice Models to the approved position in Voice & Speech. Register the finished activity and preserve existing navigation conventions.
11. Handle rotation/recreation and returning from child screens without losing the draft or adding the same entry twice.

Tests:

- Initial model value is Select; routing is neutral Automatic; the separate inline provider-selector value is Select.
- The manager has one Select Provider section with the exact approved subtext and one mode/selector row beneath it; no separate Choose Provider navigation row remains.
- Tapping Select opens the TTS provider picker, saving updates the inline provider display and mode, and tapping the selected-provider display reopens that picker.
- Add saves one correct combination and resets all specified upper fields, including Automatic mode and the provider-selector value Select.
- Failed Add retains the draft and existing rows.
- Rapid double taps cannot create accidental duplicate mutations beyond the approved duplicate policy.
- Provider edit changes one saved row and preserves endpoint/model/entry ID/list position.
- Editing a saved row does not overwrite the upper add draft.
- X removes only its target; identical model names on other endpoints remain.
- Long names, many entries, landscape, and larger text do not hide the Provider/X actions or break table alignment.
- Back navigation does not create a saved entry from an incomplete draft.

Completion: the full source-management flow works. No device or API voice is automatically activated merely because an entry was added.

### Phase 5 — Select Voice integration, preview, and real readback

**Depends on:** Phases 1–4 and U7/U8/U9 where applicable.

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
2. Display endpoint/model/provider identity using the approved summary formatting. Two entries for one model with different providers must be distinguishable.
3. Construct each API provider with an explicit source reference; remove reliance on the current chat endpoint from that path.
4. On source selection, stop the old preview and load the correct source's voices. Browsing a source alone must not switch the active voice.
5. Refresh source registrations after additions, edits, or removals without using stale list positions as IDs.
6. Keep voice metadata model/source-specific. Do not infer gender, language, or quality merely to populate filters; preserve existing user-assigned identifiers and metadata behavior.
7. Scope API voice overrides, rejected-voice knowledge, loaded lists, and preview caches so identical voice IDs under different sources cannot collide. Preserve existing Google keys and data.
8. Activate a voice with enough identity to resolve its saved source and exact voice. Retain existing preference scope.
9. Connect previews and ChatActivity readback to the Phase 2 speech resolver/transport. Do not leave previews correct while real chat speech still uses the chat API client.
10. Preserve the editable preview text, metadata filters, separate preview action, checked/unchecked selection marks, long-press editor, Stop behavior, and current device-voice availability behavior.
11. Apply the approved policy for deleted sources, changed providers, unavailable voices, and legacy active preferences. Do not create a new fallback on assumption.
12. Do not describe a merely preview-capable voice as successfully tested. If last-known-good tracking is extended, distinguish selected, catalog-advertised, and successfully played states without changing existing visible fallback behavior unapproved.

Tests:

- Every saved entry appears once as an API source; unrelated unsaved models do not appear.
- Removing/editing an entry updates the source list on return.
- Source A's late load/preview result cannot update or play for source B.
- Browsing does not activate; selecting a voice activates the exact source/voice.
- Same voice ID on two source entries does not share rejection or override state accidentally.
- Preview and full readback use the same endpoint/model/routing/path/authentication.
- Changing the chat model/endpoint does not redirect the chosen speech source.
- Cancel/Stop prevents late playback and releases audio resources.
- Existing Google browsing, filtering, renaming, selection, and readback continue to work.

Permitted partial checkpoint: if voice-service details remain unresolved, the source list can be completed as an explicitly partial milestone. Do not pretend unsupported voices play, and do not decide the partial screen's failure/disabled presentation without U8/U9 approval.

Completion: identify exactly which endpoint/model/provider/voice combinations were tested. A build alone does not establish correct voices, playback, or remote routing.

### Phase 6 — Unavailable TTS Models in Clean Up Models

**Depends on:** stable saved-source storage and all affected removal policies. Perform this phase after source-management behavior is established because it deletes saved entries.

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
8. Add the **Unavailable TTS Models** category after the existing categories. Reuse the existing category presentation and user-triggered cleanup pattern; approve any new text/actions under U9.
9. Scope the TTS category's removal operation to matching saved TTS entries. If a missing endpoint/model has multiple provider combinations, remove the matching combinations, not one arbitrary row.
10. Do not remove chat favorites or model-rule targets through the TTS delete action, even if they share an endpoint/model identity. Existing categories retain their own actions.
11. Apply U10's approved report-time versus fresh-check behavior. Do not silently change deletion timing for existing categories.
12. Reconcile the report after a successful removal and refresh the manager/Voice Browser from the same saved store.
13. Apply U7 to any active voice whose source is removed. Do not alter current speech or choose a replacement voice without the approved rule.

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
- TTS deletion does not modify chat favorites, rules, Google voices, or unrelated endpoint profiles.
- Canceling confirmation removes nothing.
- Removing entries updates both the management table and Select Voice source list.
- An interrupted or failed mutation does not claim completion or erase unrelated entries.
- Previously saved reports remain readable and correctly reconciled.

Completion: destructive scope, inconclusive-check handling, and active-selection consequences have focused test coverage; existing cleanup behavior remains intact except for changes explicitly approved.

### Phase 7 — Regression checks, device review, and handoff

**Depends on:** all phases included in the authorized milestone.

1. Inspect the final diff for unrelated changes, copied raw styles, unapproved strings, new logs, secret handling, and chat-favorite side effects.
2. Run focused existing tests first, extending fixtures where they naturally fit. Add a new test file only for a new behavior with no suitable existing suite.
3. Run the repository's Android Checks workflow when code work is authorized: unit tests, debug APK assembly, and instrumentation-test compilation. Do not spend Work Mode time rebuilding the whole Android SDK environment.
4. Review the screens on a device/emulator for navigation, table alignment, scrolling, larger text, long names, and theme-resource use. Do not claim on-device verification if it was not performed.
5. Verify both API preview and actual chat readback. Record endpoint/model/provider/voice identity and requested routing mode in the test report without exposing credentials or adding application logs.
6. A live Only-routing verification must establish the actual responding provider or demonstrate that a forbidden route is not used. A successful audio response by itself is insufficient.
7. Preferred fallback behavior needs an appropriate controlled test; a model with only one provider cannot prove multi-provider fallback.
8. Live API calls may incur charges. Use owner-authorized test credentials and bounded test inputs; no paid calls were authorized or made during this planning task.
9. Update `ui-style-adoption.md` only for the surfaces actually audited/converted. Do not claim untouched local components are fully standardized.
10. Report completed phases, remaining gates, test results, live service verification, and device review separately. Do not merge to main without authorization.

## 9. Regression matrix

| Scenario | Required result |
| --- | --- |
| Chat uses endpoint A; speech source uses endpoint B | Chat remains on A; preview/readback use B. |
| Same model saved with two different provider settings | Entries remain independently addressable. |
| Provider edited in an existing row | Only that entry's routing changes; model/endpoint remain fixed. |
| Provider picker canceled | No saved or draft routing is changed. |
| Add fails | Existing table and current draft remain intact. |
| Source changes while voices load | Old response cannot populate the new source. |
| Stop pressed during generation/download/playback | No late audio starts after cancellation. |
| Catalog omits a supported alias | Individual lookup protects the valid saved ID where supported. |
| Provider metadata or privacy policy is absent | Show unknown, not a fabricated no/yes. |
| Speech price is character-, byte-, token-, or duration-based | Preserve the actual unit; do not compare unlike units as plain numbers. |
| One upstream provider is down | Do not conclude the TTS model was removed. |
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
- [ ] Required owner gates were answered before their affected code or wording was written.
- [ ] Both endpoint-profile layouts contain the editable speech path in the requested position.
- [ ] The manager follows the approved control order and shared styles.
- [ ] Select Provider uses the exact approved title/subtext above one mode/selector row; the redundant navigation row is absent.
- [ ] The inline provider selector starts at Select, opens the TTS provider picker, and remains tappable after displaying a saved selection.
- [ ] Add saves a complete entry, then clears the upper fields only on success.
- [ ] Saved endpoint/model fields remain fixed; provider edits target one stable entry.
- [ ] Automatic/Preferred/Only remain present and synchronized.
- [ ] Provider table columns and X/blank/? meanings match the approved specification.
- [ ] Filters contain only approved, relevant TTS options and do not affect chat filters.
- [ ] API source options come from the saved TTS collection; Google remains available.
- [ ] Voices, previews, and actual speech use the same exact saved source.
- [ ] Unsupported/unknown service behavior is reported honestly; Only is never silently rewritten to Automatic.
- [ ] Unavailable TTS Models is the final cleanup category and targets only saved TTS references.
- [ ] Cleanup preserves inconclusive results, aliases, other endpoints, and unrelated saved data.
- [ ] Source removal follows the approved active-voice policy.
- [ ] Tests, Android Checks, visual review, and live service tests are reported as separate evidence.
- [ ] No unapproved logs, unrelated refactors, palette changes, or main-branch merge were introduced.

**Planning-deliverable status:** requirements and safe implementation sequence are documented; the UI decisions in Section 5 remain explicit approval gates. Application implementation and live speech-routing verification have not been performed.
