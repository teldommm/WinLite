# ----------------------------------------------------------------------------
# winlite-steam-client
# Rust native Steam protocol client for WinLite.
# ----------------------------------------------------------------------------

cmake_minimum_required(VERSION 3.22.1)

set(WINLITE_STEAM_CLIENT_VERSION_MAJOR 0)
set(WINLITE_STEAM_CLIENT_VERSION_MINOR 1)
set(WINLITE_STEAM_CLIENT_VERSION_PATCH 0)
set(WINLITE_STEAM_CLIENT_VERSION_STRING
    "${WINLITE_STEAM_CLIENT_VERSION_MAJOR}.${WINLITE_STEAM_CLIENT_VERSION_MINOR}.${WINLITE_STEAM_CLIENT_VERSION_PATCH}")

find_program(CARGO_EXECUTABLE cargo
    HINTS "$ENV{CARGO_HOME}/bin" "$ENV{HOME}/.cargo/bin" "$ENV{USERPROFILE}/.cargo/bin"
    REQUIRED)

if(NOT CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "Rust winlitesteam currently supports arm64-v8a; got ${CMAKE_ANDROID_ARCH_ABI}")
endif()
if(NOT DEFINED ANDROID_NDK)
    message(FATAL_ERROR "ANDROID_NDK not defined; Rust winlitesteam must be built via Android Gradle/CMake")
endif()

set(WINLITESTEAM_RUST_TARGET "aarch64-linux-android")
if(DEFINED ANDROID_PLATFORM)
    string(REGEX REPLACE "^android-" "" WINLITESTEAM_ANDROID_API "${ANDROID_PLATFORM}")
elseif(DEFINED CMAKE_SYSTEM_VERSION)
    set(WINLITESTEAM_ANDROID_API "${CMAKE_SYSTEM_VERSION}")
else()
    set(WINLITESTEAM_ANDROID_API "26")
endif()

if(CMAKE_HOST_WIN32)
    set(WINLITESTEAM_NDK_HOST_TAG "windows-x86_64")
    set(WINLITESTEAM_LINKER_SUFFIX ".cmd")
    set(WINLITESTEAM_AR_SUFFIX ".exe")
elseif(CMAKE_HOST_APPLE)
    set(WINLITESTEAM_NDK_HOST_TAG "darwin-x86_64")
    set(WINLITESTEAM_LINKER_SUFFIX "")
    set(WINLITESTEAM_AR_SUFFIX "")
else()
    set(WINLITESTEAM_NDK_HOST_TAG "linux-x86_64")
    set(WINLITESTEAM_LINKER_SUFFIX "")
    set(WINLITESTEAM_AR_SUFFIX "")
endif()

set(WINLITESTEAM_RUST_DIR "${CMAKE_CURRENT_SOURCE_DIR}/rust")
file(GLOB_RECURSE WINLITESTEAM_RUST_SOURCES CONFIGURE_DEPENDS
    "${WINLITESTEAM_RUST_DIR}/src/*.rs")
set(WINLITESTEAM_RUST_BUILD_INPUTS
    "${WINLITESTEAM_RUST_DIR}/Cargo.toml"
    "${WINLITESTEAM_RUST_DIR}/Cargo.lock"
    ${WINLITESTEAM_RUST_SOURCES})
set(WINLITESTEAM_RUST_LIB
    "${WINLITESTEAM_RUST_DIR}/target/${WINLITESTEAM_RUST_TARGET}/release/libwinlitesteam.so")
set(WINLITESTEAM_RUST_OUT "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/libwinlitesteam.so")
set(WINLITESTEAM_RUST_LINKER
    "${ANDROID_NDK}/toolchains/llvm/prebuilt/${WINLITESTEAM_NDK_HOST_TAG}/bin/${WINLITESTEAM_RUST_TARGET}${WINLITESTEAM_ANDROID_API}-clang${WINLITESTEAM_LINKER_SUFFIX}")

add_custom_command(
    OUTPUT "${WINLITESTEAM_RUST_OUT}"
    COMMAND "${CMAKE_COMMAND}" -E env
        "CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=${WINLITESTEAM_RUST_LINKER}"
        "CC_aarch64_linux_android=${WINLITESTEAM_RUST_LINKER}"
        "AR_aarch64_linux_android=${ANDROID_NDK}/toolchains/llvm/prebuilt/${WINLITESTEAM_NDK_HOST_TAG}/bin/llvm-ar${WINLITESTEAM_AR_SUFFIX}"
        "${CARGO_EXECUTABLE}" rustc --locked --release --target "${WINLITESTEAM_RUST_TARGET}" --lib --
        -Clink-arg=-Wl,-soname,libwinlitesteam.so
    COMMAND "${CMAKE_COMMAND}" -E copy_if_different
        "${WINLITESTEAM_RUST_LIB}"
        "${WINLITESTEAM_RUST_OUT}"
    WORKING_DIRECTORY "${WINLITESTEAM_RUST_DIR}"
    DEPENDS ${WINLITESTEAM_RUST_BUILD_INPUTS}
    COMMENT "Building Rust winlitesteam for ${WINLITESTEAM_RUST_TARGET}"
    VERBATIM
)

add_custom_target(winlitesteam_rust_build DEPENDS "${WINLITESTEAM_RUST_OUT}")
add_library(winlitesteam SHARED IMPORTED GLOBAL)
set_target_properties(winlitesteam PROPERTIES
    IMPORTED_LOCATION "${WINLITESTEAM_RUST_OUT}"
    IMPORTED_SONAME "libwinlitesteam.so")
add_dependencies(winlitesteam winlitesteam_rust_build)
