#!/usr/bin/env python3
"""Owner rule: no new or changed user-facing wording without the owner's approval.

Blocks an agent from adding or changing any text in the app's string
resources (res/values*/*.xml) or any literal text in a layout or menu
(android:text, hint, title, contentDescription, tooltipText) until the owner
approves it. Reusing wording that already exists is always allowed, and so
is removing it.

The owner unlocks wording by starting a message with "Approved wording:".
That unlocks edits until the owner's next message. Every other message from
the owner locks it again. Agents can never create or touch the unlock file.
"""
import json
import os
import re
import sys

PROJECT = os.environ.get("CLAUDE_PROJECT_DIR") or os.getcwd()
TOKEN = os.path.join(PROJECT, ".claude", "wording-approval.token")
TOKEN_NAME = "wording-approval.token"
APPROVAL = re.compile(r"^\s*approved wording\s*:", re.IGNORECASE)

VALUES_FILE = re.compile(r"/res/values[^/]*/[^/]+\.xml$")
LAYOUT_FILE = re.compile(r"/res/(layout|menu)[^/]*/[^/]+\.xml$")
RES_IN_COMMAND = re.compile(r"res/(values|layout|menu)[^/\s'\"]*/")
WRITES_IN_COMMAND = re.compile(
    r"(>|\btee\b|\bsed\s+(-[a-zA-Z]*i|--in-place)|\bperl\s+-[a-zA-Z]*i|\bpython|\bcp\b|\bmv\b|"
    r"\binstall\b|\bdd\b|\bapply\b|\bpatch\b|\bcheckout\b|\brestore\b)"
)
STRING_ELEMENT = re.compile(
    r"<(string|plurals|string-array)\b[^>]*\bname\s*=\s*\"([^\"]+)\"[^>]*?(?:/>|>(.*?)</\1\s*>)",
    re.DOTALL,
)
LITERAL_ATTR = re.compile(
    r"\b(?:android|app):(text|hint|title|contentDescription|tooltipText|summary)\s*=\s*\"([^\"]*)\""
)

REMINDER = (
    "OWNER RULE (wording): Never write new or changed user-facing text yourself: no new strings, "
    "no changed strings, no stand-in or placeholder text, and no code that depends on wording the "
    "owner has not approved. Ask the owner for the exact wording first, one question at a time, "
    "with your recommendation. Reusing wording that already exists in strings.xml needs no approval. "
    "The owner unlocks wording by starting a message with \"Approved wording:\"."
)


def unlocked():
    return os.path.isfile(TOKEN)


def emit(obj):
    print(json.dumps(obj))
    sys.exit(0)


def deny(reason):
    emit({"hookSpecificOutput": {
        "hookEventName": "PreToolUse",
        "permissionDecision": "deny",
        "permissionDecisionReason": reason,
    }})


def strings(text):
    out = {}
    for match in STRING_ELEMENT.finditer(text or ""):
        out[(match.group(1), match.group(2))] = (match.group(3) or "").strip()
    return out


def literals(text):
    return {(a, v) for a, v in LITERAL_ATTR.findall(text or "") if v and not v.startswith(("@", "?"))}


def read(path):
    try:
        with open(path, encoding="utf-8") as f:
            return f.read()
    except OSError:
        return ""


def after_edit(tool, data, before):
    if tool == "Write":
        return data.get("content", "")
    edits = data.get("edits") if tool == "MultiEdit" else [data]
    text = before
    for e in edits or []:
        old, new = e.get("old_string", ""), e.get("new_string", "")
        if e.get("replace_all"):
            text = text.replace(old, new)
        else:
            text = text.replace(old, new, 1) if old else new + text
    return text


def changed_wording(path, before, after):
    if VALUES_FILE.search(path):
        old = strings(before)
        return sorted(name for key, text in strings(after).items()
                      for name in [key[1]] if old.get(key) != text)
    if LAYOUT_FILE.search(path):
        return sorted("%s=\"%s\"" % pair for pair in literals(after) - literals(before))
    return []


def pre_tool_use(event):
    tool = event.get("tool_name", "")
    data = event.get("tool_input") or {}
    if TOKEN_NAME in json.dumps(data):
        deny("Only the owner can approve wording. The approval file cannot be created, read or changed by an agent.")
    if unlocked():
        return
    if tool == "Bash":
        command = data.get("command", "")
        if RES_IN_COMMAND.search(command) and WRITES_IN_COMMAND.search(command):
            deny("This command may write to the app's string or layout files, and wording is locked. "
                 "Use the Edit tool for those files so the wording check can see exactly what changes, "
                 "and ask the owner before adding or changing any user-facing text.")
        return
    if tool not in ("Edit", "Write", "MultiEdit"):
        return
    path = data.get("file_path", "")
    if not (VALUES_FILE.search(path) or LAYOUT_FILE.search(path)):
        return
    before = read(path)
    found = changed_wording(path, before, after_edit(tool, data, before))
    if found:
        deny("New or changed user-facing wording is locked until the owner approves it: "
             + ", ".join(found[:12])
             + ". Ask the owner for the exact wording first (one question, with your recommendation). "
               "Do not write stand-in text, and do not build code that depends on it.")


def main():
    try:
        event = json.load(sys.stdin)
    except Exception:
        return
    name = event.get("hook_event_name", "")
    if name == "UserPromptSubmit":
        if APPROVAL.search(event.get("prompt", "") or ""):
            with open(TOKEN, "w", encoding="utf-8") as f:
                f.write("approved by the owner's message\n")
            emit({"hookSpecificOutput": {"hookEventName": "UserPromptSubmit", "additionalContext":
                  "The owner approved wording in this message. Write exactly the approved wording, nothing else."}})
        elif os.path.exists(TOKEN):
            os.remove(TOKEN)
    elif name == "SessionStart":
        if os.path.exists(TOKEN):
            os.remove(TOKEN)
        emit({"hookSpecificOutput": {"hookEventName": "SessionStart", "additionalContext": REMINDER}})
    elif name == "PreToolUse":
        pre_tool_use(event)


if __name__ == "__main__":
    main()
