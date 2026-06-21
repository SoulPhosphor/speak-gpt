# Error Codes — Proposed Design (review before any code changes)

**Status: PROPOSAL ONLY. No code has been changed.** This document defines the
error system so it can be reviewed and approved before implementation. Once
approved, the wording and codes here become the contract the code must match.

---

## 1. Purpose

Two things happen on every generation error:

1. **A short, professional message appears in the chat** so the user knows what
   happened, tagged with a stable code like `[N1]`.
2. **A fuller, structured entry is written to the Error Log** (the renamed Crash
   Log — see §4) under a dedicated tag, containing the technical detail a
   developer (or a coding bot) needs to diagnose it — the parts a normal user
   would not know and that would only clutter the chat.

The **code** is the bridge between the two. The user can read `[N1]` off the
chat message and hand it over, and it points straight at the matching Error Log
entry and the known cause.

**Design rules for the user-facing message:**

- Concise — ideally one to two short sentences.
- Professional and factual: state what the system observed, nothing more.
- No fault language, no apologies, no reassurance ("sorry", "this isn't your
  fault", "don't worry" are all banned).
- No raw stack traces. No profile name, Base URL, or model name. No config
  detail. All of that lives in the Error Log.
- Understandable to a non-technical reader.

**Design rules for the Error Log entry:**

- Include only what is genuinely useful for troubleshooting. Do not bloat it.
- Always: the code, a one-line plain-English cause, and the request context
  (profile, Base URL, model) that the user message deliberately omits.
- The full exception text / stack trace is included **only** where it adds
  information the code alone does not (the unknown catch-all, and the
  ambiguous server-rejection cases). For well-understood failures (e.g. being
  offline) the stack trace is noise and is omitted.

---

## 2. Code format and categories

Format: **one category letter + one digit**, shown in brackets as the first
thing in the message, e.g. `[N1]`.

| Letter | Category |
|--------|----------|
| `N` | Network / transport |
| `A` | Authentication / key |
| `M` | Model / request configuration |
| `Q` | Quota / usage limits |
| `S` | Server response |
| `U` | Unknown / uncaught |
| `E` | Environmental / contextual — **catalog only, not auto-emitted** (see §6) |

**Codes are a stable contract.** Once a code is assigned to a cause it is never
reused for a different cause, even if the English wording is later changed. New
causes get new numbers; retired causes leave a gap rather than being recycled.

**Two kinds of code live in this document.** The `N`/`A`/`M`/`Q`/`S`/`U` codes
are **detection codes**: the classifier emits them because the app actually
observed that failure (an exception type, an HTTP status, a response body). The
`E` codes are **catalog codes**: named hypotheses for behaviors the app cannot
yet detect at the moment of failure. The classifier never emits an `E` code —
those failures still surface under whatever detection code describes the
*symptom* (usually `[N1]`/`[N2]`), and the Error Log context flags (§4) are what
let the pattern be spotted later. A code that fired on a guessed root cause it
could not prove would be worse than no code, so this line is deliberate.

A `V` (voice-only) category is **reserved but currently empty**. It will only be
introduced if a genuinely voice-only failure mode is identified that cannot be
expressed with the categories above. Voice failures otherwise use the same codes
as everything else (see §5).

---

## 3. The codes

Conditions below are the exact substrings the **current** code matches in
`e.stackTraceToString()` (text/voice path at `ChatActivity.kt` ~3408, image path
~4317), plus the dedicated `connectionAbortMessage` helper.

> **These substrings are the legacy clues, not the implementation target.** They
> are listed so each code is grounded in a real failure that happens today. The
> actual classifier must follow the **hybrid strategy in §7**: exception **type**
> first, server **status / error body** next, and raw error **text** only as a
> last-resort fallback. Where a typed exception or HTTP status identifies a code
> more reliably than the substring, the typed signal wins; the substring is the
> fallback for cases that arrive untyped (chiefly the transport drops).

> In every Error Log entry, `Profile`, `Base URL`, `Model` and `Voice` are the
> standard context block defined in §4. To avoid repetition the table lists only
> what is **added beyond** that standard block.

| Code | Technical cause | Matching condition | User-facing message (exact) | Error Log adds beyond standard block |
|------|-----------------|--------------------|------------------------------|---------------------------------------|
| `[N1]` | Socket torn down mid-request (`ECONNABORTED`) | `"Software caused connection abort"` | `[N1] The connection closed before the response finished. Try again, or switch models if this only happens with one model.` | Exception message (the raw "Software caused connection abort" detail). No stack trace. |
| `[N2]` | Connect / read timeout | `"Connect timeout has expired"` or `"SocketTimeoutException"` | `[N2] The server did not respond in time. The connection may be slow or the model busy — try again, or switch models.` | Which timeout (connect vs socket) from the exception message. No stack trace. |
| `[N3]` | Host could not be reached / resolved (offline, bad DNS, or wrong Base URL) | `"No address associated with hostname"` (typically `UnknownHostException`) | `[N3] The app could not reach the server address. Check your connection or the Base URL, then try again.` | Nothing extra; standard block only. No stack trace (cause is fully known). The Base URL in the standard block is the value to verify. |
| `[A1]` | API key rejected | `"Incorrect API key"` | `[A1] The API key was rejected. Update the key in Settings.` | Nothing extra. **Never log the key itself.** No stack trace. |
| `[M1]` | No model set on the request | `"invalid model"` or `"you must provide a model"` | `[M1] No model is set for this chat. Choose a model in this chat's settings.` | Nothing extra; the Model field in the standard block carries it. |
| `[M2]` | Named model not available on endpoint | `"does not exist"` | `[M2] The selected model is not available on this endpoint. Check the model name in this chat's settings, or choose another.` | Exception message (server's own wording, which sometimes distinguishes "no access" from "no such model"). |
| `[M3]` | Context length exceeded | `"This model's maximum"` | `[M3] The conversation is too long for this model's limit. Start a new chat or shorten the input.` | Exception message (often states the token limit and overflow). |
| `[Q1]` | Quota / usage limit reached | `"You exceeded your current quota"` | `[Q1] The account's quota or usage limit has been reached. Check the account's billing and usage limits.` | Nothing extra. |
| `[S1]` | Endpoint returned HTTP 404 | `"404"` or `"Not Found"` | `[S1] The endpoint was not found (HTTP 404). Check the Base URL in this profile's settings.` | Nothing extra; the Base URL in the standard block is the thing to check. |
| `[S2]` | Response could not be read as the expected stream (often a non-streaming error body, usually HTTP 400) | `"NoTransformationFoundException"` or `"Expected response body of the type"` | `[S2] The server returned a response the app could not read as a stream. Check whether this endpoint and model support streaming.` | **Full stack trace** plus **HTTP status if available** — this case is ambiguous and the trace is often the only place the underlying HTTP error survives. |
| `[S3]` | Prompt rejected as inappropriate content | `"Your request was rejected"` | `[S3] The request was rejected as inappropriate content and could not be processed.` | Nothing extra. Do **not** log the prompt text. |
| `[U0]` | Anything not matched above | `else` branch (catch-all) | `[U0] An unexpected error occurred. The technical details were saved to the Error Log.` | **Full exception class, message, and stack trace.** This is the only thing the user can hand over, so the log must carry everything. |

### Notes on specific wording

- `[N1]` is the error in the original report. The "switch models if it only
  happens with this one" clause is kept because it is the one genuinely useful
  distinction (server dropping a specific model vs. a general network blip) and
  it is phrased as a neutral instruction, not reassurance.
- `[M2]` deliberately does **not** print the model name into the chat (per the
  no-config-in-message rule); it directs the user to the setting instead. The
  name is in the Error Log's Model field.
- `[S2]` previously rendered a multi-paragraph explanation in chat. Under this
  design the chat keeps one sentence and the paragraph-level detail moves to the
  Error Log.

---

## 4. Where logs go, what each keeps, and the Error Log entry

### 4.1 Rename and re-scope the two logs

The app has two logs whose names no longer match what they hold. This proposal
renames them so the name describes the contents, and splits errors from voice
into separate logs:

| Old name | New name | Holds |
|----------|----------|-------|
| Crash Log | **Error Log** | App crashes **and** all generation / handled errors (the `GenError` entries below) — everything **except** voice data. |
| Event Log | **Voice Debug Log** | Voice diagnostics only (VAD, mic route, hands-free loop decisions, voice context). The **only** place voice issues are recorded. |

This **replaces** the earlier "segregate by tag inside one log" idea: the two
concerns now live in two separate logs. That is the cleanest separation and stops
high-volume voice diagnostics from burying error entries.

### 4.2 Retention (each log trims independently)

| Log | Keep the most recent… | Tie-breaker |
|-----|------------------------|-------------|
| **Error Log** | 30 days **or** 500 entries | whichever limit is reached **first** |
| **Voice Debug Log** | 7 days **or** 1,000 entries | whichever limit is reached **first** |

Voice diagnostics are far higher volume, so the Voice Debug Log keeps more
entries but over a shorter window. This replaces the current single
character-count trim in `Logger` (see §7).

**Trim by whole entries, never by physical lines.** A single entry is often
multiple lines (a `GenError` block, a multi-line stack trace). An "entry" is
defined as everything from one `[yyyy-MM-dd HH:mm:ss] …` header line up to — but
not including — the next header line. Retention counts and removes **whole
entries**, oldest first; it must never cut a multi-line entry in half the way the
current character-count trim can. Age is read from the entry's header timestamp.

### 4.3 Controls (both logs get the same two)

- **Clear** — the user can wipe either log at any time; the existing clear
  buttons are kept, one per log.
- **Copy / Export** — offered **before** clearing, so the contents can be handed
  to a developer or coding bot without being lost first. Export ships exactly
  what is stored, which already excludes the secrets listed in §4.5.

### 4.4 The Error Log entry — structure

Generation / handled errors are written to the **Error Log** via `Logger.log(...)`
under a dedicated `GenError` tag (the tag still separates a handled generation
error from a hard crash within the same log):

```
Logger.log(context, "error", "GenError", "error", <message>)
```

(The internal log-type key that routes to the Error Log is an implementation
detail in §7 — the existing "crash" channel becomes the Error Log.) `Logger`
already prefixes every line with `[yyyy-MM-dd HH:mm:ss] [GenError] [ERROR]`, so
**date/time, tag, and level are automatic** — they must not be duplicated inside
the message body.

**Standard context block** (present on every Error Log entry):

```
[N1] Connection closed before the response finished.
Profile: <profile label>
Base URL: <full sanitized base URL>
Model: <model>
Voice: <active | inactive>
Trigger: <first-send | regenerate | continue | image-generation>
Screen: <on | off | unknown>
Network: <e.g. wifi | cellular | none | unknown>   (only if cheaply/safely available)
Power save: <on | off | unknown>                   (only if cheaply/safely available)
```

- `Profile`, `Base URL`, `Model` come from `apiEndpointObject` and `model`, the
  same values the old chat message used to print.
- **`Base URL` is the full, sanitized base URL** — scheme, host, port, and path
  (e.g. `https://api.z.ai/api/coding/paas/v4`), not just the bare host. The app
  already stores and prints the full base URL, so this is the existing value;
  query parameters are stripped so no secrets ride along in the URL.
- `Voice` records whether the hands-free / voice loop was active when the error
  occurred. When it is **active**, the entry also appends a **compact voice
  context block** (§5) — a short snapshot of voice state at the moment of the
  failure (loop, stage, speech engine, and a one-line mic-route / VAD summary),
  provided purely as extra diagnostic context for *this generation error*. It is
  written **only on errors**, never per turn. The **detailed, high-volume**
  per-turn voice diagnostics are *not* duplicated here — they stay in the Voice
  Debug Log (§5), and the two logs are correlated by timestamp and code.
- **Context flags** (`Trigger`, `Screen`, `Network`, `Power save`) record the
  situation the failure happened in, not the failure itself. They exist so the
  environmental hypotheses in §6 become *visible as patterns* over time: the app
  cannot tell a Wi-Fi-sleep abort apart from a server abort at the moment it
  happens (both are `[N1]`), but if every `[N1]` in the log reads `Screen: off`
  or `Trigger: regenerate`, the cause reveals itself in hindsight. `Trigger`
  records which generation path produced the error. `Screen`, `Network`, and
  `Power save` are recorded only when they can be read cheaply and without extra
  permissions; anything unavailable is logged as `unknown` rather than guessed.
  These flags are never shown in the chat message.

**Conditional additions**, per the last column of the §3 table:

- **HTTP status**: appended as `HTTP status: <code>` whenever the failure carried
  one — i.e. the server actually answered (`A1`, `Q1`, `S1`, `S2`, `S3`, and the
  body-based `M2` cases). Transport failures (`N1`, `N2`, `N3`) have **no** HTTP
  status because the socket died before a response arrived; the field is simply
  omitted for them rather than logged as "unknown".
- Exception message: appended as `Detail: <e.message>` where the table calls for
  it.
- Full stack trace: appended for `[S2]` and `[U0]` only.

### 4.5 What must NOT be written to either log

The API key, **request headers** (they carry the authorization token), the user's
prompt text or message content, or any other secret. Both logs are local-only and
encrypted at rest, but they are meant to be handed to a developer, so they stay
free of credentials and personal content.

---

## 5. Voice-specific handling

> **Scope of this pass.** Voice-facing *error messages* and the *per-turn voice
> diagnostics* (VAD, mic route, hands-free loop decisions logged every turn) are
> **out of scope here and are not being changed**. They keep their current
> wording and behavior; only their log is renamed to "Voice Debug Log". The only
> voice-related additions in this pass are the two **failure-only** snapshots
> described below — a compact one on the Error Log entry, and a full one into the
> Voice Debug Log — both of which fire only when a turn errors, never per turn.

- Voice failures use the **same codes** as typed/image failures. A turn that
  fails mid-stream during hands-free conversation and a typed turn that fails the
  same way produce the **same code and the same chat wording**.
- **Compact voice snapshot on the error entry.** When a generation error happens
  while voice is active, its `GenError` entry in the **Error Log** appends a
  *compact* voice context block — a short snapshot of voice state at the instant
  of failure, as extra context for *that* error. It is written **only on errors**,
  never per turn:

  ```
  Voice context:
    Loop: <hands-free | push-to-talk>
    Stage: <listening | transcribing | generating | readback>   (to add: track current stage)
    STT: <local-whisper | google>
    Mic route: <one-line summary from LocalWhisperEngine.lastMicRouteDiagnostics()>
    VAD: <one-line summary from LocalWhisperEngine.lastVadDiagnostics()>   (only if non-empty)
  ```

  These reuse values the voice engine already exposes, so no new capture is
  needed beyond `Stage` (a small addition to track where in the loop the failure
  happened). The block is deliberately a **snapshot, not the firehose**.
- **Detailed / high-volume voice diagnostics stay in the Voice Debug Log only.**
  The compact snapshot above is the *only* voice data that reaches the Error Log,
  and only on an error. Everything else voice — the per-turn VAD/mic/loop output
  — remains in the Voice Debug Log exactly as today. The two logs correlate by
  timestamp and code, so a voice failure can still be traced end to end without
  the high-volume data bloating the Error Log.
- **Failure snapshot into the Voice Debug Log, even when per-turn logging is
  off.** On a generation failure during voice, write the **full** last-known
  voice information — the complete `lastMicRouteDiagnostics()`,
  `lastVadDiagnostics()`, and (when available) `lastAudioHealthDiagnostics()`
  the engine already holds in memory — as a single entry to the **Voice Debug
  Log**, *regardless of whether any VAD-logging toggle is on*. This is the
  fuller counterpart to the compact Error-Log snapshot: it leaves a real voice
  clue sitting in the voice log for after-the-fact diagnosis, so a failure is
  never silent there just because the running per-turn list happened to be
  switched off. It fires **only on failure** (rare, bounded), so it adds no
  per-turn volume.
  - *Intentional change of meaning:* "voice logging off" used to mean the Voice
    Debug Log stays empty; with this it means empty **except** for failure
    snapshots. That is deliberate — clues without spam. Do not re-gate these
    snapshots behind the diagnostics toggle in a later cleanup.
- **Extra voice data is error-only this pass.** The compact Error-Log snapshot
  and the Voice-Debug-Log failure snapshot are both written *only when a turn
  fails*. Enriching **every** turn with this extra context is a possible future
  option (behind its own toggle, since the per-turn blocks are already large),
  but it is intentionally deferred so normal hands-free use is not buried in
  more output.
- This is **not** a separate voice error system. No `V` codes are introduced yet.
  A `V` code would only appear if a failure exists that is meaningless outside
  voice (for example, a microphone-capture fault with no network request at all)
  and cannot be filed under `N/A/M/Q/S/U`.

---

## 6. Environmental / contextual catalog (`E` codes — named hypotheses, not auto-emitted)

These are behaviors that have been *observed* but that the app cannot detect as a
distinct failure at the moment they happen. They get **catalog codes** (`E`) so
they have a shared name and a graduation path — **not** detection codes. The
classifier never emits an `E` code. When one of these behaviors occurs, the
failure still surfaces under whatever **detection** code matches the symptom
(almost always `[N1]`, sometimes `[N2]`); the Error Log **context flags** (§4)
are what make the underlying pattern visible after the fact.

Think of it as the smoke alarm distinction: the detection code says *there is
smoke* (`[N1]` — the connection died). It cannot say whether the cause was a
kitchen fire or a tsunami cutting the power. The `E` catalog names the suspected
causes; the context flags are the notes written beside each alarm that let the
real cause be identified in hindsight.

| Code | Hypothesis | How it surfaces today | Context flags that would reveal it | Possible future fix / graduation |
|------|-----------|------------------------|-------------------------------------|----------------------------------|
| `[E1]` | **Screen-off / Wi-Fi sleep mid-stream.** Turning the screen off lets Wi-Fi doze and drops the streaming socket. | `[N1]` (a genuine transport abort — the detection code is correct). | A cluster of `[N1]` entries reading `Screen: off`. | Hold a Wi-Fi lock during generation. If a reliably-detectable signal emerges, `E1` could graduate to a detection code. |
| `[E2]` | **Higher failure rate on regenerate.** Regenerating an answer fails/"glitches" more often than a first generation. Possibly a real bug, not just wording. | Whatever transport/server error occurs, usually `[N1]`/`[N2]`. | `[N1]`/`[N2]` entries disproportionately reading `Trigger: regenerate`. | Investigate the regenerate path once the logs confirm the pattern. |

**These are hypotheses, not emitted codes.** They are listed so the behavior has
a name you can hand over, and so the moment a cause is pinned down to a distinct,
reliably-detectable signal it can graduate into a real detection code (in its own
category if it is truly environmental, or an existing one if it fits). Until then,
trust the detection code for *what* happened and the context flags for *why*.

---

## 7. Implementation notes (for the later coding step — not done yet)

- **Single mapping function.** Replace the two divergent `when` blocks
  (`ChatActivity.kt` ~3408 text/voice and ~4317 image) with one shared classifier
  that takes the exception and returns `(code, userMessage, eventLogDetail)`. The
  likely home is a new `util/GenerationError.kt` (alongside the existing
  `ConnectionAbortMessage.kt`, which it would absorb). Both call sites then do the
  same two things: show `userMessage` in chat, write the `GenError` entry.

- **Hybrid classification — use the most reliable signal available, in order.**
  Do not build the classifier on raw `e.stackTraceToString()` substring matching;
  that is brittle (a stray `"404"` or `"does not exist"` anywhere in a trace
  causes a false match). Instead classify by the strongest signal each error
  actually carries:

  1. **Exception type first.** The clients throw typed exceptions — e.g. the
     `com.aallam.openai` client's `AuthenticationException`, `RateLimitException`,
     `InvalidRequestException`, `PermissionException`; Ktor's
     `ConnectTimeoutException` / `SocketTimeoutException`; and `java.net`'s
     `UnknownHostException`. Match these before anything else.
  2. **Server status / error body next.** When the server actually answered,
     read the HTTP status code and the structured error body (OpenAI-style
     `error.code` / `error.message`). This is what cleanly separates a quota 429,
     an auth 401, a 404, and a model-not-found body.
  3. **Raw error text only as a last resort.** Substring matching on the
     exception message / stack trace remains the fallback for cases that arrive
     untyped and with no HTTP response — most importantly the transport drops
     (`N1`/`N2`/`N3`), where the socket died before any status existed and a
     plain `IOException` message is genuinely all the app has. For those codes,
     message/type matching is expected and correct, not a stopgap.

- **Classification priority ladder.** Several conditions can co-occur (a
  bad model name can come back as an HTTP 404; a rejected request can look like a
  generic server error). Evaluate in this fixed order and stop at the first
  match, so overlapping cases are always resolved the same way:

  1. **Auth** (`A1`) — 401 / "incorrect API key".
  2. **Quota** (`Q1`) — 429 / "exceeded your current quota".
  3. **Network / transport** (`N1`, `N2`, `N3`) — connection abort, timeout,
     host unreachable. (No HTTP response exists for these.)
  4. **Context length** (`M3`) — "this model's maximum".
  5. **Model-specific server message** (`M1`, `M2`) — "invalid model", "you must
     provide a model", "does not exist". A model-not-found body classifies as
     `M2` **even when the HTTP status is 404**.
  6. **HTTP-status-only server errors** (`S1`) — a bare 404 / "Not Found" with no
     model-specific body.
  7. **Response-shape / streaming errors** (`S2`) and **content rejection**
     (`S3`).
  8. **Unknown catch-all** (`U0`) — anything still unmatched.
- **Unified behavior.** This guarantees the same failure cannot produce two
  different messages on the text vs. image paths — a current inconsistency (the
  image path's catch-all dumps a raw stack trace into chat; the text path's adds
  profile/URL). Both converge on `[U0]` with the trace going to the log instead.
- **Catch-all (`U0`).** Any exception not matched by a known condition maps to
  `[U0]`; the chat shows the fixed one-line message, and the Error Log gets the
  exception class, message, and full stack trace. No raw trace ever reaches the
  chat.
- **Error Log always written.** Today errors are only shown in chat, and only
  when `showChatErrors()` is on. Under this design the `GenError` entry is written
  on **every** error path regardless of the `showChatErrors()` setting, because
  the log is the diagnostic record; the toggle still controls only whether the
  chat shows the message. (To confirm during review: is that the intended
  behavior — log always, display optional?)
- **Strings.** User-facing messages stay in `res/values/strings.xml` per project
  rules. The code prefix (`[N1] `) can be stored as part of each string or
  prepended by the classifier from a code enum; the enum approach keeps the code
  and its string in one place and is the suggested route.
- **Context flags.** `Trigger` is known at the call site (each generation path
  knows whether it is a first send, regenerate, continue, or image generation),
  so it is passed into the classifier/log call. `Screen`, `Network`, and
  `Power save` are read at error time from the relevant system services only when
  that is cheap and needs no extra permission; otherwise they are logged as
  `unknown`. None of these flags ever appear in the chat message.

- **Log rename and split (§4.1).** "Crash Log" → **Error Log**, "Event Log" →
  **Voice Debug Log**. Internally `Logger` already has a `crash` channel and an
  `event` channel; the simplest mapping is `crash` → Error Log (it gains the
  `GenError` entries) and `event` → Voice Debug Log (unchanged content, voice
  diagnostics only). This touches the user-facing labels in
  `res/values/strings.xml`, the rows in `AlertDebugMenuActivity`, and
  `LogsActivity` (its title/branching keyed on log type), plus the chat
  **bug-icon** shortcut (still opens the voice log) and the Voice-log
  **terminal-icon** shortcut (unchanged). No new log *type* is required — it is a
  rename plus routing generation errors to the existing `crash`/Error Log
  channel.
- **Retention (§4.2).** Replace `Logger`'s single character-count trim
  (`MAX_LOG_CHARS`) with per-log limits: Error Log = 30 days **or** 500 entries,
  Voice Debug Log = 7 days **or** 1,000 entries, whichever limit is hit first.
  **Trim whole entries, not physical lines.** An entry spans from one
  `[yyyy-MM-dd HH:mm:ss] …` header line up to the next header line, so a
  multi-line stack trace or `GenError` block counts as **one** entry and is
  removed or kept as a unit — never split. Split the stored log on the header
  pattern to get entries, drop oldest-first until both the age and count limits
  are satisfied, then rejoin. Age comes from each entry's header timestamp.
- **Clear / Export (§4.3).** Keep the existing per-log Clear buttons; add a
  Copy/Export action offered before Clear. Export emits the stored text as-is,
  which already excludes the §4.5 secrets — no extra scrubbing needed at export
  time because nothing secret is ever written in the first place.

- **No telemetry.** `Logger` is local-only and must stay that way; nothing here
  sends anything off device.

---

## 8. Review decisions (approved — implementation may proceed)

1. **Code set and exact user-facing wording (§3): approved.**
2. **Always-log: approved.** The `GenError` Error Log entry is written on **every**
   generation/handled error, even when "Show chat errors" is off. The toggle
   controls only whether the error appears in chat, never whether it is logged.
3. **Voice on shared codes, no `V` codes yet: approved, with the strict log split
   (§5).** A generation error during voice goes to the **Error Log** as a
   `GenError` entry with `Voice: active` plus a **compact** voice snapshot for
   context. **Detailed / per-turn voice diagnostics stay only in the Voice Debug
   Log.** Voice-facing error *wording* and the per-turn voice logging are **out of
   scope for this pass** and are not changed.
4. **Standard Error Log block (§4): approved** as listed — Profile, Base URL,
   Model, Voice, Trigger, Screen, Network, Power save.
