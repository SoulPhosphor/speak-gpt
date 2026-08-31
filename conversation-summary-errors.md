# Conversation Summarizer Error Wording

**Status: NORMATIVE ADDENDUM TO `conversation-summary-plan.md` §5, DECISION 16.**

Prepared July 28, 2026 at the owner's request because the approved plan defined
how Summarizer Errors are displayed but deferred the actual failure list and
user-facing wording until implementation. This document supplies that missing
contract. Implementation must use these failure categories and exact messages
unless the owner later changes them.

---

## 1. Safety and display rules

A summary failure must never block or delay the user's regular chat request.
The fold-in bookmark advances only after the returned summary has been
validated and both the summary and bookmark have been saved successfully.
Until then, the previous summary remains unchanged and every message after the
bookmark continues to be sent to the chat model in full.

Summarizer failures are shown only in the per-chat **Summarizer Errors** dialog.
They are never inserted into the conversation, summary, system prompt, or any
API request.

The dialog begins with exactly one status paragraph. Do not repeat this text
inside every stored error.

### Summarizer is on and currently behind

> The summary is behind because one or more updates failed. No messages were
> lost. Until it catches up, unsummarized messages are being sent to the AI in
> full.

### Summarizer has caught up but old errors remain in the log

> The summarizer has caught up. No messages were lost. The errors below are
> kept for review until you hide them.

### Summarizer is off for this chat

> The summarizer is off for this chat. No messages were lost. All messages are
> being sent to the AI in full. The errors below are kept for review until you
> hide them.

Each stored error shows, in this order:

1. date and 12-hour time without seconds;
2. the Title Caps error title below;
3. the exact user-facing message below;
4. the selected endpoint profile and model;
5. a sanitized provider or technical detail when one is available.

Never display or copy an API key, authorization header, complete request body,
conversation text, or summary text. A provider's error message may be included
only after those values are removed.

Each stored error carries its own **Copy** and **Hide** (Hide removes that one
entry). Beneath the list are the whole-list actions **Hide All**, **Copy All**,
and **Okay** (Okay only closes), top to bottom, in Title Caps (owner ruling,
Aug 31 2026).

---

## 2. Failure categories and exact messages

### 2.1 Summary Model Missing

**Use when:** the locally selected endpoint or model can no longer be resolved,
for example because the endpoint profile was deleted after this chat enabled
the summarizer.

> The selected Summary Model is no longer available. Choose another model in
> Summarizer Settings.

### 2.2 AI Service Unreachable

**Use when:** the host cannot be reached or resolved, the device is offline, or
the connection fails before a more specific timeout can be identified.

> The summarizer couldn't reach the selected AI service. Check your connection
> and the selected endpoint, then try again.

### 2.3 Connection Timed Out

**Use when:** the app could not establish a connection before the selected
endpoint's Connection Timeout expired.

> The summarizer couldn't connect before the endpoint's Connection Timeout
> expired. The app will try again automatically. Check your connection or
> increase Connection Timeout for the selected endpoint.

### 2.4 Response Timed Out

**Use when:** the connection succeeded, but the endpoint did not return a
complete response before its Response Time expired.

> The summarizer connected, but the model didn't respond before the endpoint's
> Response Time expired. The app will try again automatically. You can also
> choose another Summary Model or increase Response Time for the selected
> endpoint.

### 2.5 Access Rejected

**Use when:** the provider rejects the API key, authentication method, account,
or permission to use the endpoint.

> The selected AI service rejected access. Check the endpoint's API key,
> authentication mode, and model access.

### 2.6 Model Unavailable

**Use when:** the endpoint responds, but reports that the selected model does
not exist, cannot be used on that endpoint, or is unavailable to the account.
This is separate from **Summary Model Missing**, which is detected locally
before a request is made.

> The selected AI service couldn't use the Summary Model. Check the model name
> and access, or choose another model in Summarizer Settings.

### 2.7 Rate Limit Reached

**Use when:** the provider rejects the call because of a temporary request or
token-throughput rate limit.

> The selected AI service temporarily limited summary requests. The app will
> try again automatically. If it continues, wait or choose another Summary
> Model.

### 2.8 Quota Reached

**Use when:** the provider reports that the account's quota, credits, budget, or
spending limit has been exhausted.

> The selected AI account reached its quota or spending limit. Check the
> provider account's usage and billing, or choose another Summary Model.

### 2.9 Summary Request Too Large

**Use when:** the provider or the app's capacity check determines that the
current summary plus the messages being folded in cannot fit the selected
model's context, input, or request-body limit.

Before storing this error, automatically split a multi-message batch into
smaller batches and retry, down to one message. Store this error only when the
current summary or a single departing message still cannot fit. Do not advance
the bookmark past the unprocessed message.

> The current summary or one or more messages are too large for the selected
> Summary Model to process. Shorten the summary, choose a model or endpoint with
> a larger input limit, or turn off the summarizer for this chat.

### 2.10 Summary Request Rejected

**Use when:** the provider explicitly rejects the summary request because of
its content or safety rules. Do not include the rejected conversation text in
the error entry.

> The selected AI service rejected the summary request because of its content
> rules. Choose another Summary Model or turn off the summarizer for this chat.

### 2.11 AI Service Error

**Use when:** the provider returns a server-side or other HTTP error that does
not fit a more specific category above.

> The selected AI service returned an error while updating the summary. The app
> will try again automatically. If it continues, check the endpoint or choose
> another Summary Model.

Include the sanitized HTTP status and provider message in the technical detail
when available.

### 2.12 Summary Response Unreadable

**Use when:** the request succeeds but the response is empty, whitespace-only,
missing the expected response text, malformed, or otherwise cannot be accepted
as an updated summary.

> The Summary Model returned a response the app couldn't use. The previous
> summary was kept unchanged. Try another model or check whether the endpoint
> supports chat completions.

A response that merely exceeds the requested word count is not unreadable. Save
it only after applying the implementation's approved length handling; do not
silently discard a valid response.

### 2.13 Summary Couldn't Be Saved

**Use when:** the model returned a usable summary, but the app could not persist
the updated summary and bookmark on the device.

> The model returned an updated summary, but the app couldn't save it on this
> device. The previous summary and bookmark were kept. Check available storage
> and try again.

The summary and bookmark must be committed atomically. Never save one without
the other.

### 2.14 Unexpected Summarizer Error

**Use when:** no category above safely describes the observed failure.

> The summary update failed for an unknown reason. The previous summary and
> bookmark were kept. Copy this error when reporting the problem.

Include the sanitized exception class and message in this entry's technical
detail. The full stack trace is deliberately NOT stored here — it read like a
crash dump in the dialog — and stays in the app's own error/crash logs instead
(owner ruling, Aug 31 2026).

---

## 3. Retry, sound, badge, and deduplication rules

A failure episode begins with the first failed fold-in after either a successful
fold-in or a different failure category. The dedicated summarizer error sound
plays once at the start of that episode.

Repeated retries that fail for the same category while the bookmark has not
advanced must not fill the five-entry log with duplicates. Instead, update the
existing entry's timestamp and repetition count. Use this exact count line when
the count is greater than one:

> Repeated %1$d times.

A successful fold-in ends the episode. The old entry remains in the log until
the user hides it, but later failures begin a new entry and may play the sound
again.

The top-bar count badge is the always-available failure indicator (owner
ruling, Aug 31 2026), so a user can never mistake a silently failing summarizer
for a working one. It shows whenever the log has entries, and carries a state:

- **Alert** — a red number on a white, red-ringed circle, deliberately fixed
  colors (not theme attributes) so a new, unacknowledged failure is
  unmistakable in every theme. Set the moment a failure is recorded, including
  a failure after the log was hidden.
- **Neutral** — the ordinary badge look, shown once the user has opened the
  errors list. It stays as a quiet reminder while entries remain and later
  fold-ins succeed; a new failure returns it to Alert.

**Hide All** clears the log and drops the badge until the next failure; a single
**Hide** drops the badge only when it removes the last entry.

Automatic retry occurs on the next eligible summarizer cycle. Do not run a
rapid retry loop in the background.

---

## 4. Events that are not Summarizer Errors

The following must not create an error entry, increment the badge, or play the
summarizer error sound:

- there are not yet enough departed messages to form a batch;
- the summary is already up to date;
- the user turns **Use Summarizer** off;
- the user leaves the chat or closes the app;
- Android stops the app process;
- the chat is deleted;
- an in-flight call is deliberately cancelled because the selected endpoint,
  model, prompt, or chat state changed;
- a retry is merely waiting for the next eligible cycle.

In all cancellation cases, leave the bookmark unchanged. When the summarizer is
used again, the normal catch-up mechanism decides what still needs to be folded
in.

---

## 5. Relationship to the main Error Log

The per-chat Summarizer Errors dialog is the user-facing record for this
feature. The same failure may also write a concise diagnostic entry to the
app-wide Error Log, using the app's existing network, authentication, model,
quota, server, and unknown classifiers where they apply.

The app-wide log must not replace the per-chat record: the user needs to know
which conversation is currently sending unsummarized messages in full. The
per-chat record must not replace the app-wide diagnostic log: developers need
the shared technical context when troubleshooting provider or transport
failures.
