//! JNI entry points of `com.winlator.cmod.xserver.XCore`: the Rust X server as seen from Java.
//!
//! A server is identified by a handle (an id in a registry, never a raw pointer): a call that
//! races with `nativeDestroy` finds nothing and returns its default instead of touching freed
//! memory. Every entry point is panic-guarded.

use super::output::{self, RendererApi};
use super::platform::{self, AndroidPlatform};
use super::ahb_pixels::AhbPixels;
use super::{guard, guard_or};
use crate::bridge;
use crate::frames::Output;
use crate::log;
use crate::proto::gfx::PixelStore;
use crate::proto::{Config, Server};
use crate::runtime::Runtime;
use jni::objects::{JByteArray, JClass, JIntArray, JLongArray, JObject, JString};
use jni::signature::{Primitive, ReturnType};
use jni::sys::{jboolean, jdouble, jint, jlong, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::collections::HashMap;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Instant;

type Env<'a> = JNIEnv<'a>;

static NEXT: AtomicI64 = AtomicI64::new(1);

fn registry() -> &'static Mutex<HashMap<i64, Arc<Runtime>>> {
    static R: OnceLock<Mutex<HashMap<i64, Arc<Runtime>>>> = OnceLock::new();
    R.get_or_init(|| Mutex::new(HashMap::new()))
}

fn get(handle: jlong) -> Option<Arc<Runtime>> {
    registry().lock().unwrap_or_else(|e| e.into_inner()).get(&handle).cloned()
}

/// Runs `f` on the server of `handle`; `default` when the handle is unknown or `f` panicked.
fn on_server<R: Copy>(name: &str, handle: jlong, default: R, f: impl FnOnce(&mut Server) -> R) -> R {
    guard_or(name, default, || match get(handle) {
        Some(rt) => rt.with(f),
        None => default,
    })
}

fn long_array<'a>(env: &mut Env<'a>, data: &[i64]) -> JLongArray<'a> {
    let arr = env.new_long_array(data.len() as jint).expect("new_long_array");
    if !data.is_empty() {
        let _ = env.set_long_array_region(&arr, 0, data);
    }
    arr
}

fn byte_array<'a>(env: &mut Env<'a>, data: &[u8]) -> JByteArray<'a> {
    let arr = env.new_byte_array(data.len() as jint).expect("new_byte_array");
    if !data.is_empty() {
        // SAFETY: i8 and u8 have the same layout.
        let signed = unsafe { std::slice::from_raw_parts(data.as_ptr() as *const i8, data.len()) };
        let _ = env.set_byte_array_region(&arr, 0, signed);
    }
    arr
}

fn null_byte_array<'a>() -> JByteArray<'a> {
    // SAFETY: a null reference is a valid jbyteArray value.
    unsafe { JByteArray::from_raw(std::ptr::null_mut()) }
}

/// Creates the server, binds `path` and starts serving. Returns the handle, 0 on failure.
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xserver_XCore_nativeCreate(
    mut env: Env,
    _cls: JClass,
    width: jint,
    height: jint,
    hal_format: jint,
    path: JString,
) -> jlong {
    guard_or("nativeCreate", 0, || {
        let Ok(path) = env.get_string(&path) else { return 0 };
        let path: String = path.into();
        let (w, h) = (width.clamp(1, i16::MAX as i32) as i16, height.clamp(1, i16::MAX as i32) as i16);
        let start = Instant::now();
        let format = hal_format as u32;
        let mut server = Server::new(Config {
            width: w,
            height: h,
            alloc: Box::new(move |w, h| AhbPixels::new(w, h, format).map(|p| Box::new(p) as Box<dyn PixelStore>)),
            clock_ms: Box::new(move || start.elapsed().as_millis() as u32),
            platform: Box::new(AndroidPlatform),
        });
        crate::proto::ext::register_all(&mut server);
        match Runtime::start(&path, server) {
            Ok(rt) => {
                // Frames go straight to the renderer (when there is one), not through Java.
                let out = output::output();
                out.forget_drawables();
                rt.set_output(Some(out as Arc<dyn Output>));
                let handle = NEXT.fetch_add(1, Ordering::Relaxed);
                registry().lock().unwrap_or_else(|e| e.into_inner()).insert(handle, Arc::new(rt));
                log::warn(&format!("X server started on {path} ({w}x{h})"));
                handle
            }
            Err(e) => {
                log::error(&format!("cannot start the X server on {path}: {e}"));
                0
            }
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xserver_XCore_nativeDestroy(_env: Env, _cls: JClass, handle: jlong) {
    guard("nativeDestroy", || {
        let rt = registry().lock().unwrap_or_else(|e| e.into_inner()).remove(&handle);
        if let Some(rt) = rt {
            rt.set_on_events(None);
            // Queued frames give their buffer references back before the server goes.
            rt.set_output(None);
            rt.stop();
            output::output().forget_drawables();
        }
    })
}

/// Registers the object whose `void onServerEvents()` is called when events are waiting
/// (from any thread, never under the server lock); null removes it.
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xserver_XCore_nativeSetListener(mut env: Env, _cls: JClass, handle: jlong, listener: JObject) {
    guard("nativeSetListener", || {
        let Some(rt) = get(handle) else { return };
        if listener.as_raw().is_null() {
            rt.set_on_events(None);
            return;
        }
        let (Ok(vm), Ok(obj)) = (env.get_java_vm(), env.new_global_ref(&listener)) else { return };
        // Resolved once here, not on every call (a class lookup + a method lookup each time).
        let Ok(on_events) = env.get_object_class(&listener).and_then(|c| env.get_method_id(&c, "onServerEvents", "()V")) else {
            return;
        };
        rt.set_on_events(Some(Box::new(move || {
            // Mostly called on xcore-io, a thread of our own. `attach_current_thread` would attach
            // it and detach it again when its guard drops, i.e. on every I/O turn that queued
            // events (a new java.lang.Thread each time). Attached once, it stays attached until it
            // exits; on a thread the JVM already knows this is just a lookup. The MIT-SHM calls of
            // `platform.rs` run on the same thread and then find it attached too.
            let Ok(mut env) = vm.attach_current_thread_permanently() else { return };
            // SAFETY: the id was looked up for this object's class with this signature (no args,
            // void); the global reference keeps the object, and so its class, alive.
            unsafe {
                let _ = env.call_method_unchecked(&obj, on_events, ReturnType::Primitive(Primitive::Void), &[]);
            }
            if env.exception_check().unwrap_or(false) {
                let _ = env.exception_clear();
            }
        })));
    })
}

/// Queued events, [`bridge::EVENT_STRIDE`] values each (see `XCore.EV_*`).
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xserver_XCore_nativeDrainEvents<'a>(mut env: Env<'a>, _cls: JClass, handle: jlong) -> JLongArray<'a> {
    let data = guard_or("nativeDrainEvents", Vec::new(), || match get(handle) {
        Some(rt) => bridge::encode_events(&rt.drain_events()),
        None => Vec::new(),
    });
    long_array(&mut env, &data)
}

/// Every window in paint order, [`bridge::WINDOW_STRIDE`] values each (see `XCore.WF_*`).
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xserver_XCore_nativeSnapshotTree<'a>(mut env: Env<'a>, _cls: JClass, handle: jlong) -> JLongArray<'a> {
    let data = guard_or("nativeSnapshotTree", Vec::new(), || match get(handle) {
        Some(rt) => rt.with(|s| bridge::snapshot_tree(s)),
        None => Vec::new(),
    });
    long_array(&mut env, &data)
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xserver_XCore_nativePointerMove(_env: Env, _cls: JClass, handle: jlong, x: jint, y: jint) {
    on_server("nativePointerMove", handle, (), |s| s.set_pointer_position(x, y))
}

/// Moves the pointer without events (cursor locking, warps by the application).
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xserver_XCore_nativePointerMoveSilent(_env: Env, _cls: JClass, handle: jlong, x: jint, y: jint) {
    on_server("nativePointerMoveSilent", handle, (), |s| s.set_pointer_silent(x, y))
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xserver_XCore_nativePointerButton(_env: Env, _cls: JClass, handle: jlong, button: jint, pressed: jboolean) {
    let button = u8::try_from(button).unwrap_or(0);
    on_server("nativePointerButton", handle, (), |s| s.set_pointer_button(button, pressed == JNI_TRUE))
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xserver_XCore_nativeKey(_env: Env, _cls: JClass, handle: jlong, keycode: jint, keysym: jint, pressed: jboolean) {
    let Ok(code) = u8::try_from(keycode) else { return };
    on_server("nativeKey", handle, (), |s| {
        if pressed == JNI_TRUE {
            s.key_press(code, keysym as u32)
        } else {
            s.key_release(code)
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xserver_XCore_nativeSetRelativeMouse(_env: Env, _cls: JClass, handle: jlong, on: jboolean) {
    on_server("nativeSetRelativeMouse", handle, (), |s| s.input.relative_mouse = on == JNI_TRUE)
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xserver_XCore_nativeSetMouseDisabled(_env: Env, _cls: JClass, handle: jlong, disabled: jboolean) {
    on_server("nativeSetMouseDisabled", handle, (), |s| s.input.mouse_disabled = disabled == JNI_TRUE)
}

/// Pointer position, `(x << 16) | (y & 0xffff)`.
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xserver_XCore_nativePointerPosition(_env: Env, _cls: JClass, handle: jlong) -> jlong {
    on_server("nativePointerPosition", handle, 0i64, |s| bridge::pack_point(s.input.x, s.input.y))
}

/// Screen size, `(width << 16) | height`; the effective size, i.e. a fullscreen window's.
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xserver_XCore_nativeScreenSize(_env: Env, _cls: JClass, handle: jlong) -> jlong {
    on_server("nativeScreenSize", handle, 0i64, |s| {
        let (w, h) = s.effective_screen();
        ((w as i64) << 16) | (h as i64 & 0xffff)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xserver_XCore_nativeConnectionCount(_env: Env, _cls: JClass, handle: jlong) -> jint {
    guard_or("nativeConnectionCount", 0, || get(handle).map(|rt| rt.connection_count() as jint).unwrap_or(0))
}

/// Raw relative motion of the input device (XInput raw events), before it moves the pointer.
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xserver_XCore_nativeRawMotion(_env: Env, _cls: JClass, handle: jlong, dx: jdouble, dy: jdouble) {
    on_server("nativeRawMotion", handle, (), |s| s.raw_motion(dx, dy))
}

/// The refresh rates the display offers (RANDR) and the one in force.
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xserver_XCore_nativeSetRefreshRates(env: Env, _cls: JClass, handle: jlong, rates: JIntArray, active: jint) {
    guard("nativeSetRefreshRates", || {
        let len = env.get_array_length(&rates).unwrap_or(0) as usize;
        let mut buf = vec![0i32; len];
        if len == 0 || env.get_int_array_region(&rates, 0, &mut buf).is_err() {
            return;
        }
        let rates: Vec<i16> = buf.iter().map(|r| (*r).clamp(1, i16::MAX as i32) as i16).collect();
        let active = active.clamp(0, i16::MAX as i32) as i16;
        on_server("nativeSetRefreshRates", handle, (), |s| s.set_refresh_rates(&rates, active));
    })
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xserver_XCore_nativeSetActiveRate(_env: Env, _cls: JClass, handle: jlong, active: jint) {
    let active = active.clamp(0, i16::MAX as i32) as i16;
    on_server("nativeSetActiveRate", handle, (), |s| s.set_active_rate(active))
}

/// The FPS limit of the renderer view (0 = none): Present paces its releases by it.
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xserver_XCore_nativeSetFpsLimit(_env: Env, _cls: JClass, fps: jint) {
    guard("nativeSetFpsLimit", || platform::set_fps_limit(fps))
}

/// The renderer that takes the frames: `VulkanXServerView.nativeRendererApi()` (the table of its
/// C entry points) and its context (the view's native handle); 0, 0: none. Returns once no frame
/// is being handed to the previous renderer, so it can be destroyed right after.
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xserver_XCore_nativeSetRenderer(_env: Env, _cls: JClass, api: jlong, ctx: jlong) {
    guard("nativeSetRenderer", || {
        output::output().set_renderer(api as usize as *const RendererApi, ctx as usize as *mut std::os::raw::c_void)
    })
}

/// Hands the window's own content to the renderer again (a new renderer has none of it).
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xserver_XCore_nativeRefreshContent(_env: Env, _cls: JClass, handle: jlong, window: jint) {
    guard("nativeRefreshContent", || {
        if let Some(rt) = get(handle) {
            rt.refresh_content(window as u32)
        }
    })
}

/// The `SysVSharedMemory` MIT-SHM attaches the guest's segments from (null: none).
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xserver_XCore_nativeSetShmProvider(env: Env, _cls: JClass, provider: JObject) {
    guard("nativeSetShmProvider", || {
        let Ok(vm) = env.get_java_vm() else { return };
        if provider.as_raw().is_null() {
            platform::set_shm_provider(vm, None);
        } else if let Ok(obj) = env.new_global_ref(&provider) {
            platform::set_shm_provider(vm, Some(obj));
        }
    })
}

// ------------------------------------------------------------------ queries of the consumers

/// The X cursor under the pointer: `[serial, hot_x, hot_y, visible (0/1), width, height]`, or an
/// empty array when no window up to the root has one. A new serial means a new image.
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xserver_XCore_nativeCursorState<'a>(mut env: Env<'a>, _cls: JClass, handle: jlong) -> JLongArray<'a> {
    let data = guard_or("nativeCursorState", Vec::new(), || match get(handle) {
        Some(rt) => rt.with(|s| match bridge::pointer_cursor(s) {
            Some(c) => vec![c.serial as i64, c.hot_x as i64, c.hot_y as i64, c.visible as i64, c.width as i64, c.height as i64],
            None => Vec::new(),
        }),
        None => Vec::new(),
    });
    long_array(&mut env, &data)
}

/// The image of the cursor under the pointer as R,G,B,A bytes (width * height * 4), or null.
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xserver_XCore_nativeCursorImage<'a>(mut env: Env<'a>, _cls: JClass, handle: jlong) -> JByteArray<'a> {
    let data = guard_or("nativeCursorImage", None, || get(handle).and_then(|rt| rt.with(|s| bridge::pointer_cursor_image(s))));
    match data {
        Some(d) => byte_array(&mut env, &d),
        None => null_byte_array(),
    }
}

/// Pressed pointer buttons, bit `1 << code` per button code (1 = left ... 7).
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xserver_XCore_nativePointerButtons(_env: Env, _cls: JClass, handle: jlong) -> jint {
    on_server("nativePointerButtons", handle, 0, |s| {
        (1..=7u8).filter(|&c| s.input.buttons & crate::proto::input::button_flag(c) != 0).fold(0, |m, c| m | (1 << c))
    })
}

fn jstr(env: &mut Env, s: &JString) -> Option<String> {
    env.get_string(s).ok().map(|j| j.into())
}

/// The name of an atom ("" if unknown).
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xserver_XCore_nativeAtomName<'a>(env: Env<'a>, _cls: JClass, handle: jlong, atom: jint) -> JString<'a> {
    let name = guard_or("nativeAtomName", String::new(), || {
        get(handle).map(|rt| rt.with(|s| s.atoms.get(atom as usize).cloned().unwrap_or_default())).unwrap_or_default()
    });
    env.new_string(name).unwrap_or_else(|_| unsafe { JString::from_raw(std::ptr::null_mut()) })
}

/// A window property as text (Java `Property.toString()`), "" if there is none.
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xserver_XCore_nativePropertyString<'a>(mut env: Env<'a>, _cls: JClass, handle: jlong, window: jint, atom_name: JString) -> JString<'a> {
    let name = jstr(&mut env, &atom_name);
    let text = guard_or("nativePropertyString", String::new(), || match (get(handle), name) {
        (Some(rt), Some(n)) => rt.with(|s| s.property_string(window as u32, &n)),
        _ => String::new(),
    });
    env.new_string(text).unwrap_or_else(|_| unsafe { JString::from_raw(std::ptr::null_mut()) })
}

/// Value number `index` of a window property (0 if there is none).
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xserver_XCore_nativePropertyInt(mut env: Env, _cls: JClass, handle: jlong, window: jint, atom_name: JString, index: jint) -> jint {
    let name = jstr(&mut env, &atom_name);
    guard_or("nativePropertyInt", 0, || match (get(handle), name) {
        (Some(rt), Some(n)) if index >= 0 => rt.with(|s| bridge::property_int(s, window as u32, &n, index as usize)),
        _ => 0,
    })
}

#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xserver_XCore_nativeIsApplicationWindow(_env: Env, _cls: JClass, handle: jlong, window: jint) -> jboolean {
    if on_server("nativeIsApplicationWindow", handle, false, |s| bridge::is_application_window(s, window as u32)) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// The first window whose WM_CLASS contains the executable's name, or 0.
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xserver_XCore_nativeFindWindowByClass(mut env: Env, _cls: JClass, handle: jlong, exe_name: JString) -> jint {
    let name = jstr(&mut env, &exe_name);
    guard_or("nativeFindWindowByClass", 0, || match (get(handle), name) {
        (Some(rt), Some(n)) => rt.with(|s| bridge::find_window_by_class(s, &n)).unwrap_or(0) as jint,
        _ => 0,
    })
}

/// The icon of a window: width and height as two little-endian ints, then width * height * 4
/// R,G,B,A bytes; null if the window has no icon.
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xserver_XCore_nativeWindowIcon<'a>(mut env: Env<'a>, _cls: JClass, handle: jlong, window: jint) -> JByteArray<'a> {
    let icon = guard_or("nativeWindowIcon", None, || get(handle).and_then(|rt| rt.with(|s| bridge::window_icon(s, window as u32))));
    match icon {
        Some((w, h, rgba)) => {
            let mut out = Vec::with_capacity(8 + rgba.len());
            out.extend_from_slice(&w.to_le_bytes());
            out.extend_from_slice(&h.to_le_bytes());
            out.extend_from_slice(&rgba);
            byte_array(&mut env, &out)
        }
        None => null_byte_array(),
    }
}

/// One window as `[id, parent, x, y, width, height, flags, border_width]`, empty if it is gone.
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xserver_XCore_nativeWindowInfo<'a>(mut env: Env<'a>, _cls: JClass, handle: jlong, window: jint) -> JLongArray<'a> {
    let data = guard_or("nativeWindowInfo", Vec::new(), || match get(handle) {
        Some(rt) => rt.with(|s| bridge::window_info(s, window as u32)),
        None => Vec::new(),
    });
    long_array(&mut env, &data)
}

/// The children of a window, bottom-most first.
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xserver_XCore_nativeWindowChildren<'a>(mut env: Env<'a>, _cls: JClass, handle: jlong, window: jint) -> JLongArray<'a> {
    let data = guard_or("nativeWindowChildren", Vec::new(), || match get(handle) {
        Some(rt) => rt.with(|s| bridge::window_children(s, window as u32)),
        None => Vec::new(),
    });
    long_array(&mut env, &data)
}

/// The window and all below it stop receiving input events.
#[no_mangle]
pub extern "system" fn Java_com_winlator_cmod_xserver_XCore_nativeDisableSubtree(_env: Env, _cls: JClass, handle: jlong, window: jint) {
    on_server("nativeDisableSubtree", handle, (), |s| bridge::disable_subtree(s, window as u32))
}
