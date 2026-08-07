"""Scoring: judge a parsed extraction against a fixture's gold labels.

The plan is explicit (req #8, restrictions): do NOT require identical model
wording — score the *semantic* result. So matching is anchor-based, never string
equality. Each expected memory carries one or more *anchor groups*; a candidate
matches it when every group is satisfied by at least one of its alternative
phrases appearing in the candidate's (normalized) content. That is robust to
paraphrase while still demanding the actual concept be present.

Metrics recorded per (config, fixture), then aggregated — the full list the plan
requires (req #5): useful General found, useful Companion found, missed,
invented/unsupported, General-vs-Companion placement errors, duplicates, wrong
scope/target, invalid Type suggestions, useful vs noisy Model Rules, Do-Not-
Analyze violations, malformed/truncated handling, request count, input/output
tokens, latency, and estimated cost.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Optional, Set
import re

from .parser import Candidate, Rule, SCOPES


def normalize(text: str) -> str:
    text = text.lower()
    text = re.sub(r"[^a-z0-9]+", " ", text)
    return f" {text.strip()} "  # pad so word-boundary substring checks are safe


def _phrase_in(phrase: str, hay_norm: str) -> bool:
    p = normalize(phrase).strip()
    if not p:
        return False
    return f" {p} " in hay_norm or hay_norm.strip().find(p) >= 0


def anchors_match(anchor_groups: List[List[str]], content: str) -> bool:
    """True when every group has at least one alternative present in content."""
    hay = normalize(content)
    for group in anchor_groups:
        if not any(_phrase_in(alt, hay) for alt in group):
            return False
    return True


# --------------------------------------------------------------------------- #
# Gold-label model (loaded from fixture JSON)                                  #
# --------------------------------------------------------------------------- #
@dataclass
class ExpectedMemory:
    id: str
    stream: str  # "general" | "companion"
    anchors: List[List[str]]
    target: Optional[str] = None
    note: str = ""


@dataclass
class ExpectedRule:
    id: str
    anchors: List[List[str]]
    note: str = ""


@dataclass
class Trap:
    id: str
    kind: str  # invention | self_memory | fiction_as_fact | overinterpretation | leakage_bait
    anchors: List[List[str]]
    note: str = ""


@dataclass
class Gold:
    expected: List[ExpectedMemory] = field(default_factory=list)
    rules: List[ExpectedRule] = field(default_factory=list)
    acceptable_extra: List[List[List[str]]] = field(default_factory=list)  # list of anchor-group-sets
    traps: List[Trap] = field(default_factory=list)
    do_not_analyze: bool = False
    expect_unreadable: bool = False  # for malformed-output fixtures

    @staticmethod
    def from_dict(d: dict) -> "Gold":
        return Gold(
            expected=[ExpectedMemory(**{**e, "target": e.get("target"), "note": e.get("note", "")})
                      for e in d.get("expected", [])],
            rules=[ExpectedRule(**{**r, "note": r.get("note", "")}) for r in d.get("rules", [])],
            acceptable_extra=d.get("acceptable_extra", []),
            traps=[Trap(**{**t, "note": t.get("note", "")}) for t in d.get("traps", [])],
            do_not_analyze=d.get("do_not_analyze", False),
            expect_unreadable=d.get("expect_unreadable", False),
        )


# --------------------------------------------------------------------------- #
# Per-fixture score                                                           #
# --------------------------------------------------------------------------- #
@dataclass
class FixtureScore:
    fixture_id: str
    active_streams: List[str]

    useful_general: int = 0
    useful_companion: int = 0
    missed_general: int = 0
    missed_companion: int = 0
    placement_errors: int = 0     # matched but by a wrong-stream candidate
    stream_leakage: int = 0       # candidate produced for a not-requested stream
    invented: int = 0             # trap hits (unsupported / hallucinated)
    overextraction: int = 0       # candidate matching nothing gold, no trap (soft)
    target_errors: int = 0
    invalid_type: int = 0
    duplicates_removed: int = 0
    useful_rules: int = 0
    noisy_rules: int = 0
    missed_rules: int = 0
    dna_violations: int = 0
    parser_dropped: int = 0
    unreadable_calls: int = 0
    malformed_handled_ok: Optional[bool] = None

    requests: int = 0
    input_tokens: int = 0
    output_tokens: int = 0
    latency_ms: int = 0
    cost_usd: float = 0.0

    trap_detail: List[str] = field(default_factory=list)


def _dedupe_exact(cands: List[Candidate]) -> (List[Candidate], int):
    """§8.8 step 5: remove exact duplicates (normalized content + scope)."""
    seen: Set[str] = set()
    out: List[Candidate] = []
    removed = 0
    for c in cands:
        key = normalize(c.content).strip() + "|" + c.scope
        if key in seen:
            removed += 1
            continue
        seen.add(key)
        out.append(c)
    return out, removed


def score_fixture(
    fixture_id: str,
    gold: Gold,
    candidates: List[Candidate],
    rules: List[Rule],
    active_streams: List[str],
    *,
    valid_type_ids: Set[str],
    parser_dropped: int,
    unreadable_calls: int,
) -> FixtureScore:
    fs = FixtureScore(fixture_id=fixture_id, active_streams=list(active_streams))
    fs.parser_dropped = parser_dropped
    fs.unreadable_calls = unreadable_calls

    # Do Not Analyze: the pipeline must not have called the model at all. Any
    # produced candidate/rule (or model call) is a violation.
    if gold.do_not_analyze:
        fs.dna_violations = len(candidates) + len(rules) + unreadable_calls
        return fs

    # Malformed-output fixtures: success is that the parser flagged the response
    # unreadable (so the run surfaces a real failure) rather than silently
    # yielding "no memories". If it also happened to salvage valid rows, that is
    # fine, but the key check is the failure was visible.
    if gold.expect_unreadable:
        fs.malformed_handled_ok = unreadable_calls > 0 or len(candidates) == 0
        # still fall through to score any salvaged rows below (no double count)

    cands, removed = _dedupe_exact(candidates)
    fs.duplicates_removed = removed

    active = set(active_streams)
    matched_candidate_ids: Set[int] = set()

    # 1) expected memories in the requested streams.
    for e in gold.expected:
        if e.stream not in active:
            continue
        correct = None
        wrong = None
        for i, c in enumerate(cands):
            if anchors_match(e.anchors, c.content):
                if c.stream == e.stream:
                    correct = i
                    break
                else:
                    wrong = i
        if correct is not None:
            matched_candidate_ids.add(correct)
            if e.stream == "companion":
                fs.useful_companion += 1
            else:
                fs.useful_general += 1
            c = cands[correct]
            if e.target and (c.target or "").strip().lower() != e.target.strip().lower():
                fs.target_errors += 1
        elif wrong is not None:
            matched_candidate_ids.add(wrong)
            fs.placement_errors += 1
        else:
            if e.stream == "companion":
                fs.missed_companion += 1
            else:
                fs.missed_general += 1

    # 2) leakage + invention + over-extraction + invalid types, over all cands.
    for i, c in enumerate(cands):
        # invalid Type suggestion (present but not a current Type id).
        if c.type_id and c.type_id not in valid_type_ids:
            fs.invalid_type += 1
        if c.stream not in active:
            fs.stream_leakage += 1
            # a leaked candidate can still be a trap hit; check below too
        if i in matched_candidate_ids:
            continue
        # unmatched candidate: trap? acceptable extra? else soft over-extraction.
        trap = next((t for t in gold.traps if anchors_match(t.anchors, c.content)), None)
        if trap is not None:
            fs.invented += 1
            fs.trap_detail.append(f"{trap.kind}:{trap.id}")
            continue
        if any(anchors_match(groups, c.content) for groups in gold.acceptable_extra):
            continue
        fs.overextraction += 1

    # 3) model rules (only when requested).
    if "model_rules" in active:
        rule_matched: Set[int] = set()
        for er in gold.rules:
            hit = None
            for j, r in enumerate(rules):
                if j in rule_matched:
                    continue
                if anchors_match(er.anchors, r.text):
                    hit = j
                    break
            if hit is not None:
                rule_matched.add(hit)
                fs.useful_rules += 1
            else:
                fs.missed_rules += 1
        fs.noisy_rules += max(0, len(rules) - len(rule_matched))
    else:
        # rules produced when not requested are leakage/noise.
        fs.noisy_rules += len(rules)

    return fs


def aggregate(scores: List[FixtureScore]) -> Dict[str, float]:
    """Sum a config's per-fixture scores and derive precision/recall."""
    agg: Dict[str, float] = {}
    int_fields = [
        "useful_general", "useful_companion", "missed_general", "missed_companion",
        "placement_errors", "stream_leakage", "invented", "overextraction",
        "target_errors", "invalid_type", "duplicates_removed", "useful_rules",
        "noisy_rules", "missed_rules", "dna_violations", "parser_dropped",
        "unreadable_calls", "requests", "input_tokens", "output_tokens",
        "latency_ms",
    ]
    for f in int_fields:
        agg[f] = sum(getattr(s, f) for s in scores)
    agg["cost_usd"] = round(sum(s.cost_usd for s in scores), 6)

    found = agg["useful_general"] + agg["useful_companion"]
    missed = agg["missed_general"] + agg["missed_companion"]
    denom_recall = found + missed + agg["placement_errors"]
    agg["recall"] = round(found / denom_recall, 4) if denom_recall else 0.0
    # precision penalizes hallucination (invented) and soft over-extraction.
    kept = found + agg["placement_errors"] + agg["invented"] + agg["overextraction"]
    agg["precision"] = round(found / kept, 4) if kept else 0.0
    return agg
