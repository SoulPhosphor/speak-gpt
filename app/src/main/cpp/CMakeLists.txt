cmake_minimum_required(VERSION 3.22.1)
project(phosphorwhisper)

# Step 2B of the local-Whisper rollout: pull whisper.cpp from upstream
# at build time via FetchContent and link it into our JNI .so. Step 2C
# will replace the stub JNI body with actual transcription calls.

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

# Trim whisper.cpp's optional features to the bare minimum needed on
# Android: no examples, no test/server binaries, no desktop GPU backends.
# CPU + NEON is auto-enabled by ggml for arm64-v8a, which is the only
# ABI app/build.gradle ships.
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2           OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL           OFF CACHE BOOL "" FORCE)

set(GGML_OPENBLAS OFF CACHE BOOL "" FORCE)
set(GGML_CUDA     OFF CACHE BOOL "" FORCE)
set(GGML_METAL    OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN   OFF CACHE BOOL "" FORCE)
set(GGML_HIP      OFF CACHE BOOL "" FORCE)
set(GGML_SYCL     OFF CACHE BOOL "" FORCE)
# Native CPU detection trips up cross-compilation; rely on the ABI default
# (NEON on arm64-v8a).
set(GGML_NATIVE   OFF CACHE BOOL "" FORCE)

# Build whisper as static libs and bundle into our single .so so the APK
# only ships libphosphorwhisper.so.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    whisper
    GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp.git
    GIT_TAG        v1.7.4
)
FetchContent_MakeAvailable(whisper)

add_library(phosphorwhisper SHARED
    phosphor_whisper_jni.cpp)

target_include_directories(phosphorwhisper PRIVATE
    ${whisper_SOURCE_DIR}/include
    ${whisper_SOURCE_DIR}/ggml/include)

find_library(log-lib log)

target_link_libraries(phosphorwhisper
    whisper
    ${log-lib})
