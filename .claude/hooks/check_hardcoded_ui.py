#!/usr/bin/env python3
"""Blocks hard-coded UI appearance.

Every element must get its look from shared styles (ui-style-guide.md). This
checker looks only at lines being ADDED or CHANGED and fails when one of them
writes a visual value directly into a layout, menu, drawable or Kotlin file:
sizes, margins, padding, text size, colors, fonts, text styling, corner shapes.

Values belong in res/values (dimens.xml, colors.xml, styles.xml, themes.xml,
attrs.xml), which this checker does not inspect.

Modes:
  --base REF      check everything this branch adds compared with REF (CI)
  --staged        check what is staged for commit
  --claude-hook   read a Claude Code PreToolUse event on stdin and block
                  git commit, git push, gh pr merge and GitHub merge/push tools
There is no bypass.
"""

import json
import re
import subprocess
import sys

DEFAULT_BASE = "origin/beta/new-client"

XML_UI = re.compile(r"(^|/)src/(?!test|androidTest)[^/]+/res/(layout|menu|drawable)[^/]*/[^/]+\.xml$")
KOTLIN_UI = re.compile(r"(^|/)src/(?!test|androidTest)[^/]+/(java|kotlin)/.+\.kt$")

DIM = r"-?\d+(\.\d+)?(dp|dip|sp|px|pt|mm|in)"
ZERO_DIM = re.compile(r'="-?0+(\.0+)?(dp|dip|sp|px|pt|mm|in)"')

XML_RULES = [
    ("literal size", re.compile(r'="' + DIM + r'"')),
    ("literal color", re.compile(r'="#[0-9a-fA-F]{3,8}"')),
    ("framework color", re.compile(r'="@android:color/(?!transparent")[^"]+"')),
]
LAYOUT_ONLY_RULES = [
    ("text styling written in the layout",
     re.compile(r'android:(textStyle|fontFamily|typeface|textAllCaps|letterSpacing|'
                r'lineSpacingMultiplier|lineSpacingExtra|shadowColor|shadowRadius)\s*=')),
]
VECTOR_SIZE_ATTR = re.compile(r'android:(width|height|viewportWidth|viewportHeight)\s*=')

NUM = r"-?\d+(\.\d+)?[fFL]?"
KOTLIN_RULES = [
    ("literal color", re.compile(r"Color\.(parseColor|rgb|argb|valueOf)\s*\(")),
    ("literal color", re.compile(r"\bColor\.(WHITE|BLACK|RED|GREEN|BLUE|GRAY|DKGRAY|LTGRAY|YELLOW|CYAN|MAGENTA)\b")),
    ("literal color", re.compile(r"(?i)(colou?r|tint|background).*\b0x[0-9a-f]{8}\b|\b0x[0-9a-f]{8}\b.*(colou?r|tint|background)")),
    ("literal padding", re.compile(r"setPadding(Relative)?\s*\(.*(^|[(,+\-*/\s])[1-9]\d*(\.\d+)?[fF]?\s*[,)+\-*/]")),
    ("literal margin", re.compile(r"setMargins(Relative)?\s*\(.*(^|[(,+\-*/\s])[1-9]\d*(\.\d+)?[fF]?\s*[,)+\-*/]")),
    ("literal margin or size",
     re.compile(r"(?i)params\??\.(width|height|leftMargin|topMargin|rightMargin|bottomMargin|marginStart|marginEnd)\s*=\s*.*\b[1-9]\d*\b")),
    ("literal size", re.compile(r"\b(dp|dpToPx|toPx|px|spToPx)\s*\(\s*" + NUM + r"\s*\)")),
    ("literal size", re.compile(r"\bapplyDimension\s*\([^,]+,\s*" + NUM)),
    ("literal text size", re.compile(r"(setTextSize\s*\(.*\b[1-9]\d*|\btextSize\s*=\s*" + NUM + r")")),
    ("literal corner shape", re.compile(r"\b(cornerRadius|cornerRadii)\s*=\s*.*\b[1-9]\d*")),
    ("literal corner shape", re.compile(r"setCornerRadius\s*\(.*\b[1-9]\d*")),
    ("shape built in code", re.compile(r"\bGradientDrawable\s*\(")),
    ("literal elevation", re.compile(r"\b(elevation|translationZ)\s*=\s*(?!-?0+(\.0+)?[fF]?(?![\d.]))" + NUM + r"(?![\w.])")),
    ("literal font", re.compile(r"Typeface\.(create|DEFAULT_BOLD|BOLD|ITALIC|MONOSPACE|SERIF|SANS_SERIF)\b")),
]


def git(*args):
    return subprocess.run(["git", *args], capture_output=True, text=True)


def is_comment(path, text):
    s = text.strip()
    if path.endswith(".kt"):
        return s.startswith("//") or s.startswith("*") or s.startswith("/*")
    return s.startswith("<!--")


def file_is_vector(path, ref):
    shown = git("show", f"{ref}:{path}") if ref else None
    content = shown.stdout if shown is not None and shown.returncode == 0 else ""
    if not content:
        try:
            with open(path, encoding="utf-8", errors="replace") as fh:
                content = fh.read()
        except OSError:
            return False
    return "<vector" in content


def check_line(path, text, vector):
    if is_comment(path, text):
        return []
    found = []
    if XML_UI.search(path):
        for label, rx in XML_RULES:
            if label == "literal size" and vector and VECTOR_SIZE_ATTR.search(text):
                continue
            for m in rx.finditer(text):
                if label == "literal size" and ZERO_DIM.fullmatch(m.group(0)):
                    continue
                found.append(label)
                break
        if "/res/layout" in path or "/res/menu" in path:
            for label, rx in LAYOUT_ONLY_RULES:
                if rx.search(text):
                    found.append(label)
    elif KOTLIN_UI.search(path):
        for label, rx in KOTLIN_RULES:
            if rx.search(text):
                found.append(label)
    return sorted(set(found))


def added_lines_from_diff(diff_text):
    path, lineno = None, 0
    for raw in diff_text.splitlines():
        if raw.startswith("+++ "):
            target = raw[4:]
            path = target[2:] if target.startswith("b/") else None
            continue
        if raw.startswith("@@"):
            m = re.search(r"\+(\d+)", raw)
            lineno = int(m.group(1)) if m else 0
            continue
        if path is None:
            continue
        if raw.startswith("+"):
            yield path, lineno, raw[1:]
            lineno += 1
        elif not raw.startswith("-"):
            lineno += 1


def scan_diff(diff_args, ref_for_files):
    diff = git("diff", "-U0", "--no-color", "--no-ext-diff", "--diff-filter=AMRC", *diff_args)
    if diff.returncode != 0:
        print(diff.stderr, file=sys.stderr)
        sys.exit(3)
    problems, vectors = [], {}
    for path, lineno, text in added_lines_from_diff(diff.stdout):
        if not (XML_UI.search(path) or KOTLIN_UI.search(path)):
            continue
        if path not in vectors:
            vectors[path] = path.endswith(".xml") and "/res/drawable" in path and file_is_vector(path, ref_for_files)
        for label in check_line(path, text, vectors[path]):
            problems.append((path, lineno, label, text.strip()))
    return problems


def scan_contents(files):
    problems = []
    for path, content in files:
        vector = "<vector" in content
        for i, text in enumerate(content.splitlines(), 1):
            for label in check_line(path, text, vector):
                problems.append((path, i, label, text.strip()))
    return problems


def report(problems):
    out = [
        "",
        "STOP. HARD-CODED UI IS NOT ALLOWED. THIS CANNOT BE COMMITTED, PUSHED OR MERGED.",
        "",
        "The owner's rule: never write sizes, margins, padding, text size, colors,",
        "fonts, backgrounds or corner shapes directly into a layout, drawable or Kotlin.",
        "Use the shared style from ui-style-guide.md. If no style exists, create a",
        "shared style or resource in res/values and add it to ui-style-guide.md.",
        "Do not copy another screen. There is no bypass.",
        "",
    ]
    for path, lineno, label, text in problems:
        out.append(f"  {path}:{lineno}  [{label}]  {text[:160]}")
    out.append("")
    out.append(f"{len(problems)} hard-coded UI line(s) found.")
    return "\n".join(out)


def base_range(ref):
    if git("rev-parse", "--verify", "--quiet", ref).returncode != 0:
        return None
    mb = git("merge-base", ref, "HEAD")
    return mb.stdout.strip() if mb.returncode == 0 else None


def run_hook():
    try:
        event = json.load(sys.stdin)
    except Exception:
        return 0
    tool = event.get("tool_name", "")
    tin = event.get("tool_input", {}) or {}
    problems = []

    if tool == "Bash":
        cmd = tin.get("command", "")
        is_commit = re.search(r"\bgit\b[^;&|]*\bcommit\b", cmd)
        is_push = re.search(r"\bgit\b[^;&|]*\bpush\b", cmd) or re.search(r"\bgh\s+pr\s+merge\b", cmd) \
            or re.search(r"\bgit\b[^;&|]*\bmerge\b", cmd)
        if not (is_commit or is_push):
            return 0
        if is_commit:
            if re.search(r"\bcommit\b[^;&|]*(\s-[a-zA-Z]*a[a-zA-Z]*\b|--all\b)", cmd):
                problems += scan_diff(["HEAD"], None)
            else:
                problems += scan_diff(["--cached"], None)
        if is_push:
            mb = base_range(DEFAULT_BASE)
            if mb:
                problems += scan_diff([mb, "HEAD"], "HEAD")
    elif tool in ("mcp__github__merge_pull_request", "mcp__github__enable_pr_auto_merge"):
        mb = base_range(DEFAULT_BASE)
        if mb:
            problems += scan_diff([mb, "HEAD"], "HEAD")
    elif tool == "mcp__github__push_files":
        files = [(f.get("path", ""), f.get("content", "")) for f in tin.get("files", []) or []]
        problems += scan_contents(files)
    elif tool == "mcp__github__create_or_update_file":
        problems += scan_contents([(tin.get("path", ""), tin.get("content", ""))])
    else:
        return 0

    seen, unique = set(), []
    for p in problems:
        if p not in seen:
            seen.add(p)
            unique.append(p)
    if unique:
        print(report(unique), file=sys.stderr)
        return 2
    return 0


def main(argv):
    if "--claude-hook" in argv:
        return run_hook()
    if "--staged" in argv:
        problems = scan_diff(["--cached"], None)
    else:
        ref = argv[argv.index("--base") + 1] if "--base" in argv else DEFAULT_BASE
        mb = base_range(ref)
        if not mb:
            print(f"Cannot find base {ref}; fetch it first.", file=sys.stderr)
            return 3
        problems = scan_diff([mb, "HEAD"], "HEAD")
    if problems:
        print(report(problems), file=sys.stderr)
        return 1
    print("No hard-coded UI found in the added lines.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
