"""Token estimation for the evaluation harness.

The production app (and every OpenAI-compatible provider) meters and limits by
*tokens*, not characters. Phase 3 explicitly requires token-based chunk targets
rather than the current 200,000-*character* ceiling, so the harness reasons in
tokens throughout.

We deliberately keep this dependency-free. A real tokenizer (tiktoken) is used
when it happens to be installed, but the harness must run in CI and on a plain
Python install, so the default is a documented heuristic.

Heuristic: ~4 characters per token for English prose. This is the widely cited
OpenAI rule of thumb and is accurate enough for *chunk sizing* (where we only
need to keep a request comfortably under a budget). It is not accurate enough to
bill a user, which is why the live runner records the provider's real reported
``usage`` instead of trusting this estimate (see ``runner.py``).

``estimate_tokens`` is intentionally conservative-leaning for chunking: it never
under-counts by more than the heuristic error, and the chunker applies its own
safety margin on top.
"""

from __future__ import annotations

import math

# Average characters per token for English prose. Documented OpenAI heuristic.
CHARS_PER_TOKEN = 4.0

_HAS_TIKTOKEN = False
_ENC = None
try:  # pragma: no cover - environment dependent
    import tiktoken  # type: ignore

    _ENC = tiktoken.get_encoding("cl100k_base")
    _HAS_TIKTOKEN = True
except Exception:  # pragma: no cover
    _HAS_TIKTOKEN = False
    _ENC = None


def estimate_tokens(text: str) -> int:
    """Estimate the token count of ``text``.

    Uses tiktoken when available (exact for OpenAI cl100k models); otherwise a
    ~4-chars/token heuristic rounded up. Empty text is zero tokens.
    """
    if not text:
        return 0
    if _HAS_TIKTOKEN and _ENC is not None:  # pragma: no cover
        return len(_ENC.encode(text))
    return int(math.ceil(len(text) / CHARS_PER_TOKEN))


def tokenizer_name() -> str:
    """Human-readable name of the active estimator, for report provenance."""
    return "tiktoken/cl100k_base" if _HAS_TIKTOKEN else f"heuristic(~{CHARS_PER_TOKEN:g} chars/token)"
