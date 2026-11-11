//! The JNI / AHardwareBuffer / AAudio layer.

mod aaudio;
mod ahb;
mod ahb_pixels;
mod output;
mod platform;
mod server_jni;
mod services_jni;

use crate::log;
use std::panic::{catch_unwind, AssertUnwindSafe};

/// Runs a JNI entry point so that a panic cannot unwind into the JVM (undefined behavior):
/// it is logged and the call returns `default`, like a request that failed.
pub(crate) fn guard_or<R>(name: &str, default: R, f: impl FnOnce() -> R) -> R {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(v) => v,
        Err(_) => {
            log::write(log::ERROR, "xcore", &format!("{name}: panic, request dropped"));
            default
        }
    }
}

pub(crate) fn guard(name: &str, f: impl FnOnce()) {
    guard_or(name, (), f)
}
