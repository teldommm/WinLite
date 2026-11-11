//! The renderer end of [`crate::frames`] on Android: `libvulkan_renderer.so` hands over a table of
//! C functions and its context (`XCore.setRenderer`, from `VulkanXServerView`), and frames go
//! straight into it from the server's frame thread (`xcore-frames`). That thread, not the I/O
//! thread, is the one that waits when the renderer holds its lock for long (the GPU wait when the
//! cursor changes size, `DeviceWaitIdle` on a filter switch, the import of a new buffer).
//!
//! What `VulkanXServerView` did per frame in Java now happens here: point the window at the
//! buffer (`updateWindowContentAHB`), remember which buffer each drawable last handed over so it
//! can be given back when the server drops the drawable (`importedAhb` + `onDrawableRemoved`),
//! and pass the renderer's display clock on to Present (`pushDisplayClockLocked`).
//!
//! One renderer per process (there is one X server view), so the output is a process-wide object
//! that every server instance shares.

use super::ahb::{self, AHardwareBuffer};
use super::platform;
use crate::frames::{Item, Output};
use crate::log;
use crate::proto::Server;
use std::collections::HashMap;
use std::os::raw::c_void;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, OnceLock};

/// Layout shared with `vulkan_jni.cpp` (`XcoreRendererApi`); `version` first, so a mismatch is
/// noticed before any other field is read.
#[repr(C)]
pub struct RendererApi {
    pub version: u32,
    /// Show `ahb` as the content of window `id`; the renderer takes a reference of its own.
    pub update_content: unsafe extern "C" fn(ctx: *mut c_void, id: i64, ahb: *mut AHardwareBuffer),
    /// The buffer is no longer the server's: forget the renderer's import of it once unused.
    pub release_buffer: unsafe extern "C" fn(ctx: *mut c_void, ahb: *mut AHardwareBuffer),
    /// The display clock (`[period_ns, phase_error_ns, sequence]`); 0 when there is none yet.
    pub display_clock: unsafe extern "C" fn(ctx: *mut c_void, out: *mut i64) -> i32,
}

pub const RENDERER_API_VERSION: u32 = 1;

// The C side checks the same layout (vulkan_jni.cpp builds the table): version, then pointers.
const _: () = assert!(std::mem::size_of::<RendererApi>() == 32);
const _: () = assert!(std::mem::offset_of!(RendererApi, update_content) == 8);
const _: () = assert!(std::mem::offset_of!(RendererApi, display_clock) == 24);

struct Renderer {
    api: &'static RendererApi,
    ctx: *mut c_void,
}

// SAFETY: the renderer's entry points lock what they touch (renderMutex) and are called from any
// thread; the context is only used under `Inner`'s lock, and `set_renderer(null)` takes that lock
// before the Java side destroys the context.
unsafe impl Send for Renderer {}

struct Inner {
    renderer: Option<Renderer>,
    /// The buffer each drawable last handed to the renderer (a pointer value, no reference).
    shown: HashMap<u32, usize>,
    /// Sequence of the last display clock passed on; -1: none (Present paces without it).
    clock_seq: i64,
}

pub struct AndroidOutput {
    active: AtomicBool,
    inner: Mutex<Inner>,
}

fn lock(m: &Mutex<Inner>) -> MutexGuard<'_, Inner> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

/// The process-wide output.
pub fn output() -> Arc<AndroidOutput> {
    static OUT: OnceLock<Arc<AndroidOutput>> = OnceLock::new();
    OUT.get_or_init(|| {
        Arc::new(AndroidOutput {
            active: AtomicBool::new(false),
            inner: Mutex::new(Inner { renderer: None, shown: HashMap::new(), clock_seq: -1 }),
        })
    })
    .clone()
}

impl AndroidOutput {
    /// Installs the renderer (`api` null: none). Returns once no call into the previous one is in
    /// progress, so the caller may destroy it right after.
    pub fn set_renderer(&self, api: *const RendererApi, ctx: *mut c_void) {
        let renderer = if api.is_null() || ctx.is_null() {
            None
        } else {
            // SAFETY: a pointer to the renderer library's static table, which is never unloaded.
            let api: &'static RendererApi = unsafe { &*api };
            if api.version != RENDERER_API_VERSION {
                log::error(&format!("renderer API version {} (expected {RENDERER_API_VERSION}): frames stay off", api.version));
                None
            } else {
                Some(Renderer { api, ctx })
            }
        };
        let mut g = lock(&self.inner);
        // A different renderer has imported nothing of what the last one was handed.
        g.shown.clear();
        if g.clock_seq != -1 {
            g.clock_seq = -1;
            platform::set_display_clock(0, 0, 0);
        }
        self.active.store(renderer.is_some(), Ordering::Release);
        g.renderer = renderer;
    }

    /// A new server, or none: its drawables are not the ones `shown` knows about.
    pub fn forget_drawables(&self) {
        lock(&self.inner).shown.clear();
    }
}

impl Output for AndroidOutput {
    fn active(&self) -> bool {
        self.active.load(Ordering::Acquire)
    }

    fn acquire(&self, server: &Server, drawable: u32) -> usize {
        let Some(d) = server.drawables.get(&drawable) else { return 0 };
        let h = d.store.native_handle() as usize;
        if h != 0 {
            // Under the server lock: the drawable cannot be freed between the lookup and here.
            unsafe { ahb::AHardwareBuffer_acquire(h as *mut AHardwareBuffer) };
        }
        h
    }

    fn deliver(&self, items: Vec<Item>) {
        let mut g = lock(&self.inner);
        let Inner { renderer, shown, clock_seq } = &mut *g;
        let mut showed = false;
        for item in items {
            match item {
                Item::Show { window, drawable, buf } => {
                    if let Some(r) = renderer.as_ref() {
                        unsafe { (r.api.update_content)(r.ctx, window as i64, buf as *mut AHardwareBuffer) };
                        shown.insert(drawable, buf);
                        showed = true;
                    }
                    // The renderer holds its own reference now (or there is none): ours goes back.
                    unsafe { ahb::AHardwareBuffer_release(buf as *mut AHardwareBuffer) };
                }
                Item::Removed(drawable) => {
                    if let (Some(buf), Some(r)) = (shown.remove(&drawable), renderer.as_ref()) {
                        unsafe { (r.api.release_buffer)(r.ctx, buf as *mut AHardwareBuffer) };
                    }
                }
            }
        }
        // Present paces the game by the renderer's clock: pass on each new measurement.
        if let (true, Some(r)) = (showed, renderer.as_ref()) {
            let mut clock = [0i64; 3];
            if unsafe { (r.api.display_clock)(r.ctx, clock.as_mut_ptr()) } != 0 {
                if clock[2] != *clock_seq {
                    *clock_seq = clock[2];
                    platform::set_display_clock(clock[0], clock[1], clock[2]);
                }
            } else if *clock_seq != -1 {
                *clock_seq = -1;
                platform::set_display_clock(0, 0, 0);
            }
        }
    }
}
