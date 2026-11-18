#!/usr/bin/env python3
"""Owner rule: user-facing wording comes from the owner.

Reminds an agent at session start how wording is approved. It does not block
edits: the owner's chat wording or wording written in a repository document is
already approved, and only the agent can tell that wording apart from wording
it would be inventing itself.
"""
import json
import sys

REMINDER = (
    "OWNER RULE (wording): Wording the owner gives in chat, or that is written in a document in "
    "the repository, is approved and may be used exactly as given (correct the owner's spelling: "
    "their speech-to-text makes mistakes). Never invent new or changed user-facing text yourself: "
    "no new strings, no changed strings, no stand-in or placeholder text, and no code that depends "
    "on wording the owner has not given. When wording is missing, ask the owner for the exact "
    "wording first, one question at a time, with your recommendation. Reusing wording that already "
    "exists in strings.xml needs no approval."
)


def main():
    try:
        event = json.load(sys.stdin)
    except Exception:
        return
    if event.get("hook_event_name", "") == "SessionStart":
        print(json.dumps({"hookSpecificOutput": {
            "hookEventName": "SessionStart", "additionalContext": REMINDER}}))


if __name__ == "__main__":
    main()
