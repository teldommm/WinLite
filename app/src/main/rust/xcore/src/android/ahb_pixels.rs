//! Pixel memory of the Rust server's drawables: one AHardwareBuffer each, locked only while
//! a pixel operation runs.

use super::ahb::{self, AHardwareBuffer, Desc, Locked};
use crate::log;
use crate::pixels::Surface;
use crate::proto::gfx::PixelStore;
use std::ptr;

pub struct AhbPixels {
    ahb: *mut AHardwareBuffer,
    /// Row pitch in pixels, as the allocator chose it.
    stride: i32,
}

// SAFETY: the buffer handle is thread-agnostic; the server lock serialises the pixel access.
unsafe impl Send for AhbPixels {}

impl AhbPixels {
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
        Some(AhbPixels { ahb: buffer, stride })
    }
}

impl AhbPixels {
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
        Some(AhbPixels { ahb, stride: desc.stride as i32 })
    }
}

impl PixelStore for AhbPixels {
    fn stride(&self) -> i32 {
        self.stride
    }

    fn with_surface(&self, f: &mut dyn FnMut(&Surface)) -> bool {
        let Some(l) = Locked::new(self.ahb as usize as i64, ahb::USAGE_CPU_READ_OFTEN | ahb::USAGE_CPU_WRITE_OFTEN) else {
            return false;
        };
        let Some(surface) = l.surface(self.stride as i16) else { return false };
        f(&surface);
        true
    }

    fn native_handle(&self) -> i64 {
        self.ahb as usize as i64
    }
}

impl Drop for AhbPixels {
    fn drop(&mut self) {
        unsafe { ahb::AHardwareBuffer_release(self.ahb) };
    }
}
