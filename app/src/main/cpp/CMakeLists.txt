cmake_minimum_required(VERSION 3.22.1)
project(phosphorwhisper)

# Step 2A of the local-Whisper rollout: this CMake build only compiles a
# tiny JNI stub. It exists to prove the NDK + CMake + AGP pipeline works
# end-to-end before vendoring whisper.cpp in step 2B. Once whisper.cpp
# is added, this file grows to pick up its sources and link flags.

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(phosphorwhisper SHARED
    phosphor_whisper_jni.cpp)

find_library(log-lib log)

target_link_libraries(phosphorwhisper
    ${log-lib})
