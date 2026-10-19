cmake_minimum_required(VERSION 3.22.1)

project(Winlator)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -Wimplicit-function-declaration")

add_subdirectory(adrenotools)
add_subdirectory(thirdparty/dxbc)

# Add Winlator shared library
# NOTE: the DisplayX and EGL X-server renderer path (winlator/renderer/{egl,window,shader,
# cursor,xform,view_transformation,displayx,renderer_jni}.* and renderer/effectcomposer/*)
# was removed. Vulkan (vulkan_renderer, below) is now the only X-server presentation path.
# The OpenXR/VR module (xr/*.c, OpenXR-SDK/) was also removed: it was already fully
# disconnected in this fork (no Java call site ever invoked its native entry points, and
# the JNI symbol names didn't even match the actual Java package).
add_library(winlator SHARED
        winlator/drawable.c
        winlator/gpu_image.c
        winlator/process_helper.c
        winlator/sysvshared_memory.c
        winlator/xconnector_epoll.c
        winlator/alsa_client.c
        winlator/vulkan.c
)

target_compile_options(winlator PRIVATE -Wall -Wextra)

target_link_libraries(winlator
        log
        android
        jnigraphics
        aaudio
        adrenotools
        vulkan
)

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

# Copy-on-write Wine prefix overlay (loaded via LD_PRELOAD). A per-container "upper" prefix is
# layered over one shared read-only base prefix per Wine/Proton version, so containers only
# store what is theirs. See containeroverlay/README.md. Must NOT be built with FORTIFY.
add_library(containeroverlay SHARED
        containeroverlay/containeroverlay_core.c
        containeroverlay/containeroverlay_hooks.c
)

target_compile_options(containeroverlay PRIVATE
        -fvisibility=hidden
        -U_FORTIFY_SOURCE
        -D_FORTIFY_SOURCE=0
        -O2
)

target_link_libraries(containeroverlay
        log
        dl
)

target_link_options(containeroverlay PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
)

# Vulkan renderer: the sole X-server presentation path (see note above the
# `winlator` target). Still built as its own JNI library (vulkan_renderer.so).
set(VULKAN_RENDERER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/winlator/renderer/vulkan)

add_custom_command(
    OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/window_vert.h
    COMMAND glslangValidator -V --vn window_vert_code
            -x -o ${CMAKE_CURRENT_BINARY_DIR}/window_vert.h
            ${VULKAN_RENDERER_DIR}/window.vert
    DEPENDS ${VULKAN_RENDERER_DIR}/window.vert
    COMMENT "Compiling window.vert -> window_vert.h"
)

add_custom_command(
    OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/window_frag.h
    COMMAND glslangValidator -V --vn window_frag_code
            -x -o ${CMAKE_CURRENT_BINARY_DIR}/window_frag.h
            ${VULKAN_RENDERER_DIR}/window.frag
    DEPENDS ${VULKAN_RENDERER_DIR}/window.frag
    COMMENT "Compiling window.frag -> window_frag.h"
)

add_custom_command(
    OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/window_sgsr_frag.h
    COMMAND glslangValidator -V --vn window_sgsr_frag_code
            -x -o ${CMAKE_CURRENT_BINARY_DIR}/window_sgsr_frag.h
            ${VULKAN_RENDERER_DIR}/window_sgsr.frag
    DEPENDS ${VULKAN_RENDERER_DIR}/window_sgsr.frag ${VULKAN_RENDERER_DIR}/postfx_common.glsl
    COMMENT "Compiling window_sgsr.frag -> window_sgsr_frag.h"
)

add_custom_command(
    OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/window_sgsr_hq_frag.h
    COMMAND glslangValidator -V --vn window_sgsr_hq_frag_code
            -x -o ${CMAKE_CURRENT_BINARY_DIR}/window_sgsr_hq_frag.h
            ${VULKAN_RENDERER_DIR}/window_sgsr_hq.frag
    DEPENDS ${VULKAN_RENDERER_DIR}/window_sgsr_hq.frag ${VULKAN_RENDERER_DIR}/postfx_common.glsl
    COMMENT "Compiling window_sgsr_hq.frag -> window_sgsr_hq_frag.h"
)

add_custom_command(
    OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/window_nis_frag.h
    COMMAND glslangValidator -V --vn window_nis_frag_code
            -x -o ${CMAKE_CURRENT_BINARY_DIR}/window_nis_frag.h
            ${VULKAN_RENDERER_DIR}/window_nis.frag
    DEPENDS ${VULKAN_RENDERER_DIR}/window_nis.frag ${VULKAN_RENDERER_DIR}/postfx_common.glsl
    COMMENT "Compiling window_nis.frag -> window_nis_frag.h"
)

add_custom_command(
    OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/window_fsr1_frag.h
    COMMAND glslangValidator -V --vn window_fsr1_frag_code
            -x -o ${CMAKE_CURRENT_BINARY_DIR}/window_fsr1_frag.h
            ${VULKAN_RENDERER_DIR}/window_fsr1.frag
    DEPENDS ${VULKAN_RENDERER_DIR}/window_fsr1.frag ${VULKAN_RENDERER_DIR}/postfx_common.glsl
    COMMENT "Compiling single-pass FSR -> window_fsr1_frag.h"
)

add_custom_command(
    OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/window_lanczos_frag.h
    COMMAND glslangValidator -V --vn window_lanczos_frag_code
            -x -o ${CMAKE_CURRENT_BINARY_DIR}/window_lanczos_frag.h
            ${VULKAN_RENDERER_DIR}/window_lanczos.frag
    DEPENDS ${VULKAN_RENDERER_DIR}/window_lanczos.frag ${VULKAN_RENDERER_DIR}/postfx_common.glsl
    COMMENT "Compiling Lanczos 2 -> window_lanczos_frag.h"
)

add_custom_command(
    OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/window_stretch_frag.h
    COMMAND glslangValidator -V --vn window_stretch_frag_code
            -x -o ${CMAKE_CURRENT_BINARY_DIR}/window_stretch_frag.h
            ${VULKAN_RENDERER_DIR}/window_stretch.frag
    DEPENDS ${VULKAN_RENDERER_DIR}/window_stretch.frag
    COMMENT "Compiling window_stretch.frag -> window_stretch_frag.h"
)

add_custom_command(
    OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/window_postfx_frag.h
    COMMAND glslangValidator -V --vn window_postfx_frag_code
            -x -o ${CMAKE_CURRENT_BINARY_DIR}/window_postfx_frag.h
            ${VULKAN_RENDERER_DIR}/window_postfx.frag
    DEPENDS ${VULKAN_RENDERER_DIR}/window_postfx.frag ${VULKAN_RENDERER_DIR}/postfx_common.glsl
    COMMENT "Compiling window_postfx.frag -> window_postfx_frag.h"
)

include_directories(
        ${CMAKE_CURRENT_BINARY_DIR}
        ${VULKAN_RENDERER_DIR}
)

add_library(vulkan_renderer SHARED
        winlator/renderer/vulkan/vulkan_jni.cpp
        winlator/renderer/vulkan/VulkanRendererLsfg.cpp
        winlator/renderer/vulkan/lsfg/lsfg_alpha.cpp
        winlator/renderer/vulkan/lsfg/lsfg_beta.cpp
        winlator/renderer/vulkan/lsfg/lsfg_chain.cpp
        winlator/renderer/vulkan/lsfg/lsfg_common.cpp
        winlator/renderer/vulkan/lsfg/lsfg_delta.cpp
        winlator/renderer/vulkan/lsfg/lsfg_dll.cpp
        winlator/renderer/vulkan/lsfg/lsfg_dxbc.cpp
        winlator/renderer/vulkan/lsfg/lsfg_engine.cpp
        winlator/renderer/vulkan/lsfg/lsfg_gamma.cpp
        winlator/renderer/vulkan/lsfg/lsfg_generate.cpp
        winlator/renderer/vulkan/lsfg/lsfg_jni.cpp
        winlator/renderer/vulkan/lsfg/lsfg_mipmaps.cpp
        winlator/renderer/vulkan/lsfg/lsfg_pacer.cpp
        winlator/renderer/vulkan/lsfg/lsfg_probe.cpp
        winlator/renderer/vulkan/lsfg/lsfg_shaders.cpp
        winlator/renderer/vulkan/lsfg/lsfg_vkd.cpp

        winlator/renderer/vulkan/VulkanRendererContext.cpp
        winlator/renderer/vulkan/VulkanRendererFilters.cpp
        winlator/renderer/vulkan/VulkanRendererPipelineOpt.cpp

        winlator/renderer/vulkan/view_transformation.cpp
        winlator/renderer/vulkan/xform.cpp

        ${CMAKE_CURRENT_BINARY_DIR}/window_vert.h
        ${CMAKE_CURRENT_BINARY_DIR}/window_frag.h
        ${CMAKE_CURRENT_BINARY_DIR}/window_sgsr_frag.h
        ${CMAKE_CURRENT_BINARY_DIR}/window_fsr1_frag.h
        ${CMAKE_CURRENT_BINARY_DIR}/window_lanczos_frag.h
        ${CMAKE_CURRENT_BINARY_DIR}/window_sgsr_hq_frag.h
        ${CMAKE_CURRENT_BINARY_DIR}/window_nis_frag.h
        ${CMAKE_CURRENT_BINARY_DIR}/window_stretch_frag.h
        ${CMAKE_CURRENT_BINARY_DIR}/window_postfx_frag.h
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
        dxbc
        log
        android
        vulkan
        adrenotools
        dl
        atomic
)
