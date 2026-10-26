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
        winlator/renderer/vulkan/VulkanRendererAdpf.cpp
        winlator/renderer/vulkan/adpf/adpf_session.cpp
        winlator/renderer/vulkan/VulkanRendererFilters.cpp
        winlator/renderer/vulkan/VulkanRendererPipelineOpt.cpp

        winlator/renderer/vulkan/view_transformation.cpp
        winlator/renderer/vulkan/xform.cpp

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
        dxbc
        log
        android
        vulkan
        adrenotools
        dl
        atomic
)
