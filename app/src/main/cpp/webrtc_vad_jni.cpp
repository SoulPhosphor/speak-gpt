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

// JNI bridge for libfvad (the standalone WebRTC voice-activity detector).
// Backs WebRtcVadNative on the Kotlin side. libfvad is tiny, dependency-free
// and produces a per-frame voiced/unvoiced decision from 16-bit PCM frames
// of 10/20/30 ms.

#include <jni.h>
#include <android/log.h>
#include <cstdint>

#include "fvad.h"

#define TAG "WebRtcVadJNI"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

extern "C" JNIEXPORT jlong JNICALL
Java_org_teslasoft_assistant_stt_WebRtcVadNative_nativeNew(
        JNIEnv * /*env*/, jclass /*clazz*/, jint mode, jint sampleRate) {
    Fvad *vad = fvad_new();
    if (vad == nullptr) {
        LOGW("fvad_new returned null");
        return 0L;
    }
    if (fvad_set_mode(vad, mode) != 0) {
        LOGW("fvad_set_mode(%d) failed", mode);
        fvad_free(vad);
        return 0L;
    }
    if (fvad_set_sample_rate(vad, sampleRate) != 0) {
        LOGW("fvad_set_sample_rate(%d) failed", sampleRate);
        fvad_free(vad);
        return 0L;
    }
    return reinterpret_cast<jlong>(vad);
}

extern "C" JNIEXPORT void JNICALL
Java_org_teslasoft_assistant_stt_WebRtcVadNative_nativeReset(
        JNIEnv * /*env*/, jclass /*clazz*/, jlong handle) {
    if (handle == 0L) return;
    fvad_reset(reinterpret_cast<Fvad *>(handle));
}

extern "C" JNIEXPORT jint JNICALL
Java_org_teslasoft_assistant_stt_WebRtcVadNative_nativeProcess(
        JNIEnv *env, jclass /*clazz*/, jlong handle, jshortArray frame, jint length) {
    if (handle == 0L || frame == nullptr || length <= 0) return -1;
    auto *vad = reinterpret_cast<Fvad *>(handle);

    jshort *samples = env->GetShortArrayElements(frame, nullptr);
    if (samples == nullptr) return -1;

    // jshort is int16_t on Android; fvad wants the frame length in samples.
    int result = fvad_process(vad,
                              reinterpret_cast<const int16_t *>(samples),
                              static_cast<size_t>(length));

    env->ReleaseShortArrayElements(frame, samples, JNI_ABORT);
    return result; // 1 voice, 0 non-voice, -1 error
}

extern "C" JNIEXPORT void JNICALL
Java_org_teslasoft_assistant_stt_WebRtcVadNative_nativeFree(
        JNIEnv * /*env*/, jclass /*clazz*/, jlong handle) {
    if (handle == 0L) return;
    fvad_free(reinterpret_cast<Fvad *>(handle));
}
