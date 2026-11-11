//! `xcore_host [socket] [WIDTHxHEIGHT]`: runs the protocol core on a Unix socket for testing.
fn main() {
    let mut args = std::env::args().skip(1);
    let path = args.next().unwrap_or_else(|| "/tmp/.X11-unix/X77".to_string());
    let size = args.next().unwrap_or_else(|| "1024x768".to_string());
    let (w, h) = size.split_once('x').map(|(a, b)| (a.parse().unwrap_or(1024), b.parse().unwrap_or(768))).unwrap_or((1024, 768));
    if let Err(e) = xcore::host::serve(&path, w, h) {
        eprintln!("xcore_host: {e}");
        std::process::exit(1);
    }
}
