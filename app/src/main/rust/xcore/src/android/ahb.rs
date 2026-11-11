//! AHardwareBuffer FFI (libandroid) and a lock guard.

use crate::pixels::Surface;
use std::os::raw::{c_int, c_void};
use std::ptr;

#[repr(C)]
pub struct AHardwareBuffer {
    _private: [u8; 0],
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct Desc {
    pub width: u32,
    pub height: u32,
    pub layers: u32,
    pub format: u32,
    pub usage: u64,
    pub stride: u32,
    pub rfu0: u32,
    pub rfu1: u64,
}

extern "C" {
    pub fn AHardwareBuffer_allocate(desc: *const Desc, out: *mut *mut AHardwareBuffer) -> c_int;
    pub fn AHardwareBuffer_release(buffer: *mut AHardwareBuffer);
    pub fn AHardwareBuffer_acquire(buffer: *mut AHardwareBuffer);
    pub fn AHardwareBuffer_describe(buffer: *const AHardwareBuffer, out: *mut Desc);
    pub fn AHardwareBuffer_lock(
        buffer: *mut AHardwareBuffer,
        usage: u64,
        fence: i32,
        rect: *const c_void,
        out: *mut *mut c_void,
    ) -> c_int;
    pub fn AHardwareBuffer_unlock(buffer: *mut AHardwareBuffer, fence: *mut i32) -> c_int;
}

pub const USAGE_CPU_READ_OFTEN: u64 = 3;
pub const USAGE_CPU_WRITE_OFTEN: u64 = 3 << 4;
pub const USAGE_GPU_SAMPLED_IMAGE: u64 = 1 << 8;

pub const FORMAT_R8G8B8A8_UNORM: u32 = 1;
const FORMAT_R8G8B8X8_UNORM: u32 = 2;

/// A buffer locked for CPU access; unlocked when dropped, on every path out (an early return
/// used to leave buffers locked in the C code, after which the window never updated again).
pub struct Locked {
    ahb: *mut AHardwareBuffer,
    addr: *mut u8,
    pub desc: Desc,
}

impl Locked {
    pub fn new(ahb: i64, usage: u64) -> Option<Locked> {
        let ahb = ahb as usize as *mut AHardwareBuffer;
        if ahb.is_null() {
            return None;
        }
        let mut desc = Desc::default();
        let mut addr: *mut c_void = ptr::null_mut();
        unsafe {
            AHardwareBuffer_describe(ahb, &mut desc);
            if AHardwareBuffer_lock(ahb, usage, -1, ptr::null(), &mut addr) != 0 {
                return None;
            }
            if addr.is_null() {
                AHardwareBuffer_unlock(ahb, ptr::null_mut());
                return None;
            }
        }
        Some(Locked { ahb, addr: addr as *mut u8, desc })
    }

    /// Start of the locked memory.
    pub fn base(&self) -> *mut u8 {
        self.addr
    }

    pub fn is_rgba(&self) -> bool {
        self.desc.format == FORMAT_R8G8B8A8_UNORM || self.desc.format == FORMAT_R8G8B8X8_UNORM
    }

    /// The locked memory as a surface with the row pitch the Java side uses. Its capacity is
    /// what the buffer really holds (described by the allocator), not what the caller claims.
    pub fn surface(&self, stride: i16) -> Option<Surface> {
        if stride <= 0 {
            return None;
        }
        let capacity = self.desc.stride as usize * self.desc.height as usize;
        unsafe { Surface::new(self.addr, stride as usize, capacity, self.is_rgba()) }
    }
}

impl Drop for Locked {
    fn drop(&mut self) {
        unsafe { AHardwareBuffer_unlock(self.ahb, ptr::null_mut()) };
    }
}

extern "C" {
    /// Receives an AHardwareBuffer sent with `AHardwareBuffer_sendHandleToUnixSocket` (API 26).
    pub fn AHardwareBuffer_recvHandleFromUnixSocket(socket_fd: c_int, out_buffer: *mut *mut AHardwareBuffer) -> c_int;
}
