//! Frame pacing shared by the FPS limiters (Java `FrameClock` and `ShmFramePacer`).

use crate::proto::Server;
use std::collections::HashMap;

const MAX_SLEW_NS: i64 = 500_000;
const GAIN_DIV: i64 = 8;
const MAX_TRACKED_DRAWABLES: usize = 128;

/// The interval between two releases of an FPS-limited game, on the renderer's display clock:
/// an interval within 1% of a whole number of refreshes becomes exactly that many, and each new
/// arrival-phase error the renderer measured slews the next release by an eighth of it (at most
/// 0.5 ms), so the game's frames land on a refresh with the least latency.
#[derive(Default)]
pub struct FrameClock {
    /// The last measurement applied: each is used once, by whichever limiter releases next.
    applied_seq: i64,
}

impl Server {
    /// One display refresh in ns: the renderer's measured period, else the RANDR rate in force.
    /// Present's MSC counts these.
    pub fn refresh_interval_ns(&mut self) -> i64 {
        if let Some(clock) = self.platform.display_clock() {
            if clock[0] > 0 {
                return clock[0];
            }
        }
        1_000_000_000 / self.randr.current.max(1) as i64
    }

    pub fn next_frame_interval(&mut self, fps: i32) -> i64 {
        let mut interval = 1_000_000_000i64 / fps.max(1) as i64;
        let Some(clock) = self.platform.display_clock() else { return interval };
        let period = clock[0];
        if period > 0 {
            let k = (interval + period / 2) / period;
            if k >= 1 && (interval - k * period).abs() * 100 <= interval {
                interval = k * period;
            }
        }
        let seq = clock[2];
        if seq != self.frame_clock.applied_seq {
            self.frame_clock.applied_seq = seq;
            let corr = (clock[1] / GAIN_DIV).clamp(-MAX_SLEW_NS, MAX_SLEW_NS);
            interval -= corr; // arrived late: release sooner
        }
        interval
    }
}

/// MIT-SHM games that present with full-drawable blits never touch Present: this paces them by
/// suspending their socket reads, which blocks the game inside its own per-frame XSync.
#[derive(Default)]
pub struct ShmFramePacer {
    next_ns: HashMap<u32, i64>,
}

impl ShmFramePacer {
    /// Registers a presented frame; returns how long (ns) the client's reads should be suspended.
    pub fn frame_presented(&mut self, server: &mut Server, drawable: u32, fps: i32, now_ns: i64) -> i64 {
        if fps <= 0 {
            self.next_ns.clear();
            return 0;
        }
        if self.next_ns.len() > MAX_TRACKED_DRAWABLES {
            // Evict the longest-idle entry, never the presenting drawable.
            if let Some(oldest) = self.next_ns.iter().filter(|(k, _)| **k != drawable).min_by_key(|(_, v)| **v).map(|(k, _)| *k) {
                self.next_ns.remove(&oldest);
            }
        }
        let next = self.next_ns.entry(drawable).or_insert(0);
        if *next < now_ns {
            *next = now_ns;
        }
        let delay = *next - now_ns;
        let step = server.next_frame_interval(fps);
        *self.next_ns.get_mut(&drawable).unwrap() += step;
        delay
    }
}
