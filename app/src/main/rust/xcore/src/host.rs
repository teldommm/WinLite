//! Host test server: the protocol core behind a Unix socket, so real X clients (python-xlib,
//! xcb, Wine's Xlib) can talk to it on a development machine. Pixels live in plain host memory.
//! Not used on Android.

use crate::proto::gfx::{HostPixels, PixelStore};
use crate::proto::platform::{Platform, ShmMap};
use crate::proto::{Config, Server};
use crate::runtime::Runtime;
use std::io::{self, BufRead};
use std::time::Instant;

/// The host stands in for Android's SysV emulation: a guest's shmid is a real SysV id here.
struct HostPlatform {
    attached: std::collections::HashMap<i32, (usize, usize)>,
}

impl Platform for HostPlatform {
    fn shm_attach(&mut self, shmid: i32) -> Option<ShmMap> {
        if let Some((addr, len)) = self.attached.get(&shmid) {
            return Some(ShmMap { ptr: *addr as *mut u8, len: *len });
        }
        unsafe {
            let mut st: libc::shmid_ds = std::mem::zeroed();
            if libc::shmctl(shmid, libc::IPC_STAT, &mut st) < 0 {
                return None;
            }
            let p = libc::shmat(shmid, std::ptr::null(), 0);
            if p as isize == -1 {
                return None;
            }
            self.attached.insert(shmid, (p as usize, st.shm_segsz));
            Some(ShmMap { ptr: p as *mut u8, len: st.shm_segsz })
        }
    }

    fn shm_detach(&mut self, shmid: i32) {
        // Segments stay mapped for the process lifetime: a segment is attached once per shmid
        // and the host does not count attachments.
        let _ = shmid;
    }
}

pub fn new_server(width: i16, height: i16) -> Server {
    let start = Instant::now();
    let mut s = Server::new(Config {
        width,
        height,
        alloc: Box::new(|w, h| {
            if w <= 0 || h <= 0 {
                return None;
            }
            Some(Box::new(HostPixels::new(w as usize, h as usize, false)) as Box<dyn PixelStore>)
        }),
        clock_ms: Box::new(move || start.elapsed().as_millis() as u32),
        platform: Box::new(HostPlatform { attached: Default::default() }),
    });
    crate::proto::ext::register_all(&mut s);
    s
}

/// Commands accepted on stdin (one per line), to drive input from a test:
/// `move X Y`, `button N 1|0`, `key CODE 1|0 [KEYSYM]`, `relative 1|0`, `raw DX DY`, `rates ACTIVE R...`, `quit`.
fn run_command(server: &mut Server, line: &str) -> bool {
    let p: Vec<&str> = line.split_whitespace().collect();
    match p.as_slice() {
        ["move", x, y] => server.set_pointer_position(x.parse().unwrap_or(0), y.parse().unwrap_or(0)),
        ["button", n, d] => server.set_pointer_button(n.parse().unwrap_or(1), *d == "1"),
        ["key", code, d] => {
            let c: u8 = code.parse().unwrap_or(0);
            if *d == "1" {
                server.key_press(c, 0)
            } else {
                server.key_release(c)
            }
        }
        ["key", code, d, sym] => {
            let c: u8 = code.parse().unwrap_or(0);
            if *d == "1" {
                server.key_press(c, sym.parse().unwrap_or(0))
            } else {
                server.key_release(c)
            }
        }
        ["raw", dx, dy] => server.raw_motion(dx.parse().unwrap_or(0.0), dy.parse().unwrap_or(0.0)),
        ["rates", active, rest @ ..] => {
            let rates: Vec<i16> = rest.iter().filter_map(|r| r.parse().ok()).collect();
            server.set_refresh_rates(&rates, active.parse().unwrap_or(60));
        }
        ["relative", v] => server.input.relative_mouse = *v == "1",
        ["quit"] => return false,
        _ => eprintln!("xcore_host: unknown command {line:?}"),
    }
    true
}

pub fn serve(path: &str, width: i16, height: i16) -> io::Result<()> {
    let rt = Runtime::start(path, new_server(width, height))?;
    for line in io::stdin().lock().lines().map_while(Result::ok) {
        if !rt.with(|s| run_command(s, &line)) {
            rt.stop();
            return Ok(());
        }
    }
    // stdin closed: keep serving until the process is killed.
    loop {
        std::thread::sleep(std::time::Duration::from_secs(3600));
    }
}
