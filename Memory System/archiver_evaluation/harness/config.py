"""Configuration: models, chunk targets, token budgeting, and the sweep matrix.

Everything here is *reference/example* data meant to be edited for a real run.
In particular, model names, context limits, and prices change constantly and
are provider-specific — the harness never bills anyone, and the live runner
records the provider's *actual* reported token usage, so these numbers only
drive rough cost *estimates* in the report. Confirm current pricing before
quoting any figure as fact.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Optional


@dataclass
class ModelSpec:
    """A model under test. ``price_in``/``price_out`` are USD per 1M tokens and
    are EXAMPLE values — replace with the live provider's current pricing."""

    key: str
    label: str
    tier: str  # "cheap" | "strong" (the §8.11 lower-cost vs stronger axis)
    # OpenAI-compatible base URL and the provider model id. Only used by the
    # live runner; left as placeholders for offline scoring.
    base_url: str = "https://api.openai.com/v1"
    model_id: str = ""
    context_limit_tokens: int = 128_000
    supports_structured: bool = True
    price_in: float = 0.0   # USD / 1M input tokens (example)
    price_out: float = 0.0  # USD / 1M output tokens (example)
    # Env var holding the API key for this model's provider.
    api_key_env: str = "OPENAI_API_KEY"


# Example registry: one cheaper model and one stronger model, the minimum the
# plan requires (§8.11 "at least one lower-cost model"). EDIT before a live run:
# confirm model ids, context limits, structured-output support, and prices.
DEFAULT_MODELS: Dict[str, ModelSpec] = {
    "cheap": ModelSpec(
        key="cheap",
        label="Lower-cost model (example)",
        tier="cheap",
        model_id="",  # e.g. a small/cheap chat model id
        context_limit_tokens=128_000,
        supports_structured=True,
        price_in=0.15,
        price_out=0.60,
    ),
    "strong": ModelSpec(
        key="strong",
        label="Stronger model (example)",
        tier="strong",
        model_id="",  # e.g. a flagship chat model id
        context_limit_tokens=128_000,
        supports_structured=True,
        price_in=2.50,
        price_out=10.00,
    ),
}


@dataclass
class ChunkTarget:
    """A token-based chunk target. The three approved user-facing labels
    (revision 25 §7) plus extra sweep points to locate where extraction quality
    meaningfully changes — the owner's specific concern that cheap models may
    do well small but degrade when overloaded."""

    key: str
    target_tokens: int
    label: str = ""


# The approved Small/Standard/Large labels are fixed (rev 25 §7). Their exact
# token *targets* are what Phase 3 must justify — so we sweep a bounded range
# around and beyond them (established practice: test roughly 2x steps).
CHUNK_SWEEP: List[ChunkTarget] = [
    ChunkTarget("t2k", 2_000, "2k"),
    ChunkTarget("t4k", 4_000, "Small · ~4k (approved label)"),
    ChunkTarget("t6k", 6_000, "6k"),
    ChunkTarget("t8k", 8_000, "Standard · ~8k (approved label)"),
    ChunkTarget("t12k", 12_000, "12k"),
    ChunkTarget("t16k", 16_000, "Large · ~16k (approved label)"),
    ChunkTarget("t24k", 24_000, "24k"),
    ChunkTarget("t32k", 32_000, "32k"),
]


# §8.5 budget subtractions. The transcript budget for one request is the chunk
# target minus fixed overheads and a safety margin, never the raw target.
@dataclass
class BudgetModel:
    system_prompt_tokens: int = 900      # measured per profile at runtime; this is a floor
    output_reserve_tokens: int = 1_500   # room for the JSON the model must return
    structured_overhead_tokens: int = 250
    analysis_note_tokens: int = 0
    safety_margin_tokens: int = 400

    def transcript_budget(self, target_tokens: int, *, structured: bool,
                          system_prompt_tokens: Optional[int] = None) -> int:
        sysp = self.system_prompt_tokens if system_prompt_tokens is None else system_prompt_tokens
        overhead = (
            sysp
            + self.output_reserve_tokens
            + (self.structured_overhead_tokens if structured else 0)
            + self.analysis_note_tokens
            + self.safety_margin_tokens
        )
        return max(500, target_tokens - overhead)


PROFILES = ["broad", "balanced", "conservative"]

# Stream combinations to test (§8.2 / Phase 3 req: each stream alone + relevant
# combinations). "model_rules" rides along where it makes sense.
STREAM_SETS: List[List[str]] = [
    ["general"],
    ["companion"],
    ["model_rules"],
    ["general", "companion"],
    ["general", "companion", "model_rules"],
]

SCHEMAS = ["plain_json", "structured"]


@dataclass
class RunConfig:
    """One point in the sweep matrix."""

    profile: str
    streams: List[str]
    chunk: ChunkTarget
    model: ModelSpec
    schema: str

    @property
    def id(self) -> str:
        return (
            f"{self.profile}__{'+'.join(self.streams)}__{self.chunk.key}"
            f"__{self.model.key}__{self.schema}"
        )


def build_matrix(
    models: Dict[str, ModelSpec],
    profiles: List[str] = PROFILES,
    stream_sets: List[List[str]] = STREAM_SETS,
    chunks: List[ChunkTarget] = CHUNK_SWEEP,
    schemas: List[str] = SCHEMAS,
) -> List[RunConfig]:
    """Full cartesian sweep. In practice a live run narrows this (e.g. fix the
    stream set to all-three, then vary one axis at a time) to control cost; the
    CLI exposes filters for that."""
    matrix: List[RunConfig] = []
    for profile in profiles:
        for streams in stream_sets:
            for chunk in chunks:
                for model in models.values():
                    for schema in schemas:
                        if schema == "structured" and not model.supports_structured:
                            continue
                        matrix.append(
                            RunConfig(profile=profile, streams=list(streams),
                                      chunk=chunk, model=model, schema=schema)
                        )
    return matrix
