# N2 Error Investigation

Owner-reported: N2 ("server did not respond in time") happened twice. The
owner checked the Error Log afterward and is ~90% sure the N2 failures were
NOT in it — only two app-exit entries were there. The owner cleared the log
before this could be confirmed against the actual entries, so the two
specific occurrences are gone. This file exists so the next session doesn't
have to re-derive the below from scratch.

## What the code says should happen

Every N2 (and every generation error) is supposed to write to the Error Log
unconditionally. `ChatActivity.logGenerationError()` is called from the
single catch block in `generateResponse()` (`ChatActivity.kt`) and writes an
entry to the `"crash"` channel (`Logger.log(this, "crash", "GenError",
"error", ...)`) — internally named `"crash"`, but this is the same channel
the Error Log screen reads (`Logger.getCrashLog`), titled "Error Log" in the
UI (`title_crash_log` in `strings.xml`). This write is not gated by any
logging toggle — it always runs.

## Two candidate mechanisms for why an entry could go missing

Found by reading `Logger.kt` and `EncryptedPreferences.kt`. Neither is
confirmed against a real occurrence yet (the evidence was cleared before it
could be checked) — both are plausible based on how the code is written.

### 1. Unsynchronized read-modify-write on the Error Log

`Logger.log()` (`Logger.kt:144`) does:
1. `getCrashLog(context)` — read the entire stored log string
2. append the new entry, trim by `trimByEntries()`
3. `setCrashLog(context, log)` — write the whole string back

Nothing locks this sequence. If two writes to the `"crash"` channel land on
different threads close together, the second write's read happens before
the first write's write, so the second write overwrites the first —
silently dropping the first entry. No exception, no warning, just a vanished
entry.

Other things in the app that write to the same `"crash"` channel and could
race with a `GenError` entry: `CrashHandler`, `RenameJournal`,
`ChatPreferences` (corrupt-data / rename warnings), `ProfileImagesActivity`,
and `Logger.collectAndLogLastExitReason` (the process-exit recorder that
also writes to `"crash"` for app-breaking exits, e.g. ANR/native
crash/low-memory kill — this one specifically could explain "log only had
the app-exit entries" if it overwrote a `GenError` entry that had just been
written moments before the exit).

### 2. The write itself isn't guaranteed to reach disk

`EncryptedPreferences.setEncryptedPreference()` (`EncryptedPreferences.kt:71-75`)
calls `edit { putString(...) }`. The `androidx.core.content.edit` Kotlin
extension defaults to `commit = false`, i.e. `apply()` — an async, queued
write, not an immediate `commit()`. If the process dies abruptly (ANR,
native crash, low-memory kill) shortly after the write is queued but before
it flushes to disk, the entry never reaches disk.

This is not purely hypothetical for this owner: the Performance Log sample
pasted in this same investigation (2026-07-19, ~14:08–14:32) showed native
heap climbing to ~1.7–1.9GB with repeated `onTrimMemory` events in that
session — real memory pressure, the exact condition under which an abrupt
kill (and a lost `apply()` write) is more likely.

## What an entry looks like when it IS written successfully

```
[N2] The server did not respond in time. The connection may be slow or the model busy — try again, or switch models.
Profile: <endpoint label>
Base URL: <host>
Model: <model>
Voice: active/inactive
Trigger: message
Screen: on/off
Network: <wifi/cellular/none/...>
Power save: on/off
Detail: <exception message>
```

## Next steps when N2 happens again

1. Open Alerts, Errors & Logs → Error Log **before clearing anything**.
2. Check whether a `GenError` `[N2]` entry is present.
   - If **present**: read its `Network:`/`Screen:` fields — that's the real
     diagnostic data (e.g. confirms/rules out the same "radio asleep"
     pattern already fixed for hands-free).
   - If **absent**: that confirms the Error Log write is being lost, and
     points at fixing #1 and/or #2 above (not yet done — investigation
     only, no fix implemented as of this writing).
3. Paste the raw log contents (or confirm absence) before doing anything
   else with the log screen.
