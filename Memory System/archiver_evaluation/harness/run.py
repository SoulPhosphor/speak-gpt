"""Orchestrator / CLI for the archiver evaluation harness.

    python -m harness.run --help

Default is a free, deterministic OFFLINE run: it replays whatever raw responses
exist under ``recorded/`` (the shipped ones are the clearly-labeled self-test
recordings). A real measurement pass adds ``--live`` and needs provider API keys
in the environment (absent in the build environment on purpose — see README).

Nothing here ever writes to the app's memory database.
"""

from __future__ import annotations

from dataclasses import asdict
from typing import Dict, List, Optional, Set
import argparse
import json
import os
import sys

# Allow running both as a module (python -m harness.run) and as a script.
if __package__ in (None, ""):
    sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    from harness import chunking, config, parser, prompts, report, runner, scoring, tokens
else:
    from . import chunking, config, parser, prompts, report, runner, scoring, tokens

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
FIXTURES_DIR = os.path.join(ROOT, "fixtures")
RECORDED_DIR = os.path.join(ROOT, "recorded")
RESULTS_DIR = os.path.join(ROOT, "results")

# A small default Type set so type_id validation is exercised. In a real run
# this mirrors the user's live Type list; here it is representative.
DEFAULT_TYPES = [
    ("preference", "Preference"),
    ("fact", "Fact"),
    ("event", "Event"),
    ("status", "Status"),
    ("instruction", "Instruction"),
    ("lore", "Lore"),
    ("project", "Project"),
]
VALID_TYPE_IDS: Set[str] = {t[0] for t in DEFAULT_TYPES}

DEFAULT_TEMPERATURE = 0.3


def load_fixtures(path: str) -> List[dict]:
    fixtures = []
    for name in sorted(os.listdir(path)):
        if not name.endswith(".json"):
            continue
        with open(os.path.join(path, name), "r", encoding="utf-8") as f:
            data = json.load(f)
        data["_id"] = data.get("id", os.path.splitext(name)[0])
        fixtures.append(data)
    return fixtures


def call_key(fixture_id: str, cfg: "config.RunConfig", chunk_index: int) -> str:
    return f"{fixture_id}#{cfg.id}#chunk{chunk_index}"


def run_config_on_fixture(
    fixture: dict,
    cfg: "config.RunConfig",
    run: "runner.ModelRunner",
    budget: "config.BudgetModel",
) -> "scoring.FixtureScore":
    fixture_id = fixture["_id"]
    gold = scoring.Gold.from_dict(fixture.get("gold", {}))

    # Do Not Analyze: the pipeline must not call the model at all.
    if gold.do_not_analyze:
        return scoring.score_fixture(
            fixture_id, gold, [], [], cfg.streams,
            valid_type_ids=VALID_TYPE_IDS, parser_dropped=0, unreadable_calls=0,
        )

    messages = [chunking.Message(role=m["role"], content=m["content"])
                for m in fixture["conversation"]]
    companion_name = fixture.get("companion_name")
    model_tags = fixture.get("model_tags", [])

    system_prompt = prompts.build_system_prompt(
        cfg.profile, DEFAULT_TYPES, cfg.streams, companion_name, schema=cfg.schema
    )
    sys_tok = tokens.estimate_tokens(system_prompt)
    structured = cfg.schema == "structured"
    transcript_budget = budget.transcript_budget(
        cfg.chunk.target_tokens, structured=structured, system_prompt_tokens=sys_tok
    )
    # Respect the model's context limit too (§8.5 min-of).
    transcript_budget = min(transcript_budget, cfg.model.context_limit_tokens - sys_tok - budget.output_reserve_tokens)

    chunks = chunking.chunk_conversation(messages, transcript_budget)

    all_candidates: List[parser.Candidate] = []
    all_rules: List[parser.Rule] = []
    total_dropped = 0
    unreadable_calls = 0
    requests = 0
    in_tok = 0
    out_tok = 0
    latency = 0

    for idx, ch in enumerate(chunks):
        user_msg = prompts.build_user_message(
            fixture.get("chat_name", fixture_id), companion_name, model_tags, ch.text
        )
        mc = run.call(
            system_prompt, user_msg,
            model_id=cfg.model.model_id or cfg.model.key,
            temperature=DEFAULT_TEMPERATURE,
            structured=structured,
            call_key=call_key(fixture_id, cfg, idx),
        )
        requests += 1
        in_tok += mc.input_tokens
        out_tok += mc.output_tokens
        latency += mc.latency_ms
        if mc.error:
            unreadable_calls += 1
            continue
        parsed = parser.parse_envelope(mc.raw) if structured else parser.parse(mc.raw)
        if parsed.unreadable:
            unreadable_calls += 1
            continue
        all_candidates.extend(parsed.memories)
        all_rules.extend(parsed.rules)
        total_dropped += parsed.dropped

    fs = scoring.score_fixture(
        fixture_id, gold, all_candidates, all_rules, cfg.streams,
        valid_type_ids=VALID_TYPE_IDS, parser_dropped=total_dropped,
        unreadable_calls=unreadable_calls,
    )
    fs.requests = requests
    fs.input_tokens = in_tok
    fs.output_tokens = out_tok
    fs.latency_ms = latency
    fs.cost_usd = round(
        in_tok / 1_000_000 * cfg.model.price_in
        + out_tok / 1_000_000 * cfg.model.price_out,
        6,
    )
    return fs


def build_models(args) -> Dict[str, "config.ModelSpec"]:
    models = dict(config.DEFAULT_MODELS)
    if args.models:
        wanted = set(args.models.split(","))
        models = {k: v for k, v in models.items() if k in wanted or v.tier in wanted}
    return models


def filter_matrix(matrix, args):
    def keep(cfg):
        if args.profiles and cfg.profile not in args.profiles.split(","):
            return False
        if args.chunks and cfg.chunk.key not in args.chunks.split(","):
            return False
        if args.schemas and cfg.schema not in args.schemas.split(","):
            return False
        if args.streams:
            want = args.streams.split(",")  # e.g. "general" or "general+companion"
            if "+".join(cfg.streams) not in want:
                return False
        return True
    return [c for c in matrix if keep(c)]


def main(argv: Optional[List[str]] = None) -> int:
    ap = argparse.ArgumentParser(description="Archiver evaluation harness (Phase 3).")
    ap.add_argument("--fixtures-dir", default=FIXTURES_DIR)
    ap.add_argument("--recorded-dir", default=RECORDED_DIR)
    ap.add_argument("--out-dir", default=RESULTS_DIR)
    ap.add_argument("--live", action="store_true",
                    help="Call real providers (needs API keys). Default is offline replay.")
    ap.add_argument("--record-out", default=None,
                    help="When --live, write raw responses here for later free re-scoring.")
    ap.add_argument("--profiles", default=None, help="comma list: broad,balanced,conservative")
    ap.add_argument("--chunks", default=None, help="comma list of chunk keys, e.g. t4k,t8k,t16k")
    ap.add_argument("--models", default=None, help="comma list of model keys/tiers")
    ap.add_argument("--streams", default=None,
                    help="comma list, e.g. general,companion,general+companion")
    ap.add_argument("--schemas", default=None, help="comma list: plain_json,structured")
    ap.add_argument("--title", default="Archiver Evaluation — Results")
    args = ap.parse_args(argv)

    fixtures = load_fixtures(args.fixtures_dir)
    models = build_models(args)

    if args.live:
        run: runner.ModelRunner
        # One live runner per model provider is built lazily inside the loop via
        # a small cache keyed by base_url+key env.
        record_into: Dict[str, dict] = {} if args.record_out else None  # type: ignore
        live_cache: Dict[str, runner.LiveRunner] = {}

        def live_for(model: "config.ModelSpec") -> runner.LiveRunner:
            key = model.base_url + "|" + model.api_key_env
            if key not in live_cache:
                api_key = os.environ.get(model.api_key_env, "")
                if not api_key:
                    raise SystemExit(
                        f"--live needs {model.api_key_env} in the environment for {model.label}."
                    )
                live_cache[key] = runner.LiveRunner(model.base_url, api_key, record_into=record_into)
            return live_cache[key]
        get_runner = live_for
    else:
        rec = runner.RecordedRunner.from_dir(args.recorded_dir)
        get_runner = lambda _m: rec  # noqa: E731
        record_into = None

    budget = config.BudgetModel()
    matrix = filter_matrix(config.build_matrix(models), args)

    rows: List[dict] = []
    for cfg in matrix:
        scores = [run_config_on_fixture(fx, cfg, get_runner(cfg.model), budget)
                  for fx in fixtures]
        agg = scoring.aggregate(scores)
        row = {
            "config_id": cfg.id,
            "profile": cfg.profile,
            "streams": "+".join(cfg.streams),
            "chunk": cfg.chunk.key,
            "chunk_tokens": cfg.chunk.target_tokens,
            "model": cfg.model.key,
            "tier": cfg.model.tier,
            "schema": cfg.schema,
        }
        row.update(agg)
        rows.append(row)

    os.makedirs(args.out_dir, exist_ok=True)
    csv_path = os.path.join(args.out_dir, "results.csv")
    md_path = os.path.join(args.out_dir, "results.md")
    report.write_csv(rows, csv_path)
    preamble = (
        f"Runner: {'LIVE' if args.live else 'OFFLINE (recorded replay)'} · "
        f"Token estimator: {tokens.tokenizer_name()} · "
        f"Configs: {len(rows)} · Fixtures: {len(fixtures)}"
    )
    report.write_markdown(rows, md_path, title=args.title, preamble=preamble)

    if record_into is not None and args.record_out:
        with open(args.record_out, "w", encoding="utf-8") as f:
            json.dump(record_into, f, indent=2)

    print(f"Wrote {csv_path} and {md_path} ({len(rows)} configs, {len(fixtures)} fixtures).")
    if not args.live:
        print("NOTE: offline replay. Numbers reflect only the recorded self-test "
              "responses, not a live model measurement. Use --live with keys for evidence.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
