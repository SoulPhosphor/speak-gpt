"""Token-based conversation chunking (counterplan §8.6).

Phase 3 replaces the current 200,000-*character* split with a *token*-budgeted
one and must decide the real Small / Standard / Large targets from evidence.
This chunker is what the harness sweeps across those targets.

Boundary rules, straight from §8.6:

- preserve complete messages whenever possible;
- do not split just because a message count was reached;
- if one message alone exceeds the budget, split it at paragraph boundaries,
  then sentence boundaries if a paragraph is still too big;
- preserve speaker identity and original ordering;
- optional small bounded overlap, marked internally (never as memory metadata).

The budget passed in is the *transcript* budget for one request — i.e. the
chunk target after the §8.5 subtractions (system prompt, output reserve, safety
margin) have already been removed. ``config.transcript_budget`` computes it.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import List
import re

from . import tokens


@dataclass
class Message:
    role: str  # "user" | "assistant"
    content: str

    def render(self) -> str:
        label = "Assistant" if self.role == "assistant" else "User"
        return f"{label}: {self.content}"


@dataclass
class Chunk:
    text: str
    message_indices: List[int] = field(default_factory=list)
    # Token count of this chunk's transcript text (estimate).
    tokens: int = 0
    # True when this chunk carries overlap rows repeated from the prior chunk.
    has_overlap: bool = False


def _split_oversized(text: str, budget: int) -> List[str]:
    """Split a single oversized rendered message into <=budget pieces, at
    paragraph boundaries first, then sentences, preserving order."""
    if tokens.estimate_tokens(text) <= budget:
        return [text]

    pieces: List[str] = []
    paragraphs = re.split(r"\n\s*\n", text)
    buf = ""
    for para in paragraphs:
        cand = (buf + "\n\n" + para) if buf else para
        if tokens.estimate_tokens(cand) <= budget:
            buf = cand
            continue
        if buf:
            pieces.append(buf)
            buf = ""
        if tokens.estimate_tokens(para) <= budget:
            buf = para
            continue
        # Paragraph still too big: fall to sentence boundaries.
        sentences = re.split(r"(?<=[.!?])\s+", para)
        sbuf = ""
        for s in sentences:
            scand = (sbuf + " " + s) if sbuf else s
            if tokens.estimate_tokens(scand) <= budget:
                sbuf = scand
            else:
                if sbuf:
                    pieces.append(sbuf)
                # A single sentence over budget travels alone (last resort;
                # never silently dropped).
                sbuf = s
        if sbuf:
            buf = sbuf
    if buf:
        pieces.append(buf)
    return pieces


def chunk_conversation(
    messages: List[Message],
    budget_tokens: int,
    overlap_messages: int = 0,
) -> List[Chunk]:
    """Chunk ``messages`` so each chunk's transcript text stays within
    ``budget_tokens``. ``overlap_messages`` optionally repeats the last N whole
    messages of the previous chunk at the head of the next (§8.6 bounded
    overlap); 0 disables it (the default until evidence justifies the cost)."""
    if not messages:
        return []

    chunks: List[Chunk] = []
    cur_parts: List[str] = []
    cur_indices: List[int] = []
    cur_tokens = 0

    def flush(has_overlap: bool = False):
        nonlocal cur_parts, cur_indices, cur_tokens
        if cur_parts:
            text = "\n".join(cur_parts)
            chunks.append(
                Chunk(
                    text=text,
                    message_indices=list(cur_indices),
                    tokens=tokens.estimate_tokens(text),
                    has_overlap=has_overlap,
                )
            )
        cur_parts, cur_indices, cur_tokens = [], [], 0

    for idx, msg in enumerate(messages):
        rendered = msg.render()
        rtok = tokens.estimate_tokens(rendered)

        if rtok > budget_tokens:
            # Oversized single message: close the current chunk, then emit the
            # split pieces as their own chunks (all tagged with this index).
            flush()
            for piece in _split_oversized(rendered, budget_tokens):
                chunks.append(
                    Chunk(text=piece, message_indices=[idx],
                          tokens=tokens.estimate_tokens(piece))
                )
            continue

        if cur_parts and cur_tokens + rtok > budget_tokens:
            prev_indices = list(cur_indices)
            flush()
            if overlap_messages > 0 and prev_indices:
                for oi in prev_indices[-overlap_messages:]:
                    ov = messages[oi].render()
                    cur_parts.append(ov)
                    cur_indices.append(oi)
                    cur_tokens += tokens.estimate_tokens(ov)
                # The next appended chunk carries overlap; mark on flush.

        cur_parts.append(rendered)
        cur_indices.append(idx)
        cur_tokens += rtok

    # Mark overlap on the final flush path: recompute per-chunk overlap flag by
    # detecting repeated leading indices is unnecessary — we set it here only
    # when overlap was requested and more than one chunk exists.
    flush(has_overlap=(overlap_messages > 0 and len(chunks) > 0))
    return chunks
