#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum AifgStatus {
    AIFG_OK = 0,
    AIFG_NOT_INSTALLED = 1,
    AIFG_UNREADABLE_FILE = 2,
    AIFG_NOT_PORTABLE_EXECUTABLE = 3,
    AIFG_MISSING_SHADERS = 4,
    AIFG_TRANSLATION_FAILED = 5,
    AIFG_CACHE_UNUSABLE = 6
} AifgStatus;

typedef enum AifgVariant {
    AIFG_VARIANT_NONE = 0,
    AIFG_VARIANT_FP16 = 1,
    AIFG_VARIANT_FP32 = 2,
    AIFG_VARIANT_DXBC = 3
} AifgVariant;

#define AIFG_SHADER_MIPMAPS     255u
#define AIFG_SHADER_GENERATE    256u
#define AIFG_SHADER_PERF_FIRST  280u
#define AIFG_SHADER_PERF_LAST   302u
#define AIFG_SHADER_COUNT       25u

typedef struct AifgModule {
    uint32_t  id;
    uint32_t* words;
    uint32_t  word_count;
} AifgModule;

typedef struct AifgModuleSet {
    AifgModule  modules[AIFG_SHADER_COUNT];
    uint32_t    count;
    AifgVariant variant;
} AifgModuleSet;

const uint32_t* aifg_shader_ids(size_t* out_count);

AifgStatus aifg_validate_dll(const char* dll_path);

AifgVariant aifg_dll_variant(const char* dll_path);

AifgStatus aifg_build_cache(const char* dll_path, const char* cache_path, bool prefer_fp16);

AifgStatus aifg_load_modules(const char* cache_path, AifgModuleSet* out_set);

AifgStatus aifg_cache_matches_source(const char* cache_path, const char* dll_path,
                                     bool* out_matches);

void aifg_release_modules(AifgModuleSet* set);

const uint32_t* aifg_find_module(const AifgModuleSet* set, uint32_t id, uint32_t* out_word_count);

#ifdef __cplusplus
}
#endif
