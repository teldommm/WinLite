#include <jni.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>

#include "aifg_dll.h"
#include "aifg_probe.h"

#define AIFG_FN(name) Java_com_winlator_cmod_runtime_display_aifg_AifgFrameGen_##name

static char* copy_utf(JNIEnv* env, jstring value) {
    if (!value) return NULL;
    const char* chars = (*env)->GetStringUTFChars(env, value, NULL);
    if (!chars) return NULL;
    char* copy = strdup(chars);
    (*env)->ReleaseStringUTFChars(env, value, chars);
    return copy;
}

JNIEXPORT jint JNICALL AIFG_FN(nativeValidateDll)(JNIEnv* env, jclass clazz, jstring dllPath) {
    (void)clazz;
    char* path = copy_utf(env, dllPath);
    if (!path) return (jint)AIFG_NOT_INSTALLED;
    const AifgStatus status = aifg_validate_dll(path);
    free(path);
    return (jint)status;
}

JNIEXPORT jint JNICALL AIFG_FN(nativeDllVariant)(JNIEnv* env, jclass clazz, jstring dllPath) {
    (void)clazz;
    char* path = copy_utf(env, dllPath);
    if (!path) return (jint)AIFG_VARIANT_NONE;
    const AifgVariant variant = aifg_dll_variant(path);
    free(path);
    return (jint)variant;
}

JNIEXPORT jint JNICALL AIFG_FN(nativeBuildCache)(JNIEnv* env, jclass clazz, jstring dllPath,
                                                 jstring cachePath, jboolean preferFp16) {
    (void)clazz;
    char* dll = copy_utf(env, dllPath);
    char* cache = copy_utf(env, cachePath);
    AifgStatus status = AIFG_NOT_INSTALLED;
    if (dll && cache) status = aifg_build_cache(dll, cache, preferFp16 == JNI_TRUE);
    free(dll);
    free(cache);
    return (jint)status;
}

JNIEXPORT jboolean JNICALL AIFG_FN(nativeCacheMatchesSource)(JNIEnv* env, jclass clazz,
                                                             jstring cachePath, jstring dllPath) {
    (void)clazz;
    char* cache = copy_utf(env, cachePath);
    char* dll = copy_utf(env, dllPath);
    bool matches = false;
    if (cache && dll) {
        if (aifg_cache_matches_source(cache, dll, &matches) != AIFG_OK) matches = false;
    }
    free(cache);
    free(dll);
    return matches ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL AIFG_FN(nativeInspectCache)(JNIEnv* env, jclass clazz, jstring cachePath) {
    (void)clazz;
    char* cache = copy_utf(env, cachePath);
    if (!cache) return (jint)AIFG_NOT_INSTALLED;

    AifgModuleSet set;
    const AifgStatus status = aifg_load_modules(cache, &set);
    if (status == AIFG_OK) aifg_release_modules(&set);
    free(cache);
    return (jint)status;
}

JNIEXPORT jint JNICALL AIFG_FN(nativeCacheVariant)(JNIEnv* env, jclass clazz, jstring cachePath) {
    (void)clazz;
    char* cache = copy_utf(env, cachePath);
    if (!cache) return (jint)AIFG_VARIANT_NONE;

    AifgModuleSet set;
    AifgVariant variant = AIFG_VARIANT_NONE;
    if (aifg_load_modules(cache, &set) == AIFG_OK) {
        variant = set.variant;
        aifg_release_modules(&set);
    }
    free(cache);
    return (jint)variant;
}

JNIEXPORT jboolean JNICALL AIFG_FN(nativeSupportsFrameGeneration)(JNIEnv* env, jclass clazz,
                                                                  jstring driverName,
                                                                  jobject context) {
    (void)clazz;
    char* driver = copy_utf(env, driverName);
    const bool supported = aifg_probe_support(env, context, driver);
    free(driver);
    return supported ? JNI_TRUE : JNI_FALSE;
}
