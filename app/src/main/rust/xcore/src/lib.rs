//! xcore: native core of WinLite's X server.
//!
//! It holds the whole X server and the two guest services next to it:
//!
//! * [`proto`] / [`runtime`] / [`bridge`]: the X11 server (`android::server_jni` for Java);
//! * [`sysv`]: SysV shared memory for the guest's `libandroid-sysvshm.so`, whose segments MIT-SHM
//!   reads in-process; [`alsa`]: the ALSA server of the guest's PCM plugin, output through AAudio
//!   (`android::aaudio`); both started by `android::services_jni`;
//! * [`shm`] / [`sock`]: memfd/ashmem and AF_UNIX/epoll primitives under all of them.
//!
//! Everything that does not need Android APIs lives in platform-independent modules; the
//! `android` module is only the thin JNI / AHardwareBuffer / AAudio layer.

pub mod alsa;
pub mod bridge;
pub mod frames;
pub mod log;
pub mod pixels;
pub mod proto;
pub mod rop;
#[cfg(unix)]
pub mod runtime;
pub mod shm;
pub mod sock;
pub mod sysv;

#[cfg(target_os = "android")]
mod android;
