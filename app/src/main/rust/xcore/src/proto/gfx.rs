//! Drawables (window contents, pixmaps, cursor images) and graphics contexts.
//!
//! Every operation clips exactly like the Java `Drawable` did and then calls the pixel code of
//! Phase 1 (`crate::pixels`). The pixel memory sits behind [`PixelStore`] so the same code runs
//! on an AHardwareBuffer (Android) and on plain host memory (tests).

use crate::pixels::{self, Surface};
use std::cell::UnsafeCell;
use std::sync::{Arc, Mutex};

/// Pixel memory of one drawable. `with_surface` hands out the (locked) memory for the duration
/// of the closure; `false` means the buffer is gone or could not be locked.
pub trait PixelStore: Send {
    fn stride(&self) -> i32;
    fn with_surface(&self, f: &mut dyn FnMut(&Surface)) -> bool;
    /// The platform handle of the memory (an AHardwareBuffer pointer on Android), 0 if it has
    /// none. Not a reference: the caller acquires its own while the server lock is held.
    fn native_handle(&self) -> i64 {
        0
    }
}

/// Pixel memory that stays mapped for the CPU between two operations (an AHardwareBuffer stays
/// locked, so a burst of small requests locks it once). The owner hands itself to [`hold`] when it
/// maps; whoever holds the server lock calls [`release_held`] before it lets go of it, which is
/// the point where the memory must be coherent for the renderer and the clients.
pub trait Held: Send + Sync {
    /// Unmaps the memory (and with it flushes the CPU's writes).
    fn release(&self);
}

static HELD: Mutex<Vec<Arc<dyn Held>>> = Mutex::new(Vec::new());

/// Registers `h` as mapped until the next [`release_held`].
pub fn hold(h: Arc<dyn Held>) {
    HELD.lock().unwrap_or_else(|e| e.into_inner()).push(h);
}

/// Releases everything [`hold`] registered. Called with the server lock held, just before it is
/// released.
pub fn release_held() {
    let held = std::mem::take(&mut *HELD.lock().unwrap_or_else(|e| e.into_inner()));
    for h in held {
        h.release();
    }
}

/// Pixels in ordinary memory (host tests and the host test server).
pub struct HostPixels {
    data: UnsafeCell<Box<[u32]>>,
    stride: usize,
    rgba: bool,
}

// SAFETY: access is serialised by the owner (the server state lives behind one lock).
unsafe impl Send for HostPixels {}

impl HostPixels {
    pub fn new(width: usize, height: usize, rgba: bool) -> HostPixels {
        let stride = width.max(1);
        HostPixels {
            data: UnsafeCell::new(vec![0u32; stride * height.max(1)].into_boxed_slice()),
            stride,
            rgba,
        }
    }

    /// Row-major copy of the whole buffer (tests).
    pub fn snapshot(&self) -> Vec<u32> {
        unsafe { (*self.data.get()).to_vec() }
    }
}

impl PixelStore for HostPixels {
    fn stride(&self) -> i32 {
        self.stride as i32
    }

    fn with_surface(&self, f: &mut dyn FnMut(&Surface)) -> bool {
        let data = unsafe { &mut *self.data.get() };
        let s = unsafe { Surface::new(data.as_mut_ptr() as *mut u8, self.stride, data.len(), self.rgba) };
        match s {
            Some(s) => {
                f(&s);
                true
            }
            None => false,
        }
    }
}

pub struct Drawable {
    pub id: u32,
    pub width: i16,
    pub height: i16,
    /// Depth of the drawable's visual / pixmap format.
    pub depth: u8,
    /// Visual id, `None` for drawables created with a depth that has no visual (the Java code had
    /// a null visual there and failed on first use).
    pub visual: Option<u32>,
    pub store: Box<dyn PixelStore>,
    pub offscreen: bool,
    /// DRI3: the window this pixmap was imported for; drawing to it updates that window directly.
    pub direct_window: Option<u32>,
}

fn clamp(v: i32, lo: i32, hi: i32) -> i32 {
    if v < lo {
        lo
    } else if v > hi {
        hi
    } else {
        v
    }
}

impl Drawable {
    pub fn stride(&self) -> i32 {
        self.store.stride()
    }

    /// PutImage (Z_PIXMAP or depth-1 bitmap). Returns whether the "content changed" listener of
    /// the Java drawable would have run.
    #[allow(clippy::too_many_arguments)]
    pub fn draw_image(&self, src_x: i16, src_y: i16, dst_x: i16, dst_y: i16, w: i16, h: i16, depth: u8, data: &[u8], total_w: i16) -> bool {
        let stride = self.stride();
        if depth == 1 {
            self.store.with_surface(&mut |s| {
                pixels::draw_bitmap(s, data, dst_x as i32, dst_y as i32, w as i32, h as i32, self.width as i32, self.height as i32);
            });
        } else if depth == 24 || depth == 32 {
            let (mut sx, mut sy, mut dx, mut dy, mut ww, mut hh) =
                (src_x as i32, src_y as i32, dst_x as i32, dst_y as i32, w as i32, h as i32);
            if dx < 0 {
                sx -= dx;
                ww += dx;
                dx = 0;
            }
            if dy < 0 {
                sy -= dy;
                hh += dy;
                dy = 0;
            }
            if dx + ww > self.width as i32 {
                ww = self.width as i32 - dx;
            }
            if dy + hh > self.height as i32 {
                hh = self.height as i32 - dy;
            }
            if ww <= 0 || hh <= 0 || sx < 0 || sy < 0 {
                return false;
            }
            let _ = stride;
            self.store.with_surface(&mut |s| {
                s.put_image(data, total_w as i32, sx, sy, dx, dy, ww, hh);
            });
        }
        true
    }

    /// GetImage: `w*h*4` bytes, rows packed with the clamped width (as the Java code did).
    pub fn get_image(&self, x: i16, y: i16, w: i16, h: i16) -> Option<Vec<u8>> {
        let total = (w as i32).checked_mul(h as i32)?.checked_mul(4)?;
        // A reply this large cannot be sent anyway (and would abort on allocation failure).
        if !(0..=1 << 28).contains(&total) {
            return None;
        }
        let mut out = vec![0u8; total as usize];
        let x = clamp(x as i32, 0, self.width as i32 - 1);
        let y = clamp(y as i32, 0, self.height as i32 - 1);
        let mut w = w as i32;
        let mut h = h as i32;
        if x + w > self.width as i32 {
            w = self.width as i32 - x;
        }
        if y + h > self.height as i32 {
            h = self.height as i32 - y;
        }
        let w_c = w;
        self.store.with_surface(&mut |s| {
            s.get_image(&mut out, w_c, x, y, 0, 0, w_c, h);
        });
        Some(out)
    }

    pub fn fill_color(&self, color: i32) {
        self.fill_rect(0, 0, self.width as i32, self.height as i32, color);
    }

    /// Returns whether anything was drawn (the Java method skipped the listener otherwise).
    pub fn fill_rect(&self, mut x: i32, mut y: i32, mut w: i32, mut h: i32, color: i32) -> bool {
        if x < 0 {
            w += x;
            x = 0;
        }
        if y < 0 {
            h += y;
            y = 0;
        }
        if x + w > self.width as i32 {
            w = self.width as i32 - x;
        }
        if y + h > self.height as i32 {
            h = self.height as i32 - y;
        }
        if w <= 0 || h <= 0 {
            return false;
        }
        self.store.with_surface(&mut |s| {
            s.fill_rect(x, y, w, h, color);
        })
    }

    /// PolyFillRectangle: every rectangle clipped, one buffer lock for all.
    pub fn fill_rects(&self, rects: &[i16], color: i32) -> bool {
        if rects.len() < 4 {
            return false;
        }
        let mut clipped: Vec<i16> = Vec::with_capacity(rects.len());
        for q in rects.chunks_exact(4) {
            let (mut x, mut y, mut w, mut h) = (q[0] as i32, q[1] as i32, q[2] as i32, q[3] as i32);
            if x < 0 {
                w += x;
                x = 0;
            }
            if y < 0 {
                h += y;
                y = 0;
            }
            if x + w > self.width as i32 {
                w = self.width as i32 - x;
            }
            if y + h > self.height as i32 {
                h = self.height as i32 - y;
            }
            if w <= 0 || h <= 0 {
                continue;
            }
            clipped.extend_from_slice(&[x as i16, y as i16, w as i16, h as i16]);
        }
        let count = clipped.len() / 4;
        if count == 0 {
            return false;
        }
        self.store.with_surface(&mut |s| {
            s.fill_rects(&clipped, count, color);
        })
    }

    /// PolyLine (CoordinateMode Origin): endpoints clamped so the square pen fits.
    pub fn draw_lines(&self, color: i32, line_width: i32, points: &[i16]) -> bool {
        if points.len() < 4 {
            return false;
        }
        if line_width <= 0 || line_width > self.width as i32 || line_width > self.height as i32 {
            return false;
        }
        let count = points.len() / 2;
        let mut clamped = Vec::with_capacity(count * 2);
        for i in 0..count {
            clamped.push(clamp(points[i * 2] as i32, 0, self.width as i32 - line_width) as i16);
            clamped.push(clamp(points[i * 2 + 1] as i32, 0, self.height as i32 - line_width) as i16);
        }
        self.store.with_surface(&mut |s| {
            s.draw_polyline(&clamped, count, color, line_width);
        })
    }
}

impl Drawable {
    /// PolySegment: `segs` holds (x1, y1, x2, y2) quadruples, drawn with a square pen of
    /// `line_width` (0 is the thin line, one pixel). Endpoints are clamped like PolyLine's.
    pub fn draw_segments(&self, color: i32, line_width: i32, segs: &[i16]) -> bool {
        let lw = line_width.max(1);
        if segs.len() < 4 || lw > self.width as i32 || lw > self.height as i32 {
            return false;
        }
        let (max_x, max_y) = (self.width as i32 - lw, self.height as i32 - lw);
        let mut clamped: Vec<i16> = Vec::with_capacity(segs.len());
        for q in segs.chunks_exact(4) {
            clamped.push(clamp(q[0] as i32, 0, max_x) as i16);
            clamped.push(clamp(q[1] as i32, 0, max_y) as i16);
            clamped.push(clamp(q[2] as i32, 0, max_x) as i16);
            clamped.push(clamp(q[3] as i32, 0, max_y) as i16);
        }
        self.store.with_surface(&mut |s| {
            for q in clamped.chunks_exact(4) {
                s.draw_polyline(q, 2, color, lw);
            }
        })
    }

    /// PolyRectangle: `rects` holds (x, y, width, height) quadruples (the width and height are
    /// unsigned on the wire, so they come as `i32`); each outline is a pen of `line_width`
    /// (0 = one pixel) centred on the rectangle's edges. Drawn as up to four strips each.
    pub fn stroke_rects(&self, rects: &[(i32, i32, i32, i32)], color: i32, line_width: i32) -> bool {
        let lw = line_width.max(1);
        let left = lw / 2;
        let mut strips: Vec<(i32, i32, i32, i32)> = Vec::with_capacity(rects.len() * 4);
        for &(x, y, w, h) in rects {
            let (ox, oy, ow, oh) = (x - left, y - left, w + lw, h + lw);
            if ow <= 2 * lw || oh <= 2 * lw {
                strips.push((ox, oy, ow, oh)); // no hollow inside: the whole box
                continue;
            }
            strips.push((ox, oy, ow, lw));
            strips.push((ox, oy + oh - lw, ow, lw));
            strips.push((ox, oy + lw, lw, oh - 2 * lw));
            strips.push((ox + ow - lw, oy + lw, lw, oh - 2 * lw));
        }
        self.fill_boxes(&strips, color)
    }

    /// Clips every box to the drawable and fills them under one buffer lock.
    fn fill_boxes(&self, boxes: &[(i32, i32, i32, i32)], color: i32) -> bool {
        let mut clipped: Vec<i16> = Vec::with_capacity(boxes.len() * 4);
        for &(mut x, mut y, mut w, mut h) in boxes {
            if x < 0 {
                w += x;
                x = 0;
            }
            if y < 0 {
                h += y;
                y = 0;
            }
            if x + w > self.width as i32 {
                w = self.width as i32 - x;
            }
            if y + h > self.height as i32 {
                h = self.height as i32 - y;
            }
            if w <= 0 || h <= 0 {
                continue;
            }
            clipped.extend_from_slice(&[x as i16, y as i16, w as i16, h as i16]);
        }
        let count = clipped.len() / 4;
        if count == 0 {
            return false;
        }
        self.store.with_surface(&mut |s| {
            s.fill_rects(&clipped, count, color);
        })
    }
}

/// CopyArea from `src` to `dst` (clipped against both). `func` is the GC function (0..15, 3 = COPY).
/// Returns whether the destination changed (the Java listener).
#[allow(clippy::too_many_arguments)]
pub fn copy_area(src: &Drawable, dst: &Drawable, sx: i16, sy: i16, dx: i16, dy: i16, w: i16, h: i16, func: i32) -> bool {
    let (mut sx, mut sy, mut dx, mut dy, mut w, mut h) =
        (sx as i32, sy as i32, dx as i32, dy as i32, w as i32, h as i32);
    if sx < 0 {
        dx -= sx;
        w += sx;
        sx = 0;
    }
    if sy < 0 {
        dy -= sy;
        h += sy;
        sy = 0;
    }
    if dx < 0 {
        sx -= dx;
        w += dx;
        dx = 0;
    }
    if dy < 0 {
        sy -= dy;
        h += dy;
        dy = 0;
    }
    w = w.min((src.width as i32 - sx).min(dst.width as i32 - dx));
    h = h.min((src.height as i32 - sy).min(dst.height as i32 - dy));
    if w <= 0 || h <= 0 {
        return false;
    }
    if src.id == dst.id {
        dst.store.with_surface(&mut |s| {
            if func == 3 {
                pixels::copy_area(s, s, true, sx, sy, dx, dy, w, h);
            } else {
                pixels::copy_area_op(s, s, true, sx, sy, dx, dy, w, h, func);
            }
        })
    } else {
        // Lock the source first, then the destination: nesting is the only way to have both.
        let mut ok = false;
        src.store.with_surface(&mut |ss| {
            ok = dst.store.with_surface(&mut |ds| {
                if func == 3 {
                    pixels::copy_area(ds, ss, false, sx, sy, dx, dy, w, h);
                } else {
                    pixels::copy_area_op(ds, ss, false, sx, sy, dx, dy, w, h, func);
                }
            });
        });
        ok
    }
}

/// Glyph/cursor compositing into `dst` from a source and a mask drawable of the same size.
pub fn draw_alpha_masked(dst: &Drawable, src: &Drawable, mask: &Drawable, fore: u32, back: u32) -> bool {
    let (w, h) = (dst.width as i32, dst.height as i32);
    let mut ok = false;
    src.store.with_surface(&mut |ss| {
        mask.store.with_surface(&mut |ms| {
            ok = dst.store.with_surface(&mut |ds| {
                pixels::draw_alpha_masked_bitmap(ds, ss, ms, fore, back, w, h);
            });
        });
    });
    ok
}

/// GC function codes (X11 GX order, identical to the Java `Function` enum order).
pub const GX_COPY: i32 = 3;

pub struct Gc {
    pub id: u32,
    /// Drawable the GC was created for (kept as an id: it may be gone later).
    pub drawable: u32,
    pub function: i32,
    pub background: u32,
    pub foreground: u32,
    pub line_width: i32,
    pub plane_mask: i32,
    pub subwindow_mode: i32,
}

impl Gc {
    pub fn new(id: u32, drawable: u32) -> Gc {
        Gc { id, drawable, function: GX_COPY, background: 0xffffff, foreground: 0, line_width: 1, plane_mask: -1, subwindow_mode: 0 }
    }
}

pub const FLAG_FUNCTION: u32 = 1 << 0;
pub const FLAG_PLANE_MASK: u32 = 1 << 1;
pub const FLAG_FOREGROUND: u32 = 1 << 2;
pub const FLAG_BACKGROUND: u32 = 1 << 3;
pub const FLAG_LINE_WIDTH: u32 = 1 << 4;
pub const FLAG_SUBWINDOW_MODE: u32 = 1 << 15;
