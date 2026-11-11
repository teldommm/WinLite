//! JNI entry points of the guest services: `SysVSharedMemoryComponent` (SysV SHM server) and
//! `ALSAServerComponent` (ALSA server over AAudio). Like the X server, a running service is a
//! handle in a registry, never a raw pointer; every entry point is panic-guarded.

use super::aaudio::AAudioFactory;
use super::{guard, guard_or};
use crate::{alsa, log, sysv};
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jlong, JNI_FALSE};
use jni::JNIEnv;
use std::collections::HashMap;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

enum Service {
    Sysv(sysv::Server),
    Alsa(alsa::Server),
}

static NEXT: AtomicI64 = AtomicI64::new(1);

fn registry() -> &'static Mutex<HashMap<i64, Service>> {
    static R: OnceLock<Mutex<HashMap<i64, Service>>> = OnceLock::new();
    R.get_or_init(|| Mutex::new(HashMap::new()))
}

fn add(service: Service) -> jlong {
    let h = NEXT.fetch_add(1, Ordering::Relaxed);
    registry().lock().unwrap_or_else(|e| e.into_inner()).insert(h, service);
    h
}

/// Removes and stops the service (outside the registry lock: stopping joins threads).
fn remove(handle: jlong) {
    let service = registry().lock().unwrap_or_else(|e| e.into_inner()).remove(&handle);
    match service {
        Some(Service::Sysv(mut s)) => s.stop(),
        Some(Service::Alsa(mut s)) => s.stop(),
        None => {}
    }
}

fn path(env: &mut JNIEnv, path: &JString) -> Option<String> {
    env.get_string(path).ok().map(|s| s.into())
}

/// Starts the SysV SHM server on the Unix socket `path`; 0 when it cannot be bound.
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xenvironment_components_SysVSharedMemoryComponent_nativeStart(
    mut env: JNIEnv,
    _cls: JClass,
    socket_path: JString,
) -> jlong {
    guard_or("SysVSHM.nativeStart", 0, || {
        let Some(p) = path(&mut env, &socket_path) else { return 0 };
        match sysv::Server::start(&p) {
            Ok(s) => add(Service::Sysv(s)),
            Err(e) => {
                log::error(&format!("sysvshm: cannot listen on {p}: {e}"));
                0
            }
        }
    })
}

/// Stops the server and deletes the guest's segments (those the X server still has attached
/// go with their last detach).
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xenvironment_components_SysVSharedMemoryComponent_nativeStop(
    _env: JNIEnv,
    _cls: JClass,
    handle: jlong,
) {
    guard("SysVSHM.nativeStop", || remove(handle))
}

/// Starts the ALSA server on the Unix socket `path`; 0 when it cannot be bound.
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xenvironment_components_ALSAServerComponent_nativeStart(
    mut env: JNIEnv,
    _cls: JClass,
    socket_path: JString,
    latency_ms: jint,
    performance_mode: jint,
) -> jlong {
    guard_or("ALSA.nativeStart", 0, || {
        let Some(p) = path(&mut env, &socket_path) else { return 0 };
        let options = alsa::Options { latency_ms: latency_ms.max(0) as u32, performance_mode };
        match alsa::Server::start(&p, Arc::new(AAudioFactory), options) {
            Ok(s) => add(Service::Alsa(s)),
            Err(e) => {
                log::error(&format!("alsa: cannot listen on {p}: {e}"));
                0
            }
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xenvironment_components_ALSAServerComponent_nativeStop(
    _env: JNIEnv,
    _cls: JClass,
    handle: jlong,
) {
    guard("ALSA.nativeStop", || remove(handle))
}

/// Pauses (or resumes) the device output of every guest stream while the app is in the
/// background. Blocks for at most one device write per stream: call it off the UI thread.
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xenvironment_components_ALSAServerComponent_nativeSetOutputSuspended(
    _env: JNIEnv,
    _cls: JClass,
    suspended: jboolean,
) {
    guard("ALSA.nativeSetOutputSuspended", || alsa::set_output_suspended(suspended != JNI_FALSE))
}
