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
| Total cost | The actual charge, when the service reports one (OpenRouter, xAI). Otherwise tokens × the price saved with that request. |
| Input / Output / Cached cost | Always tokens × the price saved with that request. |
| Prices | Fetched when the request starts and saved with the request. Never re-fetched later. |
| Cache Hit Rate | Cached input ÷ all input (cached + uncached). |

## 1. Token counts

Every request records its own token counts from what the AI service reports.

- **Input and output counts** are read from the reply's usage report. Both the
  OpenAI-style names (`prompt_tokens`, `completion_tokens`) and the
  Claude-style names (`input_tokens`, `output_tokens`) are understood.
- **Cached input** is read from `prompt_tokens_details.cached_tokens`
  (OpenAI and OpenRouter style), `input_tokens_details.cached_tokens`, or
  `cache_read_input_tokens` (Claude style).
- **Cache writes** are read from `cache_creation_input_tokens` (Claude
  style) or `prompt_tokens_details.cache_write_tokens` (OpenRouter style).
  When a service reports cached input but no cache-write figure, cache writes
  are recorded as zero.
- **Claude-style totals are rebuilt.** Claude's `input_tokens` excludes cached
  and cache-write text, so the app adds all three together to get total input.
  OpenAI-style `prompt_tokens` already includes cached text, so it is used as
  is.
- **Reasoning counts as output** (owner decision, October 2026), because the
  user pays for it as output. Most services already include reasoning in
  their output count. Some, such as xAI, report it only separately
  (`completion_tokens_details.reasoning_tokens`). The app adds it to output
  only when the service's own total shows it was left out, meaning input +
  output + reasoning equals the reported total. Otherwise the output count is
  used as reported, so reasoning is never counted twice. If the service sends
  no total, nothing is added.
- **A later usage report never erases an earlier count.** If a service sends
  usage in several pieces and a later piece leaves out a number, the earlier
  number is kept.
- **Missing counts stay missing.** If the service reports no usage at all,
  the request has no counts and the screen shows "Not Reported" for it. New
  replies are never estimated.

**Old replies only:** replies saved before durable usage records existed have
no stored counts. For those, the app estimates input and output with a
generic tokenizer (CL100K) and marks the record as estimated. These records
have no prices and no cache information.

## 2. Prices

**Rule (owner decision, October 2026): always use the most accurate source
available.** In order:

1. the actual charge the service reports for the request;
2. the service's own published price list;
3. OpenRouter's price for that service's model;
4. nothing. The value shows "Not Reported"; a price is never guessed.

Prices are fetched in the background when a request starts. When the request
finishes, they are frozen into that request's record. A price change later
never changes an old record.

Where prices come from depends on the connection:

| Connection | Price source |
| --- | --- |
| OpenRouter | OpenRouter's provider list for that model (the endpoint list for `{model}`). The app uses the price of the provider that actually served the reply: input, output, cached and cache-write prices. |
| OpenAI direct (`api.openai.com`) | OpenRouter's **public** model list, `https://openrouter.ai/api/v1/models`, entries starting `openai/`. |
| Anthropic direct (`api.anthropic.com`) | Same public list, entries starting `anthropic/`. |
| xAI / Grok direct (`api.x.ai`) | xAI's own price list (`/language-models`, fetched with the user's xAI key). If that fails or has no match, the public OpenRouter list, entries starting `x-ai/`. |
| Any other service (for example Venice, NanoGPT) | That service's own model list, read in any of the common price layouts (see "Other services' own price lists" below). Otherwise no prices. |

### Why OpenAI and Anthropic use OpenRouter's list

The model lists from OpenAI and Anthropic give model names and capabilities
but no prices. OpenRouter publishes current prices for their models in a list
anyone can download. Owner decision, October 2026.

### xAI's own price list

xAI publishes prices in `/language-models`. The fields used are
`prompt_text_token_price`, `cached_prompt_text_token_price` and
`completion_text_token_price`. xAI states these in US cents per 100 million
tokens, so the app divides by 10,000,000,000 to get dollars per token. A model
is matched by its `id` or any of its `aliases`. This list is fetched on every
request and not kept.

xAI also charges a higher rate for very long prompts on some models; the
price list gives only the base rate. The real charge (section 3) covers this
for the Total.

The built-in OpenAI price table that existed before has been removed. Its
values were ten times too high.

### How the public list is used

- **No key is sent.** The public list is downloaded without credentials. The
  user's OpenAI, Anthropic or xAI key never goes to OpenRouter.
- **Saved for six hours.** The downloaded list is kept in memory for six hours,
  then downloaded again. It is not saved to disk.
- **Matching model names.** The services report names like
  `claude-sonnet-4-5-20250929` or `gpt-4o-2024-08-06`, while OpenRouter uses
  names like `claude-sonnet-4.5`. The app tries, in order:
  1. the exact name (dots and dashes treated as the same);
  2. the name without `-latest`;
  3. the name without a date ending (`-20250929`, `-2024-08-06`, or a
     month-day ending such as `-0709`);
  4. for xAI only, the name without `-reasoning` or `-non-reasoning`.

  An exact dated entry on OpenRouter always wins over the undated one.
- **No match means no price.** If no entry matches, the request has no price
  and its costs show "Not Reported". The app never guesses a price.
- **Entries ignored:** variants with a colon in their name (such as `:free`),
  and negative prices (OpenRouter's marker for variable pricing).

### Other services' own price lists

The app does not need code for each service. It reads `/models` (and, if
that has no prices, `/models?detailed=true`, which NanoGPT requires) and
understands these layouts:

| Layout | Used by | Fields | Unit |
| --- | --- | --- | --- |
| `pricing` | OpenRouter-compatible services, NanoGPT | `prompt` / `input`, `completion` / `output`, `input_cache_read` / `cached_prompt` / `cache_read`, `input_cache_write` / `cache_write` | The list's `unit` field when present (`per_million_tokens`, per thousand, per token). Without one, see below. |
| `model_spec.pricing` | Venice | `input.usd`, `output.usd`; cache prices from `cache_input` / `cache_read` / `cache_write` if present | Dollars per million tokens |

Rules that apply to every list:

- **Model matching:** exact name, ignoring upper and lower case. If there is
  no exact match, the name after the last `/` is compared, so
  `gpt-4o-mini` finds `openai/gpt-4o-mini`. This is used only if exactly one
  entry matches.
- **Missing unit:** a price above $0.001 per token ($1,000 per million) is
  impossible for text, so such a list is read as dollars per million tokens.
  Otherwise it is read as dollars per token.
- **Safety check:** after conversion, any price above $0.001 per token, any
  negative price, or a currency other than US dollars rejects the whole
  entry. The cost then shows "Not Reported" rather than a wrong number.
- **Unit correction from the real charge:** a list's unit can still be wrong
  or mislabelled. When the service also reports its real charge, the app
  compares it with the cost calculated from the list. If the two differ by a
  factor of about 1,000 or 1,000,000, the list was in the wrong unit, and the
  prices are corrected by that factor before being saved. Smaller differences
  (discounts, fees) are never corrected this way. This applies only to
  services' own lists, never to OpenRouter's or xAI's prices.
- **Not verified live:** Venice's cache-price field names were not confirmed.
  If Venice uses other names, its cached costs show "Not Reported" when
  cached tokens exist.

### Timing limits

- A normal finished reply waits up to 2 seconds for the price list. If the
  reported model differs from the requested one, it fetches prices for the
  reported model, again waiting up to 2 seconds.
- A stopped or failed request waits only 0.5 seconds for prices.
- If prices are not ready in time, that request is saved without prices.

## 3. Cost calculation per request

### When the service reports the actual charge

This currently applies to:

- **Any service** that puts `cost`, `total_cost` or `cost_usd` in its usage
  report, in dollars. This includes OpenRouter.
- **xAI**, through `usage.cost_in_usd_ticks`. There are 10,000,000,000 ticks
  per dollar.
- **NanoGPT**, through its `x_nanogpt_pricing.cost` receipt, used when its
  `currency` is USD.

- **Total** is the reported charge, exactly.
- **Cached and Output costs** use the service's own breakdown when it sends
  one. Otherwise they are calculated from tokens × the saved prices.
- **Input cost** is always calculated from tokens × the saved prices.
- **The rows may not add up to the Total.** The Total is the real bill, while
  the rows come from the price list. Discounts, cache-write charges, extra
  fees and provider differences can make them differ.

### When the service does not report a charge

This applies to OpenAI and Anthropic direct, and any service that sends no
charge.

- **Input cost:** (input − cached − cache writes) × input price, plus
  cache writes × cache-write price.
- **Cached cost:** cached tokens × cached price. Zero cached tokens cost $0,
  even if no cached price is published.
- **Output cost:** output tokens × output price.
- **Total:** input cost + cached cost + output cost. If the service reported
  no cache information, the Total is all input × input price + output cost.
- **Missing pieces:** if a needed price is missing, that piece is unknown,
  and the Total is unknown too.

## 4. What the screen adds up

The screen opens from the chat menu (**Usage & Cost**). It is built from the
saved request records at the moment it opens.

- **Grouping:** records are grouped by model, then by provider. Upper and
  lower case are ignored.
- **Conversation Total:** the sum of every request's Total.
- **Model Total / Provider Total:** the sum for that model or provider.
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
partial sum is never shown as if it were complete. In particular:

- **Input, Cached and Cache Hit Rate** need the service to report cached
  input. A request without cache information makes them "Not Reported" for
  its whole group.
- **Costs** need saved prices, or a reported charge for the Total.

## 5. Which requests are counted

- **Every finished request is counted.** This includes tool-call requests
  that never produce visible text; those are stored on the user's message.
- **Stopped or failed requests are counted only when the service actually
  reported usage or a charge.** They are never estimated. The record is
  stored on the latest AI reply, or the latest user message if there is no
  reply. If that reply has several versions, the record is also written into
  the version the conversation continues from, so it is counted and kept
  when another version is shown.
- **Regenerated replies:** every version's requests are counted, not just the
  version on screen.

Usage records are saved inside the chat's messages, in the
`tokenUsageRecords` field of each message and of each reply version.

## 6. Known gaps (October 2026)

These are known and not yet decided or fixed.

1. **Rows vs. Total:** with a reported charge, Input + Cached + Output may not
   equal the Total (see section 3).
2. **Reasoning without a reported total:** if a service reports reasoning
   separately but sends no total, the app cannot tell whether reasoning is
   already in the output count, so it adds nothing.
3. **Old and estimated records:** these have no cache information, so Input,
   Cached and Cache Hit Rate show "Not Reported" for any group containing
   them. The screen does not mark estimated counts as estimated.
4. **Backups:** not checked whether backup and restore carry the usage
   records.
5. **Subscription services** (such as OpenCode Go): what the screen should
   show for a flat monthly plan is not decided.
6. **Unrecognized layouts:** a service whose price list uses none of the
   layouts above, and reports no charge, shows "Not Reported" costs. Its
   token counts still work.

## Where the code lives

| Part | File |
| --- | --- |
| Reasoning counted as output | `app/src/main/java/org/teslasoft/assistant/usage/ProviderUsageAttempt.kt` (`outputIncludingReasoning`) |
| Reading usage from the reply stream | `app/src/main/java/org/teslasoft/assistant/providers/ReportedProviderParser.kt` (`RawSseInspector`) |
| Per-request capture | `app/src/main/java/org/teslasoft/assistant/usage/ProviderUsageAttempt.kt` |
| Price fetching and name matching | `app/src/main/java/org/teslasoft/assistant/usage/TokenPricingCatalog.kt` (`TokenPricingCatalogClient`, `FirstPartyPricing`, including `matchXai`; `GenericPricing` for other services) |
| Cost math, grouping, "Not Reported" formatting | `app/src/main/java/org/teslasoft/assistant/usage/TokenUsageAccounting.kt` |
| When records are created and attached | `app/src/main/java/org/teslasoft/assistant/ui/activities/ChatActivity.kt` (`completePendingUsageRecord`, `completeTerminalUsageRecord`, `attachUsageRecords`, `openUsageAndCost`) |
| The screen | `app/src/main/java/org/teslasoft/assistant/ui/activities/TokenPricingDetailsActivity.kt` |
| Tests | `app/src/test/java/org/teslasoft/assistant/usage/` |
