//! RANDR 1.1 subset (Java `RandrExtension`): version, screen info with the refresh rates, rate
//! selection. Wine's xrandr10 path and games read the display frequency from GetScreenInfo.

use super::reader;
use crate::proto::requests::Extension;
use crate::proto::{ClientId, Fail, Res, Server};

pub const MAJOR: u8 = 150; // Java: (byte)-106
const FIRST_EVENT: u8 = 65;
const RR_ROTATE_0: u16 = 1;
const BAD_IMPLEMENTATION: u8 = 17;

const QUERY_VERSION: u8 = 0;
const SET_SCREEN_CONFIG: u8 = 2;
const SELECT_INPUT: u8 = 4;
const GET_SCREEN_INFO: u8 = 5;

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

pub struct Randr;

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
                    w.u32(1).u32(1).pad(16);
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
            _ => return Err(Fail::X { code: BAD_IMPLEMENTATION, data: 0 }),
        }
        Ok(())
    }
}
