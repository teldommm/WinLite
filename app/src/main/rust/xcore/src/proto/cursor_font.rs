//! The X "cursor" font for CreateGlyphCursor, and the colouring shared with RecolorCursor.
//!
//! The 77 standard glyphs (`XC_arrow`, `XC_watch`, ...) with their masks come from the x++ server
//! (`ludashix11/core/cursor/data.h`). `cursor_font.bin` holds them as 154 bitmaps (glyph `2n`, its
//! mask `2n + 1`) of 31x31 pixels, one bit per pixel, row by row, least significant bit first. All
//! glyphs are drawn so that the hot spot is the pixel (15, 15).

const SIZE: usize = 31;
const GLYPH_BYTES: usize = (SIZE * SIZE + 7) / 8;
const GLYPHS: usize = 154;

pub const WIDTH: i16 = SIZE as i16;
pub const HEIGHT: i16 = SIZE as i16;
pub const HOT: i16 = 15;

static DATA: &[u8] = include_bytes!("cursor_font.bin");

const _: () = assert!(GLYPHS * GLYPH_BYTES == include_bytes!("cursor_font.bin").len());

/// One byte per pixel (1 = set) of the font character `id`, `WIDTH * HEIGHT` of them.
pub fn glyph(id: u32) -> Option<Vec<u8>> {
    let id = id as usize;
    if id >= GLYPHS {
        return None;
    }
    let g = DATA.get(id * GLYPH_BYTES..(id + 1) * GLYPH_BYTES)?;
    Some((0..SIZE * SIZE).map(|i| (g[i / 8] >> (i % 8)) & 1).collect())
}

/// Shape (0 transparent, 1 foreground, 2 background per pixel) of a glyph cursor. With no mask
/// glyph the mask is the glyph itself.
pub fn shape(source: &[u8], mask: Option<&[u8]>) -> Vec<u8> {
    (0..source.len())
        .map(|i| {
            let visible = match mask {
                Some(m) => m.get(i).copied().unwrap_or(0) != 0,
                None => source[i] != 0,
            };
            if !visible {
                0
            } else if source[i] != 0 {
                1
            } else {
                2
            }
        })
        .collect()
}

/// B,G,R,A bytes of a shape painted with the 0xRRGGBB colours `fore` and `back`.
pub fn paint(shape: &[u8], fore: u32, back: u32) -> Vec<u8> {
    let quad = |rgb: u32| [(rgb & 0xff) as u8, ((rgb >> 8) & 0xff) as u8, ((rgb >> 16) & 0xff) as u8, 0xff];
    let (f, b) = (quad(fore), quad(back));
    let mut out = Vec::with_capacity(shape.len() * 4);
    for &p in shape {
        match p {
            0 => out.extend_from_slice(&[0, 0, 0, 0]),
            1 => out.extend_from_slice(&f),
            _ => out.extend_from_slice(&b),
        }
    }
    out
}

/// The shape of an image made from `fore` and `back` (B,G,R,A bytes). When both colours are the
/// same the pixels cannot be told apart and count as foreground.
pub fn classify(px: &[u8], fore: u32, back: u32) -> Vec<u8> {
    px.chunks_exact(4)
        .map(|p| {
            if p[3] == 0 {
                0
            } else if fore == back || (((p[2] as u32) << 16) | ((p[1] as u32) << 8) | (p[0] as u32)) == fore {
                1
            } else {
                2
            }
        })
        .collect()
}
