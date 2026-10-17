cmake_minimum_required(VERSION 3.22)
project(clamshellds C CXX ASM)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Always build the emulator core optimised, even in debug APKs: an unoptimised
# core is unplayable on RK3568-class handhelds like the RG DS.
set(CMAKE_BUILD_TYPE Release)
set(CMAKE_C_FLAGS_RELEASE "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG")

set(MELONDS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../external/melonDS")
get_filename_component(MELONDS_DIR "${MELONDS_DIR}" ABSOLUTE)

if (NOT EXISTS "${MELONDS_DIR}/src/NDS.cpp")
    message(FATAL_ERROR "melonDS sources not found at ${MELONDS_DIR}. Run: git submodule update --init")
endif()

list(APPEND CMAKE_MODULE_PATH "${MELONDS_DIR}/cmake")
include(CheckSymbolExists)
include(CheckLibraryExists)

# Mirror the bits of melonDS' top-level CMakeLists that the core relies on,
# without pulling in the Qt/SDL desktop frontend.
if (CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64")
    set(ARCHITECTURE ARM64)
    add_definitions(-DARCHITECTURE_ARM64=1)
    set(ENABLE_JIT ON)
elseif (CMAKE_SYSTEM_PROCESSOR MATCHES "x86_64|AMD64")
    set(ARCHITECTURE x86_64)
    add_definitions(-DARCHITECTURE_x86_64=1)
    set(ENABLE_JIT ON)
else()
    set(ENABLE_JIT OFF)
endif()

set(ENABLE_OGLRENDERER OFF)
set(ENABLE_GDBSTUB OFF)
set(ENABLE_JIT_PROFILING OFF)

add_subdirectory("${MELONDS_DIR}/src" melonds-core)

add_library(clamshellds SHARED
    Platform.cpp
    EmuCore.cpp
    jni.cpp)

target_link_libraries(clamshellds PRIVATE core android log aaudio)
target_compile_options(clamshellds PRIVATE -Wall -Wno-unused-parameter)
target_link_options(clamshellds PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
