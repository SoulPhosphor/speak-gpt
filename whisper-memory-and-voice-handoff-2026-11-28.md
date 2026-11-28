# Whisper Memory and Voice Handoff — 2026-11-28

## Assignment and Starting Point

Read `CLAUDE.md`, this handoff, current `origin/main`, and the current source/tests before changing anything. Work from `origin/main` on a feature branch and preserve the side-by-side Beta identity. The old implementation plan is archived at `legacy/whisper-local-plan.md`: it describes features already built and contains an unsafe simplification of the proposed memory change. It is not an active work order.

PR #274 protects replies when their chat screen closes. It does not implement a Whisper memory reduction or a hands-free session that continues after screen destruction. Finish and verify that repair before starting voice continuity work. Its live reply owns its state and persistence; reopening observes that owner rather than taking over its message objects or save retries.

This document hands off remaining work; it does not authorize the larger voice-continuity feature or changes to settings, transcription accuracy, logging, notifications, or controls. The owner's actual assignment determines which work begins.

## Evidence and Unresolved Questions

The supplied investigation reports approximately 1.17–1.21 GB retained by Whisper small.en with beam size 5, including approximately 473 MB of weights and 690–730 MB of working state. Its five-turn PC experiment found no turn-to-turn growth. These are reported measurements, not fresh measurements made by the PR #274 repair.

The investigation also reports substantial native allocations and temporary swings outside Whisper. Their sources remain unidentified. No embedding model was installed in the reported sessions. Do not blame an embedding model for those allocations or claim all leaks have been excluded.

The JNI decoder implementation on the audited main revision was last changed in June. Its longstanding footprint does not establish why the app began failing more recently. Whole-log append/trim/encryption and whole-chat serialization create temporary Java allocations; their role in the actual failures still needs measurement. A hypothesized Java-heap threshold crossing was not captured in the supplied logs.

Use the existing ProcessExit, Memory Usage, Whisper Performance and chat-destruction diagnostics. Distinguish Java heap use, live native allocations, process PSS/RSS, and system memory pressure. An allocation returning to its allocator is not a guarantee of an identical immediate RSS reduction.

## Next Memory Work: Reclaim Large Whisper Working Buffers

Goal: reduce retained working memory between transcriptions while preserving the installed model, quantization, decoder, beam size, temperature/fallback behavior, prompts, previous-context behavior, cancellation and transcript quality.

Relevant current files:

- `app/src/main/cpp/phosphor_whisper_jni.cpp`
- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/java/org/teslasoft/assistant/stt/LocalWhisperNative.kt`
- `app/src/main/java/org/teslasoft/assistant/stt/LocalWhisperEngine.kt`
- `app/src/main/java/org/teslasoft/assistant/stt/WhisperModelContext.kt`
- Existing `WhisperModelContext` and voice tests.

The app pins whisper.cpp v1.7.4, commit `8a9ad7844d6e2a10cddf4b92de4089d7ac2b14a9`. Verify the pin before relying on source-specific behavior. Its public APIs include no-state context initialization, `whisper_init_state`, `whisper_full_with_state`, state-based segment access and `whisper_free_state`.

Do not implement an unconditional initialize/run/free recipe without addressing these source facts:

1. In v1.7.4 `src/whisper.cpp`, the self-attention-cache allocation failure inside `whisper_full_with_state` frees the supplied state before returning `-7`. Freeing the same pointer afterward can double-free it. The failure path and ownership rule need explicit protection and a test, especially in a memory-pressure repair.
2. `whisper_init_state` seeds decoder zero's random generator. The per-call decoder reset loop begins at decoder 1. A fresh state therefore does not necessarily reproduce all sampling behavior of a reused state. Temperature fallback may use sampling even when the initial configured temperature is zero. Do not change fallback settings to conceal a difference.
3. Previous text context lives in `prompt_past`. The existing implementation can retain the most recent turn's text even after a no-context transcription, then use it if the user enables previous context. Test the off-to-on transition as well as repeated turns with the switch on. Discarding state while the switch is off must not silently change this behavior.

Design explicit ownership for the model weights, large working buffers and small continuity state. If a narrowly scoped addition to the pinned native integration is needed to preserve continuity while releasing buffers, inspect and test it rather than approximating context from rendered transcripts. Keep initialization, inference, model switching and release under the existing handle/transcription locking rules; never free a handle/state while inference still uses it.

Required verification:

- Compare old and new behavior using identical recorded audio, the same model file, settings and the app's actual runtime. Include multiple consecutive turns, prompts, silence, abort, temperature/fallback cases and previous-context off/on transitions.
- Test allocation failure, ordinary inference failure, cancellation, repeated release and a model switch/release requested during loading or transcription. Each resource must be freed exactly once.
- Measure retained native memory between turns and peak memory during capture, decode, reply generation and readback. Confirm a real reduction rather than only a change in accounting.
- Measure added latency on the phone. A reported PC rebuild time of 29–36 ms and an estimated phone delay of 0.1 seconds are not measured phone results.
- Do not claim identical transcription or a particular RAM saving until the actual checks support it.

Unloading all weights while idle is a separate behavior/performance tradeoff, not an automatic extension of this assignment.

## Remaining Allocation Investigation

After the measured Whisper reduction, attribute the remaining native allocations using the same runtime and representative sessions. Check audio/model/session lifetimes and simultaneous work without assuming a small model file implies a small runtime footprint. Silero's per-frame tensors are closed in the current source; that does not measure the retained ONNX session/allocator footprint.

Measure temporary Java peaks around log appends, chat saving and request construction. Reducing allocations must preserve encryption, complete logs and their approved retention, durability, attachments, full conversation content and responsiveness. Moving identical work off main alone does not reduce its memory footprint. Avoid a broad chat-storage rewrite without evidence that it is necessary.

Validate the future overlap with a loaded embedding model once available. An absent model cannot be part of the current measurement; a future loaded model will need additional memory.

## Later Feature: Hands-Free Continuity After Screen Destruction

This is larger than PR #274 and requires its own explicit assignment and any necessary product decisions.

Currently the chat screen owns the listening/transcription/send/readback/re-arm loop. Saving a reply after screen destruction does not keep that loop going, and in-process ownership does not survive a whole-process kill.

If assigned, move the loop into a suitable service-owned session. The screen should observe the session and expose its existing controls. Reuse the reply owner; do not create a second provider request or reconstruct an unsaved original tool request. Request construction must preserve the prompt, memory/lore selection, attachments, routing, reasoning and accounting contracts.

Before implementing user-visible differences, resolve notification behavior, Hang Up, reopening during listening/readback and saved image approval. Preserve existing Bluetooth routing, wake-lock rules, watchdogs and spoken confirmation. Test close/reopen during every phase, configuration changes, Stop, Hang Up, deletion, restore, delayed storage and process death. Prove no duplicate sends, charges, image jobs or readback.
