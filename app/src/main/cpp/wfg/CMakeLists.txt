# WFG (WinNative/Winlator Frame Generator, MIT): Dense Inverse Search (DIS) optical flow and
# the frame interpolator built on it, as one static library linked into vulkan_renderer.
#
# Layout:
#   include/vkr_dis.h      public API (plain Vulkan types)
#   include/shaders/*.comp GLSL compute shaders, and the interpolation's .vert/.frag pair,
#                          compiled to SPIR-V headers at build time
#   src/                   implementation and WFG's own Vulkan dispatch table (vk_dispatch.*),
#                          bound to the renderer's driver at run time
#
# Uses GLSLC (or, when it is not available, GLSLANG_VALIDATOR) from the parent CMakeLists.

set(DIS_SHADER_SRC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/include/shaders")
set(DIS_SHADER_OUT_DIR "${CMAKE_CURRENT_BINARY_DIR}/shaders")
set(DIS_BIN2C_SCRIPT "${CMAKE_CURRENT_SOURCE_DIR}/bin2c.cmake")
file(MAKE_DIRECTORY "${DIS_SHADER_OUT_DIR}")

# Full file names: the stage comes from the extension, and the header's variable is the name with
# the dot as an underscore (dis_luma_r16.comp -> dis_luma_r16_comp).
set(DIS_SHADERS
    dis_luma_r16.comp
    dis_luma_r32.comp
    dis_gradient.comp
    dis_inverse_search.comp
    dis_propagate.comp
    dis_densify.comp
    dis_fullscreen.vert
    dis_interpolate.frag
    dis_hist.comp
    dis_side.comp
    dis_flow_pack.comp
    dis_splat.comp
    dis_fill.comp
    dis_vr_prep.comp
    dis_vr_d1.comp
    dis_vr_d2.comp
    dis_vr_w.comp
    dis_vr_coef.comp
    dis_vr_sor.comp
    dis_vr_add.comp
    dis_fb_poly.comp
    dis_fb_hsum.comp
    dis_fb_vsolve.comp
)

# Variants compiled from another shader's source with one define: "output|source|DEFINE".
# The output name gives the header's variable the same way (dis_hist_r16.comp -> dis_hist_r16_comp).
set(DIS_SHADER_VARIANTS
    "dis_hist_r16.comp|dis_hist.comp|DIS_HIST_R16"
)

set(DIS_SHADER_BUILDS "")
foreach(file ${DIS_SHADERS})
    list(APPEND DIS_SHADER_BUILDS "${file}|${file}|")
endforeach()
list(APPEND DIS_SHADER_BUILDS ${DIS_SHADER_VARIANTS})

set(DIS_SHADER_HEADERS "")
foreach(build ${DIS_SHADER_BUILDS})
    string(REPLACE "|" ";" parts "${build}")
    list(GET parts 0 file)
    list(GET parts 1 source)
    list(LENGTH parts nparts)
    set(define "")
    if(nparts GREATER 2)
        list(GET parts 2 define)
    endif()
    string(REPLACE "." "_" var "${file}")
    set(input "${DIS_SHADER_SRC_DIR}/${source}")
    set(spv   "${DIS_SHADER_OUT_DIR}/${var}.spv")
    set(hdr   "${DIS_SHADER_OUT_DIR}/${var}.spv.h")
    set(define_arg "")
    if(define)
        set(define_arg "-D${define}")
    endif()
    if(GLSLC)
        set(compile_cmd "${GLSLC}" --target-env=vulkan1.1 -O ${define_arg} "${input}" -o "${spv}")
    else()
        set(compile_cmd "${GLSLANG_VALIDATOR}" -V --target-env vulkan1.1 ${define_arg} "${input}" -o "${spv}")
    endif()
    add_custom_command(
        OUTPUT  "${hdr}"
        COMMAND ${compile_cmd}
        COMMAND "${CMAKE_COMMAND}"
            -DINPUT_FILE=${spv}
            -DOUTPUT_FILE=${hdr}
            -DVAR_NAME=${var}
            -P "${DIS_BIN2C_SCRIPT}"
        DEPENDS "${input}" "${DIS_BIN2C_SCRIPT}"
        COMMENT "Compiling WFG shader ${file} -> ${var}.spv.h"
        VERBATIM
    )
    list(APPEND DIS_SHADER_HEADERS "${hdr}")
endforeach()

add_custom_target(wfg_shaders DEPENDS ${DIS_SHADER_HEADERS})

add_library(wfg STATIC
        src/vkr_dis.c
        src/vk_dispatch.c
)
add_dependencies(wfg wfg_shaders)

set_target_properties(wfg PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_compile_options(wfg PRIVATE -O2 -Wall -Wextra -fvisibility=hidden)
target_compile_definitions(wfg PUBLIC VK_USE_PLATFORM_ANDROID_KHR)
target_include_directories(wfg
        PUBLIC
            ${CMAKE_CURRENT_SOURCE_DIR}/include
        PRIVATE
            ${CMAKE_CURRENT_SOURCE_DIR}/src
            ${CMAKE_CURRENT_BINARY_DIR}
)
target_link_libraries(wfg PRIVATE log dl)
