cmake_minimum_required(VERSION 3.22.1)

project(Winlator)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -Wimplicit-function-declaration")

# libadrenotools is third-party code (cloned by CI): its warnings are not ours to fix, so they
# are muted for that directory only.
set_property(DIRECTORY APPEND PROPERTY COMPILE_OPTIONS
        -Wno-missing-designated-field-initializers -Wno-format-truncation)
add_subdirectory(adrenotools)
set_property(DIRECTORY PROPERTY COMPILE_OPTIONS "")

# xcore: the Rust core of the X server, built with cargo into a static library and linked whole
# into libwinlator.so (the JNI entry points are only referenced by the JVM, so the linker must
# keep every object of the archive). Needs `cargo` and the Rust target aarch64-linux-android:
#   rustup target add aarch64-linux-android
if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "xcore is built for arm64-v8a only (see abiFilters in app/build.gradle)")
endif()
find_program(CARGO_EXECUTABLE cargo
        HINTS "$ENV{CARGO_HOME}/bin" "$ENV{HOME}/.cargo/bin" "$ENV{USERPROFILE}/.cargo/bin")
if(NOT CARGO_EXECUTABLE)
    message(FATAL_ERROR "cargo not found: install Rust (https://rustup.rs), then run "
                        "`rustup target add aarch64-linux-android`")
endif()
set(XCORE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../rust/xcore)
set(XCORE_TARGET_DIR ${CMAKE_CURRENT_BINARY_DIR}/xcore-target)
set(XCORE_LIB ${XCORE_TARGET_DIR}/aarch64-linux-android/release/libxcore.a)
# Always run cargo: it knows what changed, and a no-op build takes well under a second.
add_custom_target(xcore_cargo
        COMMAND ${CARGO_EXECUTABLE} build --release --locked --target aarch64-linux-android
                --manifest-path ${XCORE_DIR}/Cargo.toml --target-dir ${XCORE_TARGET_DIR}
        BYPRODUCTS ${XCORE_LIB}
        COMMENT "Building xcore (Rust)"
        VERBATIM)
add_library(xcore STATIC IMPORTED GLOBAL)
set_target_properties(xcore PROPERTIES IMPORTED_LOCATION ${XCORE_LIB})
add_dependencies(xcore xcore_cargo)

# Add Winlator shared library
# NOTE: the DisplayX and EGL X-server renderer path (winlator/renderer/{egl,window,shader,
# cursor,displayx,renderer_jni}.* and renderer/effectcomposer/*)
# was removed. Vulkan (vulkan_renderer, below) is now the only X-server presentation path.
# The OpenXR/VR module (xr/*.c, OpenXR-SDK/) was also removed: it was already fully
# disconnected in this fork (no Java call site ever invoked its native entry points, and
# the JNI symbol names didn't even match the actual Java package).
# drawable.c, sysvshared_memory.c, xconnector_epoll.c and alsa_client.c are now the Rust crate
# `xcore` (app/src/main/rust/xcore, linked in below), together with the X server itself
# (protocol, windows, drawing, DRI3/Present, ...), the SysV SHM server and the ALSA server
# (AAudio output; hence `aaudio` below). gpu_image.c (the AHardwareBuffer helper of the old
# Java server's drawables) went with the Java server.
add_library(winlator SHARED
        winlator/vulkan.c
        winlator/thread_affinity.c
        winlator/gpu_turbo.c
        winlator/gpu_busy.c
)

# JNI entry points have a fixed (env, obj) signature, so unused parameters are expected.
target_compile_options(winlator PRIVATE -Wall -Wextra -Wno-unused-parameter)

target_link_libraries(winlator
        -Wl,--whole-archive xcore -Wl,--no-whole-archive
        log
        android
        jnigraphics
        aaudio
        adrenotools
        vulkan
        dl
        m
)

# Keep the Rust runtime's own symbols out of libwinlator.so's dynamic table (whole-archive would
# otherwise export ~2000 of them). Only Rust-internal names are made local; the Java_* JNI
# entry points and everything the C code exports are untouched. See xcore.map.
target_link_options(winlator PRIVATE
        -Wl,--version-script=${XCORE_DIR}/xcore.map
        -Wl,--gc-sections
)
set_property(TARGET winlator APPEND PROPERTY LINK_DEPENDS ${XCORE_DIR}/xcore.map)

# Add Fake Input library for controller emulation (loaded via LD_PRELOAD)
add_library(fakeinput SHARED
        winlator/fakeinput.cpp
)

target_compile_options(fakeinput PRIVATE -Wall -Wextra -fvisibility=hidden)

target_link_libraries(fakeinput
        log
        dl
)

# Force SSBS: keeps PSTATE.SSBS set across Wine's signal returns on arm64ec layers that were not
# rebuilt with the SSBS fix (loaded via LD_PRELOAD when the container/game toggle is on).
# sigaction/signal/bsd_signal must stay default-visible, so no -fvisibility=hidden here.
add_library(ssbs SHARED
        winlator/ssbs_adapter.c
)

target_compile_options(ssbs PRIVATE -Wall -Wextra)

target_link_libraries(ssbs
        dl
)

# Vulkan renderer: the sole X-server presentation path (see note above the
# `winlator` target). Still built as its own JNI library (vulkan_renderer.so).
set(VULKAN_RENDERER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/winlator/renderer/vulkan)

# Shaders live in their own folder and are compiled to SPIR-V headers at build time
# (glslangValidator); the renderer includes the generated <base>.h from the build directory.
set(VULKAN_SHADER_DIR ${VULKAN_RENDERER_DIR}/shaders)
set(VULKAN_SHADER_HEADERS "")

# vulkan_shader(<source in shaders/> <base name> [USES_POSTFX])
#   -> ${CMAKE_CURRENT_BINARY_DIR}/<base>.h defining the array <base>_code.
# USES_POSTFX: the shader #includes postfx_common.glsl, so it is rebuilt when that changes.
function(vulkan_shader SOURCE BASE)
    cmake_parse_arguments(ARG "USES_POSTFX" "" "" ${ARGN})
    set(_src ${VULKAN_SHADER_DIR}/${SOURCE})
    set(_out ${CMAKE_CURRENT_BINARY_DIR}/${BASE}.h)
    set(_deps ${_src})
    if(ARG_USES_POSTFX)
        list(APPEND _deps ${VULKAN_SHADER_DIR}/postfx_common.glsl)
    endif()
    add_custom_command(
        OUTPUT ${_out}
        COMMAND glslangValidator -V --vn ${BASE}_code -x -o ${_out} ${_src}
        DEPENDS ${_deps}
        COMMENT "Compiling ${SOURCE} -> ${BASE}.h"
        VERBATIM
    )
    set(VULKAN_SHADER_HEADERS ${VULKAN_SHADER_HEADERS} ${_out} PARENT_SCOPE)
endfunction()

# WFG's compute shaders (wfg/CMakeLists.txt): glslc from the NDK's shader-tools or PATH, else
# glslangValidator, the same tool the window shaders above use.
set(GLSLC "${ANDROID_NDK}/shader-tools/${ANDROID_HOST_TAG}/glslc")
if(NOT EXISTS "${GLSLC}")
    unset(GLSLC)
    find_program(GLSLC glslc)
endif()
find_program(GLSLANG_VALIDATOR glslangValidator)
if(NOT GLSLC AND NOT GLSLANG_VALIDATOR)
    message(FATAL_ERROR "WFG shaders need glslc (NDK shader-tools) or glslangValidator")
endif()
add_subdirectory(wfg)

vulkan_shader(window.vert         window_vert)
vulkan_shader(window.frag         window_frag)
vulkan_shader(window_stretch.frag window_stretch_frag)
vulkan_shader(window_sgsr.frag    window_sgsr_frag    USES_POSTFX)
vulkan_shader(window_sgsr_hq.frag window_sgsr_hq_frag USES_POSTFX)
vulkan_shader(window_nis.frag     window_nis_frag     USES_POSTFX)
vulkan_shader(window_fsr1.frag    window_fsr1_frag    USES_POSTFX)
vulkan_shader(window_lanczos.frag window_lanczos_frag USES_POSTFX)
vulkan_shader(window_postfx.frag  window_postfx_frag  USES_POSTFX)

include_directories(
        ${CMAKE_CURRENT_BINARY_DIR}
        ${VULKAN_RENDERER_DIR}
)

add_library(vulkan_renderer SHARED
        winlator/renderer/vulkan/vulkan_jni.cpp
        winlator/renderer/vulkan/VulkanRendererContext.cpp
        winlator/renderer/vulkan/VulkanRendererFilters.cpp
        winlator/renderer/vulkan/VulkanRendererWfg.cpp

        ${VULKAN_SHADER_HEADERS}
)

set_target_properties(vulkan_renderer PROPERTIES
        CXX_STANDARD 17
        CXX_STANDARD_REQUIRED ON
        CXX_EXTENSIONS OFF
)

target_compile_options(vulkan_renderer PRIVATE
        -Wall -Wextra -fvisibility=hidden
)

target_link_libraries(vulkan_renderer
        log
        android
        vulkan
        adrenotools
        dl
        atomic
        wfg
)

# Optional Steam backend (Rust protocol client, libwinlitesteam.so). Packaged through a separate
# native target so the emulator core never loads Steam as a DT_NEEDED dependency when the
# feature is disabled.
add_subdirectory(winlite-steam-client)
add_dependencies(winlator winlitesteam_rust_build)
add_library(steam_optional SHARED steam_optional.c)
target_link_options(steam_optional PRIVATE "-Wl,--no-as-needed")
target_link_libraries(steam_optional winlitesteam)
add_dependencies(steam_optional winlitesteam_rust_build)
