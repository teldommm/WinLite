#include <aaudio/AAudio.h>
#include <android/log.h>
#include <jni.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define LOG_TAG "ALSAClient"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

#define WAIT_COMPLETION_TIMEOUT (100 * 1000000L)
#define MAX_REOPEN_ATTEMPTS 1

// Guest sample formats, in the order of ALSAClient.DataType.
enum Format {U8, S16LE, S16BE, FLOATLE, FLOATBE};

typedef struct {
    AAudioStream *stream;

    // Parameters the stream is (re)opened with.
    int32_t format;
    int32_t channelCount;
    int32_t sampleRate;
    int32_t performanceMode;
    int32_t targetFrames;   // starting buffer size: max(guest ALSA buffer, latency floor)
    int32_t capacityFrames; // requested capacity, leaves room to grow on underruns

    // Live state.
    int32_t bufferFrames;   // current buffer size, grows on underruns
    int32_t lastXRunCount;
    bool started;

    // Conversion scratch for formats AAudio cannot take as-is (U8, big-endian).
    void *scratch;
    size_t scratchBytes;
} AlsaStream;

static aaudio_format_t toAAudioFormat(int32_t format) {
    switch (format) {
        case FLOATLE:
        case FLOATBE:
            return AAUDIO_FORMAT_PCM_FLOAT;
        case U8:      // converted to I16 before writing
        case S16LE:
        case S16BE:
        default:
            return AAUDIO_FORMAT_PCM_I16;
    }
}

static int32_t outputSampleBytes(int32_t format) {
    return toAAudioFormat(format) == AAUDIO_FORMAT_PCM_FLOAT ? 4 : 2;
}

static bool openStream(AlsaStream *s) {
    AAudioStreamBuilder *builder;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) return false;

    AAudioStreamBuilder_setPerformanceMode(builder, s->performanceMode);
    AAudioStreamBuilder_setFormat(builder, toAAudioFormat(s->format));
    AAudioStreamBuilder_setChannelCount(builder, s->channelCount);
    AAudioStreamBuilder_setSampleRate(builder, s->sampleRate);
    AAudioStreamBuilder_setBufferCapacityInFrames(builder, s->capacityFrames);

    AAudioStream *stream = NULL;
    aaudio_result_t result = AAudioStreamBuilder_openStream(builder, &stream);
    AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK) {
        LOGW("openStream failed: %s", AAudio_convertResultToText(result));
        return false;
    }

    int32_t capacity = AAudioStream_getBufferCapacityInFrames(stream);
    int32_t wanted = s->bufferFrames > 0 ? s->bufferFrames : s->targetFrames;
    if (capacity > 0 && wanted > capacity) wanted = capacity;
    s->bufferFrames = AAudioStream_setBufferSizeInFrames(stream, wanted);
    if (s->bufferFrames <= 0) s->bufferFrames = wanted;
    s->lastXRunCount = AAudioStream_getXRunCount(stream);
    s->stream = stream;

    LOGI("stream opened: rate=%d ch=%d fmt=%d buffer=%d capacity=%d burst=%d perf=%d",
         s->sampleRate, s->channelCount, s->format, s->bufferFrames, capacity,
         AAudioStream_getFramesPerBurst(stream), AAudioStream_getPerformanceMode(stream));
    return true;
}

static void closeStream(AlsaStream *s) {
    if (s->stream) {
        AAudioStream_close(s->stream);
        s->stream = NULL;
    }
}

static void requestStart(AlsaStream *s) {
    if (!s->stream) return;
    AAudioStream_requestStart(s->stream);
    AAudioStream_waitForStateChange(s->stream, AAUDIO_STREAM_STATE_STARTING, NULL, WAIT_COMPLETION_TIMEOUT);
}

// The output device went away (headphones/Bluetooth switch, route change, audio server restart):
// a disconnected AAudio stream never recovers, so open a fresh one with the same parameters.
static bool reopenStream(AlsaStream *s) {
    LOGI("stream disconnected, reopening");
    closeStream(s);
    if (!openStream(s)) return false;
    if (s->started) requestStart(s);
    return true;
}

static bool isDisconnected(AlsaStream *s, aaudio_result_t result) {
    return result == AAUDIO_ERROR_DISCONNECTED
           || (s->stream && AAudioStream_getState(s->stream) == AAUDIO_STREAM_STATE_DISCONNECTED);
}

// Underruns mean the guest's buffer accounting got ahead of what the device actually holds;
// grow the device buffer by one burst per new underrun, up to the stream capacity.
static void growOnUnderrun(AlsaStream *s) {
    int32_t xruns = AAudioStream_getXRunCount(s->stream);
    if (xruns <= s->lastXRunCount) return;
    s->lastXRunCount = xruns;

    int32_t capacity = AAudioStream_getBufferCapacityInFrames(s->stream);
    if (s->bufferFrames >= capacity) return;
    int32_t burst = AAudioStream_getFramesPerBurst(s->stream);
    if (burst <= 0) burst = 256;
    int32_t next = s->bufferFrames + burst;
    if (next > capacity) next = capacity;
    int32_t applied = AAudioStream_setBufferSizeInFrames(s->stream, next);
    if (applied > 0) s->bufferFrames = applied;
}

static void *ensureScratch(AlsaStream *s, size_t bytes) {
    if (s->scratchBytes < bytes) {
        void *grown = realloc(s->scratch, bytes);
        if (!grown) return NULL;
        s->scratch = grown;
        s->scratchBytes = bytes;
    }
    return s->scratch;
}

// Returns data in the stream's format: U8 -> I16, big-endian -> little-endian, else as-is.
static const void *convertForStream(AlsaStream *s, const void *data, int32_t numFrames) {
    int32_t samples = numFrames * s->channelCount;
    switch (s->format) {
        case U8: {
            int16_t *out = ensureScratch(s, (size_t)samples * 2);
            if (!out) return NULL;
            const uint8_t *in = data;
            for (int32_t i = 0; i < samples; i++) out[i] = (int16_t)(((int32_t)in[i] - 128) << 8);
            return out;
        }
        case S16BE: {
            uint16_t *out = ensureScratch(s, (size_t)samples * 2);
            if (!out) return NULL;
            const uint16_t *in = data;
            for (int32_t i = 0; i < samples; i++) out[i] = __builtin_bswap16(in[i]);
            return out;
        }
        case FLOATBE: {
            uint32_t *out = ensureScratch(s, (size_t)samples * 4);
            if (!out) return NULL;
            const uint32_t *in = data;
            for (int32_t i = 0; i < samples; i++) out[i] = __builtin_bswap32(in[i]);
            return out;
        }
        default:
            return data;
    }
}

// Writes every frame (looping over partial writes) instead of dropping what did not fit in
// one call. Stops early only on a timeout (stream not consuming) or an unrecoverable error.
static int32_t writeAll(AlsaStream *s, const void *data, int32_t numFrames) {
    const void *src = convertForStream(s, data, numFrames);
    if (!src) return -1;

    const int32_t frameBytes = outputSampleBytes(s->format) * s->channelCount;
    int32_t written = 0;
    int reopenAttempts = 0;

    while (written < numFrames && s->stream) {
        aaudio_result_t result = AAudioStream_write(s->stream, (const uint8_t *)src + (size_t)written * frameBytes,
                                                    numFrames - written, WAIT_COMPLETION_TIMEOUT);
        if (result < 0) {
            if (isDisconnected(s, result) && reopenAttempts++ < MAX_REOPEN_ATTEMPTS && reopenStream(s)) continue;
            LOGW("write failed: %s", AAudio_convertResultToText(result));
            break;
        }
        if (result == 0) break;
        written += result;
    }

    if (s->stream) growOnUnderrun(s);
    return written > 0 ? written : (s->stream ? 0 : -1);
}

JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_alsaserver_ALSAClient_create(JNIEnv *env, jobject obj, jint format, jbyte channelCount,
                                                   jint sampleRate, jint bufferSize, jint latencyFrames,
                                                   jint performanceMode) {
    AlsaStream *s = calloc(1, sizeof(AlsaStream));
    if (!s) return 0;

    s->format = format;
    s->channelCount = channelCount;
    s->sampleRate = sampleRate;
    s->performanceMode = performanceMode;
    s->targetFrames = bufferSize > latencyFrames ? bufferSize : latencyFrames;
    // Twice the target leaves headroom for underrun growth; the extra capacity adds no latency
    // until it is actually used.
    s->capacityFrames = s->targetFrames * 2;

    if (!openStream(s)) {
        free(s);
        return 0;
    }
    return (jlong)(intptr_t)s;
}

JNIEXPORT jint JNICALL
Java_com_winlator_cmod_alsaserver_ALSAClient_write(JNIEnv *env, jobject obj, jlong ptr, jobject buffer, jint numFrames) {
    AlsaStream *s = (AlsaStream *)(intptr_t)ptr;
    if (!s || numFrames <= 0) return -1;
    void *data = (*env)->GetDirectBufferAddress(env, buffer);
    if (!data) return -1;
    return writeAll(s, data, numFrames);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_alsaserver_ALSAClient_start(JNIEnv *env, jobject obj, jlong ptr) {
    AlsaStream *s = (AlsaStream *)(intptr_t)ptr;
    if (!s) return;
    s->started = true;
    if (!s->stream || isDisconnected(s, AAUDIO_OK)) {
        reopenStream(s); // also starts it
        return;
    }
    requestStart(s);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_alsaserver_ALSAClient_stop(JNIEnv *env, jobject obj, jlong ptr) {
    AlsaStream *s = (AlsaStream *)(intptr_t)ptr;
    if (!s) return;
    s->started = false;
    if (!s->stream) return;
    AAudioStream_requestStop(s->stream);
    AAudioStream_waitForStateChange(s->stream, AAUDIO_STREAM_STATE_STOPPING, NULL, WAIT_COMPLETION_TIMEOUT);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_alsaserver_ALSAClient_pause(JNIEnv *env, jobject obj, jlong ptr) {
    AlsaStream *s = (AlsaStream *)(intptr_t)ptr;
    if (!s) return;
    s->started = false;
    if (!s->stream) return;
    AAudioStream_requestPause(s->stream);
    AAudioStream_waitForStateChange(s->stream, AAUDIO_STREAM_STATE_PAUSING, NULL, WAIT_COMPLETION_TIMEOUT);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_alsaserver_ALSAClient_close(JNIEnv *env, jobject obj, jlong ptr) {
    AlsaStream *s = (AlsaStream *)(intptr_t)ptr;
    if (!s) return;
    closeStream(s);
    free(s->scratch);
    free(s);
}
