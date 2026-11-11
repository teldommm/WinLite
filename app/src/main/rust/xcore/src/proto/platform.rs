//! What the core needs from the platform around it (the renderer, the guest's SysV shared
//! memory, buffers sent by clients). Implemented over JNI on Android and by fakes in tests.
//!
//! The methods are called from the request thread *under the server lock*: implementations must
//! be quick and must never call back into the server. What has to wait (a buffer that has not
//! arrived yet) goes through [`Platform::import_buffer_async`] instead.

use super::gfx::PixelStore;

/// A mapped shared-memory segment (MIT-SHM). The platform keeps the mapping alive until it is
/// given back with [`Platform::shm_detach`].
#[derive(Clone, Copy, Debug)]
pub struct ShmMap {
    pub ptr: *mut u8,
    pub len: usize,
}

// SAFETY: a plain address range; whoever reads it is responsible for the lifetime (see above).
unsafe impl Send for ShmMap {}

pub trait Platform: Send {
    /// Monotonic clock in nanoseconds (`System.nanoTime()` on Android).
    fn now_ns(&mut self) -> i64 {
        monotonic_ns()
    }

    /// The FPS limit in force (0 = none), the single source of truth of the renderer view.
    fn fps_limit(&mut self) -> i32 {
        0
    }

    /// The display clock `[period_ns, phase_error_ns, sequence]` of the renderer, if it has one.
    fn display_clock(&mut self) -> Option<[i64; 3]> {
        None
    }

    /// Maps the guest's SysV segment `shmid`; every `Some` is paired with one `shm_detach`.
    fn shm_attach(&mut self, _shmid: i32) -> Option<ShmMap> {
        None
    }

    fn shm_detach(&mut self, _shmid: i32) {}

    /// Receives the hardware buffer a DRI3 client sends over the socket `fd` (the core closes it
    /// afterwards). `None` when it does not arrive.
    fn import_buffer(&mut self, _fd: i32) -> Option<Box<dyn PixelStore>> {
        None
    }

    /// Like [`Platform::import_buffer`], but the waiting may happen on another thread: `done` is
    /// called with the result (from any thread, without the server lock), and the platform
    /// closes `fd`. While it runs, the client that sent the buffer is on hold and the server
    /// serves everybody else. The default does it all at once.
    fn import_buffer_async(&mut self, fd: i32, done: super::hooks::ImportDone) {
        let r = self.import_buffer(fd);
        unsafe { libc::close(fd) };
        done(r);
    }
}

/// No renderer, no shared memory: what a bare server (tests) gets.
pub struct DefaultPlatform;

impl Platform for DefaultPlatform {}

#[cfg(unix)]
#[allow(clippy::unnecessary_cast)] // c_long is 32 bits wide on armv7
pub fn monotonic_ns() -> i64 {
    let mut ts = libc::timespec { tv_sec: 0, tv_nsec: 0 };
    unsafe { libc::clock_gettime(libc::CLOCK_MONOTONIC, &mut ts) };
    ts.tv_sec as i64 * 1_000_000_000 + ts.tv_nsec as i64
}
