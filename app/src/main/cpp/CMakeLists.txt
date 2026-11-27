cmake_minimum_required(VERSION 3.22.1)
project(ajmat_voiceflow LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# The app ships a debug-signed APK. Keep native inference optimized even for
# the debug variant; an unoptimized -O0 Whisper build is far too slow on phones.
string(APPEND CMAKE_C_FLAGS_DEBUG " -O3 -DNDEBUG")
string(APPEND CMAKE_CXX_FLAGS_DEBUG " -O3 -DNDEBUG")
string(APPEND CMAKE_C_FLAGS_RELEASE " -O3")
string(APPEND CMAKE_CXX_FLAGS_RELEASE " -O3")

include(FetchContent)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_USE_COREML OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_CPU_REPACK ON CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    whisper_cpp
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG v1.7.1
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(whisper_cpp)

add_library(whisper_jni SHARED jni_bridge.c)
target_include_directories(whisper_jni PRIVATE
    ${whisper_cpp_SOURCE_DIR}/include
    ${whisper_cpp_SOURCE_DIR}/ggml/include
)
target_compile_options(whisper_jni PRIVATE -O3)
target_link_libraries(whisper_jni PRIVATE whisper android log)
