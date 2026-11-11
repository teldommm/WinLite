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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn truth_table_on_single_bits() {
        // (src, dst) -> expected per function, on one bit.
        let cases: [(i32, [u32; 4]); 16] = [
            // outputs for (s,d) = (0,0) (0,1) (1,0) (1,1)
            (0, [0, 0, 0, 0]),
            (1, [0, 0, 0, 1]),
            (2, [0, 0, 1, 0]),
            (3, [0, 0, 1, 1]),
            (4, [0, 1, 0, 0]),
            (5, [0, 1, 0, 1]),
            (6, [0, 1, 1, 0]),
            (7, [0, 1, 1, 1]),
            (8, [1, 0, 0, 0]),
            (9, [1, 0, 0, 1]),
            (10, [1, 0, 1, 0]),
            (11, [1, 0, 1, 1]),
            (12, [1, 1, 0, 0]),
            (13, [1, 1, 0, 1]),
            (14, [1, 1, 1, 0]),
            (15, [1, 1, 1, 1]),
        ];
        // X11 function numbering is the GX* one; Java's enum order differs, so map by name.
        // Java order: CLEAR AND AND_REVERSE COPY AND_INVERTED NO_OP XOR OR NOR EQUIV INVERT
        //             OR_REVERSE COPY_INVERTED OR_INVERTED NAND SET
        let java_to_gx = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15];
        for (java, gx) in java_to_gx.iter().enumerate() {
            let expected = cases[*gx as usize].1;
            for (i, (s, d)) in [(0u32, 0u32), (0, 1), (1, 0), (1, 1)].iter().enumerate() {
                let got = apply(java as i32, *s, *d) & 1;
                assert_eq!(got, expected[i], "func {java} s={s} d={d}");
            }
        }
    }

    #[test]
    fn named_cases() {
        assert_eq!(apply(0, 0x123456, 0xabcdef), 0);
        assert_eq!(apply(15, 0x123456, 0xabcdef), 0x00ff_ffff);
        assert_eq!(apply(3, 0x123456, 0xabcdef), 0x123456);
        assert_eq!(apply(5, 0x123456, 0xabcdef), 0xabcdef);
        assert_eq!(apply(6, 0xff00ff, 0x0f0f0f) & 0xff_ffff, 0xf00ff0);
        assert_eq!(apply(99, 1, 2), 2);
        assert_eq!(apply(-1, 1, 2), 2);
    }
}
