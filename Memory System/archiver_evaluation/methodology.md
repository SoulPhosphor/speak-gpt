# Archiver Evaluation — Methodology

This document defines exactly what the harness measures and how, so that any
number it produces can be read without guessing. It covers the configuration
matrix, the scoring model, token budgeting, and the parity relationship with the
production code.

The harness lives entirely under `Memory System/archiver_evaluation/` and never
touches the live memory database or files any Active/Pending memory. That
isolation is a Phase 3 restriction, not an implementation detail.

## 1. What one run does

For one **configuration** (a point in the matrix below) and one **fixture** (a
synthetic conversation with gold labels), the harness:

1. builds the system prompt for the profile, appends the live Memory-Type list,
   and appends the requested Analyze-For streams (mirrors `ArchivistPrompt`);
2. chunks the conversation to the configuration's **token** budget (§4);
3. sends each chunk to the model runner (offline recorded replay, or live);
4. parses each response with the production-parity parser (§5);
5. accumulates candidates across chunks and removes exact duplicates (§8.8);
6. scores the result against the fixture's gold labels (§3);
7. aggregates per-configuration and writes a Markdown + CSV report.

## 2. Configuration matrix

Axes (all defined in `harness/config.py`):

| Axis | Values tested |
|------|---------------|
| Prompt profile | `broad` (current production prompt, verbatim), `balanced`, `conservative` |
| Extraction streams | `general`; `companion`; `model_rules`; `general+companion`; `general+companion+model_rules` |
| Chunk target (tokens) | 2k, **4k (Small)**, 6k, **8k (Standard)**, 12k, **16k (Large)**, 24k, 32k |
| Model | one lower-cost, one stronger (the §8.11 axis) |
| Schema | `plain_json` (current), `structured` (§8.7 envelope) |

The three bold chunk targets are the labels revision 25 §7 already approved
(`Small ~4k`, `Standard ~8k`, `Large ~16k`); the extra sweep points exist to
find *where* extraction quality changes, which is the owner's specific concern
that a cheaper model may extract well on small inputs and degrade when
overloaded. Steps roughly double, which is standard practice for locating a
quality knee without an unbounded search.

The full cartesian product is 480 configurations for two models. A real pass
narrows it with CLI filters (`--profiles`, `--chunks`, `--models`, `--streams`,
`--schemas`) so cost stays bounded — e.g. fix streams to all-three, then vary one
axis at a time.

## 3. Scoring model

**Semantic, not string-equal.** The plan forbids treating a wording mismatch as
failure when the same supported memory was captured. So every expected memory
carries one or more **anchor groups**; a candidate matches it when *every* group
has at least one of its alternative phrases present in the candidate's content
(case- and punctuation-insensitive substring). This demands the concept, not the
words. `harness/scoring.py::anchors_match`.

Per fixture the harness records, honoring the configuration's active streams:

| Metric | Meaning |
|--------|---------|
| `useful_general` / `useful_companion` | expected memories found in the correct stream |
| `missed_general` / `missed_companion` | expected memories not found |
| `placement_errors` | expected memory found, but by a wrong-stream candidate (General↔Companion) |
| `stream_leakage` | a candidate produced for a stream the pass did not request |
| `invented` | candidate that matches a **trap** (hallucination, companion self-memory, fiction-as-fact, over-interpretation) |
| `overextraction` | candidate matching no gold and no trap (soft noise) |
| `target_errors` | matched, but the world/campaign/character/project target is wrong |
| `invalid_type` | `type_id` present but not a current Type id |
| `duplicates_removed` | exact duplicates collapsed before scoring |
| `useful_rules` / `noisy_rules` / `missed_rules` | Model Rule quality |
| `dna_violations` | any output produced for a Do-Not-Analyze fixture |
| `unreadable_calls` | responses that surfaced as a visible failure (not "no memories") |
| `parser_dropped` | rows the parser dropped by validation/bounds |
| `requests`, `input_tokens`, `output_tokens`, `latency_ms`, `cost_usd` | operational cost |

Aggregate quality is summarized as **recall** = found / (found + missed +
placement errors) and **precision** = found / (found + placement + invented +
over-extraction). Both are reported alongside the raw counts, because §8.11
requires quality and cost to be judged together — a profile that finds a little
more but doubles hallucination or request cost is not automatically better.

**Traps** encode the plan's hard "must not" cases: companion self-memory
(fixture 09), fiction-as-real-life (13), retracted/contradicted facts (03, 05),
and over-interpretation of casual talk (02, 16). A configuration that trips them
is penalized in `invented`, separately from ordinary over-extraction.

## 4. Token budgeting (§8.5)

Chunking works in tokens, not characters. The transcript budget for one request
is the chunk target **minus** overheads, never the raw target:

```
transcript_budget = chunk_target
                  − system_prompt_tokens (measured per profile)
                  − output_reserve (room for the returned JSON)
                  − structured_overhead (when structured output is used)
                  − analysis_note_estimate
                  − safety_margin
```

and is additionally capped by the model's context limit. Token counts use
tiktoken when it is installed and a documented ~4-chars/token heuristic
otherwise; **the live runner records the provider's real reported `usage`**, so
cost figures never depend on the estimate. `harness/config.py::BudgetModel`,
`harness/tokens.py`.

The current 200,000-*character* ceiling is deliberately not used as a fallback
(the plan retires it).

## 5. Parser parity

`harness/parser.py` is a faithful port of the production
`ArchivistResponseParser` (same scope set, same "content required / known scope
or DROP" gate, same "absent or unknown `type_id` becomes No Type rather than a
drop", same tag normalization and defensive bounds, same fenced/prose JSON
isolation). `tests/test_parser_parity.py` pins the behavior. It also adds
`parse_envelope` for the §8.7 structured schema so the two schemas are scored on
equal terms. **If the Kotlin parser changes, update the port and the parity
tests together** — otherwise the harness stops measuring what ships.

## 6. Failure handling (§8.9)

Fixture 17 supplies fenced, prose-wrapped, truncated, and garbage responses. The
harness requires that recoverable wrappers still yield their memory, and that an
unreadable/truncated response surfaces as a visible failure (`unreadable_calls`)
rather than collapsing into "no memories found". This is a deterministic,
offline test of the parsing/failure contract; a live model cannot be forced to
emit malformed output on demand.

## 7. Honesty boundary

The recordings shipped under `recorded/` are **synthetic self-test scaffolding**
generated from the fixtures' own gold labels (`harness/make_selftest_recordings.py`).
They exist only to prove the plumbing and that the scorer discriminates a loose
profile from a strict one. They are **not** model measurements and must never be
cited as evidence for a production default. Real numbers come only from a
`--live` pass against real providers.
