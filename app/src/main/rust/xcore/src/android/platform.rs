//! The Android side of [`Platform`]: the renderer's FPS limit and display clock (pushed in by
//! Java), the guest's SysV segments ([`crate::sysv`], in-process) and DRI3 buffers.
//!
//! These run on the I/O thread under the server lock, so they only read atomics or take the
//! short SysV table lock, never anything that touches the server. Waiting for a DRI3 buffer happens on a worker thread.

use super::ahb::{self, AHardwareBuffer};
use super::ahb_pixels::AhbPixels;
use crate::log;
use crate::proto::gfx::PixelStore;
use crate::proto::hooks::ImportDone;
use crate::proto::platform::{Platform, ShmMap};
use std::ptr;
use std::sync::atomic::{AtomicI32, AtomicI64, Ordering};
use std::sync::Mutex;

/// FPS limit of the renderer view (0 = none).
static FPS_LIMIT: AtomicI32 = AtomicI32::new(0);
/// Display clock: period, phase error, measurement sequence.
static CLOCK_PERIOD: AtomicI64 = AtomicI64::new(0);
static CLOCK_PHASE: AtomicI64 = AtomicI64::new(0);
static CLOCK_SEQ: AtomicI64 = AtomicI64::new(0);

pub fn set_fps_limit(fps: i32) {
    FPS_LIMIT.store(fps, Ordering::Relaxed);
}

/// The renderer measured a new arrival phase: seq changes with every measurement.
pub fn set_display_clock(period_ns: i64, phase_ns: i64, seq: i64) {
    CLOCK_PHASE.store(phase_ns, Ordering::Relaxed);
    CLOCK_PERIOD.store(period_ns, Ordering::Relaxed);
    CLOCK_SEQ.store(seq, Ordering::Release);
}

/// How long a DRI3 client may take to hand over its buffer (only that client waits meanwhile).
const IMPORT_TIMEOUT_SEC: libc::time_t = 2;

pub struct AndroidPlatform;

impl Platform for AndroidPlatform {
    fn fps_limit(&mut self) -> i32 {
        FPS_LIMIT.load(Ordering::Relaxed)
    }

    fn display_clock(&mut self) -> Option<[i64; 3]> {
        let seq = CLOCK_SEQ.load(Ordering::Acquire);
        let period = CLOCK_PERIOD.load(Ordering::Relaxed);
        if period <= 0 {
            return None;
        }
        Some([period, CLOCK_PHASE.load(Ordering::Relaxed), seq])
    }

    fn shm_attach(&mut self, shmid: i32) -> Option<ShmMap> {
        crate::sysv::attach(shmid)
    }

    fn shm_detach(&mut self, shmid: i32) {
        crate::sysv::detach(shmid)
    }

    /// The Mesa/Turnip driver sends its AHardwareBuffer over the socket pair after being poked
    /// with one byte (`nativeHardwareBufferFromSocket` of the Java server). A driver that is slow
    /// to answer must not hold the server up, so this is done on a thread of its own.
    fn import_buffer_async(&mut self, fd: i32, done: ImportDone) {
        // The closure is shared with the fallback below: it runs exactly once, in either place.
        let slot = std::sync::Arc::new(Mutex::new(Some((fd, done))));
        let worker = slot.clone();
        let spawned = std::thread::Builder::new().name("xcore-import".into()).spawn(move || {
            if let Some((fd, done)) = worker.lock().unwrap_or_else(|e| e.into_inner()).take() {
                // A panic here would leave the client on hold for good: it counts as a failure.
                let r = std::panic::catch_unwind(|| receive_buffer(fd)).unwrap_or(None);
                unsafe { libc::close(fd) };
                done(r);
            }
        });
        if spawned.is_err() {
            log::warn("DRI3: cannot start the import thread");
            if let Some((fd, done)) = slot.lock().unwrap_or_else(|e| e.into_inner()).take() {
                unsafe { libc::close(fd) };
                done(None);
            }
        }
    }

    fn import_buffer(&mut self, fd: i32) -> Option<Box<dyn PixelStore>> {
        receive_buffer(fd)
    }
}

/// Pokes the driver and waits (at most [`IMPORT_TIMEOUT_SEC`]) for its hardware buffer.
fn receive_buffer(fd: i32) -> Option<Box<dyn PixelStore>> {
    let tv = libc::timeval { tv_sec: IMPORT_TIMEOUT_SEC, tv_usec: 0 };
    unsafe {
        libc::setsockopt(fd, libc::SOL_SOCKET, libc::SO_RCVTIMEO, &tv as *const _ as *const libc::c_void, std::mem::size_of::<libc::timeval>() as libc::socklen_t);
        let byte = 1u8;
        if libc::write(fd, &byte as *const u8 as *const libc::c_void, 1) < 0 {
            log::warn("DRI3: cannot poke the buffer socket");
            return None;
        }
    }
    let mut buffer: *mut AHardwareBuffer = ptr::null_mut();
    if unsafe { ahb::AHardwareBuffer_recvHandleFromUnixSocket(fd, &mut buffer) } != 0 || buffer.is_null() {
        log::warn("DRI3: no hardware buffer arrived");
        return None;
    }
    AhbPixels::from_handle(buffer).map(|p| Box::new(p) as Box<dyn PixelStore>)
}
