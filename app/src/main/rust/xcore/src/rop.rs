//! X11 GC raster operations on 24-bit colors.
//!
//! Ordinals match `GraphicsContext.Function` on the Java side:
//! CLEAR, AND, AND_REVERSE, COPY, AND_INVERTED, NO_OP, XOR, OR, NOR, EQUIV, INVERT,
//! OR_REVERSE, COPY_INVERTED, OR_INVERTED, NAND, SET.

const WHITE: u32 = 0x00ff_ffff;
const BLACK: u32 = 0;

/// Combines a source and a destination color. The result may carry garbage above bit 23 (as the
/// C original did); callers keep only the low 24 bits.
#[inline]
pub fn apply(func: i32, src: u32, dst: u32) -> u32 {
    match func {
        0 => BLACK,
        1 => src & dst,
        2 => src & !dst,
        3 => src,
        4 => !src & dst,
        6 => src ^ dst,
        7 => src | dst,
        8 => !src & !dst,
        9 => !src ^ dst,
        10 => !dst,
        11 => src | !dst,
        12 => !src,
        13 => !src | dst,
        14 => !src | !dst,
        15 => WHITE,
        // 5 = NO_OP, and anything unknown leaves the destination alone.
        _ => dst,
    }
}
