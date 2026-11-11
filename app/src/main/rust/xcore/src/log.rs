//! Logging: logcat on Android, stderr elsewhere (host tests).

#[cfg(target_os = "android")]
mod imp {
    use std::ffi::CString;
    use std::os::raw::{c_char, c_int};

    extern "C" {
        fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char) -> c_int;
    }

    pub const DEBUG: i32 = 3;
    pub const WARN: i32 = 5;
    pub const ERROR: i32 = 6;

    pub fn write(prio: i32, tag: &str, msg: &str) {
        // Interior NULs cannot be passed to logcat; drop them instead of losing the line.
        let tag = CString::new(tag.replace('\0', "")).unwrap_or_default();
        let msg = CString::new(msg.replace('\0', "")).unwrap_or_default();
        unsafe { __android_log_write(prio as c_int, tag.as_ptr(), msg.as_ptr()) };
    }
}

#[cfg(not(target_os = "android"))]
mod imp {
    pub const DEBUG: i32 = 3;
    pub const WARN: i32 = 5;
    pub const ERROR: i32 = 6;

    pub fn write(prio: i32, tag: &str, msg: &str) {
        eprintln!("[{prio}] {tag}: {msg}");
    }
}

pub use imp::{DEBUG, ERROR, WARN};

pub fn write(prio: i32, tag: &str, msg: &str) {
    imp::write(prio, tag, msg);
}

pub fn warn(msg: &str) {
    write(WARN, "xcore", msg);
}

pub fn error(msg: &str) {
    write(ERROR, "xcore", msg);
}
