//! Composite (Java `XCompositeExtension`): RedirectWindow / UnredirectWindow. A redirected window
//! is drawn off screen; the renderer is told ([`Notify::CompositeRedirect`]) and the window's
//! content drawable is marked offscreen.

use super::reader;
use crate::proto::consts::err;
use crate::proto::hooks::Hook;
use crate::proto::requests::Extension;
use crate::proto::{bad, ClientId, Fail, Notify, Res, Server};

pub const MAJOR: u8 = 148; // Java: (byte)-108
const BAD_IMPLEMENTATION: u8 = 17;

const QUERY_VERSION: u8 = 0;
const REDIRECT_WINDOW: u8 = 1;
const UNREDIRECT_WINDOW: u8 = 3;

const UPDATE_AUTOMATIC: u8 = 0;
const UPDATE_MANUAL: u8 = 1;

struct Redirect {
    client: ClientId,
    window: u32,
    update: u8,
}

#[derive(Default)]
pub struct Composite {
    redirects: Vec<Redirect>,
}

fn set_state(s: &mut Server, window: u32, redirected: bool) {
    let has_content = match s.windows.get_mut(&window) {
        Some(w) => {
            w.composite_redirected = redirected;
            w.has_content
        }
        None => return,
    };
    if has_content {
        if let Some(d) = s.drawables.get_mut(&window) {
            d.offscreen = redirected;
        }
    }
    s.notifications.push(Notify::CompositeRedirect { window, redirected });
}

impl Composite {
    fn has_redirect(&self, window: u32) -> bool {
        self.redirects.iter().any(|r| r.window == window)
    }
}

impl Extension for Composite {
    fn name(&self) -> &str {
        "Composite"
    }
    fn major_opcode(&self) -> u8 {
        MAJOR
    }

    fn handle(&mut self, s: &mut Server, c: ClientId, minor: u8, body: &[u8]) -> Res {
        match minor {
            QUERY_VERSION => {
                s.reply(c, 0, 0, |w| {
                    w.u32(0).u32(1).pad(16);
                });
            }
            REDIRECT_WINDOW => {
                let mut r = reader(s, c, body);
                let window = r.u32()?;
                let update = r.u8()?;
                if update != UPDATE_AUTOMATIC && update != UPDATE_MANUAL {
                    return Err(bad(err::BAD_VALUE, update as u32));
                }
                let Some(w) = s.windows.get(&window) else { return Err(bad(err::BAD_WINDOW, window)) };
                if window == s.root || !w.has_content {
                    return Err(bad(err::BAD_MATCH, 0));
                }
                for red in &self.redirects {
                    if red.client == c && red.window == window && red.update == update {
                        return Ok(());
                    }
                    if update == UPDATE_MANUAL && red.window == window && red.update == UPDATE_MANUAL {
                        return Err(bad(err::BAD_ACCESS, 0));
                    }
                }
                self.redirects.push(Redirect { client: c, window, update });
                set_state(s, window, true);
            }
            UNREDIRECT_WINDOW => {
                let mut r = reader(s, c, body);
                let window = r.u32()?;
                let update = r.u8()?;
                if update != UPDATE_AUTOMATIC && update != UPDATE_MANUAL {
                    return Err(bad(err::BAD_VALUE, update as u32));
                }
                if !s.windows.contains_key(&window) {
                    return Err(bad(err::BAD_WINDOW, window));
                }
                let before = self.redirects.len();
                self.redirects.retain(|r| !(r.client == c && r.window == window && r.update == update));
                if self.redirects.len() == before {
                    return Err(bad(err::BAD_VALUE, window));
                }
                if !self.has_redirect(window) {
                    set_state(s, window, false);
                }
            }
            _ => return Err(Fail::X { code: BAD_IMPLEMENTATION, data: 0 }),
        }
        Ok(())
    }

    fn on_hook(&mut self, _s: &mut Server, hook: &Hook) {
        if let Hook::WindowFreed(w) = hook {
            self.redirects.retain(|r| r.window != *w);
        }
    }

    /// A redirect ends with the client that made it (the Java server left it in place, which kept
    /// other clients' windows off screen for the rest of the session).
    fn on_client_disconnected(&mut self, s: &mut Server, client: ClientId) {
        let windows: Vec<u32> = self.redirects.iter().filter(|r| r.client == client).map(|r| r.window).collect();
        self.redirects.retain(|r| r.client != client);
        for w in windows {
            if !self.has_redirect(w) {
                set_state(s, w, false);
            }
        }
    }
}
