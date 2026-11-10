/*
 * Per-thread CPU affinity for the thread placement controller
 * (java: com.winlator.cmod.core.ThreadAffinity / ThreadPlacementController).
 *
 * Wine's SetProcessAffinityMask only works on whole processes, so individual guest threads
 * cannot be moved from Java. The raw syscalls below take a TID and work for any thread of the
 * same uid (the Wine processes are children of the app), so no root is needed.
 *
 * Masks are a single 64-bit word: bit N = CPU N. Devices with more than 62 CPUs are not
 * supported (the Java side never enables the controller there).
 *
 * Raw syscalls instead of sched_setaffinity(): no dependency on the cpu_set_t size of the libc,
 * and the kernel zero-fills a mask shorter than its own cpumask.
 */
#include <errno.h>
#include <jni.h>
#include <stdint.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <unistd.h>

#define AFFINITY_BYTES ((int)sizeof(uint64_t))

/* 0 on success, -errno on failure. */
JNIEXPORT jint JNICALL
Java_com_winlator_cmod_core_ThreadAffinity_nativeSetAffinity(JNIEnv *env, jclass clazz, jint tid, jlong mask)
{
    uint64_t bits = (uint64_t)mask;
    if (tid <= 0 || bits == 0) return -EINVAL;
    if (syscall(SYS_sched_setaffinity, (pid_t)tid, AFFINITY_BYTES, &bits) != 0) return -errno;
    return 0;
}

/* The thread's current mask, or -1 on failure (the thread is gone, or the kernel has more CPU
 * slots than a 64-bit word can hold). */
JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_core_ThreadAffinity_nativeGetAffinity(JNIEnv *env, jclass clazz, jint tid)
{
    uint64_t bits = 0;
    if (tid <= 0) return -1;
    if (syscall(SYS_sched_getaffinity, (pid_t)tid, AFFINITY_BYTES, &bits) < 0) return -1;
    return (jlong)bits;
}
