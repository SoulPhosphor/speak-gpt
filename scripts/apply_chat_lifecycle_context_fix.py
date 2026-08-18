from pathlib import Path

chat = Path("app/src/main/java/org/teslasoft/assistant/ui/activities/ChatActivity.kt")
source = chat.read_text()


def replace_once(old: str, new: str, label: str) -> None:
    global source
    count = source.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    source = source.replace(old, new, 1)


# ActivityThread performs final cleanup of receivers and bound services that were
# registered with an Activity Context after Activity.onDestroy. The retained
# hands-free controller must therefore bind long-lived voice infrastructure to
# the process Context, while still using ChatActivity only for UI ownership.
replace_once(
    "        ContextCompat.registerReceiver(\n"
    "            this,\n"
    "            hangUpReceiver,\n",
    "        ContextCompat.registerReceiver(\n"
    "            applicationContext,\n"
    "            hangUpReceiver,\n",
    "application-scoped Hang Up receiver",
)

replace_once(
    "        try { unregisterReceiver(hangUpReceiver) } catch (_: Exception) { /* not registered */ }\n",
    "        try { applicationContext.unregisterReceiver(hangUpReceiver) } catch (_: Exception) { /* not registered */ }\n",
    "application-scoped Hang Up receiver cleanup",
)

replace_once(
    "        recognizer = SpeechRecognizer.createSpeechRecognizer(this)\n",
    "        recognizer = SpeechRecognizer.createSpeechRecognizer(applicationContext)\n",
    "application-scoped SpeechRecognizer binding",
)

replace_once(
    "        tts = TextToSpeech(this, ttsListener)\n",
    "        tts = TextToSpeech(applicationContext, ttsListener)\n",
    "application-scoped TTS binding",
)

chat.write_text(source)
print("Moved retained voice bindings to applicationContext")
