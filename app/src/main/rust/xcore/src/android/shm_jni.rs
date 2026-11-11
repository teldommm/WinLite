//! JNI entry points of `com.winlator.cmod.sysvshm.SysVSharedMemory`.

use super::{guard, guard_or};
use crate::shm;
use jni::objects::{JByteBuffer, JClass, JString};
use jni::sys::{jboolean, jint, jlong, jobject, JNI_FALSE};
use jni::JNIEnv;
use std::ptr;

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_sysvshm_SysVSharedMemory_createMemoryFd(
    mut env: JNIEnv,
    _cls: JClass,
    name: JString,
    size: jint,
) -> jint {
    guard_or("createMemoryFd", -1, || {
        let Ok(name) = env.get_string(&name) else { return -1 };
        let name: String = name.into();
        shm::memfd_create(&name, size as i64)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_sysvshm_SysVSharedMemory_ashmemCreateRegion(
    _env: JNIEnv,
    _cls: JClass,
    index: jint,
    size: jlong,
) -> jint {
    guard_or("ashmemCreateRegion", -1, || shm::ashmem_create(&format!("sysvshm-{index}"), size))
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_sysvshm_SysVSharedMemory_mapSHMSegment(
    mut env: JNIEnv,
    _cls: JClass,
    fd: jint,
    size: jlong,
    offset: jint,
    readonly: jboolean,
) -> jobject {
    guard_or("mapSHMSegment", ptr::null_mut(), || {
        if size <= 0 {
            return ptr::null_mut();
        }
        let addr = shm::map(fd, size as usize, offset as i64, readonly != JNI_FALSE);
        if addr.is_null() {
            return ptr::null_mut();
        }
        match unsafe { env.new_direct_byte_buffer(addr, size as usize) } {
            Ok(buf) => buf.into_raw(),
            Err(_) => {
                unsafe { shm::unmap(addr, size as usize) };
                ptr::null_mut()
            }
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_sysvshm_SysVSharedMemory_unmapSHMSegment(
    env: JNIEnv,
    _cls: JClass,
    data: JByteBuffer,
    size: jlong,
) {
    guard("unmapSHMSegment", || {
        let Ok(addr) = env.get_direct_buffer_address(&data) else { return };
        if !addr.is_null() && size > 0 {
            unsafe { shm::unmap(addr, size as usize) };
        }
    })
}
