# Phase 2 — Model Rule path audit (item 7)

Canonical plan: `external_memory_analysis_counterplan.md`, Phase 2 item 7.

This is an audit deliverable. Phase 2 does **not** redesign Model Rules. The
existing approved behavior is traced below and preserved. The visible review
contract is fully defined, so no product question was raised.

## What a Model Rule is

A user-written patch for a specific AI model's habits (owner_approved_rules §11,
Revision 5). The MODEL STRING is the identity — no profiles/groups. A Model Rule
is **not** an Associative Memory: it is a separate output and storage stream,
carries **no Memory Type** and **no importance rating**, and never passes through
Associative-Memory filing.

## Storage

- Table `model_rules` (`MemoryStore`), row shape `ModelRuleRecord`
  (`MemoryData.kt`): `rule_id`, `text`, `model_strings_json`, `status`
  (`draft` | `active`), `source_model_string`, timestamps.
- Organizing tags: `model_rule_tags` + `model_rule_tag_links`
  (`ModelRuleTagRecord`, `ModelRuleTagLink`) — a separate pool that never decides
  injection.
- Store methods: `getModelRules(status?)`, `getModelRule`, `upsertModelRule`,
  `deleteModelRule`, `acceptModelRule` (draft → active), `countModelRuleDrafts`,
  `getActiveModelRulesForModel`.

## Filing (the API Memory Assistant / Archivist)

`Archivist.fileRuleDrafts` files rule suggestions as `status = 'draft'` with an
empty `model_strings_json` and the chat's model string as `source_model_string`.
Dedup is by trimmed rule text against existing rules. This path is **separate**
from `Archivist.fileMemoryDrafts` (the Associative-Memory path) — a rule draft
never touches memory scope, Type, importance, targets, or the Pending memory
filer.

## Pending / review presentation

- `ModelRulesActivity` lists rules; a draft shows the "Draft" badge, a pending
  count (`countModelRuleDrafts`) drives the banner/pointer, and a status filter
  (all / active / draft) narrows the view.
- Injection matching for active rules: `ModelRuleMatcher` (model-string
  contains, provider prefix ignored).

## Approval and application

- Approval: opening a draft in `ModelRuleEditorActivity` shows an **Accept**
  button. Accept saves with `status = 'active'` (the user assigns the model
  strings on accept, seeded by the source model string). Plain **Save** keeps a
  draft a draft; **Delete** discards it. A hand-written rule is active
  immediately.
- Application: `ChatActivity` calls `getActiveModelRulesForModel(model)` →
  `ModelRuleMatcher` → injected into the prompt. Only `status = 'active'` rules
  apply. The on/off decision is a global default plus a per-chat toggle, never in
  the store.

## Phase 2 impact

None to the Model Rule path itself. Phase 2 adds a separate, validated
`ModelRuleCandidate` (`MemoryCandidate.kt`) that is deliberately **not** a
`MemoryCandidate`, has no Type and no importance field, and is produced by
`MemoryCandidateValidator.validateModelRule`. This keeps the three candidate
streams (General Associative, Companion Associative, Model Rule) distinct and
guarantees a Model Rule can never acquire memory Type/importance by passing
through Associative-Memory code. The filing, review, approval, and application
code above is unchanged.
