//! How extensions plug into the core: hooks (the core tells them something happened), timers,
//! file descriptors received with a request, and clients that wait for a condition.
//!
//! Extension handlers are taken out of `ext_handlers` while they run, so none is ever called
//! re-entrantly; a hook that fires while its own extension is running is simply not delivered to
//! it (the extension caused it and knows).

use super::gfx::PixelStore;
use super::requests::Extension;
use super::{ClientId, Server};
use std::collections::HashMap;
use std::os::unix::io::RawFd;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::{Arc, Mutex};

/// Wakes the thread that serves the sockets (a worker thread finished something).
pub type Wake = Arc<dyn Fn() + Send + Sync>;

/// Called, from any thread and without the server lock, when a buffer import is over.
pub type ImportDone = Box<dyn FnOnce(Option<Box<dyn PixelStore>>) + Send>;

/// A DRI3 pixmap whose buffer is being received (see [`Server::start_import`]).
pub struct ImportJob {
    pub client: ClientId,
    pub pixmap: u32,
    pub window: u32,
    pub width: i16,
    pub height: i16,
    pub depth: u8,
    /// Minor opcode of the request, for an error report.
    pub minor: u8,
}

/// Where finished imports wait for the server to pick them up (token -> buffer, `None` = failed).
#[derive(Default)]
pub struct ImportResults(Mutex<HashMap<u64, Option<Box<dyn PixelStore>>>>);

impl ImportResults {
    fn map(&self) -> std::sync::MutexGuard<'_, HashMap<u64, Option<Box<dyn PixelStore>>>> {
        self.0.lock().unwrap_or_else(|e| e.into_inner())
    }
    fn put(&self, token: u64, r: Option<Box<dyn PixelStore>>) {
        self.map().insert(token, r);
    }
    fn has(&self, token: u64) -> bool {
        self.map().contains_key(&token)
    }
    fn take(&self, token: u64) -> Option<Box<dyn PixelStore>> {
        self.map().remove(&token).flatten()
    }
    #[cfg(test)]
    pub(crate) fn is_empty_for_test(&self) -> bool {
        self.map().is_empty()
    }
    fn retain(&self, mut keep: impl FnMut(u64) -> bool) {
        self.map().retain(|t, _| keep(*t));
    }
}

/// Things that happen in the core that extensions care about (the Java `OnResourceLifecycle`,
/// `OnWindowModification` and `OnPointerMotion` listeners).
#[derive(Clone, Debug, PartialEq)]
pub enum Hook {
    /// Just before the window leaves the tree (its events are still deliverable).
    WindowFreed(u32),
    PixmapFreed(u32),
    WindowUnmapped(u32),
    WindowGeometry { window: u32, resized: bool },
    PointerButton { button: u8, pressed: bool },
    /// Relative motion of the input device, before it moves the cursor.
    RawMotion { dx: f64, dy: f64 },
}

/// Why a client's requests are on hold.
#[derive(Clone, Debug, PartialEq)]
pub enum Blocked {
    /// SYNC AwaitFence: until one of the fences is triggered (or one is gone: an error).
    Fences { ids: Vec<u32>, major: u8, minor: u8 },
    /// DRI3 PixmapFromBuffer: until the buffer has been received (on a worker thread, so that
    /// the wait does not hold up the server).
    Import { token: u64 },
}

impl Server {
    // ------------------------------------------------------------------ hooks

    /// Runs `f` on the handler of the extension `major`, which is out of `ext_handlers` meanwhile.
    /// `None`: there is no such extension. A panic inside the handler is caught and reported as
    /// `Some(Err(()))`; the handler is put back in every case, so one failed request cannot make
    /// the extension vanish for everybody else.
    pub(crate) fn with_ext<R>(&mut self, major: u8, f: impl FnOnce(&mut dyn Extension, &mut Server) -> R) -> Option<Result<R, ()>> {
        let mut ext = self.ext_handlers.remove(&major)?;
        let r = catch_unwind(AssertUnwindSafe(|| f(&mut *ext, self)));
        self.ext_handlers.insert(major, ext);
        Some(match r {
            Ok(v) => Ok(v),
            Err(_) => {
                crate::log::error(&format!("extension {major}: handler panicked, request dropped"));
                Err(())
            }
        })
    }

    pub fn run_hook(&mut self, hook: Hook) {
        let majors: Vec<u8> = self.ext_handlers.keys().copied().collect();
        for m in majors {
            let _ = self.with_ext(m, |ext, s| ext.on_hook(s, &hook));
        }
    }

    /// Raw motion of an input source (a finger, a mouse), reported before the cursor moves.
    pub fn raw_motion(&mut self, dx: f64, dy: f64) {
        self.run_hook(Hook::RawMotion { dx, dy });
    }

    // ------------------------------------------------------------------ timers

    /// Calls `on_timer(token)` of the extension with major opcode `ext` at `deadline_ns`
    /// (the platform's monotonic clock).
    pub fn schedule(&mut self, ext: u8, deadline_ns: i64, token: u64) {
        self.timer_seq += 1;
        self.timers.push(std::cmp::Reverse((deadline_ns, self.timer_seq, ext, token)));
    }

    pub fn next_timer(&self) -> Option<i64> {
        self.timers.peek().map(|t| (t.0).0)
    }

    /// Runs the timers that are due at `now_ns`.
    pub fn run_timers(&mut self, now_ns: i64) {
        while let Some(&std::cmp::Reverse((deadline, _, ext, token))) = self.timers.peek() {
            if deadline > now_ns {
                break;
            }
            self.timers.pop();
            let _ = self.with_ext(ext, |handler, s| handler.on_timer(s, token));
        }
        self.service_wakes();
    }

    // ------------------------------------------------------------------ file descriptors

    /// Like [`Server::feed`], with descriptors that arrived with the bytes (SCM_RIGHTS).
    pub fn feed_fds(&mut self, client: ClientId, data: &[u8], fds: Vec<RawFd>) {
        match self.client_mut(client) {
            Some(c) => c.fds.extend(fds),
            None => {
                for fd in fds {
                    unsafe { libc::close(fd) };
                }
                return;
            }
        }
        self.feed(client, data);
    }

    /// The next descriptor received from the client, in arrival order. The caller owns it.
    pub fn take_fd(&mut self, client: ClientId) -> Option<RawFd> {
        self.client_mut(client)?.fds.pop_front()
    }

    // ------------------------------------------------------------------ buffer imports

    /// What worker threads call to wake the I/O thread (set by the runtime).
    pub fn set_waker(&mut self, waker: Option<Wake>) {
        self.waker = waker;
    }

    /// Receives the buffer on `fd` without holding the server up: the platform does the waiting
    /// on its own thread, and the client is on hold (its later requests stay queued, so nothing
    /// can see the pixmap before it exists) until the result is collected by `service_wakes`.
    pub(crate) fn start_import(&mut self, fd: RawFd, job: ImportJob) {
        self.import_seq += 1;
        let token = self.import_seq;
        let client = job.client;
        self.import_jobs.insert(token, job);
        let results = self.import_results.clone();
        let waker = self.waker.clone();
        self.platform.import_buffer_async(
            fd,
            Box::new(move |r| {
                results.put(token, r);
                if let Some(w) = waker {
                    w();
                }
            }),
        );
        self.block_client(client, Blocked::Import { token });
    }

    // ------------------------------------------------------------------ waiting clients

    /// Puts the client's requests on hold (the request being handled is finished).
    pub fn block_client(&mut self, client: ClientId, why: Blocked) {
        if let Some(c) = self.client_mut(client) {
            c.blocked = Some(why);
        }
    }

    /// Stops reading the client's socket until `until_ns`: the kernel buffer fills and the
    /// client's own writes block (frame pacing of MIT-SHM games).
    pub fn pause_reads(&mut self, client: ClientId, until_ns: i64) {
        if let Some(c) = self.client_mut(client) {
            c.pause_until_ns = until_ns;
        }
    }

    /// Whether the client's socket must not be read now (MIT-SHM frame pacing). The pause ends
    /// by itself: an expired one is cleared here.
    pub fn reads_paused(&mut self, client: ClientId) -> bool {
        let now = self.platform.now_ns();
        match self.client_mut(client) {
            Some(c) if c.pause_until_ns > now => true,
            Some(c) => {
                c.pause_until_ns = 0;
                false
            }
            None => false,
        }
    }

    /// The earliest moment the I/O thread must wake up for: a timer or the end of a read pause
    /// (platform clock, ns).
    pub fn next_deadline(&self) -> Option<i64> {
        let pause = self.clients.iter().flatten().map(|c| c.pause_until_ns).filter(|t| *t > 0).min();
        match (self.next_timer(), pause) {
            (Some(a), Some(b)) => Some(a.min(b)),
            (a, b) => a.or(b),
        }
    }

    /// Wakes the clients whose condition is met and processes what they had queued. Called at the
    /// end of every top-level operation, when no extension handler is out of the table.
    pub fn service_wakes(&mut self) {
        if self.in_wakes {
            return;
        }
        self.in_wakes = true;
        // Results of clients that left meanwhile are of no use to anybody.
        self.import_results.retain(|t| self.import_jobs.contains_key(&t));
        loop {
            let ready: Vec<(ClientId, Blocked)> = self
                .clients
                .iter()
                .flatten()
                .filter_map(|c| c.blocked.clone().map(|b| (c.id, b)))
                .filter(|(_, b)| self.wake_ready(b))
                .collect();
            if ready.is_empty() {
                break;
            }
            for (id, why) in ready {
                if let Some(c) = self.client_mut(id) {
                    c.blocked = None;
                }
                self.finish_blocked(id, &why);
                self.feed(id, &[]);
            }
        }
        self.in_wakes = false;
    }

    fn wake_ready(&self, b: &Blocked) -> bool {
        match b {
            Blocked::Fences { ids, .. } => ids.iter().any(|id| self.sync.fence_state(*id) != Some(false)),
            Blocked::Import { token } => self.import_results.has(*token),
        }
    }

    /// The end of a held request: an error if a fence disappeared, nothing if one triggered.
    fn finish_blocked(&mut self, client: ClientId, why: &Blocked) {
        match why {
            Blocked::Import { token } => {
                let Some(job) = self.import_jobs.remove(token) else { return };
                let store = self.import_results.take(*token);
                super::ext::dri3::finish_import(self, job, store);
            }
            Blocked::Fences { ids, major, minor } => {
                for id in ids {
                    match self.sync.fence_state(*id) {
                        None => {
                            let code = self.sync.first_error() + 2;
                            self.send_error(client, code, *id, *minor, *major);
                            return;
                        }
                        Some(true) => return,
                        Some(false) => {}
                    }
                }
            }
        }
    }
}
