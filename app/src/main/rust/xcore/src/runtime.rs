//! Threaded runtime of the protocol core: the [`Server`] behind one lock, a listening Unix
//! socket and an I/O thread. Used by the Android bridge.
//!
//! * Clients are served on the I/O thread (`poll`): bytes in -> [`Server::feed`], output out.
//! * Any other thread (UI, input, renderer) calls [`Runtime::with`]: it runs a closure under the
//!   server lock; afterwards the notifications it produced are processed, so replies and events
//!   reach the sockets without the caller knowing about connections.
//! * Notifications that are not about sockets (window created, content updated, ...) are queued
//!   for the owner ([`Runtime::drain_events`]); `on_events` fires after every turn (I/O turn or
//!   [`Runtime::with`] call) that queued some, so a consumer can sleep instead of polling. No
//!   callback runs under the server lock.
//! * With a frame output ([`Runtime::set_output`]), frames bypass the owner: the
//!   [`frames::Router`] queues them under the server lock, and the frame thread (`xcore-frames`)
//!   hands them to the output, in server order. Whoever queued frames only wakes that thread: a
//!   renderer that is busy (it waits for the GPU under its own lock when the cursor changes size
//!   or a filter is switched, and imports new buffers under it) holds up the frames, never the
//!   I/O thread, the clients or input. The owner only gets a periodic count
//!   ([`Notify::ContentFrames`]).
//! * A socket that is full does not block anyone: the rest stays queued and the I/O thread waits
//!   for POLLOUT. A client that stops reading is cut off when its queue passes [`MAX_PENDING`].

use crate::frames::{Output, Router};
use crate::log;
use crate::proto::{ClientId, Notify, Server};
use std::io::{self, Write};
use std::os::unix::io::{AsRawFd, FromRawFd, RawFd};
use std::os::unix::net::{UnixListener, UnixStream};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::thread::JoinHandle;
use std::time::Instant;

/// `recvmsg` calls per client and poll turn (64 KiB each).
const MAX_READS_PER_TURN: usize = 16;

/// Bytes queued for one client before it is considered dead (a client that never reads).
const MAX_PENDING: usize = 256 << 20;

struct Conn {
    id: ClientId,
    stream: UnixStream,
    out: Vec<u8>,
}

struct State {
    server: Server,
    conns: Vec<Conn>,
    /// Frames for the renderer (see [`frames`]); `None`: frames go to the owner as events.
    router: Option<Router>,
}

type Wakeup = Box<dyn Fn() + Send + Sync>;

struct Shared {
    state: Mutex<State>,
    events: Mutex<Vec<Notify>>,
    on_events: Mutex<Option<Wakeup>>,
    /// Held while frames are handed to the output: one batch at a time, in queue order.
    delivery: Mutex<()>,
    /// Frames are waiting for the frame thread (set by whoever queued them).
    frames_kick: Mutex<bool>,
    frames_cv: Condvar,
    stop: AtomicBool,
    /// Self-pipe: written to wake the I/O thread (`[read, write]`).
    wake: [RawFd; 2],
}

pub struct Runtime {
    shared: Arc<Shared>,
    thread: Mutex<Option<JoinHandle<()>>>,
    frames_thread: Mutex<Option<JoinHandle<()>>>,
    path: String,
}

fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    // A panic in a handler must not turn into a dead server: the state is still usable.
    m.lock().unwrap_or_else(|e| e.into_inner())
}

impl Shared {
    fn wake(&self) {
        let b = 1u8;
        unsafe { libc::write(self.wake[1], &b as *const u8 as *const libc::c_void, 1) };
    }
}

impl Drop for Shared {
    fn drop(&mut self) {
        unsafe {
            libc::close(self.wake[0]);
            libc::close(self.wake[1]);
        }
    }
}

impl Runtime {
    /// Binds `path` and starts the I/O thread.
    pub fn start(path: &str, server: Server) -> io::Result<Runtime> {
        let fd = crate::sock::create_unix_listener(path.as_bytes());
        if fd < 0 {
            return Err(io::Error::last_os_error());
        }
        let listener = unsafe { UnixListener::from_raw_fd(fd) };
        listener.set_nonblocking(true)?;

        let mut pipe = [0 as RawFd; 2];
        if unsafe { libc::pipe2(pipe.as_mut_ptr(), libc::O_NONBLOCK | libc::O_CLOEXEC) } < 0 {
            return Err(io::Error::last_os_error());
        }
        let shared = Arc::new(Shared {
            state: Mutex::new(State { server, conns: Vec::new(), router: None }),
            events: Mutex::new(Vec::new()),
            on_events: Mutex::new(None),
            delivery: Mutex::new(()),
            frames_kick: Mutex::new(false),
            frames_cv: Condvar::new(),
            stop: AtomicBool::new(false),
            wake: pipe,
        });
        // Worker threads (DRI3 buffer imports) wake the I/O thread when they are done. They hold
        // only a weak reference: a late wake-up after `stop` must not touch a closed pipe.
        let weak = Arc::downgrade(&shared);
        lock(&shared.state).server.set_waker(Some(Arc::new(move || {
            if let Some(s) = weak.upgrade() {
                s.wake();
            }
        })));
        let f = shared.clone();
        let frames_thread = std::thread::Builder::new().name("xcore-frames".into()).spawn(move || frames_loop(f))?;
        let t = shared.clone();
        let thread = match std::thread::Builder::new().name("xcore-io".into()).spawn(move || io_loop(t, listener)) {
            Ok(t) => t,
            Err(e) => {
                shared.stop.store(true, Ordering::SeqCst);
                kick(&shared);
                let _ = frames_thread.join();
                return Err(e);
            }
        };
        Ok(Runtime {
            shared,
            thread: Mutex::new(Some(thread)),
            frames_thread: Mutex::new(Some(frames_thread)),
            path: path.to_string(),
        })
    }

    /// Runs `f` on the server, then delivers whatever it produced (replies, events).
    pub fn with<R>(&self, f: impl FnOnce(&mut Server) -> R) -> R {
        let (r, p, deadline_changed) = {
            let mut st = lock(&self.shared.state);
            let before = st.server.next_deadline();
            let r = f(&mut st.server);
            st.server.service_wakes();
            let p = pump(&self.shared, &mut st);
            (r, p, st.server.next_deadline() != before)
        };
        // Also wakes the I/O thread: `f` may have moved the earliest timer.
        if p.output_pending || deadline_changed {
            self.shared.wake();
        }
        if p.frames {
            kick(&self.shared);
        }
        if p.events {
            fire(&self.shared);
        }
        r
    }

    /// Frames go to `output` from now on (see [`frames`]); `None`: back to the owner's events.
    /// Frames still queued for a previous output are given back to it.
    pub fn set_output(&self, output: Option<Arc<dyn Output>>) {
        let old = {
            let mut st = lock(&self.shared.state);
            std::mem::replace(&mut st.router, output.map(Router::new))
        };
        // Outside the server lock: dropping a router delivers what it still holds.
        let _serial = lock(&self.shared.delivery);
        drop(old);
    }

    /// Gives the output the whole state again: window tree, contents, pointer, cursor (a new
    /// renderer has none of it).
    pub fn replay(&self) {
        self.with_router(|r, s| r.replay(s));
    }

    /// Queues `item` for the output, in order with what the server queued before it.
    pub fn push_item(&self, item: crate::frames::Item) {
        self.with_router(|r, _| r.push(item));
    }

    fn with_router(&self, f: impl FnOnce(&mut Router, &Server)) {
        let queued = {
            let mut st = lock(&self.shared.state);
            let State { server, router, .. } = &mut *st;
            match router.as_mut() {
                Some(r) => {
                    f(r, server);
                    r.has_pending()
                }
                None => false,
            }
        };
        if queued {
            kick(&self.shared);
        }
    }

    /// Sends the window's own content to the output again (a new renderer has none of it).
    pub fn refresh_content(&self, window: u32) {
        let queued = {
            let mut st = lock(&self.shared.state);
            let State { server, router, .. } = &mut *st;
            match router.as_mut() {
                Some(r) => {
                    r.show_content(server, window);
                    r.has_pending()
                }
                None => false,
            }
        };
        if queued {
            kick(&self.shared);
        }
    }

    /// Takes the queued notifications that are not about sockets.
    pub fn drain_events(&self) -> Vec<Notify> {
        std::mem::take(&mut *lock(&self.shared.events))
    }

    /// `f` is called (from an arbitrary thread, outside the server lock) when events were queued.
    pub fn set_on_events(&self, f: Option<Wakeup>) {
        *lock(&self.shared.on_events) = f;
    }

    pub fn connection_count(&self) -> usize {
        lock(&self.shared.state).conns.len()
    }

    /// Stops the I/O thread, closes every client and removes the socket file. Idempotent.
    pub fn stop(&self) {
        self.shared.stop.store(true, Ordering::SeqCst);
        self.shared.wake();
        if let Some(t) = lock(&self.thread).take() {
            let _ = t.join();
        }
        kick(&self.shared);
        if let Some(t) = lock(&self.frames_thread).take() {
            let _ = t.join();
        }
        let router = {
            let mut st = lock(&self.shared.state);
            let ids: Vec<ClientId> = st.conns.iter().map(|c| c.id).collect();
            for id in ids {
                close_conn(&mut st, id);
            }
            st.router.take()
        };
        // Frames still queued give their buffer references back (outside the server lock).
        {
            let _serial = lock(&self.shared.delivery);
            drop(router);
        }
        let _ = std::fs::remove_file(&self.path);
    }
}

impl Drop for Runtime {
    fn drop(&mut self) {
        self.stop();
    }
}

/// Wakes the frame thread: frames are queued. Never waits for the output.
fn kick(shared: &Shared) {
    *lock(&shared.frames_kick) = true;
    shared.frames_cv.notify_one();
}

/// The frame thread: delivers whenever frames were queued, until the runtime stops (frames left
/// then go back with the router, see `stop`).
fn frames_loop(shared: Arc<Shared>) {
    loop {
        {
            let mut kicked = lock(&shared.frames_kick);
            while !*kicked && !shared.stop.load(Ordering::SeqCst) {
                kicked = shared.frames_cv.wait(kicked).unwrap_or_else(|e| e.into_inner());
            }
            if shared.stop.load(Ordering::SeqCst) {
                return;
            }
            *kicked = false;
        }
        deliver(&shared);
    }
}

/// Hands the queued frames to the output (frame thread). Frames are queued under the server lock,
/// so taking the whole queue each time keeps them in server order; the server lock is only held
/// to take the queue, never while the output works.
fn deliver(shared: &Shared) {
    let _serial = lock(&shared.delivery);
    loop {
        let (output, items) = {
            let mut st = lock(&shared.state);
            match st.router.as_mut() {
                Some(r) if r.has_pending() => (r.output().clone(), r.take_pending()),
                _ => return,
            }
        };
        // A panic in the output may leak the references of this batch; it must not take the I/O
        // thread (or the caller) with it.
        if catch_unwind(AssertUnwindSafe(|| output.deliver(items))).is_err() {
            log::error("frame output panicked");
        }
    }
}

fn fire(shared: &Shared) {
    if let Some(f) = lock(&shared.on_events).as_ref() {
        f();
    }
}

fn close_conn(st: &mut State, id: ClientId) {
    if let Some(i) = st.conns.iter().position(|c| c.id == id) {
        st.conns.remove(i);
        st.server.client_disconnected(id);
    }
}

/// Writes as much of the queue as the socket takes. `false`: the connection is broken.
fn try_write(conn: &mut Conn) -> bool {
    while !conn.out.is_empty() {
        match conn.stream.write(&conn.out) {
            Ok(0) => return false,
            Ok(n) => {
                conn.out.drain(..n);
            }
            Err(e) if e.kind() == io::ErrorKind::WouldBlock => return true,
            Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
            Err(_) => return false,
        }
    }
    true
}

/// What a [`pump`] left to do once the server lock is released.
#[derive(Clone, Copy, Default)]
struct Pumped {
    /// Events were queued for the owner.
    events: bool,
    /// This pump queued frames for the output: wake the frame thread.
    frames: bool,
    /// Some socket has data waiting.
    output_pending: bool,
}

/// Processes the server's notifications until there are none: output to sockets, kills, frames
/// to the router, and the rest to the event queue.
fn pump(shared: &Shared, st: &mut State) -> Pumped {
    let mut queued = false;
    let now = Instant::now();
    let frames_before = st.router.as_ref().map(|r| r.pending_len()).unwrap_or(0);
    loop {
        let notes = std::mem::take(&mut st.server.notifications);
        if notes.is_empty() {
            break;
        }
        let mut output: Vec<ClientId> = Vec::new();
        let mut kill: Vec<ClientId> = Vec::new();
        let mut rest: Vec<Notify> = Vec::new();
        for n in notes {
            match n {
                Notify::Output(c) => {
                    if !output.contains(&c) {
                        output.push(c)
                    }
                }
                Notify::Kill(c) => kill.push(c),
                other => match st.router.as_mut() {
                    Some(r) => rest.extend(r.route(&st.server, other, now)),
                    None => rest.push(other),
                },
            }
        }
        for c in output {
            let data = st.server.take_output(c);
            if data.is_empty() {
                continue;
            }
            let Some(conn) = st.conns.iter_mut().find(|k| k.id == c) else { continue };
            conn.out.extend_from_slice(&data);
            if !try_write(conn) || conn.out.len() > MAX_PENDING {
                kill.push(c);
            }
        }
        for c in kill {
            close_conn(st, c);
        }
        if !rest.is_empty() {
            lock(&shared.events).extend(rest);
            queued = true;
        }
    }
    // The cursor, once the run is through; windows the router hid lose their input.
    let State { server, router, .. } = &mut *st;
    if let Some(r) = router.as_mut() {
        for w in r.finish(server) {
            crate::bridge::disable_subtree(server, w);
        }
    }
    Pumped {
        events: queued,
        frames: st.router.as_ref().map(|r| r.pending_len() > frames_before).unwrap_or(false),
        output_pending: st.conns.iter().any(|c| !c.out.is_empty()),
    }
}

/// One `recvmsg`: the bytes and the descriptors that came with them (SCM_RIGHTS, close-on-exec).
fn recv_with_fds(fd: RawFd, buf: &mut [u8]) -> io::Result<(usize, Vec<RawFd>)> {
    let mut ctrl = [0u64; 24]; // 192 bytes: CMSG_SPACE for up to 44 descriptors, 8-aligned
    let mut iov = libc::iovec { iov_base: buf.as_mut_ptr() as *mut libc::c_void, iov_len: buf.len() };
    let mut msg: libc::msghdr = unsafe { std::mem::zeroed() };
    msg.msg_iov = &mut iov;
    msg.msg_iovlen = 1;
    msg.msg_control = ctrl.as_mut_ptr() as *mut libc::c_void;
    msg.msg_controllen = std::mem::size_of_val(&ctrl) as _;
    let n = unsafe { libc::recvmsg(fd, &mut msg, libc::MSG_CMSG_CLOEXEC) };
    if n < 0 {
        return Err(io::Error::last_os_error());
    }
    if msg.msg_flags & libc::MSG_CTRUNC != 0 {
        // The kernel closed the descriptors that did not fit; the requests that expected them
        // will fail on their own ("request without a buffer").
        log::warn("recvmsg: ancillary data truncated, some descriptors were lost");
    }
    let mut fds = Vec::new();
    unsafe {
        let mut cmsg = libc::CMSG_FIRSTHDR(&msg);
        while !cmsg.is_null() {
            if (*cmsg).cmsg_level == libc::SOL_SOCKET && (*cmsg).cmsg_type == libc::SCM_RIGHTS {
                let data = libc::CMSG_DATA(cmsg) as *const RawFd;
                let count = ((*cmsg).cmsg_len as usize).saturating_sub(libc::CMSG_LEN(0) as usize) / std::mem::size_of::<RawFd>();
                for i in 0..count {
                    fds.push(std::ptr::read_unaligned(data.add(i)));
                }
            }
            cmsg = libc::CMSG_NXTHDR(&msg, cmsg);
        }
    }
    Ok((n as usize, fds))
}

fn poll_timeout_ms(server: &mut Server) -> i32 {
    match server.next_deadline() {
        None => -1,
        Some(deadline) => {
            let now = server.platform.now_ns();
            // Round up, so the deadline has passed when poll returns.
            ((deadline - now).max(0) + 999_999).saturating_div(1_000_000).min(i32::MAX as i64) as i32
        }
    }
}

fn io_loop(shared: Arc<Shared>, listener: UnixListener) {
    let mut buf = vec![0u8; 1 << 16];
    while !shared.stop.load(Ordering::SeqCst) {
        // Snapshot of what to wait for; the lock is not held while sleeping.
        let mut fds = vec![
            libc::pollfd { fd: shared.wake[0], events: libc::POLLIN, revents: 0 },
            libc::pollfd { fd: listener.as_raw_fd(), events: libc::POLLIN, revents: 0 },
        ];
        let (ids, timeout) = {
            let mut st = lock(&shared.state);
            let State { server, conns, .. } = &mut *st;
            for c in conns.iter() {
                // A paused client (MIT-SHM pacing) is not read: its own writes block instead.
                let read = if server.reads_paused(c.id) { 0 } else { libc::POLLIN };
                let events = read | if c.out.is_empty() { 0 } else { libc::POLLOUT };
                fds.push(libc::pollfd { fd: c.stream.as_raw_fd(), events, revents: 0 });
            }
            let timeout = poll_timeout_ms(server);
            (conns.iter().map(|c| c.id).collect::<Vec<ClientId>>(), timeout)
        };
        let n = unsafe { libc::poll(fds.as_mut_ptr(), fds.len() as libc::nfds_t, timeout) };
        if n < 0 {
            if io::Error::last_os_error().kind() == io::ErrorKind::Interrupted {
                continue;
            }
            log::error("poll failed, I/O thread exits");
            return;
        }
        if fds[0].revents != 0 {
            let mut sink = [0u8; 64];
            while unsafe { libc::read(shared.wake[0], sink.as_mut_ptr() as *mut libc::c_void, sink.len()) } > 0 {}
        }
        if shared.stop.load(Ordering::SeqCst) {
            return;
        }

        let p = {
            let mut st = lock(&shared.state);
            if fds[1].revents & libc::POLLIN != 0 {
                while let Ok((stream, _)) = listener.accept() {
                    if stream.set_nonblocking(true).is_err() {
                        continue;
                    }
                    match st.server.add_client() {
                        Some(id) => st.conns.push(Conn { id, stream, out: Vec::new() }),
                        None => log::warn("out of client id ranges, connection refused"),
                    }
                }
            }
            // Timers first (releases, timeouts), then the sockets.
            let now = st.server.platform.now_ns();
            if catch_unwind(AssertUnwindSafe(|| st.server.run_timers(now))).is_err() {
                log::error("timer handler panicked");
            }
            let mut dead: Vec<ClientId> = Vec::new();
            for (i, &id) in ids.iter().enumerate() {
                let rev = fds[i + 2].revents;
                if rev == 0 {
                    continue;
                }
                let State { server, conns, .. } = &mut *st;
                let Some(conn) = conns.iter_mut().find(|c| c.id == id) else { continue };
                if rev & libc::POLLOUT != 0 && !try_write(conn) {
                    dead.push(id);
                    continue;
                }
                if rev & (libc::POLLIN | libc::POLLHUP | libc::POLLERR) != 0 {
                    // At most MAX_READS_PER_TURN reads: a client that floods the socket must not
                    // keep the server lock (and every other client) for as long as it writes.
                    // poll() is level-triggered, so what is left is read on the next turn.
                    let mut reads = 0;
                    while reads < MAX_READS_PER_TURN {
                        reads += 1;
                        if server.reads_paused(id) {
                            break;
                        }
                        match recv_with_fds(conn.stream.as_raw_fd(), &mut buf) {
                            Ok((0, _)) => {
                                dead.push(id);
                                break;
                            }
                            Ok((n, fds)) => {
                                // A panic in a request handler costs that client its connection,
                                // not the whole server its I/O thread.
                                let fed = catch_unwind(AssertUnwindSafe(|| server.feed_fds(id, &buf[..n], fds)));
                                if fed.is_err() {
                                    log::error("request handler panicked, the client is disconnected");
                                    dead.push(id);
                                    break;
                                }
                            }
                            Err(e) if e.kind() == io::ErrorKind::WouldBlock => break,
                            Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
                            Err(_) => {
                                dead.push(id);
                                break;
                            }
                        }
                    }
                }
            }
            // Deliver replies first, so a client that sent a request and hung up still gets it.
            let r = pump(&shared, &mut st);
            for id in dead {
                close_conn(&mut st, id);
            }
            let r2 = pump(&shared, &mut st);
            // The next loop iteration asks for POLLOUT by itself.
            Pumped { events: r.events || r2.events, frames: r.frames || r2.frames, output_pending: r2.output_pending }
        };
        // Frames first: the renderer is the one waiting; the owner's events can come after.
        if p.frames {
            kick(&shared);
        }
        if p.events {
            fire(&shared);
        }
    }
}
