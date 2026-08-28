# API Voice Models — Implementation Status

Current implementation: Phase 6 is implemented below, with verification in progress. The Phase 5 global voice-storage contract supersedes the earlier per-chat scope.

## Phase 1 — Complete

- Branch: `feature/tts-api-models-phase-1`.
- Implementation/test commit: `647407c7878e14e2d986ebab8cf0147f31c5dc7e`.
- Android Checks: https://github.com/SoulPhosphor/speak-gpt/actions/runs/33067674630
- Baseline: `e2180c3dc048f8f21aa4f4d7a127b2faddc597fa` on `main`.
- No later phase is implemented by this change. No merge to `main` is authorized.

## Contracts for Phase 2

- `ApiEndpointObject.speechEndpoint` is a trailing constructor property, default
  `/audio/speech`. Profile storage uses `<endpoint-id>_speech_endpoint`; old
  profiles default without rewriting their data. Both editor layouts place the
  editable field immediately after Chat Completions Endpoint. The existing label,
  save, dirty-state and discard behavior applies. Quick Settings profile copies
  and the legacy editor preserve this property.
- `ApiEndpointObject.normalizedSpeechEndpoint` trims whitespace and defaults a
  blank value. `composeSpeechUrl(host, path)` appends the path without discarding
  a base prefix such as `/api/v1/`; it preserves a custom trailing path slash.
  The speech transport must consume this property in Phase 2. It is not yet a
  working playback configuration.
- `preferences/tts/SavedTtsSourcesPreferences.getPreferences(context)` returns
  the app-wide source store. Public operations: `load`, `add(endpointId, modelId,
  routing)`, `replaceRouting(entryId, routing)`, `removeEntryIds`, and
  `removeTargets(Set<ModelIdentity>)`. Each returns `Result`; failures carry
  `TtsStorageException.reason` (`READ_FAILED`, `INVALID_DATA`, `WRITE_FAILED`,
  `INVALID_SELECTION`, `DUPLICATE`, or `NOT_FOUND`). Never convert a failed load
  into an empty collection or report an unsuccessful mutation as saved.
- `SavedTtsSource`: stable UUID-backed `id`, exact `endpointId`, exact `modelId`,
  `routing`. `sourceId` is `api-tts:<entry-id>` for the Voice Browser. Neither ID
  changes with labels or routing. The source list keeps insertion order.
- `TtsRoutingSettings`: `mode` (`automatic`, `preferred`, `only`),
  `selectedProvider`, ordered `providerOrder`, and `allowFallbacks`. Only without
  a provider is rejected, never converted to Automatic. No text-only filters or
  Ignore control were added. Add/edit duplicate identity is endpoint/model plus
  the selected provider set; mode, fallback and priority alone do not create a
  new provider combination. Duplicate failures leave the draft handling to the
  later UI and do not mutate stored rows.
- Source JSON: version 1, `entries` array of the fields above, with `routing`
  nested per entry. Stored at private `filesDir/tts/saved_sources.json`.
  Credentials remain solely in the existing endpoint secret store.
- `PreviousTtsVoicePreferences.getPreferences(context)` stores only the
  immediately previous global voice, in
  `filesDir/tts/previous_voice_<hash-of-empty-string>.json`. Phase 5 replaced the
  original chat-scoped factory; use this no-chat-ID contract for later work.
  JSON version 1 has a nullable `previous` object: `kind` (`DEVICE` or `API`),
  `sourceId`, `voiceId`, nullable `modelId`. API source IDs use the saved source's
  `sourceId`; device IDs use the existing provider ID. `load()` returns
  `Result<TtsVoiceSelection?>`; absent history is null.
- `recordActivation(current, next)` accepts the authoritative current selection
  and records it only when it differs from next. It does not select a voice.
  Phase 5 calls it only for actual user activation, never browse, preview or
  automatic restoration, and handles failures before completing activation.
  Current voice and `LastKnownGoodVoiceRegistry` are also global in Phase 5;
  they remain distinct from this previous-selection history.
- Both stores serialize read/modify/write across instances in this process.
  `TtsFileStorage` writes, flushes, syncs and verifies a same-directory temporary
  file, then atomically replaces the destination. There is no delete-original
  fallback or memory-first commit. Unreadable/malformed/future-version content
  is preserved and blocks mutations. Call synchronous disk operations on the
  existing IO dispatcher when the UI is connected.

## Changed/new files

All source paths below are relative to `app/src/main/`:

- `java/org/teslasoft/assistant/preferences/dto/ApiEndpointObject.kt`
- `java/org/teslasoft/assistant/preferences/ApiEndpointPreferences.kt`
- `java/org/teslasoft/assistant/preferences/tts/SavedTtsSource.kt`
- `java/org/teslasoft/assistant/preferences/tts/SavedTtsSourcesPreferences.kt`
- `java/org/teslasoft/assistant/preferences/tts/PreviousTtsVoicePreferences.kt`
- `java/org/teslasoft/assistant/preferences/tts/TtsStorage.kt`
- `java/org/teslasoft/assistant/ui/activities/ApiEndpointEditorActivity.kt`
- `java/org/teslasoft/assistant/ui/fragments/dialogs/QuickSettingsBottomSheetDialogFragment.kt`
- `java/org/teslasoft/assistant/ui/fragments/dialogs/EditApiEndpointDialogFragment.kt`
- `res/layout/activity_api_endpoint_editor.xml`
- `res/layout/activity_api_endpoint_editor_new.xml`
- `res/values/strings.xml`

Tests, relative to `app/src/test/java/org/teslasoft/assistant/`:

- Extended `preferences/ApiEndpointStableIdTest.kt`.
- New `preferences/tts/SavedTtsSourcesPreferencesTest.kt`.
- New `preferences/tts/PreviousTtsVoicePreferencesTest.kt`.

## Verification

- Local Kotlin 2.2.21 compiler: new storage, source/history models, endpoint DTO,
  model identity and Hash compiled against the existing Android 36 SDK; passed.
- Python ElementTree checks of both editor XML files: valid XML, unique speech
  field, correct immediate placement after chat field and shared field style;
  passed. Strings XML parsed successfully.
- `git diff --check`: passed.
- Android Checks on the implementation/test commit above:
  - `./gradlew --no-daemon test`: passed.
  - `./gradlew --no-daemon assembleDebug`: passed.
  - `./gradlew --no-daemon assembleDebugAndroidTest`: passed.
- The final status-only commit does not change the tested application or test code.
- Device/emulator review: not performed. No live speech requests or paid calls.
- Playback, remote routing support, source activation and deletion recovery are
  not claimed verified. These remain the assigned work of later phases.

## Phase 2 — Complete

- Branch: `feature/tts-api-models-phase-2`, based on Phase 1 at `891d693`.
- Final implementation/test commit: `256467d53e9dd3890cfc12996297bab24642520b`.
- Android Checks: https://github.com/SoulPhosphor/speak-gpt/actions/runs/33112273264
  — passed on the final implementation/test commit.
- Scope: internal discovery, provider metadata, routing, failure handling and
  transport contracts. No new screens, voice activation, player integration,
  cleanup deletion, or changes to current chat/Google behavior.
- The legacy `ApiSpeechCatalogClient` / `OpenAiVoiceProvider` remains connected
  to the old UI until Phase 5. Do not reuse its endpoint-wide voice list or
  fallback voice catalog for the new feature. The new interfaces below are the
  Phase 3–5 integration path.

### Contracts for Phase 3

All new production files are in
`app/src/main/java/org/teslasoft/assistant/tts/api/`.

- `TtsAndroidServices.resolver(context)` supplies a `TtsSourceResolver` backed by
  the Phase 1 saved store and saved endpoint profiles. `saved(sourceId, voiceId)`
  resolves an existing entry; `resolve(TtsTarget)` resolves an explicit draft.
  Missing profiles never fall back to the active chat endpoint. Load failures
  stay failures. Run these synchronous disk operations off the UI thread.
- `TtsTarget` carries exact endpoint ID, model ID, routing settings, optional
  saved source ID and voice ID. Saved source IDs remain `api-tts:<entry-id>`.
  Model/voice/provider IDs are not normalized or shortened. `TtsEndpoint` is
  an immutable per-attempt profile snapshot, including speech path, discovery
  path, credentials and both timeouts. It is not a navigation extra or a cache
  key. Resolve again when starting an operation after profile/source edits.
- `TtsDiscoveryClient.models(source, token)` returns `TtsModelCatalog`. Each
  `TtsModel` retains capability evidence, exact ID, name, its own voice metadata
  and the optional details link. OpenRouter uses the speech-filtered catalog;
  synthesis evidence includes nested `architecture.output_modalities`. Names,
  generic audio support and audio-input/chat capability are not TTS evidence.
  A filtered, empty or incomplete result must never prove model deletion.
- `providers(source, token)` respects the configured provider-discovery path.
  OpenRouter's standard path first attempts the existing safe canonical-link
  resolver; fallback discovery can still succeed. Credentials cannot follow an
  API-provided link or HTTP redirect to another origin. Provider route IDs are
  distinct from display names. Metadata is limited to the approved provider,
  price, latency, uptime, ZDR and training/data-use information plus voice data.
  Empty `supported_parameters` does not exclude a provider or model.
- `voices(source, token)` keeps model and provider voice metadata separate.
  OpenRouter can use exact lookup for aliases; generic voice probes include the
  explicit model/routing target. There is no guessed voice fallback. Optional
  probe failures do not mask a later successful supported source.
- `TtsVoiceCatalog.Known` can contain an empty list; `Unavailable` means no
  supported discovery source; `Invalid` distinguishes unreadable data from
  missing identifiers. Request failures throw `TtsException` with a structured
  `TtsFailure`. Missing voice metadata is never model absence. All discovery is
  uncached so reopening can retry; no failure is permanently remembered.
- `TtsFailures.voiceDiscovery(source, result)` maps empty/unavailable/invalid
  voice results to their specific explanation. Nonempty success returns null.
  `TtsFailures.message(failure)` supplies title, explanation and ordered actions.
  `TtsAndroidServices.providerDetails(context, failure)` reuses existing chat
  detail labels without logging or reading global request state. Display both
  through the normal shared scrolling dialog. Do not invent a provider error
  for a successful empty list or purely local failure.
- Failures retain operation, exact target, response-received state, status and
  same-attempt provider evidence. Serving-provider identity comes only from the
  response. Network causes, rate limits, usage limits, credits, routing failures,
  malformed data and bare 404 responses remain distinct. No new logs were added.
- `TtsProviderSort` is a picker-local value with the approved alphabetical
  default and Price/Latency/Uptime precedence; it never touches chat filters or
  saved provider priority. Keep this value while visiting the Filters panel;
  create a fresh one when opening a new picker.
- `TtsPrice`, `TtsCharge` and `TtsPriceComparator` preserve decimal prices,
  currency, component, quantity and billing basis. Equivalent quantities and
  duration units compare exactly by cross multiplication. Free, compatible paid
  groups and unknown groups retain their order in either direction. Missing
  billing metadata remains unknown; flat prompt/completion numbers alone do not
  license assuming tokens, characters, a currency or a cross-unit conversion.
  Partial known charges remain visible in `display()`. A paid input plus zero
  output is not free. Unknown/absent privacy values stay nullable. Positive ZDR
  list matches require the exact model and provider route; absence is not false.
- Latency/uptime retain the text table's 30-minute metric (p50 for percentile
  objects), with identified fallback metrics. Daily uptime is not substituted.
  Different measurement definitions are not numerically ranked against each other.

### Transport and lifecycle contracts for Phase 5

- `TtsSpeechTransport.request` / `synthesize` consume the explicit resolved
  source for both preview and readback. They never consult chat preferences.
  Speech uses the profile's speech path, exact model/voice, authentication mode
  and timeouts. Requested format is MP3. Empty, JSON-error and unsupported-format
  responses cannot become playable audio. `TtsAudio` supplies bytes, MIME type,
  extension, source target and optional generation ID; it does not play audio.
- `TtsRouting` composes structured raw JSON using the existing provider routing
  serializer while preserving speech `provider.options`. Automatic is unrestricted,
  Preferred preserves order/fallback, Only sends `only` and disables fallback.
  Empty Only and empty Preferred with fallbacks disabled are blocked. The
  transport's endpoint-adapter factory permits alternate endpoint wire contracts
  without changing storage or the chat serializer. Generic endpoints are not
  excluded by hostname. Wire serialization is not proof of remote enforcement.
- `TtsRequestGate.begin()` cancels the previous generation for that consumer.
  Pass its token through every discovery or synthesis call; call gate.cancel()
  for Stop, source/profile changes and lifecycle teardown. HTTP cancellation
  closes the in-flight call. Use `token.deliver { ... }` on the delivery thread
  around UI updates, errors and playback start, including callbacks already
  queued before Stop. Separate consumers should have separate gates. Do not
  turn cancellation into an error dialog.
- Retry is a later UI action: re-resolve the same still-valid target and start a
  new token. No transport retries, redirects or unrestricted retries are made
  automatically. Playback completion, MediaPlayer cleanup, activation/history
  and hands-free integration remain Phase 5 work.

### Phase 2 verification

- Focused compilation of the new non-Android core against actual repository
  dependencies passed using the available Kotlin compiler.
- Local JUnit run: 131 tests passed, including 42 Phase 2 tests and 89 existing
  routing, discovery-parser, filter, error-classifier and provider-error tests.
  HTTP tests use MockWebServer or injected transports; none call a paid API.
- `git diff --check`: passed. GitHub Android Checks on the final implementation
  commit passed all required steps:
  - `./gradlew --no-daemon test`
  - `./gradlew --no-daemon assembleDebug`
  - `./gradlew --no-daemon assembleDebugAndroidTest`
- The final status-only commit changes no tested application or test code.
- No device/emulator UI review, real speech playback or live provider-routing
  enforcement test was performed. No endpoint/model/provider/voice combination
  is claimed live-verified. No merge to `main` was performed.

## Phase 3 — Complete

- Branch: `feature/tts-api-models-phase-3`, based on Phase 2 at `d940bc3e`.
- Final implementation/test commit: `23eb2cd8959eabb1399af5df318b3e0bc33fe3c5`.
- Android Checks: https://github.com/SoulPhosphor/speak-gpt/actions/runs/33139303774
  — passed on the final implementation/test commit.
- Scope: result-only TTS model/provider pickers and an isolated Filters panel.
  No manager entry point, voice activation, playback, cleanup, or main merge.
- `TtsModelPickerActivity` reuses the full-screen View All scaffold and model
  row presentation. It hides View All/current-chat/favorite/reasoning/routing
  actions, uses only `TtsDiscoveryClient.models`, searches exact IDs and names,
  highlights the caller's current model, and returns the exact selected ID.
- `TtsProviderPickerActivity` locks endpoint/model for both draft and saved-row
  callers. Automatic/Preferred/Only, radio/checkbox selection, priority arrows,
  removal and the Preferred fallback switch edit a local copy only. Save returns
  the complete target; Back cancels. No chat preference/favorite writes occur.
- Its data columns are Provider, Price, Latency, Uptime, ZDR, Training/Data Use.
  One width table aligns headings and cells. Long values wrap; price components
  and billing units are retained. Nullable booleans are X/blank/?. Sorting uses
  Phase 2's decimal-safe grouped comparator and never changes routing priority.
- `TtsProviderFiltersActivity` retains the separate slide-in panel and automatic
  application on closing/Back. It exposes alphabetical, Price, Latency and Uptime
  only, with the existing None/Highest to Lowest/Lowest to Highest wording and
  Reset Filters. No privacy/text-capability filters or shared text singleton.
- Requests resolve saved profiles off the UI thread. Saved-row requests also
  validate source identity before discovery and Save. Failures retain routing,
  display the Phase 2 specific explanation/provider evidence through shared
  dialogs, and retry the same target. Cancellation/backgrounding invalidates
  callbacks; interrupted work resumes on return. Recreation retains choices,
  search and the local sort state. No logs or paid calls were added.

### Phase 4 caller contract

- Register `TtsModelPickerContract` / `TtsProviderPickerContract` using Android's
  activity-result API. Launch either with `TtsPickerRequest(TtsTarget(...))`.
  Only non-secret identity/routing travels in the encoded activity payload.
- Model launch: exact endpoint ID plus the draft's current model ID, no source
  ID. It starts directly on View All. The model picker refuses saved-row targets.
- Provider draft launch: endpoint ID, model ID, complete draft routing, null
  source ID. Saved-row launch: the same fields plus `SavedTtsSource.sourceId`.
  Both lock the endpoint/model; there is no model chooser inside this picker.
- Save/model tap returns `TtsTarget`; canceled results return null. Retain the
  launch request in the caller and use `acceptsProviderResult` or
  `acceptsModelResult` to reject results for a draft/row replaced while open.
- The caller owns Add/replaceRouting, duplicate dialogs and all store mutations.
  A returned provider result is not itself a persisted edit. Preserve the full
  draft on an unsuccessful Add/edit and use Phase 1's stable entry ID for edits.
- Incomplete Only drafts survive navigation/recreation but cannot Save. Empty
  Preferred with fallback disabled is also blocked. An old Preferred selection
  stored only in selectedProvider is exposed in the ordered list; removing it
  clears that reference so it cannot survive as an invisible effective route.
- `TtsProviderPickerState` owns one routing object and `TtsProviderSort` value.
  A fresh picker resets sorting; visiting Filters/recreation preserves it.
- `TtsPickerStateTest` covers result identity, saved-row locking, cancel isolation,
  recreation, routing selection/order/fallback, blank Only validation, separate
  text/TTS filters, grouped price ordering, endpoint-isolated speech search and
  source/layout wiring. Phase 2 catalog/price/failure/transport tests remain.

### Phase 3 verification

- Local XML parsing, referenced string/style checks and `git diff --check`: passed.
- Android Checks passed on the final implementation/test commit:
  - `./gradlew --no-daemon test` (including 16 new picker tests).
  - `./gradlew --no-daemon assembleDebug`.
  - `./gradlew --no-daemon assembleDebugAndroidTest` (compilation, not execution).
- The final handoff-only commit changes no tested application or test code.
- Device/emulator visual review: not performed. The pickers are callable through
  their final contracts; the user-facing manager entry point belongs to Phase 4.
- Live service/routing/playback: not performed. No endpoint/model/provider/voice
  combination is claimed live verified and no paid requests were made.


## Phase 4 — Complete

- Final implementation/test commit: `ff841b76afa95fd0a28e369d2b1c87fbc703c9b0`.
- Android Checks: https://github.com/SoulPhosphor/speak-gpt/actions/runs/33145117762
  — passed on the final implementation/test commit.
- Branch: `feature/tts-api-models-phase-3`, continuing `5a2acabb` and preserving
  all preceding phases and composer fixes. No new branch or main merge.
- `ApiVoiceModelsActivity` is registered and opens from the new Select API Voice
  Models row immediately above Advanced Voice Settings. Its simple header has
  no Save/decorative icon. One vertical scroll includes the whole form and saved
  table, with one horizontal table scroll and shared header/body column widths.
- Endpoint choices read saved profiles without changing the active chat endpoint.
  The AI Model and provider values start at Select. The exact Select Provider
  title/hint precedes one Automatic/Preferred/Only dropdown + provider value row.
  The value opens/reopens the Phase 3 provider picker for every endpoint.
- `TtsManagerState` owns draft/picker target isolation and Add/Edit/Remove.
  Endpoint/model changes clear dependent routing. Add validates and persists,
  reloads the saved list, then resets every upper field. Explicit incomplete Only
  remains Only and cannot save. Empty optional Preferred becomes Automatic.
- Duplicate Add and provider-edit Save use the exact Combination Already Exists
  message and the shared single Okay action. Failed writes retain draft/data;
  failed saved-row routing stays available when reopening that row's picker.
- Saved-row edits use `SavedTtsSource.id` / `sourceId` (`api-tts:<id>`), keeping
  endpoint/model/position fixed. X removes only the exact row without another
  confirmation. Neither action activates a voice or edits chat favorites.
- `ApiVoiceModelsViewModel` retains local writes through activity recreation and
  stores only non-secret draft/picker identities in SavedStateHandle. It serializes
  mutations and defers a returned row edit behind an in-flight reload. A committed
  Add with a failed refresh can retry the read without repeating the mutation.
- Observation for Phase 5: `SavedTtsSourcesPreferences` remains the sole source
  of truth. The manager reloads on start/return and after successful mutations;
  `ui` is its StateFlow snapshot. There is no separate saved-source cache or
  alternate voice registry. Phase 5 should refresh the Voice Browser from this
  same store and re-resolve exact stable source IDs before playback.
- The legacy engine tile intentionally remains until Phase 5 connects selected
  voices to preview/readback. No saved API entry can be activated yet. Active API
  voice/history/removal recovery must therefore be connected in Phase 5, using
  this store's deletion as loss of the source; do not keep a playable hidden copy.

### Phase 4 verification

- Extended existing saved-source and picker suites for manager transactions,
  exact duplicate wording, draft/row isolation, stale and canceled results,
  source deletion, recreation, write/read failures and layout/navigation wiring.
- Changed XML parsing, manager string/style/required-dimension checks and diff
  whitespace checks passed locally. The layout regression checks required width
  and height through the shared style chain, not just XML syntax.
- Android Checks passed on the final implementation/test commit:
  - `./gradlew --no-daemon test` (including 12 new manager regression tests).
  - `./gradlew --no-daemon assembleDebug`.
  - `./gradlew --no-daemon assembleDebugAndroidTest` (compilation, not execution).
- The final status-only commit changes no tested application or test code.
- No device/emulator visual review, live service calls, speech playback or remote
  routing verification performed in Phase 4. Phase 5 is recorded below; Phase 6 remains unimplemented.


## Phase 5 — Complete

- Branch: `feature/tts-api-models-phase-3`. Continues the existing phases and
  composer fixes; no additional branch or merge to `main`.
- Core integration commits: `6d311e83`, `9b3faf6f`, `f1da43e6`, `b27133a3`.
- Stable chat-ID correction: `f0f63b3182e1a73cb068197d5e61c8bc6d84c919`.
- Global voice-storage correction: `2e72ba92d25d3c22e7147d613a38f91d05104502`.

### Selection, storage and recovery contracts

- `VoiceBrowserActivity` keeps Google and registers one `SavedApiVoiceProvider`
  per saved `api-tts:<entry-id>` source. Labels use endpoint/model/provider names
  when available, with exact IDs as the optional-metadata fallback. Source
  refresh preserves stable identities and source-specific filters/overrides.
- Browsing and previewing do not activate a voice. `TtsSelectionService.activate`
  commits the full source/voice/model identity and records the immediately
  previous user selection. Failed persistence does not consume that predecessor.
- **Voice selection is app-wide.** `AppTtsVoicePreferences` reads/writes the
  existing encrypted global/default `settings.` store. `Preferences` delegates
  voice identity and its legacy compatibility fields to it, even when constructed
  for a chat. Chat-specific legacy voice values are neither adopted nor deleted.
- `PreviousTtsVoicePreferences.getPreferences(context)` takes no chat ID. It uses
  the existing global/default history file, `tts/previous_voice_<hash-of-empty-string>.json`.
  `tts_history_scope` is no longer read or written. Old per-chat history is not
  moved, merged, or deleted.
- `LastKnownGoodVoiceRegistry(context)` also uses `settings.`. The selected
  default, immediately previous activation, and last-known-good record remain
  distinct. Existing Google availability/fallback behavior is retained; API
  catalog/preview capability is not recorded as proof of successful playback.
- Chat creation does not copy voice preferences. Chat readback observes global
  selection changes and cancels superseded speech. Switching, renaming or deleting
  a chat does not change the selected default or its recovery storage.
- Future Companion overrides are not implemented. Their intended precedence is
  Companion voice, global default, then last-known-good recovery. This phase does
  not add a Companion selector or a new automatic retry/fallback policy.
- `TtsSelectionService.reconcile` applies Section 7.7 only for confirmed voice
  deletion or a removed saved source/profile. It restores the exact immediately
  previous usable selection without recording the removed voice as new history.
  Transient failures and failed/empty/unknown catalogs preserve the selection.
- If the previous voice cannot be restored, `TtsVoiceDialogs` shows the exact
  permanent-unavailability message with Okay / Select New Voice. The latter
  opens the existing Voice Browser. Other errors retain typed provider evidence
  and explicit source-specific retry behavior.

### Playback and settings integration

- Preview and ChatActivity API readback both use `TtsPlayback`, the saved-source
  resolver, and the Phase 2 transport. Speech resolves the selected source's
  endpoint, speech path, auth, model, voice, timeouts and routing; it does not use
  the active chat client or chat model/endpoint.
- Request tokens, playback generations, async player preparation and terminal
  cancellation prevent stale starts and duplicate completion. Stop/replacement
  releases the player and temporary audio. Source removal/edit or relevant speech
  profile changes invalidate playback; unrelated chat-model/profile edits do not.
- Chat speech retains its existing completion, hands-free and keepalive handling.
  Cancel/Stop never manufactures a failure dialog or provider evidence.
- The old engine tile and both dead settings-transition references are removed.
  The existing Select Voice row shows the actual selected source/voice. Existing
  shared row/dialog layouts and voice metadata controls remain in use.

### Stable chat-ID correction

- Manual naming, list-dialog naming and automatic naming persist only the title;
  every existing stored ID stays unchanged. Live activities update the title
  without replacing preferences or moving image/summarizer job identities.
- Direct list/navigation, preview, pin/metadata, deletion, memory lookup and backup
  lookup sites use the stored ID. Missing-ID legacy reads retain their prior
  name-hash fallback without writing a migration. Automatic placeholder naming
  skips IDs already owned by renamed chats; new named chats cannot overwrite an
  existing ID through a reused title.
- The rename transaction has a same-ID, list-only write path. History, settings,
  attachments and memory records are not copied or moved. The legacy cross-ID
  routines remain untouched behind the title-only return; no cleanup was run.
- No existing IDs, backup formats or restore formats were migrated or rewritten.
  This correction is not a broader backup or cross-feature audit.

### Verification and remaining work

- The core Phase 5 integration passed Android Checks at `b27133a3`:
  https://github.com/SoulPhosphor/speak-gpt/actions/runs/33155950080
- Final implementation/test commit: `73b8375eb619b338b5bde8e626e95b9b52dd9d5c`.
- Final combined Android Checks passed:
  https://github.com/SoulPhosphor/speak-gpt/actions/runs/33157771646
  - `./gradlew --no-daemon test` — passed.
  - `./gradlew --no-daemon assembleDebug` — passed; APK artifact uploaded.
  - `./gradlew --no-daemon assembleDebugAndroidTest` — passed (compilation only).
- The final documentation-only commit changes no tested application/test code.
- Focused suites cover full selection identity, activation history, failed writes,
  permanent versus transient recovery, global selection across different chat
  stores and deletion, separate global last-known-good state, global history-file
  reopening, source refresh/stale callbacks, exact preview/readback requests,
  player stop/replacement/error/duplicate completion, and relevant configuration
  invalidation. Chat-ID tests cover stored-ID reads and title-only transaction
  success/failure/interruption without other file mutations.
- Local checks: changed XML parsing, resource/reference checks, per-chat preference
  inventory consistency, and `git diff --check`. No Android SDK bootstrap or
  local full Gradle build was attempted.
- No live endpoint/model/provider/voice combination was tested in this phase.
  HTTP fixtures use synthetic speech sources/voices; playback tests use a
  recording MediaPlayer, not a real audio decoder. No device/emulator visual,
  actual audio, background/hands-free or remote-routing verification was done.
  OpenRouter routing remains an intended request mapping, not a verified server
  behavior.
- Phase 6 cleanup integration remains unimplemented. Its implementation must
  retain global voice storage and these selection/recovery contracts. The plan's
  definitions, storage/recovery instructions, Phase 6 steps, regression scenarios
  and acceptance checklist now carry these rules explicitly. The drawer redesign
  instructions also describe title-only renaming without changing chat IDs.


## Phase 6 — Implemented; verification in progress

- Branch: `feature/tts-api-models-phase-3`, continuing `92f79a92`. No new
  branch, main merge, chat-ID changes, per-chat voice edits, or logging changes.
- `ModelCleanupReferencesLoader` adds only explicitly saved TTS sources;
  endpoint/model sets deduplicate provider combinations. Unreadable TTS storage
  makes the reference load incomplete, preserves the saved report, and produces
  the existing specific storage error. `ModelCleanupPolicy.prune` includes TTS
  references and refuses to prune incomplete loads. The report format is unchanged.
- `ModelCatalogAvailabilityClient` requests OpenRouter `output_modalities=all`
  when a scan includes TTS references. Exact saved-ID/canonical-slug and alias
  checks remain in use. Empty, failed, malformed and visibly partial catalogs
  remain inconclusive. Pagination markers and HTTP next-page links never become
  absence evidence; no pagination URL is guessed.
- Official OpenAI's all-model catalog can establish absence. For generic endpoints,
  an absent TTS ID receives an exact `/models/{id}` lookup: an exact returned ID
  proves presence; an explicit `model_not_found` 404 proves absence. Unsupported
  lookup paths, bare 404s, failed lookups and chat-only lists alone are inconclusive.
  No voice or upstream-provider catalog is used as model-absence evidence.
- `ModelCleanupActivity` adds Unavailable TTS Models last, reusing shared section,
  result and Delete All styles. Its approved Cancel / Okay confirmation captures
  only reported TTS endpoint/model targets. There is no availability recheck at
  deletion time and no call to favorite/rule deletion from this action.
- `TtsModelCleanupViewModel` retains a confirmed operation across rotation and
  finishes an already-started atomic removal/recovery even when the activity leaves.
  `TtsSelectionService.removeUnavailableSources` serializes the batch with global
  activation, uses `SavedTtsSourcesPreferences.removeTargets`, and invokes the
  existing recovery logic once only when the current API source was removed.
  Recovery failures are separate from successful deletion, so a removed source
  is never reported as still saved. The immediately previous usable voice can be
  restored; otherwise the existing Okay / Select New Voice dialog is shown.
- Inactive removal leaves selection/history/last-known-good records unchanged.
  Store change notifications invalidate removed-source playback; the manager and
  Voice Browser continue to reload that same store. Local report reconciliation
  follows successful deletion and also runs when reopening cleanup.
- Focused tests extend `ModelCleanupPolicyTest`, `PreviousTtsVoicePreferencesTest`,
  `TtsPickerStateTest`, and `TtsPlaybackTest`. New `ModelCatalogAvailabilityClientTest`
  covers the previously untested HTTP availability layer; `ModelCleanupReportStoreTest`
  verifies old report compatibility. Existing atomic saved-source tests remain in use.

### Phase 6 verification

- Android Checks: pending.
- No device/emulator visual review or real speech playback performed.
- No paid speech requests or live provider-routing verification performed. Catalog
  network tests use synthetic HTTP interceptors; they are not service verification.
- Catalog-scope references: https://openrouter.ai/docs/guides/overview/models and
  https://developers.openai.com/api/reference/resources/models/methods/list/.
