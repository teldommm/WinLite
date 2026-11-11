//! RANDR 1.2: one CRTC, one output, and one mode per refresh rate the panel offers (all of the
//! screen's size). Wine's xrandr10 path and games read the display frequency from GetScreenInfo;
//! GetScreenResources, GetOutputInfo, GetCrtcInfo and the gamma requests are what clients of the
//! 1.2 interface (SDL, GLFW, Qt, xrandr) need. Rate changes go through SetScreenConfig and
//! SetCrtcConfig. The screen size cannot change.

use super::reader;
use crate::proto::consts::err;
use crate::proto::requests::Extension;
use crate::proto::{bad, ClientId, Fail, Res, Server};

pub const MAJOR: u8 = 150; // Java: (byte)-106
const FIRST_EVENT: u8 = 65;
const RR_ROTATE_0: u16 = 1;
const BAD_IMPLEMENTATION: u8 = 17;

const QUERY_VERSION: u8 = 0;
const SET_SCREEN_CONFIG: u8 = 2;
const SELECT_INPUT: u8 = 4;
const GET_SCREEN_INFO: u8 = 5;
const GET_SCREEN_SIZE_RANGE: u8 = 6;
const SET_SCREEN_SIZE: u8 = 7;
const GET_SCREEN_RESOURCES: u8 = 8;
const GET_OUTPUT_INFO: u8 = 9;
const LIST_OUTPUT_PROPERTIES: u8 = 10;
const GET_OUTPUT_PROPERTY: u8 = 15;
const GET_CRTC_INFO: u8 = 20;
const SET_CRTC_CONFIG: u8 = 21;
const GET_CRTC_GAMMA_SIZE: u8 = 22;
const GET_CRTC_GAMMA: u8 = 23;
const SET_CRTC_GAMMA: u8 = 24;
const GET_SCREEN_RESOURCES_CURRENT: u8 = 25;

/// Ids the server hands out (below every client's resource id range).
const CRTC: u32 = 0x100001;
const OUTPUT: u32 = 0x100002;
const MODE_BASE: u32 = 0x100003;

/// RRSetConfigSuccess / RRSetConfigFailed.
const CONFIG_SUCCESS: u8 = 0;
const CONFIG_FAILED: u8 = 3;
/// The timestamp of every reply: nothing here ever changes configuration.
const TIMESTAMP: u32 = 1;
const GAMMA_SIZE: u16 = 256;
const OUTPUT_NAME: &str = "WinLite";

/// The rates the panel offers and the one in use; set by the UI when the panel mode changes.
#[derive(Clone, Debug)]
pub struct RandrState {
    pub rates: Vec<i16>,
    pub current: i16,
}

impl Default for RandrState {
    fn default() -> Self {
        RandrState { rates: vec![60], current: 60 }
    }
}

fn match_rate(rates: &[i16], active: i16) -> i16 {
    let mut best = rates[0];
    for &r in rates {
        if (r as i32 - active as i32).abs() < (best as i32 - active as i32).abs() {
            best = r;
        }
    }
    best
}

impl Server {
    /// The panel's modes. Ignored when `rates` is empty. The current rate becomes the one of
    /// `rates` closest to `active` (display rates are rounded floats).
    pub fn set_refresh_rates(&mut self, rates: &[i16], active: i16) {
        if rates.is_empty() {
            return;
        }
        self.randr.rates = rates.to_vec();
        self.randr.current = match_rate(rates, active);
    }

    /// The panel mode changed: report its rate as the current one.
    pub fn set_active_rate(&mut self, active: i16) {
        self.randr.current = match_rate(&self.randr.rates, active);
    }
}

fn pad4(n: usize) -> usize {
    (4 - n % 4) % 4
}

pub struct Randr;

impl Randr {
    /// GetScreenResources / GetScreenResourcesCurrent.
    fn screen_resources(s: &mut Server, c: ClientId) {
        let rates = s.randr.rates.clone();
        let (width, height) = (s.screen_w.max(0) as u16, s.screen_h.max(0) as u16);
        let name = format!("{width}x{height}");
        let names_size = name.len() * rates.len();
        let names_pad = pad4(names_size);
        let length = (8 + rates.len() * 32 + names_size + names_pad) / 4;
        s.reply(c, 0, length as u32, |w| {
            w.u32(TIMESTAMP).u32(TIMESTAMP);
            w.u16(1).u16(1).u16(rates.len() as u16).u16(names_size as u16).pad(8);
            w.u32(CRTC).u32(OUTPUT);
            for (i, rate) in rates.iter().enumerate() {
                // refresh rate = dot clock / (hTotal * vTotal)
                let dot_clock = (width as u64 * height as u64 * (*rate).max(0) as u64).min(u32::MAX as u64) as u32;
                w.u32(MODE_BASE + i as u32).u16(width).u16(height).u32(dot_clock);
                w.u16(width).u16(width).u16(width).u16(0); // hSyncStart, hSyncEnd, hTotal, hSkew
                w.u16(height).u16(height).u16(height).u16(name.len() as u16); // vSyncStart, vSyncEnd, vTotal, nameLength
                w.u32(0); // modeFlags
            }
            for _ in 0..rates.len() {
                w.bytes(name.as_bytes());
            }
            w.pad(names_pad);
        });
    }
}

impl Extension for Randr {
    fn name(&self) -> &str {
        "RANDR"
    }
    fn major_opcode(&self) -> u8 {
        MAJOR
    }
    fn first_event(&self) -> u8 {
        FIRST_EVENT
    }

    fn handle(&mut self, s: &mut Server, c: ClientId, minor: u8, body: &[u8]) -> Res {
        match minor {
            QUERY_VERSION => {
                s.reply(c, 0, 0, |w| {
                    w.u32(1).u32(2).pad(16);
                });
            }
            GET_SCREEN_INFO => {
                let window = reader(s, c, body).u32()?;
                let rates = s.randr.rates.clone();
                let current = s.randr.current;
                let (width, height) = (s.screen_w, s.screen_h);
                let entries = 1 + rates.len();
                let body_bytes = 8 + entries * 2;
                let pad = (4 - body_bytes % 4) % 4;
                s.reply(c, RR_ROTATE_0 as u8, ((body_bytes + pad) / 4) as u32, |w| {
                    w.u32(window).u32(0).u32(0);
                    w.u16(1).u16(0).u16(RR_ROTATE_0).u16(current as u16).u16(entries as u16).u16(0);
                    w.u16(width as u16).u16(height as u16).u16((width / 10) as u16).u16((height / 10) as u16);
                    w.u16(rates.len() as u16);
                    for r in &rates {
                        w.u16(*r as u16);
                    }
                    w.pad(pad);
                });
            }
            SET_SCREEN_CONFIG => {
                let mut r = reader(s, c, body);
                let window = r.u32()?;
                r.skip(8)?;
                r.u16()?;
                r.u16()?;
                let requested = r.u16()? as i16;
                if s.randr.rates.contains(&requested) {
                    s.randr.current = requested;
                }
                s.reply(c, 0, 0, |w| {
                    w.u32(0).u32(0).u32(window).u16(0).u16(0).pad(8);
                });
            }
            SELECT_INPUT => {}
            GET_SCREEN_SIZE_RANGE => {
                // The size is fixed: the range is that one size.
                let (width, height) = (s.screen_w.max(0) as u16, s.screen_h.max(0) as u16);
                s.reply(c, 0, 0, |w| {
                    w.u16(width).u16(height).u16(width).u16(height).pad(16);
                });
            }
            SET_SCREEN_SIZE => {
                let mut r = reader(s, c, body);
                let _window = r.u32()?;
                let (width, height) = (r.u16()? as i32, r.u16()? as i32);
                if width != s.screen_w as i32 || height != s.screen_h as i32 {
                    return Err(bad(err::BAD_MATCH, 0));
                }
            }
            GET_SCREEN_RESOURCES | GET_SCREEN_RESOURCES_CURRENT => Self::screen_resources(s, c),
            GET_OUTPUT_INFO => {
                let output = reader(s, c, body).u32()?;
                if output != OUTPUT {
                    return Err(bad(err::BAD_VALUE, output));
                }
                let rates = s.randr.rates.len();
                let (mm_w, mm_h) = (s.screen_w.max(0) as u32 / 10, s.screen_h.max(0) as u32 / 10);
                let name_pad = pad4(OUTPUT_NAME.len());
                // The reply is 36 bytes: the length counts the 4 beyond the usual 32 as well.
                let length = (4 + 4 + rates * 4 + OUTPUT_NAME.len() + name_pad) / 4;
                s.reply(c, CONFIG_SUCCESS, length as u32, |w| {
                    w.u32(TIMESTAMP).u32(CRTC).u32(mm_w).u32(mm_h);
                    w.u8(0).u8(0); // connected, subpixel order unknown
                    w.u16(1).u16(rates as u16).u16(1).u16(0).u16(OUTPUT_NAME.len() as u16); // crtcs, modes, preferred, clones, name length
                    w.u32(CRTC);
                    for i in 0..rates {
                        w.u32(MODE_BASE + i as u32);
                    }
                    w.bytes(OUTPUT_NAME.as_bytes()).pad(name_pad);
                });
            }
            GET_CRTC_INFO => {
                let crtc = reader(s, c, body).u32()?;
                if crtc != CRTC {
                    return Err(bad(err::BAD_VALUE, crtc));
                }
                let (width, height) = (s.screen_w.max(0) as u16, s.screen_h.max(0) as u16);
                let index = s.randr.rates.iter().position(|r| *r == s.randr.current).unwrap_or(0);
                s.reply(c, CONFIG_SUCCESS, 2, |w| {
                    w.u32(TIMESTAMP).i16(0).i16(0).u16(width).u16(height);
                    w.u32(MODE_BASE + index as u32).u16(RR_ROTATE_0).u16(RR_ROTATE_0);
                    w.u16(1).u16(1); // outputs, possible outputs
                    w.u32(OUTPUT).u32(OUTPUT);
                });
            }
            SET_CRTC_CONFIG => {
                let mut r = reader(s, c, body);
                let crtc = r.u32()?;
                r.skip(8)?; // timestamp, config timestamp
                let (x, y) = (r.i16()?, r.i16()?);
                let mode = r.u32()?;
                let mut status = CONFIG_FAILED;
                if crtc == CRTC && x == 0 && y == 0 && mode >= MODE_BASE {
                    if let Some(rate) = s.randr.rates.get((mode - MODE_BASE) as usize).copied() {
                        s.randr.current = rate;
                        status = CONFIG_SUCCESS;
                    }
                }
                s.reply(c, status, 0, |w| {
                    w.u32(TIMESTAMP).pad(20);
                });
            }
            LIST_OUTPUT_PROPERTIES | GET_OUTPUT_PROPERTY => {
                // No properties (no EDID): an empty list / "property does not exist".
                s.reply(c, 0, 0, |w| {
                    w.pad(24);
                });
            }
            GET_CRTC_GAMMA_SIZE => {
                s.reply(c, 0, 0, |w| {
                    w.u16(GAMMA_SIZE).pad(22);
                });
            }
            GET_CRTC_GAMMA => {
                // A linear ramp for each of red, green and blue.
                s.reply(c, 0, 3 * GAMMA_SIZE as u32 / 2, |w| {
                    w.u16(GAMMA_SIZE).pad(22);
                    for _ in 0..3 {
                        for i in 0..GAMMA_SIZE {
                            w.u16(i * 257);
                        }
                    }
                });
            }
            SET_CRTC_GAMMA => {}
            _ => return Err(Fail::X { code: BAD_IMPLEMENTATION, data: 0 }),
        }
        Ok(())
    }
}
