//! The ALSA server: what the guest's `libasound_module_pcm_android_aserver.so` talks to (port of
//! `ALSAClient` / `ALSARequestHandler` / `ALSAServerComponent`). The audio device itself is behind
//! [`Sink`] (AAudio on Android, see `android::aaudio`), so everything here runs on the host too.
//!
//! One thread per guest stream: a WRITE blocks while the device has no room, and that is what
//! paces the guest (its next POINTER waits for the reply). The guest's wire format, unchanged:
//!
//! * header `u8 code, i32 length` (little endian), then `length` body bytes where noted;
//! * PREPARE body `u8 channels, u8 data type, i32 rate, i32 buffer frames` -> one byte `0` with
//!   a memfd attached: the shared buffer the guest copies each period into;
//! * WRITE `length` = bytes in the shared buffer (or, without one, `length` bytes inline);
//! * POINTER -> `i32` frames consumed since PREPARE.
//!
//! Changes against the Java server:
//! * a stream the device cannot open (or loses for good) no longer freezes the guest: its frames
//!   are consumed at the real-time rate instead of never;
//! * the device starts with the first START or WRITE, not at PREPARE: started empty it counted
//!   underruns that the underrun growth turned into extra latency before the first sample;
//! * resuming after PAUSE works: the guest plugin sends PAUSE for both pause and resume, which
//!   left the stream paused and the guest stuck; a WRITE now restarts it (ALSA only writes in
//!   PREPARED / RUNNING, so a write means the stream is meant to run);
//! * STOP (snd_pcm_drop) discards what is queued instead of playing it out, and DRAIN plays all
//!   of it before the CLOSE that follows closes the device (it used to cut the last ~100 ms);
//! * while the app is in the background the frames are consumed at the real-time rate, not
//!   instantly (which ran the guest's audio clock as fast as it could write).

use crate::log;
use crate::shm;
use crate::sock;
use std::io;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, Weak};
use std::thread::JoinHandle;
use std::time::Duration;

pub const CLOSE: u8 = 0;
pub const START: u8 = 1;
pub const STOP: u8 = 2;
pub const PAUSE: u8 = 3;
pub const PREPARE: u8 = 4;
pub const WRITE: u8 = 5;
pub const DRAIN: u8 = 6;
pub const POINTER: u8 = 7;

/// PREPARE body bytes.
const PREPARE_LEN: usize = 10;
/// Largest body accepted. The plugin's constraints allow 64 periods of 64 KiB: 4 MiB.
const MAX_BODY: usize = 8 << 20;
/// Longest single pause of the real-time pacing (the guest's next request waits that long).
const MAX_PACE: Duration = Duration::from_millis(500);

/// Sample formats of the guest, in the wire order.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DataType {
    U8,
    S16LE,
    S16BE,
    FloatLE,
    FloatBE,
}

impl DataType {
    /// Unknown values fall back to S16LE, as before.
    pub fn from_wire(v: u8) -> DataType {
        match v {
            0 => DataType::U8,
            1 => DataType::S16LE,
            2 => DataType::S16BE,
            3 => DataType::FloatLE,
            4 => DataType::FloatBE,
            _ => DataType::S16LE,
        }
    }

    pub fn bytes(self) -> usize {
        match self {
            DataType::U8 => 1,
            DataType::S16LE | DataType::S16BE => 2,
            DataType::FloatLE | DataType::FloatBE => 4,
        }
    }

    /// What the device is fed: U8 and big-endian data are converted.
    pub fn output(self) -> SampleFormat {
        match self {
            DataType::FloatLE | DataType::FloatBE => SampleFormat::F32,
            _ => SampleFormat::I16,
        }
    }
}

/// Device sample format, always little endian.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SampleFormat {
    I16,
    F32,
}

impl SampleFormat {
    pub fn bytes(self) -> usize {
        match self {
            SampleFormat::I16 => 2,
            SampleFormat::F32 => 4,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct StreamParams {
    pub format: SampleFormat,
    pub channels: u32,
    pub rate: u32,
    /// The guest's ALSA buffer size in frames.
    pub buffer_frames: u32,
}

/// Tunables, read once per launch from the container/shortcut environment.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Options {
    /// Floor of the device buffer; 0 = the guest's ALSA buffer size.
    pub latency_ms: u32,
    /// AAudio performance mode (AAUDIO_PERFORMANCE_MODE_*).
    pub performance_mode: i32,
}

pub const PERFORMANCE_MODE_NONE: i32 = 10;
pub const PERFORMANCE_MODE_POWER_SAVING: i32 = 11;
pub const PERFORMANCE_MODE_LOW_LATENCY: i32 = 12;

impl Default for Options {
    fn default() -> Self {
        Options { latency_ms: 0, performance_mode: PERFORMANCE_MODE_LOW_LATENCY }
    }
}

/// An output stream of the device. Only ever used by one thread at a time.
pub trait Sink: Send {
    fn start(&mut self);
    fn pause(&mut self);
    /// Stops and drops what is queued (ALSA drop).
    fn discard(&mut self);
    /// Plays out what is queued, then stops (ALSA drain). May block until it has: the guest does
    /// not wait for it, but its next requests (the STOP and CLOSE that follow) are served after.
    fn drain(&mut self);
    /// Writes `frames` frames of `data` (interleaved, in the stream's format). Frames accepted:
    /// fewer only when the device stopped consuming (a timeout). `None`: the stream is gone for
    /// good and nothing more can be written to it.
    fn write(&mut self, data: &[u8], frames: usize) -> Option<usize>;
}

pub trait SinkFactory: Send + Sync {
    fn open(&self, params: &StreamParams, options: &Options) -> Option<Box<dyn Sink>>;
}

/// Monotonic clock in nanoseconds.
fn now_ns() -> i64 {
    let mut ts = libc::timespec { tv_sec: 0, tv_nsec: 0 };
    unsafe { libc::clock_gettime(libc::CLOCK_MONOTONIC, &mut ts) };
    ts.tv_sec as i64 * 1_000_000_000 + ts.tv_nsec as i64
}

/// Stands in for the device when there is none (or it is paused for the background): frames
/// leave a virtual buffer of the guest's size at the sample rate.
#[derive(Default)]
struct Pacer {
    /// When the virtual device will have played everything it holds; 0 = empty.
    drained_at: i64,
}

impl Pacer {
    fn reset(&mut self) {
        self.drained_at = 0;
    }

    /// Queues `frames`; how long to wait before the guest may go on.
    fn push(&mut self, frames: usize, rate: u32, buffer_frames: u32, now: i64) -> Duration {
        if rate == 0 {
            return Duration::ZERO;
        }
        let ns = |f: u64| (f as u128 * 1_000_000_000 / rate as u128) as i64;
        if self.drained_at < now {
            self.drained_at = now;
        }
        self.drained_at += ns(frames as u64);
        let excess = self.drained_at - now - ns(buffer_frames as u64);
        if excess <= 0 {
            Duration::ZERO
        } else {
            Duration::from_nanos(excess as u64).min(MAX_PACE)
        }
    }
}

/// Converts guest samples to the device format. Returns the bytes to write: `input` itself
/// when no conversion is needed, else `scratch`.
pub fn convert<'a>(dt: DataType, input: &'a [u8], scratch: &'a mut Vec<u8>) -> &'a [u8] {
    match dt {
        DataType::S16LE | DataType::FloatLE => input,
        DataType::U8 => {
            scratch.clear();
            scratch.reserve(input.len() * 2);
            for &b in input {
                let v = ((b as i16) - 128) << 8;
                scratch.extend_from_slice(&v.to_le_bytes());
            }
            scratch
        }
        DataType::S16BE => {
            scratch.clear();
            scratch.extend(input.chunks_exact(2).flat_map(|c| [c[1], c[0]]));
            scratch
        }
        DataType::FloatBE => {
            scratch.clear();
            scratch.extend(input.chunks_exact(4).flat_map(|c| [c[3], c[2], c[1], c[0]]));
            scratch
        }
    }
}

#[derive(Clone, Copy, Debug)]
struct Prepared {
    data_type: DataType,
    params: StreamParams,
    /// Guest bytes per frame.
    frame_bytes: usize,
}

/// The server's read-only view of the guest's shared buffer.
struct SharedBuffer {
    ptr: *mut u8,
    len: usize,
}

// SAFETY: a plain mapping owned by the client; used only under the client's lock.
unsafe impl Send for SharedBuffer {}

impl Drop for SharedBuffer {
    fn drop(&mut self) {
        unsafe { shm::unmap(self.ptr, self.len) };
    }
}

/// One guest stream.
pub struct Client {
    factory: Arc<dyn SinkFactory>,
    options: Options,
    prepared: Option<Prepared>,
    sink: Option<Box<dyn Sink>>,
    shared: Option<SharedBuffer>,
    /// Frames consumed since PREPARE (what POINTER reports). Wraps like the guest's int does.
    position: u32,
    /// The guest wants the stream running (START or a write, until STOP / PAUSE / DRAIN).
    playing: bool,
    /// The device stream is started.
    device_running: bool,
    /// The app is in the background: the device is paused, frames are paced instead.
    suspended: bool,
    /// DRAIN was the last state change: the STOP alsa-lib sends right after it (its drain ends
    /// with a drop) must not discard what is being played out.
    drained: bool,
    pacer: Pacer,
    scratch: Vec<u8>,
}

impl Client {
    pub fn new(factory: Arc<dyn SinkFactory>, options: Options) -> Client {
        Client {
            factory,
            options,
            prepared: None,
            sink: None,
            shared: None,
            position: 0,
            playing: false,
            device_running: false,
            suspended: SUSPENDED.load(Ordering::Acquire),
            drained: false,
            pacer: Pacer::default(),
            scratch: Vec::new(),
        }
    }

    pub fn position(&self) -> u32 {
        self.position
    }

    /// CLOSE, and the end of the connection: the device stream and the shared buffer go.
    pub fn release(&mut self) {
        self.sink = None; // closes the device stream
        self.shared = None;
        self.playing = false;
        self.device_running = false;
        self.drained = false;
    }

    /// PREPARE. Returns the shared buffer's descriptor for the reply (the caller closes it), or
    /// -1 when there is none.
    pub fn prepare(&mut self, body: &[u8]) -> i32 {
        self.release();
        self.position = 0;
        self.pacer.reset();
        self.prepared = None;
        if body.len() < PREPARE_LEN {
            return -1;
        }
        let channels = body[0] as u32;
        let data_type = DataType::from_wire(body[1]);
        let rate = i32::from_le_bytes([body[2], body[3], body[4], body[5]]);
        let buffer = i32::from_le_bytes([body[6], body[7], body[8], body[9]]);
        if channels == 0 || channels > 8 || rate <= 0 || buffer <= 0 {
            log::warn(&format!("alsa: bad PREPARE channels={channels} rate={rate} buffer={buffer}"));
            return -1;
        }
        let params = StreamParams { format: data_type.output(), channels, rate: rate as u32, buffer_frames: buffer as u32 };
        let frame_bytes = channels as usize * data_type.bytes();
        self.prepared = Some(Prepared { data_type, params, frame_bytes });

        self.sink = self.factory.open(&params, &self.options);
        if self.sink.is_none() {
            log::warn(&format!("alsa: no device stream for rate={rate} ch={channels} fmt={data_type:?}, pacing in real time"));
        }

        let bytes = buffer as usize * frame_bytes;
        let fd = shm::memfd_create("alsa-shm", bytes as i64);
        if fd >= 0 {
            let ptr = shm::map(fd, bytes, 0, true);
            if !ptr.is_null() {
                self.shared = Some(SharedBuffer { ptr, len: bytes });
            }
        }
        fd
    }

    fn start_device(&mut self) {
        if !self.suspended && !self.device_running {
            if let Some(sink) = self.sink.as_mut() {
                sink.start();
                self.device_running = true;
            }
        }
    }

    pub fn start(&mut self) {
        if self.prepared.is_none() || self.playing {
            return;
        }
        self.playing = true;
        self.drained = false;
        self.pacer.reset();
        self.start_device();
    }

    pub fn stop(&mut self) {
        self.playing = false;
        self.device_running = false;
        if self.drained {
            return;
        }
        if let Some(sink) = self.sink.as_mut() {
            sink.discard();
        }
    }

    pub fn pause(&mut self) {
        self.playing = false;
        if self.device_running {
            self.device_running = false;
            if let Some(sink) = self.sink.as_mut() {
                sink.pause();
            }
        }
    }

    pub fn drain(&mut self) {
        self.playing = false;
        self.device_running = false;
        self.drained = true;
        if let Some(sink) = self.sink.as_mut() {
            sink.drain();
        }
    }

    /// The app went to (or came back from) the background.
    pub fn set_suspended(&mut self, suspend: bool) {
        if suspend == self.suspended {
            return;
        }
        self.suspended = suspend;
        if suspend {
            if self.device_running {
                self.device_running = false;
                if let Some(sink) = self.sink.as_mut() {
                    sink.pause();
                }
            }
        } else {
            self.pacer.reset();
            if self.playing {
                self.start_device();
            }
        }
    }

    pub fn has_shared_buffer(&self) -> bool {
        self.shared.is_some()
    }

    /// WRITE of `len` bytes from the shared buffer. Returns how long to wait before serving the
    /// next request (real-time pacing), to be slept without the client's lock.
    pub fn write_shared(&mut self, len: i32) -> Duration {
        let Some(shared) = self.shared.as_ref() else { return Duration::ZERO };
        // A length past the shared segment is ignored, as before.
        if len <= 0 || len as usize > shared.len {
            return Duration::ZERO;
        }
        let ptr = shared.ptr;
        // SAFETY: the mapping lives as long as `self.shared`, which `write` does not touch; the
        // guest refills it only after the reply to its next POINTER, which comes after this.
        let data = unsafe { std::slice::from_raw_parts(ptr, len as usize) };
        self.write(data)
    }

    /// WRITE of inline data (no shared buffer). See [`Client::write_shared`].
    pub fn write(&mut self, data: &[u8]) -> Duration {
        let Some(prep) = self.prepared else { return Duration::ZERO };
        let frames = data.len() / prep.frame_bytes;
        if frames == 0 {
            return Duration::ZERO;
        }
        if !self.playing {
            // ALSA writes only in PREPARED / RUNNING: the stream is meant to run (this is also
            // the resume after PAUSE, which the plugin sends as another PAUSE).
            self.start();
        }
        let data = &data[..frames * prep.frame_bytes];

        if !self.suspended {
            if let Some(sink) = self.sink.as_mut() {
                let out = convert(prep.data_type, data, &mut self.scratch);
                match sink.write(out, frames) {
                    Some(n) => {
                        let n = n.min(frames);
                        self.position = self.position.wrapping_add(n as u32);
                        return Duration::ZERO;
                    }
                    None => {
                        log::warn("alsa: device stream lost, pacing in real time");
                        self.sink = None;
                        self.device_running = false;
                    }
                }
            }
        }
        // No device (or paused for the background): consume at the real-time rate.
        self.position = self.position.wrapping_add(frames as u32);
        self.pacer.push(frames, prep.params.rate, prep.params.buffer_frames, now_ns())
    }
}

// ------------------------------------------------------------- background suspension (global)

static SUSPENDED: AtomicBool = AtomicBool::new(false);
static CLIENTS: Mutex<Vec<Weak<Mutex<Client>>>> = Mutex::new(Vec::new());

fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

fn register(client: &Arc<Mutex<Client>>) {
    let mut all = lock(&CLIENTS);
    all.retain(|w| w.strong_count() > 0);
    all.push(Arc::downgrade(client));
}

/// Pauses (or resumes) every guest stream's device output while the app is in the background.
/// Blocks for at most one device write per stream.
pub fn set_output_suspended(suspend: bool) {
    SUSPENDED.store(suspend, Ordering::Release);
    let clients: Vec<Arc<Mutex<Client>>> = lock(&CLIENTS).iter().filter_map(|w| w.upgrade()).collect();
    for c in clients {
        lock(&c).set_suspended(suspend);
    }
}

// ----------------------------------------------------------------------------------- server

struct Conn {
    fd: i32,
    thread: JoinHandle<()>,
}

struct Shared {
    factory: Arc<dyn SinkFactory>,
    options: Options,
    stop: AtomicBool,
    conns: Mutex<Vec<Conn>>,
}

/// The ALSA socket server: an accept thread, and a thread per connection.
pub struct Server {
    shared: Arc<Shared>,
    stop_fd: i32,
    acceptor: Option<JoinHandle<()>>,
}

impl Server {
    pub fn start(path: &str, factory: Arc<dyn SinkFactory>, options: Options) -> io::Result<Server> {
        let listener = sock::create_unix_listener(path.as_bytes());
        if listener < 0 {
            return Err(io::Error::last_os_error());
        }
        let stop_fd = sock::eventfd_create();
        if stop_fd < 0 {
            let err = io::Error::last_os_error();
            sock::close_fd(listener);
            return Err(err);
        }
        let shared = Arc::new(Shared { factory, options, stop: AtomicBool::new(false), conns: Mutex::new(Vec::new()) });
        let s = shared.clone();
        let acceptor = std::thread::Builder::new()
            .name("alsa-accept".into())
            .spawn(move || accept_loop(s, listener, stop_fd))
            .map_err(|e| {
                sock::close_fd(listener);
                sock::close_fd(stop_fd);
                e
            })?;
        Ok(Server { shared, stop_fd, acceptor: Some(acceptor) })
    }

    /// Stops accepting, ends every connection (its device stream closes) and joins the threads.
    pub fn stop(&mut self) {
        let Some(acceptor) = self.acceptor.take() else { return };
        self.shared.stop.store(true, Ordering::Release);
        let one: u64 = 1;
        unsafe { sock::write(self.stop_fd, &one as *const u64 as *const u8, 8) };
        let _ = acceptor.join();
        sock::close_fd(self.stop_fd);

        let conns: Vec<Conn> = std::mem::take(&mut *lock(&self.shared.conns));
        // shutdown() ends a blocked read with EOF; a device write ends within its timeout.
        for c in &conns {
            unsafe { libc::shutdown(c.fd, libc::SHUT_RDWR) };
        }
        for c in conns {
            let _ = c.thread.join();
        }
    }
}

impl Drop for Server {
    fn drop(&mut self) {
        self.stop();
    }
}

fn accept_loop(shared: Arc<Shared>, listener: i32, stop_fd: i32) {
    let mut next_id = 0u32;
    loop {
        let mut pfds = [
            libc::pollfd { fd: listener, events: libc::POLLIN, revents: 0 },
            libc::pollfd { fd: stop_fd, events: libc::POLLIN, revents: 0 },
        ];
        let r = unsafe { libc::poll(pfds.as_mut_ptr(), 2, -1) };
        if r < 0 {
            if io::Error::last_os_error().raw_os_error() == Some(libc::EINTR) {
                continue;
            }
            break;
        }
        if pfds[1].revents != 0 || shared.stop.load(Ordering::Acquire) {
            break;
        }
        if pfds[0].revents & libc::POLLIN == 0 {
            continue;
        }
        let fd = unsafe { libc::accept4(listener, std::ptr::null_mut(), std::ptr::null_mut(), libc::SOCK_CLOEXEC) };
        if fd < 0 {
            continue;
        }
        next_id = next_id.wrapping_add(1);
        let s = shared.clone();
        let spawned = std::thread::Builder::new().name(format!("alsa-{next_id}")).spawn(move || serve_client(s, fd));
        match spawned {
            Ok(thread) => {
                let mut conns = lock(&shared.conns);
                conns.retain(|c| !c.thread.is_finished());
                conns.push(Conn { fd, thread });
            }
            Err(_) => {
                log::warn("alsa: cannot start a client thread");
                sock::close_fd(fd);
            }
        }
    }
    sock::close_fd(listener);
}

/// Reads exactly `buf.len()` bytes; `false` on EOF or error.
fn read_exact(fd: i32, buf: &mut [u8]) -> bool {
    let mut off = 0;
    while off < buf.len() {
        let n = unsafe { sock::read(fd, buf[off..].as_mut_ptr(), buf.len() - off) };
        if n > 0 {
            off += n as usize;
        } else if n < 0 && io::Error::last_os_error().raw_os_error() == Some(libc::EINTR) {
            continue;
        } else {
            return false;
        }
    }
    true
}

fn send_all(fd: i32, data: &[u8]) -> bool {
    let mut off = 0;
    while off < data.len() {
        let n = unsafe { sock::send_nosignal(fd, data[off..].as_ptr(), data.len() - off) };
        if n > 0 {
            off += n as usize;
        } else if n < 0 && io::Error::last_os_error().raw_os_error() == Some(libc::EINTR) {
            continue;
        } else {
            return false;
        }
    }
    true
}

/// Audio request threads were plain Java threads; ask for THREAD_PRIORITY_AUDIO (-16). Best
/// effort: refused, the thread keeps its priority.
fn raise_priority() {
    unsafe {
        let tid = libc::syscall(libc::SYS_gettid) as libc::id_t;
        libc::setpriority(libc::PRIO_PROCESS, tid, -16);
    }
}

fn serve_client(shared: Arc<Shared>, fd: i32) {
    raise_priority();
    let client = Arc::new(Mutex::new(Client::new(shared.factory.clone(), shared.options)));
    register(&client);
    let mut inline: Vec<u8> = Vec::new();

    let mut header = [0u8; 5];
    while !shared.stop.load(Ordering::Acquire) && read_exact(fd, &mut header) {
        let code = header[0];
        let len = i32::from_le_bytes([header[1], header[2], header[3], header[4]]);
        let ok = match code {
            CLOSE => {
                lock(&client).release();
                true
            }
            START => {
                lock(&client).start();
                true
            }
            STOP => {
                lock(&client).stop();
                true
            }
            PAUSE => {
                lock(&client).pause();
                true
            }
            DRAIN => {
                lock(&client).drain();
                true
            }
            PREPARE => {
                if len < 0 || len as usize > MAX_BODY {
                    false
                } else {
                    let mut body = vec![0u8; len as usize];
                    if !read_exact(fd, &mut body) {
                        false
                    } else {
                        // The guest always waits for this reply (one byte, maybe a descriptor).
                        let shm_fd = lock(&client).prepare(&body);
                        let zero = [0u8; 1];
                        if shm_fd >= 0 {
                            let n = unsafe { sock::send_with_fd(fd, zero.as_ptr(), 1, shm_fd) };
                            sock::close_fd(shm_fd);
                            n == 1
                        } else {
                            send_all(fd, &zero)
                        }
                    }
                }
            }
            WRITE => {
                let mut c = lock(&client);
                let wait = if c.has_shared_buffer() {
                    c.write_shared(len)
                } else if len < 0 || len as usize > MAX_BODY {
                    drop(c);
                    break; // an inline length we cannot skip: the stream is out of sync
                } else {
                    drop(c);
                    inline.resize(len as usize, 0);
                    if !read_exact(fd, &mut inline) {
                        break;
                    }
                    c = lock(&client);
                    c.write(&inline)
                };
                drop(c);
                if !wait.is_zero() {
                    std::thread::sleep(wait);
                }
                true
            }
            POINTER => {
                let pos = lock(&client).position();
                send_all(fd, &pos.to_le_bytes())
            }
            _ => true, // unknown request: header ignored, as before
        };
        if !ok {
            break;
        }
    }
    lock(&client).release();
    sock::close_fd(fd);
}
