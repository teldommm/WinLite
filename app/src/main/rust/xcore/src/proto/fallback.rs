//! Minimal but valid answers for core requests that expect a reply and are not otherwise
//! implemented (Java `FallbackRequests`). A client waiting for a reply would block forever, and an
//! X error is no option either: Wine passes unexpected X errors to Xlib's fatal handler.
//!
//! The server only has TrueColor visuals, so colors are computed from the root visual's masks.

use super::consts::op;
use super::wire::Reader;
use super::{ClientId, Res, Server};

fn to_component(value16: u32, mask: u32) -> u32 {
    if mask == 0 {
        return 0;
    }
    let shift = mask.trailing_zeros();
    let bits = mask.count_ones();
    ((value16 & 0xffff) >> (16 - bits)) << shift
}

fn from_component(pixel: u32, mask: u32) -> u32 {
    if mask == 0 {
        return 0;
    }
    let shift = mask.trailing_zeros();
    let bits = mask.count_ones();
    let value = (pixel & mask) >> shift;
    // Math.round(value * 65535.0 / ((1L << bits) - 1)) in Java
    ((value as f64) * 65535.0 / (((1u64 << bits) - 1) as f64) + 0.5).floor() as u32
}

/// Hex digits of any length 1..=4 scaled to 16 bits, as Xlib does for "#" and "rgb:" specs.
fn scale_hex(hex: &str) -> Option<u32> {
    if hex.is_empty() || hex.len() > 4 {
        return None;
    }
    let value = u32::from_str_radix(hex, 16).ok()?;
    let max = (1u32 << (4 * hex.len())) - 1;
    Some(((value as f64) * 65535.0 / (max as f64) + 0.5).floor() as u32)
}

/// Accepts "#rgb", "#rrggbb", "#rrrrggggbbbb", "rgb:r/g/b" and a few common names; anything else is black.
pub fn parse_color(name: &str) -> [u32; 3] {
    let value = name.trim().to_lowercase();
    if let Some(rest) = value.strip_prefix('#') {
        if !rest.is_empty() && rest.len() % 3 == 0 {
            let d = rest.len() / 3;
            let mut rgb = [0u32; 3];
            for (i, slot) in rgb.iter_mut().enumerate() {
                match rest.get(i * d..(i + 1) * d).and_then(scale_hex) {
                    Some(v) => *slot = v,
                    None => return [0, 0, 0],
                }
            }
            return rgb;
        }
    }
    if let Some(rest) = value.strip_prefix("rgb:") {
        let parts: Vec<&str> = rest.split('/').collect();
        if parts.len() == 3 {
            return match (scale_hex(parts[0]), scale_hex(parts[1]), scale_hex(parts[2])) {
                (Some(r), Some(g), Some(b)) => [r, g, b],
                _ => [0, 0, 0],
            };
        }
    }
    match value.replace(' ', "").as_str() {
        "white" => [0xffff, 0xffff, 0xffff],
        "red" => [0xffff, 0, 0],
        "green" => [0, 0xffff, 0],
        "blue" => [0, 0, 0xffff],
        "yellow" => [0xffff, 0xffff, 0],
        "cyan" => [0, 0xffff, 0xffff],
        "magenta" => [0xffff, 0, 0xffff],
        "gray" | "grey" => [0xbebe, 0xbebe, 0xbebe],
        _ => [0, 0, 0],
    }
}

impl Server {
    fn root_masks(&self) -> (u32, u32, u32) {
        let v = &self.visuals[0];
        (v.red_mask, v.green_mask, v.blue_mask)
    }

    fn to_pixel(&self, r: u32, g: u32, b: u32) -> u32 {
        let (rm, gm, bm) = self.root_masks();
        to_component(r, rm) | to_component(g, gm) | to_component(b, bm)
    }

    fn from_pixel(&self, pixel: u32) -> [u32; 3] {
        let (rm, gm, bm) = self.root_masks();
        [from_component(pixel, rm), from_component(pixel, gm), from_component(pixel, bm)]
    }

    fn reply_empty(&mut self, c: ClientId, data: u8) {
        self.reply(c, data, 0, |w| {
            w.pad(24);
        });
    }

    /// Returns whether the request was answered.
    pub fn fallback_request(&mut self, c: ClientId, opcode: u8, r: &mut Reader) -> Res<bool> {
        match opcode {
            op::LIST_PROPERTIES => {
                let id = r.u32()?;
                let atoms: Vec<u32> = self.windows.get(&id).map(|w| w.properties.keys().copied().collect()).unwrap_or_default();
                self.reply(c, 0, atoms.len() as u32, |w| {
                    w.u16(atoms.len() as u16).pad(22);
                    for a in &atoms {
                        w.u32(*a);
                    }
                });
            }
            // GrabKeyboard: Success. GetMotionEvents: no events. QueryTextExtents, GetFontPath,
            // ListInstalledColormaps, ListHosts, Set*Mapping: empty / Success.
            op::GRAB_KEYBOARD | op::GET_MOTION_EVENTS | op::QUERY_TEXT_EXTENTS | op::GET_FONT_PATH | op::LIST_INSTALLED_COLORMAPS | op::LIST_HOSTS | op::SET_POINTER_MAPPING | op::SET_MODIFIER_MAPPING => self.reply_empty(c, 0),
            // A font with no glyphs and no properties; ListFontsWithInfo ends right away.
            op::QUERY_FONT | op::LIST_FONTS_WITH_INFO => {
                self.reply(c, 0, 7, |w| {
                    w.pad(52);
                });
            }
            op::ALLOC_COLOR => {
                r.skip(4)?;
                let (red, green, blue) = (r.u16()? as u32, r.u16()? as u32, r.u16()? as u32);
                let pixel = self.to_pixel(red, green, blue);
                let [fr, fg, fb] = self.from_pixel(pixel);
                self.reply(c, 0, 0, |w| {
                    w.u16(fr as u16).u16(fg as u16).u16(fb as u16).pad(2).u32(pixel).pad(12);
                });
            }
            op::ALLOC_NAMED_COLOR => {
                let rgb = read_color_name(r)?;
                let pixel = self.to_pixel(rgb[0], rgb[1], rgb[2]);
                let [fr, fg, fb] = self.from_pixel(pixel);
                self.reply(c, 0, 0, |w| {
                    w.u32(pixel).u16(rgb[0] as u16).u16(rgb[1] as u16).u16(rgb[2] as u16).u16(fr as u16).u16(fg as u16).u16(fb as u16).pad(8);
                });
            }
            op::LOOKUP_COLOR => {
                let rgb = read_color_name(r)?;
                let pixel = self.to_pixel(rgb[0], rgb[1], rgb[2]);
                let [fr, fg, fb] = self.from_pixel(pixel);
                self.reply(c, 0, 0, |w| {
                    w.u16(rgb[0] as u16).u16(rgb[1] as u16).u16(rgb[2] as u16).u16(fr as u16).u16(fg as u16).u16(fb as u16).pad(12);
                });
            }
            // Read-write cells do not exist on TrueColor; answer with zeroed entries so Xlib reads what it expects.
            op::ALLOC_COLOR_CELLS => {
                r.skip(4)?;
                let (colors, planes) = (r.u16()? as usize, r.u16()? as usize);
                self.reply(c, 0, (colors + planes) as u32, |w| {
                    w.u16(colors as u16).u16(planes as u16).pad(20).pad(4 * (colors + planes));
                });
            }
            op::ALLOC_COLOR_PLANES => {
                r.skip(4)?;
                let colors = r.u16()? as usize;
                self.reply(c, 0, colors as u32, |w| {
                    w.u16(colors as u16).pad(2).u32(0).u32(0).u32(0).pad(8).pad(4 * colors);
                });
            }
            op::QUERY_COLORS => {
                r.skip(4)?;
                let count = r.remaining() / 4;
                let mut pixels = Vec::with_capacity(count);
                for _ in 0..count {
                    pixels.push(r.u32()?);
                }
                let colors: Vec<[u32; 3]> = pixels.iter().map(|p| self.from_pixel(*p)).collect();
                self.reply(c, 0, (2 * count) as u32, |w| {
                    w.u16(count as u16).pad(22);
                    for [cr, cg, cb] in colors {
                        w.u16(cr as u16).u16(cg as u16).u16(cb as u16).pad(2);
                    }
                });
            }
            op::QUERY_BEST_SIZE => {
                r.skip(4)?;
                let (width, height) = (r.u16()?, r.u16()?);
                self.reply(c, 0, 0, |w| {
                    w.u16(width).u16(height).pad(20);
                });
            }
            op::LIST_EXTENSIONS => {
                let names: Vec<String> = self.extensions.iter().map(|e| e.name.clone()).collect();
                let bytes: usize = names.iter().map(|n| 1 + n.len()).sum();
                let padded = (bytes + 3) & !3;
                self.reply(c, names.len() as u8, (padded / 4) as u32, |w| {
                    w.pad(24);
                    for n in &names {
                        w.u8(n.len() as u8).bytes(n.as_bytes());
                    }
                    w.pad(padded - bytes);
                });
            }
            op::GET_KEYBOARD_CONTROL => {
                self.reply(c, 1, 5, |w| {
                    w.u32(0).u8(0).u8(50).u16(400).u16(100).pad(2);
                    for _ in 0..32 {
                        w.u8(0xff);
                    }
                });
            }
            op::GET_POINTER_CONTROL => {
                self.reply(c, 0, 0, |w| {
                    w.u16(1).u16(1).u16(0).pad(18);
                });
            }
            _ => return Ok(false),
        }
        Ok(true)
    }
}

fn read_color_name(r: &mut Reader) -> Res<[u32; 3]> {
    r.skip(4)?;
    let len = r.u16()? as usize;
    r.skip(2)?;
    Ok(parse_color(&r.string8(len)?))
}
