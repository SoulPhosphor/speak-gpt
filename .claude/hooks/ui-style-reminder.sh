#!/usr/bin/env bash
# Owner's UI styling rule (owner ruling, Oct 31 2026), injected like a
# lorebook entry: when the owner's message is about the app's look, and
# whenever a screen, layout, style or drawable file is about to be edited.
input="$(cat)"

reminder='OWNER RULE FOR ANY UI CHANGE: Never hard-code appearance. Always look in ui-style-guide.md (the styles file at the repository root) and use the style that applies. Do not ask the owner how something should look when the guide already covers it: unless the owner says to make it a different way, just follow the style that applies. In the unusual case that no style exists yet for what you are building, create a new shared style (with its dimensions) without asking the owner. Never write visual values (sizes, margins, padding, text size or color, colors, backgrounds, corner shapes) directly into a layout, drawable or Kotlin. Never copy another screen (its layout, code, values or approach) unless the owner explicitly tells you to copy that screen: some screens are converted wrong, so other screens are never a reference for how something should look or be built. When the guide does not cover something, create the shared style and add it to the guide; do not look at other screens to fill the gap. Reason: the owner wants the app to have changeable appearances, and that only works when every element gets its look from correct styles.'

copy_warning='OWNER RULE: You are reading a screen file. Never copy another screen (its layout, code, values or approach) unless the owner explicitly told you to copy that screen; some screens are converted wrong. Only ui-style-guide.md decides how something looks. Reading the screen you are changing, to change it, is fine.'

event="$(printf '%s' "$input" | jq -r '.hook_event_name // empty')"

emit() {
  jq -n --arg event "$1" --arg ctx "$2" \
    '{hookSpecificOutput: {hookEventName: $event, additionalContext: $ctx}}'
}

case "$event" in
  UserPromptSubmit)
    prompt="$(printf '%s' "$input" | jq -r '.prompt // empty')"
    if printf '%s' "$prompt" | grep -qiE '\b(ui|ux|layout|layouts|button|buttons|pop-?ups?|dialogs?|screens?|styles?|styling|toggles?|switch|switches|rows?|headers?|colou?rs?|fonts?|text size|spacing|padding|margins?|icons?|themes?|appearance|looks?|align(ed|ment)?|cent(er|re)d?|menus?|banners?|xml|design|visual|subtext|label|labels)\b'; then
      emit UserPromptSubmit "$reminder"
    fi
    ;;
  PreToolUse)
    path="$(printf '%s' "$input" | jq -r '.tool_input.file_path // empty')"
    if printf '%s' "$path" | grep -qE '/res/(layout|values|drawable|menu)[^/]*/|/ui/.*\.kt$|Activity\.kt$|Fragment\.kt$|Dialog\.kt$'; then
      emit PreToolUse "$reminder"
    fi
    ;;
  PostToolUse)
    path="$(printf '%s' "$input" | jq -r '.tool_input.file_path // .tool_input.command // empty')"
    if printf '%s' "$path" | grep -qE 'res/layout[^/ ]*/|Activity\.kt|Fragment\.kt|Dialog\.kt'; then
      emit PostToolUse "$copy_warning"
    fi
    ;;
esac
exit 0
