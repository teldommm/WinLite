//! Threaded runtime of the protocol core: the [`Server`] behind one lock, a listening Unix
//! socket and an I/O thread. Shared by the Android bridge and the host test server.
//!
//! * Clients are served on the I/O thread (`poll`): bytes in -> [`Server::feed`], output out.
//! * Any other thread (UI, input, renderer) calls [`Runtime::with`]: it runs a closure under the
//!   server lock; afterwards the notifications it produced are processed, so replies and events
//!   reach the sockets without the caller knowing about connections.
//! * Notifications that are not about sockets (window created, content updated, ...) are queued
//!   for the owner ([`Runtime::drain_events`]); `on_events` fires when the queue becomes non-empty
//!   so a consumer can sleep instead of polling. No callback runs under the server lock.
//! * A socket that is full does not block anyone: the rest stays queued and the I/O thread waits
//!   for POLLOUT. A client that stops reading is cut off when its queue passes [`MAX_PENDING`].

use crate::log;
use crate::proto::{ClientId, Notify, Server};
use std::io::{self, Write};
use std::os::unix::io::{AsRawFd, FromRawFd, RawFd};
use std::os::unix::net::{UnixListener, UnixStream};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::thread::JoinHandle;

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
}

type Wakeup = Box<dyn Fn() + Send + Sync>;

struct Shared {
    state: Mutex<State>,
    events: Mutex<Vec<Notify>>,
    on_events: Mutex<Option<Wakeup>>,
    stop: AtomicBool,
    /// Self-pipe: written to wake the I/O thread (`[read, write]`).
    wake: [RawFd; 2],
}

pub struct Runtime {
    shared: Arc<Shared>,
    thread: Mutex<Option<JoinHandle<()>>>,
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
            state: Mutex::new(State { server, conns: Vec::new() }),
            events: Mutex::new(Vec::new()),
            on_events: Mutex::new(None),
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
        let t = shared.clone();
        let thread = std::thread::Builder::new().name("xcore-io".into()).spawn(move || io_loop(t, listener))?;
        Ok(Runtime { shared, thread: Mutex::new(Some(thread)), path: path.to_string() })
    }

    /// Runs `f` on the server, then delivers whatever it produced (replies, events).
    pub fn with<R>(&self, f: impl FnOnce(&mut Server) -> R) -> R {
        let (r, new_events, pending, deadline_changed) = {
            let mut st = lock(&self.shared.state);
            let before = st.server.next_deadline();
            let r = f(&mut st.server);
            st.server.service_wakes();
            let (new_events, pending) = pump(&self.shared, &mut st);
            (r, new_events, pending, st.server.next_deadline() != before)
        };
        // Also wakes the I/O thread: `f` may have moved the earliest timer.
        if pending || deadline_changed {
            self.shared.wake();
        }
        if new_events {
            fire(&self.shared);
        }
        r
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
        let mut st = lock(&self.shared.state);
        let ids: Vec<ClientId> = st.conns.iter().map(|c| c.id).collect();
        for id in ids {
            close_conn(&mut st, id);
        }
        let _ = std::fs::remove_file(&self.path);
    }
}

impl Drop for Runtime {
    fn drop(&mut self) {
        self.stop();
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

/// Processes the server's notifications until there are none: output to sockets, kills, and
/// the rest to the event queue. Returns (events were queued, some socket has data waiting).
fn pump(shared: &Shared, st: &mut State) -> (bool, bool) {
    let mut queued = false;
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
                other => rest.push(other),
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
    (queued, st.conns.iter().any(|c| !c.out.is_empty()))
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
            let State { server, conns } = &mut *st;
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

        let (queued, pending) = {
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
                let State { server, conns } = &mut *st;
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
            (r.0 || r2.0, r2.1)
        };
        let _ = pending; // the next loop iteration asks for POLLOUT by itself
        if queued {
            fire(&shared);
        }
    }
}

#[cfg(test)]
mod tests {
    use std::io::Read;
    use super::*;
    use crate::host::new_server;
    use std::sync::atomic::AtomicUsize;
    use std::time::{Duration, Instant};

    fn socket_path(tag: &str) -> String {
        format!("/tmp/xcore-rt-{}-{}", std::process::id(), tag)
    }

    fn wait_for(what: &str, mut cond: impl FnMut() -> bool) {
        let t = Instant::now();
        while !cond() {
            assert!(t.elapsed() < Duration::from_secs(10), "timeout waiting for {what}");
            std::thread::sleep(Duration::from_millis(5));
        }
    }

    fn connect(path: &str) -> UnixStream {
        let mut s = UnixStream::connect(path).unwrap();
        s.write_all(&[b'l', 0, 11, 0, 0, 0, 0, 0, 0, 0, 0, 0]).unwrap();
        let mut head = [0u8; 8];
        s.read_exact(&mut head).unwrap();
        assert_eq!(head[0], 1, "setup accepted");
        let words = u16::from_le_bytes([head[6], head[7]]) as usize;
        let mut rest = vec![0u8; words * 4];
        s.read_exact(&mut rest).unwrap();
        s
    }

    /// InternAtom("WM_NAME") request, 16 bytes.
    fn intern_wm_name() -> Vec<u8> {
        let mut r = vec![16, 0, 4, 0, 7, 0, 0, 0];
        r.extend_from_slice(b"WM_NAME\0");
        r
    }

    #[test]
    fn serves_a_client_and_delivers_events() {
        let path = socket_path("basic");
        let rt = Runtime::start(&path, new_server(640, 480)).unwrap();
        let wakes = Arc::new(AtomicUsize::new(0));
        let w = wakes.clone();
        rt.set_on_events(Some(Box::new(move || {
            w.fetch_add(1, Ordering::SeqCst);
        })));

        let mut c = connect(&path);
        c.write_all(&intern_wm_name()).unwrap();
        let mut reply = [0u8; 32];
        c.read_exact(&mut reply).unwrap();
        assert_eq!(reply[0], 1);
        assert_eq!(u32::from_le_bytes([reply[8], reply[9], reply[10], reply[11]]), 39); // WM_NAME

        rt.with(|s| s.set_pointer_position(5, 6));
        wait_for("event wake-up", || wakes.load(Ordering::SeqCst) > 0);
        let ev = rt.drain_events();
        assert!(ev.iter().any(|e| matches!(e, Notify::PointerMove { x: 5, y: 6 })), "{ev:?}");
        assert!(rt.drain_events().is_empty());

        drop(c);
        wait_for("disconnect", || rt.connection_count() == 0);
        rt.stop();
        assert!(!std::path::Path::new(&path).exists());
        rt.stop(); // idempotent
    }

    #[test]
    fn a_client_that_reads_late_still_gets_every_reply() {
        let path = socket_path("backlog");
        let rt = Runtime::start(&path, new_server(640, 480)).unwrap();
        let mut c = connect(&path);
        const N: usize = 40_000; // 1.28 MB of replies: far more than a socket buffer holds
        let req = intern_wm_name();
        let mut all = Vec::with_capacity(N * req.len());
        for _ in 0..N {
            all.extend_from_slice(&req);
        }
        let mut writer = c.try_clone().unwrap();
        let t = std::thread::spawn(move || writer.write_all(&all).unwrap());
        std::thread::sleep(Duration::from_millis(300)); // do not read yet
        let mut got = 0usize;
        let mut buf = vec![0u8; 1 << 16];
        c.set_read_timeout(Some(Duration::from_secs(10))).unwrap();
        while got < N * 32 {
            let n = c.read(&mut buf).unwrap();
            assert!(n > 0);
            got += n;
        }
        assert_eq!(got, N * 32);
        t.join().unwrap();
        rt.stop();
    }

    #[test]
    fn a_protocol_violation_closes_only_that_client() {
        let path = socket_path("kill");
        let rt = Runtime::start(&path, new_server(640, 480)).unwrap();
        let mut good = connect(&path);
        let mut bad = UnixStream::connect(&path).unwrap();
        bad.write_all(&[b'l', 0, 12, 0, 0, 0, 0, 0, 0, 0, 0, 0]).unwrap(); // protocol version 12
        bad.set_read_timeout(Some(Duration::from_secs(10))).unwrap();
        let mut b = [0u8; 16];
        // Either an explicit failure reply followed by EOF, or plain EOF.
        loop {
            match bad.read(&mut b) {
                Ok(0) | Err(_) => break,
                Ok(_) => continue,
            }
        }
        good.write_all(&intern_wm_name()).unwrap();
        let mut reply = [0u8; 32];
        good.read_exact(&mut reply).unwrap();
        assert_eq!(reply[0], 1);
        wait_for("bad connection removed", || rt.connection_count() == 1);
        rt.stop();
    }

    /// Sends `data` and one descriptor in a single `sendmsg`.
    fn send_with_fd(sock: &UnixStream, data: &[u8], fd: RawFd) {
        let mut ctrl = [0u64; 4];
        let mut iov = libc::iovec { iov_base: data.as_ptr() as *mut libc::c_void, iov_len: data.len() };
        let mut msg: libc::msghdr = unsafe { std::mem::zeroed() };
        msg.msg_iov = &mut iov;
        msg.msg_iovlen = 1;
        msg.msg_control = ctrl.as_mut_ptr() as *mut libc::c_void;
        msg.msg_controllen = unsafe { libc::CMSG_SPACE(4) } as _;
        unsafe {
            let cmsg = libc::CMSG_FIRSTHDR(&msg);
            (*cmsg).cmsg_level = libc::SOL_SOCKET;
            (*cmsg).cmsg_type = libc::SCM_RIGHTS;
            (*cmsg).cmsg_len = libc::CMSG_LEN(4) as _;
            std::ptr::write_unaligned(libc::CMSG_DATA(cmsg) as *mut RawFd, fd);
            assert_eq!(libc::sendmsg(sock.as_raw_fd(), &msg, 0), data.len() as isize);
        }
    }

    #[test]
    fn descriptors_travel_with_the_request() {
        let path = socket_path("fds");
        let rt = Runtime::start(&path, new_server(640, 480)).unwrap();
        let mut c = connect(&path);
        // CreateWindow(id = resource base | 1, root), then DRI3 PixmapFromBuffer with a descriptor.
        let (base, root) = rt.with(|s| (s.clients.iter().flatten().next().unwrap().resource_base, s.root));
        let w = base | 1;
        let mut cw = vec![1u8, 0, 8, 0];
        for v in [w, root] {
            cw.extend(v.to_le_bytes());
        }
        cw.extend([0, 0, 0, 0, 8, 0, 8, 0, 0, 0, 1, 0]);
        cw.extend(0u32.to_le_bytes()); // visual
        cw.extend(0u32.to_le_bytes()); // mask
        c.write_all(&cw).unwrap();
        let mut b = vec![154u8, 2, 6, 0];
        for v in [w | 2, w, 64u32] {
            b.extend(v.to_le_bytes());
        }
        b.extend([8, 0, 8, 0, 32, 0, 24, 32]);
        let mut fds = [0 as RawFd; 2];
        assert_eq!(unsafe { libc::pipe(fds.as_mut_ptr()) }, 0);
        send_with_fd(&c, &b, fds[0]);
        unsafe {
            libc::close(fds[0]);
            libc::close(fds[1]);
        }
        // The bare test server cannot import a buffer: BadAlloc proves the descriptor arrived
        // (without one the request is dropped and nothing comes back).
        c.set_read_timeout(Some(Duration::from_secs(10))).unwrap();
        let mut e = [0u8; 32];
        c.read_exact(&mut e).unwrap();
        assert_eq!((e[0], e[1]), (0, 11), "{e:?}");
        rt.stop();
    }

    struct Ticker(Arc<AtomicUsize>);

    impl crate::proto::requests::Extension for Ticker {
        fn name(&self) -> &str {
            "TICKER"
        }
        fn major_opcode(&self) -> u8 {
            200
        }
        fn handle(&mut self, _: &mut Server, _: ClientId, _: u8, _: &[u8]) -> crate::proto::Res {
            Ok(())
        }
        fn on_timer(&mut self, _: &mut Server, token: u64) {
            self.0.store(token as usize, Ordering::SeqCst);
        }
    }

    #[test]
    fn timers_fire_while_the_server_is_idle() {
        let path = socket_path("timer");
        let rt = Runtime::start(&path, new_server(640, 480)).unwrap();
        let hit = Arc::new(AtomicUsize::new(0));
        let h = hit.clone();
        std::thread::sleep(Duration::from_millis(100)); // the I/O thread sleeps in poll
        let t0 = Instant::now();
        rt.with(|s| {
            s.register_extension(Box::new(Ticker(h)));
            let now = s.platform.now_ns();
            s.schedule(200, now + 80_000_000, 7);
        });
        wait_for("timer", || hit.load(Ordering::SeqCst) == 7);
        assert!(t0.elapsed() >= Duration::from_millis(80), "fired early: {:?}", t0.elapsed());
        rt.stop();
    }

    #[test]
    fn a_paused_client_is_not_read() {
        let path = socket_path("pause");
        let rt = Runtime::start(&path, new_server(640, 480)).unwrap();
        let mut c = connect(&path);
        let t0 = Instant::now();
        rt.with(|s| {
            let id = s.clients.iter().flatten().next().unwrap().id;
            let now = s.platform.now_ns();
            s.pause_reads(id, now + 300_000_000);
        });
        c.write_all(&intern_wm_name()).unwrap();
        c.set_read_timeout(Some(Duration::from_millis(100))).unwrap();
        let mut reply = [0u8; 32];
        assert!(c.read_exact(&mut reply).is_err(), "answered while paused");
        c.set_read_timeout(Some(Duration::from_secs(10))).unwrap();
        c.read_exact(&mut reply).unwrap();
        assert_eq!(reply[0], 1);
        assert!(t0.elapsed() >= Duration::from_millis(300), "{:?}", t0.elapsed());
        rt.stop();
    }
}
