//! JNI entry points of `XConnectorEpoll` and `ClientSocket`.
//!
//! The method IDs of the Java callbacks are looked up once and cached; the C code resolved them
//! (`GetObjectClass` + `GetMethodID`) on every epoll wake-up and on every received message that
//! carried descriptors.

use super::{guard_or};
use crate::sock::{self, Event, Wait, MAX_FDS};
use jni::objects::{JByteBuffer, JClass, JMethodID, JObject, JString, JValue};
use jni::signature::{Primitive, ReturnType};
use jni::sys::{jboolean, jint, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::sync::OnceLock;

struct EpollIds {
    new_connection: JMethodID,
    existing_connection: JMethodID,
}

static EPOLL_IDS: OnceLock<EpollIds> = OnceLock::new();
static ANCILLARY_ID: OnceLock<JMethodID> = OnceLock::new();

fn epoll_ids(env: &mut JNIEnv, obj: &JObject) -> Option<&'static EpollIds> {
    if let Some(ids) = EPOLL_IDS.get() {
        return Some(ids);
    }
    let cls = env.get_object_class(obj).ok()?;
    let new_connection = env.get_method_id(&cls, "handleNewConnection", "(I)V").ok()?;
    let existing_connection = env.get_method_id(&cls, "handleExistingConnection", "(I)V").ok()?;
    Some(EPOLL_IDS.get_or_init(|| EpollIds { new_connection, existing_connection }))
}

/// Calls a `void method(int)`. `false` when it left an exception pending: the caller must make
/// no further JNI calls and let it reach Java.
fn call_void_int(env: &mut JNIEnv, obj: &JObject, id: JMethodID, arg: i32) -> bool {
    unsafe {
        let _ = env.call_method_unchecked(obj, id, ReturnType::Primitive(Primitive::Void), &[JValue::Int(arg).as_jni()]);
    }
    !env.exception_check().unwrap_or(true)
}

fn direct(env: &mut JNIEnv, buf: &JByteBuffer, offset: jint, length: jint) -> Option<*mut u8> {
    let addr = env.get_direct_buffer_address(buf).ok()?;
    let cap = env.get_direct_buffer_capacity(buf).ok()?;
    let (off, len) = (usize::try_from(offset).ok()?, usize::try_from(length).ok()?);
    if addr.is_null() || off.checked_add(len)? > cap {
        return None;
    }
    Some(unsafe { addr.add(off) })
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xconnector_XConnectorEpoll_createAFUnixSocket(
    mut env: JNIEnv,
    _obj: JObject,
    path: JString,
) -> jint {
    guard_or("createAFUnixSocket", -1, || {
        let Ok(path) = env.get_string(&path) else { return -1 };
        let path: String = path.into();
        sock::create_unix_listener(path.as_bytes())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xconnector_XConnectorEpoll_createEpollFd(_env: JNIEnv, _obj: JObject) -> jint {
    sock::epoll_create()
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xconnector_XConnectorEpoll_createEventFd(_env: JNIEnv, _obj: JObject) -> jint {
    sock::eventfd_create()
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xconnector_XConnectorEpoll_closeFd(_env: JNIEnv, _cls: JClass, fd: jint) {
    sock::close_fd(fd);
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xconnector_XConnectorEpoll_addFdToEpoll(
    _env: JNIEnv,
    _obj: JObject,
    epoll_fd: jint,
    fd: jint,
) -> jboolean {
    if sock::epoll_add(epoll_fd, fd) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xconnector_XConnectorEpoll_removeFdFromEpoll(
    _env: JNIEnv,
    _obj: JObject,
    epoll_fd: jint,
    fd: jint,
) {
    sock::epoll_del(epoll_fd, fd);
}

/// One `epoll_wait` and the Java callbacks for what it returned. `false` ends the connector's
/// loop (an error, or a Java exception that must reach the caller).
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xconnector_XConnectorEpoll_doEpollIndefinitely(
    mut env: JNIEnv,
    obj: JObject,
    epoll_fd: jint,
    server_fd: jint,
    add_client_to_epoll: jboolean,
) -> jboolean {
    guard_or("doEpollIndefinitely", JNI_FALSE, || {
        let Some(ids) = epoll_ids(&mut env, &obj) else { return JNI_FALSE };
        let keep_going = sock::dispatch_once(epoll_fd, server_fd, add_client_to_epoll != JNI_FALSE, &mut |event| match event {
            Event::NewConnection(fd) => call_void_int(&mut env, &obj, ids.new_connection, fd),
            Event::Readable(fd) => call_void_int(&mut env, &obj, ids.existing_connection, fd),
        });
        if keep_going {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    })
}

/// The multithreaded-clients mode: one thread per client blocks here.
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xconnector_XConnectorEpoll_waitForSocketRead(
    mut env: JNIEnv,
    obj: JObject,
    client_fd: jint,
    shutdown_fd: jint,
) -> jboolean {
    guard_or("waitForSocketRead", JNI_FALSE, || match sock::wait_for_socket_read(client_fd, shutdown_fd) {
        Wait::Stop => JNI_FALSE,
        Wait::Retry | Wait::Idle => JNI_TRUE,
        Wait::Ready => {
            let Some(ids) = epoll_ids(&mut env, &obj) else { return JNI_FALSE };
            // An exception left pending here reaches Java when this call returns.
            call_void_int(&mut env, &obj, ids.existing_connection, client_fd);
            JNI_TRUE
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xconnector_ClientSocket_read(
    mut env: JNIEnv,
    _obj: JObject,
    fd: jint,
    data: JByteBuffer,
    offset: jint,
    length: jint,
) -> jint {
    guard_or("read", -1, || match direct(&mut env, &data, offset, length) {
        Some(p) => unsafe { sock::read(fd, p, length as usize) as jint },
        None => -1,
    })
}

/// Writes from the start of the buffer (not its position), as before: used for 8-byte wake-ups.
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xconnector_ClientSocket_write(
    mut env: JNIEnv,
    _obj: JObject,
    fd: jint,
    data: JByteBuffer,
    length: jint,
) -> jint {
    guard_or("write", -1, || match direct(&mut env, &data, 0, length) {
        Some(p) => unsafe { sock::write(fd, p, length as usize) as jint },
        None => -1,
    })
}

/// Sends without ever blocking: the bytes written, or `-errno` (`-EAGAIN`: the socket is full
/// because the client stopped reading).
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xconnector_ClientSocket_writeNonBlocking(
    mut env: JNIEnv,
    _obj: JObject,
    fd: jint,
    data: JByteBuffer,
    offset: jint,
    length: jint,
) -> jint {
    guard_or("writeNonBlocking", -libc::EINVAL, || match direct(&mut env, &data, offset, length) {
        Some(p) => unsafe { sock::write_nonblocking(fd, p, length as usize) as jint },
        None => -libc::EINVAL,
    })
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xconnector_ClientSocket_recvAncillaryMsg(
    mut env: JNIEnv,
    obj: JObject,
    client_fd: jint,
    data: JByteBuffer,
    offset: jint,
    length: jint,
) -> jint {
    guard_or("recvAncillaryMsg", -1, || {
        let Some(p) = direct(&mut env, &data, offset, length) else { return -1 };
        let mut fds = [-1i32; MAX_FDS];
        let (size, count) = unsafe { sock::recv_with_fds(client_fd, p, length as usize, &mut fds) };
        if count > 0 {
            let id = match ANCILLARY_ID.get() {
                Some(id) => Some(*id),
                None => env
                    .get_object_class(&obj)
                    .and_then(|cls| env.get_method_id(&cls, "addAncillaryFd", "(I)V"))
                    .ok()
                    .map(|id| *ANCILLARY_ID.get_or_init(|| id)),
            };
            if let Some(id) = id {
                for fd in &fds[..count] {
                    call_void_int(&mut env, &obj, id, *fd);
                }
            } else {
                // Nobody can take the descriptors: do not leak them.
                for fd in &fds[..count] {
                    sock::close_fd(*fd);
                }
            }
        }
        size as jint
    })
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xconnector_ClientSocket_sendAncillaryMsg(
    mut env: JNIEnv,
    _obj: JObject,
    client_fd: jint,
    data: JByteBuffer,
    length: jint,
    ancillary_fd: jint,
) -> jint {
    guard_or("sendAncillaryMsg", -1, || match direct(&mut env, &data, 0, length) {
        Some(p) => unsafe { sock::send_with_fd(client_fd, p, length as usize, ancillary_fd) as jint },
        None => -1,
    })
}
