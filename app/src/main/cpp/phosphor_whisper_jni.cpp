/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

// Step 2C of the local-Whisper rollout: replaces the smoke-test stubs
// with the actual transcription path. Three responsibilities exposed to
// Kotlin via LocalWhisperNative:
//
//   - initContextNative(path)        -> opaque jlong handle (0 on failure)
//   - releaseContextNative(handle)
//   - transcribeNative(handle, pcm16, sampleRate, lang) -> joined text
//
// systemInfoNative + pingNative are kept as diagnostics; they're cheap
// and the LocalWhisperEngine uses them to verify the .so loaded before
// trusting init.

#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <string>
#include <vector>
#include <thread>
#include <algorithm>

#include "whisper.h"

#define TAG "PhosphorWhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

// Cooperative abort flag for the in-flight whisper_full run. whisper.cpp
// can't be interrupted from the outside, but it polls an abort callback
// between decode steps, so wiring this flag lets the app bail out of a
// transcription the user has given up on (instead of the native call
// running to completion in the background — which previously left a
// stale whisper_full racing the next one on the same context). Only one
// transcription runs at a time (serialized on the Kotlin side), so a
// single process-wide flag is sufficient.
static std::atomic<bool> g_abortRequested{false};

static bool phosphor_whisper_abort_cb(void * /*user_data*/) {
    return g_abortRequested.load(std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_org_teslasoft_assistant_stt_LocalWhisperNative_signalAbortNative(
        JNIEnv * /*env*/, jclass /*clazz*/) {
    g_abortRequested.store(true, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_org_teslasoft_assistant_stt_LocalWhisperNative_clearAbortNative(
        JNIEnv * /*env*/, jclass /*clazz*/) {
    g_abortRequested.store(false, std::memory_order_relaxed);
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_teslasoft_assistant_stt_LocalWhisperNative_pingNative(
        JNIEnv *env, jclass /*clazz*/) {
    return env->NewStringUTF("phosphor-whisper native ok");
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_teslasoft_assistant_stt_LocalWhisperNative_systemInfoNative(
        JNIEnv *env, jclass /*clazz*/) {
    const char *info = whisper_print_system_info();
    return env->NewStringUTF(info ? info : "<no info>");
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_teslasoft_assistant_stt_LocalWhisperNative_initContextNative(
        JNIEnv *env, jclass /*clazz*/, jstring jpath) {
    if (jpath == nullptr) return 0L;
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    if (path == nullptr) return 0L;

    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;

    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    if (ctx == nullptr) {
        LOGW("whisper_init_from_file_with_params returned null for %s", path);
    }

    env->ReleaseStringUTFChars(jpath, path);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_org_teslasoft_assistant_stt_LocalWhisperNative_releaseContextNative(
        JNIEnv * /*env*/, jclass /*clazz*/, jlong handle) {
    if (handle == 0L) return;
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    whisper_free(ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_teslasoft_assistant_stt_LocalWhisperNative_transcribeNative(
        JNIEnv *env, jclass /*clazz*/,
        jlong handle, jshortArray jpcm, jint /*sampleRate*/, jstring jlang,
        jboolean useBeam, jint beamSize, jfloat temperature,
        jboolean suppressBlank, jboolean singleSegment, jboolean noContext,
        jstring jinitialPrompt) {
    if (handle == 0L || jpcm == nullptr) {
        return env->NewStringUTF("");
    }
    auto *ctx = reinterpret_cast<whisper_context *>(handle);

    jsize n = env->GetArrayLength(jpcm);
    if (n <= 0) {
        return env->NewStringUTF("");
    }

    // whisper expects float32 PCM in [-1, 1]; AudioRecord hands us int16.
    std::vector<float> samples(static_cast<size_t>(n));
    jshort *pcm = env->GetShortArrayElements(jpcm, nullptr);
    if (pcm == nullptr) {
        return env->NewStringUTF("");
    }
    for (jsize i = 0; i < n; i++) {
        samples[i] = static_cast<float>(pcm[i]) / 32768.0f;
    }
    env->ReleaseShortArrayElements(jpcm, pcm, JNI_ABORT);

    // Decode strategy is user-selectable (advanced voice settings). Beam
    // search keeps several candidate continuations alive and commits to the
    // best-scoring whole sequence — cleaner sentence structure and more
    // consistent punctuation, at more compute per clip. Greedy takes the
    // single most likely token each step — faster, useful on slower devices.
    // Defaults passed from Kotlin reproduce the long-standing behaviour
    // (beam, size 5, temperature 0, suppress blank, multi-segment, no
    // cross-clip context, no prompt).
    int clampedBeam = beamSize < 1 ? 1 : (beamSize > 8 ? 8 : beamSize);
    struct whisper_full_params wparams = whisper_full_default_params(
        useBeam ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    if (useBeam) {
        wparams.beam_search.beam_size = clampedBeam;
    }
    wparams.temperature      = temperature < 0.0f ? 0.0f : (temperature > 1.0f ? 1.0f : temperature);
    wparams.print_realtime   = false;
    wparams.print_progress   = false;
    wparams.print_timestamps = false;
    wparams.print_special    = false;
    wparams.translate        = false;
    wparams.single_segment   = (singleSegment == JNI_TRUE);
    wparams.no_context       = (noContext == JNI_TRUE);
    wparams.suppress_blank   = (suppressBlank == JNI_TRUE);

    // Optional decoder priming text. Held for the duration of whisper_full —
    // wparams.initial_prompt is a borrowed pointer.
    const char *initialPrompt = nullptr;
    if (jinitialPrompt != nullptr) {
        initialPrompt = env->GetStringUTFChars(jinitialPrompt, nullptr);
    }
    if (initialPrompt != nullptr && initialPrompt[0] != '\0') {
        wparams.initial_prompt = initialPrompt;
    }

    // Let the user abort a long-running transcription. The flag is cleared
    // by the caller (under the transcribe lock) immediately before this
    // call, and set via signalAbortNative() when the run should stop.
    wparams.abort_callback           = phosphor_whisper_abort_cb;
    wparams.abort_callback_user_data = nullptr;

    // Hold the lang string for the duration of whisper_full — wparams.language
    // is a borrowed pointer into JNI-managed memory.
    const char *lang = nullptr;
    if (jlang != nullptr) {
        lang = env->GetStringUTFChars(jlang, nullptr);
    }
    wparams.language = (lang && lang[0]) ? lang : "en";

    unsigned int hc = std::thread::hardware_concurrency();
    int n_threads = (hc == 0) ? 4 : static_cast<int>(hc) - 1;
    if (n_threads < 1) n_threads = 1;
    if (n_threads > 4) n_threads = 4;
    wparams.n_threads = n_threads;

    int result = whisper_full(ctx, wparams, samples.data(), n);

    if (lang != nullptr) {
        env->ReleaseStringUTFChars(jlang, lang);
    }
    if (initialPrompt != nullptr) {
        env->ReleaseStringUTFChars(jinitialPrompt, initialPrompt);
    }

    if (result != 0) {
        LOGW("whisper_full failed: %d", result);
        return env->NewStringUTF("");
    }

    std::string out;
    int n_segs = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_segs; i++) {
        const char *txt = whisper_full_get_segment_text(ctx, i);
        if (txt != nullptr) out += txt;
    }

    return env->NewStringUTF(out.c_str());
}
