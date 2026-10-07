# Usage & Cost: Where the Numbers Come From

This is the reference for the Usage & Cost screen. It explains where every
number comes from, how it is calculated, and when the screen shows
"Not Reported" instead of a number. Verify against the code before relying on
a detail; the file list at the end shows where each part lives.

## Terms

- **Token:** the unit of text an AI service counts and charges for.
- **Input:** text sent to the model that was not served from the cache.
- **Cached input:** earlier conversation text the service already processed
  and reused. Usually charged at a much lower price.
- **Cache write:** some services (Claude models) charge extra the first time
  text is stored in the cache. Counted as part of input, at its own price.
- **Output:** text the model generated, including any reasoning it was billed
  for.
- **Request:** one call to the AI service. One reply can involve several
  requests (for example, a tool call and its continuation).

## The short version

| Number on screen | Where it comes from |
| --- | --- |
| Token counts | Reported by the AI service in each reply. Never guessed for new replies. |
| Total cost | The actual charge, when the service reports one in US dollars. Otherwise the sum of the calculated parts. |
| Input / Output / Cached cost | Calculated: reported tokens × the published price saved with that request. Never provider-reported values unless the service itemizes them. |
| Prices | Fetched when the request starts and saved with the request. Never re-fetched later. |
| Cache Hit Rate | Cached input ÷ all input (cached + uncached). |

## Owner rules (October 2026)

1. Always use the most accurate source available: the service's reported
   charge, then the service's own price list (downloaded by the app while it
   runs), then OpenRouter's price for that service's model, then nothing
   ("Not Reported").
2. Services with their own documented formats (NanoGPT, Venice, xAI) are
   handled by their documentation, not by generic guessing.
3. A model's price is used only for that exact model ID, or for an alias or
   equivalent ID the provider itself publishes. No date stripping, no
   reasoning-name stripping, no partial or fuzzy name matching.
4. Units: a stated unit is used; a documented provider or format convention
   is applied; a truly unknown unit is not guessed. The size of a number never
   decides its unit.
5. The reported bill is never used to infer or correct prices or units, and
   the calculated parts are never adjusted to add up to it. A difference
   between them is allowed.
6. Prices must be finite and not negative. There is no upper limit. A
   reported zero is a real $0. Costs in a currency other than US dollars are
   treated as unavailable; there is no currency conversion.
7. An unreported cache split stays unknown. It is assumed to be zero only
   when the provider documents that the model offers no caching.
8. Reasoning tokens are added to output only where the provider documents
   that its output count leaves them out.
9. Wording on the screen (labels for calculated costs, estimates, or the
   OpenRouter price source) is on hold until the owner reviews the finished
   screen.
10. **Never hard-code prices (owner ruling, October 7 2026).** No price,
    rate, or price table is ever written into the app, including prices
    copied from a service's documentation. Prices come only from a charge the
    service reports or a price list the app downloads at the time of the
    request. Otherwise the cost is "Not Reported".

## 1. Token counts

Every request records its own token counts from what the AI service reports.

- **Input and output counts** are read from the reply's usage report. Both the
  OpenAI-style names (`prompt_tokens`, `completion_tokens`) and the
  Claude-style names (`input_tokens`, `output_tokens`) are understood.
- **Cached input** is read from `prompt_tokens_details.cached_tokens`
  (OpenAI, OpenRouter, NanoGPT, Venice), `input_tokens_details.cached_tokens`,
  or `cache_read_input_tokens` (Claude style).
- **Cache writes** are read from `cache_creation_input_tokens` (Claude style,
  also sent by NanoGPT), `prompt_tokens_details.cache_write_tokens`
  (OpenRouter) or `prompt_tokens_details.cache_creation_input_tokens`
  (Venice). When a service reports cached input but no cache-write figure,
  cache writes are recorded as zero.
- **Claude-style totals are rebuilt.** Claude's `input_tokens` excludes cached
  and cache-write text, so the app adds all three together to get total input.
  OpenAI-style `prompt_tokens` already includes cached text, so it is used as
  is.
- **Cache split not reported:** the input count is kept as reported and the
  cached amount stays unknown. It becomes zero only for a model the provider
  documents as having no caching (Venice: a model with no `cache_input` price).
- **Reasoning:** counted as output, because the user pays for it as output.
  Per provider documentation:
  - **xAI direct** reports reasoning only in
    `completion_tokens_details.reasoning_tokens`, outside `completion_tokens`,
    so it is added to output.
  - **Venice** documents reasoning tokens as part of its completion tokens,
    and **NanoGPT** documents that reasoning must not be added to output
    again, so their output counts are used as reported.
  - **Every other service** (including OpenAI, Anthropic and OpenRouter):
    the output count is used as reported. Nothing is added without a
    provider document saying it is left out.
- **A later usage report never erases an earlier count.** If a service sends
  usage in several pieces and a later piece leaves out a number, the earlier
  number is kept.
- **Missing counts stay missing.** If the service reports no usage at all,
  the request has no counts. New replies are never estimated.

**Old replies only:** replies saved before durable usage records existed have
no stored counts. For those, the app estimates input and output with a
generic tokenizer (CL100K) and marks the record as estimated. These records
have no prices and no cache information.

## 2. Prices

Prices are fetched in the background when a request starts. When the request
finishes, they are frozen into that request's record. A price change later
never changes an old record.

| Connection | Price source |
| --- | --- |
| OpenRouter | OpenRouter's provider list for that model (the endpoint list for `{model}`). The app uses the price of the provider that actually served the reply: input, output, cached and cache-write prices. |
| OpenAI direct (`api.openai.com`) | OpenRouter's **public** model list, `https://openrouter.ai/api/v1/models`, entry `openai/<model>`. |
| Anthropic direct (`api.anthropic.com`) | Same public list, entry `anthropic/<model>`. |
| xAI direct (`api.x.ai`, `us.api.x.ai`) | xAI's own price list (`/language-models`, with the user's xAI key). If that fails or has no match, the public OpenRouter list, entry `x-ai/<model>`. |
| NanoGPT (`nano-gpt.com`, `api.nano-gpt.com`) | NanoGPT's detailed model list (`/models?detailed=true`, with the user's key). |
| Venice (`api.venice.ai`) | Venice's text model list (`/models?type=text`), with Venice's compatibility mapping for aliases. |
| Featherless (`api.featherless.ai`) | Exact model detail (`/v1/models/{model-id}`); decimal USD per-token `pricing.prompt` and `pricing.completion`. |
| Any other service | That service's own `/models` list, if it uses OpenRouter's `pricing` layout. Otherwise no prices. |

The built-in OpenAI price table that existed before has been removed. Its
values were ten times too high.

### OpenRouter's public list (OpenAI, Anthropic, xAI fallback)

OpenAI and Anthropic give no prices through their model lists. OpenRouter
publishes current prices for their models in a list anyone can download.

- **No key is sent.** The user's OpenAI, Anthropic or xAI key never goes to
  OpenRouter.
- **Saved for six hours** in memory, then downloaded again. Not saved to disk.
- **Matching:** the entry whose `id` is exactly `<author>/<model>`, or whose
  `canonical_slug` (OpenRouter's own permanent ID for that entry) is exactly
  that. Nothing else.
- **Consequence:** OpenAI and Anthropic often report dated model names (for
  example `claude-sonnet-4-5-20250929` or `gpt-5-2025-08-07`). OpenRouter
  usually lists those models under a different name (`claude-sonnet-4.5`,
  `gpt-5`) and does not publish the provider's dated name. Those requests
  have no price and show "Not Reported" costs.
- **Zero and negative prices:** a listed `0` is a real $0, including `:free`
  entries when that exact ID is used. Negative prices (OpenRouter's marker
  for variable pricing) are not used.

### xAI's own price list

xAI publishes prices in `/language-models`, in US cents per 100 million
tokens; the app divides by 10,000,000,000 to get dollars per token. A model is
matched by its exact `id` or one of the `aliases` xAI lists for it. Fetched on
every request and not kept.

| Field | Used for |
| --- | --- |
| `prompt_text_token_price` | Input |
| `cached_prompt_text_token_price` | Cached input |
| `completion_text_token_price` | Output |
| `long_context_threshold` | Prompt size at or above which the long-context rates apply to every token in the request. 0 means the model has no long-context tier. |
| `prompt_text_token_price_long_context` | Input, long context. 0 means the standard price applies. |
| `cached_prompt_text_token_price_long_context` | Cached input, long context. 0 means the standard price applies. |
| `completion_text_token_price_long_context` | Output, long context. 0 means the standard price applies. |

An absent field is read as 0, the default value of xAI's numeric fields. The
prompt size compared with the threshold is the request's total input tokens.
If the input count is unknown and the model has a long-context tier, the
applicable rates are unknown.

If xAI's own list cannot be reached or has no match, OpenRouter's public
price for `x-ai/<model>` is used, and if that is also unavailable the costs
show "Not Reported" (owner decision, October 2026). OpenRouter's list has no
long-context rates, so on that fallback a very long prompt is priced at the
standard rate; the Total is still xAI's reported charge when xAI sends one.

### xAI US regional endpoint

`https://us.api.x.ai/v1` is also an official xAI endpoint, so separately
reported reasoning is included in output there too. xAI documents US token
rates as 1.1 times the global rates, including cached input and long context.
For a US connection, pricing metadata is read from xAI's global
`https://api.x.ai/v1/language-models` using the same xAI key (officially valid
on both hosts), then every token rate is multiplied by 1.1 exactly once.
Generation remains on the configured US endpoint; no prompts are sent to the
global pricing endpoint.

The settled source order is unchanged: xAI's list first, public OpenRouter
fallback second, then Not Reported. The same regional multiplier is applied
to either global price source. Missing prices stay missing, zero stays zero,
and a provider-reported billed charge is preserved exactly, never multiplied.
No supported model list or production model price is hard-coded.

Sources verified October 3, 2026:
- https://docs.x.ai/developers/pricing#us-regional-endpoint-pricing
- https://docs.x.ai/developers/advanced-api-usage/regions

### NanoGPT

From NanoGPT's documentation (`/models?detailed=true`):

- `pricing.prompt` and `pricing.completion`: US dollars per million tokens,
  with `currency: "USD"` and `unit: "per_million_tokens"`. A list with any
  other currency or unit is not used.
- `cacheReadInputPer1kTokens` and `cacheWriteInputPer1kTokens`: US dollars
  per thousand tokens, as their names state.
- Matched by exact model `id`.

### Venice

From Venice's API specification (`/models?type=text`, `model_spec.pricing`):

- `input.usd`, `output.usd`: US dollars per million tokens.
- `cache_input.usd` (cache reads) and `cache_write.usd` (cache writes): US
  dollars per million tokens. `cache_input` is present only for models that
  support context caching; a model without it has zero cached usage.
- `extended`: long-context rates. When a request's input tokens exceed
  `extended.context_token_threshold`, the extended `input`, `output`,
  `cache_input` and `cache_write` rates apply to the entire request. If the
  input count is unknown, the applicable rates are unknown.
- DIEM prices are not used.
- Matched by exact model `id`. If there is none, Venice's
  `/models/compatibility_mapping?type=text` (Venice's published alias table)
  is checked, and the model it maps to is looked up by exact `id`.

### Other services

A service's own `/models` list is used only when it has OpenRouter's
`pricing` layout (`prompt`, `completion`, `input_cache_read` or
`cached_prompt`, `input_cache_write`) and the model's exact `id`:

- a stated `unit` of `per_token`, `per_thousand_tokens` or
  `per_million_tokens` is applied; any other stated unit makes the price
  unknown;
- with no stated unit, OpenRouter's convention (US dollars per token)
  applies;
- a stated `currency` other than `USD` makes the price unknown.

### Timing limits

- A normal finished reply waits up to 2 seconds for prices. If the reported
  model differs from the requested one, it fetches prices for the reported
  model, again waiting up to 2 seconds.
- A stopped or failed request waits only 0.5 seconds for prices.
- If prices are not ready in time, that request is saved without prices.

## 3. Cost calculation per request

### When the service reports the actual charge

| Service | Where the charge comes from |
| --- | --- |
| OpenRouter, and any service using these names | `usage.cost` or `usage.total_cost`, in dollars |
| xAI | `usage.cost_in_usd_ticks`; 10,000,000,000 ticks per dollar |
| NanoGPT | `x_nanogpt_pricing`: `amount` when `currency` is `USD`, otherwise `cost` when `currency` or `paymentSource` is `USD`. A charge in XNO, or with no stated currency, is not used. |
| Venice | Top-level `cost`: `usd` is used only when `diem` is 0. If any part was billed in DIEM, the charge is not used. |

- **Total** is the reported charge, exactly.
- **Cached and Output costs** use the service's own itemized figures when it
  sends them. Otherwise they are calculated from tokens × the saved prices.
- **Input cost** is always calculated.
- **The calculated parts may not add up to the Total, and that is allowed.**
  The service does not say why they differ (discounts, fees, subscription
  coverage, long-prompt rates).

### When the service does not report a charge

- **Input cost:** (input − cached − cache writes) × input price, plus
  cache writes × cache-write price.
- **Cached cost:** cached tokens × cached price. Zero cached tokens cost $0,
  even if no cached price is published.
- **Output cost:** output tokens × output price.
- **Total:** input cost + cached cost + output cost.
- **Missing pieces:** if a needed price or the cache split is unknown, that
  piece is unknown, and the Total is unknown too.

## 4. What the screen adds up

The screen opens from the chat menu (**Usage & Cost**). It is built from the
chat's usage log (section 5) at the moment it opens.

- **Sections (owner ruling, October 6 2026):** requests are split into
  sections, top to bottom: **Chat, Image Generations, Summarizing, STT, TTS**.
  Each section's title sits in a centered pill above its cards. A section
  with no requests in this chat is not shown.
- **Grouping:** inside a section, records are grouped by model, then by
  provider. Upper and lower case are ignored. Each model is one card: the
  model's name, total, and request count on top, then one block per provider.
- **What a Summarizing model did:** in the Summarizing section only, a line
  under the model's request count lists what that model was used for, in this
  order and separated by commas: Summarizing, Compacting, Condensing,
  Reducing, Image Description, Removal. Only the ones used appear.
- **Conversation Total:** the sum of every request's Total, in every section.
- **Model Total / Provider Total:** the sum for that model or provider within
  its section.
- **Rows:**
  - **Input:** input minus cached. This includes cache writes.
  - **Cached:** cached input.
  - **Output:** output.
- **Cache Hit Rate:** cached ÷ all input, for that provider.
- **Price per 1M Tokens:** shows the price when every request in the group
  used the same price. It shows "Variable" when prices differed between
  requests, or when cache writes were charged at a different rate than input.

### "Not Reported" rule

A sum shows "Not Reported" if any request in it is missing that value. A
partial sum is never shown as if it were complete.

## 5. Which requests are counted

- **Every finished request is counted.** This includes tool-call requests
  that never produce visible text; those are stored on the user's message.
- **Stopped or failed requests are counted only when the service actually
  reported usage or a charge.** They are never estimated. The record is
  stored on the latest AI reply, or the latest user message if there is no
  reply. If that reply has several versions, the record is also written into
  the version the conversation continues from, so it is counted and kept
  when another version is shown. If a tools-not-supported retry removes an
  empty reply, its frozen records are first moved to the initiating user
  message, without recalculating any count, price, or cost.
- **Regenerated replies:** every version's requests are counted, not just the
  version on screen, including versions from a different model and versions
  that were never used.
- **Summarizing section:** every Summarizer section (Summarizing), Compact
  fold-in (Compacting), Condense (Condensing), Reduce (Reducing), Summarizer
  image description (Image Description), and the short reminder written when
  an attachment is removed (Removal) is a paid request and is counted.
- **Recorded the same way as chat replies:** counts and charges come only from
  the service's report, and prices are frozen when the request finishes. A
  finished request with no usage report is still counted, with its values
  "Not Reported"; a failed one only when the service reported usage.
- **TTS section:** every API voice request that returned audio is counted.
  Section 7 explains how.
- **Image Generations:** `/imagine` and AI `create_image` jobs record each
  dispatched HTTP attempt through the shared registry/coordinator. A stable
  request entry is committed before dispatch, enriched with the full allowlisted
  usage before payload parsing/download, and finalized even on cancellation or
  failure. Unconfirmed billing stays Not Reported; an attempt is never called free.
- **Not counted yet:** Whisper cloud voice input (audio sent to OpenAI's `whisper-1`
  transcription service; STT). Their sections exist and appear once they are
  recorded. The STT section is meant for any speech-to-text service, not only
  Whisper.

### The usage log (owner ruling, October 6 2026)

Each chat keeps its own usage log in its per-chat settings file
(`usage_log`). Entries are only ever added: deleting a message,
regenerating (including an earlier reply, which removes everything after it), making
another version current, or compacting never removes usage already spent.

- **First open:** the records already stored in the chat's messages are copied
  into the log once. Requests recorded before that (for example by the
  Summarizer) are kept and merged.
- **Each entry** keeps the request's frozen record, what it was for (chat,
  its section and, for Summarizing, its function), and the permanent id of the message
  it served, when there is one. Neither depends on a message's position.
- **Old replies** saved before usage records existed are not in the log;
  they are still estimated from the messages present (section 1).

Chat replies also keep their records inside the message, in the
`tokenUsageRecords` field of each message and of each reply version, so a
reply's details still show its own usage.

### Backup and restore

Chat backups copy each chat's stored messages and its per-chat settings file
unchanged, so usage records (and their copies inside reply versions) and the
usage log, including TTS entries, are exported and restored with them. A backup made before usage records existed restores normally; its
replies fall back to the old-reply estimate described in section 1.

## 6. Settled behavior and open items (October 2026)

Settled by the owner:

- A legitimate "Not Reported" is correct. It is never replaced by a guessed
  mapping or assumption. This includes OpenAI and Anthropic dated model names
  with no exact OpenRouter entry, and NanoGPT replies whose model name does
  not exactly match a price-list entry.
- The Input row is non-cached input. When a provider reports total input but
  not the cached portion, it shows "Not Reported". When a provider reports
  non-cached input itself (Claude-style `input_tokens`), that is used.
- Testing against the real services is a testing limitation, not a defect.

On hold until the owner reviews the finished screen:

1. Labels for calculated costs, estimates, and the OpenRouter price source.
2. NanoGPT subscription-covered display. NanoGPT's documentation does not say
   how a reply marks subscription coverage; a $0 USD receipt is shown as a
   $0 charge.
3. Display of old and estimated records.
4. Subscription services in general (such as OpenCode Go).

## 7. TTS (text to speech)

Device (Google) voices are free and not recorded. API voices are recorded
in the unit each service actually bills, never converted to text tokens.

### When a request is counted

- **Counted when the audio arrives.** The service charges for synthesis, not
  playback. A request is recorded once the service has returned valid audio,
  before playback starts. A playback error, Stop, or leaving the screen
  afterwards does not remove it.
- **Each synthesis is its own request.** A Retry that synthesizes again is
  counted again.
- **Not counted:** a request stopped before the service answered, and a
  request that failed without returning audio. No charge is invented for
  them.
- **Which chat:** read-aloud and hands-free readback in a chat are recorded
  in that chat. A Voice Browser preview is real paid synthesis. It is
  recorded in a chat only when the Voice Browser was opened from that chat's
  settings and the chat exists. A preview from the Voice Browser opened from
  the main settings (no chat) is **not recorded anywhere**. There is no
  app-wide usage log yet.
- **Recorded in the background.** Cost details are completed after playback
  has started, so they never delay speech. The usage entry is written by the
  app's process, so closing the chat straight away does not lose it. If the
  app process itself ends before it is written (for OpenRouter, up to about
  15 seconds while the charge is looked up), that entry is lost.

### Billing units

| Row | Unit | How the quantity is known |
| --- | --- | --- |
| Characters | characters | The service's report (ElevenLabs), or counted from the exact text sent. Characters are Unicode code points: an emoji counts once, not as two. |
| UTF-8 Bytes | bytes | Counted from the UTF-8 encoding of the exact text sent. |
| Text Input | tokens | Only from the service's report (OpenAI). Never estimated. |
| Audio Output | tokens | Only from the service's report (OpenAI). Never estimated. |
| Audio Output | seconds | Measured from the MP3 audio returned (its frame headers). Never estimated from text length. |

The TTS card shows only these rows. There is no Cached row and no Cache Hit
Rate, because no supported speech service reports a cache. The price line
under the rows names the actual basis: per 1M characters, per 1M tokens,
per 1M UTF-8 bytes, or per minute of audio. "Variable" means requests in the
group used different prices.

### Cost source order

1. The service's reported US-dollar charge for this request.
2. The service's reported quantity × its price frozen with the request.
3. An exact locally known quantity × the price frozen with the request.
4. Otherwise "Not Reported".

The total is the reported charge when there is one, even when no detail row
can be shown. Otherwise it is the sum of the rows, and only when every
billed row has a cost; a partial sum is never shown as the total. A request
whose billing unit is unknown is still counted, with "Not Reported" costs.

### OpenRouter (including ElevenLabs and other models routed through it)

Speech uses OpenRouter's own `/audio/speech` request and key. A model such
as `elevenlabs/eleven-turbo-v2` chosen on an OpenRouter connection is an
OpenRouter request, and is shown as one.

- **Reported charge:** after the audio arrives, the app asks OpenRouter's
  generation record (`/generation?id=…`) for the `X-Generation-Id` the speech
  response returned. `total_cost` becomes the Total; `provider_name` becomes
  the provider shown; `model` the model shown. OpenRouter documents the
  generation ID on speech but does not promise speech appears in that
  record, so this is best effort: it is asked again after about 1.5, 3 and 6
  seconds, and a failure never affects speech or shows an error.
- **Serving provider:** only what OpenRouter reports. Routing can fall back,
  so the requested provider is never assumed. Without a report the provider
  shows "Not Reported".
- **Price fallback and detail rows:** the price list OpenRouter publishes for
  the model's providers (the same list the provider picker reads), for the
  provider that served the request. When the serving provider is unknown, a
  price is used only if every listed provider charges the same. A price is
  applied only when each paid part states a unit this app can measure
  exactly (characters, bytes, seconds/minutes, tokens). OpenRouter's flat
  `prompt`/`completion` fields without a stated unit are not applied, so
  such requests show "Not Reported" unless OpenRouter reported the charge.
  OpenRouter does not report tokens for speech, so token-priced speech
  models (such as Gemini TTS) have unknown token counts.

### OpenAI direct (`api.openai.com`)

OpenAI's model list does not mark speech models, so on the official host the
documented speech model IDs are recognized exactly: `tts-1`, `tts-1-1106`,
`tts-1-hd`, `tts-1-hd-1106`, `gpt-4o-mini-tts`,
`gpt-4o-mini-tts-2025-03-20`, `gpt-4o-mini-tts-2025-12-15`.

- **Cost: "Not Reported".** OpenAI reports no charge for speech and
  publishes no price list the app can download, and prices are never written
  into the app (owner rule 10). The quantities below are still shown.
- **`tts-1` and `tts-1-hd` (and their dated versions):** billed per character;
  the Characters row counts the exact text sent.
- **`gpt-4o-mini-tts` and its dated versions:** requested with
  `stream_format: "sse"`. The audio arrives in `speech.audio.delta` events,
  which are decoded and joined in order; the final `speech.audio.done` event
  reports `input_tokens` and `output_tokens`, shown as Text Input and Audio
  Output. If OpenAI sends no usage, the token counts are "Not Reported".
- SSE is used only on the official host and only for the models above. Every
  other OpenAI-compatible service keeps the ordinary audio request.
- A third-party price listing reports that `tts-1` is scheduled for
  deprecation on December 15, 2026. This was not confirmed on OpenAI's own
  page. Speech keeps using `/audio/speech`; nothing was moved to Realtime.

### ElevenLabs direct

Official ElevenLabs hosts are recognized exactly: `api.elevenlabs.io`,
`api.us.elevenlabs.io`, `api.eu.residency.elevenlabs.io`,
`api.in.residency.elevenlabs.io`. The connection's address must include
`/v1` (for example `https://api.elevenlabs.io/v1`). There is no ElevenLabs
setting to choose: on these addresses the key is always sent in ElevenLabs'
`xi-api-key` header, whatever the connection's Auth mode says.

- **Speech:** `POST {address}/text-to-speech/{voice_id}?output_format=mp3_44100_128`
  with `text` and `model_id`. The connection's Text to Speech Endpoint
  setting is not used for ElevenLabs.
- **Models and voices:** `GET {address}/models` (models whose
  `can_do_text_to_speech` is true) and `GET {address}/voices`, shown in the
  same Voice Browser.
- **Usage:** the `character-cost` response header is the reported character
  count. If it is missing, the count is "Not Reported".
- **Cost: "Not Reported".** ElevenLabs reports no dollar charge and
  publishes no price list the app can download, and prices are never written
  into the app (owner rule 10).

## 8. Featherless

Implemented using official documentation verified October 3, 2026:

- https://featherless.ai/docs/api-reference-models
- https://featherless.ai/docs/completions
- https://featherless.ai/docs/api-reference-plan
- https://featherless.ai/docs/api-reference-usage-activity
- https://featherless.ai/docs/billing
- https://featherless.ai/docs/request-pricing-and-credits

`https://api.featherless.ai/v1` is recognized by its exact official host.
Each request fetches `/v1/models/{model-id}` with the exact model ID (including
its owner prefix). The returned ID must match exactly. `pricing.prompt` and
`pricing.completion` are decimal strings in USD per token. Zero is valid;
negative, non-finite, malformed, and missing prices are unavailable. No
production prices or model aliases are hard-coded.

The normal completion response documents `usage.prompt_tokens`,
`completion_tokens`, and `total_tokens`. These remain provider-reported.
There is no documented completion billing receipt. The standard parser also
preserves any explicitly reported standard cache split; a missing split is
unknown. Cached input is a subset of input, so the Input row subtracts it
rather than adding it to the total. Reasoning is not added to output again.

The model-detail documentation does not identify a cached-input price field
or guarantee that missing cache pricing means no caching. No such field or
zero-cache assumption is invented. A positive reported cache count therefore
has no calculated cached cost until an official cache price can be read.
Without a reported split, input costs remain unavailable; output can still be
calculated when billing applicability is established.

### Billing applicability and calculated costs

The ordinary `/v1/plan` API accepts a normal API key. The exact plan ID
`feather_request_pricing` is documented together with
`billing_mode: request_pricing` in the official usage activity example.
Only that known ID enables calculated component costs from the live model
prices. This is a conservative plan-ID association, not a per-request billing
receipt. Future/unknown IDs, the documented `feather_pro_plus` subscription,
and failed or malformed plan reads retain prices and tokens but leave every
request cost unavailable. No subscription usage receives an invented $0 or
per-token charge. Prices and resulting costs are frozen in the existing usage
records; backup and restore are unchanged.

### Why exact activity billing is not integrated

`/usage/activity/requests` requires an organization Admin key with
`manage_billing`. It exposes applied rates and costs in nano USD, cache
counts, `billing_mode`, `cost_status` (`final`, `not_applicable`, or
`unavailable`), and pricing-source information. Its `request_id` is not
documented as the ordinary completion `id`; there is no documented exact
join or single-completion lookup. Time/model/token-count matching and polling
would not establish an exact association. No activity or credits calls are
made, even with an Admin key. Thus unavailable optional billing-detail access
cannot break normal usage tracking. Actual billed totals and billing statuses
are not fabricated from these inaccessible records.

Tests: `FeatherlessUsageTest.kt` covers exact host/model matching, detail URL
encoding, normal responses, cache subset accounting, calculated components,
zero and invalid rates, unavailable detail/plan information, flat/unknown
plans, absence of admin calls, reasoning, and frozen-record serialization.

## Where the code lives

| Part | File |
| --- | --- |
| Reading usage and charges from the reply stream | `app/src/main/java/org/teslasoft/assistant/providers/ReportedProviderParser.kt` (`RawSseInspector`) |
| Per-request capture, reasoning as output | `app/src/main/java/org/teslasoft/assistant/usage/ProviderUsageAttempt.kt` (`outputIncludingReasoning`) |
| Price fetching and matching | `app/src/main/java/org/teslasoft/assistant/usage/TokenPricingCatalog.kt` (`TokenPricingCatalogClient`, `PricingSource`, `FirstPartyPricing`, `NanoGptPricing`, `VenicePricing`, `GenericPricing`) |
| Cost math, long-context tier, grouping, "Not Reported" formatting | `app/src/main/java/org/teslasoft/assistant/usage/TokenUsageAccounting.kt` |
| When records are created and attached | `app/src/main/java/org/teslasoft/assistant/ui/activities/ChatActivity.kt` (`completePendingUsageRecord`, `completeTerminalUsageRecord`, `attachUsageRecords`, `appendUsageLog`, `withAttachmentUsage`, `openUsageAndCost`) |
| The usage log | `app/src/main/java/org/teslasoft/assistant/usage/UsageLog.kt` (`UsageLogState`, `UsageLogStore`) |
| Attachment and Summarizer request records | `app/src/main/java/org/teslasoft/assistant/usage/AuxiliaryUsage.kt`; `app/src/main/java/org/teslasoft/assistant/util/summarizer/SummarizerController.kt` (`withSummarizerUsage`) |
| The screen | `app/src/main/java/org/teslasoft/assistant/ui/activities/TokenPricingDetailsActivity.kt` |
| Non-token (metered) usage, its grouping and storage | `app/src/main/java/org/teslasoft/assistant/usage/MeteredUsage.kt` (`UsageMeter`, `MeteredUsageAccounting`, `UsageMeterCodec`) |
| TTS request formats, OpenAI SSE, MP3 duration | `app/src/main/java/org/teslasoft/assistant/tts/api/TtsServices.kt`, `TtsTransport.kt` |
| TTS usage records, prices, OpenRouter lookup, recording | `app/src/main/java/org/teslasoft/assistant/tts/api/TtsUsage.kt` (`TtsUsageAccounting`, `OpenAiSpeechPricing`, `OpenRouterGenerationClient`, `TtsUsageRecorder`) |
| Tests | `app/src/test/java/org/teslasoft/assistant/usage/`, `app/src/test/java/org/teslasoft/assistant/providers/ReportedProviderParserTest.kt`, `app/src/test/java/org/teslasoft/assistant/preferences/backup/portable/PortableChatRestorePlanTest.kt`, `app/src/test/java/org/teslasoft/assistant/tts/api/` (`TtsUsageTest`, `TtsWireFormatTest`, `TtsProviderDiscoveryTest`, `TtsPlaybackUsageTest`), `app/src/test/java/org/teslasoft/assistant/ui/activities/UsageCostTtsRenderingTest.kt` |


## 8. Image generation evidence and settings

Image generation reuses generic meters, not chat text-token fields. Image count,
actual output megapixels, modality token counts, cached modality token counts, and
provider-reported credits retain their own units. Fractional credits are preserved;
there is no invented credit-to-dollar exchange rate. Only USD enters dollar totals.
Request IDs, selected and reported model IDs, endpoint, start time, HTTP status,
effective settings, pricing source, and resolved tariff evidence are retained.
Prompts, API keys, temporary image URLs, and raw response bodies are not copied into
this accounting evidence.

| Request protocol | Usage and authoritative cost evidence |
| --- | --- |
| Direct OpenAI `/images/generations` | Images API usage details; exact model documents supply frozen token rates and explicit aliases. Without output details, only an explicitly published image-only output modality can resolve output tokens to image tokens. A missing cache split with distinct cached rates leaves the total Not Reported. Approximate per-image pricing examples are never used as actual token-billed charges. |
| Direct Gemini native `generateContent` | `usageMetadata` modality counts and additional thinking counts; exact model sections on Google's pricing page, standard synchronous USD token rates, only when the same section confirms no free tier. Missing modality counts, ambiguous tiers, aliases, or unparsed prices remain Not Reported. |
| OpenRouter dedicated `/images` | Response `usage.cost` is documented USD. An exact generation ID can retrieve the generation receipt. Frozen per-endpoint tariffs are applied only for the identified route, or when all possible routes publish identical tariffs. |
| NanoGPT dedicated `/images` | Model and endpoint descriptors supply settings and public tariffs. `X-Request-ID` retrieves the exact primary-charge receipt using the original key; explicit USD charges are used. XNO and unlabeled amounts retain their native evidence with USD cost Not Reported. The receipt excludes refunds and separately billed extras. |
| Other OpenAI-compatible `/images/generations` | Preserve real image/request/usage information and explicit provider-reported currency/cost or credits. Unsupported or unavailable metadata never triggers a guessed model price or capability. |

A receipt lookup retries only GET requests, never paid generation. Each enrichment
updates the same image-request entry, so it cannot count a second charge. Deleting
an image/message cannot remove its log entry; a receipt cannot resurrect an entry
removed with the chat. Pending receipts unavailable within the bounded lookup window
remain Not Reported. Pricing is resolved before dispatch and stored with the request;
opening Usage & Cost never fetches newer rates to reprice history.

The image menu orders the app toggles above Image Generation Options, Model Provider,
Model, and published model settings. Labels use toggle-row typography. Enum choices
and numeric bounds are fetched, never assigned by model name. Settings are scoped to
the endpoint and exact model; historical shorthand defaults are translated against
published choices, and unsupported historical defaults defer to the provider.
Explicit request overrides and saved model-specific settings are strictly validated
before dispatch. Unavailable metadata leaves provider defaults available and cost
unknown; it does not fabricate extra controls.

Authoritative references: [OpenAI Images](https://developers.openai.com/api/reference/resources/images/methods/generate/),
[OpenAI model documents](https://developers.openai.com/api/docs/models),
[Gemini image generation](https://ai.google.dev/gemini-api/docs/generate-content/image-generation),
[Gemini pricing](https://ai.google.dev/gemini-api/docs/pricing),
[OpenRouter Image API](https://openrouter.ai/docs/guides/overview/multimodal/image-generation),
[NanoGPT Image API](https://docs.nano-gpt.com/api-reference/image-generation),
[NanoGPT request billing](https://docs.nano-gpt.com/api-reference/endpoint/request-billing).
