//! MIT-SHM (Java `MITSHMExtension` + `SHMSegmentManager`): attach the guest's SysV segments and
//! blit from them.

use super::frame::ShmFramePacer;
use super::reader;
use crate::proto::consts::err;
use crate::proto::events::Event;
use crate::proto::gfx::GX_COPY;
use crate::proto::platform::ShmMap;
use crate::proto::requests::Extension;
use crate::proto::{bad, ClientId, Fail, Res, Server};
use std::collections::HashMap;

pub const MAJOR: u8 = 155; // Java: (byte)-101
/// BadShmSeg.
pub const FIRST_ERROR: u8 = 128;
const FIRST_EVENT: u8 = 64;
const BAD_IMPLEMENTATION: u8 = 17;

const QUERY_VERSION: u8 = 0;
const ATTACH: u8 = 1;
const DETACH: u8 = 2;
const PUT_IMAGE: u8 = 3;

struct Segment {
    shmid: i32,
    map: ShmMap,
    owner: ClientId,
}

#[derive(Default)]
pub struct MitShm {
    segments: HashMap<u32, Segment>,
    pacer: ShmFramePacer,
}

impl MitShm {
    fn detach(&mut self, s: &mut Server, xid: u32) {
        if let Some(seg) = self.segments.remove(&xid) {
            s.platform.shm_detach(seg.shmid);
        }
    }
}

impl Extension for MitShm {
    fn name(&self) -> &str {
        "MIT-SHM"
    }
    fn major_opcode(&self) -> u8 {
        MAJOR
    }
    fn first_error(&self) -> u8 {
        FIRST_ERROR
    }
    fn first_event(&self) -> u8 {
        FIRST_EVENT
    }

    fn handle(&mut self, s: &mut Server, c: ClientId, minor: u8, body: &[u8]) -> Res {
        let mut r = reader(s, c, body);
        match minor {
            QUERY_VERSION => {
                // The Java reply stopped after 17 bytes (the rest of the 32 was never written),
                // which desynchronises any client that calls XShmQueryVersion.
                s.reply(c, 0, 0, |w| {
                    w.u16(1).u16(1).u16(0).u16(0).u8(0).pad(15);
                });
            }
            ATTACH => {
                let (xid, shmid) = (r.u32()?, r.u32()? as i32);
                self.detach(s, xid);
                if let Some(map) = s.platform.shm_attach(shmid) {
                    self.segments.insert(xid, Segment { shmid, map, owner: c });
                }
            }
            DETACH => {
                let xid = r.u32()?;
                self.detach(s, xid);
            }
            PUT_IMAGE => {
                let (drawable, gc) = (r.u32()?, r.u32()?);
                let (total_w, _total_h) = (r.i16()?, r.i16()?);
                let (src_x, src_y, src_w, src_h) = (r.i16()?, r.i16()?, r.i16()?, r.i16()?);
                let (dst_x, dst_y) = (r.i16()?, r.i16()?);
                let depth = r.u8()?;
                r.skip(1)?; // format
                let send_event = r.u8()? != 0;
                r.skip(1)?;
                let shmseg = r.u32()?;
                // The Java server ignored this offset, which is wrong for any offset but 0.
                let offset = r.u32()? as usize;

                if !s.drawables.contains_key(&drawable) {
                    return Err(bad(err::BAD_DRAWABLE, drawable));
                }
                let Some(g) = s.gcs.get(&gc) else { return Err(bad(err::BAD_GC, gc)) };
                let function = g.function;
                let Some(seg) = self.segments.get(&shmseg) else { return Err(bad(FIRST_ERROR, shmseg)) };
                if offset > seg.map.len {
                    return Err(bad(FIRST_ERROR, shmseg));
                }
                if function != GX_COPY {
                    return Err(Fail::Drop("GC function other than COPY is not supported"));
                }
                // SAFETY: the platform keeps the mapping alive until shm_detach, and the guest
                // writes to it only as a client of this very request.
                let data = unsafe { std::slice::from_raw_parts(seg.map.ptr.add(offset), seg.map.len - offset) };
                let d = &s.drawables[&drawable];
                let (dw, dh) = (d.width as i32, d.height as i32);
                let drew = d.draw_image(src_x, src_y, dst_x, dst_y, src_w, src_h, depth, data, total_w);
                if drew {
                    s.content_changed(drawable);
                }
                // XShmPutImage(send_event = True): the client waits for this before it reuses the
                // segment, so it must not be left out.
                if send_event {
                    s.send_to_client(c, &Event::ShmCompletion { code: FIRST_EVENT, drawable, shmseg, offset: offset as u32 });
                }

                // Alternative FPS limiter for games that present through full-drawable blits and
                // never touch Present: a blit covering >= 80% of the drawable is a frame.
                let cw = (src_w as i32).min(dw - (dst_x as i32).max(0));
                let ch = (src_h as i32).min(dh - (dst_y as i32).max(0));
                if cw > 0 && ch > 0 && cw * ch * 5 >= dw * dh * 4 {
                    let fps = s.platform.fps_limit().min(1000);
                    let now = s.platform.now_ns();
                    let delay = self.pacer.frame_presented(s, drawable, fps, now);
                    if delay > 0 {
                        s.pause_reads(c, now + delay);
                    }
                }
            }
            _ => return Err(Fail::X { code: BAD_IMPLEMENTATION, data: 0 }),
        }
        Ok(())
    }

    /// Xlib does not detach a process's segments when it exits.
    fn on_client_disconnected(&mut self, s: &mut Server, client: ClientId) {
        let mine: Vec<u32> = self.segments.iter().filter(|(_, g)| g.owner == client).map(|(k, _)| *k).collect();
        for xid in mine {
            self.detach(s, xid);
        }
    }
}
