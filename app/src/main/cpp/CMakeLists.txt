cmake_minimum_required(VERSION 3.22)
project("assistant" LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

# llama.cpp (git submodule, pinned in REQUIREMENTS.md). CPU only; one backend library per CPU variant is
# built and the best one for the phone is picked at runtime (GGML_BACKEND_DL + GGML_CPU_ALL_VARIANTS).
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_SYSTEM_ARCH "ARM")
    set(GGML_CPU_KLEIDIAI ON)
    set(GGML_OPENMP ON)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(GGML_SYSTEM_ARCH "x86")
    set(GGML_CPU_KLEIDIAI OFF)
    set(GGML_OPENMP OFF)
endif()

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/llama.cpp)
add_subdirectory(${LLAMA_SRC} build-llama)

add_library(assistant SHARED assistant_jni.cpp)
target_include_directories(assistant PRIVATE ${LLAMA_SRC}/common ${LLAMA_SRC}/include ${LLAMA_SRC}/ggml/include)
target_link_libraries(assistant llama llama-common android log)
