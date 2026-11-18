#include "DirectServer.h"
#include "VulkanRendererContext.h"

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/sync_file.h>
#include <poll.h>
#include <pthread.h>
#include <sys/epoll.h>
#include <sys/eventfd.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cstring>

#define DLOG(...) __android_log_print(ANDROID_LOG_INFO, "Winlator_Direct", __VA_ARGS__)
#define DLOG_E(...) __android_log_print(ANDROID_LOG_ERROR, "Winlator_Direct", __VA_ARGS__)

namespace {

constexpr uint64_t kMs = 1000000ull;
constexpr uint64_t kHysteresisNs   = 400 * kMs;   // conditions must hold this long before Direct
constexpr uint64_t kLeaveGiveUpNs  = 500 * kMs;   // the X content never came: take the composite
constexpr uint64_t kRevealNoTiming = 50 * kMs;    // composite frame shown (no display timing)
constexpr uint64_t kRevealMaxNs    = 200 * kMs;
constexpr uint64_t kLeavingTickNs  = 8 * kMs;
constexpr size_t   kMaxClients     = 8;

// epoll tags: kind in the top byte, the rest is the id / fd.
enum : uint64_t { kTagListen = 1ull << 56, kTagEvent = 2ull << 56, kTagClient = 3ull << 56, kTagFence = 4ull << 56 };
constexpr uint64_t kTagMask = 0xffull << 56;

uint64_t nowNs() {
    return (uint64_t)std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

void closeFd(int& fd) {
    if (fd >= 0) close(fd);
    fd = -1;
}

// When a signaled sync file signaled (CLOCK_MONOTONIC ns): the latest of its fences; 0 if
// unknown.
uint64_t syncFileSignalTime(int fd) {
    struct sync_file_info info;
    memset(&info, 0, sizeof(info));
    if (ioctl(fd, SYNC_IOC_FILE_INFO, &info) < 0 || info.num_fences == 0 || info.num_fences > 64) return 0;
    std::vector<struct sync_fence_info> fences(info.num_fences);
    memset(fences.data(), 0, fences.size() * sizeof(fences[0]));
    info.sync_fence_info = (uint64_t)(uintptr_t)fences.data();
    if (ioctl(fd, SYNC_IOC_FILE_INFO, &info) < 0) return 0;
    uint64_t t = 0;
    for (const auto& f : fences) if (f.status == 1) t = std::max<uint64_t>(t, f.timestamp_ns);
    return t;
}

// Transaction callbacks come on a binder thread, possibly after the server is gone: they find
// it (and the layer the transaction set a buffer on) here.
std::mutex gCbMu;
DirectServer* gServer = nullptr;
std::unordered_map<uint64_t, ASurfaceControl*> gTxLayer;   // under gCbMu
DirectSc* gSc = nullptr;                                     // under gCbMu
std::atomic<uint64_t> gTxSeq{0};

void onTxComplete(void* context, ASurfaceTransactionStats* stats) {
    const uint64_t id = (uint64_t)(uintptr_t)context;
    std::lock_guard<std::mutex> g(gCbMu);
    auto it = gTxLayer.find(id);
    if (!gServer || !gSc || it == gTxLayer.end()) return;
    ASurfaceControl* sc = it->second;
    gTxLayer.erase(it);
    DirectServer::TxDone d{};
    d.id = id;
    d.latchNs = gSc->statsLatchTime(stats);
    d.presentFd = gSc->statsPresentFenceFd(stats);
    d.prevReleaseFd = sc ? gSc->statsPreviousReleaseFenceFd(stats, sc) : -1;
    d.committed = false;
    gServer->postTxDone(d);
}

void onTxCommit(void* context, ASurfaceTransactionStats*) {
    const uint64_t id = (uint64_t)(uintptr_t)context;
    std::lock_guard<std::mutex> g(gCbMu);
    if (!gServer || !gTxLayer.count(id)) return;
    DirectServer::TxDone d{};
    d.id = id;
    d.presentFd = -1;
    d.prevReleaseFd = -1;
    d.committed = true;
    gServer->postTxDone(d);
}

ARect arect(int32_t x, int32_t y, int32_t w, int32_t h) {
    ARect r;
    r.left = x; r.top = y; r.right = x + w; r.bottom = y + h;
    return r;
}

}  // namespace

// ================================================================ lifetime

DirectServer::DirectServer(VulkanRendererContext& ctx) : ctx_(ctx) {
    sc_.load();
    DLOG("SurfaceControl %s (API %d, commit callbacks %s)", sc_.ok ? "available" : "unavailable",
         sc_.api, sc_.setOnCommit ? "yes" : "no");
    {
        std::lock_guard<std::mutex> g(gCbMu);
        gServer = this;
        gSc = &sc_;
    }
    epollFd_ = epoll_create1(EPOLL_CLOEXEC);
    eventFd_ = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
    if (epollFd_ >= 0 && eventFd_ >= 0) {
        epoll_event ev{};
        ev.events = EPOLLIN;
        ev.data.u64 = kTagEvent;
        epoll_ctl(epollFd_, EPOLL_CTL_ADD, eventFd_, &ev);
        running_ = true;
        thread_ = std::thread(&DirectServer::threadMain, this);
    } else {
        DLOG_E("epoll/eventfd failed (%d): no WL Direct clients, no fence waits", errno);
    }
}

DirectServer::~DirectServer() {
    detach();
    running_ = false;
    wakeThread();
    if (thread_.joinable()) thread_.join();
    {
        std::lock_guard<std::mutex> g(gCbMu);
        gServer = nullptr;
        gSc = nullptr;
        gTxLayer.clear();
    }
    Deferred d;
    {
        std::lock_guard<std::mutex> lk(mu_);
        std::vector<int> ids;
        for (auto& [id, c] : clients_) ids.push_back(id);
        for (int id : ids) dropClientLocked(id, d);
        for (auto& [fd, w] : fences_) close(fd);
        fences_.clear();
        for (auto& s : cursorSlots_) if (s.ahb) { AHardwareBuffer_release(s.ahb); s = CursorSlot{}; }
    }
    runDeferred(d);
    {
        std::lock_guard<std::mutex> g(cbMu_);
        for (auto& t : cbQueue_) { closeFd(t.presentFd); closeFd(t.prevReleaseFd); }
        cbQueue_.clear();
    }
    closeFd(listenFd_);
    if (!path_.empty()) unlink(path_.c_str());
    closeFd(eventFd_);
    closeFd(epollFd_);
}

void DirectServer::runDeferred(Deferred& d) {
    for (auto& f : d) f();
    d.clear();
}

void DirectServer::wakeThread() {
    if (eventFd_ < 0) return;
    const uint64_t one = 1;
    (void)!write(eventFd_, &one, sizeof(one));
}

void DirectServer::postTxDone(const TxDone& d) {
    {
        std::lock_guard<std::mutex> g(cbMu_);
        cbQueue_.push_back(d);
    }
    wakeThread();
}

// ================================================================ surface

void DirectServer::createLayersLocked() {
    if (!sc_.ok || !window_) return;
    container_ = sc_.createFromWindow(window_, "WinLite Direct");
    if (!container_) { DLOG_E("ASurfaceControl_createFromWindow failed"); return; }
    backdrop_ = sc_.create(container_, "WinLite Direct backdrop");
    game_.sc = sc_.create(container_, "WinLite Direct game");
    cursor_.sc = sc_.create(container_, "WinLite Direct cursor");
    if (!backdrop_ || !game_.sc || !cursor_.sc) {
        DLOG_E("ASurfaceControl_create failed");
        Deferred unused;
        destroyLayersLocked(unused);
        return;
    }
    ASurfaceTransaction* t = sc_.txCreate();
    // Over the composite's own buffer (z 0 of the SurfaceView), under the app window as the
    // SurfaceView itself. The backdrop hides what the composite presented last (its cursor too).
    sc_.setZOrder(t, container_, 1);
    sc_.setVisibility(t, container_, DirectSc::kVisibilityHide);
    sc_.setColor(t, backdrop_, 0.f, 0.f, 0.f, 1.f, DirectSc::kDataspaceSrgb);
    sc_.setZOrder(t, backdrop_, 0);
    sc_.setZOrder(t, game_.sc, 1);
    sc_.setZOrder(t, cursor_.sc, 2);
    sc_.setVisibility(t, cursor_.sc, DirectSc::kVisibilityHide);
    sc_.txApply(t);
    sc_.txDelete(t);
    shown_ = false;
    cursorVisibleApplied_ = false;
    cursorDstApplied_ = Rect{};
    DLOG("layers created");
}

void DirectServer::destroyLayersLocked(Deferred& d) {
    if (container_) {
        ASurfaceTransaction* t = sc_.txCreate();
        sc_.setVisibility(t, container_, DirectSc::kVisibilityHide);
        sc_.reparent(t, container_, nullptr);
        sc_.txApply(t);
        sc_.txDelete(t);
    }
    for (ASurfaceControl** s : {&cursor_.sc, &game_.sc, &backdrop_, &container_})
        if (*s) { sc_.release(*s); *s = nullptr; }
    // Everything the layers had or were about to get goes back now: the surface is gone.
    {
        std::lock_guard<std::mutex> g(gCbMu);
        for (auto& [id, tx] : txs_) gTxLayer.erase(id);
    }
    txs_.clear();
    for (Layer* L : {&game_, &cursor_}) {
        if (L->pending.valid) dropFrameLocked(L->pending, d);
        if (L->attachedLive) giveBackLocked(L->attached, -1, d);
        *L = Layer{};
    }
    for (auto& s : cursorSlots_) s.busy = false;
    for (auto it = fences_.begin(); it != fences_.end();) {
        if (it->second.kind == FenceWatch::ReleaseX) {
            AHardwareBuffer* ahb = it->second.ahb;
            epoll_ctl(epollFd_, EPOLL_CTL_DEL, it->first, nullptr);
            close(it->first);
            it = fences_.erase(it);
            held_.erase(ahb);
            d.push_back([this, ahb] { ctx_.directBufferReleased(ahb); AHardwareBuffer_release(ahb); });
        } else if (it->second.kind == FenceWatch::ReleaseCursor) {
            epoll_ctl(epollFd_, EPOLL_CTL_DEL, it->first, nullptr);
            close(it->first);
            it = fences_.erase(it);
        } else {
            ++it;
        }
    }
    for (AHardwareBuffer* ahb : held_)
        d.push_back([this, ahb] { ctx_.directBufferReleased(ahb); AHardwareBuffer_release(ahb); });
    held_.clear();
    shown_ = false;
    hiding_ = false;
    if (Client* c = clientLocked(client_)) {
        if (c->fxFrame.valid) dropFrameLocked(c->fxFrame, d);
        setGrantLocked(*c, false, WL_REASON_DETACHED);
    }
    phase_ = Phase::X;
    route_ = Route::X;
    client_ = -1;
    window_id_ = 0;
    watchWindow_ = 0;
}

void DirectServer::attach(ANativeWindow* window) {
    std::lock_guard<std::mutex> lk(mu_);
    if (window_ == window && container_) return;
    if (window_) ANativeWindow_release(window_);
    window_ = window;
    if (window_) ANativeWindow_acquire(window_);
    createLayersLocked();
}

void DirectServer::detach() {
    Deferred d;
    {
        std::lock_guard<std::mutex> lk(mu_);
        if (phase_ != Phase::X) DLOG("surface detached: back to the composite");
        destroyLayersLocked(d);
        if (window_) { ANativeWindow_release(window_); window_ = nullptr; }
        statusSink_ = 0;
        statusSource_ = 0;
        statusReason_ = WL_REASON_DETACHED;
    }
    runDeferred(d);
}

void DirectServer::setMode(int mode) {
    mode = std::clamp(mode, (int)WL_OUTPUT_OFF, (int)WL_OUTPUT_ALWAYS);
    if (mode_.exchange(mode) != mode) {
        DLOG("output mode %s", mode == WL_OUTPUT_OFF ? "Off" : mode == WL_OUTPUT_AUTO ? "Auto" : "Always");
        ctx_.requestRender();
    }
}

// ================================================================ decision

DirectServer::Client* DirectServer::clientLocked(int id) {
    auto it = clients_.find(id);
    return it == clients_.end() ? nullptr : &it->second;
}

bool DirectServer::hasClient(int64_t xWindow) const {
    std::lock_guard<std::mutex> lk(mu_);
    for (const auto& entry : clients_)
        if ((int64_t)entry.second.xWindow == xWindow) return true;
    return false;
}

void DirectServer::sendLocked(Client& c, wl_direct_hdr* h, int fd) {
    h->seq = ++c.seq;
    const int r = wl_direct_send(c.fd, h, fd);
    if (r < 0 && r != -EAGAIN) DLOG_E("client %d: send failed (%d)", c.id, -r);
}

void DirectServer::setGrantLocked(Client& c, bool granted, int reason) {
    if (c.granted == granted && c.reason == reason) return;
    c.granted = granted;
    c.reason = reason;
    wl_direct_grant m{};
    wl_direct_hdr_init(&m.hdr, WL_DIRECT_GRANT, 0);
    m.granted = granted ? 1 : 0;
    m.reason = (uint32_t)reason;
    m.panel_x = dst_.x; m.panel_y = dst_.y; m.panel_w = dst_.w; m.panel_h = dst_.h;
    m.refresh_ns = ctx_.refreshPeriodNs();
    sendLocked(c, &m.hdr, -1);
    if (granted) DLOG("client %d (window 0x%x, pid %u): grant", c.id, c.xWindow, c.pid);
}

DirectServer::Plan DirectServer::plan(const Candidate& c, uint64_t now) {
    Deferred d;
    Plan p;
    {
        std::lock_guard<std::mutex> lk(mu_);
        lastWatchGen_ = c.watchGen;
        const int mode = mode_.load();
        int reason = c.reason;
        if (mode == WL_OUTPUT_OFF) reason = WL_REASON_OFF;
        else if (!sc_.ok) reason = WL_REASON_API;
        else if (!container_) reason = WL_REASON_DETACHED;

        Client* wl = nullptr;
        if (reason == WL_REASON_OK)
            for (auto& [id, cl] : clients_) if ((int64_t)cl.xWindow == c.window) { wl = &cl; break; }
        Route src = Route::X;
        if (reason == WL_REASON_OK) {
            if (wl) src = Route::DirectWl;
            else if (c.xSwapped && c.xBuffer) src = Route::DirectX;
            else reason = WL_REASON_FORMAT;   // a buffer drawn in place (CPU, single-buffered)
        }
        if (reason == WL_REASON_OK && c.needsFx && !c.fxPossible) reason = WL_REASON_FORMAT;
        if (reason == WL_REASON_OK) dst_ = c.dst;
        // Moved or scaled with no new frame (the view's transform changed): place it again.
        if (phase_ == Phase::Direct && game_.sc && game_.attachedLive && game_.placed != dst_) {
            ASurfaceTransaction* t = sc_.txCreate();
            sc_.place(t, game_.sc, arect(0, 0, game_.w, game_.h), arect(dst_.x, dst_.y, dst_.w, dst_.h));
            sc_.txApply(t);
            sc_.txDelete(t);
            game_.placed = dst_;
        }

        bool ready = false;
        if (reason == WL_REASON_OK) {
            if (stableWindow_ != c.window || !stableSince_) { stableWindow_ = c.window; stableSince_ = now; }
            const uint64_t hold = mode == WL_OUTPUT_ALWAYS ? 0 : kHysteresisNs;
            if (now - stableSince_ >= hold) ready = true;
            else { reason = WL_REASON_SETTLING; p.wakeAtNs = stableSince_ + hold; }
        } else {
            stableSince_ = 0;
            stableWindow_ = 0;
        }
        reason_ = reason;

        switch (phase_) {
        case Phase::X:
            if (ready) {
                window_id_ = c.window;
                watchWindow_ = c.window;
                fx_ = c.needsFx;
                route_ = src;
                if (src == Route::DirectWl) {
                    client_ = wl->id;
                    setGrantLocked(*wl, true, WL_REASON_OK);
                    phase_ = Phase::Wait;
                } else {
                    client_ = -1;
                    phase_ = Phase::Direct;
                    DLOG("window 0x%llx: Direct from X%s", (unsigned long long)c.window, fx_ ? " + FX" : "");
                }
            }
            break;
        case Phase::Wait:
            if (!ready || src != Route::DirectWl || c.window != window_id_) {
                // Nothing was shown yet: back off quietly.
                if (Client* g = clientLocked(client_)) {
                    if (g->fxFrame.valid) dropFrameLocked(g->fxFrame, d);
                    setGrantLocked(*g, false, reason == WL_REASON_OK ? WL_REASON_NOT_TOP : reason);
                }
                client_ = -1;
                phase_ = Phase::X;
                route_ = Route::X;
                watchWindow_ = 0;
            } else {
                fx_ = c.needsFx;
            }
            break;
        case Phase::Direct:
            if (!ready || src != route_ || c.window != window_id_) leaveLocked(now, d);
            else fx_ = c.needsFx;
            break;
        case Phase::Leaving:
            break;
        }

        // The others are told why they have no grant (only when that changes).
        for (auto& [id, cl] : clients_) {
            if (id == client_) continue;
            // The game window's client while the rule settles: SETTLING; one more swapchain of
            // the same window while another holds the grant: OTHER_CLIENT.
            // NOT_TOP only when a window is the candidate and this client is not its window; a
            // refusal (scissor, windowed, no window ...) is reported as it is.
            const bool refused = reason != WL_REASON_OK && reason != WL_REASON_SETTLING;
            const int r = refused ? reason
                : (int64_t)cl.xWindow == c.window
                    ? (reason == WL_REASON_OK ? WL_REASON_OTHER_CLIENT : reason)
                    : WL_REASON_NOT_TOP;
            setGrantLocked(cl, false, r);
        }

        p.route = route_;
        p.fx = fx_;
        bool fxFrameWaiting = false;
        if (Client* g = clientLocked(client_)) fxFrameWaiting = g->fxFrame.valid;
        p.composite = phase_ == Phase::X || phase_ == Phase::Leaving ||
                      (phase_ == Phase::Wait && !(fx_ && fxFrameWaiting));
        if (phase_ == Phase::Leaving) p.wakeAtNs = now + kLeavingTickNs;

        statusSink_ = phase_ == Phase::Direct ? (fx_ ? 2 : 1) : 0;
        statusSource_ = phase_ == Phase::Direct ? (route_ == Route::DirectWl ? 2 : 1) : 0;
        statusReason_ = phase_ == Phase::Direct ? WL_REASON_OK : reason;
    }
    runDeferred(d);
    return p;
}

void DirectServer::leaveLocked(uint64_t now, Deferred& d) {
    DLOG("window 0x%llx: back to the composite (%d)", (unsigned long long)window_id_, reason_);
    if (Client* g = clientLocked(client_)) {
        if (g->fxFrame.valid) dropFrameLocked(g->fxFrame, d);
        setGrantLocked(*g, false, reason_ == WL_REASON_OK ? WL_REASON_NOT_TOP : reason_);
    }
    phase_ = Phase::Leaving;
    leaveGen_ = lastWatchGen_;
    leaveSince_ = now;
    revealSeq_ = 0;
    revealAt_ = 0;
    // The composite draws the cursor from now on.
    if (cursor_.sc && cursorVisibleApplied_) {
        ASurfaceTransaction* t = sc_.txCreate();
        sc_.setVisibility(t, cursor_.sc, DirectSc::kVisibilityHide);
        sc_.txApply(t);
        sc_.txDelete(t);
        cursorVisibleApplied_ = false;
    }
    if (game_.pending.valid) dropFrameLocked(game_.pending, d);
    d.push_back([this] { ctx_.requestRender(); });
}

void DirectServer::compositePresented(uint64_t presentSeq, uint64_t shownSeq, uint64_t now) {
    Deferred d;
    {
        std::lock_guard<std::mutex> lk(mu_);
        if (phase_ != Phase::Leaving || hiding_) return;
        // A composite frame stands in for Direct once it has the window's X content: the WL
        // client presents its latest image through X after the revoke. From X the composite
        // has had it all along. A client that presents nothing more is not waited for long.
        const bool suitable = route_ != Route::DirectWl || lastWatchGen_ > leaveGen_ ||
                              now - leaveSince_ >= kLeaveGiveUpNs;
        if (suitable && !revealSeq_) { revealSeq_ = presentSeq; revealAt_ = now; }
        if (!revealSeq_) return;
        const bool shown = shownSeq ? shownSeq >= revealSeq_ : now - revealAt_ >= kRevealNoTiming;
        if (shown || now - revealAt_ >= kRevealMaxNs) hideLocked(d);
    }
    runDeferred(d);
}

void DirectServer::hideLocked(Deferred& d) {
    if (!shown_ || !container_) { finishLeavingLocked(d); return; }
    hiding_ = true;
    ASurfaceTransaction* t = sc_.txCreate();
    sc_.setVisibility(t, container_, DirectSc::kVisibilityHide);
    Tx tx;
    tx.layer = -1;
    tx.hide = true;
    newTxLocked(tx, nullptr, t);
    sc_.txApply(t);
    sc_.txDelete(t);
    shown_ = false;
}

void DirectServer::finishLeavingLocked(Deferred& d) {
    // Hidden and shown no more: what the layers still have is free.
    for (Layer* L : {&game_, &cursor_}) {
        if (L->pending.valid) dropFrameLocked(L->pending, d);
        if (L->attachedLive) giveBackLocked(L->attached, -1, d);
        L->attachedLive = false;
    }
    phase_ = Phase::X;
    route_ = Route::X;
    client_ = -1;
    window_id_ = 0;
    watchWindow_ = 0;
    hiding_ = false;
    cursorSerialApplied_ = ~0ull;
    statusSink_ = 0;
    statusSource_ = 0;
    DLOG("composite");
}

// ================================================================ buffers

uint64_t DirectServer::newTxLocked(const Tx& txIn, ASurfaceControl* sc, ASurfaceTransaction* t) {
    Tx tx = txIn;
    tx.id = ++gTxSeq;
    txs_[tx.id] = tx;
    {
        std::lock_guard<std::mutex> g(gCbMu);
        gTxLayer[tx.id] = sc;
    }
    sc_.setOnComplete(t, (void*)(uintptr_t)tx.id, onTxComplete);
    if (tx.layer >= 0 && sc_.setOnCommit) sc_.setOnCommit(t, (void*)(uintptr_t)tx.id, onTxCommit);
    return tx.id;
}

void DirectServer::showLocked(ASurfaceTransaction* t) {
    sc_.setVisibility(t, container_, DirectSc::kVisibilityShow);
    shown_ = true;
}

void DirectServer::applyFrameLocked(int layer, Frame f, Deferred& d) {
    Layer& L = layer == 0 ? game_ : cursor_;
    if (!L.sc) { dropFrameLocked(f, d); return; }
    if (L.busy) {
        // Latest wins: the frame waiting behind the transaction in flight was never shown.
        if (L.pending.valid) dropFrameLocked(L.pending, d);
        L.pending = f;
        return;
    }
    submitFrameLocked(layer, f, d);
}

void DirectServer::submitFrameLocked(int layer, Frame f, Deferred&) {
    Layer& L = layer == 0 ? game_ : cursor_;
    ASurfaceTransaction* t = sc_.txCreate();
    sc_.setBuffer(t, L.sc, f.buf.ahb, f.fence);   // the transaction owns the fence now
    f.fence = -1;
    const ARect src = arect(0, 0, f.w, f.h);
    const Rect& r = layer == 0 ? dst_ : cursorDst_;
    sc_.place(t, L.sc, src, arect(r.x, r.y, r.w, r.h));
    sc_.setBufferTransparency(t, L.sc, layer == 0 ? DirectSc::kTransparencyOpaque
                                                  : DirectSc::kTransparencyTranslucent);
    if (sc_.setBufferDataspace) sc_.setBufferDataspace(t, L.sc, DirectSc::kDataspaceSrgb);
    if (f.desiredNs && sc_.setDesiredPresentTime) sc_.setDesiredPresentTime(t, (int64_t)f.desiredNs);
    if (layer == 1) {
        sc_.setVisibility(t, L.sc, cursorVisible_ ? DirectSc::kVisibilityShow : DirectSc::kVisibilityHide);
        cursorVisibleApplied_ = cursorVisible_;
        cursorDstApplied_ = cursorDst_;
    }
    if (!shown_ && layer == 0) showLocked(t);
    Tx tx;
    tx.layer = layer;
    tx.prev = L.attached;
    tx.prevLive = L.attachedLive;
    tx.client = f.buf.kind == BufKind::Wl ? f.buf.client : -1;
    tx.frameId = f.frameId;
    newTxLocked(tx, L.sc, t);
    sc_.txApply(t);
    sc_.txDelete(t);
    L.busy = true;
    L.attached = f.buf;
    L.attachedLive = true;
    L.w = f.w;
    L.h = f.h;
    if (layer == 0) L.placed = dst_;
}

void DirectServer::dropFrameLocked(Frame& f, Deferred& d) {
    if (!f.valid) return;
    closeFd(f.fence);
    giveBackLocked(f.buf, -1, d);
    f = Frame{};
}

// A buffer the layers are done with goes back to its owner once `fenceFd` (owned, -1: now)
// signals.
void DirectServer::giveBackLocked(const Buf& b, int fenceFd, Deferred& d) {
    switch (b.kind) {
    case BufKind::X:
        if (fenceFd >= 0) {
            FenceWatch w{FenceWatch::ReleaseX};
            w.ahb = b.ahb;
            watchFenceLocked(fenceFd, w);
        } else {
            AHardwareBuffer* ahb = b.ahb;
            held_.erase(ahb);
            d.push_back([this, ahb] { ctx_.directBufferReleased(ahb); AHardwareBuffer_release(ahb); });
        }
        break;
    case BufKind::Wl:
        if (Client* c = clientLocked(b.client)) {
            wl_direct_release m{};
            wl_direct_hdr_init(&m.hdr, WL_DIRECT_RELEASE, 0);
            m.image_index = b.index;
            m.flags = fenceFd >= 0 ? WL_RELEASE_HAS_FENCE : 0;
            sendLocked(*c, &m.hdr, fenceFd);
        }
        if (fenceFd >= 0) close(fenceFd);
        break;
    case BufKind::Ring: {
        const uint32_t gen = b.gen, slot = b.index;
        d.push_back([this, gen, slot, fenceFd] { ctx_.directRingReleased(gen, slot, fenceFd); });
        break;
    }
    case BufKind::Cursor:
        if (fenceFd >= 0) {
            FenceWatch w{FenceWatch::ReleaseCursor};
            w.slot = b.index;
            watchFenceLocked(fenceFd, w);
        } else if (b.index < kCursorSlots) {
            cursorSlots_[b.index].busy = false;
            if (cursorDirty_) applyCursorLocked(d);
        }
        break;
    case BufKind::None:
        if (fenceFd >= 0) close(fenceFd);
        break;
    }
}

void DirectServer::watchFenceLocked(int fd, const FenceWatch& w) {
    if (epollFd_ < 0) {
        // No server thread: wait here (rare; only when epoll could not be created).
        struct pollfd pfd{fd, POLLIN, 0};
        poll(&pfd, 1, 1000);
        close(fd);
        return;
    }
    fences_[fd] = w;
    epoll_event ev{};
    ev.events = EPOLLIN;
    ev.data.u64 = kTagFence | (uint64_t)(uint32_t)fd;
    epoll_ctl(epollFd_, EPOLL_CTL_ADD, fd, &ev);
}

bool DirectServer::holds(AHardwareBuffer* ahb) const {
    std::lock_guard<std::mutex> lk(mu_);
    return held_.count(ahb) != 0;
}

DirectServer::Status DirectServer::status() const {
    Status s;
    s.sink = statusSink_.load();
    s.reason = statusReason_.load();
    s.source = statusSource_.load();
    return s;
}

// ---------------------------------------------------------------- render thread feeds

void DirectServer::pushX(AHardwareBuffer* ahb, int32_t w, int32_t h) {
    Deferred d;
    {
        std::lock_guard<std::mutex> lk(mu_);
        if (phase_ != Phase::Direct || route_ != Route::DirectX || fx_ || !ahb) return;
        if (game_.attachedLive && game_.attached.kind == BufKind::X && game_.attached.ahb == ahb) return;
        if (game_.pending.valid && game_.pending.buf.ahb == ahb) return;
        if (held_.count(ahb)) return;   // still on its way back from SF: shows with the next one
        AHardwareBuffer_acquire(ahb);
        held_.insert(ahb);
        Frame f;
        f.buf.kind = BufKind::X;
        f.buf.ahb = ahb;
        f.w = w; f.h = h;
        f.valid = true;
        applyFrameLocked(0, f, d);
    }
    runDeferred(d);
}

void DirectServer::pushFx(uint32_t gen, uint32_t slot, AHardwareBuffer* ahb, int32_t w, int32_t h, int fenceFd) {
    Deferred d;
    {
        std::lock_guard<std::mutex> lk(mu_);
        Frame f;
        f.buf.kind = BufKind::Ring;
        f.buf.ahb = ahb;
        f.buf.index = slot;
        f.buf.gen = gen;
        f.fence = fenceFd;
        f.w = w; f.h = h;
        f.valid = true;
        if (!(phase_ == Phase::Direct || phase_ == Phase::Wait) || !fx_) {
            dropFrameLocked(f, d);
        } else {
            applyFrameLocked(0, f, d);
            if (phase_ == Phase::Wait) {
                phase_ = Phase::Direct;
                DLOG("window 0x%llx: Direct + FX from WL client %d", (unsigned long long)window_id_, client_);
            }
        }
    }
    runDeferred(d);
}

bool DirectServer::takeWlFrame(WlFrame& out) {
    std::lock_guard<std::mutex> lk(mu_);
    Client* c = clientLocked(client_);
    if (!c || !c->fxFrame.valid || !fx_) return false;
    out.client = c->id;
    out.image = c->fxFrame.buf.index;
    out.ahb = c->fxFrame.buf.ahb;
    out.acquireFd = c->fxFrame.fence;
    out.w = c->fxFrame.w;
    out.h = c->fxFrame.h;
    out.frameId = c->fxFrame.frameId;
    out.desiredNs = c->fxFrame.desiredNs;
    c->fxFrame = Frame{};
    return true;
}

void DirectServer::releaseWlFrame(int client, uint32_t image, int fenceFd) {
    Deferred d;
    {
        std::lock_guard<std::mutex> lk(mu_);
        Buf b;
        b.kind = BufKind::Wl;
        b.client = client;
        b.index = image;
        giveBackLocked(b, fenceFd, d);
    }
    runDeferred(d);
}

// ---------------------------------------------------------------- cursor

void DirectServer::updateCursor(bool visible, const Rect& dst, const uint32_t* rgba, int32_t w, int32_t h,
                                uint64_t serial) {
    Deferred d;
    {
        std::lock_guard<std::mutex> lk(mu_);
        cursorVisible_ = visible;
        cursorDst_ = dst;
        if (rgba && w > 0 && h > 0 && w <= 512 && h <= 512 && serial != cursorSerial_) {
            cursorPixels_.assign(rgba, rgba + (size_t)w * h);
            cursorW_ = w;
            cursorH_ = h;
            cursorSerial_ = serial;
        }
        if (cursorSerial_ != cursorSerialApplied_ && !cursorPixels_.empty()) cursorDirty_ = true;
        applyCursorLocked(d);
    }
    runDeferred(d);
}

void DirectServer::writeCursorLocked(uint32_t slot, const uint32_t* rgba, int32_t w, int32_t h) {
    CursorSlot& s = cursorSlots_[slot];
    if (s.ahb && (s.w != w || s.h != h)) { AHardwareBuffer_release(s.ahb); s.ahb = nullptr; }
    if (!s.ahb) {
        AHardwareBuffer_Desc desc{};
        desc.width = (uint32_t)w;
        desc.height = (uint32_t)h;
        desc.layers = 1;
        desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
        desc.usage = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                     AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY;
        if (AHardwareBuffer_allocate(&desc, &s.ahb) != 0) { s.ahb = nullptr; return; }
        s.w = w;
        s.h = h;
    }
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(s.ahb, &desc);
    void* addr = nullptr;
    if (AHardwareBuffer_lock(s.ahb, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, nullptr, &addr) != 0 || !addr) return;
    // SurfaceFlinger blends premultiplied alpha; the X cursor is straight alpha.
    for (int32_t y = 0; y < h; y++) {
        const uint8_t* src = reinterpret_cast<const uint8_t*>(rgba + (size_t)y * w);
        uint8_t* dst = static_cast<uint8_t*>(addr) + (size_t)y * desc.stride * 4;
        for (int32_t x = 0; x < w; x++) {
            const uint32_t a = src[x * 4 + 3];
            dst[x * 4 + 0] = (uint8_t)((src[x * 4 + 0] * a + 127) / 255);
            dst[x * 4 + 1] = (uint8_t)((src[x * 4 + 1] * a + 127) / 255);
            dst[x * 4 + 2] = (uint8_t)((src[x * 4 + 2] * a + 127) / 255);
            dst[x * 4 + 3] = (uint8_t)a;
        }
    }
    AHardwareBuffer_unlock(s.ahb, nullptr);
}

void DirectServer::applyCursorLocked(Deferred& d) {
    if (phase_ != Phase::Direct || !cursor_.sc) return;
    if (cursorDirty_ && !cursor_.busy) {
        int slot = -1;
        for (uint32_t i = 0; i < kCursorSlots; i++) if (!cursorSlots_[i].busy) { slot = (int)i; break; }
        if (slot >= 0) {
            writeCursorLocked((uint32_t)slot, cursorPixels_.data(), cursorW_, cursorH_);
            CursorSlot& s = cursorSlots_[slot];
            if (s.ahb) {
                s.busy = true;
                Frame f;
                f.buf.kind = BufKind::Cursor;
                f.buf.ahb = s.ahb;
                f.buf.index = (uint32_t)slot;
                f.w = s.w; f.h = s.h;
                f.valid = true;
                cursorDirty_ = false;
                cursorSerialApplied_ = cursorSerial_;
                submitFrameLocked(1, f, d);
                return;
            }
        }
    }
    // Moved or shown / hidden only: the layer's geometry and visibility, no buffer.
    if (!cursor_.attachedLive) return;
    if (cursorDst_ == cursorDstApplied_ && cursorVisible_ == cursorVisibleApplied_) return;
    const CursorSlot& s = cursorSlots_[cursor_.attached.index % kCursorSlots];
    ASurfaceTransaction* t = sc_.txCreate();
    sc_.place(t, cursor_.sc, arect(0, 0, s.w, s.h), arect(cursorDst_.x, cursorDst_.y, cursorDst_.w, cursorDst_.h));
    sc_.setVisibility(t, cursor_.sc, cursorVisible_ ? DirectSc::kVisibilityShow : DirectSc::kVisibilityHide);
    sc_.txApply(t);
    sc_.txDelete(t);
    cursorDstApplied_ = cursorDst_;
    cursorVisibleApplied_ = cursorVisible_;
}

// ================================================================ server thread

void DirectServer::listen(const std::string& path) {
    std::lock_guard<std::mutex> lk(mu_);
    if (path == path_ && listenFd_ >= 0) return;
    if (listenFd_ >= 0) {
        epoll_ctl(epollFd_, EPOLL_CTL_DEL, listenFd_, nullptr);
        closeFd(listenFd_);
        if (!path_.empty()) unlink(path_.c_str());
    }
    path_ = path;
    if (path.empty() || epollFd_ < 0) return;
    sockaddr_un addr{};
    addr.sun_family = AF_UNIX;
    if (path.size() >= sizeof(addr.sun_path)) { DLOG_E("socket path too long: %s", path.c_str()); return; }
    memcpy(addr.sun_path, path.c_str(), path.size() + 1);
    const int fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (fd < 0) { DLOG_E("socket failed (%d)", errno); return; }
    unlink(path.c_str());
    if (bind(fd, (sockaddr*)&addr, sizeof(addr)) != 0 || ::listen(fd, 8) != 0) {
        DLOG_E("cannot listen on %s (%d)", path.c_str(), errno);
        close(fd);
        return;
    }
    listenFd_ = fd;
    epoll_event ev{};
    ev.events = EPOLLIN;
    ev.data.u64 = kTagListen;
    epoll_ctl(epollFd_, EPOLL_CTL_ADD, fd, &ev);
    DLOG("WL Direct on %s", path.c_str());
}

void DirectServer::threadMain() {
    pthread_setname_np(pthread_self(), "WinLiteDirect");
    epoll_event evs[16];
    while (running_) {
        const int n = epoll_wait(epollFd_, evs, 16, -1);
        if (n < 0) {
            if (errno == EINTR) continue;
            DLOG_E("epoll_wait failed (%d): server thread exits", errno);
            return;
        }
        for (int i = 0; i < n; i++) {
            const uint64_t tag = evs[i].data.u64 & kTagMask;
            const uint64_t val = evs[i].data.u64 & ~kTagMask;
            if (tag == kTagEvent) {
                uint64_t v;
                while (read(eventFd_, &v, sizeof(v)) > 0) {}
                std::vector<TxDone> q;
                {
                    std::lock_guard<std::mutex> g(cbMu_);
                    q.swap(cbQueue_);
                }
                for (auto& t : q) onTxDone(t);
            } else if (tag == kTagListen) {
                acceptClient();
            } else if (tag == kTagClient) {
                readClient((int)val);
            } else if (tag == kTagFence) {
                onFence((int)val);
            }
        }
    }
}

void DirectServer::onTxDone(const TxDone& done) {
    Deferred d;
    int presentFd = done.presentFd, prevFd = done.prevReleaseFd;
    {
        std::lock_guard<std::mutex> lk(mu_);
        auto it = txs_.find(done.id);
        if (it == txs_.end()) {
            closeFd(presentFd);
            closeFd(prevFd);
            return;
        }
        if (done.committed) {
            // Latched: the next buffer may follow.
            Layer& L = it->second.layer == 1 ? cursor_ : game_;
            if (it->second.layer >= 0 && L.busy) {
                L.busy = false;
                if (L.pending.valid) { Frame f = L.pending; L.pending = Frame{}; submitFrameLocked(it->second.layer, f, d); }
                else if (it->second.layer == 1 && cursorDirty_) applyCursorLocked(d);
            }
            return;
        }
        const Tx tx = it->second;
        txs_.erase(it);
        if (tx.hide) {
            closeFd(prevFd);
            finishLeavingLocked(d);
        } else if (tx.layer >= 0) {
            Layer& L = tx.layer == 1 ? cursor_ : game_;
            if (tx.prevLive) giveBackLocked(tx.prev, prevFd, d);
            else closeFd(prevFd);
            prevFd = -1;
            // Without commit callbacks (API 29, 30) the completion paces the layer.
            if (L.busy && !sc_.setOnCommit) {
                L.busy = false;
                if (L.pending.valid) { Frame f = L.pending; L.pending = Frame{}; submitFrameLocked(tx.layer, f, d); }
                else if (tx.layer == 1 && cursorDirty_) applyCursorLocked(d);
            }
        } else {
            closeFd(prevFd);
        }
        if (presentFd >= 0) {
            FenceWatch w{FenceWatch::Present};
            w.client = tx.client;
            w.frameId = tx.frameId;
            w.latchNs = done.latchNs;
            watchFenceLocked(presentFd, w);
            presentFd = -1;
        } else if (tx.client >= 0) {
            if (Client* c = clientLocked(tx.client)) {
                wl_direct_timing m{};
                wl_direct_hdr_init(&m.hdr, WL_DIRECT_TIMING, 0);
                m.frame_id = tx.frameId;
                m.latch_ns = done.latchNs > 0 ? (uint64_t)done.latchNs : 0;
                sendLocked(*c, &m.hdr, -1);
            }
        }
    }
    runDeferred(d);
}

void DirectServer::onFence(int fd) {
    Deferred d;
    uint64_t shownNs = 0;
    {
        std::lock_guard<std::mutex> lk(mu_);
        auto it = fences_.find(fd);
        if (it == fences_.end()) return;
        const FenceWatch w = it->second;
        fences_.erase(it);
        epoll_ctl(epollFd_, EPOLL_CTL_DEL, fd, nullptr);
        switch (w.kind) {
        case FenceWatch::ReleaseX: {
            AHardwareBuffer* ahb = w.ahb;
            held_.erase(ahb);
            d.push_back([this, ahb] { ctx_.directBufferReleased(ahb); AHardwareBuffer_release(ahb); });
            break;
        }
        case FenceWatch::ReleaseCursor:
            if (w.slot < kCursorSlots) cursorSlots_[w.slot].busy = false;
            if (cursorDirty_) applyCursorLocked(d);
            break;
        case FenceWatch::Present:
            shownNs = syncFileSignalTime(fd);
            if (w.client >= 0) {
                if (Client* c = clientLocked(w.client)) {
                    wl_direct_timing m{};
                    wl_direct_hdr_init(&m.hdr, WL_DIRECT_TIMING, 0);
                    m.frame_id = w.frameId;
                    m.latch_ns = w.latchNs > 0 ? (uint64_t)w.latchNs : 0;
                    m.present_ns = shownNs;
                    sendLocked(*c, &m.hdr, -1);
                }
            }
            break;
        }
        close(fd);
    }
    if (shownNs) {
        // Direct puts the frames on the display now: its present fences are the display clock.
        DisplayClock& clock = ctx_.displayClock();
        clock.setPeriod(ctx_.refreshPeriodNs());
        clock.noteShown(shownNs);
    }
    runDeferred(d);
}

void DirectServer::acceptClient() {
    for (;;) {
        const int fd = accept4(listenFd_, nullptr, nullptr, SOCK_CLOEXEC);
        if (fd < 0) return;
        Client c;
        c.fd = fd;
        {
            std::lock_guard<std::mutex> lk(mu_);
            if (clients_.size() >= kMaxClients) { DLOG_E("too many WL Direct clients"); close(fd); continue; }
            c.id = nextClient_++;
        }
        if (!handshake(c)) {
            for (AHardwareBuffer* a : c.images) AHardwareBuffer_release(a);
            close(fd);
            continue;
        }
        std::lock_guard<std::mutex> lk(mu_);
        const int id = c.id;
        clients_.emplace(id, std::move(c));
        Client& cl = clients_[id];
        wl_direct_welcome w{};
        wl_direct_hdr_init(&w.hdr, WL_DIRECT_WELCOME, 0);
        w.client_id = (uint32_t)id;
        w.version = WL_DIRECT_VERSION;
        w.granted = 0;
        w.reason = WL_REASON_SETTLING;
        sendLocked(cl, &w.hdr, -1);
        const int flags = fcntl(cl.fd, F_GETFL);
        fcntl(cl.fd, F_SETFL, flags | O_NONBLOCK);
        epoll_event ev{};
        ev.events = EPOLLIN | EPOLLRDHUP;
        ev.data.u64 = kTagClient | (uint64_t)(uint32_t)id;
        epoll_ctl(epollFd_, EPOLL_CTL_ADD, cl.fd, &ev);
        DLOG("client %d: window 0x%x, pid %u, %zu images %dx%d format %u", id, cl.xWindow, cl.pid,
             cl.images.size(), cl.w, cl.h, cl.format);
        ctx_.requestRender();   // the decision may hand it the grant
    }
}

// HELLO and the images, blocking with a timeout: the client sends them all at once.
bool DirectServer::handshake(Client& c) {
    timeval tv{2, 0};
    setsockopt(c.fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    setsockopt(c.fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
    alignas(8) unsigned char buf[WL_DIRECT_MAX_PACKET];
    int fd = -1;
    const ssize_t n = wl_direct_recv(c.fd, buf, &fd);
    closeFd(fd);
    const auto* h = reinterpret_cast<const wl_direct_hello*>(buf);
    if (n != (ssize_t)sizeof(wl_direct_hello) || h->hdr.type != WL_DIRECT_HELLO || h->magic != WL_DIRECT_MAGIC) {
        DLOG_E("client: bad HELLO (%zd)", n);
        return false;
    }
    if (h->version != WL_DIRECT_VERSION) {
        DLOG_E("client: protocol version %u, this server speaks %u", h->version, WL_DIRECT_VERSION);
        return false;
    }
    if (h->image_count == 0 || h->image_count > WL_DIRECT_MAX_IMAGES || !h->width || !h->height) {
        DLOG_E("client: bad swapchain (%u images %ux%u)", h->image_count, h->width, h->height);
        return false;
    }
    c.xWindow = h->x_window;
    c.pid = h->pid;
    c.w = (int32_t)h->width;
    c.h = (int32_t)h->height;
    c.format = h->ahb_format;
    for (uint32_t i = 0; i < h->image_count; i++) {
        AHardwareBuffer* ahb = nullptr;
        if (AHardwareBuffer_recvHandleFromUnixSocket(c.fd, &ahb) != 0 || !ahb) {
            DLOG_E("client: image %u did not arrive", i);
            return false;
        }
        c.images.push_back(ahb);
        AHardwareBuffer_Desc desc{};
        AHardwareBuffer_describe(ahb, &desc);
        if ((int32_t)desc.width != c.w || (int32_t)desc.height != c.h) {
            DLOG_E("client: image %u is %ux%u, not %dx%d", i, desc.width, desc.height, c.w, c.h);
            return false;
        }
    }
    return true;
}

void DirectServer::readClient(int id) {
    Deferred d;
    {
        std::lock_guard<std::mutex> lk(mu_);
        Client* c = clientLocked(id);
        if (!c) return;
        for (;;) {
            alignas(8) unsigned char buf[WL_DIRECT_MAX_PACKET];
            int fd = -1;
            const ssize_t n = wl_direct_recv(c->fd, buf, &fd);
            if (n == -EAGAIN || n == -EWOULDBLOCK) break;
            if (n <= 0) {
                if (n < 0) DLOG_E("client %d: %s", id, n == -EPROTO ? "malformed message" : strerror((int)-n));
                dropClientLocked(id, d);
                break;
            }
            const auto* h = reinterpret_cast<const wl_direct_hdr*>(buf);
            if (h->type == WL_DIRECT_BYE) { closeFd(fd); dropClientLocked(id, d); break; }
            if (h->type != WL_DIRECT_FRAME) {
                closeFd(fd);
                DLOG_E("client %d: unexpected message %u", id, h->type);
                dropClientLocked(id, d);
                break;
            }
            const auto* m = reinterpret_cast<const wl_direct_frame*>(buf);
            if (m->image_index >= c->images.size() || ((m->flags & WL_FRAME_HAS_FENCE) != 0) != (fd >= 0)) {
                closeFd(fd);
                DLOG_E("client %d: bad FRAME", id);
                dropClientLocked(id, d);
                break;
            }
            Frame f;
            f.buf.kind = BufKind::Wl;
            f.buf.ahb = c->images[m->image_index];
            f.buf.client = id;
            f.buf.index = m->image_index;
            f.fence = fd;
            f.w = c->w; f.h = c->h;
            f.frameId = m->frame_id;
            f.desiredNs = m->desired_present_ns;
            f.valid = true;
            const bool ours = c->granted && client_ == id && route_ == Route::DirectWl &&
                              (phase_ == Phase::Wait || phase_ == Phase::Direct);
            if (!ours) { dropFrameLocked(f, d); continue; }
            if (fx_) {
                // For the Fx pass on the render thread; the one it did not take yet is replaced.
                if (c->fxFrame.valid) dropFrameLocked(c->fxFrame, d);
                c->fxFrame = f;
                d.push_back([this] { ctx_.requestRender(); });
            } else {
                applyFrameLocked(0, f, d);
                if (phase_ == Phase::Wait) {
                    phase_ = Phase::Direct;
                    statusSink_ = 1;
                    statusSource_ = 2;
                    statusReason_ = WL_REASON_OK;
                    DLOG("window 0x%llx: Direct from WL client %d", (unsigned long long)window_id_, id);
                }
            }
        }
    }
    runDeferred(d);
}

void DirectServer::dropClientLocked(int id, Deferred& d) {
    auto it = clients_.find(id);
    if (it == clients_.end()) return;
    Client& c = it->second;
    if (c.fxFrame.valid) { closeFd(c.fxFrame.fence); c.fxFrame = Frame{}; }
    if (client_ == id) {
        if (phase_ == Phase::Direct) {
            leaveLocked(nowNs(), d);
            leaveSince_ = 0;   // nothing will come through X from it: no waiting
        } else if (phase_ == Phase::Wait) {
            phase_ = Phase::X;
            route_ = Route::X;
            watchWindow_ = 0;
        }
        client_ = -1;
    }
    if (game_.pending.valid && game_.pending.buf.kind == BufKind::Wl && game_.pending.buf.client == id) {
        closeFd(game_.pending.fence);
        game_.pending = Frame{};
    }
    if (epollFd_ >= 0 && c.fd >= 0) epoll_ctl(epollFd_, EPOLL_CTL_DEL, c.fd, nullptr);
    closeFd(c.fd);
    // SurfaceFlinger keeps its own references to what it shows; the renderer drops its Vulkan
    // imports of the images once the GPU is done with them.
    std::vector<AHardwareBuffer*> images = std::move(c.images);
    d.push_back([this, images] {
        ctx_.directClientGone(images);
        for (AHardwareBuffer* a : images) AHardwareBuffer_release(a);
    });
    DLOG("client %d gone", id);
    clients_.erase(it);
}
