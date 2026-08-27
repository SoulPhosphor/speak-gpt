# API Voice Models — Implementation Status

## Phase 1 — Code implemented; Android Checks pending

- Branch: `feature/tts-api-models-phase-1`.
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
- Full verification required on the implementation commit through Android Checks:
  `./gradlew --no-daemon test`, `./gradlew --no-daemon assembleDebug`, and
  `./gradlew --no-daemon assembleDebugAndroidTest`. Results pending.
- Device/emulator review: not performed. No live speech requests or paid calls.
- Playback, remote routing support, source activation and deletion recovery are
  not claimed verified. These remain the assigned work of later phases.
