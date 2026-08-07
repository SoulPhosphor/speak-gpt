"""Response parsing — a faithful port of the production ``ArchivistResponseParser``.

The harness must judge exactly what the *production* app would accept, so this
module mirrors the Kotlin parser at
``app/.../preferences/memory/archivist/ArchivistResponseParser.kt`` byte-for-
behaviour: the same scope set, the same "content required, scope must be known,
otherwise DROP and count" gate, the same "absent/unknown type_id becomes No Type
rather than a drop", the same tag normalization, and the same defensive bounds.
``tests/test_parser_parity.py`` pins this with shared vectors; if the Kotlin
parser changes, that test must be updated in lockstep.

It also adds ``parse_envelope`` for the §8.7 *structured* response contract
(``general_memories`` / ``companion_memories`` / ``model_rules``) so the harness
can compare the current plain-JSON schema against the structured-output schema
Phase 4 may adopt. Both parse paths normalize to one internal ``Candidate`` list
with a derived extraction *stream* (general vs companion), which is what the
Analyze-For product concept classifies proposals into.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import List, Optional
import json

# Mirrors ArchivistResponseParser.SCOPES.
SCOPES = {"global", "real_life", "companion", "project", "world", "campaign", "rp_character"}

# Mirrors the production defensive bounds.
MAX_MEMORIES_PER_CONVERSATION = 40
MAX_RULES_PER_CONVERSATION = 5
MAX_TAGS_PER_MEMORY = 8

# Roleplay scopes live under the "General" browser tab but keep their own
# targets; they are not companion memories. Companion is the only scope that
# routes to the Companion stream (revision 25 §9 / counterplan §8.2).
COMPANION_SCOPES = {"companion"}


def stream_of(scope: str) -> str:
    """The Analyze-For destination stream a memory scope routes to."""
    return "companion" if scope in COMPANION_SCOPES else "general"


@dataclass
class Candidate:
    """One validated memory proposal, normalized across schema shapes."""

    content: str
    scope: str
    type_id: Optional[str] = None
    tags: List[str] = field(default_factory=list)
    target: Optional[str] = None

    @property
    def stream(self) -> str:
        return stream_of(self.scope)


@dataclass
class Rule:
    text: str


@dataclass
class Parsed:
    memories: List[Candidate]
    rules: List[Rule]
    dropped: int
    # True when the outer object could not be isolated/parsed at all (the
    # UNREADABLE failure class). The production runner tags this as a
    # per-conversation failure rather than "no memories found".
    unreadable: bool = False


class NoJsonObject(ValueError):
    """Raised when no outer JSON object can be isolated (mirrors the Kotlin
    ``require(start in 0 until end)``)."""


def extract_json_object(raw: str) -> str:
    """Take the outermost ``{...}`` — models often wrap JSON in prose or a
    markdown fence. Mirrors ``ArchivistResponseParser.extractJsonObject``."""
    start = raw.find("{")
    end = raw.rfind("}")
    if not (0 <= start < end):
        raise NoJsonObject("no JSON object in response")
    return raw[start : end + 1]


def _clean_tags(raw_tags) -> List[str]:
    tags: List[str] = []
    if isinstance(raw_tags, list):
        for t in raw_tags:
            if not isinstance(t, str):
                continue
            tag = t.strip()
            if (
                tag
                and len(tag) <= 64
                and all(x.lower() != tag.lower() for x in tags)
                and len(tags) < MAX_TAGS_PER_MEMORY
            ):
                tags.append(tag)
    return tags


def parse(raw: str) -> Parsed:
    """Parse the current combined ``{memories:[...], model_rules:[...]}`` schema.

    Faithful port of ``ArchivistResponseParser.parse``. On an unreadable outer
    object the production code throws and the runner tags UNREADABLE; here we
    return ``unreadable=True`` so the scorer can distinguish "the model failed"
    from "the model correctly found nothing".
    """
    try:
        obj = json.loads(extract_json_object(raw))
        if not isinstance(obj, dict):
            raise ValueError("outer JSON is not an object")
    except (NoJsonObject, ValueError):
        return Parsed(memories=[], rules=[], dropped=0, unreadable=True)

    dropped = 0
    memories: List[Candidate] = []
    mem_array = obj.get("memories")
    if isinstance(mem_array, list):
        for o in mem_array:
            if not isinstance(o, dict):
                dropped += 1
                continue
            content = str(o.get("content", "")).strip()
            scope = str(o.get("scope", "")).strip().lower()
            type_id = str(o.get("type_id", "")).strip() or None
            if not content or scope not in SCOPES:
                dropped += 1
                continue
            if len(memories) >= MAX_MEMORIES_PER_CONVERSATION:
                dropped += 1
                continue
            target = str(o.get("target", "")).strip() or None
            memories.append(
                Candidate(content=content, scope=scope, type_id=type_id,
                          tags=_clean_tags(o.get("tags")), target=target)
            )

    rules: List[Rule] = []
    rule_array = obj.get("model_rules")
    if isinstance(rule_array, list):
        for o in rule_array:
            text = ""
            if isinstance(o, dict):
                text = str(o.get("text", "")).strip()
            if not text:
                dropped += 1
                continue
            if len(rules) >= MAX_RULES_PER_CONVERSATION:
                dropped += 1
                continue
            rules.append(Rule(text=text))

    return Parsed(memories=memories, rules=rules, dropped=dropped)


def parse_envelope(raw: str) -> Parsed:
    """Parse the §8.7 structured envelope
    (``general_memories`` / ``companion_memories`` / ``model_rules``).

    Applies the same content-required / known-scope / bounds gate so the two
    schemas are scored on equal terms. General entries carry an explicit scope;
    companion entries are forced to scope ``companion`` and must name a
    ``companion_target`` (a missing target is dropped, mirroring "never target a
    companion by ambiguous matching").
    """
    try:
        obj = json.loads(extract_json_object(raw))
        if not isinstance(obj, dict):
            raise ValueError("outer JSON is not an object")
    except (NoJsonObject, ValueError):
        return Parsed(memories=[], rules=[], dropped=0, unreadable=True)

    dropped = 0
    memories: List[Candidate] = []

    for o in obj.get("general_memories", []) or []:
        if not isinstance(o, dict):
            dropped += 1
            continue
        content = str(o.get("content", "")).strip()
        scope = str(o.get("scope", "")).strip().lower()
        if not content or scope not in SCOPES or scope == "companion":
            dropped += 1
            continue
        if len(memories) >= MAX_MEMORIES_PER_CONVERSATION:
            dropped += 1
            continue
        memories.append(
            Candidate(
                content=content,
                scope=scope,
                type_id=(str(o.get("suggested_type", "")).strip() or None),
                tags=_clean_tags(o.get("tags")),
                target=(str(o.get("target", "")).strip() or None),
            )
        )

    for o in obj.get("companion_memories", []) or []:
        if not isinstance(o, dict):
            dropped += 1
            continue
        content = str(o.get("content", "")).strip()
        companion_target = str(o.get("companion_target", "")).strip()
        if not content or not companion_target:
            dropped += 1
            continue
        if len(memories) >= MAX_MEMORIES_PER_CONVERSATION:
            dropped += 1
            continue
        memories.append(
            Candidate(
                content=content,
                scope="companion",
                type_id=(str(o.get("suggested_type", "")).strip() or None),
                tags=_clean_tags(o.get("tags")),
                target=companion_target,
            )
        )

    rules: List[Rule] = []
    for o in obj.get("model_rules", []) or []:
        text = ""
        if isinstance(o, dict):
            # §8.7 names the field "content"; the current schema uses "text".
            # Accept either so a structured run is not penalized for the label.
            text = str(o.get("content", o.get("text", ""))).strip()
        if not text:
            dropped += 1
            continue
        if len(rules) >= MAX_RULES_PER_CONVERSATION:
            dropped += 1
            continue
        rules.append(Rule(text=text))

    return Parsed(memories=memories, rules=rules, dropped=dropped)
