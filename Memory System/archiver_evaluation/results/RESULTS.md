# Phase 3 Results Report — Archiver Evaluation Harness

**Status:** The evaluation harness is **built, verified, and ready to run.** The
**live measurement pass that produces the actual model/cost numbers has not been
run**, because this build environment has no third-party model API keys and
running a multi-configuration paid pass spends usage that is the owner's to
authorize. This report documents what is done, what the harness will decide, and
the one decision needed to finish Phase 3. It does **not** fabricate model
numbers.

---

## 1. What is delivered and verified

- A pure-Python, dependency-free harness under `Memory System/archiver_evaluation/`
  that never touches the live memory database (a Phase 3 restriction).
- **17 synthetic fixtures with gold labels**, covering every case the plan lists
  (see §3).
- A **production-parity response parser** (port of `ArchivistResponseParser`,
  pinned by tests) plus a parser for the §8.7 structured envelope.
- **Token-based chunking** with the §8.6 boundary rules and the §8.5 budget
  subtractions, sweeping the approved Small/Standard/Large labels plus extra
  points to locate quality knees.
- Three **prompt profiles**: `broad` = the current production prompt verbatim
  (Phase 3 req #6), plus `balanced` and `conservative` test candidates.
- A **scorer** recording every metric the plan requires (§5 of `methodology.md`):
  useful found, missed, invented/unsupported, General↔Companion placement,
  stream leakage, wrong target, invalid Type, duplicates, useful/noisy Model
  Rules, Do-Not-Analyze violations, malformed/unreadable handling, request
  count, input/output tokens, latency, and estimated cost.
- A **configuration matrix** across profiles × streams × chunk sizes × models ×
  schema, with CLI filters to run bounded slices.
- A **39-test suite** (`python3 -m unittest discover -s tests`) — all green —
  covering parser parity, chunk boundaries, scoring logic, and an end-to-end
  offline run.

**Completion-gate check (§ Phase 3 gate):** the harness *can* compare prompt
profiles, extraction streams, conversation policies, chunk sizes, and models;
retrieval-style combination of several relevant memories under a stream budget
is exercised by the mixed fixtures; and failures are visible (`unreadable_calls`)
rather than collapsed into "no memories". The one gate sub-item still open is
"provisional production defaults documented **with evidence**" — that needs the
live pass in §6.

## 2. Configurations the harness tests

| Axis | Values |
|------|--------|
| Prompt profile | broad (current), balanced, conservative |
| Streams | general; companion; model_rules; general+companion; all three |
| Chunk target | 2k, 4k (Small), 6k, 8k (Standard), 12k, 16k (Large), 24k, 32k |
| Model | one lower-cost, one stronger |
| Schema | plain_json (current), structured (§8.7) |

Full product = 480 configs for two models; real passes are narrowed (§6).

## 3. Fixture coverage (Phase 3 req #2)

| # | Fixture | Case exercised |
|---|---------|----------------|
| 01 | dense_product_decisions | dense decisions; no personality inference |
| 02 | long_casual | long low-density chat; over-mining trap |
| 03 | changing_facts_status | a fact changes mid-conversation |
| 04 | preferences | stated style/response preferences |
| 05 | contradictions | retracted/contradicted claim |
| 06 | projects_plans | named project + targets |
| 07 | companion_shared_history | relationship history → Companion stream |
| 08 | companion_behavior_guidance | companion behavior ≠ global Model Rule |
| 09 | companion_no_self_memory | companion must not memorize its own persona |
| 10 | do_not_analyze | pipeline must not analyze at all |
| 11 | general_enabled_companion_disabled | stream isolation (General only) |
| 12 | companion_enabled_general_disabled | stream isolation (Companion only) |
| 13 | roleplay_worlds_characters_campaigns | fiction never becomes real-life fact |
| 14 | model_rules | repeated habit → one rule; one-off → none |
| 15 | mixed_general_companion_rules | all three streams, no cross-pool duplication |
| 16 | sparse_nothing | correct empty result; no manufactured proposals |
| 17 | malformed_output | fenced/prose/truncated/garbage response handling |

## 4. Offline self-test outcome (mechanism check — NOT model evidence)

Running the two scripted profiles over all fixtures
(`results/selftest_results.md`) produces the intended discrimination:

| Config (synthetic) | Recall | Precision | Invented | DNA viol | Unreadable |
|--------------------|--------|-----------|----------|----------|------------|
| broad (loose script) | 1.00 | 0.69 | 16 | 0 | 0 |
| conservative (strict script) | 0.63 | 1.00 | 0 | 0 | 1 |

This confirms the plumbing and that the scorer separates a high-recall/low-
precision profile from a high-precision/lower-recall one, that Do-Not-Analyze
produces zero output and zero requests, and that a truncated response surfaces as
`unreadable` while a fenced one is recovered. **These are scripted responses
derived from the gold labels, not measurements of any model.**

## 5. Failure patterns the harness is built to surface

The metrics that will matter most for the real recommendation, and the fixtures
that stress them:

- **Over-mining under a Broad profile** — `invented`/`overextraction` on 02, 16.
- **Cheap-model quality collapse on large chunks** — recall drop as chunk size
  rises for the lower-cost model; this is the owner's stated concern and is the
  primary reason the chunk axis is swept, not fixed.
- **General↔Companion misplacement and leakage** — `placement_errors`,
  `stream_leakage` on 07, 08, 11, 12, 15.
- **Companion self-memory and fiction-as-fact** — `invented` traps on 09, 13.
- **Retracted/contradicted facts filed as standing truth** — 03, 05.
- **Malformed/truncated output hidden as "no memories"** — `unreadable_calls`
  on 17.

## 6. What the live pass still has to produce

To close the Phase 3 gate, run the live pass (README "Run live") with real
provider keys. A cost-bounded plan:

1. **Chunk-size knee**, both models, all-three streams, `broad` profile,
   plain_json: sweep 2k→32k. This is the owner's priority — it shows where the
   cheaper model loses extraction quality. (~8 chunks × 2 models × 17 fixtures.)
2. **Profile comparison** at the chunk size chosen in step 1: broad vs balanced
   vs conservative, both models.
3. **Stream isolation** on 11/12 and combinations on 15.
4. **Schema**: plain_json vs structured on the model(s) that support it.
5. Use `--record-out` so every response is saved and can be re-scored for free.

## 7. Provisional starting points (plan-grounded hypotheses — confirm with §6)

These are **not** evidence-backed recommendations yet. They are the defensible
starting points from the approved plan, to be confirmed or corrected by the live
pass. Where a genuine product choice is involved, it is flagged in §8, not
decided here.

- **Chunk target labels:** fixed by revision 25 §7 (Small ~4k / Standard ~8k /
  Large ~16k). The harness may recommend adjusting the *token values* behind
  them, not the labels.
- **Provisional default chunk target:** *hypothesis* Standard (~8k) or Auto —
  broad model compatibility with adequate context. **The default itself is a
  product choice (§8).**
- **Provisional prompt profile default:** *hypothesis* Balanced — the plan
  positions it as the sensible middle. **The default is a product choice (§8).**
- **Output/schema strategy:** already fixed by §8.7 — use provider structured
  output when available, always keep the plain-JSON fallback working. The pass
  confirms the fallback loses nothing measurable; it does not reopen the choice.
- **Model-class guidance:** *pending* — the whole point of the cheap-vs-strong
  sweep. Likely outcome to test: cheaper model is viable at small/standard
  chunks and degrades at large; to be confirmed.

## 8. Decisions for the owner (do not want silent defaults)

1. **How should the live evidence be produced?** This environment has no model
   API keys and a paid multi-config pass is the owner's usage to authorize.
   Options: (a) provide provider keys + approval for a bounded pass and it will
   be run and scored; (b) run the documented pass yourself and drop the captured
   responses in `recorded/` for scoring; (c) supply recorded transcripts. No
   numbers will be invented in the meantime.
2. **Default prompt profile** (Balanced / Broad / Conservative) — user-visible
   default behavior; the evidence informs it, but the choice is yours.
3. **Default chunk target** (Auto / Small / Standard / Large) — user-visible
   default; same footing as #2.

## 9. Confidence and limitations

- **High confidence:** the harness mechanics, parser parity, metric definitions,
  fixture coverage, and failure-visibility behavior (all test-verified).
- **No confidence yet:** any statement about which profile/chunk/model/schema is
  *best*. That requires §6.
- The token estimate is a heuristic for *sizing*; cost uses the provider's real
  reported usage in a live run.
- Fixtures are synthetic and English-only; they cover the plan's case list but
  are not a substitute for the owner's real (sanitized) conversations if those
  can be provided for a second pass.
- Parser parity is a snapshot of the current Kotlin parser; keep them in sync.

## 10. Exact defaults Phase 4 should start with

Until the §6 pass runs, Phase 4 should start from the plan-fixed, non-evidence
items and treat the rest as provisional:

- **Response contract:** the §8.7 envelope; structured output when the provider
  supports it, plain-JSON fallback always; no title/importance/provenance/ids.
- **Chunk labels:** Auto, Small ~4k, Standard ~8k, Large ~16k, Custom (rev 25 §7),
  token budget governing, no hidden fallback overriding an explicit pick.
- **Provisional runtime defaults (to confirm):** default chunk = Standard/Auto;
  default profile = Balanced; deterministic exact-dedup before any semantic
  consolidation; bounded shrink-and-retry on truncation/context rejection;
  failures surfaced, never "no memories".

These provisional runtime defaults are marked provisional precisely because §8
#2 and #3 are the owner's to set and §6 has not yet been run.
