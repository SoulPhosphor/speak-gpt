"""Archiver evaluation harness (Phase 3).

A repeatable, offline-by-default evaluation harness for the memory Archiver.
It never touches the live memory database: it reads synthetic conversation
fixtures, builds the prompts the production Archiver would build, runs them
through a pluggable model runner (recorded/offline or live), parses and scores
the results exactly the way the production parser would, and writes a report.

See ``../README.md`` and ``../methodology.md`` for how it fits together and
what its numbers mean.
"""

__all__ = [
    "tokens",
    "chunking",
    "prompts",
    "parser",
    "runner",
    "scoring",
    "config",
    "report",
]
