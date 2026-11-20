#!/usr/bin/env python3
"""Owner rule: AMOLED work is paused. Never add or change AMOLED code.

CLAUDE.md section 5 pauses AMOLED-specific styling and forbids expanding the
per-screen recoloring mechanism. This hook enforces it before a tool runs:

  - Edit / MultiEdit: refused when the new text has more "amoled" mentions
    (any capitalization) than the text it replaces.
  - Write: refused when the new file has more "amoled" mentions than the file
    on disk.
  - Bash: refused when the command mentions "amoled" and writes files (sed -i,
    perl -i, tee, output redirection, a script opening a file for writing,
    mv/cp, or a git command that rewrites the working tree). Searching and
    reading stay allowed.

Removing AMOLED code is not blocked, so an owner-ordered removal still works.
Markdown files and this hook itself are exempt so the written rules can be
maintained. If the owner lifts the pause, delete this hook and its entry in
.claude/settings.json.
"""
import json
import os
import re
import sys

WORD = re.compile(r"amoled", re.I)

BASH_WRITES = re.compile(
    r"sed\s[^|;&]*-i"
    r"|perl\s[^|;&]*-i"
    r"|\btee\b"
    r"|open\([^)]*['\"][wax+]"
    r"|\.write(_text|_bytes)?\("
    r"|(?<![0-9&<>-])>(?![&=])"
    r"|\b(mv|cp|install|patch)\s"
    r"|\bgit\s+(apply|am|checkout|restore|stash|cherry-pick|revert|merge|rebase)\b"
)

REASON = (
    "Blocked: AMOLED work is paused by the owner (CLAUDE.md section 5). Do not add "
    "or change AMOLED code, colors, or recoloring, even to make new UI match. Leave "
    "the AMOLED path alone and tell the owner about any mismatch instead."
)


def count(text):
    return len(WORD.findall(text or ""))


def exempt(path):
    path = path or ""
    return path.lower().endswith(".md") or os.path.basename(path) == "amoled-gate.py"


def deny():
    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": REASON,
        }
    }))
    sys.exit(0)


def main():
    try:
        data = json.load(sys.stdin)
    except Exception:
        return
    tool = data.get("tool_name", "")
    inp = data.get("tool_input") or {}

    if tool == "Edit":
        if exempt(inp.get("file_path")):
            return
        if count(inp.get("new_string")) > count(inp.get("old_string")):
            deny()
    elif tool == "MultiEdit":
        if exempt(inp.get("file_path")):
            return
        edits = inp.get("edits") or []
        added = sum(count(e.get("new_string")) for e in edits)
        removed = sum(count(e.get("old_string")) for e in edits)
        if added > removed:
            deny()
    elif tool == "Write":
        path = inp.get("file_path")
        if exempt(path):
            return
        try:
            with open(path, encoding="utf-8", errors="replace") as f:
                before = count(f.read())
        except OSError:
            before = 0
        if count(inp.get("content")) > before:
            deny()
    elif tool == "Bash":
        command = inp.get("command") or ""
        if WORD.search(command) and BASH_WRITES.search(command):
            deny()


if __name__ == "__main__":
    main()
