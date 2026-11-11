//! DRI3 (Java `DRI3Extension`): the game's Vulkan/GL driver renders into a hardware buffer and
//! sends it as a pixmap. The buffer arrives as a file descriptor with the request; the platform
//! receives it on a worker thread ([`Platform::import_buffer_async`]) while the client waits,
//! and the pixmap becomes the *direct content* of the
//! window it was made for: drawing / presenting it tells the renderer ([`Notify`]) instead of
//! going through the window's own pixels.
//!
//! The direct-content table lives in the core (`Drawable::direct_window`), so removal when the
//! pixmap is freed needs no state here.

use super::reader;
use std::os::unix::io::RawFd;
use crate::proto::consts::err;
use crate::proto::gfx::{Drawable, PixelStore};
use crate::proto::hooks::ImportJob;
use crate::proto::requests::Extension;
use crate::proto::{bad, ClientId, Fail, Notify, Owned, Res, Server};

pub const MAJOR: u8 = 154; // Java: (byte)-102
const BAD_ALLOC: u8 = 11;
const BAD_IMPLEMENTATION: u8 = 17;

const QUERY_VERSION: u8 = 0;
const OPEN: u8 = 1;
const PIXMAP_FROM_BUFFER: u8 = 2;
const GET_SUPPORTED_MODIFIERS: u8 = 6;
const PIXMAP_FROM_BUFFERS: u8 = 7;

#[derive(Default)]
pub struct Dri3;

/// The common tail of both PixmapFromBuffer requests.
/// `fd` is the descriptor that came with the request; it is taken here (the platform closes it
/// when it is done) or left in the option when the request fails first: the caller closes it
/// then, so a failed request never leaves its descriptor in the client's queue for the next one.
///
/// The buffer is received on a worker thread: the client is on hold until it has arrived
/// ([`finish_import`] completes the request), and nobody else waits for it.
#[allow(clippy::too_many_arguments)]
fn pixmap_from_buffer(s: &mut Server, c: ClientId, minor: u8, fd: &mut Option<RawFd>, pixmap: u32, window: u32, width: i16, height: i16, depth: u8) -> Res {
    if !s.windows.contains_key(&window) {
        return Err(bad(err::BAD_WINDOW, window));
    }
    if s.drawables.contains_key(&pixmap) || !s.is_valid_resource_id(c, pixmap) {
        return Err(bad(err::BAD_ID_CHOICE, pixmap));
    }
    let Some(fd) = fd.take() else { return Err(Fail::Drop("DRI3 pixmap request without a buffer")) };
    s.start_import(fd, ImportJob { client: c, pixmap, window, width, height, depth, minor });
    Ok(())
}

/// The second half of PixmapFromBuffer, once the buffer has been received (or not).
pub(crate) fn finish_import(s: &mut Server, job: ImportJob, store: Option<Box<dyn PixelStore>>) {
    let ImportJob { client: c, pixmap, window, width, height, depth, minor } = job;
    let Some(store) = store else {
        s.send_error(c, BAD_ALLOC, 0, minor, MAJOR);
        return;
    };
    // The window may have been destroyed while the buffer was on its way.
    if !s.windows.contains_key(&window) || s.drawables.contains_key(&pixmap) {
        return;
    }
    let visual = s.visual_for_depth(depth);
    s.drawables.insert(pixmap, Drawable { id: pixmap, width, height, depth, visual, store, offscreen: false, direct_window: Some(window) });
    s.pixmaps.insert(pixmap);
    s.register_owner(c, Owned::Pixmap(pixmap));
    s.notifications.push(Notify::DirectContentAdded { window, pixmap });
}

impl Extension for Dri3 {
    fn name(&self) -> &str {
        "DRI3"
    }
    fn major_opcode(&self) -> u8 {
        MAJOR
    }

    fn handle(&mut self, s: &mut Server, c: ClientId, minor: u8, body: &[u8]) -> Res {
        let mut r = reader(s, c, body);
        match minor {
            QUERY_VERSION => {
                s.reply(c, 0, 0, |w| {
                    w.u32(1).u32(2).pad(16);
                });
            }
            OPEN => {
                let drawable = r.u32()?;
                if !s.drawables.contains_key(&drawable) {
                    return Err(bad(err::BAD_DRAWABLE, drawable));
                }
                // nfd = 0: the client gets no device; Wine/Mesa then use the buffer path below.
                s.reply(c, 0, 0, |w| {
                    w.pad(24);
                });
            }
            GET_SUPPORTED_MODIFIERS => {
                let window = r.u32()?;
                if !s.windows.contains_key(&window) {
                    return Err(bad(err::BAD_WINDOW, window));
                }
                s.reply(c, 0, 0, |w| {
                    w.u32(0).u32(0).pad(16);
                });
            }
            PIXMAP_FROM_BUFFER | PIXMAP_FROM_BUFFERS => {
                // The descriptor belongs to this request whatever happens to it.
                let mut fd = s.take_fd(c);
                let res = (|| {
                    let (pixmap, window) = (r.u32()?, r.u32()?);
                    let (width, height, depth);
                    if minor == PIXMAP_FROM_BUFFER {
                        let _size = r.u32()?;
                        (width, height) = (r.i16()?, r.i16()?);
                        let _stride = r.i16()?;
                        depth = r.u8()?;
                    } else {
                        r.skip(4)?; // num_buffers
                        (width, height) = (r.i16()?, r.i16()?);
                        let _stride = r.u32()?;
                        let _offset = r.u32()?;
                        r.skip(24)?; // the other three planes
                        depth = r.u8()?;
                    }
                    pixmap_from_buffer(s, c, minor, &mut fd, pixmap, window, width, height, depth)
                })();
                if let Some(fd) = fd {
                    unsafe { libc::close(fd) };
                }
                res?;
            }
            _ => return Err(Fail::X { code: BAD_IMPLEMENTATION, data: 0 }),
        }
        Ok(())
    }
}
