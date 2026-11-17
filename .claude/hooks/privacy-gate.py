#!/usr/bin/env python3
"""Owner rule: no AI attribution, model names or session links in commits or on GitHub.

CLAUDE.md section 9 forbids AI co-author trailers, model attribution, session IDs and AI
conversation links in commits, pull requests, comments and other repository metadata.

Before a tool runs (PreToolUse):
  - Bash: a `git commit` (message given inline, in a heredoc, or with -F/--file) is
    refused if the message carries any of it; a `git push` is refused if any commit it
    would publish carries it.
  - GitHub tools: any call whose text (title, body, comment, commit message, file
    content) carries it is refused.

After a GitHub tool runs (PostToolUse):
  - After creating or editing a PR, comment, review or reply, the agent must read the
    published text back, because the GitHub tool itself can append a session link that
    no check on the outgoing text can see.
  - When text read back from GitHub carries a forbidden item, the agent must remove it
    at once.
"""
import json
import os
import re
import shlex
import subprocess
import sys

PROJECT = os.environ.get("CLAUDE_PROJECT_DIR") or os.getcwd()

FORBIDDEN = [
    (re.compile(r"claude\.ai/code", re.I), "a Claude session link"),
    (re.compile(r"claude\.com/claude-code", re.I), "a Claude Code attribution link"),
    (re.compile(r"\bsession_[0-9A-Za-z]{12,}"), "a session ID"),
    (re.compile(r"Claude-Session\s*:", re.I), "a session trailer"),
    (re.compile(r"Co-Authored-By\s*:[^\n]*(claude|anthropic)", re.I), "an AI co-author trailer"),
    (re.compile(r"noreply@anthropic\.com", re.I), "an AI co-author address"),
    (re.compile(r"Generated (with|by)\s*\[?\s*Claude", re.I), "an AI attribution footer"),
    (re.compile(r"\bclaude-(opus|sonnet|haiku|fable)-\d", re.I), "a model identifier"),
    (re.compile(r"\b(Opus|Sonnet|Haiku|Fable) \d+(\.\d+)?\b"), "a model name"),
]

# GitHub tools that publish text. Reads are handled separately below.
GITHUB_WRITES = re.compile(
    r"^mcp__github__(create_pull_request|update_pull_request|add_issue_comment|"
    r"add_reply_to_pull_request_comment|add_comment_to_pending_review|"
    r"pull_request_review_write|update_issue_comment|issue_write|merge_pull_request|"
    r"create_or_update_file|push_files)$"
)
GITHUB_PUBLISHES_PROSE = re.compile(
    r"^mcp__github__(create_pull_request|update_pull_request|add_issue_comment|"
    r"add_reply_to_pull_request_comment|add_comment_to_pending_review|"
    r"pull_request_review_write|update_issue_comment|issue_write)$"
)
GITHUB_READS = re.compile(r"^mcp__github__(pull_request_read|issue_read)$")


def findings(text):
    return sorted({label for pattern, label in FORBIDDEN if pattern.search(text or "")})


def strings_in(value):
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for item in value.values():
            yield from strings_in(item)
    elif isinstance(value, list):
        for item in value:
            yield from strings_in(item)


def deny(reason):
    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": reason,
        }
    }))
    sys.exit(0)


def block_after(reason):
    print(json.dumps({"decision": "block", "reason": reason}))
    sys.exit(0)


RULE = ("CLAUDE.md section 9: no AI co-author trailers, model attribution, session IDs or "
        "AI conversation links in commits, pull requests, comments or other repository metadata.")


def commit_message_text(command):
    """The command text plus the contents of any -F/--file message file it names."""
    parts = [command]
    try:
        tokens = shlex.split(command, posix=True)
    except ValueError:
        tokens = command.split()
    for i, token in enumerate(tokens):
        path = None
        if token in ("-F", "--file") and i + 1 < len(tokens):
            path = tokens[i + 1]
        elif token.startswith("--file="):
            path = token.split("=", 1)[1]
        if path and path != "-":
            full = path if os.path.isabs(path) else os.path.join(PROJECT, path)
            try:
                with open(full, encoding="utf-8", errors="replace") as handle:
                    parts.append(handle.read())
            except OSError:
                pass
    return "\n".join(parts)


def unpushed_messages():
    for base in ("@{upstream}", "origin/main"):
        result = subprocess.run(
            ["git", "log", "--format=%B", f"{base}..HEAD"],
            cwd=PROJECT, capture_output=True, text=True,
        )
        if result.returncode == 0:
            return result.stdout
    return ""


GIT_COMMIT = re.compile(r"\bgit\b[^;&|\n]*\b(commit|commit-tree|merge|tag|notes)\b")
GIT_PUSH = re.compile(r"\bgit\b[^;&|\n]*\bpush\b")


def pre_tool_use(data):
    tool = data.get("tool_name", "")
    tool_input = data.get("tool_input") or {}

    if tool == "Bash":
        command = tool_input.get("command", "")
        if GIT_COMMIT.search(command):
            found = findings(commit_message_text(command))
            if found:
                deny(f"Commit refused: the message contains {', '.join(found)}. {RULE} "
                     "Write the message without it.")
        if GIT_PUSH.search(command):
            found = findings(unpushed_messages())
            if found:
                deny(f"Push refused: a commit to be pushed contains {', '.join(found)}. {RULE} "
                     "Tell the owner which commit it is; do not rewrite shared history without "
                     "the owner's instruction.")
        return

    if GITHUB_WRITES.match(tool):
        found = findings("\n".join(strings_in(tool_input)))
        if found:
            deny(f"GitHub call refused: the text contains {', '.join(found)}. {RULE} "
                 "Send it without that text and without any attribution footer.")


def post_tool_use(data):
    tool = data.get("tool_name", "")
    if GITHUB_PUBLISHES_PROSE.match(tool):
        block_after(
            "Privacy check required now: read back exactly what was published "
            "(pull_request_read get, or the comment or review you just wrote). The GitHub tool "
            "can append a session link or attribution footer by itself. If the published text "
            f"contains any, remove it immediately with the matching update tool. {RULE}"
        )
    if GITHUB_READS.match(tool):
        found = findings("\n".join(strings_in(data.get("tool_response"))))
        if found:
            block_after(
                f"The GitHub text just read contains {', '.join(found)}. {RULE} If this session "
                "published it, remove it now with the matching update tool and read it back "
                "again. If someone else wrote it, tell the owner instead of editing it."
            )


def main():
    try:
        data = json.load(sys.stdin)
    except (json.JSONDecodeError, ValueError):
        return
    event = data.get("hook_event_name", "")
    if event == "PreToolUse":
        pre_tool_use(data)
    elif event == "PostToolUse":
        post_tool_use(data)


if __name__ == "__main__":
    main()
