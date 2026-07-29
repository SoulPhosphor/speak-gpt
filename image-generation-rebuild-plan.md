# IMAGE GENERATION REBUILD

## Repository-Grounded Implementation Plan for SoulPhosphor/speak-gpt

**Status:** Planning only. No application code has been changed.  
**Repository:** `SoulPhosphor/speak-gpt`  
**Baseline:** `main` at commit `16592e7d0d0798eaf85c2d83fb3e020aaad099bb`  
**Prepared:** 2026-07-28  
**Revised:** 2026-07-29 — settings placement moved to the existing Images row,
tile and Slash commands removal, full Function Calling removal, screen layout
order set by the owner. Code claims re-verified against `main` at
`5c6fb13`.

## 1. Goal

Rebuild image generation so that:

1. `/imagine <prompt>` remains a direct, reliable way for the user to create
   an image.
2. The model in the current conversation can create or improve an image prompt
   and ask the app to run the configured image generator.
3. The conversation model and image-generation model can use completely
   different API endpoints and providers.
4. Neither conversation models nor image-generation models are restricted by
   hard-coded model names.
5. A provider or model that does not support tool calling does not break normal
   chat and does not prevent `/imagine` from working.
6. Image generation has clear progress, cancellation, error, persistence, and
   cost-safety behavior.

This plan is only for creating new images. Image editing, variations,
reference-image generation, and multiple images per request are intentionally
left for later work.

## 2. The Two User Paths

Both paths must use the same underlying image-generation service. They differ
only in who writes the generator prompt.

### Path 1: Direct `/imagine`

Example:

`/imagine a fox sleeping beneath glowing mushrooms`

Behavior:

1. The app recognizes `/imagine` only when it appears at the beginning of the
   raw user message and is followed by a prompt.
2. The app sends the user's text after `/imagine` directly to the configured
   image generator.
3. The conversation model is not called to rewrite or approve the prompt.
4. This path does not require the conversation model to support tools.
5. Because the command itself is an explicit request, it does not require an
   additional confirmation.

The existing command must remain enabled by default. Its setting stays
independent from model-requested image generation.

### Path 2: The Conversation Model Requests an Image

Example:

The user says:

> Make an image of what this scene looks like from your point of view.

Behavior:

1. The normal request goes to the model already selected for that chat, with
   the same companion, system instructions, memory, lore, attachments, summary,
   and conversation history it would normally receive.
2. The request includes an app-provided `create_image` tool.
3. The conversation model decides whether to call the tool and writes the
   image-generator prompt itself.
4. The app receives the tool request and runs the separately configured image
   generator.
5. The generated image appears in the conversation.
6. The app returns success or failure to the same conversation model so it can
   finish its response naturally.

This is the route that preserves the fun of letting a companion interpret a
scene or invent the visual prompt. There must be no hidden OpenAI model deciding
on its behalf.

## 3. Required Separation of Roles

The app must treat these as two independent roles:

| Role | Responsibility | Example |
| --- | --- | --- |
| Conversation model | Talks to the user, understands the conversation, and writes the image prompt | Claude, Gemini, Llama, Mistral, an OpenAI model, or a local model |
| Image generator | Turns the prompt into image bytes | An OpenAI image model, an OpenRouter image model, or another supported image API |

A chat using one provider must be able to send image requests to a generator
using another provider. The image generator must not automatically inherit the
active chat endpoint.

## 4. Current Problems That Must Be Removed

The current implementation is spread through `ChatActivity`, `Preferences`,
`SettingsActivity`, `SelectImageModelFragment`, and their layouts.

### 4.1 A hidden hard-coded decision model

When the old Function Calling setting is enabled, the app sends a separate
non-streaming request to `gpt-4o`. That request receives only the newest user
text and is asked whether to call `generateImage` or `searchAtInternet`.

Consequences:

- The selected conversation model is not making the decision.
- The hidden request does not receive the full companion or conversation
  context.
- A provider without a model named `gpt-4o` fails even if its selected model
  supports tools.
- A normal non-tool reply can require the hidden request followed by another
  request to the actual conversation model.

This routing request must be deleted, not renamed to a newer hard-coded model.

### 4.2 Fixed image-model radio buttons

The image-model dialog recognizes only:

- `dall-e-2`
- `dall-e-3`
- `gpt-image-1`
- `gpt-image-1-mini`
- `gpt-image-1.5`

The model list, selection comparisons, and labels are hard-coded. They must be
replaced with provider-backed discovery plus manual model-ID entry.

### 4.3 Model-name-based API behavior

The generator checks whether the selected model name contains
`gpt-image-`. That name check chooses between two different clients and two
different response assumptions.

API behavior must come from the configured generator API format, not from a
substring in a model name.

### 4.4 Generator tied to the chat endpoint

The existing generator uses the current chat's endpoint, key, and base URL.
This prevents a clean configuration such as a Claude conversation endpoint
with a separate OpenAI or OpenRouter generator.

### 4.5 Forced and outdated output assumptions

The existing paths assume:

- GPT Image means Base64 output.
- Older DALL-E means URL output.
- A GPT Image request is always `1024x1024`.
- Quality is always `AUTO`.
- Generated output is PNG.

The rebuilt path must accept either Base64 data or a temporary URL, validate
the actual response, detect the real file type, and omit optional parameters
when the user selected Automatic.

### 4.6 Incorrect command detection

The current code searches for `/imagine` anywhere inside the already-modified
message and then slices at a fixed character position.

The rebuilt parser must inspect the raw user input before prefixes or end
separators are added. Mentioning the command in ordinary conversation must not
accidentally generate an image.

### 4.7 New-chat setting loss

New-chat creation copies the resolution and several older DALL-E settings but
does not appear to copy `imageModel`. Migration and new-chat tests must cover
all replacement image-generation settings.

## 5. User Interface

The settings screen already contains an **Images** row (subtitle "Image
generation settings.") above the two small Image model and Resolution tiles.
Today that row is decorative: it has no tap action wired to it. The rebuild
wires it up instead of adding a new row.

### Main settings screen changes

1. The existing **Images** row opens the new **Image Generation** settings
   screen.
2. The two small tiles beneath it — **Image model** and **Resolution** — are
   removed, together with their fixed-list selection dialogs.
3. The standalone **Slash commands** toggle tile is removed. Its only real
   function is enabling `/imagine`; that control moves into the Image
   Generation screen.
4. The **Function calling** tile is removed entirely (section 15).

### Image Generation screen

Follow the interaction pattern of the Summarizer settings screen: a dedicated
screen whose rows save as they are changed, with an endpoint row that opens
the existing endpoint list picker and a model row that opens the shared
searchable model picker fed by the chosen endpoint.

Rows, top to bottom:

1. **Let the AI create images** (toggle, at the top)
   - Makes `create_image` available to the current conversation model.
   - This setting is independent from `/imagine`.

2. **Ask before creating** (toggle)
   - Visible only while **Let the AI create images** is enabled.
   - Enabled by default.
   - Protects against unexpected image-generation charges from an
     over-enthusiastic model.

3. **Image service**
   - Selects the saved API endpoint used for image generation, using the
     existing endpoint picker.
   - Subtitle shows the endpoint's friendly label.
   - It may differ from the current conversation endpoint.

4. **Image model**
   - Opens the shared searchable model picker for the selected image service
     (the same picker family the chat and the Summarizer use).
   - If the provider exposes image-output capability information, show only
     image-generating models.
   - Otherwise show the provider's available models.
   - Manual model-ID entry must be available. The shared picker does not
     currently offer manual entry, so this rebuild adds it.
   - No model name is rejected merely because it is unfamiliar to the app.

5. **Default shape**
   - Automatic
   - Square
   - Portrait
   - Landscape

   Automatic is the default. Exact dimensions are chosen by the provider
   adapter. The app must not expose choices the selected provider explicitly
   says it cannot accept.

   This is deliberately the only image-output setting in the first release.
   Quality tiers, exact pixel sizes, image counts, and output formats stay on
   provider defaults (section 11) until their cost implications are designed.

6. **Enable `/imagine`**
   - Preserves the existing direct command setting.
   - Enabled by default.

### Confirmation experience

When the conversation model requests an image and confirmation is enabled,
show an inline card:

> **AI wants to create an image**

Actions:

- **Create**
- **Cancel**
- **View prompt**

The prompt remains collapsed initially so an intended surprise is not spoiled.
`/imagine` bypasses this card because the user already issued a direct command.

### Progress experience

After approval, place an inline assistant image bubble in a
**Creating image…** state with a visible **Cancel** action.

Requirements:

- Do not rely on the screen's global spinner as the only status.
- Do not save an unexplained empty assistant message.
- Leaving and reopening the chat must restore the visible job state.
- Rotation or activity recreation must not lose the job or generate a second
  image.
- The turn must end in exactly one of: Complete, Failed, or Cancelled.

### Completed image actions

A completed generated-image bubble should support:

- Open
- Save or share through the existing Android flow
- Copy prompt
- An accessible content description

The image should not automatically be sent back to the conversation model on
every future turn. That would create avoidable vision-token cost. The stored
prompt and description can preserve text-level conversational continuity.

## 6. Tool Contract

Expose one narrowly defined client-side tool:

`create_image`

Suggested inputs:

| Field | Required | Purpose |
| --- | --- | --- |
| `prompt` | Yes | Detailed text sent to the image generator |
| `description` | Yes | Short plain-language description for the image bubble and accessibility |
| `shape` | No | `automatic`, `square`, `portrait`, or `landscape` |

Rules:

- The generator creates exactly one image per tool call.
- Allow at most one successful image-generation tool call per user turn.
- The conversation model cannot choose the generator endpoint, generator
  model, number of images, quality tier, or another cost-affecting provider
  setting.
- Those choices remain under user control.
- Reject an empty prompt, invalid JSON, unknown fields that would change
  behavior, or an excessive prompt length with a clean tool error.
- Never execute an unknown tool name.

The tool description should explain that it creates a visible image in the
current conversation and that the app may ask the user to approve it.

## 7. Normal Conversation Request Flow

The image tool must be added to the same request the app already sends to the
selected conversation model. There must not be a preliminary routing request.

### No tool call

If the model returns ordinary text, stream and display that response exactly as
normal. There is only one model request.

### Image tool call

1. Accumulate streamed tool-call fragments until the tool name and JSON
   arguments are complete. Providers differ in how they stream tool calls, so
   the assembler must also accept a complete non-streamed tool call, and must
   treat a stream that ends mid-tool-call as a clean failure rather than a
   hang.
2. Validate the request.
3. Ask for confirmation when required.
4. Run the configured image generator.
5. Save and display the image.
6. Return a tool result to the same conversation model:
   - success, generated-image identity, and the stored description; or
   - a concise cancellation or failure result.
7. Let the conversation model produce its final text response.

The second conversation-model request occurs only after an actual tool call.
Ordinary chat must never pay for a hidden decision request.

### The app's two regular request builders

The repository currently has a frozen typed-send path and a legacy
voice/retry/regenerate path. Both must use the same tool-availability decision
and tool definition. Neither path may silently omit the image tool.

The existing conversation summarizer remains excluded from image-generation
tool-loop internals unless its approved rules are separately changed. The
visible user request and final assistant result remain normal conversation
content.

## 8. Conversation-Model Compatibility

Do not maintain a list of models allowed to request images.

Track tool support by the exact endpoint and conversation-model ID:

- **Unknown:** Try sending the tool when enabled.
- **Supported:** The endpoint accepted a request containing tools.
- **Unsupported:** The provider returned a clear tools-not-supported error.

This can use the same general three-state pattern already established for image
input capability, but it is a separate capability.

If tools are explicitly unsupported:

1. Mark that endpoint/model combination unsupported.
2. Retry the rejected chat request once without tools when it is safe to do so.
3. Continue normal chat.
4. Show a concise one-time message:

   > This model cannot request image creation. You can still use `/imagine`.

5. Provide a **Try again** or reset action in advanced settings so a provider
   upgrade is not treated as permanent.

Do not classify a model as unsupported because it chose not to call the tool.
A clean text response proves that the provider accepted the tool-bearing
request, not that the model will use the tool every time.

## 9. Image-Generator API Layer

Create one provider-neutral image-generation coordinator outside
`ChatActivity`.

Its responsibilities:

- Load the selected generator endpoint and model.
- Apply the endpoint's existing authentication mode.
- Respect the endpoint's connection and response timeouts.
- Build the provider-specific request.
- Return one normalized result.
- Handle cancellation.
- Classify errors without exposing credentials.

Suggested normalized request:

- prompt
- shape
- endpoint ID
- generator model ID
- one image

Suggested normalized result:

- bytes
- MIME type
- width
- height
- provider request ID when available
- provider-reported usage or cost when available

### Initial provider adapters

1. **OpenAI-compatible Image API**
   - Uses the endpoint's configured image-generation path, defaulting to the
     standard generations path.
   - Accepts Base64 or URL response data when supplied by a compatible
     provider.

2. **OpenRouter Image API**
   - OpenRouter has no separate images endpoint: image generation goes
     through its normal chat endpoint with an image-output request flag, and
     image models are discovered by filtering the model catalog by output
     capability. The adapter must speak that mechanism, not an OpenAI-style
     generations path.
   - Maps normalized shape and output choices to the provider's supported
     request fields.

3. **Custom**
   - Allows an advanced exact image path and one of the supported response
     formats.
   - It does not promise compatibility with an unrelated proprietary API
     merely because a URL was entered.

Provider adapters must be selected from saved endpoint configuration or
successful capability discovery. They must not be selected from the image
model's name.

The project already includes OkHttp. A single controlled HTTP layer is
preferable to keeping one SDK for one model-name family and a different SDK for
another.

## 10. Generator Model Discovery

When the Image Model row is opened:

1. Ask the selected generator endpoint for its image-model catalog when it
   provides one.
2. Otherwise ask for its ordinary model catalog.
3. Show a searchable list.
4. Always provide manual model-ID entry.
5. Preserve the user's current selection even when it is temporarily absent
   from the catalog.

Provider capability metadata may improve the list, but it must never become a
hard-coded name filter.

## 11. Request Parameters and Cost Control

Initial release behavior:

- Generate one image.
- Default shape is Automatic.
- Default quality and output format come from the provider.
- Omit optional API parameters when set to Automatic rather than sending values
  an endpoint may reject.
- Do not let the conversation model raise quality, request many images, or
  select an expensive generator.
- Preserve the configured image-generator response timeout because image
  generation can take substantially longer than ordinary chat.

Quality, exact dimensions, background, compression, seed, and reference images
can be added later under Advanced settings after their provider mappings and
cost implications are designed.

## 12. Storage and Message Representation

Keep generated image bytes in the existing app-owned image storage area, but
stop treating `~file:<hash>` as the complete data model.

Store structured metadata with the generated assistant message:

- stable generated-image ID
- file hash
- MIME type
- width and height
- generator endpoint ID
- generator model ID
- prompt
- accessible description
- created timestamp
- status: generating, complete, failed, or cancelled
- sanitized failure code when applicable

Never store an API key, authorization header, or signed temporary download URL
in chat history.

Legacy `~file:` messages must continue rendering. New generated images should
use the structured representation, with a compatibility projection only where
old adapter code still requires it during migration.

Deleting a generated-image message should delete its private image file when
no other app record references it.

## 13. Error Behavior

Errors must identify which side failed:

- The conversation model cannot use tools.
- No image generator is configured.
- The generator model was rejected.
- The generator endpoint could not be reached.
- Authentication failed.
- The provider rejected the prompt.
- Generation timed out.
- The response did not contain a usable image.
- The image download was too large or invalid.
- The user cancelled.

Normal users receive a concise message and a retry/configure action where
appropriate. Detailed stack traces remain limited to the existing debugging or
error-log surfaces.

If no generator is configured:

- Natural-language tool use is not offered to the conversation model.
- `/imagine` shows a persistent dialog explaining that an image service and
  model must be selected, with a **Configure** action.
- The message must not claim that OpenAI is required.

## 14. Settings Migration

Migrate without deleting the old values until the rebuilt feature is verified.

For each existing chat and the default settings:

1. Copy the current `imageModel` into the new generator-model field.
2. Copy the current `resolution` into the closest new shape/default-size
   setting.
3. Use the chat's current API endpoint as the initial generator endpoint so
   existing behavior does not abruptly change.
4. Preserve `imagine_command`.
5. Migrate the old Function Calling state only into the new
   **Let the AI create images** setting where that preserves the user's current
   choice. Do not keep image creation dependent on the old generic toggle.
6. Ensure new chats copy every new default image-generation setting.

Only remove legacy fields after migration tests and at least one stable release
path are established.

## 15. Old Function Calling Is Removed Entirely

**Owner ruling, 2026-07-29:** the old Function Calling feature is removed
completely, not retained behind separate behavior. Everything the switch
controls today is part of the broken image router, so nothing of value is
lost:

- the hidden `gpt-4o` routing request;
- the hard-coded `generateImage` / `searchAtInternet` function map;
- the `searchAtInternet` search stub itself;
- the **Function calling** settings tile;
- the stored function-calling preference and its new-chat copy;
- the routing checks that divert function-calling chats away from the normal
  typed-send path and exclude them from summarizer transmission.

Image creation moves to the new normal-request tool coordinator described in
sections 6 and 7.

One behavior consequence must be covered by tests: chats that had Function
Calling enabled were excluded from summarizer transmission. After removal
they follow the normal summarizer rules like any other chat.

A future web-search feature, if ever wanted, starts from its own approved
plan. It must not dictate the architecture of image creation.

## 16. Ordered Implementation

Keep this as one numbered work order. Do not create overlapping lettered
phases.

1. Add regression tests that capture the current `/imagine`, image storage,
   new-chat copying, and regular chat request paths.
2. Add the new generator settings and backward-compatible migration.
3. Build the provider-neutral image request/result models and generator
   coordinator.
4. Add the OpenAI-compatible and OpenRouter adapters.
5. Build the Image Generation screen behind the existing Images row —
   endpoint selection, model discovery, search, manual entry, and the toggles
   in the approved order — and remove the Image model tile, the Resolution
   tile, their fixed-list dialogs, and the Slash commands tile.
6. Route `/imagine` through the new generator coordinator.
7. Add the `create_image` tool to both normal chat request builders.
8. Implement streamed tool-call assembly, validation, confirmation, execution,
   tool-result return, and final response.
9. Add persistent inline generation status, cancellation, and recovery after
   activity recreation.
10. Add endpoint/model tool-capability learning and text-only fallback.
11. Replace new generated-image markers with structured metadata while
    retaining legacy rendering.
12. Remove the whole Function Calling feature (section 15): the hard-coded
    `gpt-4o` router, the function map and search stub, the settings tile, and
    the stored preference — plus fixed image-model checks, duplicate image
    clients, and OpenAI-only error wording.
13. Run the complete acceptance matrix and repair any regressions before
    removing legacy settings.

## 17. Acceptance Criteria

The work is complete only when all of the following are true:

1. Searching the active image-generation path finds no hard-coded `gpt-4o`
   routing model.
2. No model name controls whether a chat model may receive `create_image`.
3. No image-model-name substring controls which image API client is used.
4. A tool-capable non-OpenAI conversation model can request an image.
5. The conversation model receives the full normal context before it writes the
   generator prompt.
6. A conversation endpoint and generator endpoint can be different.
7. A model not listed by the app can be manually selected as the generator.
8. `/imagine` works even when the conversation model does not support tools.
9. Mentioning `/imagine` in the middle of ordinary text does not generate an
   image.
10. Ordinary chat with image tools enabled makes only one conversation-model
    request unless the model actually calls the tool.
11. Tool rejection falls back to normal chat without losing or duplicating the
    user's message.
12. Confirmation preserves the optional surprise by keeping the prompt
    collapsed.
13. Cancellation, timeout, navigation away, rotation, and reopening produce one
    correct final state.
14. Base64 and URL image responses both work.
15. The stored file uses the real supported MIME type and extension.
16. Existing generated-image messages still render.
17. New chats inherit the configured generator endpoint, generator model,
    shape, `/imagine` state, and AI-image setting.
18. Existing image attachments, image-input capability checks, Reduce, and
    document behavior remain unchanged.
19. User-facing image errors do not claim that OpenAI is required.
20. No API key, auth header, or temporary signed image URL is persisted in chat
    history or logs.
21. The main settings screen no longer shows the Image model tile, the
    Resolution tile, the Slash commands tile, or the Function calling tile,
    and the Images row opens the Image Generation screen.
22. No function-calling preference, router, or search stub remains in the
    code, and a chat that previously had Function Calling enabled behaves as
    a normal chat, including normal summarizer transmission.

## 18. Required Test Matrix

At minimum, verify:

| Conversation side | Generator side | Expected result |
| --- | --- | --- |
| Tool-capable OpenAI-compatible model | OpenAI-compatible image endpoint | Natural request and `/imagine` work |
| Tool-capable non-OpenAI model through a compatible endpoint | Separate OpenAI-compatible image endpoint | The selected non-OpenAI model writes the prompt and the separate generator creates the image |
| Tool-capable model | OpenRouter image endpoint/model | Natural request and `/imagine` work |
| Tool-incapable model | Configured generator | Normal chat works; `/imagine` works; one-time tool notice appears |
| Local/custom chat endpoint | Separate cloud generator | No chat text or credentials are silently routed to the generator |
| Any chat model | Missing generator configuration | Normal chat works; `/imagine` offers Configure |

Also test alternate auth modes, provider errors, invalid JSON tool arguments,
multiple attempted tool calls, cancellation at each stage, response timeout,
oversized download, malformed Base64, missing files, chat deletion, backup and
restore, and process recreation.

## 19. Explicit Non-Goals

Do not add these during this rebuild:

- Image editing
- Inpainting
- Variations
- Reference-image generation
- Automatic re-sending of generated images on every chat turn
- Multiple generated images per request
- Video generation
- A web-search redesign
- A migration of the entire multi-provider chat app to one provider's
  proprietary conversational image API

The architecture should leave room for these later without making the first
release carry all of them.

## 20. Reference Notes

- OpenAI documents direct one-prompt generation through its Image API and
  conversational image generation through tool-using flows:
  <https://developers.openai.com/api/docs/guides/image-generation>
- OpenAI's function-calling documentation confirms that the application sends
  a tool definition, executes the model's tool request, returns the tool
  result, and then receives the model's final response:
  <https://developers.openai.com/api/docs/guides/function-calling>
- OpenRouter documents dedicated image-model discovery and image-generation
  endpoints, illustrating why image API behavior cannot safely be guessed from
  a model name:
  <https://openrouter.ai/docs/guides/overview/multimodal/image-generation>

