# Error Codes — Proposed Design (review before any code changes)

**Status: PROPOSAL ONLY. No code has been changed.** This document defines the
error system so it can be reviewed and approved before implementation. Once
approved, the wording and codes here become the contract the code must match.

---

## 1. Purpose

Two things happen on every generation error:

1. **A short, professional message appears in the chat** so the user knows what
   happened, tagged with a stable code like `[N1]`.
2. **A fuller, structured entry is written to the Event Log** under a dedicated
   tag, containing the technical detail a developer (or a coding bot) needs to
   diagnose it — the parts a normal user would not know and that would only
   clutter the chat.

The **code** is the bridge between the two. The user can read `[N1]` off the
chat message and hand it over, and it points straight at the matching Event Log
entry and the known cause.

**Design rules for the user-facing message:**

- Concise — ideally one to two short sentences.
- Professional and factual: state what the system observed, nothing more.
- No fault language, no apologies, no reassurance ("sorry", "this isn't your
  fault", "don't worry" are all banned).
- No raw stack traces. No profile name, Base URL, or model name. No config
  detail. All of that lives in the Event Log.
- Understandable to a non-technical reader.

**Design rules for the Event Log entry:**

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

**Codes are a stable contract.** Once a code is assigned to a cause it is never
reused for a different cause, even if the English wording is later changed. New
causes get new numbers; retired causes leave a gap rather than being recycled.

A `V` (voice-only) category is **reserved but currently empty**. It will only be
introduced if a genuinely voice-only failure mode is identified that cannot be
expressed with the categories above. Voice failures otherwise use the same codes
as everything else (see §5).

---

## 3. The codes

Conditions below are the exact substrings the current code matches in
`e.stackTraceToString()` (text/voice path at `ChatActivity.kt` ~3408, image path
~4317), plus the dedicated `connectionAbortMessage` helper.

> In every Event Log entry, `Profile`, `Base URL`, `Model` and `Voice` are the
> standard context block defined in §4. To avoid repetition the table lists only
> what is **added beyond** that standard block.

| Code | Technical cause | Matching condition | User-facing message (exact) | Event Log adds beyond standard block |
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
| `[U0]` | Anything not matched above | `else` branch (catch-all) | `[U0] An unexpected error occurred. The technical details were saved to the Event Log.` | **Full exception class, message, and stack trace.** This is the only thing the user can hand over, so the log must carry everything. |

### Notes on specific wording

- `[N1]` is the error in the original report. The "switch models if it only
  happens with this one" clause is kept because it is the one genuinely useful
  distinction (server dropping a specific model vs. a general network blip) and
  it is phrased as a neutral instruction, not reassurance.
- `[M2]` deliberately does **not** print the model name into the chat (per the
  no-config-in-message rule); it directs the user to the setting instead. The
  name is in the Event Log's Model field.
- `[S2]` previously rendered a multi-paragraph explanation in chat. Under this
  design the chat keeps one sentence and the paragraph-level detail moves to the
  Event Log.

---

## 4. Event Log entry — structure

All error entries are written through the existing `Logger.log(...)` under a
dedicated tag so they are trivially separable from voice diagnostics:

```
Logger.log(context, "event", "GenError", "error", <message>)
```

`Logger` already prefixes every line with `[yyyy-MM-dd HH:mm:ss] [GenError] [ERROR]`,
so **date/time, tag, and level are automatic** — they must not be duplicated
inside the message body.

**Standard context block** (present on every error entry):

```
[N1] Connection closed before the response finished.
Profile: <profile label>
Base URL: <host>
Model: <model>
Voice: <active | inactive>
```

- `Profile`, `Base URL`, `Model` come from `apiEndpointObject` and `model`, the
  same values the old chat message used to print.
- **`Base URL` is the full, sanitized base URL** — scheme, host, port, and path
  (e.g. `https://api.z.ai/api/coding/paas/v4`), not just the bare host. The app
  already stores and prints the full base URL, so this is the existing value;
  query parameters are stripped so no secrets ride along in the URL.
- `Voice` records whether the hands-free / voice loop was active when the error
  occurred. When **active**, the entry appends a voice context block (§5).

**Conditional additions**, per the last column of the §3 table:

- **HTTP status**: appended as `HTTP status: <code>` whenever the failure carried
  one — i.e. the server actually answered (`A1`, `Q1`, `S1`, `S2`, `S3`, and the
  body-based `M2` cases). Transport failures (`N1`, `N2`, `N3`) have **no** HTTP
  status because the socket died before a response arrived; the field is simply
  omitted for them rather than logged as "unknown".
- Exception message: appended as `Detail: <e.message>` where the table calls for
  it.
- Full stack trace: appended for `[S2]` and `[U0]` only.

**What must NOT be written to the Event Log:** the API key, **request headers**
(they carry the authorization token), the user's prompt text or message content,
or any other secret. The log is local-only and encrypted at rest, but it is
meant to be handed to a developer, so it stays free of credentials and personal
content.

**Segregation from voice spam:** voice diagnostics already use their own tags
(`VoiceDiag`, `MicRoute`, `VoiceLoop`). Errors use `GenError`. That tag is what
lets errors be found, filtered, or later shown in a dedicated view without being
buried under per-turn voice output. (A truly separate log channel is noted as a
future option in §7, but is out of scope here.)

---

## 5. Voice-specific handling

- Voice failures use the **same codes** as typed/image failures. A turn that
  fails mid-stream during hands-free conversation and a typed turn that fails the
  same way produce the **same code and the same chat wording**.
- The only difference is the Event Log: when the voice loop is active at the time
  of the error, the entry appends a **voice context block** so a voice failure
  can be reconstructed. Proposed fields (only those already obtainable, plus a
  small number marked *to add*):

  ```
  Voice context:
    Loop: <hands-free | push-to-talk>
    Stage: <listening | transcribing | generating | readback>   (to add: track current stage)
    STT: <local-whisper | google>
    Last mic route: <from LocalWhisperEngine.lastMicRouteDiagnostics()>
    Last VAD: <from LocalWhisperEngine.lastVadDiagnostics()>      (only if non-empty)
  ```

  `Last mic route` and `Last VAD` reuse the data the voice diagnostics functions
  already expose, so no new capture is needed for them. `Stage` is the one field
  that needs a small addition to track where in the loop the failure happened.

- This is **not** a separate voice error system. No `V` codes are introduced yet.
  A `V` code would only appear if a failure exists that is meaningless outside
  voice (for example, a microphone-capture fault with no network request at all)
  and cannot be filed under `N/A/M/Q/S/U`.

---

## 6. Known environmental / intermittent issues (not yet coded)

These are observations worth recording even though they do not each get a code
today:

- **Screen-off / Wi-Fi sleep mid-stream.** Turning the screen off can let Wi-Fi
  doze and drop the streaming socket, which then surfaces as `[N1]`. The code is
  correct (it *is* a transport abort); the root cause is power management, and a
  potential future fix is holding a Wi-Fi lock during generation. Until then, an
  `[N1]` that coincides with the screen turning off is most likely this.
- **Higher failure rate on regenerate.** Regenerating an answer has been observed
  to fail or "glitch" more often than a first generation. This is not yet
  explained and may be a real bug rather than a wording issue. It does not get
  its own code; if it produces a transport drop it will read as `[N1]`. Flagged
  here so it is investigated separately.

When the cause of an intermittent issue is pinned down and is distinct enough to
act on, it can graduate into a real code at that time.

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
  `[U0]`; the chat shows the fixed one-line message, and the Event Log gets the
  exception class, message, and full stack trace. No raw trace ever reaches the
  chat.
- **Event Log always written.** Today errors are only shown in chat, and only
  when `showChatErrors()` is on. Under this design the `GenError` entry is written
  on **every** error path regardless of the `showChatErrors()` setting, because
  the log is the diagnostic record; the toggle still controls only whether the
  chat shows the message. (To confirm during review: is that the intended
  behavior — log always, display optional?)
- **Strings.** User-facing messages stay in `res/values/strings.xml` per project
  rules. The code prefix (`[N1] `) can be stored as part of each string or
  prepended by the classifier from a code enum; the enum approach keeps the code
  and its string in one place and is the suggested route.
- **No telemetry.** `Logger` is local-only and must stay that way; nothing here
  sends anything off device.

---

## 8. Open questions for review

1. Approve the **code set and the exact user-facing wording** in §3?
2. Confirm: **write the `GenError` Event Log entry on every error even when
   "Show chat errors" is off** (§7)?
3. Approve keeping voice on the **shared codes** with only an added Event Log
   context block, no `V` codes yet (§5)?
4. Anything in the **standard Event Log block** you want added or removed (§4)?
