//! AF_UNIX sockets, descriptor passing and the epoll dispatch loop (once `xconnector_epoll.c`),
//! used by the X server and the SysV SHM / ALSA servers. Pure libc.

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

/// `sendmsg` of `len` bytes with one descriptor attached (SCM_RIGHTS), without SIGPIPE.
/// Bytes sent or -1.
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

    libc::sendmsg(fd, &msg, libc::MSG_NOSIGNAL)
}

/// `send(2)` without SIGPIPE (a guest that went away is an error, not a signal); -1 on error.
///
/// # Safety
/// `buf` readable for `len` bytes.
pub unsafe fn send_nosignal(fd: i32, buf: *const u8, len: usize) -> isize {
    libc::send(fd, buf as *const c_void, len, libc::MSG_NOSIGNAL)
}
