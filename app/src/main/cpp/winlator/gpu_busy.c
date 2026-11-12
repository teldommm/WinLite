/*
 * GPU load of an Adreno GPU without root (java: com.winlator.cmod.core.KgslGpuLoad).
 *
 * Used by the HUD when the kgsl sysfs nodes (gpubusy, ...) are not readable for an app, which is the
 * case on most devices. The memstore of /dev/kgsl-3d0 - a node the app may open, the Vulkan driver
 * does too - is mapped read-only and a low-priority thread samples the start / end timestamps in it
 * (see gpu_busy_core.h). Nothing is submitted to the GPU and no clock or power setting is touched.
 *
 * The thread starts when the session is opened and keeps counting while the Java side keeps reading
 * the counters. When nobody has read them for a few seconds (HUD hidden, caller gone) it parks
 * itself instead of sampling for nothing; the next read wakes it up again.
 */
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <time.h>
#include <unistd.h>

#include "gpu_busy_core.h"

/*
 * The part of the kgsl UAPI (msm_kgsl.h) that is needed: ask the device for its "shadow" property,
 * i.e. where the memstore is. Declared here so the build does not depend on a kernel header copy.
 */
#define KGSL_IOC_TYPE            0x09
#define KGSL_PROP_DEVICE_SHADOW  0x05

struct kgsl_getproperty_req {
    unsigned int type;
    void *value;
    size_t sizebytes;
};

struct kgsl_shadow_info {
    unsigned long gpuaddr;      /* the offset to give mmap() */
    size_t size;
    unsigned int flags;
};

#define KGSL_GETPROPERTY_IOCTL _IOWR(KGSL_IOC_TYPE, 0x2, struct kgsl_getproperty_req)

#define SAMPLE_INTERVAL_NS  1000000L        /* 1 ms */
#define CONTEXT_RESCAN_EVERY 200            /* samples between looks for new / gone contexts */
#define PARK_AFTER_NS       5000000000LL    /* unread for 5 s: stop sampling */
#define PARK_POLL_NS        50000000L

struct session {
    int fd;
    void *map;
    size_t map_bytes;
    int rb_base;
    int context_mode;

    struct gb_watch watch;          /* owned by the sampler thread once it runs */

    pthread_t thread;
    int thread_started;
    atomic_int quit;
    atomic_llong last_read_ns;
    atomic_uint_fast64_t busy_samples;
    atomic_uint_fast64_t samples;
    atomic_uint_fast64_t moves;
};

static long long now_ns(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static void sleep_ns(long ns)
{
    struct timespec ts = {0, ns};
    nanosleep(&ts, NULL);
}

static void *sampler_main(void *arg)
{
    struct session *s = arg;
    pthread_setname_np(pthread_self(), "gpu-busy");
    setpriority(PRIO_PROCESS, 0, 10);

    int until_rescan = 0;
    while (!atomic_load(&s->quit)) {
        if (now_ns() - atomic_load(&s->last_read_ns) > PARK_AFTER_NS) {
            sleep_ns(PARK_POLL_NS);
            continue;
        }
        if (s->context_mode && until_rescan-- <= 0) {
            gb_watch_contexts(&s->watch, s->map, s->rb_base);
            until_rescan = CONTEXT_RESCAN_EVERY;
        }
        int moved = 0;
        int busy = gb_sample(s->map, &s->watch, &moved);
        if (busy) atomic_fetch_add(&s->busy_samples, 1);
        if (moved) atomic_fetch_add(&s->moves, 1);
        atomic_fetch_add(&s->samples, 1);
        sleep_ns(SAMPLE_INTERVAL_NS);
    }
    return NULL;
}

static void session_free(struct session *s)
{
    if (!s) return;
    if (s->thread_started) {
        atomic_store(&s->quit, 1);
        pthread_join(s->thread, NULL);
    }
    if (s->map && s->map != MAP_FAILED) munmap(s->map, s->map_bytes);
    if (s->fd >= 0) close(s->fd);
    free(s);
}

/* 0 on success (and *out set), else a negative errno. */
static int session_open(struct session **out)
{
    struct session *s = calloc(1, sizeof(*s));
    if (!s) return -ENOMEM;
    s->fd = open("/dev/kgsl-3d0", O_RDWR | O_CLOEXEC);
    if (s->fd < 0) {
        int err = -errno;
        session_free(s);
        return err;
    }

    struct kgsl_shadow_info shadow;
    memset(&shadow, 0, sizeof(shadow));
    struct kgsl_getproperty_req req = {
        .type = KGSL_PROP_DEVICE_SHADOW, .value = &shadow, .sizebytes = sizeof(shadow),
    };
    if (ioctl(s->fd, KGSL_GETPROPERTY_IOCTL, &req) != 0) {
        int err = -errno;
        session_free(s);
        return err;
    }
    s->map_bytes = shadow.size;
    s->rb_base = gb_ringbuffer_base(shadow.size);
    if (s->rb_base < 0) {
        session_free(s);
        return -ERANGE;
    }
    s->map = mmap(NULL, s->map_bytes, PROT_READ, MAP_SHARED, s->fd, (off_t)shadow.gpuaddr);
    if (s->map == MAP_FAILED) {
        int err = -errno;
        session_free(s);
        return err;
    }

    if (gb_ringbuffer_in_use(s->map, s->rb_base)) {
        gb_watch_ringbuffers(&s->watch, s->map, s->rb_base);
    } else {
        s->context_mode = 1;
        gb_watch_contexts(&s->watch, s->map, s->rb_base);
    }

    atomic_store(&s->last_read_ns, now_ns());
    if (pthread_create(&s->thread, NULL, sampler_main, s) != 0) {
        session_free(s);
        return -EAGAIN;
    }
    s->thread_started = 1;
    *out = s;
    return 0;
}

JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_core_KgslGpuLoad_nativeOpen(JNIEnv *env, jclass clazz, jintArray error)
{
    struct session *s = NULL;
    int err = session_open(&s);
    if (err != 0 && error && (*env)->GetArrayLength(env, error) > 0) {
        jint code = err;
        (*env)->SetIntArrayRegion(env, error, 0, 1, &code);
    }
    return err != 0 ? 0 : (jlong)(intptr_t)s;
}

/* 1 when the contexts' own entries are sampled (hardware-scheduled kernel), 0 for the ringbuffers. */
JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_core_KgslGpuLoad_nativeIsContextMode(JNIEnv *env, jclass clazz, jlong handle)
{
    return handle != 0 && ((struct session *)(intptr_t)handle)->context_mode ? JNI_TRUE : JNI_FALSE;
}

/* out = {busy samples, samples, samples in which a start stamp moved}: running totals. */
JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_core_KgslGpuLoad_nativeRead(JNIEnv *env, jclass clazz, jlong handle, jlongArray out)
{
    if (handle == 0 || !out || (*env)->GetArrayLength(env, out) < 3) return JNI_FALSE;
    struct session *s = (struct session *)(intptr_t)handle;
    atomic_store(&s->last_read_ns, now_ns());
    jlong v[3] = {
        (jlong)atomic_load(&s->busy_samples),
        (jlong)atomic_load(&s->samples),
        (jlong)atomic_load(&s->moves),
    };
    (*env)->SetLongArrayRegion(env, out, 0, 3, v);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_core_KgslGpuLoad_nativeClose(JNIEnv *env, jclass clazz, jlong handle)
{
    session_free((struct session *)(intptr_t)handle);
}
