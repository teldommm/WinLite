//! Pixel memory of the Rust server's drawables: one AHardwareBuffer each. A buffer is locked for
//! the CPU when a pixel operation first needs it and stays locked until the server lock is let go
//! (`proto::gfx::release_held`, called by the runtime): a burst of small requests (Wine's GDI)
//! locks each buffer once instead of once per operation. Unlocking at that point is what flushes
//! the CPU's writes, so nobody else (the renderer, a DRI3 client) sees a half-written buffer
//! state that the server could still change.

use super::ahb::{self, AHardwareBuffer, Desc, Locked};
use crate::log;
use crate::pixels::Surface;
use crate::proto::gfx::{self, Held, PixelStore};
use std::cell::UnsafeCell;
use std::ptr;
use std::sync::Arc;

struct Inner {
    ahb: *mut AHardwareBuffer,
    /// Row pitch in pixels, as the allocator chose it.
    stride: i32,
    /// The CPU mapping while held. Touched only with the server lock held.
    lock: UnsafeCell<Option<Locked>>,
}

// SAFETY: the buffer handle is thread-agnostic, and `lock` is only used with the server lock
// held, which serialises every access (the pixel operations and `release_held`).
unsafe impl Send for Inner {}
unsafe impl Sync for Inner {}

impl Held for Inner {
    fn release(&self) {
        // SAFETY: see the `Sync` impl; dropping the guard unlocks the buffer.
        unsafe { *self.lock.get() = None };
    }
}

impl Drop for Inner {
    fn drop(&mut self) {
        // Unlock before the last reference goes.
        self.lock.get_mut().take();
        unsafe { ahb::AHardwareBuffer_release(self.ahb) };
    }
}

pub struct AhbPixels {
    inner: Arc<Inner>,
}

impl AhbPixels {
    fn wrap(ahb: *mut AHardwareBuffer, stride: i32) -> AhbPixels {
        AhbPixels { inner: Arc::new(Inner { ahb, stride, lock: UnsafeCell::new(None) }) }
    }

    /// A zeroed buffer of `hal_format` (1 = RGBA, 5 = BGRA). `None` when it cannot be allocated
    /// or cleared (a buffer that could not be cleared would show garbage).
    pub fn new(width: i16, height: i16, hal_format: u32) -> Option<AhbPixels> {
        if width <= 0 || height <= 0 {
            return None;
        }
        let desc = Desc {
            width: width as u32,
            height: height as u32,
            layers: 1,
            format: hal_format,
            usage: ahb::USAGE_CPU_WRITE_OFTEN | ahb::USAGE_CPU_READ_OFTEN | ahb::USAGE_GPU_SAMPLED_IMAGE,
            ..Default::default()
        };
        let mut buffer: *mut AHardwareBuffer = ptr::null_mut();
        if unsafe { ahb::AHardwareBuffer_allocate(&desc, &mut buffer) } != 0 || buffer.is_null() {
            log::warn("failed to allocate a drawable buffer");
            return None;
        }
        let Some(l) = Locked::new(buffer as usize as i64, ahb::USAGE_CPU_WRITE_OFTEN) else {
            log::warn("failed to lock a new drawable buffer");
            unsafe { ahb::AHardwareBuffer_release(buffer) };
            return None;
        };
        let stride = l.desc.stride as i32;
        unsafe { ptr::write_bytes(l.base(), 0, stride as usize * l.desc.height as usize * 4) };
        drop(l);
        Some(AhbPixels::wrap(buffer, stride))
    }

    /// Takes over one reference of a buffer another process allocated (DRI3). The row pitch is
    /// the allocator's, read from the buffer itself.
    pub fn from_handle(ahb: *mut AHardwareBuffer) -> Option<AhbPixels> {
        if ahb.is_null() {
            return None;
        }
        let mut desc = ahb::Desc::default();
        unsafe { ahb::AHardwareBuffer_describe(ahb, &mut desc) };
        if desc.stride == 0 {
            unsafe { ahb::AHardwareBuffer_release(ahb) };
            return None;
        }
        Some(AhbPixels::wrap(ahb, desc.stride as i32))
    }
}

impl PixelStore for AhbPixels {
    fn stride(&self) -> i32 {
        self.inner.stride
    }

    fn with_surface(&self, f: &mut dyn FnMut(&Surface)) -> bool {
        let inner = &*self.inner;
        // SAFETY: server lock held (see `Inner`). No reference into the slot outlives this block:
        // `f` may run other pixel operations, and they must find the slot untouched.
        let surface = unsafe {
            let slot = inner.lock.get();
            if (*slot).is_none() {
                let usage = ahb::USAGE_CPU_READ_OFTEN | ahb::USAGE_CPU_WRITE_OFTEN;
                let Some(l) = Locked::new(inner.ahb as usize as i64, usage) else { return false };
                *slot = Some(l);
                gfx::hold(self.inner.clone());
            }
            match (*slot).as_ref().and_then(|l| l.surface(inner.stride as i16)) {
                Some(s) => s,
                None => return false,
            }
        };
        f(&surface);
        true
    }

    fn native_handle(&self) -> i64 {
        self.inner.ahb as usize as i64
    }
}
