// SurfaceFlinger presentation path for the X server: every viewable X window becomes an
// ASurfaceControl (a layer SurfaceFlinger composes), and the window's AHardwareBuffer is handed
// to it as is - no Vulkan swapchain, no GPU pass of our own. Buffers are tracked through
// transaction-complete callbacks so the X Present extension can hand a pixmap back to the guest
// only once SurfaceFlinger has let go of it (release fence).
//
// The ASurface* NDK entry points are API 29; the app's minSdk is 28, so they are resolved with
// dlsym and the whole path stays unavailable (nativeIsSupported() == false) on older systems.
//
// Buffer modes (Ctx::copyMode):
//   2 auto (default): an RGBA8888 / RGBX8888 window buffer that the GPU can sample goes to
//     SurfaceFlinger as it is (no copy). Anything else - BGRA, which is what the guest allocates
//     unless its surface format is set to rgba8, or a buffer without GPU usage - is first copied by
//     a small GL pass (BlitConverter) into an RGBA buffer this file allocated itself (GPU color
//     output + composer overlay), and only that copy is given to the layer.
//   1 copy: every frame is copied. 0 direct: nothing is copied (the first version of this path).
// Buffers handed over as they are (BGRA / CPU-lockable / foreign) are what makes some
// SurfaceFlinger/HWC/GPU-driver combinations misbehave (inverted colors, crashes). A copied frame
// frees the guest's buffer as soon as the copy has finished instead of when SurfaceFlinger lets go.
// Switch: ASurfaceXServerView.setCompatMode() (true = 2, false = 0) for views created afterwards, or
// `setprop debug.winlite.sf_compat 0|1|2` (wins over the Java setting).
//
// Locking: one mutex (Ctx::mu) guards all state. Transactions are built and applied under it so
// their order is the order of the state changes. Completion callbacks arrive on a SurfaceFlinger
// thread and take the same mutex briefly; nothing here calls back into Java.

#include <jni.h>
#include <android/api-level.h>
#include <android/hardware_buffer.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/rect.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <poll.h>
#include <sys/system_properties.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <climits>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <vector>

#include "blit_converter.h"

#define LOG_TAG "ASurfaceXServer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

using Clock = std::chrono::steady_clock;

int64_t nowNs() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now().time_since_epoch()).count();
}

// ---------------------------------------------------------------------------------------------
// NDK entry points (resolved at run time)
// ---------------------------------------------------------------------------------------------
struct Api {
    void* (*scCreateFromWindow)(ANativeWindow*, const char*) = nullptr;
    void  (*scRelease)(void*) = nullptr;
    void* (*txCreate)() = nullptr;
    void  (*txDelete)(void*) = nullptr;
    void  (*txApply)(void*) = nullptr;
    void  (*txSetBuffer)(void*, void*, AHardwareBuffer*, int) = nullptr;
    void  (*txSetZOrder)(void*, void*, int32_t) = nullptr;
    void  (*txSetVisibility)(void*, void*, int8_t) = nullptr;
    void  (*txSetGeometry)(void*, void*, const ARect*, const ARect*, int32_t) = nullptr;
    void  (*txSetOnComplete)(void*, void*, void (*)(void*, void*)) = nullptr;
    void  (*txSetTransparency)(void*, void*, int8_t) = nullptr;
    void  (*txSetDesiredPresentTime)(void*, int64_t) = nullptr;   // optional
    void  (*txReparent)(void*, void*, void*) = nullptr;
    void  (*statsGetControls)(void*, void***, size_t*) = nullptr;
    void  (*statsReleaseControls)(void**) = nullptr;
    int   (*statsPrevReleaseFence)(void*, void*) = nullptr;
    bool ok = false;
};

Api g_api;
std::once_flag g_apiOnce;

void loadApi() {
    if (android_get_device_api_level() < 29) {
        LOGI("API level %d < 29: SurfaceFlinger path unavailable", android_get_device_api_level());
        return;
    }
    void* lib = dlopen("libandroid.so", RTLD_NOW | RTLD_NOLOAD);
    if (!lib) lib = dlopen("libandroid.so", RTLD_NOW);
    if (!lib) { LOGE("dlopen libandroid.so failed: %s", dlerror()); return; }
#define LOAD(field, name) g_api.field = reinterpret_cast<decltype(g_api.field)>(dlsym(lib, name))
    LOAD(scCreateFromWindow, "ASurfaceControl_createFromWindow");
    LOAD(scRelease, "ASurfaceControl_release");
    LOAD(txCreate, "ASurfaceTransaction_create");
    LOAD(txDelete, "ASurfaceTransaction_delete");
    LOAD(txApply, "ASurfaceTransaction_apply");
    LOAD(txSetBuffer, "ASurfaceTransaction_setBuffer");
    LOAD(txSetZOrder, "ASurfaceTransaction_setZOrder");
    LOAD(txSetVisibility, "ASurfaceTransaction_setVisibility");
    LOAD(txSetGeometry, "ASurfaceTransaction_setGeometry");
    LOAD(txSetOnComplete, "ASurfaceTransaction_setOnComplete");
    LOAD(txSetTransparency, "ASurfaceTransaction_setBufferTransparency");
    LOAD(txSetDesiredPresentTime, "ASurfaceTransaction_setDesiredPresentTime");
    LOAD(txReparent, "ASurfaceTransaction_reparent");
    LOAD(statsGetControls, "ASurfaceTransactionStats_getASurfaceControls");
    LOAD(statsReleaseControls, "ASurfaceTransactionStats_releaseASurfaceControls");
    LOAD(statsPrevReleaseFence, "ASurfaceTransactionStats_getPreviousReleaseFenceFd");
#undef LOAD
    g_api.ok = g_api.scCreateFromWindow && g_api.scRelease && g_api.txCreate && g_api.txDelete &&
               g_api.txApply && g_api.txSetBuffer && g_api.txSetZOrder && g_api.txSetVisibility &&
               g_api.txSetGeometry && g_api.txSetOnComplete && g_api.txSetTransparency &&
               g_api.txReparent && g_api.statsGetControls && g_api.statsReleaseControls &&
               g_api.statsPrevReleaseFence;
    if (!g_api.ok) LOGE("ASurface* symbols missing");
}

bool apiAvailable() {
    std::call_once(g_apiOnce, loadApi);
    return g_api.ok;
}

constexpr int8_t kVisHide = 0, kVisShow = 1;
constexpr int8_t kTranslucent = 1, kOpaque = 2;
// Transactions with a buffer for one layer that SurfaceFlinger has not completed yet. More than
// this and the newest frame waits (latest wins): an unthrottled guest (MAILBOX, no limit) would
// otherwise queue hundreds of transactions a second.
constexpr int kMaxInflight = 3;
// A buffer whose replacing transaction never reports back (surface gone, display off) stops
// counting as busy after this, so the guest is not held for good.
constexpr int64_t kCallbackGraceNs = 200'000'000LL;
// No completion for this long with the in-flight slots all taken: the callbacks are taken as
// lost (surface hidden, display off) and the slots are given back, so frames flow again.
constexpr int64_t kInflightStallNs = 300'000'000LL;
// Compat mode: upper bound for converted buffers kept around (a layer needs up to four: the one
// on screen and one per transaction in flight), and how long a replaced one may wait for its
// release before the transaction callback is taken as lost and the buffer is reused.
constexpr size_t kMaxSlots = 64;
constexpr int64_t kSlotStallNs = 3'000'000'000LL;

struct Ctx;

// An ASurfaceControl, released when the last reference (a layer, or a transaction callback that
// still has to look it up in the stats) is gone.
struct SCRef {
    void* sc;
    explicit SCRef(void* s) : sc(s) {}
    ~SCRef() { if (sc) g_api.scRelease(sc); }
};

// A converted (RGBA8888) buffer SurfaceFlinger may be reading. Compat mode only.
struct Slot {
    AHardwareBuffer* buf = nullptr;
    uint32_t w = 0, h = 0;
    bool inUse = false;         // on screen, or replaced and not yet released by SurfaceFlinger
    uint32_t epoch = 0;         // bumped when a lost slot is taken back: late callbacks are ignored
    int fence = -1;             // release fence of SurfaceFlinger's last read; the next copy waits on it
    int64_t replacedAtNs = 0;   // when its layer moved on to another buffer (0 = still shown)
    int64_t freedAtNs = 0;
    Slot() = default;
    Slot(const Slot&) = delete;
    Slot& operator=(const Slot&) = delete;
    ~Slot() {
        if (fence >= 0) close(fence);
        if (buf) AHardwareBuffer_release(buf);
    }
};

struct Layer {
    int64_t id = 0;
    std::shared_ptr<SCRef> sc;
    int64_t curSource = 0;            // uid of the buffer submitted last (0 = none yet)
    AHardwareBuffer* curAhb = nullptr;  // our reference to it (re-submitted when the CPU drew in it)
    std::shared_ptr<Slot> curSlot;    // the copy SurfaceFlinger shows, when the frame was copied
    bool curDirect = false;           // else: curSource itself is what it shows (counted in shownRefs)
    int bufW = 0, bufH = 0;
    int baseX = 0, baseY = 0;         // window position in X root coordinates
    int offX = 0, offY = 0;           // extra offset of the shown buffer inside the window
    int z = 0;
    bool translucent = false;
    bool trApplied = false, trValue = false;
    int inflight = 0;
    int64_t progressNs = 0;           // last time the in-flight count moved
    int64_t pendSource = 0;           // newest frame waiting for an in-flight slot
    AHardwareBuffer* pendAhb = nullptr;
    int64_t pendDesiredNs = 0;        // its desired present time (0 = as soon as possible)
    bool bufDirty = false;            // the CPU drew into curAhb: submit it again at the next vsync
    // What SurfaceFlinger has been told, to skip redundant state.
    bool visApplied = false;
    bool zApplied = false; int zValue = 0;
    bool geoApplied = false; ARect srcApplied{}, dstApplied{};
};

struct BufState {
    int shownRefs = 0;       // layers showing it (or holding it as their pending frame)
    int awaitCb = 0;         // replacing transactions not completed yet
    int64_t replacedAtNs = 0;
    int fence = -1;          // release fence delivered by the last completed replacement
};

struct Entry {
    std::shared_ptr<Layer> layer;   // null for entries that only keep a control alive
    std::shared_ptr<SCRef> sc;
    int64_t prevSource;             // buffer this transaction replaced (0 = none / not tracked)
    std::shared_ptr<Slot> prevSlot; // compat mode: converted buffer this transaction replaced
    uint32_t prevSlotEpoch;
    Entry(std::shared_ptr<Layer> l, std::shared_ptr<SCRef> s, int64_t prev,
          std::shared_ptr<Slot> slot = nullptr, uint32_t epoch = 0)
        : layer(std::move(l)), sc(std::move(s)), prevSource(prev),
          prevSlot(std::move(slot)), prevSlotEpoch(epoch) {}
};

struct TxCtx {
    std::shared_ptr<Ctx> ctx;
    std::vector<Entry> entries;
};

struct Tx {
    void* raw;
    std::vector<Entry> entries;
    Tx() : raw(g_api.txCreate()) {}
    Tx(const Tx&) = delete;
    Tx& operator=(const Tx&) = delete;
};

void onTransactionComplete(void* context, void* stats);

struct Ctx : std::enable_shared_from_this<Ctx> {
    std::mutex mu;
    std::condition_variable cv;
    ANativeWindow* window = nullptr;
    int surfW = 0, surfH = 0;
    bool closing = false;
    bool dead = false;
    int waiters = 0;
    uint64_t waitSerial = 0;

    std::unordered_map<int64_t, std::shared_ptr<Layer>> layers;
    std::unordered_map<int64_t, BufState> bufs;

    // compat mode: GL copy pass and the converted buffers
    int copyMode = 0;                 // 0 direct, 1 copy all, 2 auto
    BlitConverter* conv = nullptr;
    bool loggedDirect = false, loggedCopy = false;
    std::vector<std::shared_ptr<Slot>> slots;
    int64_t lastWarnNs = 0;
    enum class Sub { Ok, NoSlot, Failed };

    // scene transform (X root coordinates -> surface pixels) and the clip rectangle
    float ox = 0, oy = 0, sx = 1, sy = 1;
    bool clip = false;
    int cx = 0, cy = 0, cw = 0, ch = 0;
    bool xformDirty = true;

    // letterbox background
    std::shared_ptr<SCRef> bgSc;
    AHardwareBuffer* bgBuf = nullptr;
    uint32_t bgBufW = 0, bgBufH = 0;
    bool bgBufPending = false;        // bgBuf is not on the control yet
    bool bgDirty = true;

    // cursor
    std::shared_ptr<SCRef> curSc;
    AHardwareBuffer* curBuf = nullptr;
    int curW = 0, curH = 0, hotX = 0, hotY = 0;
    int ptrX = 0, ptrY = 0;
    bool curWanted = false;      // Java: cursor shown
    bool curVisApplied = false;
    bool curBufPending = false;  // curBuf not yet handed to SurfaceFlinger on the current control
    bool curGeoDirty = true;
    bool curGeoApplied = false; ARect curSrcApplied{}, curDstApplied{};

    // ---- helpers (mu held) ----
    void sendTx(Tx& tx);
    bool mapRect(float x, float y, int bufW, int bufH, ARect& src, ARect& dst) const;
    void applyLayerGeometry(Tx& tx, const std::shared_ptr<Layer>& L);
    void submit(Tx& tx, const std::shared_ptr<Layer>& L, int64_t source, AHardwareBuffer* ahb,
                int64_t desiredNs = 0);
    Sub doSubmit(Tx& tx, const std::shared_ptr<Layer>& L, int64_t source, AHardwareBuffer* ahb,
                 int64_t desiredNs = 0);
    void stashPending(const std::shared_ptr<Layer>& L, int64_t source, AHardwareBuffer* ahb,
                      int64_t desiredNs);
    Sub acquireSlot(uint32_t w, uint32_t h, std::shared_ptr<Slot>& out, int& acquireFence);
    Sub convertToSlot(AHardwareBuffer* ahb, const AHardwareBuffer_Desc& desc,
                      std::shared_ptr<Slot>& slot, int& fenceFd);
    bool warnDue();
    bool copyNeeded(const AHardwareBuffer_Desc& desc) const;
    void ensureBgBuffer();
    void dropPending(const std::shared_ptr<Layer>& L);
    bool promotePending(Tx& tx, const std::shared_ptr<Layer>& L);
    void retireLayer(const std::shared_ptr<Layer>& L);
    void gcBuf(int64_t source);
    bool busyLocked(int64_t source, int64_t now);
    void createFixedLayers();
    void retireFixedLayers();
    void applyBackground(Tx& tx);
    void applyCursor(Tx& tx);
    void flushLocked();
    void teardownLocked();
    ~Ctx() { delete conv; }
};

void Ctx::sendTx(Tx& tx) {
    if (!tx.raw) return;
    if (!tx.entries.empty()) {
        auto* tc = new TxCtx{shared_from_this(), std::move(tx.entries)};
        g_api.txSetOnComplete(tx.raw, tc, onTransactionComplete);
    }
    g_api.txApply(tx.raw);
    g_api.txDelete(tx.raw);
    tx.raw = nullptr;
}

// Maps a buffer of bufW x bufH placed at (x, y) in X coordinates to a destination rectangle in
// surface pixels, cut to the clip rectangle with the source rectangle cut to match.
bool Ctx::mapRect(float x, float y, int bufW, int bufH, ARect& src, ARect& dst) const {
    if (bufW <= 0 || bufH <= 0) return false;
    const int l = (int) lroundf(ox + x * sx), t = (int) lroundf(oy + y * sy);
    const int r = (int) lroundf(ox + (x + bufW) * sx), b = (int) lroundf(oy + (y + bufH) * sy);
    if (r <= l || b <= t) return false;
    int cl = l, ct = t, cr = r, cb = b;
    if (clip) {
        cl = std::max(l, cx); ct = std::max(t, cy);
        cr = std::min(r, cx + cw); cb = std::min(b, cy + ch);
        if (cr <= cl || cb <= ct) return false;
    }
    const float kx = (float) bufW / (float) (r - l), ky = (float) bufH / (float) (b - t);
    src.left = std::clamp((int) floorf((float) (cl - l) * kx), 0, bufW);
    src.top = std::clamp((int) floorf((float) (ct - t) * ky), 0, bufH);
    src.right = std::clamp((int) ceilf((float) (cr - l) * kx), 0, bufW);
    src.bottom = std::clamp((int) ceilf((float) (cb - t) * ky), 0, bufH);
    if (src.right <= src.left || src.bottom <= src.top) return false;
    dst.left = cl; dst.top = ct; dst.right = cr; dst.bottom = cb;
    return true;
}

static bool sameRect(const ARect& a, const ARect& b) {
    return a.left == b.left && a.top == b.top && a.right == b.right && a.bottom == b.bottom;
}

void Ctx::applyLayerGeometry(Tx& tx, const std::shared_ptr<Layer>& L) {
    ARect src{}, dst{};
    const bool show = L->curAhb && mapRect((float) (L->baseX + L->offX), (float) (L->baseY + L->offY),
                                           L->bufW, L->bufH, src, dst);
    void* sc = L->sc->sc;
    if (!show) {
        if (L->visApplied) { g_api.txSetVisibility(tx.raw, sc, kVisHide); L->visApplied = false; }
        return;
    }
    if (!L->geoApplied || !sameRect(src, L->srcApplied) || !sameRect(dst, L->dstApplied)) {
        g_api.txSetGeometry(tx.raw, sc, &src, &dst, 0);
        L->srcApplied = src; L->dstApplied = dst; L->geoApplied = true;
    }
    if (!L->zApplied || L->zValue != L->z) {
        g_api.txSetZOrder(tx.raw, sc, L->z);
        L->zApplied = true; L->zValue = L->z;
    }
    if (!L->visApplied) { g_api.txSetVisibility(tx.raw, sc, kVisShow); L->visApplied = true; }
}

void Ctx::dropPending(const std::shared_ptr<Layer>& L) {
    if (!L->pendAhb) return;
    auto it = bufs.find(L->pendSource);
    if (it != bufs.end()) { it->second.shownRefs--; gcBuf(L->pendSource); }
    AHardwareBuffer_release(L->pendAhb);
    L->pendAhb = nullptr;
    L->pendSource = 0;
    L->pendDesiredNs = 0;
}

bool Ctx::copyNeeded(const AHardwareBuffer_Desc& desc) const {
    if (!conv || copyMode == 0) return false;
    if (copyMode == 1) return true;
    // Auto: only layouts and usages that were checked to be safe for SurfaceFlinger go as they are.
    const bool rgba = desc.format == AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM ||
                      desc.format == AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM;
    return !(rgba && desc.layers == 1 && (desc.usage & AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE));
}

bool Ctx::warnDue() {
    const int64_t now = nowNs();
    if (now - lastWarnNs < 1'000'000'000LL) return false;
    lastWarnNs = now;
    return true;
}

// Compat mode: a free converted buffer of this size (its release fence, if any, comes back in
// acquireFence and is consumed by the copy), allocating one when needed. NoSlot: all are busy.
Ctx::Sub Ctx::acquireSlot(uint32_t w, uint32_t h, std::shared_ptr<Slot>& out, int& acquireFence) {
    acquireFence = -1;
    const int64_t now = nowNs();
    for (auto& sl : slots) {
        // The callback that would release it never came (surface hidden, display off).
        if (sl->inUse && sl->replacedAtNs && now - sl->replacedAtNs > kSlotStallNs) {
            sl->epoch++;
            sl->inUse = false;
            sl->replacedAtNs = 0;
            sl->freedAtNs = now;
            if (sl->fence >= 0) { close(sl->fence); sl->fence = -1; }
        }
    }
    for (auto& sl : slots) {
        if (!sl->inUse && sl->w == w && sl->h == h) {
            sl->inUse = true;
            sl->replacedAtNs = 0;
            acquireFence = sl->fence;
            sl->fence = -1;
            out = sl;
            return Sub::Ok;
        }
    }
    if (slots.size() >= kMaxSlots) {
        // Make room: the idle buffer (necessarily of another size) that has been idle longest.
        size_t victim = slots.size();
        for (size_t i = 0; i < slots.size(); i++) {
            if (slots[i]->inUse) continue;
            if (victim == slots.size() || slots[i]->freedAtNs < slots[victim]->freedAtNs) victim = i;
        }
        if (victim == slots.size()) return Sub::NoSlot;
        conv->unregisterBuffer(slots[victim]->buf);
        slots.erase(slots.begin() + (std::ptrdiff_t) victim);
    }

    AHardwareBuffer_Desc d{};
    d.width = w; d.height = h; d.layers = 1;
    d.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    d.usage = AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
              AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY;
    AHardwareBuffer* buf = nullptr;
    int rc = AHardwareBuffer_allocate(&d, &buf);
    if (rc != 0 || !buf) {
        d.usage = AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
        buf = nullptr;
        rc = AHardwareBuffer_allocate(&d, &buf);
    }
    if (rc != 0 || !buf) {
        if (warnDue()) LOGE("converted buffer %ux%u not allocated (%d)", w, h, rc);
        return Sub::Failed;
    }
    if (!conv->registerBuffer(buf)) {
        if (warnDue()) LOGE("converted buffer %ux%u not usable by the GL pass", w, h);
        AHardwareBuffer_release(buf);
        return Sub::Failed;
    }
    auto sl = std::make_shared<Slot>();
    sl->buf = buf;
    sl->w = w;
    sl->h = h;
    sl->inUse = true;
    slots.push_back(sl);
    out = sl;
    return Sub::Ok;
}

// Compat mode: copies ahb into a converted buffer. The GL pass is submitted when this returns, not
// finished: fenceFd (>= 0 on Ok, ownership goes to the caller) signals the end of the copy.
Ctx::Sub Ctx::convertToSlot(AHardwareBuffer* ahb, const AHardwareBuffer_Desc& desc,
                            std::shared_ptr<Slot>& slot, int& fenceFd) {
    fenceFd = -1;
    if (!conv) return Sub::Failed;
    // 1 = RGBA8888, 2 = RGBX8888, 5 = BGRA8888 (HAL_PIXEL_FORMAT_BGRA_8888).
    if (desc.layers != 1 || desc.width == 0 || desc.height == 0 ||
        (desc.format != AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM &&
         desc.format != AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM && desc.format != 5u)) {
        if (warnDue()) LOGW("window buffer %ux%u format %u cannot be converted, frame dropped",
                            desc.width, desc.height, desc.format);
        return Sub::Failed;
    }
    int dstFence = -1;
    const Sub st = acquireSlot(desc.width, desc.height, slot, dstFence);
    if (st != Sub::Ok) return st;
    // No acquire fence for the source: the guest finishes rendering before it sends PresentPixmap
    // (and CPU drawing is done when its draw request returns). The copy waits for the slot's last
    // read by SurfaceFlinger on the GPU side. Blocks until the draw is submitted.
    const int fd = conv->convertBGRAtoRGBA(ahb, slot->buf, -1, dstFence, false).get();
    if (fd < 0) {
        slot->inUse = false;
        slot->freedAtNs = nowNs();
        slot.reset();
        if (warnDue()) LOGE("GL copy of a %ux%u window buffer (format %u) failed",
                            desc.width, desc.height, desc.format);
        return Sub::Failed;
    }
    fenceFd = fd;
    return Sub::Ok;
}

void Ctx::stashPending(const std::shared_ptr<Layer>& L, int64_t source, AHardwareBuffer* ahb,
                       int64_t desiredNs) {
    dropPending(L);
    AHardwareBuffer_acquire(ahb);
    L->pendAhb = ahb;
    L->pendSource = source;
    L->pendDesiredNs = desiredNs;
    bufs[source].shownRefs++;
}

void Ctx::submit(Tx& tx, const std::shared_ptr<Layer>& L, int64_t source, AHardwareBuffer* ahb,
                 int64_t desiredNs) {
    if (L->inflight >= kMaxInflight && nowNs() - L->progressNs > kInflightStallNs) {
        LOGW("window %lld: no transaction completed for a while, resetting", (long long) L->id);
        L->inflight = 0;
    }
    if (L->inflight >= kMaxInflight) {
        stashPending(L, source, ahb, desiredNs);
        return;
    }
    dropPending(L);
    // No converted buffer free (compat mode): the frame waits like one without an in-flight slot.
    if (doSubmit(tx, L, source, ahb, desiredNs) == Sub::NoSlot) stashPending(L, source, ahb, desiredNs);
}

Ctx::Sub Ctx::doSubmit(Tx& tx, const std::shared_ptr<Layer>& L, int64_t source, AHardwareBuffer* ahb,
                       int64_t desiredNs) {
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(ahb, &desc);

    AHardwareBuffer* shown = ahb;   // what SurfaceFlinger gets
    int fence = -1;                 // its acquire fence (ownership goes to the transaction)
    std::shared_ptr<Slot> slot;
    const bool viaCopy = copyNeeded(desc);
    if (viaCopy) {
        if (!loggedCopy) {
            loggedCopy = true;
            LOGI("window buffer %ux%u format %u usage 0x%llx: copied before SurfaceFlinger", desc.width,
                 desc.height, desc.format, (unsigned long long) desc.usage);
        }
        const Sub st = convertToSlot(ahb, desc, slot, fence);
        if (st != Sub::Ok) return st;
        shown = slot->buf;
        // SurfaceFlinger never reads the guest's buffer: it is free as soon as the copy is done.
        BufState& b = bufs[source];
        const int copyFence = fcntl(fence, F_DUPFD_CLOEXEC, 0);
        if (copyFence >= 0) {
            if (b.fence >= 0) close(b.fence);
            b.fence = copyFence;
        } else {
            pollfd pfd{fence, POLLIN, 0};   // out of descriptors: wait for the copy instead
            poll(&pfd, 1, 1000);
            gcBuf(source);
        }
    } else if (!loggedDirect) {
        loggedDirect = true;
        LOGI("window buffer %ux%u format %u usage 0x%llx: given to SurfaceFlinger as it is", desc.width,
             desc.height, desc.format, (unsigned long long) desc.usage);
    }

    const int64_t prev = L->curSource;
    AHardwareBuffer_acquire(ahb);
    if (L->curAhb) AHardwareBuffer_release(L->curAhb);
    L->curAhb = ahb;
    L->curSource = source;
    L->bufW = (int) desc.width;
    L->bufH = (int) desc.height;
    L->bufDirty = false;

    if (!L->trApplied || L->trValue != L->translucent) {
        g_api.txSetTransparency(tx.raw, L->sc->sc, L->translucent ? kTranslucent : kOpaque);
        L->trApplied = true; L->trValue = L->translucent;
    }
    // Not copied: no acquire fence, the guest finishes rendering before it sends PresentPixmap.
    // Copied: the fence of the copy.
    g_api.txSetBuffer(tx.raw, L->sc->sc, shown, fence);
    // FPS limiter: SurfaceFlinger holds the frame back until its slot, so it is shown at a fixed
    // phase of the refresh however the guest's present times jitter. Past times mean "now".
    if (desiredNs > 0 && g_api.txSetDesiredPresentTime) g_api.txSetDesiredPresentTime(tx.raw, desiredNs);

    // What this transaction replaces: a copy (freed when the transaction completes), or the
    // guest's own buffer (busy until then, and until its release fence has signalled).
    int64_t replaced = 0;
    std::shared_ptr<Slot> prevSlot = std::move(L->curSlot);
    if (prevSlot) prevSlot->replacedAtNs = nowNs();
    const bool sameDirect = L->curDirect && !viaCopy && prev == source;
    if (L->curDirect && prev && !sameDirect) {
        BufState& p = bufs[prev];
        p.shownRefs--;
        p.awaitCb++;
        p.replacedAtNs = nowNs();
        replaced = prev;
    }
    if (viaCopy) {
        L->curSlot = slot;
        L->curDirect = false;
    } else {
        if (!sameDirect) bufs[source].shownRefs++;
        L->curDirect = true;
    }
    if (L->inflight == 0) L->progressNs = nowNs();
    L->inflight++;
    const uint32_t prevEpoch = prevSlot ? prevSlot->epoch : 0;
    tx.entries.push_back(Entry(L, L->sc, replaced, std::move(prevSlot), prevEpoch));
    applyLayerGeometry(tx, L);
    return Sub::Ok;
}

// Sends the frame that waited for an in-flight slot (or a converted buffer), if there is one now.
bool Ctx::promotePending(Tx& tx, const std::shared_ptr<Layer>& L) {
    if (!L->pendAhb || L->inflight >= kMaxInflight) return false;
    AHardwareBuffer* ahb = L->pendAhb;
    const int64_t src = L->pendSource;
    const int64_t desired = L->pendDesiredNs;
    if (doSubmit(tx, L, src, ahb, desired) == Sub::NoSlot) return false;   // stays pending
    // Sent, or dropped for good: the pending reference goes.
    L->pendAhb = nullptr;
    L->pendSource = 0;
    L->pendDesiredNs = 0;
    auto it = bufs.find(src);
    if (it != bufs.end()) { it->second.shownRefs--; gcBuf(src); }
    AHardwareBuffer_release(ahb);
    return true;
}

void Ctx::gcBuf(int64_t source) {
    auto it = bufs.find(source);
    if (it == bufs.end()) return;
    BufState& b = it->second;
    if (b.shownRefs <= 0 && b.awaitCb <= 0 && b.fence < 0) bufs.erase(it);
}

// Whether SurfaceFlinger may still read the buffer: it is on screen (or queued), the transaction
// that replaced it has not completed, or its release fence has not signalled.
bool Ctx::busyLocked(int64_t source, int64_t now) {
    auto it = bufs.find(source);
    if (it == bufs.end()) return false;
    BufState& b = it->second;
    if (b.shownRefs > 0) return true;
    if (b.awaitCb > 0 && now - b.replacedAtNs < kCallbackGraceNs) return true;
    if (b.fence >= 0) {
        pollfd pfd{b.fence, POLLIN, 0};
        if (poll(&pfd, 1, 0) == 0) return true;
        close(b.fence);
        b.fence = -1;
    }
    if (b.awaitCb <= 0) { gcBuf(source); }
    return false;
}

void Ctx::retireLayer(const std::shared_ptr<Layer>& L) {
    dropPending(L);
    Tx tx;
    int64_t replaced = 0;
    std::shared_ptr<Slot> slot = std::move(L->curSlot);
    if (L->curSource) {
        if (L->curDirect) {
            BufState& p = bufs[L->curSource];
            p.shownRefs--;
            p.awaitCb++;
            p.replacedAtNs = nowNs();
            replaced = L->curSource;
        }
        L->curSource = 0;
    }
    L->curDirect = false;
    if (slot) slot->replacedAtNs = nowNs();
    if (tx.raw) {
        g_api.txSetVisibility(tx.raw, L->sc->sc, kVisHide);
        g_api.txReparent(tx.raw, L->sc->sc, nullptr);
        const uint32_t slotEpoch = slot ? slot->epoch : 0;
        tx.entries.push_back(Entry(L, L->sc, replaced, std::move(slot), slotEpoch));
        L->inflight++;
        sendTx(tx);
    } else if (slot) {
        slot->inUse = false;
        slot->freedAtNs = nowNs();
    }
    if (L->curAhb) { AHardwareBuffer_release(L->curAhb); L->curAhb = nullptr; }
}

void Ctx::createFixedLayers() {
    if (!window) return;
    void* bg = g_api.scCreateFromWindow(window, "(x11_background)");
    if (bg) bgSc = std::make_shared<SCRef>(bg);
    else LOGE("background control not created");
    void* cur = g_api.scCreateFromWindow(window, "(x11_cursor)");
    if (cur) curSc = std::make_shared<SCRef>(cur);
    else LOGE("cursor control not created");

    if (bgSc) ensureBgBuffer();
    if (bgSc && bgBuf) {
        Tx tx;
        if (tx.raw) {
            g_api.txSetTransparency(tx.raw, bgSc->sc, kOpaque);
            g_api.txSetBuffer(tx.raw, bgSc->sc, bgBuf, -1);
            bgBufPending = false;
            g_api.txSetZOrder(tx.raw, bgSc->sc, INT_MIN);
            sendTx(tx);
        }
    }
    if (curSc) {
        Tx tx;
        if (tx.raw) {
            g_api.txSetZOrder(tx.raw, curSc->sc, INT_MAX);
            g_api.txSetTransparency(tx.raw, curSc->sc, kTranslucent);
            g_api.txSetVisibility(tx.raw, curSc->sc, kVisHide);
            sendTx(tx);
        }
    }
    bgDirty = true;
    curBufPending = curBuf != nullptr;
    curGeoDirty = true;
    curVisApplied = false;
    curGeoApplied = false;
}

void Ctx::retireFixedLayers() {
    for (auto* scp : {&bgSc, &curSc}) {
        if (!*scp) continue;
        Tx tx;
        if (tx.raw) {
            g_api.txSetVisibility(tx.raw, (*scp)->sc, kVisHide);
            g_api.txReparent(tx.raw, (*scp)->sc, nullptr);
            sendTx(tx);
        }
        scp->reset();
    }
    curVisApplied = false;
    curGeoApplied = false;
}

// Opaque black at the surface's size, shown 1:1: a 1x1 buffer stretched over the screen is beyond
// every display controller's scaler, so the layer would go to GPU composition (and with it a GPU
// pass every frame wherever the game does not cover the screen). The stretched 1x1 buffer stays as
// the fallback when the big one cannot be allocated.
void Ctx::ensureBgBuffer() {
    if (surfW <= 0 || surfH <= 0) return;
    if (bgBuf && bgBufW == (uint32_t) surfW && bgBufH == (uint32_t) surfH) return;
    AHardwareBuffer_Desc d{};
    d.width = (uint32_t) surfW; d.height = (uint32_t) surfH; d.layers = 1;
    d.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    d.usage = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
              AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY;
    AHardwareBuffer* nb = nullptr;
    if (AHardwareBuffer_allocate(&d, &nb) != 0) {
        d.usage = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
        nb = nullptr;
        if (AHardwareBuffer_allocate(&d, &nb) != 0) nb = nullptr;
    }
    uint32_t w = d.width, h = d.height;
    if (!nb) {
        if (bgBuf) return;      // keep what there is
        w = 1; h = 1;
        d.width = 1; d.height = 1;
        d.usage = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
        if (AHardwareBuffer_allocate(&d, &nb) != 0) return;
        LOGW("background buffer %dx%d not allocated, using a stretched 1x1", surfW, surfH);
    }
    AHardwareBuffer_Desc got{};
    AHardwareBuffer_describe(nb, &got);
    void* p = nullptr;
    if (AHardwareBuffer_lock(nb, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, nullptr, &p) == 0 && p) {
        const uint32_t stride = got.stride ? got.stride : w;
        for (uint32_t y = 0; y < h; y++) {
            uint32_t* row = reinterpret_cast<uint32_t*>(p) + (size_t) y * stride;
            std::fill(row, row + w, 0xFF000000u);
        }
        AHardwareBuffer_unlock(nb, nullptr);
    }
    if (bgBuf) AHardwareBuffer_release(bgBuf);   // SurfaceFlinger holds its own reference
    bgBuf = nb;
    bgBufW = w;
    bgBufH = h;
    bgBufPending = true;
}

void Ctx::applyBackground(Tx& tx) {
    if (!bgDirty || !bgSc || surfW <= 0 || surfH <= 0) return;
    ensureBgBuffer();
    if (!bgBuf) return;
    if (bgBufPending) {
        g_api.txSetTransparency(tx.raw, bgSc->sc, kOpaque);
        g_api.txSetBuffer(tx.raw, bgSc->sc, bgBuf, -1);
        bgBufPending = false;
    }
    ARect src{0, 0, (int32_t) bgBufW, (int32_t) bgBufH}, dst{0, 0, surfW, surfH};
    g_api.txSetGeometry(tx.raw, bgSc->sc, &src, &dst, 0);
    g_api.txSetVisibility(tx.raw, bgSc->sc, kVisShow);
    bgDirty = false;
}

void Ctx::applyCursor(Tx& tx) {
    if (!curSc) return;
    if (curBufPending && curBuf) {
        g_api.txSetBuffer(tx.raw, curSc->sc, curBuf, -1);
        curBufPending = false;
        curGeoDirty = true;
    }
    ARect src{}, dst{};
    const bool show = curWanted && curBuf && !curBufPending &&
                      mapRect((float) (ptrX - hotX), (float) (ptrY - hotY), curW, curH, src, dst);
    if (show) {
        if (curGeoDirty || !curGeoApplied || !sameRect(src, curSrcApplied) || !sameRect(dst, curDstApplied)) {
            g_api.txSetGeometry(tx.raw, curSc->sc, &src, &dst, 0);
            curSrcApplied = src; curDstApplied = dst; curGeoApplied = true;
        }
        if (!curVisApplied) { g_api.txSetVisibility(tx.raw, curSc->sc, kVisShow); curVisApplied = true; }
    } else if (curVisApplied) {
        g_api.txSetVisibility(tx.raw, curSc->sc, kVisHide);
        curVisApplied = false;
    }
    curGeoDirty = false;
}

// Everything that changed since the last flush, in one transaction: the scene transform for all
// layers, buffers the CPU drew into, the cursor, the letterbox background.
void Ctx::flushLocked() {
    if (!window) return;
    bool any = xformDirty || bgDirty || curGeoDirty || curBufPending;
    if (!any) for (auto& kv : layers) if (kv.second->bufDirty || kv.second->pendAhb) { any = true; break; }
    if (!any) return;
    Tx tx;
    if (!tx.raw) return;
    const int64_t now = nowNs();
    for (auto& kv : layers) {
        const std::shared_ptr<Layer>& L = kv.second;
        if (L->pendAhb) {
            if (L->inflight >= kMaxInflight && now - L->progressNs > kInflightStallNs) {
                LOGW("window %lld: no transaction completed for a while, resetting", (long long) L->id);
                L->inflight = 0;
            }
            if (promotePending(tx, L)) continue;
        }
        if (L->bufDirty && L->curAhb && L->inflight < kMaxInflight) {
            AHardwareBuffer* ahb = L->curAhb;
            AHardwareBuffer_acquire(ahb);          // doSubmit swaps curAhb
            const int64_t src = L->curSource;
            const Sub r = doSubmit(tx, L, src, ahb);
            AHardwareBuffer_release(ahb);
            if (r != Sub::Ok) {
                // NoSlot: the frame is retried at the next vsync; Failed: it is dropped. Either
                // way doSubmit did not apply the new transform to this layer.
                if (r == Sub::Failed) L->bufDirty = false;
                if (xformDirty) applyLayerGeometry(tx, L);
            }
        } else if (xformDirty) {
            applyLayerGeometry(tx, L);
        }
    }
    applyBackground(tx);
    applyCursor(tx);
    xformDirty = false;
    sendTx(tx);
}

void Ctx::teardownLocked() {
    std::vector<std::shared_ptr<Layer>> all;
    all.reserve(layers.size());
    for (auto& kv : layers) all.push_back(kv.second);
    layers.clear();
    for (auto& L : all) retireLayer(L);
    retireFixedLayers();
    if (window) { ANativeWindow_release(window); window = nullptr; }
}

void onTransactionComplete(void* context, void* stats) {
    std::unique_ptr<TxCtx> tc(static_cast<TxCtx*>(context));
    std::shared_ptr<Ctx> ctx = tc->ctx;

    // Release fences of the buffers this transaction replaced. Only controls the stats list are
    // asked for: querying one that is not part of them is a fatal error in the NDK.
    std::vector<int> fences(tc->entries.size(), -1);
    void** controls = nullptr;
    size_t count = 0;
    g_api.statsGetControls(stats, &controls, &count);
    for (size_t i = 0; i < tc->entries.size(); i++) {
        const Entry& e = tc->entries[i];
        if (!e.prevSource && !e.prevSlot) continue;
        for (size_t k = 0; k < count; k++) {
            if (controls[k] == e.sc->sc) { fences[i] = g_api.statsPrevReleaseFence(stats, e.sc->sc); break; }
        }
    }
    if (controls) g_api.statsReleaseControls(controls);

    std::unique_lock<std::mutex> lk(ctx->mu);
    for (size_t i = 0; i < tc->entries.size(); i++) {
        const Entry& e = tc->entries[i];
        if (e.prevSource) {
            auto it = ctx->bufs.find(e.prevSource);
            if (it != ctx->bufs.end()) {
                BufState& b = it->second;
                b.awaitCb--;
                if (fences[i] >= 0) {
                    if (b.fence >= 0) close(b.fence);
                    b.fence = fences[i];
                    fences[i] = -1;
                }
                ctx->gcBuf(e.prevSource);
            }
        }
        // Compat mode: SurfaceFlinger is done with the converted buffer this transaction replaced.
        if (e.prevSlot && e.prevSlot->epoch == e.prevSlotEpoch) {
            if (e.prevSlot->fence >= 0) close(e.prevSlot->fence);
            e.prevSlot->fence = fences[i];
            fences[i] = -1;
            e.prevSlot->inUse = false;
            e.prevSlot->replacedAtNs = 0;
            e.prevSlot->freedAtNs = nowNs();
        }
        if (fences[i] >= 0) close(fences[i]);
        if (e.layer) {
            if (e.layer->inflight > 0) e.layer->inflight--;
            e.layer->progressNs = nowNs();
        }
    }
    // Frames that waited for a free slot go out now.
    bool anyPending = false;
    if (!ctx->dead && ctx->window) {
        for (auto& e : tc->entries) if (e.layer && e.layer->pendAhb) { anyPending = true; break; }
    }
    if (anyPending) {
        Tx tx;
        bool used = false;
        if (tx.raw) {
            for (auto& e : tc->entries) {
                const std::shared_ptr<Layer>& L = e.layer;
                if (!L || !L->pendAhb) continue;
                auto lit = ctx->layers.find(L->id);
                if (lit == ctx->layers.end() || lit->second != L) continue;
                if (ctx->promotePending(tx, L)) used = true;
            }
            if (used) ctx->sendTx(tx);
            else { g_api.txDelete(tx.raw); tx.raw = nullptr; }
        }
    }
    lk.unlock();
    ctx->cv.notify_all();
}

inline std::shared_ptr<Ctx>* handleOf(jlong h) { return reinterpret_cast<std::shared_ptr<Ctx>*>(h); }

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_widget_ASurfaceXServerView_nativeIsSupported(JNIEnv*, jclass) {
    return apiAvailable() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_widget_ASurfaceXServerView_nativeInit(JNIEnv* env, jobject, jobject surface,
                                                             jint screenW, jint screenH, jboolean compatWanted) {
    (void) screenW; (void) screenH;
    if (!apiAvailable() || !surface) return 0;
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) return 0;

    int mode = compatWanted == JNI_TRUE ? 2 : 0;
    char prop[PROP_VALUE_MAX] = {};
    if (__system_property_get("debug.winlite.sf_compat", prop) > 0) {
        if (prop[0] == '0') mode = 0;
        else if (prop[0] == '1') mode = 1;
        else if (prop[0] == '2') mode = 2;
    }
    auto ctx = std::make_shared<Ctx>();
    if (mode != 0) {
        // EGL context, shader and private GL objects are created now, not on the first frame.
        BlitConverter* c = new BlitConverter(32);
        if (c->initialize()) {
            ctx->conv = c;
            ctx->copyMode = mode;
        } else {
            LOGW("GL copy pass unavailable on this device: buffers go to SurfaceFlinger as they are");
            delete c;
        }
    }
    LOGI("window buffers go to SurfaceFlinger: %s",
         ctx->copyMode == 2 ? "RGBA as they are, others copied (auto)" :
         ctx->copyMode == 1 ? "all copied" : "all as they are (direct)");
    {
        std::lock_guard<std::mutex> lk(ctx->mu);
        ctx->window = window;
        ctx->surfW = ANativeWindow_getWidth(window);
        ctx->surfH = ANativeWindow_getHeight(window);
        ctx->createFixedLayers();
        if (!ctx->bgSc && !ctx->curSc) {
            ANativeWindow_release(ctx->window);
            ctx->window = nullptr;
            return 0;
        }
    }
    return reinterpret_cast<jlong>(new std::shared_ptr<Ctx>(std::move(ctx)));
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_ASurfaceXServerView_nativeDetachSurface(JNIEnv*, jobject, jlong h) {
    if (!h) return;
    std::shared_ptr<Ctx> ctx = *handleOf(h);
    std::lock_guard<std::mutex> lk(ctx->mu);
    ctx->teardownLocked();
}

JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_widget_ASurfaceXServerView_nativeReattachSurface(JNIEnv* env, jobject, jlong h,
                                                                         jobject surface) {
    if (!h || !surface) return JNI_FALSE;
    std::shared_ptr<Ctx> ctx = *handleOf(h);
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) return JNI_FALSE;
    std::lock_guard<std::mutex> lk(ctx->mu);
    ctx->teardownLocked();
    ctx->window = window;
    ctx->surfW = ANativeWindow_getWidth(window);
    ctx->surfH = ANativeWindow_getHeight(window);
    ctx->createFixedLayers();
    ctx->xformDirty = true;
    return (ctx->bgSc || ctx->curSc) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_ASurfaceXServerView_nativeDestroy(JNIEnv*, jobject, jlong h) {
    if (!h) return;
    auto* holder = handleOf(h);
    std::shared_ptr<Ctx> ctx = *holder;
    {
        std::unique_lock<std::mutex> lk(ctx->mu);
        ctx->closing = true;
        ctx->cv.notify_all();
        ctx->cv.wait(lk, [&] { return ctx->waiters == 0; });
        ctx->teardownLocked();
        ctx->dead = true;
        if (ctx->conv) {
            // Queued copies finish first; the worker then drops every import it holds.
            for (auto& sl : ctx->slots) ctx->conv->unregisterBuffer(sl->buf);
            ctx->slots.clear();
            ctx->conv->shutdown();
            delete ctx->conv;
            ctx->conv = nullptr;
        }
        for (auto& kv : ctx->bufs) if (kv.second.fence >= 0) close(kv.second.fence);
        ctx->bufs.clear();
        if (ctx->curBuf) { AHardwareBuffer_release(ctx->curBuf); ctx->curBuf = nullptr; }
        if (ctx->bgBuf) { AHardwareBuffer_release(ctx->bgBuf); ctx->bgBuf = nullptr; }
    }
    delete holder;   // pending transaction callbacks keep the context alive until they ran
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_ASurfaceXServerView_nativeResize(JNIEnv*, jobject, jlong h, jint w, jint hh) {
    if (!h) return;
    std::shared_ptr<Ctx> ctx = *handleOf(h);
    std::lock_guard<std::mutex> lk(ctx->mu);
    ctx->surfW = w;
    ctx->surfH = hh;
    ctx->bgDirty = true;
    ctx->xformDirty = true;
    ctx->curGeoDirty = true;
}

// l: stride 3 per layer, back to front: window id, wanted buffer uid, its AHardwareBuffer.
//    uid 0 = nothing to show, -1 = keep what the layer shows now.
// v: stride 5 per layer: x, y, offX, offY, flags (bit 0: the pixels carry alpha).
JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_ASurfaceXServerView_nativeSetLayout(JNIEnv* env, jobject, jlong h,
                                                                  jlongArray larr, jintArray varr,
                                                                  jint count) {
    if (!h || count < 0) return;
    std::vector<jlong> l((size_t) count * 3);
    std::vector<jint> v((size_t) count * 5);
    if (count > 0) {
        env->GetLongArrayRegion(larr, 0, count * 3, l.data());
        env->GetIntArrayRegion(varr, 0, count * 5, v.data());
    }
    std::shared_ptr<Ctx> ctx = *handleOf(h);
    std::lock_guard<std::mutex> lk(ctx->mu);
    if (!ctx->window) return;
    Tx tx;
    if (!tx.raw) return;

    std::unordered_map<int64_t, std::shared_ptr<Layer>> next;
    next.reserve((size_t) count);
    for (int i = 0; i < count; i++) {
        const int64_t id = l[i * 3];
        const int64_t uid = l[i * 3 + 1];
        auto* ahb = reinterpret_cast<AHardwareBuffer*>(l[i * 3 + 2]);
        std::shared_ptr<Layer> L;
        auto it = ctx->layers.find(id);
        if (it != ctx->layers.end()) {
            L = it->second;
            ctx->layers.erase(it);
        } else {
            void* sc = g_api.scCreateFromWindow(ctx->window, "(x11_window)");
            if (!sc) { LOGE("window control not created"); continue; }
            L = std::make_shared<Layer>();
            L->id = id;
            L->sc = std::make_shared<SCRef>(sc);
        }
        next[id] = L;
        L->z = i;
        L->baseX = v[i * 5];
        L->baseY = v[i * 5 + 1];
        L->offX = v[i * 5 + 2];
        L->offY = v[i * 5 + 3];
        L->translucent = (v[i * 5 + 4] & 1) != 0;
        const int64_t latest = L->pendAhb ? L->pendSource : L->curSource;
        if (uid > 0 && ahb && uid != latest) ctx->submit(tx, L, uid, ahb);
        ctx->applyLayerGeometry(tx, L);   // position changes apply even if the frame waits for a slot
    }
    // Layers of windows that are gone or no longer viewable.
    std::vector<std::shared_ptr<Layer>> gone;
    for (auto& kv : ctx->layers) gone.push_back(kv.second);
    ctx->layers = std::move(next);
    for (auto& L : gone) ctx->retireLayer(L);
    ctx->sendTx(tx);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_ASurfaceXServerView_nativeSetTransform(JNIEnv*, jobject, jlong h,
                                                                     jfloat ox, jfloat oy, jfloat sx, jfloat sy,
                                                                     jboolean clip, jint cx, jint cy, jint cw, jint ch) {
    if (!h) return;
    std::shared_ptr<Ctx> ctx = *handleOf(h);
    std::lock_guard<std::mutex> lk(ctx->mu);
    if (ctx->ox == ox && ctx->oy == oy && ctx->sx == sx && ctx->sy == sy && ctx->clip == (bool) clip &&
        ctx->cx == cx && ctx->cy == cy && ctx->cw == cw && ctx->ch == ch) return;
    ctx->ox = ox; ctx->oy = oy; ctx->sx = sx; ctx->sy = sy;
    ctx->clip = clip; ctx->cx = cx; ctx->cy = cy; ctx->cw = cw; ctx->ch = ch;
    ctx->xformDirty = true;
    ctx->curGeoDirty = true;
}

// A frame for a window. immediate: a Present - go out now even if it is the buffer already shown.
// Otherwise (the CPU drew into the window's own buffer) a second submit of the same buffer waits
// for the next vsync. Returns false when the window has no layer (yet): the Java side re-sends
// what it shows with the next layout.
JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_widget_ASurfaceXServerView_nativePresent(JNIEnv*, jobject, jlong h, jlong windowId,
                                                                jlong sourceUid, jlong ahbPtr,
                                                                jint offX, jint offY, jboolean immediate,
                                                                jlong desiredPresentNs) {
    if (!h || !ahbPtr || sourceUid <= 0) return JNI_FALSE;
    std::shared_ptr<Ctx> ctx = *handleOf(h);
    std::lock_guard<std::mutex> lk(ctx->mu);
    auto it = ctx->layers.find(windowId);
    if (it == ctx->layers.end()) return JNI_FALSE;
    const std::shared_ptr<Layer>& L = it->second;
    auto* ahb = reinterpret_cast<AHardwareBuffer*>(ahbPtr);
    const bool moved = L->offX != offX || L->offY != offY;
    L->offX = offX;
    L->offY = offY;
    const int64_t latest = L->pendAhb ? L->pendSource : L->curSource;
    if (sourceUid == latest && !immediate) {
        L->bufDirty = true;
        if (moved) ctx->xformDirty = true;
        return JNI_TRUE;
    }
    Tx tx;
    if (!tx.raw) return JNI_FALSE;
    ctx->submit(tx, L, sourceUid, ahb, (int64_t) desiredPresentNs);
    ctx->sendTx(tx);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_ASurfaceXServerView_nativeFlush(JNIEnv*, jobject, jlong h) {
    if (!h) return;
    std::shared_ptr<Ctx> ctx = *handleOf(h);
    std::lock_guard<std::mutex> lk(ctx->mu);
    ctx->flushLocked();
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_ASurfaceXServerView_nativeSetPointer(JNIEnv*, jobject, jlong h, jint x, jint y) {
    if (!h) return;
    std::shared_ptr<Ctx> ctx = *handleOf(h);
    std::lock_guard<std::mutex> lk(ctx->mu);
    if (ctx->ptrX == x && ctx->ptrY == y) return;
    ctx->ptrX = x;
    ctx->ptrY = y;
    ctx->curGeoDirty = true;
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_ASurfaceXServerView_nativeSetCursorVisible(JNIEnv*, jobject, jlong h, jboolean v) {
    if (!h) return;
    std::shared_ptr<Ctx> ctx = *handleOf(h);
    std::lock_guard<std::mutex> lk(ctx->mu);
    if (ctx->curWanted == (bool) v) return;
    ctx->curWanted = v;
    ctx->curGeoDirty = true;
}

// pixels: the cursor image (stride in pixels). Always a new buffer: SurfaceFlinger may still be
// reading the previous one.
JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_ASurfaceXServerView_nativeSetCursorImage(JNIEnv* env, jobject, jlong h,
                                                                        jobject pixels, jint w, jint hh,
                                                                        jint stride, jint hotX, jint hotY,
                                                                        jboolean bgra) {
    if (!h || !pixels || w <= 0 || hh <= 0) return;
    const auto* src = static_cast<const uint8_t*>(env->GetDirectBufferAddress(pixels));
    if (!src) return;
    if (stride < w) stride = w;
    AHardwareBuffer_Desc d{};
    d.width = (uint32_t) w; d.height = (uint32_t) hh; d.layers = 1;
    d.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    d.usage = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
              AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY;
    AHardwareBuffer* buf = nullptr;
    if (AHardwareBuffer_allocate(&d, &buf) != 0) {
        d.usage = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
        if (AHardwareBuffer_allocate(&d, &buf) != 0) return;
    }
    AHardwareBuffer_Desc od{};
    AHardwareBuffer_describe(buf, &od);
    void* dst = nullptr;
    if (AHardwareBuffer_lock(buf, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, nullptr, &dst) != 0 || !dst) {
        AHardwareBuffer_release(buf);
        return;
    }
    for (int y = 0; y < hh; y++) {
        const uint8_t* s = src + (size_t) y * stride * 4;
        uint8_t* o = static_cast<uint8_t*>(dst) + (size_t) y * od.stride * 4;
        if (bgra) {
            for (int x = 0; x < w; x++) {
                o[x * 4] = s[x * 4 + 2];
                o[x * 4 + 1] = s[x * 4 + 1];
                o[x * 4 + 2] = s[x * 4];
                o[x * 4 + 3] = s[x * 4 + 3];
            }
        } else {
            memcpy(o, s, (size_t) w * 4);
        }
    }
    AHardwareBuffer_unlock(buf, nullptr);

    std::shared_ptr<Ctx> ctx = *handleOf(h);
    std::lock_guard<std::mutex> lk(ctx->mu);
    if (ctx->curBuf) AHardwareBuffer_release(ctx->curBuf);
    ctx->curBuf = buf;
    ctx->curW = w;
    ctx->curH = hh;
    ctx->hotX = hotX;
    ctx->hotY = hotY;
    ctx->curBufPending = true;
    ctx->curGeoDirty = true;
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_ASurfaceXServerView_nativeReleaseDrawable(JNIEnv*, jobject, jlong h, jlong uid) {
    if (!h) return;
    std::shared_ptr<Ctx> ctx = *handleOf(h);
    std::lock_guard<std::mutex> lk(ctx->mu);
    // A frame still waiting for a slot is dropped; what is on screen stays (we hold the buffer).
    for (auto& kv : ctx->layers) {
        if (kv.second->pendAhb && kv.second->pendSource == uid) ctx->dropPending(kv.second);
    }
    // Whatever is left to track for a buffer that is gone is the copy's fence.
    {
        auto it = ctx->bufs.find(uid);
        if (it != ctx->bufs.end() && it->second.shownRefs <= 0 && it->second.awaitCb <= 0) {
            if (it->second.fence >= 0) close(it->second.fence);
            ctx->bufs.erase(it);
        }
    }
}

JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_widget_ASurfaceXServerView_nativeSourceBusy(JNIEnv*, jobject, jlong h, jlong uid) {
    if (!h) return JNI_FALSE;
    std::shared_ptr<Ctx> ctx = *handleOf(h);
    std::lock_guard<std::mutex> lk(ctx->mu);
    return ctx->busyLocked(uid, nowNs()) ? JNI_TRUE : JNI_FALSE;
}

// Registered under the Java lock nativeDestroy also runs under: destroy waits for registered
// waits to return, so the context cannot vanish under one.
JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_widget_ASurfaceXServerView_nativeBeginWait(JNIEnv*, jobject, jlong h, jlong uid) {
    (void) uid;
    if (!h) return 0;
    std::shared_ptr<Ctx> ctx = *handleOf(h);
    std::lock_guard<std::mutex> lk(ctx->mu);
    if (ctx->closing) return 0;
    ctx->waiters++;
    return (jlong) ++ctx->waitSerial;
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_widget_ASurfaceXServerView_nativeEndWait(JNIEnv*, jobject, jlong h, jlong uid,
                                                                jlong serial, jlong timeoutNs) {
    (void) serial;
    if (!h) return;
    std::shared_ptr<Ctx> ctx = *handleOf(h);
    const int64_t deadline = nowNs() + std::max<int64_t>(0, timeoutNs);
    std::unique_lock<std::mutex> lk(ctx->mu);
    while (!ctx->closing) {
        const int64_t now = nowNs();
        if (!ctx->busyLocked(uid, now)) break;
        if (now >= deadline) break;
        auto it = ctx->bufs.find(uid);
        int fenceFd = -1;
        if (it != ctx->bufs.end() && it->second.shownRefs <= 0 &&
            (it->second.awaitCb <= 0 || now - it->second.replacedAtNs >= kCallbackGraceNs) &&
            it->second.fence >= 0) {
            fenceFd = dup(it->second.fence);
        }
        if (fenceFd >= 0) {
            lk.unlock();
            // Short slices: a destroy waiting for this wait (it holds the view's lock) must not
            // sit out a long fence; `closing` is looked at again after each slice.
            const int64_t ms = std::max<int64_t>(1, (deadline - now) / 1'000'000);
            pollfd pfd{fenceFd, POLLIN, 0};
            poll(&pfd, 1, (int) std::min<int64_t>(ms, 20));
            close(fenceFd);
            lk.lock();
        } else {
            ctx->cv.wait_until(lk, Clock::now() + std::chrono::nanoseconds(deadline - now));
        }
    }
    ctx->waiters--;
    ctx->cv.notify_all();
}

}  // extern "C"
