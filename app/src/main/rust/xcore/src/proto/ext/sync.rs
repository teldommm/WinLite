//! SYNC fences (Java `SyncExtension`): CreateFence, TriggerFence, ResetFence, DestroyFence and
//! AwaitFence. Present triggers fences when a buffer becomes idle.
//!
//! The fence table lives in [`Server::sync`] so Present and the core (resources being freed,
//! clients leaving) can reach it. AwaitFence does not block a thread: the client's requests are
//! put on hold ([`Blocked`]) and resume when a fence of the list triggers.

use super::reader;
use crate::proto::consts::err;
use crate::proto::hooks::Blocked;
use crate::proto::requests::Extension;
use crate::proto::{bad, ClientId, Fail, Res, Server};
use std::collections::BTreeMap;

pub const MAJOR: u8 = 152; // Java: (byte)-104
/// SYNC has three errors (Counter, Alarm, Fence); BadFence is the third.
pub const FIRST_ERROR: u8 = 160;
const BAD_IMPLEMENTATION: u8 = 17;

const INITIALIZE: u8 = 0;
const LIST_SYSTEM_COUNTERS: u8 = 1;
const CREATE_FENCE: u8 = 14;
const TRIGGER_FENCE: u8 = 15;
const RESET_FENCE: u8 = 16;
const DESTROY_FENCE: u8 = 17;
const QUERY_FENCE: u8 = 18;
const AWAIT_FENCE: u8 = 19;

#[derive(Default)]
pub struct SyncState {
    /// id -> (drawable it was created for, triggered)
    fences: BTreeMap<u32, (u32, bool)>,
}

impl SyncState {
    pub fn first_error(&self) -> u8 {
        FIRST_ERROR
    }

    /// `Some(triggered)`, or `None` for a fence that does not exist.
    pub fn fence_state(&self, id: u32) -> Option<bool> {
        self.fences.get(&id).map(|f| f.1)
    }

    /// Triggers the fence if it exists (Java `setTriggered`).
    pub fn trigger(&mut self, id: u32) {
        if let Some(f) = self.fences.get_mut(&id) {
            f.1 = true;
        }
    }

    /// A window or pixmap went away: its fences with it.
    pub fn drop_for_drawable(&mut self, drawable: u32) {
        self.fences.retain(|_, f| f.0 != drawable);
    }

    /// A client left: the fences in its id range, which the next client gets.
    pub fn drop_in_range(&mut self, base: u32, mask: u32) {
        self.fences.retain(|id, _| (id | mask) != (base | mask));
    }

    pub fn len(&self) -> usize {
        self.fences.len()
    }

    pub fn is_empty(&self) -> bool {
        self.fences.is_empty()
    }
}

fn bad_fence(id: u32) -> Fail {
    Fail::X { code: FIRST_ERROR + 2, data: id }
}

pub struct Sync;

impl Extension for Sync {
    fn name(&self) -> &str {
        "SYNC"
    }
    fn major_opcode(&self) -> u8 {
        MAJOR
    }
    fn first_error(&self) -> u8 {
        FIRST_ERROR
    }

    fn handle(&mut self, s: &mut Server, c: ClientId, minor: u8, body: &[u8]) -> Res {
        let mut r = reader(s, c, body);
        match minor {
            // Not in the Java server (BadImplementation, which killed xdpyinfo and any client that
            // calls XSyncInitialize); version 3.1 is what fences need.
            INITIALIZE => {
                s.reply(c, 0, 0, |w| {
                    w.u8(3).u8(1).pad(22);
                });
            }
            // No counters (only fences exist), so XSyncListSystemCounters has nothing to list.
            LIST_SYSTEM_COUNTERS => {
                s.reply(c, 0, 0, |w| {
                    w.u32(0).pad(20);
                });
            }
            QUERY_FENCE => {
                let id = r.u32()?;
                let Some(state) = s.sync.fence_state(id) else { return Err(bad_fence(id)) };
                s.reply(c, 0, 0, |w| {
                    w.u8(state as u8).pad(23);
                });
            }
            CREATE_FENCE => {
                let drawable = r.u32()?;
                let id = r.u32()?;
                if s.sync.fences.contains_key(&id) {
                    return Err(bad(err::BAD_ID_CHOICE, id));
                }
                let triggered = r.u8()? == 1;
                s.sync.fences.insert(id, (drawable, triggered));
            }
            TRIGGER_FENCE => {
                let id = r.u32()?;
                if !s.sync.fences.contains_key(&id) {
                    return Err(bad_fence(id));
                }
                s.sync.trigger(id);
            }
            RESET_FENCE => {
                let id = r.u32()?;
                match s.sync.fences.get_mut(&id) {
                    None => return Err(bad_fence(id)),
                    Some(f) if !f.1 => return Err(bad(err::BAD_MATCH, 0)),
                    Some(f) => f.1 = false,
                }
            }
            DESTROY_FENCE => {
                let id = r.u32()?;
                if s.sync.fences.remove(&id).is_none() {
                    return Err(bad_fence(id));
                }
            }
            AWAIT_FENCE => {
                let mut ids = Vec::new();
                while r.remaining() >= 4 {
                    ids.push(r.u32()?);
                }
                // The Java server waited on an empty list for ever (and with it its only request
                // thread); an empty wait has nothing to wait for.
                for id in &ids {
                    match s.sync.fence_state(*id) {
                        None => return Err(bad_fence(*id)),
                        Some(true) => return Ok(()),
                        Some(false) => {}
                    }
                }
                if !ids.is_empty() {
                    s.block_client(c, Blocked::Fences { ids, major: MAJOR, minor });
                }
            }
            _ => return Err(Fail::X { code: BAD_IMPLEMENTATION, data: 0 }),
        }
        Ok(())
    }
}
