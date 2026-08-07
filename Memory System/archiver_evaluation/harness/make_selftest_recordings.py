"""Generate SYNTHETIC self-test recordings for the offline harness path.

IMPORTANT — these are NOT model measurements and must never be read as evidence
for any production default. They are scripted, deterministic responses derived
from each fixture's own gold labels, whose only purpose is to prove the
end-to-end plumbing (chunk -> prompt -> runner -> parse -> score -> report)
works and that the scorer moves in the expected direction (a looser "broad"
script scores higher recall / lower precision than a stricter "conservative"
script). The real numbers come only from a ``--live`` pass with provider keys.

Run: ``python -m harness.make_selftest_recordings`` (writes recorded/selftest.json).
"""

from __future__ import annotations

import json
import os
from typing import List

if __package__ in (None, ""):
    import sys
    sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    from harness import config
    from harness.run import load_fixtures, call_key, FIXTURES_DIR
else:
    from . import config
    from .run import load_fixtures, call_key, FIXTURES_DIR

HERE = os.path.dirname(os.path.abspath(__file__))
RECORDED = os.path.join(os.path.dirname(HERE), "recorded")

# The single small matrix the self-test drives.
STREAMS = ["general", "companion", "model_rules"]
CHUNK = next(c for c in config.CHUNK_SWEEP if c.key == "t8k")
MODEL = config.DEFAULT_MODELS["cheap"]


def _first(alts: List[str]) -> str:
    return alts[0] if alts else ""


def _scope_for(stream: str) -> str:
    return "companion" if stream == "companion" else "real_life"


def _content_from_anchors(anchor_groups: List[List[str]]) -> str:
    # Join one alternative from each group into a sentence that satisfies the
    # anchors (self-test only — a real model writes its own prose).
    parts = [_first(g) for g in anchor_groups if g]
    return "Note: " + ", ".join(parts) + "."


def _memory_obj(stream: str, anchor_groups, target):
    obj = {"content": _content_from_anchors(anchor_groups), "scope": _scope_for(stream)}
    if target:
        obj["target"] = target
    return obj


def build_broad(fx) -> str:
    gold = fx.get("gold", {})
    mems = []
    for e in gold.get("expected", []):
        mems.append(_memory_obj(e["stream"], e["anchors"], e.get("target")))
    # Broad also trips traps: emit one over-extraction per trap.
    for t in gold.get("traps", []):
        mems.append({"content": _content_from_anchors(t["anchors"]), "scope": "real_life"})
    rules = [{"text": _content_from_anchors(r["anchors"])} for r in gold.get("rules", [])]
    return json.dumps({"memories": mems, "model_rules": rules})


def build_conservative(fx) -> str:
    gold = fx.get("gold", {})
    expected = gold.get("expected", [])
    # Conservative captures all but the last expected (a modest miss) and no traps.
    keep = expected[:-1] if len(expected) > 1 else expected
    mems = [_memory_obj(e["stream"], e["anchors"], e.get("target")) for e in keep]
    rules = [{"text": _content_from_anchors(r["anchors"])} for r in gold.get("rules", [])]
    return json.dumps({"memories": mems, "model_rules": rules})


def build_response(profile: str, fx) -> str:
    # Fixture 17 is the malformed-output test: exercise recovery vs failure.
    if fx["_id"] == "17_malformed_output":
        if profile == "broad":
            # Fenced + prose-wrapped but recoverable.
            return ("Sure! Here are the memories:\n```json\n"
                    + json.dumps({"memories": [
                        {"content": "Note: firefox, default", "scope": "real_life"}],
                        "model_rules": []})
                    + "\n```\nHope that helps.")
        else:
            # Truncated / unreadable — must surface as a visible failure.
            return "{ \"memories\": [ { \"content\": \"Note: firefox, defaul"
    return build_broad(fx) if profile == "broad" else build_conservative(fx)


def main() -> int:
    fixtures = load_fixtures(FIXTURES_DIR)
    store = {}
    for profile in ("broad", "conservative"):
        cfg = config.RunConfig(profile=profile, streams=STREAMS, chunk=CHUNK,
                               model=MODEL, schema="plain_json")
        for fx in fixtures:
            if fx.get("gold", {}).get("do_not_analyze"):
                continue  # model is never called for Do Not Analyze
            key = call_key(fx["_id"], cfg, 0)
            store[key] = {"raw": build_response(profile, fx),
                          "usage": {"prompt_tokens": 1200, "completion_tokens": 220},
                          "latency_ms": 900}
    os.makedirs(RECORDED, exist_ok=True)
    out = os.path.join(RECORDED, "selftest.json")
    with open(out, "w", encoding="utf-8") as f:
        json.dump(store, f, indent=2, sort_keys=True)
    print(f"Wrote {out} with {len(store)} synthetic self-test recordings.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
