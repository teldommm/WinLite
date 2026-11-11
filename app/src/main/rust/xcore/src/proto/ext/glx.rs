//! GLX 1.4 metadata (Java `GLXExtension`): visual and framebuffer configs, contexts, drawables.
//! No GL runs in the X server; clients (Mesa's GLX, Wine's OpenGL path) only need these answers.

use super::reader;
use crate::proto::consts::err;
use crate::proto::requests::Extension;
use crate::proto::wire::Writer;
use crate::proto::{bad, ClientId, Fail, Res, Server};
use std::collections::HashMap;

pub const MAJOR: u8 = 151; // Java: (byte)-105
/// Java: `Byte.MIN_VALUE + 3`.
pub const FIRST_ERROR: u8 = 131;
const BAD_IMPLEMENTATION: u8 = 17;

const ERR_BAD_CONTEXT: u8 = FIRST_ERROR;
const ERR_BAD_DRAWABLE: u8 = FIRST_ERROR + 2;
const ERR_BAD_PIXMAP: u8 = FIRST_ERROR + 3;
const ERR_BAD_FBCONFIG: u8 = FIRST_ERROR + 9;
const ERR_BAD_WINDOW: u8 = FIRST_ERROR + 12;
const ERR_BAD_PROFILE_ARB: u8 = FIRST_ERROR + 13;

const CREATE_CONTEXT: u8 = 3;
const DESTROY_CONTEXT: u8 = 4;
const IS_DIRECT: u8 = 6;
const QUERY_VERSION: u8 = 7;
const GET_VISUAL_CONFIGS: u8 = 14;
const QUERY_SERVER_STRING: u8 = 19;
const CLIENT_INFO: u8 = 20;
const GET_FB_CONFIGS: u8 = 21;
const CREATE_PIXMAP: u8 = 22;
const DESTROY_PIXMAP: u8 = 23;
const CREATE_NEW_CONTEXT: u8 = 24;
const GET_DRAWABLE_ATTRIBUTES: u8 = 29;
const CREATE_WINDOW: u8 = 31;
const DESTROY_WINDOW: u8 = 32;
const CLIENT_INFO_ARB: u8 = 33;
const CREATE_CONTEXT_ATTRIBS_ARB: u8 = 34;
const CLIENT_INFO_2_ARB: u8 = 35;

const GLX_VENDOR: u32 = 1;
const GLX_VERSION: u32 = 2;
const GLX_EXTENSIONS: u32 = 3;
const GLX_NONE: u32 = 0x8000;
const GLX_RGBA_TYPE: u32 = 0x8014;
const GLX_COLOR_INDEX_TYPE: u32 = 0x8015;
const GLX_RGBA_BIT: u32 = 1;
const GLX_WINDOW_BIT: u32 = 1;
const GLX_PIXMAP_BIT: u32 = 2;
const GLX_TRUE_COLOR: u32 = 0x8002;

const ATTR_X_VISUAL_ID: u32 = 0x800B;
const ATTR_FBCONFIG_ID: u32 = 0x8013;
const ATTR_X_RENDERABLE: u32 = 0x8012;
const ATTR_WIDTH: u32 = 0x801D;
const ATTR_HEIGHT: u32 = 0x801E;
const ATTR_RGBA: u32 = 4;
const ATTR_RENDER_TYPE: u32 = 0x8011;
const ATTR_DOUBLEBUFFER: u32 = 5;
const ATTR_STEREO: u32 = 6;
const ATTR_BUFFER_SIZE: u32 = 2;
const ATTR_LEVEL: u32 = 3;
const ATTR_AUX_BUFFERS: u32 = 7;
const ATTR_RED: u32 = 8;
const ATTR_GREEN: u32 = 9;
const ATTR_BLUE: u32 = 10;
const ATTR_ALPHA: u32 = 11;
const ATTR_DEPTH: u32 = 12;
const ATTR_STENCIL: u32 = 13;
const ATTR_ACCUM_RED: u32 = 14;
const ATTR_ACCUM_GREEN: u32 = 15;
const ATTR_ACCUM_BLUE: u32 = 16;
const ATTR_ACCUM_ALPHA: u32 = 17;
const ATTR_X_VISUAL_TYPE: u32 = 0x22;
const ATTR_TRANSPARENT_TYPE: u32 = 0x23;
const ATTR_TRANSPARENT_INDEX: u32 = 0x24;
const ATTR_TRANSPARENT_RED: u32 = 0x25;
const ATTR_TRANSPARENT_GREEN: u32 = 0x26;
const ATTR_TRANSPARENT_BLUE: u32 = 0x27;
const ATTR_TRANSPARENT_ALPHA: u32 = 0x28;
const ATTR_DRAWABLE_TYPE: u32 = 0x8010;

const CTX_MAJOR: u32 = 0x2091;
const CTX_MINOR: u32 = 0x2092;
const CTX_FLAGS: u32 = 0x2094;
const CTX_PROFILE_MASK: u32 = 0x9126;
const CTX_FORWARD_COMPATIBLE_BIT: u32 = 2;
const PROFILE_CORE: u32 = 1;
const PROFILE_COMPAT: u32 = 2;

#[derive(Clone, Copy)]
struct FbConfig {
    id: u32,
    depth: u32,
    stencil: u32,
    double_buffered: bool,
    buffer_size: u32,
}

const FB_CONFIGS: [FbConfig; 6] = [
    FbConfig { id: 1, depth: 0, stencil: 0, double_buffered: true, buffer_size: 32 },
    FbConfig { id: 2, depth: 0, stencil: 0, double_buffered: false, buffer_size: 32 },
    FbConfig { id: 3, depth: 24, stencil: 0, double_buffered: false, buffer_size: 32 },
    FbConfig { id: 4, depth: 24, stencil: 8, double_buffered: false, buffer_size: 32 },
    FbConfig { id: 5, depth: 24, stencil: 0, double_buffered: true, buffer_size: 32 },
    FbConfig { id: 6, depth: 24, stencil: 8, double_buffered: true, buffer_size: 32 },
];

fn find_fb(id: u32) -> Option<FbConfig> {
    FB_CONFIGS.iter().copied().find(|c| c.id == id)
}

struct GlxContext {
    direct: bool,
    owner: ClientId,
}

struct GlxPixmap {
    pixmap: u32,
    fb: FbConfig,
    owner: ClientId,
}

struct GlxWindow {
    window: u32,
    fb: FbConfig,
    owner: ClientId,
}

#[derive(Default)]
pub struct Glx {
    contexts: HashMap<u32, GlxContext>,
    pixmaps: HashMap<u32, GlxPixmap>,
    windows: HashMap<u32, GlxWindow>,
}

fn pair(w: &mut Writer, a: u32, b: u32) {
    w.u32(a).u32(b);
}

fn write_fb_config(w: &mut Writer, visual: u32, fb: &FbConfig) {
    pair(w, ATTR_X_VISUAL_ID, visual);
    pair(w, ATTR_FBCONFIG_ID, fb.id);
    pair(w, ATTR_X_RENDERABLE, 1);
    pair(w, ATTR_RGBA, 1);
    pair(w, ATTR_RENDER_TYPE, GLX_RGBA_BIT);
    pair(w, ATTR_DOUBLEBUFFER, fb.double_buffered as u32);
    pair(w, ATTR_STEREO, 0);
    pair(w, ATTR_BUFFER_SIZE, fb.buffer_size);
    pair(w, ATTR_LEVEL, 0);
    pair(w, ATTR_AUX_BUFFERS, 0);
    pair(w, ATTR_RED, 8);
    pair(w, ATTR_BLUE, 8);
    pair(w, ATTR_GREEN, 8);
    pair(w, ATTR_ALPHA, 8);
    pair(w, ATTR_ACCUM_RED, 0);
    pair(w, ATTR_ACCUM_BLUE, 0);
    pair(w, ATTR_ACCUM_GREEN, 0);
    pair(w, ATTR_ACCUM_ALPHA, 0);
    pair(w, ATTR_DEPTH, fb.depth);
    pair(w, ATTR_STENCIL, fb.stencil);
    pair(w, ATTR_X_VISUAL_TYPE, GLX_TRUE_COLOR);
    pair(w, ATTR_TRANSPARENT_TYPE, GLX_NONE);
    pair(w, ATTR_TRANSPARENT_INDEX, 0);
    pair(w, ATTR_TRANSPARENT_RED, 0);
    pair(w, ATTR_TRANSPARENT_BLUE, 0);
    pair(w, ATTR_TRANSPARENT_GREEN, 0);
    pair(w, ATTR_TRANSPARENT_ALPHA, 0);
    pair(w, ATTR_DRAWABLE_TYPE, GLX_WINDOW_BIT | GLX_PIXMAP_BIT);
    for _ in 0..16 {
        pair(w, 0, 0);
    }
}

fn bad_fb(id: u32) -> Fail {
    bad(ERR_BAD_FBCONFIG, id)
}

impl Glx {
    /// The checks every context creation does on the share list.
    fn check_share(&self, share: u32) -> Res {
        if share != 0 && !self.contexts.contains_key(&share) {
            return Err(bad(ERR_BAD_CONTEXT, share));
        }
        Ok(())
    }
}

impl Extension for Glx {
    fn name(&self) -> &str {
        "GLX"
    }
    fn major_opcode(&self) -> u8 {
        MAJOR
    }
    fn first_error(&self) -> u8 {
        FIRST_ERROR
    }

    /// A client's GLX contexts and drawables go away with it (their ids are handed to the next
    /// client that gets the same id range).
    fn on_client_disconnected(&mut self, _s: &mut Server, client: ClientId) {
        self.contexts.retain(|_, v| v.owner != client);
        self.pixmaps.retain(|_, v| v.owner != client);
        self.windows.retain(|_, v| v.owner != client);
    }

    fn handle(&mut self, s: &mut Server, c: ClientId, minor: u8, body: &[u8]) -> Res {
        let mut r = reader(s, c, body);
        let visual = s.visuals[0].id;
        match minor {
            QUERY_VERSION => {
                s.reply(c, 0, 0, |w| {
                    w.u32(1).u32(4).pad(16);
                });
            }
            GET_VISUAL_CONFIGS => {
                let n = FB_CONFIGS.len() as u32;
                s.reply(c, 0, 18 * n, |w| {
                    w.u32(n).u32(18).pad(16);
                    for fb in &FB_CONFIGS {
                        w.u32(visual).u32(4).u32(1).u32(8).u32(8).u32(8).u32(8);
                        w.u32(0).u32(0).u32(0).u32(0);
                        w.u32(fb.double_buffered as u32).u32(0).u32(fb.buffer_size).u32(fb.depth).u32(fb.stencil).u32(0).u32(0);
                    }
                });
            }
            QUERY_SERVER_STRING => {
                r.skip(4)?;
                let name = r.u32()?;
                let text = match name {
                    GLX_VENDOR => "Winlator ",
                    GLX_VERSION => "1.4 ",
                    GLX_EXTENSIONS => "GLX_ARB_create_context GLX_ARB_create_context_profile ",
                    _ => "",
                };
                let n = text.len() as u32;
                s.reply(c, 0, n.div_ceil(4), |w| {
                    w.pad(4).u32(n).pad(16);
                    w.string8(text);
                });
            }
            GET_FB_CONFIGS => {
                let n = FB_CONFIGS.len() as u32;
                s.reply(c, 0, 88 * n, |w| {
                    w.u32(n).u32(44).pad(16);
                    for fb in &FB_CONFIGS {
                        write_fb_config(w, visual, fb);
                    }
                });
            }
            // The client's GLX/GL version and extension strings: nothing the replies depend on.
            CLIENT_INFO | CLIENT_INFO_ARB | CLIENT_INFO_2_ARB => {}
            CREATE_NEW_CONTEXT => {
                let (id, fbconfig, _screen, render_type, share) = (r.u32()?, r.u32()?, r.u32()?, r.u32()?, r.u32()?);
                let direct = r.u8()? != 0;
                if render_type != GLX_RGBA_TYPE {
                    return Err(bad(err::BAD_VALUE, render_type));
                }
                find_fb(fbconfig).ok_or_else(|| bad_fb(fbconfig))?;
                self.check_share(share)?;
                self.contexts.insert(id, GlxContext { direct, owner: c });
            }
            CREATE_CONTEXT => {
                let (id, visual_id, _screen, share) = (r.u32()?, r.u32()?, r.u32()?, r.u32()?);
                let direct = r.u8()? != 0;
                if visual_id != visual {
                    return Err(bad(err::BAD_VALUE, visual_id));
                }
                self.check_share(share)?;
                self.contexts.insert(id, GlxContext { direct, owner: c });
            }
            CREATE_CONTEXT_ATTRIBS_ARB => {
                let (id, fbconfig, _screen, share) = (r.u32()?, r.u32()?, r.u32()?, r.u32()?);
                let direct = r.u8()? != 0;
                r.skip(3)?;
                let num_attribs = r.u32()?;
                let (mut major, mut minor_gl, mut flags) = (1u32, 0u32, 0u32);
                let mut render_type = GLX_RGBA_TYPE;
                for _ in 0..num_attribs {
                    let (attr, value) = (r.u32()?, r.u32()?);
                    match attr {
                        CTX_MAJOR => major = value,
                        CTX_MINOR => minor_gl = value,
                        CTX_FLAGS => flags = value,
                        ATTR_RENDER_TYPE => {
                            if value != GLX_COLOR_INDEX_TYPE && value != GLX_RGBA_TYPE {
                                return Err(bad(err::BAD_VALUE, value));
                            }
                            render_type = value;
                        }
                        CTX_PROFILE_MASK => {
                            if value != PROFILE_CORE && value != PROFILE_COMPAT {
                                return Err(bad(ERR_BAD_PROFILE_ARB, value));
                            }
                        }
                        _ => return Err(bad(err::BAD_VALUE, attr)),
                    }
                }
                if render_type != GLX_RGBA_TYPE {
                    return Err(bad(err::BAD_VALUE, render_type));
                }
                find_fb(fbconfig).ok_or_else(|| bad_fb(fbconfig))?;
                self.check_share(share)?;
                let mismatch = !(1..=4).contains(&major)
                    || (major == 1 && minor_gl > 5)
                    || (major == 2 && minor_gl > 1)
                    || (major == 3 && minor_gl > 3)
                    || (major < 3 && flags & CTX_FORWARD_COMPATIBLE_BIT != 0);
                if mismatch {
                    return Err(bad(err::BAD_MATCH, 0));
                }
                self.contexts.insert(id, GlxContext { direct, owner: c });
            }
            IS_DIRECT => {
                let id = r.u32()?;
                let Some(ctx) = self.contexts.get(&id) else { return Err(bad(ERR_BAD_CONTEXT, id)) };
                let direct = ctx.direct as u8;
                s.reply(c, 0, 0, |w| {
                    w.u8(direct).pad(23);
                });
            }
            DESTROY_CONTEXT => {
                let id = r.u32()?;
                if self.contexts.remove(&id).is_none() {
                    return Err(bad(ERR_BAD_CONTEXT, id));
                }
            }
            GET_DRAWABLE_ATTRIBUTES => {
                let id = r.u32()?;
                let glx_window = self.windows.get(&id);
                let window = glx_window.map(|g| g.window).unwrap_or(id);
                let window_ref = s.windows.get(&window);
                let glx_pixmap = self.pixmaps.get(&id);
                if glx_window.is_none() && window_ref.is_none() && glx_pixmap.is_none() {
                    return Err(bad(ERR_BAD_DRAWABLE, id));
                }
                let fb = glx_window.map(|g| g.fb).or(glx_pixmap.map(|g| g.fb)).unwrap_or(FB_CONFIGS[0]);
                let drawable_type = if glx_pixmap.is_some() { GLX_PIXMAP_BIT } else { GLX_WINDOW_BIT };
                let (width, height) = match window_ref {
                    Some(w) => (w.width as u32, w.height as u32),
                    None => match glx_pixmap.and_then(|g| s.drawables.get(&g.pixmap)) {
                        Some(d) => (d.width as u32, d.height as u32),
                        None => return Err(Fail::Drop("GLX pixmap without a drawable")),
                    },
                };
                s.reply(c, 0, 10, |w| {
                    w.u32(5).pad(20);
                    pair(w, ATTR_FBCONFIG_ID, fb.id);
                    pair(w, ATTR_DRAWABLE_TYPE, drawable_type);
                    pair(w, ATTR_RENDER_TYPE, 1);
                    pair(w, ATTR_WIDTH, width);
                    pair(w, ATTR_HEIGHT, height);
                });
            }
            CREATE_PIXMAP => {
                let (_screen, fbconfig, pixmap, glx_id, n) = (r.u32()?, r.u32()?, r.u32()?, r.u32()?, r.u32()?);
                r.skip((n as usize).saturating_mul(8).min(r.remaining()))?;
                let Some(d) = s.drawables.get(&pixmap).filter(|_| s.pixmaps.contains(&pixmap)) else { return Err(bad(err::BAD_PIXMAP, pixmap)) };
                let fb = find_fb(fbconfig).ok_or_else(|| bad_fb(fbconfig))?;
                if d.visual.is_none() {
                    return Err(Fail::Drop("pixmap without a visual"));
                }
                if d.depth as u32 != fb.buffer_size {
                    return Err(bad(err::BAD_MATCH, 0));
                }
                self.pixmaps.insert(glx_id, GlxPixmap { pixmap, fb, owner: c });
            }
            DESTROY_PIXMAP => {
                let id = r.u32()?;
                if self.pixmaps.remove(&id).is_none() {
                    return Err(bad(ERR_BAD_PIXMAP, id));
                }
            }
            CREATE_WINDOW => {
                let (_screen, fbconfig, window, glx_id, n) = (r.u32()?, r.u32()?, r.u32()?, r.u32()?, r.u32()?);
                r.skip((n as usize).saturating_mul(8).min(r.remaining()))?;
                let Some(w) = s.windows.get(&window) else { return Err(bad(err::BAD_WINDOW, window)) };
                let fb = find_fb(fbconfig).ok_or_else(|| bad_fb(fbconfig))?;
                // An InputOnly window has no content: the Java code failed there without an error.
                let Some(d) = s.drawables.get(&window).filter(|_| w.has_content) else { return Err(Fail::Drop("GLX window without content")) };
                if d.depth as u32 != fb.buffer_size {
                    return Err(bad(err::BAD_MATCH, 0));
                }
                self.windows.insert(glx_id, GlxWindow { window, fb, owner: c });
            }
            DESTROY_WINDOW => {
                let id = r.u32()?;
                if self.windows.remove(&id).is_none() {
                    return Err(bad(ERR_BAD_WINDOW, id));
                }
            }
            _ => return Err(Fail::X { code: BAD_IMPLEMENTATION, data: 0 }),
        }
        Ok(())
    }
}
