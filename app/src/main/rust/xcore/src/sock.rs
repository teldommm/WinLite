//! AF_UNIX sockets and the epoll dispatch loop (the port of `xconnector_epoll.c`).
//!
//! Pure libc: the JNI layer only supplies the callbacks into Java.

use std::mem;
use std::os::raw::c_void;
use std::ptr;

pub const MAX_EVENTS: usize = 10;
/// Most file descriptors accepted from one received message (SCM_RIGHTS).
pub const MAX_FDS: usize = 32;

#[inline]
fn errno() -> i32 {
    std::io::Error::last_os_error().raw_os_error().unwrap_or(0)
}

/// Listening AF_UNIX stream socket bound to `path` (an existing file there is replaced).
/// Returns the fd, or -1.
pub fn create_unix_listener(path: &[u8]) -> i32 {
    unsafe {
        let fd = libc::socket(libc::AF_UNIX, libc::SOCK_STREAM, 0);
        if fd < 0 {
            return -1;
        }
        let mut addr: libc::sockaddr_un = mem::zeroed();
        addr.sun_family = libc::AF_UNIX as libc::sa_family_t;
        // sun_path keeps room for the terminating NUL.
        let len = path.len().min(addr.sun_path.len() - 1);
        for (i, b) in path[..len].iter().enumerate() {
            addr.sun_path[i] = *b as libc::c_char;
        }
        let addr_len = (mem::size_of::<libc::sa_family_t>() + len) as libc::socklen_t;

        libc::unlink(addr.sun_path.as_ptr());
        if libc::bind(fd, &addr as *const _ as *const libc::sockaddr, addr_len) < 0
            || libc::listen(fd, MAX_EVENTS as i32) < 0
        {
            libc::close(fd);
            return -1;
        }
        fd
    }
}

pub fn epoll_create() -> i32 {
    unsafe { libc::epoll_create1(0) }
}

pub fn eventfd_create() -> i32 {
    unsafe { libc::eventfd(0, libc::EFD_NONBLOCK) }
}

pub fn epoll_add(epfd: i32, fd: i32) -> bool {
    let mut ev = libc::epoll_event { events: libc::EPOLLIN as u32, u64: fd as u32 as u64 };
    unsafe { libc::epoll_ctl(epfd, libc::EPOLL_CTL_ADD, fd, &mut ev) >= 0 }
}

pub fn epoll_del(epfd: i32, fd: i32) {
    unsafe { libc::epoll_ctl(epfd, libc::EPOLL_CTL_DEL, fd, ptr::null_mut()) };
}

pub fn close_fd(fd: i32) {
    unsafe { libc::close(fd) };
}

/// What `dispatch_once` found.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Event {
    /// A connection was accepted (and, if asked, added to the epoll set).
    NewConnection(i32),
    /// The fd is readable, hung up or failed.
    Readable(i32),
}

/// One `epoll_wait` and the dispatch of what it returned.
///
/// * the listening socket: accept, then (`add_client`) add the new fd to the epoll set, then
///   `NewConnection(fd)`;
/// * any other fd readable, hung up or failed: `Readable(fd)`. A hang-up can be reported
///   without EPOLLIN; nobody reading it made level-triggered epoll return it again at once and
///   spin the thread, so it is handed on like data (the read sees EOF / the error).
///
/// The handler returns `false` to stop (a Java exception is pending). Returns `false` to end
/// the loop: the handler asked for it or `epoll_wait` failed. A signal (EINTR) is not a failure.
pub fn dispatch_once(
    epfd: i32,
    server_fd: i32,
    add_client: bool,
    handler: &mut dyn FnMut(Event) -> bool,
) -> bool {
    let mut events = [libc::epoll_event { events: 0, u64: 0 }; MAX_EVENTS];
    let n = unsafe { libc::epoll_wait(epfd, events.as_mut_ptr(), MAX_EVENTS as i32, -1) };
    if n < 0 {
        return errno() == libc::EINTR;
    }
    for i in 0..n as usize {
        let ev = events[i]; // packed on some targets: copy out, never borrow a field
        let (flags, fd) = (ev.events, ev.u64 as u32 as i32);
        if fd == server_fd {
            let client = unsafe { libc::accept(server_fd, ptr::null_mut(), ptr::null_mut()) };
            if client >= 0 {
                if add_client && !epoll_add(epfd, client) {
                    close_fd(client); // not served: do not leak it
                } else if !handler(Event::NewConnection(client)) {
                    return false;
                }
            }
        } else if flags & (libc::EPOLLIN | libc::EPOLLHUP | libc::EPOLLERR) as u32 != 0 {
            if !handler(Event::Readable(fd)) {
                return false;
            }
        }
    }
    true
}

/// Blocks until `client_fd` has something to read or `shutdown_fd` is signalled.
pub enum Wait {
    /// Shut down (shutdown fd signalled) or the socket is invalid: stop the client's thread.
    Stop,
    /// Interrupted by a signal: wait again.
    Retry,
    /// The client socket needs its read handler (data, hang-up or error).
    Ready,
    /// Nothing for the handler (spurious wake-up).
    Idle,
}

pub fn wait_for_socket_read(client_fd: i32, shutdown_fd: i32) -> Wait {
    let mut pfds = [
        libc::pollfd { fd: client_fd, events: libc::POLLIN, revents: 0 },
        libc::pollfd { fd: shutdown_fd, events: libc::POLLIN, revents: 0 },
    ];
    let res = unsafe { libc::poll(pfds.as_mut_ptr(), 2, -1) };
    if res < 0 {
        return if errno() == libc::EINTR { Wait::Retry } else { Wait::Stop };
    }
    if pfds[1].revents & libc::POLLIN != 0 || pfds[0].revents & libc::POLLNVAL != 0 {
        return Wait::Stop;
    }
    if pfds[0].revents & (libc::POLLIN | libc::POLLHUP | libc::POLLERR) != 0 {
        Wait::Ready
    } else {
        Wait::Idle
    }
}

/// `read(2)`; -1 on error (errno is not reported: the Java side treats <0 as a dead socket).
///
/// # Safety
/// `buf` writable for `len` bytes.
pub unsafe fn read(fd: i32, buf: *mut u8, len: usize) -> isize {
    libc::read(fd, buf as *mut c_void, len)
}

/// # Safety
/// `buf` readable for `len` bytes.
pub unsafe fn write(fd: i32, buf: *const u8, len: usize) -> isize {
    libc::write(fd, buf as *const c_void, len)
}

/// Sends without ever blocking: bytes written, or `-errno` (`-EAGAIN`: the client stopped
/// reading and its socket is full).
///
/// # Safety
/// `buf` readable for `len` bytes.
pub unsafe fn write_nonblocking(fd: i32, buf: *const u8, len: usize) -> isize {
    let n = libc::send(fd, buf as *const c_void, len, libc::MSG_DONTWAIT | libc::MSG_NOSIGNAL);
    if n >= 0 {
        n
    } else {
        -(errno() as isize)
    }
}

const CTRL_BYTES: usize = 16 + MAX_FDS * 4; // CMSG_SPACE(MAX_FDS ints) on 64-bit
const CTRL_WORDS: usize = (CTRL_BYTES + 7) / 8;

/// `recvmsg` collecting SCM_RIGHTS descriptors into `fds`. Returns (bytes received or -1,
/// number of descriptors stored).
///
/// # Safety
/// `buf` writable for `len` bytes.
pub unsafe fn recv_with_fds(fd: i32, buf: *mut u8, len: usize, fds: &mut [i32; MAX_FDS]) -> (isize, usize) {
    let mut iov = libc::iovec { iov_base: buf as *mut c_void, iov_len: len };
    let mut ctrl = [0u64; CTRL_WORDS]; // 8-aligned for cmsghdr
    let mut msg: libc::msghdr = mem::zeroed();
    msg.msg_iov = &mut iov;
    msg.msg_iovlen = 1;
    msg.msg_control = ctrl.as_mut_ptr() as *mut c_void;
    msg.msg_controllen = (CTRL_WORDS * 8) as _;

    let size = libc::recvmsg(fd, &mut msg, 0);
    let mut count = 0;
    if size >= 0 {
        let mut cmsg = libc::CMSG_FIRSTHDR(&msg);
        while !cmsg.is_null() {
            if (*cmsg).cmsg_level == libc::SOL_SOCKET && (*cmsg).cmsg_type == libc::SCM_RIGHTS {
                let data_len = (*cmsg).cmsg_len as usize - libc::CMSG_LEN(0) as usize;
                let n = (data_len / mem::size_of::<i32>()).min(MAX_FDS - count);
                let data = libc::CMSG_DATA(cmsg) as *const i32;
                for i in 0..n {
                    fds[count + i] = ptr::read_unaligned(data.add(i));
                }
                count += n;
            }
            cmsg = libc::CMSG_NXTHDR(&msg, cmsg);
        }
    }
    (size, count)
}

/// `sendmsg` of `len` bytes with one descriptor attached (SCM_RIGHTS). Bytes sent or -1.
///
/// # Safety
/// `buf` readable for `len` bytes.
pub unsafe fn send_with_fd(fd: i32, buf: *const u8, len: usize, anc_fd: i32) -> isize {
    let mut iov = libc::iovec { iov_base: buf as *mut c_void, iov_len: len };
    let mut ctrl = [0u64; 4]; // CMSG_SPACE(4) = 24 bytes
    let mut msg: libc::msghdr = mem::zeroed();
    msg.msg_iov = &mut iov;
    msg.msg_iovlen = 1;
    msg.msg_control = ctrl.as_mut_ptr() as *mut c_void;
    msg.msg_controllen = libc::CMSG_SPACE(mem::size_of::<i32>() as u32) as _;

    let cmsg = libc::CMSG_FIRSTHDR(&msg);
    (*cmsg).cmsg_level = libc::SOL_SOCKET;
    (*cmsg).cmsg_type = libc::SCM_RIGHTS;
    (*cmsg).cmsg_len = libc::CMSG_LEN(mem::size_of::<i32>() as u32) as _;
    ptr::write_unaligned(libc::CMSG_DATA(cmsg) as *mut i32, anc_fd);

    libc::sendmsg(fd, &msg, 0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::ffi::CString;

    fn socketpair() -> (i32, i32) {
        let mut sv = [0i32; 2];
        assert_eq!(unsafe { libc::socketpair(libc::AF_UNIX, libc::SOCK_STREAM, 0, sv.as_mut_ptr()) }, 0);
        (sv[0], sv[1])
    }

    fn temp_path(tag: &str) -> Vec<u8> {
        format!("/tmp/xcore-test-{}-{}.sock", std::process::id(), tag).into_bytes()
    }

    #[test]
    fn listener_accepts_and_dispatch_routes_events() {
        let path = temp_path("dispatch");
        let server = create_unix_listener(&path);
        assert!(server >= 0);
        let epfd = epoll_create();
        assert!(epfd >= 0 && epoll_add(epfd, server));

        // Client connects; one dispatch accepts it and reports it.
        let client = unsafe {
            let fd = libc::socket(libc::AF_UNIX, libc::SOCK_STREAM, 0);
            let mut addr: libc::sockaddr_un = mem::zeroed();
            addr.sun_family = libc::AF_UNIX as _;
            for (i, b) in path.iter().enumerate() {
                addr.sun_path[i] = *b as libc::c_char;
            }
            let len = (mem::size_of::<libc::sa_family_t>() + path.len()) as u32;
            assert_eq!(libc::connect(fd, &addr as *const _ as *const libc::sockaddr, len), 0);
            fd
        };
        let mut new_fds = vec![];
        let ok = dispatch_once(epfd, server, true, &mut |e| match e {
            Event::NewConnection(fd) => { new_fds.push(fd); true }
            other => panic!("unexpected {other:?}"),
        });
        assert!(ok);
        assert_eq!(new_fds.len(), 1);

        // Data on the accepted socket goes to on_existing, and the data is still there to read.
        let msg = b"hello";
        assert_eq!(unsafe { write(client, msg.as_ptr(), msg.len()) }, 5);
        let mut seen = vec![];
        let ok = dispatch_once(epfd, server, true, &mut |e| match e {
            Event::Readable(fd) => { seen.push(fd); true }
            other => panic!("unexpected {other:?}"),
        });
        assert!(ok);
        assert_eq!(seen, new_fds);
        let mut buf = [0u8; 8];
        assert_eq!(unsafe { read(new_fds[0], buf.as_mut_ptr(), 8) }, 5);
        assert_eq!(&buf[..5], msg);

        // Hang-up is handed on too (no EPOLLIN needed), and a callback can stop the loop.
        close_fd(client);
        let mut hung = 0;
        let ok = dispatch_once(epfd, server, true, &mut |_| { hung += 1; false });
        assert!(!ok);
        assert_eq!(hung, 1);

        // A re-created path replaces the old socket file.
        close_fd(new_fds[0]);
        close_fd(server);
        let server2 = create_unix_listener(&path);
        assert!(server2 >= 0);
        close_fd(server2);
        close_fd(epfd);
        unsafe { libc::unlink(CString::new(path).unwrap().as_ptr()) };
    }

    #[test]
    fn nonblocking_write_reports_eagain_when_full() {
        let (a, b) = socketpair();
        let chunk = vec![0u8; 1 << 16];
        let mut total = 0isize;
        let err = loop {
            let n = unsafe { write_nonblocking(a, chunk.as_ptr(), chunk.len()) };
            if n < 0 {
                break -n;
            }
            total += n;
            assert!(total < (1 << 30), "socket never filled up");
        };
        assert_eq!(err as i32, libc::EAGAIN);
        close_fd(a);
        close_fd(b);
    }

    #[test]
    fn nonblocking_write_to_closed_peer_is_an_error_not_a_signal() {
        let (a, b) = socketpair();
        close_fd(b);
        let n = unsafe { write_nonblocking(a, b"x".as_ptr(), 1) };
        assert_eq!(n, -(libc::EPIPE as isize)); // MSG_NOSIGNAL: no SIGPIPE
        close_fd(a);
    }

    #[test]
    fn descriptor_passing_roundtrip() {
        let (a, b) = socketpair();
        let mut pipe = [0i32; 2];
        assert_eq!(unsafe { libc::pipe(pipe.as_mut_ptr()) }, 0);

        let payload = b"abc";
        assert_eq!(unsafe { send_with_fd(a, payload.as_ptr(), 3, pipe[1]) }, 3);

        let mut buf = [0u8; 16];
        let mut fds = [-1i32; MAX_FDS];
        let (n, count) = unsafe { recv_with_fds(b, buf.as_mut_ptr(), buf.len(), &mut fds) };
        assert_eq!(n, 3);
        assert_eq!(&buf[..3], payload);
        assert_eq!(count, 1);
        assert!(fds[0] >= 0 && fds[0] != pipe[1]);

        // The received descriptor is the pipe's write end.
        assert_eq!(unsafe { write(fds[0], b"z".as_ptr(), 1) }, 1);
        let mut one = [0u8; 1];
        assert_eq!(unsafe { read(pipe[0], one.as_mut_ptr(), 1) }, 1);
        assert_eq!(one[0], b'z');

        // Plain data without descriptors reports none.
        assert_eq!(unsafe { write(a, b"q".as_ptr(), 1) }, 1);
        let (n, count) = unsafe { recv_with_fds(b, buf.as_mut_ptr(), buf.len(), &mut fds) };
        assert_eq!((n, count), (1, 0));

        for fd in [a, b, pipe[0], pipe[1], fds[0]] {
            close_fd(fd);
        }
    }

    #[test]
    fn wait_for_socket_read_reports_state() {
        let (a, b) = socketpair();
        let shutdown = eventfd_create();
        assert!(matches!(
            { unsafe { write(b, b"x".as_ptr(), 1) }; wait_for_socket_read(a, shutdown) },
            Wait::Ready
        ));
        // Shutdown wins over pending data.
        let one: u64 = 1;
        unsafe { write(shutdown, &one as *const u64 as *const u8, 8) };
        assert!(matches!(wait_for_socket_read(a, shutdown), Wait::Stop));
        for fd in [a, b, shutdown] {
            close_fd(fd);
        }
    }
}
