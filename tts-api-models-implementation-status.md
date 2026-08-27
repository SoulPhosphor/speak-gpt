# API Voice Models — Implementation Status

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
- `PreviousTtsVoicePreferences.getPreferences(context, chatId)` stores only the
  immediately previous voice, in `filesDir/tts/previous_voice_<chat-id-hash>.json`.
  JSON version 1 has a nullable `previous` object: `kind` (`DEVICE` or `API`),
  `sourceId`, `voiceId`, nullable `modelId`. API source IDs use the saved source's
  `sourceId`; device IDs use the existing provider ID. `load()` returns
  `Result<TtsVoiceSelection?>`; absent history is null.
- `recordActivation(current, next)` accepts the authoritative current selection
  and records it only when it differs from next. It does not select a voice.
  Phase 5 must call it only for actual user activation, never browse, preview or
  automatic restoration, and handle failures before completing activation. No
  changes were made to current preferences or `LastKnownGoodVoiceRegistry`.
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

## Phase 2 — Implemented; Android Checks pending

- Branch: `feature/tts-api-models-phase-2`, based on Phase 1 at `891d693`.
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
- `git diff --check`: passed. Full Android tests/APK/instrumentation compilation
  are pending GitHub Android Checks; the final result will be recorded here.
- No device/emulator UI review, real speech playback or live provider-routing
  enforcement test was performed. No endpoint/model/provider/voice combination
  is claimed live-verified. No merge to `main` was performed.
