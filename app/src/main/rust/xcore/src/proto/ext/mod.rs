//! The protocol extensions of the Java server, ported one for one (Phase 3).
//!
//! | extension  | major | state                                             |
//! |------------|-------|---------------------------------------------------|
//! | Composite  | 148   | handler                                           |
//! | XInput     | 146   | handler                                           |
//! | RANDR      | 150   | `Server::randr` (the UI sets the refresh rates)   |
//! | GLX        | 151   | handler (metadata only: no GL is executed here)   |
//! | SYNC       | 152   | `Server::sync` (Present triggers its fences)      |
//! | Present    | 153   | handler                                           |
//! | DRI3       | 154   | handler                                           |
//! | MIT-SHM    | 155   | handler                                           |
//!
//! (BIG-REQUESTS, 156, is in `requests.rs`.)

pub mod composite;
pub mod dri3;
pub mod frame;
pub mod glx;
pub mod mitshm;
pub mod present;
pub mod randr;
pub mod sync;
pub mod xinput;

use super::wire::{Order, Reader};
use super::{ClientId, Server};

/// A reader over a request body in the byte order of the client.
pub(crate) fn reader<'a>(s: &Server, c: ClientId, body: &'a [u8]) -> Reader<'a> {
    Reader::new(body, s.client(c).map(|c| c.order).unwrap_or(Order::Le))
}

/// Registers every extension, as the Java `XServer.setupExtensions` did.
pub fn register_all(server: &mut Server) {
    server.register_extension(Box::new(super::requests::BigRequests));
    server.register_extension(Box::new(randr::Randr));
    server.register_extension(Box::new(sync::Sync));
    server.register_extension(Box::new(mitshm::MitShm::default()));
    server.register_extension(Box::new(composite::Composite::default()));
    server.register_extension(Box::new(xinput::XInput::default()));
    server.register_extension(Box::new(dri3::Dri3));
    server.register_extension(Box::new(present::Present::default()));
    server.register_extension(Box::new(glx::Glx::default()));
}
