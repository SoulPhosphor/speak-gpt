# Memory Retrieval and Analysis UI Copy

**2026-08-04**

This focused specification accompanies `external_memory_analysis_counterplan.md` and `memory_controls_and_pending_ui_copy.md`. It supplies the exact user-facing wording and behavior contract for live Associative Memory retrieval controls and analysis chunk choices. Where these controls are implemented, use this wording rather than asking the owner to decide it again.

## 1. Memory Retrieval

**Section Title:** `Memory Retrieval`

These controls limit only how much approved **Associative Memory** may be included in one live AI request. They do not limit how many memories may be stored on the device.

The Associative Memory retrieval budget is separate from:

- fixed app, system, and developer instructions;
- fixed companion identity and companion-profile instructions;
- Model Rules;
- Lorebooks;
- roleplay cards and other existing roleplay context systems.

Do not combine those systems into one shared budget as part of the memory-system work. Lorebooks and roleplay cards retain their existing independent priority and budget behavior. When applicable roleplay context needs prompt space, it takes priority over filling the separate Associative Memory allowance.

### Use Model-Aware Limits

**Toggle Label:** `Use Model-Aware Limits`

**Subtext:**

> Automatically reduce memory context only when a verified model or provider context limit is available. When turned off, your selected limits are always used.

**Recommended Default:** On.

When On:

- the user's selected memory count and token limits remain the preferred maximums;
- the app may reduce them only when a verified model, provider, endpoint-profile, or manual context limit proves the assembled request cannot safely fit;
- the app must show the reported or manual context limit and any reduction it applies;
- missing or unknown context information does not authorize an invented fallback for live memory retrieval.

When Off:

- the app uses the user's selected limits;
- the app does not proactively reduce them from model or provider metadata;
- a request may fail if the selected limits exceed the real context window, but this remains the user's choice.

### Maximum Memories Per Response

**Field Label:** `Maximum Memories Per Response`

**Subtext:**

> The maximum number of relevant memories that can be included with one AI response.

This is a maximum, not a required count. Memories that do not pass relevance and eligibility rules are not added merely to fill the selected number.

### Maximum Memory Context

**Field Label:** `Maximum Memory Context`

**Subtext:**

> The maximum amount of retrieved memory that can be included with one AI response. Smaller values leave more room for the current conversation.

The app stops adding retrieved Associative Memory when either the selected memory-count maximum or selected memory-token maximum is reached, whichever occurs first.

This setting does not limit stored memory on the device and does not control Lorebook, roleplay-card, Model Rule, or fixed companion-instruction capacity.

### Memory Priority

**Field Label:** `Memory Priority`

**Options:**

- `Balanced`
- `General Memories First`
- `Companion Memories First`

**Subtext:**

> Controls which memory group receives priority when both General and Companion memories are relevant.

Behavior:

- `Balanced` considers both enabled pools together;
- `General Memories First` prefers General memories when similarly relevant results compete for limited Associative Memory space;
- `Companion Memories First` prefers the current companion's memories when similarly relevant results compete for limited Associative Memory space;
- semantic relevance remains primary;
- priority never makes an irrelevant memory eligible;
- unused space may be filled by any other enabled and relevant Associative Memory pool.

`Memory Priority` is the only General-versus-Companion competition mechanism in the approved design. Do not add a separate protected-companion-capacity setting, reserve, percentage, quota, or balance slider.

### Memory Match Strictness

**Field Label:** `Memory Match Strictness`

**Options:**

- `Strict`
- `Balanced`
- `Broad`

**Subtext:**

> Controls how closely a memory must match the current conversation before it can be used.

Behavior:

- `Strict` requires a stronger semantic match;
- `Balanced` uses the normal relevance threshold;
- `Broad` permits looser associations and may include more marginally related memories;
- the ordinary UI does not expose an unexplained raw decimal threshold.

### Current Retrieval Limits

**Section Title:** `Current Retrieval Limits`

This is a read-only status area.

When a model or provider reports a verified context limit:

> **Model Context:** {token count} Tokens (Reported)

When a user-entered override is active:

> **Model Context:** {token count} Tokens (Manual)

When no context limit is available:

> **Model Context:** Unknown

When context is unknown, the app uses the user's selected memory limits. Do not display text saying that a conservative fallback will be used for live memory retrieval.

When model-aware limits reduce the selected values, show the selected and effective values and a plain explanation that the reduction preserves room for the rest of the assembled request.

### Context Window Override

This control belongs with the relevant model or endpoint profile and may also be linked from Memory Retrieval.

**Field Label:** `Context Window Override`

**Subtext:**

> Override the reported context window for this model or provider. Leave blank to use the reported value when available.

Behavior:

- accepts a user-entered token count;
- a manual value overrides reported metadata until cleared;
- leaving the field blank uses verified reported metadata when available;
- leaving it blank with no reported metadata leaves the model context Unknown;
- the app does not invent a context-window value.

## 2. Retrieval Behavior

For each live response:

1. preserve the existing independent Lorebook, roleplay-card, fixed instruction, companion-profile, and Model Rule behavior;
2. determine which Associative Memory pools the conversation permits;
3. search only eligible General, Companion, and Roleplay scope/target contexts;
4. reject memories below the selected Match Strictness;
5. rank primarily by semantic relevance;
6. apply optional importance only as the approved secondary signal;
7. apply Memory Priority when relevant General and Companion results compete;
8. add memories until the Associative Memory count or token maximum is reached;
9. when Use Model-Aware Limits is On, reduce only from verified or manual context information;
10. keep fixed app safety, developer instructions, fixed companion identity, Model Rules, and applicable roleplay context above retrieved Associative Memory context;
11. insert retrieved Associative Memory before the conversation transcript.

No retrieval setting limits how many memories are stored on the phone.

## 3. Conversation Amount Per Request

These controls govern archiver input chunks, not live memory retrieval.

**Field Label:** `Conversation Amount Per Request`

**Options:**

- `Auto`
- `Small · About 4,000 Tokens`
- `Standard · About 8,000 Tokens`
- `Large · About 16,000 Tokens`
- `Custom`

**Subtext:**

> Controls how much conversation text is sent in each AI request. Smaller amounts work with more models and providers but require more requests.

Behavior:

- Small targets approximately 4,000 transcript tokens;
- Standard targets approximately 8,000 transcript tokens;
- Large targets approximately 16,000 transcript tokens;
- Custom uses a user-entered transcript-token target;
- Auto chooses from verified model, provider, endpoint-profile, or manual limits when available;
- when Auto has no verified limit, the Revision 26 repair contract uses the ordinary Standard-sized target rather than inventing a giant-context strategy;
- the selected value is a transcript target, not the total request size;
- the app still reserves space for prompts, bounded existing-memory context, structured-output or JSON overhead, Analysis Note, expected output, and a safety margin;
- the app may shrink below the selected target when a verified limit requires it;
- when no verified limit exists, the selected Small, Standard, Large, or Custom target remains in effect rather than being replaced by an invented hidden value;
- whole messages are preserved whenever possible;
- the existing 200,000-character ceiling is not retained as a fallback.

These are the approved initial user-facing choices and values. Later real on-device use may justify adjustments; a standalone multi-model evaluation harness is not required before the Memory Assistant can work.

## 4. Design Rule

Model and provider metadata may assist the user, but it does not replace user control.

- unknown metadata is shown as Unknown;
- verified metadata may reduce live memory limits only while Use Model-Aware Limits is On;
- manual context-window overrides are respected;
- no hidden fallback is presented as knowledge;
- selected and effective limits remain visible when they differ;
- do not add separate protected-capacity, percentage-balance, subtype-budget, dynamic preset, or automatic tuning controls unless the owner explicitly approves them later;
- do not redesign Lorebook, roleplay-card, fixed companion-instruction, or Model Rule budgeting as part of Associative Memory retrieval.
