//! xcore: native core of WinLite's X server.
//!
//! Phase 1 replaces three C files of `libwinlator.so` with Rust, keeping every JNI symbol name
//! and signature, so the Java side is unchanged:
//!
//! * `drawable.c`           -> [`pixels`] (pure pixel code) + `android::drawable` (JNI, AHardwareBuffer)
//! * `sysvshared_memory.c`  -> [`shm`] (pure libc code) + `android::shm_jni`
//! * `xconnector_epoll.c`   -> [`sock`] (pure libc code) + `android::connector`
//!
//! Everything that does not need Android APIs lives in platform-independent modules and is unit
//! tested on the host; the `android` module is only the thin JNI / AHardwareBuffer layer.

pub mod bridge;
pub mod log;
pub mod pixels;
pub mod proto;
pub mod rop;
#[cfg(unix)]
pub mod runtime;
pub mod shm;
pub mod sock;

#[cfg(target_os = "android")]
mod android;

#[cfg(all(unix, not(target_os = "android")))]
pub mod host;
