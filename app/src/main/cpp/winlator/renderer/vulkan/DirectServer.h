#pragma once
// Direct output: the game's frames go straight to SurfaceFlinger layers (children of the
// renderer's SurfaceView surface) instead of through the Vulkan composite.
//
//   Direct zero-copy  the game's own buffer is the layer's buffer; HWC (or SF's GPU
//                     composition) scales it into place
//   Direct + FX       Fx draws the game's buffer into a buffer of the panel rectangle (render
//                     thread, VulkanRendererContext::runDirectFx); that one goes to the layer
//   X composite       everything else: windows over the game, windowed games, the desktop
//
// Sources of the game's frames:
//   WL Direct  the Vulkan wrapper's WL swapchain over the socket (wl_proto.h), when it holds
//              the grant: the X server sees no PresentPixmap of the window meanwhile
//   X          the window's buffers as xcore hands them to the renderer (any DRI3 client that
//              presents alternating GPU buffers)
//
// Who decides: the render thread, every frame (plan()), from the candidate the composite's
// window tree yields; on with a hysteresis, off at once. The switches are seamless: on, the
// layers show with the first Direct buffer (the composite stops presenting after it); off, the
// composite takes over and the layers go once a composite frame with the game's X content has
// reached the display.
//
// SurfaceFlinger side: one buffer transaction in flight per layer (the next waits for the
// previous one's commit, or its completion before API 31), so no buffer is ever dropped and
// every buffer comes back through exactly one release: the previous-release fence of the
// transaction that replaced it.
//
// Threads and locks: mu_ guards the state. The render thread calls in with renderMutex free or
// held (holds() is called under it); the server thread (socket, fences, transaction callbacks)
// never takes renderMutex with mu_ held: what it hands to the renderer runs after mu_ is let
// go (Deferred). Transaction callbacks come on a binder thread and only queue.
#include "DirectSc.h"
#include "../../wl_proto.h"

#include <atomic>
#include <cstdint>
#include <deque>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <vector>

class VulkanRendererContext;

class DirectServer {
public:
    struct Rect {
        int32_t x = 0, y = 0, w = 0, h = 0;
        bool operator==(const Rect& o) const { return x == o.x && y == o.y && w == o.w && h == o.h; }
        bool operator!=(const Rect& o) const { return !(*this == o); }
    };

    // What the composite's window tree says, every frame (render thread, VulkanRendererContext::
    // directCandidateLocked).
    struct Candidate {
        int      reason = WL_REASON_NO_WINDOW;   // WL_REASON_OK when Direct may show it
        int64_t  window = 0;                     // the X window (the WL client's x_window)
        Rect     dst;                            // where the game lands on the surface (px)
        bool     needsFx = false;                // an effect is on
        bool     fxPossible = false;             // sync files: Direct + FX can work
        // The window's content from xcore.
        AHardwareBuffer* xBuffer = nullptr;
        int32_t  xW = 0, xH = 0;
        bool     xSwapped = false;               // alternating GPU buffers: may go direct
        // Content generation of watchWindow() (seamless switch back to the composite).
        uint64_t watchGen = 0;
    };

    enum class Route { X, DirectX, DirectWl };
    struct Plan {
        bool  composite = true;   // the composite draws and presents this frame
        Route route = Route::X;   // who feeds the game layer
        bool  fx = false;         // through the Fx pass
        uint64_t wakeAtNs = 0;    // render again at this time (hysteresis, leaving), 0 = no
    };

    // A WL Direct frame for the Fx pass (render thread).
    struct WlFrame {
        int      client = -1;
        uint32_t image = 0;
        AHardwareBuffer* ahb = nullptr;
        int      acquireFd = -1;   // owned by the taker
        int32_t  w = 0, h = 0;
        uint64_t frameId = 0;
        uint64_t desiredNs = 0;
    };

    // For the HUD.
    struct Status {
        int sink = 0;     // 0 X composite, 1 Direct, 2 Direct + FX
        int reason = WL_REASON_OFF;
        int source = 0;   // 0 none, 1 X, 2 WL Direct
    };

    explicit DirectServer(VulkanRendererContext& ctx);
    ~DirectServer();

    bool available() const { return sc_.ok; }

    // ---- surface (JNI / render thread with the frame lock held exclusively)
    void attach(ANativeWindow* window);
    void detach();   // back to X at once: the layers go, every buffer is given back

    // ---- settings
    void setMode(int mode);   // WL_OUTPUT_*
    int  mode() const { return mode_.load(); }
    // Starts serving WL Direct clients on `path` (once; a new path restarts).
    void listen(const std::string& path);

    // ---- render thread
    int64_t watchWindow() const { return watchWindow_.load(); }
    bool hasClient(int64_t xWindow) const;
    Plan plan(const Candidate& c, uint64_t nowNs);
    // Direct zero-copy from X: the game window's buffer (call when it changed).
    void pushX(AHardwareBuffer* ahb, int32_t w, int32_t h);
    // Direct + FX: ring buffer `slot` of generation `gen` holds the frame, `fenceFd` (owned)
    // signals when Fx is done with it.
    void pushFx(uint32_t gen, uint32_t slot, AHardwareBuffer* ahb, int32_t w, int32_t h, int fenceFd);
    // The next WL Direct frame for the Fx pass; false if none is waiting.
    bool takeWlFrame(WlFrame& out);
    // The Fx pass is done with a WL frame's image once `fenceFd` (owned, -1 = now) signals.
    void releaseWlFrame(int client, uint32_t image, int fenceFd);
    // The cursor layer: shown or not, where (surface px), and its image when `serial` is new
    // (R,G,B,A straight alpha, w x h). Only while the composite does not present.
    void updateCursor(bool visible, const Rect& dst, const uint32_t* rgba, int32_t w, int32_t h, uint64_t serial);
    // The composite presented (sequence presentSeq); the display has shown up to shownSeq
    // (0 = no display timing).
    void compositePresented(uint64_t presentSeq, uint64_t shownSeq, uint64_t nowNs);
    // Direct shows, or may still show, this buffer of xcore's (renderMutex may be held).
    bool holds(AHardwareBuffer* ahb) const;
    Status status() const;

    // ---- transaction callbacks (binder thread)
    struct TxDone { uint64_t id; int64_t latchNs; int presentFd; int prevReleaseFd; bool committed; };
    void postTxDone(const TxDone& d);

private:
    // What a layer shows, and how it comes back.
    enum class BufKind : uint8_t { None, X, Wl, Ring, Cursor };
    struct Buf {
        BufKind kind = BufKind::None;
        AHardwareBuffer* ahb = nullptr;
        int      client = -1;     // Wl
        uint32_t index = 0;       // Wl image, Ring / Cursor slot
        uint32_t gen = 0;         // Ring
        bool operator==(const Buf& o) const { return kind == o.kind && ahb == o.ahb && client == o.client && index == o.index && gen == o.gen; }
    };
    struct Frame {
        Buf      buf;
        int      fence = -1;      // acquire, owned
        int32_t  w = 0, h = 0;
        uint64_t frameId = 0;     // Wl
        uint64_t desiredNs = 0;
        bool     valid = false;
    };
    struct Layer {
        ASurfaceControl* sc = nullptr;
        bool  busy = false;       // a buffer transaction waits for its commit / completion
        Frame pending;            // the newest frame behind it
        Buf   attached;           // the buffer SF has on the layer
        bool  attachedLive = false;   // ... and it has not been given back yet
        int32_t w = 0, h = 0;     // its size
        Rect  placed;             // where it was put
    };
    struct Tx {
        uint64_t id = 0;
        int      layer = 0;       // 0 game, 1 cursor, -1 no buffer
        Buf      prev;            // what the transaction's buffer replaced
        bool     prevLive = false;
        bool     hide = false;    // the container goes: everything attached comes back
        int      client = -1;     // TIMING goes to
        uint64_t frameId = 0;
    };
    struct Client {
        int      fd = -1;
        int      id = 0;
        uint32_t seq = 0;
        uint32_t xWindow = 0;
        uint32_t pid = 0;
        int32_t  w = 0, h = 0;
        uint32_t format = 0;
        std::vector<AHardwareBuffer*> images;
        bool     granted = false;
        int      reason = WL_REASON_SETTLING;
        Frame    fxFrame;         // waiting for the Fx pass
    };
    struct FenceWatch {
        enum Kind { ReleaseX, ReleaseCursor, Present } kind;
        AHardwareBuffer* ahb = nullptr;   // ReleaseX
        uint32_t slot = 0;                // ReleaseCursor
        int      client = -1;             // Present: TIMING
        uint64_t frameId = 0;
        int64_t  latchNs = 0;
    };
    enum class Phase { X, Wait, Direct, Leaving };

    // Work for after mu_ is released (calls into the renderer).
    using Deferred = std::vector<std::function<void()>>;
    void runDeferred(Deferred& d);

    // under mu_
    void applyFrameLocked(int layer, Frame f, Deferred& d);
    void submitFrameLocked(int layer, Frame f, Deferred& d);
    void dropFrameLocked(Frame& f, Deferred& d);
    void giveBackLocked(const Buf& b, int fenceFd, Deferred& d);
    void hideLocked(Deferred& d);
    void showLocked(ASurfaceTransaction* t);
    void setGrantLocked(Client& c, bool granted, int reason);
    void leaveLocked(uint64_t nowNs, Deferred& d);
    void finishLeavingLocked(Deferred& d);
    void writeCursorLocked(uint32_t slot, const uint32_t* rgba, int32_t w, int32_t h);
    void applyCursorLocked(Deferred& d);
    Client* clientLocked(int id);
    void dropClientLocked(int id, Deferred& d);
    void watchFenceLocked(int fd, const FenceWatch& w);
    void sendLocked(Client& c, struct wl_direct_hdr* h, int fd);
    uint64_t newTxLocked(const Tx& tx, ASurfaceControl* sc, ASurfaceTransaction* t);
    void createLayersLocked();
    void destroyLayersLocked(Deferred& d);

    // server thread
    void threadMain();
    void acceptClient();
    void readClient(int id);
    bool handshake(Client& c);
    void onTxDone(const TxDone& d);
    void onFence(int fd);
    void wakeThread();

    VulkanRendererContext& ctx_;
    DirectSc sc_;

    mutable std::mutex mu_;
    ANativeWindow* window_ = nullptr;
    ASurfaceControl* container_ = nullptr;
    ASurfaceControl* backdrop_ = nullptr;
    Layer game_;
    Layer cursor_;
    bool  shown_ = false;     // the container is visible

    std::atomic<int> mode_{WL_OUTPUT_AUTO};
    Phase  phase_ = Phase::X;
    Route  route_ = Route::X;
    bool   fx_ = false;
    int64_t window_id_ = 0;    // the game window Direct shows
    int    client_ = -1;       // the WL client that holds the grant
    Rect   dst_;
    int    reason_ = WL_REASON_OFF;
    uint64_t stableSince_ = 0;
    int64_t  stableWindow_ = 0;
    // Leaving: the composite takes over once it shows the window's X content (watchGen past
    // leaveGen) and that frame has reached the display.
    std::atomic<int64_t> watchWindow_{0};
    uint64_t leaveGen_ = 0;
    uint64_t leaveSince_ = 0;
    uint64_t lastWatchGen_ = 0;
    uint64_t revealSeq_ = 0;
    uint64_t revealAt_ = 0;
    bool     hiding_ = false;  // the hide transaction is out

    // X buffers SF has or may still read (a reference each).
    std::unordered_set<AHardwareBuffer*> held_;

    // Cursor ring: CPU-written RGBA buffers.
    static constexpr uint32_t kCursorSlots = 3;
    struct CursorSlot { AHardwareBuffer* ahb = nullptr; int32_t w = 0, h = 0; bool busy = false; };
    CursorSlot cursorSlots_[kCursorSlots];
    bool     cursorVisible_ = false;
    bool     cursorVisibleApplied_ = false;
    Rect     cursorDst_;
    Rect     cursorDstApplied_;
    uint64_t cursorSerial_ = 0;      // image the newest write holds
    uint64_t cursorSerialApplied_ = ~0ull;
    std::vector<uint32_t> cursorPixels_;
    int32_t  cursorW_ = 0, cursorH_ = 0;
    bool     cursorDirty_ = false;   // an image waits for a free slot

    // Clients.
    std::unordered_map<int, Client> clients_;
    int nextClient_ = 1;

    // Fences the server thread waits for (fd -> what it means).
    std::unordered_map<int, FenceWatch> fences_;

    // Transactions out (id -> record).
    std::unordered_map<uint64_t, Tx> txs_;

    // Server thread.
    std::string path_;
    int listenFd_ = -1;
    int epollFd_ = -1;
    int eventFd_ = -1;
    std::thread thread_;
    std::atomic<bool> running_{false};

    // Transaction callbacks waiting for the server thread.
    std::mutex cbMu_;
    std::vector<TxDone> cbQueue_;

    std::atomic<int> statusSink_{0}, statusReason_{WL_REASON_OFF}, statusSource_{0};

    // Last logged admission state (under mu_): makes a persistent NOT_TOP diagnosable.
    int64_t debugCandidate_ = -1;
    int debugReason_ = -1, debugMode_ = -1;
};
