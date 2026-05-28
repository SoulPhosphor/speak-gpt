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

// Steps 2A and 2B of the local-Whisper rollout. pingNative is the original
// "we can load a .so" smoke test; systemInfoNative additionally proves
// that whisper.cpp source compiled, linked, and that we can resolve
// symbols from the upstream library. Step 2C replaces both with the real
// transcription entry points.

#include <jni.h>
#include <string>

#include "whisper.h"

extern "C" JNIEXPORT jstring JNICALL
Java_org_teslasoft_assistant_stt_LocalWhisperNative_pingNative(
        JNIEnv *env, jclass /*clazz*/) {
    return env->NewStringUTF("phosphor-whisper native ok");
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_teslasoft_assistant_stt_LocalWhisperNative_systemInfoNative(
        JNIEnv *env, jclass /*clazz*/) {
    // whisper_print_system_info() returns a static C string with the
    // backends/CPU features whisper.cpp built with. No model load
    // required — it's safe to call before any context init.
    const char *info = whisper_print_system_info();
    if (info == nullptr) {
        return env->NewStringUTF("<no info>");
    }
    return env->NewStringUTF(info);
}
