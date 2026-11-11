//! Shared memory primitives of the SysV SHM emulation (the port of `sysvshared_memory.c`).

use std::ffi::CString;
use std::os::raw::c_void;
use std::ptr;

pub const ASHMEM_NAME_LEN: usize = 256;
const ASHMEM_IOC_TYPE: u32 = 0x77;

/// `_IOW(type, nr, size)` in the asm-generic layout (arm64, x86).
pub const fn iow(ty: u32, nr: u32, size: u32) -> u32 {
    (1 << 30) | (size << 16) | (ty << 8) | nr
}

/// `ASHMEM_SET_NAME` = `_IOW(0x77, 1, char[256])`
pub const ASHMEM_SET_NAME: u32 = iow(ASHMEM_IOC_TYPE, 1, ASHMEM_NAME_LEN as u32);
/// `ASHMEM_SET_SIZE` = `_IOW(0x77, 3, size_t)`
pub const ASHMEM_SET_SIZE: u32 = iow(ASHMEM_IOC_TYPE, 3, std::mem::size_of::<usize>() as u32);

const MFD_ALLOW_SEALING: libc::c_uint = 0x0002;

/// A new ashmem region of `size` bytes, or -1 (no /dev/ashmem on newer devices).
pub fn ashmem_create(name: &str, size: i64) -> i32 {
    unsafe {
        let path = b"/dev/ashmem\0";
        let fd = libc::open(path.as_ptr() as *const libc::c_char, libc::O_RDWR);
        if fd < 0 {
            return -1;
        }
        let mut buf = [0u8; ASHMEM_NAME_LEN];
        let n = name.len().min(ASHMEM_NAME_LEN - 1);
        buf[..n].copy_from_slice(&name.as_bytes()[..n]);

        if libc::ioctl(fd, ASHMEM_SET_NAME as _, buf.as_ptr()) < 0
            || libc::ioctl(fd, ASHMEM_SET_SIZE as _, size as usize) < 0
        {
            libc::close(fd);
            return -1;
        }
        fd
    }
}

/// A sealable anonymous memory file of `size` bytes, or -1. `memfd_create` is called as a raw
/// syscall: libc only exports it from API 30, the app supports 28.
pub fn memfd_create(name: &str, size: i64) -> i32 {
    let Ok(cname) = CString::new(name) else { return -1 };
    unsafe {
        let fd = libc::syscall(libc::SYS_memfd_create, cname.as_ptr(), MFD_ALLOW_SEALING) as i32;
        if fd < 0 {
            return -1;
        }
        if libc::ftruncate(fd, size as libc::off_t) < 0 {
            libc::close(fd);
            return -1;
        }
        fd
    }
}

/// Maps `size` bytes of `fd` shared; null on failure.
pub fn map(fd: i32, size: usize, offset: i64, readonly: bool) -> *mut u8 {
    let prot = if readonly { libc::PROT_READ } else { libc::PROT_READ | libc::PROT_WRITE };
    let p = unsafe { libc::mmap(ptr::null_mut(), size, prot, libc::MAP_SHARED, fd, offset as libc::off_t) };
    if p == libc::MAP_FAILED {
        ptr::null_mut()
    } else {
        p as *mut u8
    }
}

/// # Safety
/// `ptr`/`size` describe a mapping made by [`map`] that nothing uses any more.
pub unsafe fn unmap(ptr: *mut u8, size: usize) {
    libc::munmap(ptr as *mut c_void, size);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ioctl_numbers_match_linux_ashmem_h_on_64_bit() {
        assert_eq!(ASHMEM_SET_NAME, 0x4100_7701);
        #[cfg(target_pointer_width = "64")]
        assert_eq!(ASHMEM_SET_SIZE, 0x4008_7703);
    }

    #[test]
    fn memfd_map_roundtrip() {
        let fd = memfd_create("xcore-test", 4096);
        assert!(fd >= 0);
        let a = map(fd, 4096, 0, false);
        let b = map(fd, 4096, 0, true);
        assert!(!a.is_null() && !b.is_null());
        unsafe {
            *a.add(100) = 0x5a;
            assert_eq!(*b.add(100), 0x5a); // shared
            unmap(a, 4096);
            unmap(b, 4096);
            libc::close(fd);
        }
    }

    #[test]
    fn map_failure_is_null_and_bad_name_is_rejected() {
        assert!(map(-1, 4096, 0, false).is_null());
        assert_eq!(memfd_create("bad\0name", 16), -1);
        assert_eq!(memfd_create("ok", -1), -1); // ftruncate fails
    }
}
