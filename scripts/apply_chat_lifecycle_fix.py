from pathlib import Path

CHAT = Path("app/src/main/java/org/teslasoft/assistant/ui/activities/ChatActivity.kt")
POLICY = Path("app/src/main/java/org/teslasoft/assistant/service/HandsFreeActivityLifetimePolicy.kt")
TEST = Path("app/src/test/java/org/teslasoft/assistant/service/HandsFreeActivityLifetimePolicyTest.kt")

source = CHAT.read_text()


def replace_once(old: str, new: str, label: str) -> None:
    global source
    count = source.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    source = source.replace(old, new, 1)


def replace_exact_count(old: str, new: str, expected: int, label: str) -> None:
    global source
    count = source.count(old)
    if count != expected:
        raise RuntimeError(f"{label}: expected {expected} matches, found {count}")
    source = source.replace(old, new)


replace_once(
    "import org.teslasoft.assistant.service.GenerationForegroundService\nimport org.teslasoft.assistant.service.HandsFreeService\n",
    "import org.teslasoft.assistant.service.GenerationForegroundService\n"
    "import org.teslasoft.assistant.service.HandsFreeActivityLifetimePolicy\n"
    "import org.teslasoft.assistant.service.HandsFreeService\n",
    "policy import",
)

replace_once(
    "        private val responseLifecycleRecorderAttribute =\n"
    "            AttributeKey<ResponseLifecycleRecorder>(\"ResponseLifecycleRecorder\")\n\n",
    "        private val responseLifecycleRecorderAttribute =\n"
    "            AttributeKey<ResponseLifecycleRecorder>(\"ResponseLifecycleRecorder\")\n\n"
    "        // Hands-free is still driven by ChatActivity's recognizer/TTS/coroutine\n"
    "        // controller. Android may destroy the window while the foreground\n"
    "        // HandsFreeService remains alive, so retain exactly the Activity that\n"
    "        // owns that live controller until the user explicitly stops the session\n"
    "        // (or the loop ends on its own). This is intentional lifetime ownership,\n"
    "        // not a UI cache: replacement screens never become the controller.\n"
    "        @SuppressLint(\"StaticFieldLeak\")\n"
    "        @Volatile\n"
    "        private var handsFreeSessionOwner: ChatActivity? = null\n\n",
    "session owner field",
)

replace_once(
    "    private var handsFreeUserSpoke = false\n"
    "    private var handsFreeStopped = false\n",
    "    private var handsFreeUserSpoke = false\n"
    "    private var handsFreeStopped = false\n"
    "    // Set when Android destroys this Activity while it still owns a live\n"
    "    // hands-free controller. Voice resources are then released by the real\n"
    "    // session-ending funnel, not by the window lifecycle callback.\n"
    "    private var deferredHandsFreeDestroyCleanup = false\n",
    "deferred cleanup field",
)

replace_once(
    "    private fun isHandsFreeEngaged(): Boolean =\n"
    "        preferences?.getHandsFreeMode() == true && !handsFreeStopped\n\n",
    "    private fun isHandsFreeEngaged(): Boolean =\n"
    "        preferences?.getHandsFreeMode() == true && !handsFreeStopped\n\n"
    "    private fun isHandsFreeSessionOwner(): Boolean = handsFreeSessionOwner === this\n\n"
    "    private fun claimHandsFreeSessionOwnership() {\n"
    "        if (handsFreeSessionOwner == null || handsFreeSessionOwner === this) {\n"
    "            handsFreeSessionOwner = this\n"
    "        }\n"
    "    }\n\n"
    "    private fun releaseHandsFreeSessionOwnership() {\n"
    "        if (handsFreeSessionOwner === this) handsFreeSessionOwner = null\n"
    "    }\n\n"
    "    private fun retainedHandsFreeOwnerForThisChat(): ChatActivity? {\n"
    "        val owner = handsFreeSessionOwner ?: return null\n"
    "        if (owner === this || owner.chatId != chatId || !owner.isHandsFreeEngaged()) return null\n"
    "        return owner\n"
    "    }\n\n"
    "    private fun shouldPreserveHandsFreeControllerOnDestroy(): Boolean =\n"
    "        HandsFreeActivityLifetimePolicy.shouldPreserveOnDestroy(\n"
    "            isSessionOwner = isHandsFreeSessionOwner(),\n"
    "            handsFreeEnabled = preferences?.getHandsFreeMode() == true,\n"
    "            loopStopped = handsFreeStopped\n"
    "        )\n\n"
    "    private fun handsFreeControllerCanRun(): Boolean =\n"
    "        HandsFreeActivityLifetimePolicy.controllerCanRun(\n"
    "            activityDestroyed = isDestroyed,\n"
    "            isSessionOwner = isHandsFreeSessionOwner(),\n"
    "            loopStopped = handsFreeStopped,\n"
    "            cancelled = cancelState\n"
    "        )\n\n"
    "    private fun backgroundVoiceControllerAvailable(): Boolean =\n"
    "        !isDestroyed || isHandsFreeSessionOwner()\n\n"
    "    /** A replacement ChatActivity can display the same chat while the old,\n"
    "     *  destroyed instance still owns the live voice controller. Stop/Hang Up\n"
    "     *  must reach that owner rather than merely tearing down the replacement\n"
    "     *  screen's idle state. */\n"
    "    private fun cancelHandsFreeOwnerOrSelf(source: String) {\n"
    "        val owner = retainedHandsFreeOwnerForThisChat()\n"
    "        if (owner != null) {\n"
    "            owner.cancelAllAiActivity(\"$source (forwarded from replacement screen)\")\n"
    "            handsFreeStopped = true\n"
    "            micIdle()\n"
    "            refreshConversationButton()\n"
    "            return\n"
    "        }\n"
    "        cancelAllAiActivity(source)\n"
    "    }\n\n"
    "    /** Release the Activity-bound voice objects that were deliberately kept\n"
    "     *  across onDestroy for a live hands-free controller. This runs only when\n"
    "     *  the session itself has ended. */\n"
    "    private fun cleanupDeferredHandsFreeDestroy() {\n"
    "        if (!deferredHandsFreeDestroyCleanup) return\n"
    "        deferredHandsFreeDestroyCleanup = false\n"
    "        releaseDestroyedActivityVoiceResources()\n"
    "    }\n\n"
    "    private fun releaseDestroyedActivityVoiceResources() {\n"
    "        try { tts?.stop() } catch (_: Exception) { /* ignore */ }\n"
    "        try { tts?.shutdown() } catch (_: Exception) { /* ignore */ }\n"
    "        tts = null\n"
    "        try {\n"
    "            if (mediaPlayer?.isPlaying == true) mediaPlayer?.stop()\n"
    "            mediaPlayer?.reset()\n"
    "            mediaPlayer?.release()\n"
    "        } catch (_: Exception) { /* ignore */ }\n"
    "        mediaPlayer = null\n"
    "        try { unregisterReceiver(hangUpReceiver) } catch (_: Exception) { /* not registered */ }\n"
    "        try { languageIdentifier?.close() } catch (_: Exception) { /* ignore */ }\n"
    "        languageIdentifier = null\n"
    "        releaseReadbackKeepAlive()\n"
    "        readbackKeepAliveHandler.removeCallbacksAndMessages(null)\n"
    "        try { recognizer?.cancel() } catch (_: Exception) { /* ignore */ }\n"
    "        try { recognizer?.destroy() } catch (_: Exception) { /* ignore */ }\n"
    "        recognizer = null\n"
    "        try { LocalWhisperEngine.get().cancel() } catch (_: Exception) { /* ignore */ }\n"
    "        unregisterAudioRouteDiagnostics()\n"
    "    }\n\n",
    "hands-free ownership helpers",
)

replace_once(
    "        // Hands-free is a live, per-session control started from the conversation\n"
    "        // button — never a persisted setting (there is no settings toggle any\n"
    "        // more). Opening a chat always starts disengaged; the flag is only ever\n"
    "        // turned on by an explicit button tap, so a value left over from a\n"
    "        // previous session (or a hard kill mid-loop) can never auto-resume a\n"
    "        // conversation the moment the chat opens.\n"
    "        preferences?.setHandsFreeMode(false)\n"
    "        handsFreeStopped = false\n",
    "        // Normally a new ChatActivity starts disengaged. The exception is a\n"
    "        // replacement screen for this same chat while a destroyed Activity is\n"
    "        // deliberately retained as the live hands-free controller; clearing the\n"
    "        // shared preference here would silently hang up that still-running loop.\n"
    "        val retainedOwner = handsFreeSessionOwner\n"
    "        val reattachingToLiveHandsFree = retainedOwner != null &&\n"
    "            retainedOwner !== this && retainedOwner.chatId == chatId &&\n"
    "            retainedOwner.isHandsFreeEngaged()\n"
    "        if (!reattachingToLiveHandsFree) preferences?.setHandsFreeMode(false)\n"
    "        handsFreeStopped = false\n",
    "startup hands-free reset",
)

replace_once(
    "            initUI()\n            reloadAmoled()\n            initSpeechListener()\n",
    "            initUI()\n"
    "            retainedHandsFreeOwnerForThisChat()?.let { owner ->\n"
    "                micHandsFreeActive(listening = owner.isRecording)\n"
    "                logVoiceEvent(\"replacement chat screen attached while the hands-free controller remains active\")\n"
    "            }\n"
    "            reloadAmoled()\n"
    "            initSpeechListener()\n",
    "replacement screen live-state paint",
)

replace_once(
    "                cancelAllAiActivity(\"notification Hang Up action\")\n",
    "                cancelHandsFreeOwnerOrSelf(\"notification Hang Up action\")\n",
    "notification hang-up forwarding",
)

replace_once(
    "    private fun stopHandsFreeByUser() {\n"
    "        logVoiceEvent(\"hands-free stopped (conversation button)\")\n"
    "        cancelAllAiActivity(\"conversation button tap (stop hands-free)\")\n"
    "    }\n",
    "    private fun stopHandsFreeByUser() {\n"
    "        logVoiceEvent(\"hands-free stopped (conversation button)\")\n"
    "        cancelHandsFreeOwnerOrSelf(\"conversation button tap (stop hands-free)\")\n"
    "    }\n",
    "conversation stop forwarding",
)

replace_once(
    "        val engine = preferences!!.getEffectiveAudioModel()\n",
    "        val existingOwner = handsFreeSessionOwner\n"
    "        if (existingOwner != null && existingOwner !== this) {\n"
    "            // Starting a different live conversation is an explicit user action;\n"
    "            // end the prior retained controller first so there can never be two\n"
    "            // Activity-owned microphone loops competing for one service.\n"
    "            existingOwner.cancelAllAiActivity(\"hands-free session replaced by another chat\")\n"
    "        }\n"
    "        val engine = preferences!!.getEffectiveAudioModel()\n",
    "single hands-free owner before start",
)

replace_once(
    "    private fun startHandsFreeService() {\n"
    "        ensurePostNotificationsPermission()\n",
    "    private fun startHandsFreeService() {\n"
    "        claimHandsFreeSessionOwnership()\n"
    "        ensurePostNotificationsPermission()\n",
    "claim owner on service start",
)

replace_once(
    "    private fun stopHandsFreeService() {\n"
    "        try {\n"
    "            HandsFreeService.stop(this)\n"
    "        } catch (_: Exception) { /* ignore */ }\n"
    "    }\n",
    "    private fun stopHandsFreeService() {\n"
    "        // A replacement screen is not allowed to stop the service that keeps\n"
    "        // another Activity's retained voice controller alive. Its Stop action\n"
    "        // is forwarded to the owner by cancelHandsFreeOwnerOrSelf instead.\n"
    "        val owner = handsFreeSessionOwner\n"
    "        if (owner != null && owner !== this) return\n"
    "        releaseHandsFreeSessionOwnership()\n"
    "        try {\n"
    "            HandsFreeService.stop(this)\n"
    "        } catch (_: Exception) { /* ignore */ }\n"
    "        cleanupDeferredHandsFreeDestroy()\n"
    "    }\n",
    "owner-safe service stop",
)

# Voice re-arm paths must regard the intentionally retained controller as alive
# even though Android has already called Activity.onDestroy(). UI-only guards
# elsewhere remain strict and continue to reject work against a dead window.
replace_exact_count(
    "if (!isFinishing && !isDestroyed && isRecording && !handsFreeStopped && !cancelState) {",
    "if (handsFreeControllerCanRun() && isRecording) {",
    3,
    "google recognizer delayed re-arm guards",
)
replace_exact_count(
    "if (!isFinishing && !isDestroyed && !handsFreeStopped && !cancelState && !isRecording) {",
    "if (handsFreeControllerCanRun() && !isRecording) {",
    2,
    "local whisper delayed re-arm guards",
)
replace_once(
    "                if (!isFinishing && !isDestroyed && !cancelState) {\n",
    "                if (handsFreeControllerCanRun()) {\n",
    "google result outer re-arm guard",
)
replace_once(
    "        if (attempt > 120 || isFinishing || isDestroyed) return\n",
    "        if (attempt > 120 || !backgroundVoiceControllerAvailable()) return\n",
    "queued spoken follow-up outer guard",
)
replace_once(
    "            if (isFinishing || isDestroyed) return@postDelayed\n",
    "            if (!backgroundVoiceControllerAvailable()) return@postDelayed\n",
    "queued spoken follow-up delayed guard",
)
replace_once(
    "        if (handsFree && sttSupported && !cancelState && !handsFreeStopped && !isRecording &&\n"
    "            !isFinishing && !isDestroyed\n"
    "        ) {\n",
    "        if (handsFree && sttSupported && handsFreeControllerCanRun() && !isRecording) {\n",
    "readback-to-mic re-arm guard",
)
replace_once(
    "            if (isFinishing || isDestroyed || cancelState || handsFreeStopped || isRecording) return@Runnable\n",
    "            if (!handsFreeControllerCanRun() || isRecording) return@Runnable\n",
    "readback watchdog retained-owner guard",
)
replace_once(
    "            if (!isFinishing && !isDestroyed) applyTtsDeliveryTuning(isRetry = true)\n",
    "            if (backgroundVoiceControllerAvailable()) applyTtsDeliveryTuning(isRetry = true)\n",
    "tts tuning retained-owner guard",
)

# Replace onDestroy as one unit. The preserve branch performs only UI-adjacent
# cleanup; Activity-bound voice resources and generation scopes stay alive until
# Stop/Hang Up/error runs the existing session-ending funnel.
start = source.index("    public override fun onDestroy() {")
end = source.index("    /** SYSTEM INITIALIZATION START **/", start)
new_on_destroy = '''    public override fun onDestroy() {
        val voiceWasLive = isRecording || handsFreeReadbackExpected ||
                (try { tts?.isSpeaking == true } catch (_: Exception) { false }) ||
                (try { mediaPlayer?.isPlaying == true } catch (_: Exception) { false })
        val preserveHandsFreeController = shouldPreserveHandsFreeControllerOnDestroy()

        if (voiceWasLive) {
            if (preserveHandsFreeController) {
                logVoiceEvent("chat screen destroyed while hands-free was active — voice controller retained; session continues")
            } else {
                logVoiceEvent("chat screen destroyed while voice was active — readback and mic loop torn down" +
                        if (isFinishing) " (screen was closed)" else " (destroyed by the system)")
            }
        }

        val teardownAction = when {
            preserveHandsFreeController -> "detach screen only (hands-free controller retained)"
            isChangingConfigurations ->
                "full teardown (configuration recreation — session state will be lost)"
            else -> "full teardown (genuine destroy)"
        }
        logVoiceEventAlways(
            "ChatActivity destroy: finishing=$isFinishing" +
                    " changingConfig=$isChangingConfigurations" +
                    " generationActive=${requestPreparationInProgress || providerRequestDispatched}" +
                    " providerDispatched=$providerRequestDispatched" +
                    " readbackActive=$voiceWasLive" +
                    " readbackExpected=$handsFreeReadbackExpected" +
                    " handsFreePref=${preferences?.getHandsFreeMode() == true}" +
                    " handsFreeService=${HandsFreeService.isRunning}" +
                    " handsFreeOwner=${isHandsFreeSessionOwner()}" +
                    " turn=${currentLifecycleTurnId.ifBlank { "none" }}" +
                    " action=$teardownAction"
        )
        logVoiceEventAlways("AudioRoute [chat destroy]: ${describeAudioOutputRoute()}")

        // These jobs belong to the visible screen, not to the live microphone /
        // generation controller, so they are always safe to detach here.
        summarizerController?.cancel()
        ImageGenerationJobRegistry.detach(chatId, this)
        for (scope in imageImportScopes.toList()) {
            try { scope.cancel() } catch (_: Exception) { /* ignore */ }
        }
        imageImportScopes.clear()

        if (preserveHandsFreeController) {
            // Do NOT call killAllProcesses(), stopHandsFreeService(), stop TTS,
            // destroy the recognizer, or cancel LocalWhisper here. Those are the
            // exact objects driving the foreground-service-protected conversation.
            // The actual session-ending funnels eventually call stopHandsFreeService,
            // which releases these deferred Activity resources exactly once.
            deferredHandsFreeDestroyCleanup = true
            super.onDestroy()
            return
        }

        killAllProcesses()
        stopHandsFreeService()
        releaseDestroyedActivityVoiceResources()
        super.onDestroy()
    }

'''
source = source[:start] + new_on_destroy + source[end:]

CHAT.write_text(source)

POLICY.write_text('''/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *************************************************************************/
package org.teslasoft.assistant.service

/**
 * Pure lifetime rules for the Activity-owned hands-free controller.
 *
 * HandsFreeService protects process/mic/wake-lock lifetime, but ChatActivity
 * still owns the recognizer, TTS objects and generation coroutines. Android is
 * therefore allowed to destroy the window without that being a request to end
 * the conversation. Only the registered session owner may survive that destroy,
 * and an explicit stop always wins.
 */
internal object HandsFreeActivityLifetimePolicy {
    fun shouldPreserveOnDestroy(
        isSessionOwner: Boolean,
        handsFreeEnabled: Boolean,
        loopStopped: Boolean
    ): Boolean = isSessionOwner && handsFreeEnabled && !loopStopped

    fun controllerCanRun(
        activityDestroyed: Boolean,
        isSessionOwner: Boolean,
        loopStopped: Boolean,
        cancelled: Boolean
    ): Boolean = !loopStopped && !cancelled && (!activityDestroyed || isSessionOwner)
}
''')

TEST.parent.mkdir(parents=True, exist_ok=True)
TEST.write_text('''package org.teslasoft.assistant.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandsFreeActivityLifetimePolicyTest {
    @Test
    fun `destroyed owner keeps controller alive`() {
        assertTrue(
            HandsFreeActivityLifetimePolicy.controllerCanRun(
                activityDestroyed = true,
                isSessionOwner = true,
                loopStopped = false,
                cancelled = false
            )
        )
    }

    @Test
    fun `destroyed non owner cannot keep controller alive`() {
        assertFalse(
            HandsFreeActivityLifetimePolicy.controllerCanRun(
                activityDestroyed = true,
                isSessionOwner = false,
                loopStopped = false,
                cancelled = false
            )
        )
    }

    @Test
    fun `explicit stop wins even for retained owner`() {
        assertFalse(
            HandsFreeActivityLifetimePolicy.controllerCanRun(
                activityDestroyed = true,
                isSessionOwner = true,
                loopStopped = true,
                cancelled = true
            )
        )
    }

    @Test
    fun `destroy preservation requires live owner session`() {
        assertTrue(
            HandsFreeActivityLifetimePolicy.shouldPreserveOnDestroy(
                isSessionOwner = true,
                handsFreeEnabled = true,
                loopStopped = false
            )
        )
        assertFalse(
            HandsFreeActivityLifetimePolicy.shouldPreserveOnDestroy(
                isSessionOwner = false,
                handsFreeEnabled = true,
                loopStopped = false
            )
        )
        assertFalse(
            HandsFreeActivityLifetimePolicy.shouldPreserveOnDestroy(
                isSessionOwner = true,
                handsFreeEnabled = false,
                loopStopped = false
            )
        )
    }
}
''')

print("Patched ChatActivity and wrote lifetime policy/tests")
