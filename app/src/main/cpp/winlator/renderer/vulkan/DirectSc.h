#pragma once
// SurfaceControl / SurfaceTransaction (NDK, libandroid) resolved at run time: the app's minSdk is
// 28, the API is 29+, and parts of it are 31+. Own declarations, no <android/surface_control.h>
// (whose prototypes are __INTRODUCED_IN-guarded). The C++ references of the NDK prototypes
// (`const ARect&`) are passed as pointers here: the same ABI.
#include <android/hardware_buffer.h>
#include <android/native_window.h>
#include <android/rect.h>
#include <dlfcn.h>
#include <cstdint>
#include <cstdlib>
#include <sys/system_properties.h>
#include <type_traits>

struct ASurfaceControl;
struct ASurfaceTransaction;
struct ASurfaceTransactionStats;

class DirectSc {
public:
    using OnComplete = void (*)(void* context, ASurfaceTransactionStats* stats);
    using OnCommit   = void (*)(void* context, ASurfaceTransactionStats* stats);

    static constexpr int8_t  kVisibilityHide   = 0;
    static constexpr int8_t  kVisibilityShow   = 1;
    static constexpr int8_t  kTransparencyOpaque = 2;
    static constexpr int8_t  kTransparencyTranslucent = 1;
    static constexpr int32_t kDataspaceSrgb    = 142671872;   // ADATASPACE_SRGB

    // API 29
    ASurfaceControl* (*createFromWindow)(ANativeWindow* parent, const char* name) = nullptr;
    ASurfaceControl* (*create)(ASurfaceControl* parent, const char* name) = nullptr;
    void (*release)(ASurfaceControl*) = nullptr;
    ASurfaceTransaction* (*txCreate)() = nullptr;
    void (*txDelete)(ASurfaceTransaction*) = nullptr;
    void (*txApply)(ASurfaceTransaction*) = nullptr;
    void (*setOnComplete)(ASurfaceTransaction*, void* context, OnComplete) = nullptr;
    void (*reparent)(ASurfaceTransaction*, ASurfaceControl*, ASurfaceControl* parent) = nullptr;
    void (*setVisibility)(ASurfaceTransaction*, ASurfaceControl*, int8_t) = nullptr;
    void (*setZOrder)(ASurfaceTransaction*, ASurfaceControl*, int32_t) = nullptr;
    void (*setBuffer)(ASurfaceTransaction*, ASurfaceControl*, AHardwareBuffer*, int acquireFenceFd) = nullptr;
    void (*setColor)(ASurfaceTransaction*, ASurfaceControl*, float r, float g, float b, float alpha, int32_t dataspace) = nullptr;
    void (*setGeometry)(ASurfaceTransaction*, ASurfaceControl*, const ARect* source, const ARect* destination, int32_t transform) = nullptr;
    void (*setBufferTransparency)(ASurfaceTransaction*, ASurfaceControl*, int8_t) = nullptr;
    void (*setBufferDataspace)(ASurfaceTransaction*, ASurfaceControl*, int32_t) = nullptr;
    void (*setDesiredPresentTime)(ASurfaceTransaction*, int64_t) = nullptr;
    int64_t (*statsLatchTime)(ASurfaceTransactionStats*) = nullptr;
    int (*statsPresentFenceFd)(ASurfaceTransactionStats*) = nullptr;
    int (*statsPreviousReleaseFenceFd)(ASurfaceTransactionStats*, ASurfaceControl*) = nullptr;
    // API 31 (null below)
    void (*setOnCommit)(ASurfaceTransaction*, void* context, OnCommit) = nullptr;
    void (*setCrop)(ASurfaceTransaction*, ASurfaceControl*, const ARect* crop) = nullptr;
    void (*setPosition)(ASurfaceTransaction*, ASurfaceControl*, int32_t x, int32_t y) = nullptr;
    void (*setScale)(ASurfaceTransaction*, ASurfaceControl*, float xScale, float yScale) = nullptr;

    int  api = 0;
    bool ok  = false;   // everything of API 29 is there, and a way to place a buffer

    static int deviceApi() {
        char v[PROP_VALUE_MAX] = {};
        return __system_property_get("ro.build.version.sdk", v) > 0 ? atoi(v) : 0;
    }

    bool load() {
        api = deviceApi();
        if (api < 29) return ok = false;
        void* lib = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
        if (!lib) return ok = false;
        auto sym = [lib](auto& fn, const char* name) {
            fn = reinterpret_cast<std::remove_reference_t<decltype(fn)>>(dlsym(lib, name));
            return fn != nullptr;
        };
        bool base = true;
        base &= sym(createFromWindow, "ASurfaceControl_createFromWindow");
        base &= sym(create, "ASurfaceControl_create");
        base &= sym(release, "ASurfaceControl_release");
        base &= sym(txCreate, "ASurfaceTransaction_create");
        base &= sym(txDelete, "ASurfaceTransaction_delete");
        base &= sym(txApply, "ASurfaceTransaction_apply");
        base &= sym(setOnComplete, "ASurfaceTransaction_setOnComplete");
        base &= sym(reparent, "ASurfaceTransaction_reparent");
        base &= sym(setVisibility, "ASurfaceTransaction_setVisibility");
        base &= sym(setZOrder, "ASurfaceTransaction_setZOrder");
        base &= sym(setBuffer, "ASurfaceTransaction_setBuffer");
        base &= sym(setColor, "ASurfaceTransaction_setColor");
        base &= sym(setBufferTransparency, "ASurfaceTransaction_setBufferTransparency");
        base &= sym(statsLatchTime, "ASurfaceTransactionStats_getLatchTime");
        base &= sym(statsPresentFenceFd, "ASurfaceTransactionStats_getPresentFenceFd");
        base &= sym(statsPreviousReleaseFenceFd, "ASurfaceTransactionStats_getPreviousReleaseFenceFd");
        sym(setGeometry, "ASurfaceTransaction_setGeometry");
        sym(setBufferDataspace, "ASurfaceTransaction_setBufferDataspace");
        sym(setDesiredPresentTime, "ASurfaceTransaction_setDesiredPresentTime");
        if (api >= 31) {
            sym(setOnCommit, "ASurfaceTransaction_setOnCommit");
            sym(setCrop, "ASurfaceTransaction_setCrop");
            sym(setPosition, "ASurfaceTransaction_setPosition");
            sym(setScale, "ASurfaceTransaction_setScale");
        }
        const bool place = setGeometry || (setCrop && setPosition && setScale);
        return ok = base && place;
    }

    // Shows `source` of the layer's buffer at `destination` (surface pixels): setGeometry, or its
    // API 31 replacement. A destination of zero size hides nothing but places nothing either.
    void place(ASurfaceTransaction* t, ASurfaceControl* sc, const ARect& source, const ARect& destination) const {
        if (setGeometry) {
            setGeometry(t, sc, &source, &destination, 0);
            return;
        }
        const int sw = source.right - source.left, sh = source.bottom - source.top;
        const int dw = destination.right - destination.left, dh = destination.bottom - destination.top;
        setCrop(t, sc, &source);
        setPosition(t, sc, destination.left, destination.top);
        setScale(t, sc, sw > 0 ? (float)dw / (float)sw : 1.f, sh > 0 ? (float)dh / (float)sh : 1.f);
    }
};
