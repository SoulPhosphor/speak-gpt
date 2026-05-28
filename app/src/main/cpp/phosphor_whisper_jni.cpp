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

// Step 2A of the local-Whisper rollout. Only purpose: prove the NDK +
// CMake + AGP + System.loadLibrary pipeline runs end-to-end before
// whisper.cpp lands in step 2B. Returning a known string from native is
// enough to confirm the .so loaded, the symbol resolved, and JNI marshaling
// works in both directions.

#include <jni.h>

extern "C" JNIEXPORT jstring JNICALL
Java_org_teslasoft_assistant_stt_LocalWhisperNative_pingNative(
        JNIEnv *env, jclass /*clazz*/) {
    return env->NewStringUTF("phosphor-whisper native ok");
}
