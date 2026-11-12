//! RENDER, the subset a Wine guest needs (the C++ server of Ludashi has the same one): the
//! version, the picture formats, pictures, and ARGB cursors. Nothing is drawn through RENDER;
//! Wine's GDI stays on the core requests with `ClientSideWithRender=N`, and every drawing request
//! is answered with BadImplementation.
//!
//! * QueryVersion: 0.8 (libXcursor wants 0.5 for ARGB cursors and 0.8 for animated ones).
//! * QueryPictFormats: A1, RGB24 and ARGB32, one screen; the root visual maps to ARGB32 (the
//!   server's visual is 32 bits deep).
//! * CreatePicture / FreePicture: a picture has the depth of its format; a cursor-sized 32-bit one
//!   keeps a copy of the pixels, because libXcursor frees the pixmap before CreateCursor.
//! * CreateCursor: a cursor from the pixels of a 32-bit picture. Xcursor sends premultiplied ARGB, the renderer
//!   blends straight alpha, so the colors are divided by alpha on the way in. A cursor without a
//!   single visible pixel hides the pointer, as an empty core cursor mask does.
//! * CreateAnimCursor: the first frame, still (the renderer takes one image per cursor).

use super::reader;
use crate::proto::consts::err;
use crate::proto::requests::Extension;
use crate::proto::{bad, ClientId, Cursor, Fail, Owned, Res, Server};
use std::collections::HashMap;

pub const MAJOR: u8 = 157;
/// RENDER's five errors: BadPictFormat, BadPicture, BadPictOp, BadGlyphSet, BadGlyph.
pub const FIRST_ERROR: u8 = 170;
const BAD_PICT_FORMAT: u8 = FIRST_ERROR;
const BAD_PICTURE: u8 = FIRST_ERROR + 1;
const BAD_ALLOC: u8 = 11;
const BAD_LENGTH: u8 = 16;
const BAD_IMPLEMENTATION: u8 = 17;

const QUERY_VERSION: u8 = 0;
const QUERY_PICT_FORMATS: u8 = 1;
const CREATE_PICTURE: u8 = 4;
const FREE_PICTURE: u8 = 7;
const CREATE_CURSOR: u8 = 27;
const CREATE_ANIM_CURSOR: u8 = 31;

/// Picture format ids (server-owned, below every client's id range).
const FORMAT_A1: u32 = 0x40;
const FORMAT_RGB24: u32 = 0x41;
const FORMAT_ARGB32: u32 = 0x42;

/// Largest cursor, in pixels (the same cap as the C++ server).
const MAX_CURSOR_PIXELS: usize = 512 * 512;

struct Picture {
    client: ClientId,
    depth: u8,
    /// The pixels of a cursor-sized 32-bit drawable, taken in CreatePicture: libXcursor frees its
    /// pixmap before CreateCursor, and a BadMatch there makes Wine exit (its X error handler ends
    /// in Xlib's default one).
    image: Option<(i16, i16, Vec<u8>)>,
}

#[derive(Default)]
pub struct Render {
    pictures: HashMap<u32, Picture>,
}

fn format_depth(format: u32) -> Option<u8> {
    match format {
        FORMAT_A1 => Some(1),
        FORMAT_RGB24 => Some(24),
        FORMAT_ARGB32 => Some(32),
        _ => None,
    }
}

/// B,G,R,A bytes with premultiplied colors -> straight alpha, in place. Returns whether any pixel
/// is visible.
fn unpremultiply(px: &mut [u8]) -> bool {
    let mut visible = false;
    for p in px.chunks_exact_mut(4) {
        match p[3] as u32 {
            0 => {
                p[0] = 0;
                p[1] = 0;
                p[2] = 0;
            }
            255 => visible = true,
            a => {
                visible = true;
                for c in &mut p[..3] {
                    *c = ((*c as u32 * 255 + a / 2) / a).min(255) as u8;
                }
            }
        }
    }
    visible
}

impl Render {
    fn id_taken(&self, s: &Server, id: u32) -> bool {
        self.pictures.contains_key(&id) || s.windows.contains_key(&id) || s.drawables.contains_key(&id) || s.gcs.contains_key(&id) || s.cursors.contains_key(&id)
    }

    fn new_id(&self, s: &Server, c: ClientId, id: u32) -> Res {
        if !s.is_valid_resource_id(c, id) || self.id_taken(s, id) {
            return Err(bad(err::BAD_ID_CHOICE, id));
        }
        Ok(())
    }

    fn query_pict_formats(&self, s: &mut Server, c: ClientId) {
        let visual = s.visuals[0].id;
        s.reply(c, 0, 32, |w| {
            // numFormats, numScreens, numDepths, numVisuals, numSubpixel
            w.u32(3).u32(1).u32(3).u32(1).u32(1).pad(4);
            // xPictFormInfo: id, type (direct), depth, direct{red, redMask, green, greenMask,
            // blue, blueMask, alpha, alphaMask}, colormap
            for (id, depth) in [(FORMAT_A1, 1u8), (FORMAT_RGB24, 24), (FORMAT_ARGB32, 32)] {
                w.u32(id).u8(1).u8(depth).pad(2);
                if depth == 1 {
                    w.u16(0).u16(0).u16(0).u16(0).u16(0).u16(0).u16(0).u16(1);
                } else {
                    w.u16(16).u16(255).u16(8).u16(255).u16(0).u16(255);
                    if depth == 32 {
                        w.u16(24).u16(255);
                    } else {
                        w.u16(0).u16(0);
                    }
                }
                w.u32(0);
            }
            // xPictScreen: nDepth, fallback
            w.u32(3).u32(FORMAT_RGB24);
            // xPictDepth: depth, nPictVisuals; the root visual is 32 bits deep
            w.u8(1).pad(1).u16(0).pad(4);
            w.u8(24).pad(1).u16(0).pad(4);
            w.u8(32).pad(1).u16(1).pad(4);
            // xPictVisual: visual, format
            w.u32(visual).u32(FORMAT_ARGB32);
            // subpixel order of the screen: unknown
            w.u32(0);
        });
    }

    fn create_picture(&mut self, s: &mut Server, c: ClientId, body: &[u8]) -> Res {
        let mut r = reader(s, c, body);
        let (pid, drawable, format, mask) = (r.u32()?, r.u32()?, r.u32()?, r.u32()?);
        self.new_id(s, c, pid)?;
        if body.len() != 16 + mask.count_ones() as usize * 4 {
            return Err(bad(BAD_LENGTH, 0));
        }
        let Some(depth) = format_depth(format) else { return Err(bad(BAD_PICT_FORMAT, format)) };
        let Some(d) = s.drawables.get(&drawable) else { return Err(bad(err::BAD_DRAWABLE, drawable)) };
        if d.depth != depth {
            return Err(bad(err::BAD_MATCH, 0));
        }
        let image = if depth == 32 && d.width as usize * d.height as usize <= MAX_CURSOR_PIXELS {
            d.get_image(0, 0, d.width, d.height).map(|px| (d.width, d.height, px))
        } else {
            None
        };
        self.pictures.insert(pid, Picture { client: c, depth, image });
        Ok(())
    }

    fn create_cursor(&mut self, s: &mut Server, c: ClientId, body: &[u8]) -> Res {
        if body.len() != 12 {
            return Err(bad(BAD_LENGTH, 0));
        }
        let mut r = reader(s, c, body);
        let (cid, src) = (r.u32()?, r.u32()?);
        let (x, y) = (r.u16()? as i32, r.u16()? as i32);
        self.new_id(s, c, cid)?;
        let Some(pic) = self.pictures.get(&src) else { return Err(bad(BAD_PICTURE, src)) };
        // A picture without pixels is a 32-bit drawable larger than a cursor.
        let Some((w, h, px)) = &pic.image else {
            return Err(bad(if pic.depth == 32 { BAD_ALLOC } else { err::BAD_MATCH }, 0));
        };
        let (w, h, mut px) = (*w, *h, px.clone());
        if x >= w as i32 || y >= h as i32 {
            return Err(bad(err::BAD_MATCH, 0));
        }
        let visible = unpremultiply(&mut px);
        let visual = s.visual_for_depth(32);
        let Some(image) = s.new_drawable_with(0, w, h, 32, visual) else { return Err(bad(BAD_ALLOC, 0)) };
        image.draw_image(0, 0, 0, 0, w, h, 32, &px, w);
        self.add_cursor(s, c, cid, x as i16, y as i16, image, visible);
        Ok(())
    }

    fn create_anim_cursor(&mut self, s: &mut Server, c: ClientId, body: &[u8]) -> Res {
        if body.len() < 12 || (body.len() - 4) % 8 != 0 {
            return Err(bad(BAD_LENGTH, 0));
        }
        let mut r = reader(s, c, body);
        let cid = r.u32()?;
        self.new_id(s, c, cid)?;
        let mut first = 0;
        for i in 0..(body.len() - 4) / 8 {
            let cursor = r.u32()?;
            r.skip(4)?; // delay
            if !s.cursors.contains_key(&cursor) {
                return Err(bad(err::BAD_CURSOR, cursor));
            }
            if i == 0 {
                first = cursor;
            }
        }
        // A still copy of the first frame: the renderer takes one image per cursor.
        let f = &s.cursors[&first];
        let (w, h, depth, visual, hot_x, hot_y, visible) = (f.image.width, f.image.height, f.image.depth, f.image.visual, f.hot_x, f.hot_y, f.visible);
        let Some(px) = f.image.get_image(0, 0, w, h) else { return Err(bad(err::BAD_MATCH, 0)) };
        let Some(image) = s.new_drawable_with(0, w, h, depth, visual) else { return Err(bad(BAD_ALLOC, 0)) };
        image.draw_image(0, 0, 0, 0, w, h, depth, &px, w);
        self.add_cursor(s, c, cid, hot_x, hot_y, image, visible);
        Ok(())
    }

    #[allow(clippy::too_many_arguments)]
    fn add_cursor(&mut self, s: &mut Server, c: ClientId, id: u32, hot_x: i16, hot_y: i16, image: crate::proto::gfx::Drawable, visible: bool) {
        s.cursor_serial += 1;
        let serial = s.cursor_serial;
        s.cursors.insert(id, Cursor { id, serial, freed: false, hot_x, hot_y, image, visible, shape: None });
        s.register_owner(c, Owned::Cursor(id));
    }
}

impl Extension for Render {
    fn name(&self) -> &str {
        "RENDER"
    }
    fn major_opcode(&self) -> u8 {
        MAJOR
    }
    fn first_error(&self) -> u8 {
        FIRST_ERROR
    }

    fn handle(&mut self, s: &mut Server, c: ClientId, minor: u8, body: &[u8]) -> Res {
        match minor {
            QUERY_VERSION => {
                let mut r = reader(s, c, body);
                let (major, minor_wanted) = (r.u32()?, r.u32()?);
                let version = if major > 0 { 8 } else { minor_wanted.min(8) };
                s.reply(c, 0, 0, |w| {
                    w.u32(0).u32(version).pad(16);
                });
            }
            QUERY_PICT_FORMATS => self.query_pict_formats(s, c),
            CREATE_PICTURE => self.create_picture(s, c, body)?,
            FREE_PICTURE => {
                let id = reader(s, c, body).u32()?;
                if self.pictures.remove(&id).is_none() {
                    return Err(bad(BAD_PICTURE, id));
                }
            }
            CREATE_CURSOR => self.create_cursor(s, c, body)?,
            CREATE_ANIM_CURSOR => self.create_anim_cursor(s, c, body)?,
            _ => return Err(Fail::X { code: BAD_IMPLEMENTATION, data: 0 }),
        }
        Ok(())
    }

    fn on_client_disconnected(&mut self, _s: &mut Server, client: ClientId) {
        self.pictures.retain(|_, p| p.client != client);
    }
}
