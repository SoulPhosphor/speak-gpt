"""Report generation: a Markdown summary plus a machine-readable CSV.

The report separates *quality* (found / missed / invented / placement) from
*cost* (requests / tokens / latency / USD), because the plan insists the two be
judged together (§8.11): a profile that finds a little more but doubles
hallucinations or request cost is not automatically better.
"""

from __future__ import annotations

from typing import Dict, List
import csv
import io


# Columns in report order. (internal key, header, is_cost)
COLUMNS = [
    ("recall", "Recall", False),
    ("precision", "Precision", False),
    ("useful_general", "Gen found", False),
    ("useful_companion", "Comp found", False),
    ("missed_general", "Gen missed", False),
    ("missed_companion", "Comp missed", False),
    ("placement_errors", "Placement err", False),
    ("stream_leakage", "Stream leak", False),
    ("invented", "Invented", False),
    ("overextraction", "Over-extract", False),
    ("target_errors", "Target err", False),
    ("invalid_type", "Invalid type", False),
    ("duplicates_removed", "Dupes", False),
    ("useful_rules", "Rules ok", False),
    ("noisy_rules", "Rules noisy", False),
    ("dna_violations", "DNA viol", False),
    ("unreadable_calls", "Unreadable", False),
    ("parser_dropped", "Dropped", False),
    ("requests", "Reqs", True),
    ("input_tokens", "In tok", True),
    ("output_tokens", "Out tok", True),
    ("latency_ms", "Latency ms", True),
    ("cost_usd", "Cost $", True),
]


def write_csv(rows: List[Dict], path: str) -> None:
    if not rows:
        open(path, "w").close()
        return
    keys = ["config_id", "profile", "streams", "chunk", "chunk_tokens", "model",
            "tier", "schema"] + [c[0] for c in COLUMNS]
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=keys, extrasaction="ignore")
        w.writeheader()
        for r in rows:
            w.writerow(r)


def _table(rows: List[Dict], keys: List[str]) -> str:
    out = io.StringIO()
    headers = ["Config"] + [h for (_k, h, _c) in COLUMNS if _k in keys]
    out.write("| " + " | ".join(headers) + " |\n")
    out.write("|" + "|".join(["---"] * len(headers)) + "|\n")
    for r in rows:
        cells = [r["config_id"]]
        for (k, _h, _c) in COLUMNS:
            if k not in keys:
                continue
            v = r.get(k, "")
            cells.append(f"{v}")
        out.write("| " + " | ".join(str(c) for c in cells) + " |\n")
    return out.getvalue()


def write_markdown(rows: List[Dict], path: str, *, title: str, preamble: str = "") -> None:
    quality_keys = [
        "recall", "precision", "useful_general", "useful_companion",
        "missed_general", "missed_companion", "placement_errors",
        "stream_leakage", "invented", "overextraction",
    ]
    rule_keys = ["useful_rules", "noisy_rules", "invalid_type", "target_errors",
                 "duplicates_removed", "dna_violations", "unreadable_calls", "parser_dropped"]
    cost_keys = ["requests", "input_tokens", "output_tokens", "latency_ms", "cost_usd"]

    with open(path, "w", encoding="utf-8") as f:
        f.write(f"# {title}\n\n")
        if preamble:
            f.write(preamble.strip() + "\n\n")
        f.write("## Quality\n\n")
        f.write(_table(rows, quality_keys))
        f.write("\n## Rules, types, integrity\n\n")
        f.write(_table(rows, rule_keys))
        f.write("\n## Cost & operations\n\n")
        f.write(_table(rows, cost_keys))
        f.write("\n")
