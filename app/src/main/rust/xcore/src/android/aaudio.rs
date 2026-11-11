//! The AAudio output behind the ALSA server (port of `alsa_client.c`).
//!
//! Each stream is used by its client's thread only, under that client's lock: AAudio calls on
//! one stream are never concurrent.

use crate::alsa::{Options, SampleFormat, Sink, SinkFactory, StreamParams};
use crate::log;
use std::ffi::CStr;
use std::os::raw::{c_char, c_void};
use std::ptr;

#[allow(non_camel_case_types)]
type aaudio_result_t = i32;

#[repr(C)]
struct AAudioStreamBuilder {
    _p: [u8; 0],
}

#[repr(C)]
struct AAudioStream {
    _p: [u8; 0],
}

const AAUDIO_OK: aaudio_result_t = 0;
const AAUDIO_ERROR_DISCONNECTED: aaudio_result_t = -899;
const AAUDIO_FORMAT_PCM_I16: i32 = 1;
const AAUDIO_FORMAT_PCM_FLOAT: i32 = 2;
const AAUDIO_STREAM_STATE_STARTING: i32 = 3;
const AAUDIO_STREAM_STATE_PAUSING: i32 = 5;
const AAUDIO_STREAM_STATE_FLUSHING: i32 = 7;
const AAUDIO_STREAM_STATE_STOPPING: i32 = 9;
const AAUDIO_STREAM_STATE_DISCONNECTED: i32 = 13;
const AAUDIO_USAGE_GAME: i32 = 14;

/// Every blocking AAudio call gives up after this long (state changes, writes).
const TIMEOUT_NS: i64 = 100 * 1_000_000;
const MAX_REOPEN_ATTEMPTS: u32 = 1;

// libaaudio is linked by CMake (target_link_libraries of libwinlator).
extern "C" {
    fn AAudio_createStreamBuilder(builder: *mut *mut AAudioStreamBuilder) -> aaudio_result_t;
    fn AAudio_convertResultToText(result: aaudio_result_t) -> *const c_char;
    fn AAudioStreamBuilder_setPerformanceMode(builder: *mut AAudioStreamBuilder, mode: i32);
    fn AAudioStreamBuilder_setFormat(builder: *mut AAudioStreamBuilder, format: i32);
    fn AAudioStreamBuilder_setChannelCount(builder: *mut AAudioStreamBuilder, channels: i32);
    fn AAudioStreamBuilder_setSampleRate(builder: *mut AAudioStreamBuilder, rate: i32);
    fn AAudioStreamBuilder_setBufferCapacityInFrames(builder: *mut AAudioStreamBuilder, frames: i32);
    fn AAudioStreamBuilder_setUsage(builder: *mut AAudioStreamBuilder, usage: i32);
    fn AAudioStreamBuilder_openStream(builder: *mut AAudioStreamBuilder, stream: *mut *mut AAudioStream) -> aaudio_result_t;
    fn AAudioStreamBuilder_delete(builder: *mut AAudioStreamBuilder) -> aaudio_result_t;

    fn AAudioStream_close(stream: *mut AAudioStream) -> aaudio_result_t;
    fn AAudioStream_requestStart(stream: *mut AAudioStream) -> aaudio_result_t;
    fn AAudioStream_requestPause(stream: *mut AAudioStream) -> aaudio_result_t;
    fn AAudioStream_requestFlush(stream: *mut AAudioStream) -> aaudio_result_t;
    fn AAudioStream_requestStop(stream: *mut AAudioStream) -> aaudio_result_t;
    fn AAudioStream_getState(stream: *mut AAudioStream) -> i32;
    fn AAudioStream_waitForStateChange(stream: *mut AAudioStream, input: i32, next: *mut i32, timeout_ns: i64) -> aaudio_result_t;
    fn AAudioStream_write(stream: *mut AAudioStream, buffer: *const c_void, frames: i32, timeout_ns: i64) -> aaudio_result_t;
    fn AAudioStream_setBufferSizeInFrames(stream: *mut AAudioStream, frames: i32) -> aaudio_result_t;
    fn AAudioStream_getBufferCapacityInFrames(stream: *mut AAudioStream) -> i32;
    fn AAudioStream_getFramesPerBurst(stream: *mut AAudioStream) -> i32;
    fn AAudioStream_getXRunCount(stream: *mut AAudioStream) -> i32;
    fn AAudioStream_getPerformanceMode(stream: *mut AAudioStream) -> i32;
}

fn now_ns() -> i64 {
    let mut ts = libc::timespec { tv_sec: 0, tv_nsec: 0 };
    unsafe { libc::clock_gettime(libc::CLOCK_MONOTONIC, &mut ts) };
    ts.tv_sec * 1_000_000_000 + ts.tv_nsec
}

fn result_text(r: aaudio_result_t) -> String {
    unsafe {
        let p = AAudio_convertResultToText(r);
        if p.is_null() {
            format!("{r}")
        } else {
            CStr::from_ptr(p).to_string_lossy().into_owned()
        }
    }
}

pub struct AAudioFactory;

impl SinkFactory for AAudioFactory {
    fn open(&self, params: &StreamParams, options: &Options) -> Option<Box<dyn Sink>> {
        let latency_frames = (options.latency_ms as u64 * params.rate as u64 / 1000) as i32;
        let target = (params.buffer_frames as i32).max(latency_frames).max(1);
        let mut s = AAudioSink {
            stream: ptr::null_mut(),
            format: params.format,
            channels: params.channels as i32,
            rate: params.rate as i32,
            performance_mode: options.performance_mode,
            target_frames: target,
            // Twice the target leaves headroom for underrun growth; the extra capacity adds no
            // latency until it is actually used.
            capacity_frames: target.saturating_mul(2),
            buffer_frames: 0,
            last_xruns: 0,
            started: false,
        };
        if s.open() {
            Some(Box::new(s))
        } else {
            None
        }
    }
}

struct AAudioSink {
    stream: *mut AAudioStream,
    format: SampleFormat,
    channels: i32,
    rate: i32,
    performance_mode: i32,
    target_frames: i32,
    capacity_frames: i32,
    /// Current device buffer size: grows on underruns, kept across a reopen.
    buffer_frames: i32,
    last_xruns: i32,
    /// The guest wants it running: a reopened stream is started again.
    started: bool,
}

// SAFETY: an AAudio stream may be used from any thread; this one is used by one at a time.
unsafe impl Send for AAudioSink {}

impl AAudioSink {
    fn open(&mut self) -> bool {
        unsafe {
            let mut builder: *mut AAudioStreamBuilder = ptr::null_mut();
            if AAudio_createStreamBuilder(&mut builder) != AAUDIO_OK || builder.is_null() {
                return false;
            }
            AAudioStreamBuilder_setPerformanceMode(builder, self.performance_mode);
            AAudioStreamBuilder_setFormat(
                builder,
                match self.format {
                    SampleFormat::I16 => AAUDIO_FORMAT_PCM_I16,
                    SampleFormat::F32 => AAUDIO_FORMAT_PCM_FLOAT,
                },
            );
            AAudioStreamBuilder_setChannelCount(builder, self.channels);
            AAudioStreamBuilder_setSampleRate(builder, self.rate);
            AAudioStreamBuilder_setBufferCapacityInFrames(builder, self.capacity_frames);
            // Game audio (routing, focus and ducking policy); the default was "media".
            AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_GAME);

            let mut stream: *mut AAudioStream = ptr::null_mut();
            let r = AAudioStreamBuilder_openStream(builder, &mut stream);
            AAudioStreamBuilder_delete(builder);
            if r != AAUDIO_OK || stream.is_null() {
                log::warn(&format!("alsa: AAudio openStream failed: {}", result_text(r)));
                return false;
            }

            let capacity = AAudioStream_getBufferCapacityInFrames(stream);
            let mut wanted = if self.buffer_frames > 0 { self.buffer_frames } else { self.target_frames };
            if capacity > 0 && wanted > capacity {
                wanted = capacity;
            }
            let applied = AAudioStream_setBufferSizeInFrames(stream, wanted);
            self.buffer_frames = if applied > 0 { applied } else { wanted };
            self.last_xruns = AAudioStream_getXRunCount(stream);
            self.stream = stream;

            log::write(
                log::DEBUG,
                "ALSAServer",
                &format!(
                    "stream opened: rate={} ch={} fmt={:?} buffer={} capacity={} burst={} perf={}",
                    self.rate,
                    self.channels,
                    self.format,
                    self.buffer_frames,
                    capacity,
                    AAudioStream_getFramesPerBurst(stream),
                    AAudioStream_getPerformanceMode(stream)
                ),
            );
        }
        true
    }

    fn close(&mut self) {
        if !self.stream.is_null() {
            unsafe { AAudioStream_close(self.stream) };
            self.stream = ptr::null_mut();
        }
    }

    fn wait_leaving(&self, state: i32) {
        let mut next = 0;
        unsafe { AAudioStream_waitForStateChange(self.stream, state, &mut next, TIMEOUT_NS) };
    }

    fn request_start(&mut self) {
        if self.stream.is_null() {
            return;
        }
        unsafe { AAudioStream_requestStart(self.stream) };
        self.wait_leaving(AAUDIO_STREAM_STATE_STARTING);
        // Underruns counted while it was stopped or paused are not the guest's fault: they must
        // not grow the buffer.
        self.last_xruns = unsafe { AAudioStream_getXRunCount(self.stream) };
    }

    fn disconnected(&self, result: aaudio_result_t) -> bool {
        result == AAUDIO_ERROR_DISCONNECTED
            || (!self.stream.is_null() && unsafe { AAudioStream_getState(self.stream) } == AAUDIO_STREAM_STATE_DISCONNECTED)
    }

    /// The output device went away (headphones / Bluetooth switch, route change, audio server
    /// restart): a disconnected AAudio stream never recovers, so a fresh one is opened.
    fn reopen(&mut self) -> bool {
        log::write(log::DEBUG, "ALSAServer", "stream disconnected, reopening");
        self.close();
        if !self.open() {
            return false;
        }
        if self.started {
            self.request_start();
        }
        true
    }

    /// Underruns mean the guest's buffer accounting got ahead of what the device holds: one
    /// burst more per new underrun, up to the stream capacity.
    fn grow_on_underrun(&mut self) {
        unsafe {
            let xruns = AAudioStream_getXRunCount(self.stream);
            if xruns <= self.last_xruns {
                return;
            }
            self.last_xruns = xruns;
            let capacity = AAudioStream_getBufferCapacityInFrames(self.stream);
            if self.buffer_frames >= capacity {
                return;
            }
            let mut burst = AAudioStream_getFramesPerBurst(self.stream);
            if burst <= 0 {
                burst = 256;
            }
            let next = (self.buffer_frames + burst).min(capacity);
            let applied = AAudioStream_setBufferSizeInFrames(self.stream, next);
            if applied > 0 {
                self.buffer_frames = applied;
            }
        }
    }
}

impl Sink for AAudioSink {
    fn start(&mut self) {
        self.started = true;
        if self.stream.is_null() || self.disconnected(AAUDIO_OK) {
            self.reopen(); // also starts it
            return;
        }
        self.request_start();
    }

    fn pause(&mut self) {
        self.started = false;
        if self.stream.is_null() {
            return;
        }
        unsafe { AAudioStream_requestPause(self.stream) };
        self.wait_leaving(AAUDIO_STREAM_STATE_PAUSING);
    }

    fn discard(&mut self) {
        self.started = false;
        if self.stream.is_null() {
            return;
        }
        // Flush is only allowed on a paused stream: pause first, then drop what is queued.
        unsafe { AAudioStream_requestPause(self.stream) };
        self.wait_leaving(AAUDIO_STREAM_STATE_PAUSING);
        if unsafe { AAudioStream_requestFlush(self.stream) } == AAUDIO_OK {
            self.wait_leaving(AAUDIO_STREAM_STATE_FLUSHING);
        }
    }

    fn drain(&mut self) {
        self.started = false;
        if self.stream.is_null() {
            return;
        }
        // requestStop on an output stream plays out what is queued, then stops. Wait for that
        // (at most the buffer's length, plus a margin): the CLOSE that follows would cut it off.
        unsafe { AAudioStream_requestStop(self.stream) };
        let budget = self.buffer_frames.max(0) as i64 * 1_000_000_000 / self.rate.max(1) as i64 + TIMEOUT_NS;
        let deadline = now_ns() + budget.min(2_000_000_000);
        let mut state = AAUDIO_STREAM_STATE_STOPPING;
        while state == AAUDIO_STREAM_STATE_STOPPING {
            let left = deadline - now_ns();
            if left <= 0 {
                break;
            }
            let mut next = state;
            if unsafe { AAudioStream_waitForStateChange(self.stream, state, &mut next, left) } != AAUDIO_OK {
                break;
            }
            state = next;
        }
    }

    /// Writes every frame (looping over partial writes). Stops early only on a timeout (the
    /// stream is not consuming) or an unrecoverable error.
    fn write(&mut self, data: &[u8], frames: usize) -> Option<usize> {
        let frame_bytes = self.format.bytes() * self.channels as usize;
        let frames = frames.min(data.len() / frame_bytes.max(1));
        let mut written = 0usize;
        let mut reopens = 0;
        while written < frames && !self.stream.is_null() {
            let chunk = (frames - written).min(i32::MAX as usize) as i32;
            let r = unsafe {
                AAudioStream_write(self.stream, data[written * frame_bytes..].as_ptr() as *const c_void, chunk, TIMEOUT_NS)
            };
            if r < 0 {
                if self.disconnected(r) && reopens < MAX_REOPEN_ATTEMPTS {
                    reopens += 1;
                    if self.reopen() {
                        continue;
                    }
                }
                log::warn(&format!("alsa: AAudio write failed: {}", result_text(r)));
                break;
            }
            if r == 0 {
                break;
            }
            written += r as usize;
        }
        if self.stream.is_null() {
            return if written > 0 { Some(written) } else { None };
        }
        self.grow_on_underrun();
        Some(written)
    }
}

impl Drop for AAudioSink {
    fn drop(&mut self) {
        self.close();
    }
}
