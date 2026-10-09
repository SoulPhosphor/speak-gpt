# API Voice Service (API speech-to-text)

Owner-approved October 9 2026. This replaces the old built-in cloud Whisper
(a hard-coded `whisper-1` request to OpenAI) with a user-configured API
speech-to-text service.

## What is built (first version: "the bones")

### Voice & Speech → Voice Input

Order: **API Voice Service**, **Google Dictation**, **On-Device Whisper**.

- API Voice Service: the gear opens the API Voice Service screen. While it is
  selected and not set up, its label turns the theme's error color and the
  gear becomes **Set Up**. Selecting it never opens setup automatically.
  "Set up" means the saved endpoint still exists, a model is chosen, and an
  Only routing has a provider.
- On-Device Whisper: same pattern with **Install** while selected and no
  model is installed. On a phone whose processor cannot run it, the row shows
  a red X instead of a radio, a red label, and **Unavailable**, and cannot be
  selected. A leftover selection on such a phone moves to Google Dictation.

### API Voice Service screen

Simple header (back + title "API Voice Service"), then:

1. **API Endpoint** dropdown (plain text when only one endpoint exists).
2. **AI Model** — the model picker in speech-to-text mode. Models come only
   from the endpoint while the app runs: OpenRouter is asked for
   `output_modalities=transcription`; any model list entry that states a
   `transcription` output modality counts. An endpoint whose list carries no
   such data shows "This client can't detect speech to text models on this
   endpoint."
3. **Routing Type** — Automatic / Preferred / Only, and a gear that opens the
   provider picker for the chosen model (so it lists only that endpoint's
   providers for that speech-to-text model).
4. **Spoken Language** — Automatic (default) or an ISO 639-1 language from
   the platform list. Automatic sends no language.
5. **Automatic Punctuation** — shown on and greyed out. Not wired yet.
6. **Custom Vocabulary** — field + Add, a box showing about ten entries before
   it scrolls, an X per entry (confirmation with "Do not show this warning for
   the rest of this session."), **Clear All** (always confirms), and an entry
   count. Capped at 75 entries: at the cap the count turns red, Add is
   disabled, and "Custom vocabulary maximum reached. Remove some to add new
   ones." shows under the field. Duplicates (ignoring capitalization) are not
   added.

### Popups in chat

- On-Device Whisper selected, nothing installed: **Onboard Whisper Not
  Downloaded** with API (or **Set Up API STT** while not set up), Google
  Dictation, Download Whisper.
- API Voice Service selected, not set up: **API Voice Service Not Set Up**
  with Download Whisper (hidden where it cannot run), Google Dictation, **Set
  Up API STT**.
- A failed transcription shows **Transcription Failed** with the specific
  cause and "Nothing was added to your message."

### Requests

- OpenRouter: JSON to `audio/transcriptions` with base64 audio, `language`
  when chosen, and the user's provider routing exactly as chosen. Owner
  ruling (Oct 9 2026): Routing Type and its gear stay fully available, even
  though OpenRouter currently documents that it does not apply `order` /
  `only` / `allow_fallbacks` on this endpoint (the same position as API
  voices). OpenRouter has no top-level vocabulary field and forwards
  `provider.options` only to the provider that serves the request, so the
  hint is sent as `provider.options.<provider>.prompt` for every provider
  serving the model (read from OpenRouter when the list is non-empty) plus
  any the routing names.
- Every other endpoint: OpenAI-style multipart upload with `prompt`.
- Entries are sent oldest first, newest last. Whisper models read only about
  the last 224 tokens of the hint (roughly 40–75 names or short phrases).

### Storage and backup

`stt_api_target`, `stt_api_language`, `stt_api_vocabulary` in the global
`settings` preferences. The app-settings backup carries them
(`ApiSttSettingsTest`).

## Not built yet — remember these

- **Automatic Punctuation:** wire the toggle up for services that actually
  offer a punctuation switch (for example Deepgram, AssemblyAI, Google Cloud,
  which use their own request formats). Show it only for those services,
  default On.
- **Other services' vocabulary:** research how other speech-to-text services
  accept custom vocabulary (keywords, boosts, phrase lists) and send it the
  way each one expects.
- **Per-service vocabulary switch:** a toggle to turn custom vocabulary off
  for particular services while keeping the saved list.
- **OpenAI direct model detection:** OpenAI's own model list carries no
  capability data, so its speech-to-text models are not detected yet. The
  API voice feature reads OpenAI's published documentation for this; the same
  could be done here.
- **OpenRouter routing enforcement:** if OpenRouter starts applying routing
  on transcription, the saved choice already goes out with every request.
