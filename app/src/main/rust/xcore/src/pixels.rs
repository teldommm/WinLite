//! Pixel operations of the X server on 32-bit surfaces (the port of `drawable.c`).
//!
//! Pure code: no Android API, no JNI. A [`Surface`] describes memory that is already mapped (an
//! AHardwareBuffer that the JNI layer has locked); every operation checks its rectangle against
//! the surface's real capacity before touching memory. The C original trusted the Java side to
//! clip, so a wrong rectangle was a write past the buffer; here it is a rejected request
//! (`false`), which the caller logs.
//!
//! Channel order: the X visual is fixed (red 0xff0000, green 0x00ff00, blue 0x0000ff), so all
//! pixels that cross the X protocol are B,G,R,A in memory. A surface whose buffer is RGBA
//! (`rgba == true`) gets red and blue exchanged wherever pixels cross between the protocol and
//! the buffer; copies between two buffers of the same order are plain copies.

use crate::rop;
use std::ptr;
use std::slice;

const WHITE: u32 = 0x00ff_ffff;
const BLACK: u32 = 0;

/// Exchanges the first and the third byte of a 32-bit pixel (B,G,R,A <-> R,G,B,A in memory).
#[inline(always)]
pub fn swap_rb32(p: u32) -> u32 {
    (p & 0xff00_ff00) | ((p & 0x0000_00ff) << 16) | ((p >> 16) & 0x0000_00ff)
}

/// Opaque pixel for an X color (0xRRGGBB) in the byte order of the surface.
#[inline(always)]
pub fn solid_pixel(color: i32, rgba: bool) -> u32 {
    let p = (color as u32 & 0x00ff_ffff) | 0xff00_0000;
    if rgba {
        swap_rb32(p)
    } else {
        p
    }
}

/// `count` pixels from `src` (any alignment: client memory) to `dst`, red and blue exchanged.
///
/// # Safety
/// `src` readable and `dst` writable for `count * 4` bytes, not overlapping, `dst` 4-aligned.
#[inline]
unsafe fn copy_row_swap_rb(dst: *mut u32, src: *const u8, count: usize) {
    let src = src as *const u32;
    for i in 0..count {
        *dst.add(i) = swap_rb32(ptr::read_unaligned(src.add(i)));
    }
}

/// A rectangle with validated (non-negative, non-empty) geometry.
#[derive(Clone, Copy, Debug)]
struct Rect {
    x: usize,
    y: usize,
    w: usize,
    h: usize,
}

impl Rect {
    fn new(x: i32, y: i32, w: i32, h: i32) -> Option<Rect> {
        if x < 0 || y < 0 || w <= 0 || h <= 0 {
            return None;
        }
        Some(Rect { x: x as usize, y: y as usize, w: w as usize, h: h as usize })
    }
}

/// Memory of a locked buffer: `capacity` pixels from `base`, rows `stride` pixels apart.
#[derive(Clone, Copy)]
pub struct Surface {
    base: *mut u8,
    stride: usize,
    capacity: usize,
    rgba: bool,
}

impl Surface {
    /// # Safety
    /// `base` must be valid for reads and writes of `capacity * 4` bytes for as long as the
    /// surface is used, and 4-byte aligned (checked; `None` otherwise).
    pub unsafe fn new(base: *mut u8, stride: usize, capacity: usize, rgba: bool) -> Option<Surface> {
        if base.is_null() || (base as usize) & 3 != 0 || stride == 0 || capacity == 0 {
            return None;
        }
        Some(Surface { base, stride, capacity, rgba })
    }

    pub fn is_rgba(&self) -> bool {
        self.rgba
    }

    /// Whether the rectangle lies inside the surface: within one row pitch horizontally and
    /// inside the allocation vertically.
    fn fits(&self, r: Rect) -> bool {
        match r.x.checked_add(r.w) {
            Some(right) if right <= self.stride => {}
            _ => return false,
        }
        match (r.y + r.h - 1)
            .checked_mul(self.stride)
            .and_then(|v| v.checked_add(r.x + r.w))
        {
            Some(last) => last <= self.capacity,
            None => false,
        }
    }

    #[inline(always)]
    unsafe fn row_ptr(&self, x: usize, y: usize) -> *mut u32 {
        (self.base as *mut u32).add(x + y * self.stride)
    }

    #[inline(always)]
    unsafe fn bytes(&self) -> *mut u8 {
        self.base
    }

    unsafe fn fill_unchecked(&self, r: Rect, pixel: u32) {
        for row in 0..r.h {
            slice::from_raw_parts_mut(self.row_ptr(r.x, r.y + row), r.w).fill(pixel);
        }
    }

    /// PolyFillRectangle element / single fill. `false` if the rectangle is empty or outside.
    pub fn fill_rect(&self, x: i32, y: i32, w: i32, h: i32, color: i32) -> bool {
        let Some(r) = Rect::new(x, y, w, h) else { return false };
        if !self.fits(r) {
            return false;
        }
        unsafe { self.fill_unchecked(r, solid_pixel(color, self.rgba)) };
        true
    }

    /// `rects` holds (x, y, w, h) quadruples; returns how many were rejected.
    pub fn fill_rects(&self, rects: &[i16], count: usize, color: i32) -> usize {
        let pixel = solid_pixel(color, self.rgba);
        let mut rejected = 0;
        for q in rects.chunks_exact(4).take(count) {
            match Rect::new(q[0] as i32, q[1] as i32, q[2] as i32, q[3] as i32) {
                Some(r) if self.fits(r) => unsafe { self.fill_unchecked(r, pixel) },
                _ => rejected += 1,
            }
        }
        rejected
    }

    /// Bresenham line with a square pen of `lw` pixels. The caller has validated that the pen
    /// stays inside the surface along the whole segment (its bounding box).
    unsafe fn plot_line(&self, x0: i32, y0: i32, x1: i32, y1: i32, pixel: u32, lw: usize) {
        let dx = (x1 - x0).abs();
        let dy = -(y1 - y0).abs();
        let sx = if x0 < x1 { 1 } else { -1 };
        let sy = if y0 < y1 { 1 } else { -1 };
        let mut e1 = dx + dy;
        let (mut px, mut py) = (x0, y0);
        loop {
            for i in 0..lw {
                slice::from_raw_parts_mut(self.row_ptr(px as usize, py as usize + i), lw).fill(pixel);
            }
            if px == x1 && py == y1 {
                break;
            }
            let e2 = e1 * 2;
            if e2 >= dy {
                e1 += dy;
                px += sx;
            }
            if e2 <= dx {
                e1 += dx;
                py += sy;
            }
        }
    }

    /// `points` holds (x, y) pairs; one segment between each consecutive pair.
    pub fn draw_polyline(&self, points: &[i16], count: usize, color: i32, line_width: i32) -> bool {
        if line_width <= 0 || count < 2 || points.len() < count * 2 {
            return false;
        }
        let pts = &points[..count * 2];
        let (mut min_x, mut min_y, mut max_x, mut max_y) = (i32::MAX, i32::MAX, i32::MIN, i32::MIN);
        for p in pts.chunks_exact(2) {
            min_x = min_x.min(p[0] as i32);
            max_x = max_x.max(p[0] as i32);
            min_y = min_y.min(p[1] as i32);
            max_y = max_y.max(p[1] as i32);
        }
        let lw = line_width;
        let Some(bounds) = Rect::new(min_x, min_y, max_x - min_x + lw, max_y - min_y + lw) else {
            return false;
        };
        if !self.fits(bounds) {
            return false;
        }
        let pixel = solid_pixel(color, self.rgba);
        for n in 1..count {
            unsafe {
                self.plot_line(
                    pts[n * 2 - 2] as i32,
                    pts[n * 2 - 1] as i32,
                    pts[n * 2] as i32,
                    pts[n * 2 + 1] as i32,
                    pixel,
                    lw as usize,
                );
            }
        }
        true
    }

    pub fn draw_line(&self, x0: i32, y0: i32, x1: i32, y1: i32, color: i32, line_width: i32) -> bool {
        let pts = [x0 as i16, y0 as i16, x1 as i16, y1 as i16];
        self.draw_polyline(&pts, 2, color, line_width)
    }

    /// Cheap argument check of a PutImage, so a request that cannot succeed does not even lock
    /// the destination buffer (`src_len`: bytes of client memory).
    pub fn put_image_args_ok(src_len: usize, src_stride: i32, sx: i32, sy: i32, w: i32, h: i32) -> bool {
        if w <= 0 || h <= 0 || sx < 0 || sy < 0 || src_stride <= 0 || sx + w > src_stride {
            return false;
        }
        ((sy as usize + h as usize - 1) * src_stride as usize + sx as usize + w as usize) * 4 <= src_len
    }

    /// PutImage / MIT-SHM PutImage, Z_PIXMAP: `src` is client memory (B,G,R,A rows of
    /// `src_stride` pixels), the source rectangle starts at (sx, sy).
    pub fn put_image(
        &self,
        src: &[u8],
        src_stride: i32,
        sx: i32,
        sy: i32,
        dx: i32,
        dy: i32,
        w: i32,
        h: i32,
    ) -> bool {
        if w <= 0 || h <= 0 || sx < 0 || sy < 0 || src_stride <= 0 || sx + w > src_stride {
            return false;
        }
        let Some(dst) = Rect::new(dx, dy, w, h) else { return false };
        if !self.fits(dst) {
            return false;
        }
        // The source is client memory: never read past it.
        let needed = ((sy as usize + h as usize - 1) * src_stride as usize + sx as usize + w as usize) * 4;
        if needed > src.len() {
            return false;
        }
        let (w, h, ss) = (w as usize, h as usize, src_stride as usize);
        unsafe {
            for y in 0..h {
                let d = self.row_ptr(dst.x, dst.y + y);
                let s = src.as_ptr().add((sx as usize + (sy as usize + y) * ss) * 4);
                if self.rgba {
                    copy_row_swap_rb(d, s, w);
                } else {
                    ptr::copy_nonoverlapping(s, d as *mut u8, w * 4);
                }
            }
        }
        true
    }

    /// GetImage: copies the rectangle at (sx, sy) of this surface into `dst` (rows of
    /// `dst_stride` pixels, at (dx, dy)), as B,G,R,A.
    pub fn get_image(
        &self,
        dst: &mut [u8],
        dst_stride: i32,
        sx: i32,
        sy: i32,
        dx: i32,
        dy: i32,
        w: i32,
        h: i32,
    ) -> bool {
        let Some(src) = Rect::new(sx, sy, w, h) else { return false };
        if !self.fits(src) || dx < 0 || dy < 0 || dst_stride <= 0 || dx + w > dst_stride {
            return false;
        }
        let needed = ((dy as usize + h as usize - 1) * dst_stride as usize + dx as usize + w as usize) * 4;
        if needed > dst.len() {
            return false;
        }
        let (w, h, ds) = (w as usize, h as usize, dst_stride as usize);
        unsafe {
            for y in 0..h {
                let s = self.row_ptr(src.x, src.y + y) as *const u8;
                let d = dst.as_mut_ptr().add((dx as usize + (dy as usize + y) * ds) * 4);
                if self.rgba {
                    // Destination is client-reply memory: any alignment.
                    for i in 0..w {
                        let p = ptr::read(s.add(i * 4) as *const u32);
                        ptr::write_unaligned(d.add(i * 4) as *mut u32, swap_rb32(p));
                    }
                } else {
                    ptr::copy_nonoverlapping(s, d, w * 4);
                }
            }
        }
        true
    }
}

/// CopyArea between two surfaces (`same`: both are one and the same buffer, e.g. scrolling).
/// Overlap-safe: rows are walked away from the direction of the move and each row is a memmove.
pub fn copy_area(
    dst: &Surface,
    src: &Surface,
    same: bool,
    sx: i32,
    sy: i32,
    dx: i32,
    dy: i32,
    w: i32,
    h: i32,
) -> bool {
    let (Some(sr), Some(dr)) = (Rect::new(sx, sy, w, h), Rect::new(dx, dy, w, h)) else {
        return false;
    };
    if !src.fits(sr) || !dst.fits(dr) {
        return false;
    }
    let bottom_up = same && dy > sy;
    // Two buffers of different channel order (never the same buffer, so no overlap) convert.
    let swap = !same && src.rgba != dst.rgba;
    let (w, h) = (w as usize, h as usize);
    unsafe {
        for i in 0..h {
            let y = if bottom_up { h - 1 - i } else { i };
            let d = dst.row_ptr(dr.x, dr.y + y);
            let s = src.row_ptr(sr.x, sr.y + y) as *const u8;
            if swap {
                copy_row_swap_rb(d, s, w);
            } else {
                ptr::copy(s, d as *mut u8, w * 4);
            }
        }
    }
    true
}

/// CopyArea with a GC function other than COPY: the raster operation combines the colors of
/// each pixel; the alpha byte of the destination is left alone.
pub fn copy_area_op(
    dst: &Surface,
    src: &Surface,
    same: bool,
    sx: i32,
    sy: i32,
    dx: i32,
    dy: i32,
    w: i32,
    h: i32,
    func: i32,
) -> bool {
    let (Some(sr), Some(dr)) = (Rect::new(sx, sy, w, h), Rect::new(dx, dy, w, h)) else {
        return false;
    };
    if !src.fits(sr) || !dst.fits(dr) {
        return false;
    }
    let bottom_up = same && dy > sy;
    let right_to_left = same && dy == sy && dx > sx;
    // The operation combines colors, so the source must be in the destination's order.
    let swap_src = !same && src.rgba != dst.rgba;
    let (w, h) = (w as usize, h as usize);
    unsafe {
        let sb = src.bytes();
        let db = dst.bytes();
        for i in 0..h {
            let y = if bottom_up { h - 1 - i } else { i };
            for k in 0..w {
                let x = if right_to_left { w - 1 - k } else { k };
                let si = ((sr.x + x) + (sr.y + y) * src.stride) * 4;
                let di = ((dr.x + x) + (dr.y + y) * dst.stride) * 4;
                let (s0, s1, s2) = (*sb.add(si) as u32, *sb.add(si + 1) as u32, *sb.add(si + 2) as u32);
                let src_color = if swap_src { (s2 << 16) | (s1 << 8) | s0 } else { (s0 << 16) | (s1 << 8) | s2 };
                let dst_color = ((*db.add(di) as u32) << 16) | ((*db.add(di + 1) as u32) << 8) | *db.add(di + 2) as u32;
                let out = rop::apply(func, src_color, dst_color);
                *db.add(di) = (out >> 16) as u8;
                *db.add(di + 1) = (out >> 8) as u8;
                *db.add(di + 2) = out as u8;
            }
        }
    }
    true
}

/// Rows of a 1-bit X bitmap are padded to 32 bits.
fn bitmap_row_bytes(width: usize) -> usize {
    ((width + 31) >> 5) << 2
}

/// PutImage of a 1-bit bitmap at (dst_x, dst_y), clipped to `dst_w` x `dst_h` (the drawable's
/// size). Set bits become 0x00ffffff, clear bits 0 (the alpha byte is zero, as it always was).
pub fn draw_bitmap(
    dst: &Surface,
    src: &[u8],
    dst_x: i32,
    dst_y: i32,
    width: i32,
    height: i32,
    dst_w: i32,
    dst_h: i32,
) -> bool {
    if width <= 0 || height <= 0 || dst_w <= 0 || dst_h <= 0 {
        return false;
    }
    let width = width as usize;
    let row_bytes = bitmap_row_bytes(width);
    // Never read past what the client sent.
    let height = (height as usize).min(src.len() / row_bytes);
    if height == 0 {
        return false;
    }
    // The clip region must lie inside the surface.
    let (dst_w, dst_h) = (dst_w as usize, dst_h as usize);
    let Some(region) = Rect::new(0, 0, dst_w as i32, dst_h as i32) else { return false };
    if !dst.fits(region) {
        return false;
    }
    for y in 0..height {
        let dy = dst_y as i64 + y as i64;
        if dy < 0 {
            continue;
        }
        if dy >= dst_h as i64 {
            break;
        }
        let row = &src[y * row_bytes..(y + 1) * row_bytes];
        for x in 0..width {
            let dx = dst_x as i64 + x as i64;
            if dx < 0 {
                continue;
            }
            if dx >= dst_w as i64 {
                break;
            }
            let bit = row[x >> 3] & (1 << (x & 7)) != 0;
            unsafe { *dst.row_ptr(dx as usize, dy as usize) = if bit { WHITE } else { BLACK } };
        }
    }
    true
}

/// Glyph/cursor compositing: where `mask` is white the pixel is `fore` (source white) or `back`,
/// opaque; elsewhere it is fully transparent. `fore`/`back` are 0xRRGGBB.
pub fn draw_alpha_masked_bitmap(
    dst: &Surface,
    src: &Surface,
    mask: &Surface,
    fore: u32,
    back: u32,
    w: i32,
    h: i32,
) -> bool {
    let Some(r) = Rect::new(0, 0, w, h) else { return false };
    if !dst.fits(r) || !src.fits(r) || !mask.fits(r) {
        return false;
    }
    let (mut fore, mut back) = (fore, back);
    if dst.rgba {
        fore = swap_rb32(fore);
        back = swap_rb32(back);
    }
    unsafe {
        for y in 0..r.h {
            let d = dst.row_ptr(0, y);
            let s = src.row_ptr(0, y);
            let m = mask.row_ptr(0, y);
            for x in 0..r.w {
                *d.add(x) = if *m.add(x) == WHITE {
                    (if *s.add(x) == WHITE { fore } else { back }) | 0xff00_0000
                } else {
                    0
                };
            }
        }
    }
    true
}

/// Converts a pixmap (and optional mask pixmap, whose first byte is the alpha) into the R,G,B,A
/// bytes of an Android Bitmap: `out` has rows `out_stride` bytes apart.
pub fn to_bitmap(
    color: &Surface,
    mask: Option<&Surface>,
    out: &mut [u8],
    out_stride: usize,
    width: usize,
    height: usize,
) -> bool {
    if width == 0 || height == 0 || out_stride < width * 4 {
        return false;
    }
    if out.len() < (height - 1) * out_stride + width * 4 {
        return false;
    }
    let (Some(r), true) = (Rect::new(0, 0, width as i32, height as i32), true) else {
        return false;
    };
    if !color.fits(r) || mask.map_or(false, |m| !m.fits(r)) {
        return false;
    }
    let src_rgba = color.rgba;
    unsafe {
        for y in 0..height {
            let s = color.row_ptr(0, y) as *const u8;
            let m = mask.map(|m| m.row_ptr(0, y) as *const u8);
            let d = out.as_mut_ptr().add(y * out_stride);
            for x in 0..width {
                let sp = s.add(x * 4);
                let dp = d.add(x * 4);
                // A BGRA source needs red and blue exchanged; an RGBA one is in order already.
                *dp.add(2) = *sp.add(if src_rgba { 2 } else { 0 });
                *dp.add(1) = *sp.add(1);
                *dp = *sp.add(if src_rgba { 0 } else { 2 });
                *dp.add(3) = match m {
                    Some(m) => *m.add(x * 4),
                    None => *sp.add(3),
                };
            }
        }
    }
    true
}
