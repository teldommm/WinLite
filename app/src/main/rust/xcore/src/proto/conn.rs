//! Connection level: the setup handshake and request framing (Java `XClientRequestHandler`).

use super::consts::{MAX_KEYCODE, MIN_KEYCODE, VENDOR_NAME, VERSION};
use super::wire::{Order, Writer};
use super::{ClientId, Fail, Notify, Server};

/// Largest request accepted (BIG-REQUESTS maximum, in 4-byte units).
const MAX_BIG_WORDS: u32 = 4194303;

enum Step {
    /// More bytes are needed.
    Wait,
    /// Consumed this many bytes.
    Consumed(usize),
    /// Protocol violation: close the connection.
    Kill,
}

impl Server {
    /// Feeds received bytes of a connection; complete requests are executed. Output accumulates in
    /// the client's buffer ([`Server::take_output`]); side effects are queued in `notifications`.
    pub fn feed(&mut self, client: ClientId, data: &[u8]) {
        let Some(c) = self.client_mut(client) else { return };
        let mut buf = std::mem::take(&mut c.inbuf);
        buf.extend_from_slice(data);
        let mut pos = 0;
        loop {
            let Some((authenticated, blocked)) = self.client(client).map(|c| (c.authenticated, c.blocked.is_some())) else { return };
            if blocked {
                break; // held (SYNC AwaitFence): the rest waits in the input buffer
            }
            let step = if authenticated { self.step_request(client, &buf[pos..]) } else { self.step_handshake(client, &buf[pos..]) };
            match step {
                Step::Wait => break,
                Step::Consumed(n) => pos += n,
                Step::Kill => {
                    self.notifications.push(Notify::Kill(client));
                    buf.clear();
                    pos = 0;
                    break;
                }
            }
        }
        buf.drain(..pos);
        if let Some(c) = self.client_mut(client) {
            c.inbuf = buf;
        }
        self.service_wakes();
    }

    /// Bytes queued for the socket of `client`.
    pub fn take_output(&mut self, client: ClientId) -> Vec<u8> {
        self.client_mut(client).map(|c| std::mem::take(&mut c.out)).unwrap_or_default()
    }

    /// The connection is gone: frees the client's resources and tells the extensions.
    pub fn client_disconnected(&mut self, client: ClientId) {
        self.import_jobs.retain(|_, j| j.client != client);
        self.remove_client(client);
        let ids: Vec<u8> = self.ext_handlers.keys().copied().collect();
        for id in ids {
            let _ = self.with_ext(id, |ext, s| ext.on_client_disconnected(s, client));
        }
    }

    fn step_handshake(&mut self, client: ClientId, buf: &[u8]) -> Step {
        if buf.len() < 12 {
            return Step::Wait;
        }
        let order = match buf[0] {
            66 => Order::Be,
            _ => Order::Le,
        };
        let rd16 = |i: usize| -> usize {
            match order {
                Order::Le => u16::from_le_bytes([buf[i], buf[i + 1]]) as usize,
                Order::Be => u16::from_be_bytes([buf[i], buf[i + 1]]) as usize,
            }
        };
        if rd16(2) != VERSION as usize {
            return Step::Kill;
        }
        let (name_len, data_len) = (rd16(6), rd16(8));
        let total = 12 + name_len + (4 - name_len % 4) % 4 + data_len + (4 - data_len % 4) % 4;
        if buf.len() < total {
            return Step::Wait;
        }
        if let Some(c) = self.client_mut(client) {
            c.order = order;
            c.authenticated = true;
        }
        self.send_setup(client);
        Step::Consumed(total)
    }

    fn send_setup(&mut self, client: ClientId) {
        let Some(c) = self.client(client) else { return };
        let (order, base) = (c.order, c.resource_base);
        let mut body = Vec::new();
        {
            let mut w = Writer::new(&mut body, order);
            w.u32(1).u32(base).u32(self.id_mask).u32(256).u16(VENDOR_NAME.len() as u16).u16(super::consts::MAX_REQUEST_LENGTH);
            w.u8(1).u8(self.formats.len() as u8).u8(0).u8(0).u8(32).u8(32).u8(MIN_KEYCODE).u8(MAX_KEYCODE).u32(0);
            w.string8(VENDOR_NAME);
            for &(depth, bpp, pad) in &self.formats {
                w.u8(depth).u8(bpp).u8(pad).pad(5);
            }
            let root_visual = &self.visuals[0];
            let (sw, sh) = (self.screen_w, self.screen_h);
            w.u32(self.root).u32(0).u32(0xffffff).u32(0).u32(self.all_event_masks(self.root));
            w.i16(sw).i16(sh).i16(sw / 10).i16(sh / 10).u16(1).u16(1).u32(root_visual.id).u8(0).u8(0).u8(root_visual.depth).u8(self.visuals.len() as u8);
            for v in &self.visuals {
                w.u8(v.depth).u8(0).u16(v.displayable as u16).u32(0);
                if v.displayable {
                    w.u32(v.id).u8(4).u8(v.bits_per_rgb).u16(256).u32(v.red_mask).u32(v.green_mask).u32(v.blue_mask).u32(0);
                }
            }
        }
        let words = (body.len() / 4) as u16;
        if let Some(c) = self.client_mut(client) {
            let mut w = Writer::new(&mut c.out, order);
            w.u8(1).u8(0).u16(VERSION).u16(0).u16(words).bytes(&body);
        }
        self.notifications.push(Notify::Output(client));
    }

    fn step_request(&mut self, client: ClientId, buf: &[u8]) -> Step {
        if buf.len() < 4 {
            return Step::Wait;
        }
        let order = self.client(client).map(|c| c.order).unwrap_or(Order::Le);
        let rd16 = |i: usize| -> u32 {
            match order {
                Order::Le => u16::from_le_bytes([buf[i], buf[i + 1]]) as u32,
                Order::Be => u16::from_be_bytes([buf[i], buf[i + 1]]) as u32,
            }
        };
        let (opcode, data) = (buf[0], buf[1]);
        let len16 = rd16(2);
        let (header, total) = if len16 != 0 {
            (4usize, len16 as usize * 4)
        } else {
            if buf.len() < 8 {
                return Step::Wait;
            }
            let b = [buf[4], buf[5], buf[6], buf[7]];
            let words = match order {
                Order::Le => u32::from_le_bytes(b),
                Order::Be => u32::from_be_bytes(b),
            };
            if !(2..=MAX_BIG_WORDS).contains(&words) {
                return Step::Kill;
            }
            (8usize, words as usize * 4)
        };
        if buf.len() < total {
            return Step::Wait;
        }
        if let Some(c) = self.client_mut(client) {
            c.seq = c.seq.wrapping_add(1);
        }
        match self.handle_request(client, opcode, data, &buf[header..total]) {
            Ok(()) => {}
            Err(Fail::X { code, data: bad_value }) => self.send_error(client, code, bad_value, data, opcode),
            Err(Fail::Drop(why)) => crate::log::warn(&format!("request opcode {opcode} (minor {data}) failed, dropped: {why}")),
        }
        Step::Consumed(total)
    }
}
