// wl.control, WinLite's side: see WlControl.h and wl_proto.h. JNI for
// com.winlator.cmod.core.WlControl.

#include "WlControl.h"

#include <android/log.h>
#include <fcntl.h>
#include <jni.h>
#include <sys/mman.h>
#include <unistd.h>

#include <atomic>
#include <cerrno>
#include <cstring>
#include <mutex>

#define WLC_TAG "WinLiteWlControl"

namespace wlcontrol {
namespace {

// The page of the current game. A page replaced by a newer one (the next game) stays mapped: a
// writer on another thread (the display clock) may still hold it, and it is 4 KiB.
std::atomic<wl_control*> gPage{nullptr};
std::mutex gMu;                    // open / settings writers
wl_control_settings gSettings{};   // the latest, for a page made after them
bool gHaveSettings = false;
std::mutex gClockMu;               // the clock region has one writer at a time

}  // namespace

bool open(const char* path) {
    if (!path || !*path) return false;
    std::lock_guard<std::mutex> lk(gMu);
    const int fd = ::open(path, O_RDWR | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) {
        __android_log_print(ANDROID_LOG_ERROR, WLC_TAG, "cannot create %s (%d)", path, errno);
        return false;
    }
    if (ftruncate(fd, WL_CONTROL_SIZE) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, WLC_TAG, "cannot size %s (%d)", path, errno);
        close(fd);
        return false;
    }
    void* p = mmap(nullptr, WL_CONTROL_SIZE, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    close(fd);
    if (p == MAP_FAILED) {
        __android_log_print(ANDROID_LOG_ERROR, WLC_TAG, "cannot map %s (%d)", path, errno);
        return false;
    }
    auto* page = static_cast<wl_control*>(p);
    // Made before Wine starts: nobody reads it yet, the settings may follow the identity.
    wl_control_init(page);
    if (gHaveSettings) wl_control_write_settings(page, &gSettings);
    gPage.store(page, std::memory_order_release);
    __android_log_print(ANDROID_LOG_INFO, WLC_TAG, "wl.control at %s", path);
    return true;
}

void writeSettings(const wl_control_settings& s) {
    std::lock_guard<std::mutex> lk(gMu);
    gSettings = s;
    gHaveSettings = true;
    if (wl_control* page = gPage.load(std::memory_order_acquire)) wl_control_write_settings(page, &s);
}

void publishClock(uint64_t periodNs, uint64_t anchorNs) {
    wl_control* page = gPage.load(std::memory_order_acquire);
    if (!page) return;
    const wl_control_clock c{periodNs, anchorNs};
    std::lock_guard<std::mutex> lk(gClockMu);
    wl_control_write_clock(page, &c);
}

bool readStatus(wl_control_status& out) {
    wl_control* page = gPage.load(std::memory_order_acquire);
    return page && wl_control_read_status(page, &out, nullptr);
}

}  // namespace wlcontrol

// ---------------------------------------------------------------- JNI

extern "C" JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_core_WlControl_nativeOpen(JNIEnv* env, jclass, jstring path) {
    if (!path) return JNI_FALSE;
    const char* p = env->GetStringUTFChars(path, nullptr);
    const bool ok = wlcontrol::open(p);
    env->ReleaseStringUTFChars(path, p);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// {output_mode, fg_mode, fg_multiplier, fg_flags, fps_limit, fg_target_fps, fg_flow_side,
//  fg_min_fps, fg_resume_fps}
extern "C" JNIEXPORT void JNICALL
Java_com_winlator_cmod_core_WlControl_nativeSetSettings(JNIEnv* env, jclass, jintArray values) {
    if (!values || env->GetArrayLength(values) < 9) return;
    jint v[9];
    env->GetIntArrayRegion(values, 0, 9, v);
    auto u8 = [](jint x) { return (uint8_t)(x < 0 ? 0 : x > 255 ? 255 : x); };
    auto u16 = [](jint x) { return (uint16_t)(x < 0 ? 0 : x > 65535 ? 65535 : x); };
    wl_control_settings s{};
    s.output_mode = u8(v[0]);
    s.fg_mode = u8(v[1]);
    s.fg_multiplier = u8(v[2]);
    s.fg_flags = u8(v[3]);
    s.fps_limit = u16(v[4]);
    s.fg_target_fps = u16(v[5]);
    s.fg_flow_side = u16(v[6]);
    s.fg_min_fps = u16(v[7]);
    s.fg_resume_fps = u16(v[8]);
    wlcontrol::writeSettings(s);
}

// {pid, sink, fg_state, reason, real_frames, generated_frames, dropped_frames}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_core_WlControl_nativeReadStatus(JNIEnv* env, jclass, jlongArray out) {
    if (!out || env->GetArrayLength(out) < 7) return JNI_FALSE;
    wl_control_status st{};
    if (!wlcontrol::readStatus(st) || !st.pid) return JNI_FALSE;
    const jlong v[7] = {(jlong)st.pid, (jlong)st.sink, (jlong)st.fg_state, (jlong)st.reason,
                        (jlong)st.real_frames, (jlong)st.generated_frames, (jlong)st.dropped_frames};
    env->SetLongArrayRegion(out, 0, 7, v);
    return JNI_TRUE;
}
