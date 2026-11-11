//! SysV shared memory emulation: the segment table and the server the guest's
//! `libandroid-sysvshm.so` talks to (port of `SysVSharedMemory` / `SysVSHMRequestHandler`).
//!
//! A segment is used from two sides that know nothing of each other: the guest (shmget / shmat /
//! shmctl over the socket below) and the X server (MIT-SHM Attach/Detach, through
//! [`attach`]/[`detach`] on its I/O thread). The guest's DELETE (sent once it has detached after
//! IPC_RMID) can arrive before the X server has processed the client's ShmDetach: Xlib only
//! queues that request and shmdt follows at once. A segment therefore lives until both sides are
//! done with it: deleted by the guest AND detached by every X attachment.
//!
//! The table is process-global so the X server reaches it with a plain call (the Java version
//! went Rust -> Java -> Rust on every ShmAttach).
//!
//! Wire protocol (little endian, one connection per guest call):
//! * `SHMGET  u8=0, u32 size` -> `i32 shmid` (0 = failure, which is what the guest checks for)
//! * `GET_FD  u8=1, i32 shmid` -> one byte `0`, with the segment's fd attached (none if unknown)
//! * `DELETE  u8=2, i32 shmid` -> no reply

use crate::log;
use crate::proto::platform::ShmMap;
use crate::shm;
use crate::sock::{self, Event};
use std::collections::{BTreeMap, HashMap};
use std::io;
use std::sync::{Mutex, MutexGuard};
use std::thread::JoinHandle;

pub const SHMGET: u8 = 0;
pub const GET_FD: u8 = 1;
pub const DELETE: u8 = 2;
const REQUEST_LEN: usize = 5;

struct Segment {
    fd: i32,
    size: usize,
    /// The X server's mapping, shared by its attachments (null while none).
    map: *mut u8,
    attachments: u32,
    /// The guest removed it (IPC_RMID, last shmdt).
    deleted: bool,
}

struct Table {
    segments: BTreeMap<i32, Segment>,
    last_id: i32,
}

// SAFETY: `map` is a plain address range owned by the table; it is only touched under the lock.
unsafe impl Send for Table {}

static TABLE: Mutex<Table> = Mutex::new(Table { segments: BTreeMap::new(), last_id: 0 });

fn table() -> MutexGuard<'static, Table> {
    TABLE.lock().unwrap_or_else(|e| e.into_inner())
}

fn close_fd(seg: &mut Segment) {
    if seg.fd >= 0 {
        sock::close_fd(seg.fd);
        seg.fd = -1;
    }
}

fn unmap(seg: &mut Segment) {
    if !seg.map.is_null() {
        unsafe { shm::unmap(seg.map, seg.size) };
        seg.map = std::ptr::null_mut();
    }
}

/// A new segment of `size` bytes; its id, or 0 when it cannot be created.
pub fn create(size: u64) -> i32 {
    if size == 0 || size > isize::MAX as u64 {
        return 0;
    }
    let mut t = table();
    // Ids are positive and never 0 (the guest's failure value); skip any still in use.
    let mut id = t.last_id;
    loop {
        id = if id == i32::MAX { 1 } else { id + 1 };
        if !t.segments.contains_key(&id) {
            break;
        }
    }
    let name = format!("sysvshm-{id}");
    let mut fd = shm::ashmem_create(&name, size as i64);
    if fd < 0 {
        fd = shm::memfd_create(&name, size as i64);
    }
    if fd < 0 {
        log::warn(&format!("sysvshm: cannot create a segment of {size} bytes"));
        return 0;
    }
    t.last_id = id;
    t.segments.insert(id, Segment { fd, size: size as usize, map: std::ptr::null_mut(), attachments: 0, deleted: false });
    id
}

/// The descriptor of a live segment (owned by the table: dup or send it, never close it), or -1.
pub fn fd(id: i32) -> i32 {
    match table().segments.get(&id) {
        Some(seg) if !seg.deleted => seg.fd,
        _ => -1,
    }
}

/// Runs `f` with the descriptor of a live segment while the table is locked, so a concurrent
/// DELETE cannot close it in between.
pub fn with_fd<R>(id: i32, f: impl FnOnce(i32) -> R) -> R {
    let t = table();
    let fd = match t.segments.get(&id) {
        Some(seg) if !seg.deleted => seg.fd,
        _ => -1,
    };
    f(fd)
}

/// The guest removed the segment: freed now, or once the X server has detached from it.
pub fn delete(id: i32) {
    let mut t = table();
    let Some(seg) = t.segments.get_mut(&id) else { return };
    seg.deleted = true;
    // The X server's mapping does not need the fd, and nobody can attach a removed segment.
    close_fd(seg);
    if seg.attachments == 0 {
        unmap(seg);
        t.segments.remove(&id);
    }
}

/// As if the guest deleted every segment. A mapping the X server still has attached is not
/// pulled from under it (it may be stopped after this); it goes with its last detach.
pub fn delete_all() {
    let ids: Vec<i32> = table().segments.keys().copied().collect();
    for id in ids {
        delete(id);
    }
}

/// An X server attachment (MIT-SHM Attach). Pair every `Some` with [`detach`].
pub fn attach(id: i32) -> Option<ShmMap> {
    let mut t = table();
    let seg = t.segments.get_mut(&id)?;
    if seg.deleted {
        return None;
    }
    if seg.map.is_null() {
        if seg.fd < 0 {
            return None;
        }
        seg.map = shm::map(seg.fd, seg.size, 0, false);
        if seg.map.is_null() {
            return None;
        }
    }
    seg.attachments += 1;
    Some(ShmMap { ptr: seg.map, len: seg.size })
}

/// Ends one X server attachment (MIT-SHM Detach, or its client going away).
pub fn detach(id: i32) {
    let mut t = table();
    let Some(seg) = t.segments.get_mut(&id) else { return };
    if seg.attachments == 0 {
        return;
    }
    seg.attachments -= 1;
    if seg.attachments > 0 {
        return;
    }
    unmap(seg);
    if seg.deleted {
        t.segments.remove(&id);
    }
}

#[cfg(test)]
fn live_count() -> usize {
    table().segments.len()
}

// ----------------------------------------------------------------------------------- server

/// The SysV SHM socket server: one thread, every connection on one epoll set (requests are
/// five bytes and never block).
pub struct Server {
    stop_fd: i32,
    thread: Option<JoinHandle<()>>,
}

impl Server {
    /// Binds `path` (an existing file there is replaced) and starts the thread.
    pub fn start(path: &str) -> io::Result<Server> {
        let listener = sock::create_unix_listener(path.as_bytes());
        if listener < 0 {
            return Err(io::Error::last_os_error());
        }
        let epfd = sock::epoll_create();
        let stop_fd = sock::eventfd_create();
        if epfd < 0 || stop_fd < 0 || !sock::epoll_add(epfd, listener) || !sock::epoll_add(epfd, stop_fd) {
            let err = io::Error::last_os_error();
            for fd in [listener, epfd, stop_fd] {
                if fd >= 0 {
                    sock::close_fd(fd);
                }
            }
            return Err(err);
        }
        // The thread owns listener and epfd; stop_fd is closed by `stop` after the join.
        let thread = std::thread::Builder::new()
            .name("sysvshm".into())
            .spawn(move || serve(epfd, listener, stop_fd))
            .map_err(|e| {
                for fd in [listener, epfd, stop_fd] {
                    sock::close_fd(fd);
                }
                e
            })?;
        Ok(Server { stop_fd, thread: Some(thread) })
    }

    /// Stops the thread (closing every connection) and deletes the guest's segments.
    pub fn stop(&mut self) {
        let Some(thread) = self.thread.take() else { return };
        let one: u64 = 1;
        unsafe { sock::write(self.stop_fd, &one as *const u64 as *const u8, 8) };
        let _ = thread.join();
        sock::close_fd(self.stop_fd);
        delete_all();
    }
}

impl Drop for Server {
    fn drop(&mut self) {
        self.stop();
    }
}

fn serve(epfd: i32, listener: i32, stop_fd: i32) {
    // Pending bytes of each connection (requests may arrive split).
    let mut conns: HashMap<i32, Vec<u8>> = HashMap::new();
    loop {
        let keep_going = sock::dispatch_once(epfd, listener, true, &mut |ev| match ev {
            Event::NewConnection(fd) => {
                conns.insert(fd, Vec::new());
                true
            }
            Event::Readable(fd) if fd == stop_fd => false,
            Event::Readable(fd) => {
                let alive = match conns.get_mut(&fd) {
                    Some(pending) => serve_readable(fd, pending),
                    None => false,
                };
                if !alive {
                    sock::epoll_del(epfd, fd);
                    sock::close_fd(fd);
                    conns.remove(&fd);
                }
                true
            }
        });
        if !keep_going {
            break;
        }
    }
    for (fd, _) in conns.drain() {
        sock::close_fd(fd);
    }
    sock::epoll_del(epfd, listener);
    sock::close_fd(listener);
    sock::close_fd(epfd);
}

/// Reads what arrived and serves every complete request. `false`: the connection is done.
fn serve_readable(fd: i32, pending: &mut Vec<u8>) -> bool {
    let mut buf = [0u8; 256];
    let n = unsafe { sock::read(fd, buf.as_mut_ptr(), buf.len()) };
    if n <= 0 {
        return n < 0 && std::io::Error::last_os_error().raw_os_error() == Some(libc::EINTR);
    }
    pending.extend_from_slice(&buf[..n as usize]);
    let mut used = 0;
    while pending.len() - used >= REQUEST_LEN {
        let req = &pending[used..used + REQUEST_LEN];
        let code = req[0];
        let arg = u32::from_le_bytes([req[1], req[2], req[3], req[4]]);
        used += REQUEST_LEN;
        if !handle(fd, code, arg) {
            return false;
        }
    }
    pending.drain(..used);
    true
}

/// Serves one request; `false` when the reply could not be sent.
fn handle(fd: i32, code: u8, arg: u32) -> bool {
    match code {
        SHMGET => {
            let id = create(arg as u64);
            send_all(fd, &id.to_le_bytes())
        }
        GET_FD => with_fd(arg as i32, |seg_fd| {
            let zero = [0u8; 1];
            let n = if seg_fd >= 0 {
                unsafe { sock::send_with_fd(fd, zero.as_ptr(), 1, seg_fd) }
            } else {
                // No descriptor: the guest sees none arrive and fails the shmget.
                unsafe { sock::send_nosignal(fd, zero.as_ptr(), 1) }
            };
            n == 1
        }),
        DELETE => {
            delete(arg as i32);
            true
        }
        _ => true, // unknown request: ignored, as before
    }
}

fn send_all(fd: i32, data: &[u8]) -> bool {
    let mut off = 0;
    while off < data.len() {
        let n = unsafe { sock::send_nosignal(fd, data[off..].as_ptr(), data.len() - off) };
        if n < 0 {
            if std::io::Error::last_os_error().raw_os_error() == Some(libc::EINTR) {
                continue;
            }
            return false;
        }
        off += n as usize;
    }
    true
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex as StdMutex;

    // The table is process-global: tests touching it run one at a time.
    static SERIAL: StdMutex<()> = StdMutex::new(());

    fn lock() -> std::sync::MutexGuard<'static, ()> {
        SERIAL.lock().unwrap_or_else(|e| e.into_inner())
    }

    #[test]
    fn segment_lives_until_deleted_and_detached() {
        let _g = lock();
        let before = live_count();
        let id = create(8192);
        assert!(id > 0);
        assert!(fd(id) >= 0);

        let a = attach(id).expect("attach");
        let b = attach(id).expect("second attach shares the mapping");
        assert_eq!(a.ptr, b.ptr);
        assert_eq!(a.len, 8192);
        unsafe { *a.ptr.add(10) = 7 };

        // The guest deletes it while the X server still holds it: still mapped, fd gone.
        delete(id);
        assert_eq!(fd(id), -1);
        assert!(attach(id).is_none(), "a removed segment cannot be attached again");
        assert_eq!(unsafe { *b.ptr.add(10) }, 7);
        detach(id);
        assert_eq!(live_count(), before + 1);
        detach(id);
        assert_eq!(live_count(), before, "last detach frees it");
        detach(id); // unknown id: nothing happens
    }

    #[test]
    fn delete_without_attachment_frees_at_once_and_bad_sizes_fail() {
        let _g = lock();
        let before = live_count();
        let id = create(100);
        assert!(id > 0);
        delete(id);
        assert_eq!(live_count(), before);
        assert_eq!(create(0), 0);
    }

    #[test]
    fn delete_all_keeps_attached_mappings_alive() {
        let _g = lock();
        let id1 = create(4096);
        let id2 = create(4096);
        let m = attach(id1).unwrap();
        delete_all();
        assert_eq!(fd(id2), -1);
        unsafe { *m.ptr = 1 }; // still mapped
        detach(id1);
        assert!(attach(id1).is_none());
    }

    fn connect(path: &[u8]) -> i32 {
        unsafe {
            let fd = libc::socket(libc::AF_UNIX, libc::SOCK_STREAM, 0);
            let mut addr: libc::sockaddr_un = std::mem::zeroed();
            addr.sun_family = libc::AF_UNIX as _;
            for (i, b) in path.iter().enumerate() {
                addr.sun_path[i] = *b as libc::c_char;
            }
            let len = (std::mem::size_of::<libc::sa_family_t>() + path.len()) as u32;
            assert_eq!(libc::connect(fd, &addr as *const _ as *const libc::sockaddr, len), 0);
            fd
        }
    }

    fn request(fd: i32, code: u8, arg: u32) {
        let mut req = [code, 0, 0, 0, 0];
        req[1..].copy_from_slice(&arg.to_le_bytes());
        assert_eq!(unsafe { sock::write(fd, req.as_ptr(), 5) }, 5);
    }

    fn read_exact(fd: i32, buf: &mut [u8]) {
        let mut off = 0;
        while off < buf.len() {
            let n = unsafe { sock::read(fd, buf[off..].as_mut_ptr(), buf.len() - off) };
            assert!(n > 0);
            off += n as usize;
        }
    }

    #[test]
    fn guest_protocol_roundtrip() {
        let _g = lock();
        let path = format!("/tmp/xcore-sysv-{}.sock", std::process::id());
        let mut server = Server::start(&path).expect("start");

        // shmget, as the guest does it: connect, request, read, close.
        let c = connect(path.as_bytes());
        request(c, SHMGET, 65536);
        let mut id = [0u8; 4];
        read_exact(c, &mut id);
        let id = i32::from_le_bytes(id);
        assert!(id > 0);

        // GET_FD, split over two writes to check request reassembly.
        let mut req = [GET_FD, 0, 0, 0, 0];
        req[1..].copy_from_slice(&id.to_le_bytes());
        assert_eq!(unsafe { sock::write(c, req.as_ptr(), 2) }, 2);
        std::thread::sleep(std::time::Duration::from_millis(20));
        assert_eq!(unsafe { sock::write(c, req[2..].as_ptr(), 3) }, 3);
        let mut b = [0u8; 4];
        let mut fds = [-1i32; sock::MAX_FDS];
        let (n, count) = unsafe { sock::recv_with_fds(c, b.as_mut_ptr(), 4, &mut fds) };
        assert_eq!((n, count), (1, 1));

        // The guest's mapping and the X server's see the same memory.
        let guest = shm::map(fds[0], 65536, 0, false);
        assert!(!guest.is_null());
        let x = attach(id).unwrap();
        unsafe { *guest.add(1234) = 0x42 };
        assert_eq!(unsafe { *x.ptr.add(1234) }, 0x42);

        // Unknown id: a byte arrives with no descriptor.
        request(c, GET_FD, 999_999);
        let mut none = [-1i32; sock::MAX_FDS];
        let (n, count) = unsafe { sock::recv_with_fds(c, b.as_mut_ptr(), 4, &mut none) };
        assert_eq!((n, count), (1, 0));
        sock::close_fd(c);

        // DELETE on its own connection, then the X server detaches: gone.
        let c = connect(path.as_bytes());
        request(c, DELETE, id as u32);
        sock::close_fd(c);
        std::thread::sleep(std::time::Duration::from_millis(50));
        assert_eq!(fd(id), -1);
        detach(id);

        unsafe {
            shm::unmap(guest, 65536);
            libc::close(fds[0]);
        }
        server.stop();
        let _ = std::fs::remove_file(&path);
    }
}
