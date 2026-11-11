//! Protocol core tests: a minimal client speaks raw X11 bytes to a [`Server`] in memory.

use super::*;
use crate::host::new_server;

#[derive(Debug, Clone)]
enum Msg {
    Reply { data: u8, seq: u16, body: Vec<u8> },
    Event([u8; 32]),
    Error([u8; 32]),
}

fn parse(out: &[u8]) -> Vec<Msg> {
    let mut v = Vec::new();
    let mut i = 0;
    while i < out.len() {
        match out[i] {
            0 => {
                let mut e = [0u8; 32];
                e.copy_from_slice(&out[i..i + 32]);
                v.push(Msg::Error(e));
                i += 32;
            }
            1 => {
                let len = u32::from_le_bytes(out[i + 4..i + 8].try_into().unwrap()) as usize;
                v.push(Msg::Reply { data: out[i + 1], seq: u16::from_le_bytes([out[i + 2], out[i + 3]]), body: out[i + 8..i + 32 + len * 4].to_vec() });
                i += 32 + len * 4;
            }
            _ => {
                let mut e = [0u8; 32];
                e.copy_from_slice(&out[i..i + 32]);
                v.push(Msg::Event(e));
                i += 32;
            }
        }
    }
    v
}

struct T {
    s: Server,
    c: ClientId,
    base: u32,
}

fn u32le(v: u32) -> [u8; 4] {
    v.to_le_bytes()
}

impl T {
    fn new() -> T {
        let mut s = new_server(1024, 768);
        let c = s.add_client().unwrap();
        let base = s.client(c).unwrap().resource_base;
        s.feed(c, &[0x6c, 0, 11, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
        let mut t = T { s, c, base };
        let out = t.out();
        assert_eq!(out[0], 1, "setup must succeed");
        t
    }

    fn second(&mut self) -> (ClientId, u32) {
        let c = self.s.add_client().unwrap();
        let base = self.s.client(c).unwrap().resource_base;
        self.s.feed(c, &[0x6c, 0, 11, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
        self.s.take_output(c);
        (c, base)
    }

    fn id(&self, n: u32) -> u32 {
        self.base + n
    }

    fn req(&mut self, opcode: u8, data: u8, body: &[u8]) {
        let c = self.c;
        self.req_as(c, opcode, data, body);
    }

    fn req_as(&mut self, c: ClientId, opcode: u8, data: u8, body: &[u8]) {
        assert_eq!(body.len() % 4, 0, "body must be padded");
        let words = (body.len() / 4 + 1) as u16;
        let mut v = vec![opcode, data];
        v.extend_from_slice(&words.to_le_bytes());
        v.extend_from_slice(body);
        self.s.feed(c, &v);
    }

    fn out(&mut self) -> Vec<u8> {
        let c = self.c;
        self.s.take_output(c)
    }

    fn msgs(&mut self) -> Vec<Msg> {
        let o = self.out();
        parse(&o)
    }

    fn create_window(&mut self, id: u32, parent: u32, x: i16, y: i16, w: u16, h: u16, mask: u32, values: &[u32]) {
        let c = self.c;
        self.create_window_as(c, id, parent, x, y, w, h, mask, values);
    }

    #[allow(clippy::too_many_arguments)]
    fn create_window_as(&mut self, c: ClientId, id: u32, parent: u32, x: i16, y: i16, w: u16, h: u16, mask: u32, values: &[u32]) {
        let mut b = Vec::new();
        b.extend(u32le(id));
        b.extend(u32le(parent));
        b.extend(x.to_le_bytes());
        b.extend(y.to_le_bytes());
        b.extend(w.to_le_bytes());
        b.extend(h.to_le_bytes());
        b.extend(0u16.to_le_bytes()); // border
        b.extend(1u16.to_le_bytes()); // InputOutput
        b.extend(u32le(0)); // visual: CopyFromParent
        b.extend(u32le(mask));
        for v in values {
            b.extend(u32le(*v));
        }
        self.req_as(c, 1, 0, &b);
    }

    fn map(&mut self, id: u32) {
        self.req(8, 0, &u32le(id));
    }

    fn select(&mut self, id: u32, mask: u32) {
        let mut b = Vec::new();
        b.extend(u32le(id));
        b.extend(u32le(1 << 11));
        b.extend(u32le(mask));
        self.req(2, 0, &b);
    }

    fn intern(&mut self, name: &str) -> u32 {
        let mut b = Vec::new();
        b.extend((name.len() as u16).to_le_bytes());
        b.extend([0, 0]);
        b.extend(name.as_bytes());
        while b.len() % 4 != 0 {
            b.push(0);
        }
        self.req(16, 0, &b);
        match &self.msgs()[0] {
            Msg::Reply { body, .. } => u32::from_le_bytes(body[0..4].try_into().unwrap()),
            m => panic!("{m:?}"),
        }
    }

    fn change_property(&mut self, win: u32, mode: u8, atom: u32, ty: u32, format: u8, data: &[u8]) {
        let mut b = Vec::new();
        b.extend(u32le(win));
        b.extend(u32le(atom));
        b.extend(u32le(ty));
        b.extend([format, 0, 0, 0]);
        b.extend(u32le((data.len() / (format as usize / 8)) as u32));
        b.extend(data);
        while b.len() % 4 != 0 {
            b.push(0);
        }
        self.req(18, mode, &b);
    }

    fn get_property(&mut self, win: u32, atom: u32, ty: u32, off: u32, len: u32, delete: bool) -> Msg {
        let mut b = Vec::new();
        for v in [win, atom, ty, off, len] {
            b.extend(u32le(v));
        }
        self.req(20, delete as u8, &b);
        self.msgs().remove(0)
    }

    fn create_gc(&mut self, id: u32, drawable: u32, mask: u32, values: &[u32]) {
        let mut b = Vec::new();
        for v in [id, drawable, mask] {
            b.extend(u32le(v));
        }
        for v in values {
            b.extend(u32le(*v));
        }
        self.req(55, 0, &b);
    }

    fn get_image(&mut self, d: u32, x: i16, y: i16, w: u16, h: u16) -> Vec<u8> {
        let mut b = Vec::new();
        b.extend(u32le(d));
        b.extend(x.to_le_bytes());
        b.extend(y.to_le_bytes());
        b.extend(w.to_le_bytes());
        b.extend(h.to_le_bytes());
        b.extend(u32le(!0));
        self.req(73, 2, &b);
        match self.msgs().remove(0) {
            Msg::Reply { body, .. } => body[24..].to_vec(),
            m => panic!("{m:?}"),
        }
    }
}

fn events(msgs: &[Msg]) -> Vec<[u8; 32]> {
    msgs.iter().filter_map(|m| if let Msg::Event(e) = m { Some(*e) } else { None }).collect()
}

fn codes(msgs: &[Msg]) -> Vec<u8> {
    events(msgs).iter().map(|e| e[0]).collect()
}

fn le32(b: &[u8], i: usize) -> u32 {
    u32::from_le_bytes(b[i..i + 4].try_into().unwrap())
}

fn le16(b: &[u8], i: usize) -> u16 {
    u16::from_le_bytes(b[i..i + 2].try_into().unwrap())
}

#[test]
fn setup_reply_is_consistent() {
    let mut s = new_server(1024, 768);
    let c = s.add_client().unwrap();
    s.feed(c, &[0x6c, 0, 11, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
    let out = s.take_output(c);
    assert_eq!(out[0], 1);
    let words = le16(&out, 6) as usize;
    assert_eq!(out.len(), 8 + words * 4);
    assert_eq!(le32(&out, 12), 1 << 22, "resource id base of the first client");
    assert_eq!(le32(&out, 16), (1 << 22) - 1);
    assert!(out.windows(24).any(|w| w == b"Elbrus Technologies, LLC"));
}

#[test]
fn handshake_waits_for_all_bytes_and_rejects_other_versions() {
    let mut s = new_server(800, 600);
    let c = s.add_client().unwrap();
    s.feed(c, &[0x6c, 0, 11, 0]);
    assert!(s.take_output(c).is_empty());
    s.feed(c, &[0, 0, 0, 0, 0, 0, 0, 0]);
    assert_eq!(s.take_output(c)[0], 1);
    let c2 = s.add_client().unwrap();
    s.feed(c2, &[0x6c, 0, 10, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
    assert!(s.notifications.contains(&Notify::Kill(c2)));
}

#[test]
fn big_endian_client() {
    let mut s = new_server(800, 600);
    let c = s.add_client().unwrap();
    s.feed(c, &[b'B', 0, 0, 11, 0, 0, 0, 0, 0, 0, 0, 0]);
    let out = s.take_output(c);
    assert_eq!(out[0], 1);
    // InternAtom("WM_NAME") big endian
    let name = b"WM_NAME\0";
    let mut req = vec![16u8, 0, 0, 4, 0, 7, 0, 0];
    req.extend_from_slice(name);
    s.feed(c, &req);
    let out = s.take_output(c);
    assert_eq!(out[0], 1);
    assert_eq!(u32::from_be_bytes(out[8..12].try_into().unwrap()), 39);
}

#[test]
fn requests_split_across_reads() {
    let mut t = T::new();
    let mut b = Vec::new();
    b.extend(5u16.to_le_bytes());
    b.extend([0, 0]);
    b.extend(b"WM_ID");
    b.extend([0, 0, 0]);
    let mut full = vec![16u8, 0];
    full.extend(((b.len() / 4 + 1) as u16).to_le_bytes());
    full.extend(&b);
    for byte in &full {
        t.s.feed(t.c, std::slice::from_ref(byte));
    }
    let m = t.msgs();
    assert_eq!(m.len(), 1);
    match &m[0] {
        Msg::Reply { seq, body, .. } => {
            assert_eq!(*seq, 1);
            assert!(le32(body, 0) > 68, "new atoms follow the predefined ones");
        }
        m => panic!("{m:?}"),
    }
}

#[test]
fn errors_carry_sequence_and_values() {
    let mut t = T::new();
    t.req(3, 0, &u32le(0x1234));
    let m = t.msgs();
    match &m[0] {
        Msg::Error(e) => {
            assert_eq!(e[1], 3, "BadWindow");
            assert_eq!(le16(e, 2), 1);
            assert_eq!(le32(e, 4), 0x1234);
            assert_eq!(e[10], 3, "major opcode");
        }
        m => panic!("{m:?}"),
    }
    t.req(17, 0, &u32le(5000));
    match &t.msgs()[0] {
        Msg::Error(e) => {
            assert_eq!(e[1], 5, "BadAtom");
            assert_eq!(le16(e, 2), 2);
        }
        m => panic!("{m:?}"),
    }
}

#[test]
fn create_map_destroy_events() {
    let mut t = T::new();
    let (w, root) = (t.id(1), t.s.root);
    t.select(root, ev::SUBSTRUCTURE_NOTIFY);
    t.create_window(w, root, 10, 20, 200, 100, 1 << 11, &[ev::STRUCTURE_NOTIFY | ev::EXPOSURE]);
    let m = t.msgs();
    assert_eq!(codes(&m), vec![16], "CreateNotify to the parent");
    t.map(w);
    let m = t.msgs();
    // MapNotify to the window, MapNotify to the parent, then Expose.
    assert_eq!(codes(&m), vec![19, 19, 12]);
    let e = events(&m);
    assert_eq!(le32(&e[0], 4), w);
    assert_eq!(le32(&e[2], 4), w);
    assert_eq!((le16(&e[2], 12), le16(&e[2], 14)), (200, 100));
    t.req(4, 0, &u32le(w));
    let m = t.msgs();
    // Unmap (2) then Destroy (2).
    assert_eq!(codes(&m), vec![18, 18, 17, 17]);
}

#[test]
fn get_window_attributes_shape() {
    let mut t = T::new();
    let (w, root) = (t.id(1), t.s.root);
    t.create_window(w, root, 0, 0, 50, 50, 1 << 11, &[ev::EXPOSURE]);
    t.msgs();
    t.req(3, 0, &u32le(w));
    match &t.msgs()[0] {
        Msg::Reply { body, .. } => {
            assert_eq!(body.len(), 24 + 12, "3 extra words");
            assert_eq!(le32(body, 0), 1, "visual id of the root visual");
            assert_eq!(le32(&body[0..], 0), 1);
            assert_eq!(le16(body, 4), 1, "InputOutput");
            assert_eq!(body[6], 5, "bit gravity CENTER by default");
            assert_eq!(body[7], 5, "win gravity CENTER by default");
        }
        m => panic!("{m:?}"),
    }
}

#[test]
fn map_request_redirect() {
    let mut t = T::new();
    let (c2, base2) = t.second();
    let root = t.s.root;
    // Client 1 acts as window manager on the root.
    t.select(root, ev::SUBSTRUCTURE_REDIRECT | ev::SUBSTRUCTURE_NOTIFY);
    t.msgs();
    let w = base2 + 1;
    // Client 2 creates and maps a window: the WM gets MapRequest, the window stays unmapped.
    t.create_window_as(c2, w, root, 0, 0, 10, 10, 0, &[]);
    t.req_as(c2, 8, 0, &u32le(w));
    let m = parse(&t.s.take_output(c2));
    assert!(m.is_empty(), "client 2 gets nothing: {m:?}");
    let m = t.msgs();
    assert_eq!(codes(&m), vec![16, 20], "CreateNotify, MapRequest");
    assert_eq!(t.s.map_state(w), 0);
    // The redirect applies to every client (also the WM itself, as in the Java server); once the
    // WM lets go of the root, a MapWindow goes through.
    t.select(root, ev::SUBSTRUCTURE_NOTIFY);
    t.map(w);
    assert_eq!(t.s.map_state(w), 2);
}

#[test]
fn properties_roundtrip_and_shapes() {
    let mut t = T::new();
    let (w, root) = (t.id(1), t.s.root);
    t.create_window(w, root, 0, 0, 10, 10, 1 << 11, &[ev::PROPERTY_CHANGE]);
    t.msgs();
    let name = t.intern("WM_NAME");
    let string = t.intern("STRING");
    t.change_property(w, 0, name, string, 8, b"hello world");
    let m = t.msgs();
    assert_eq!(codes(&m), vec![28]);
    // whole property
    match t.get_property(w, name, 0, 0, 100, false) {
        Msg::Reply { data, body, .. } => {
            assert_eq!(data, 8);
            assert_eq!(le32(&body, 0), string);
            assert_eq!(le32(&body, 4), 0, "bytes after");
            assert_eq!(le32(&body, 8), 11, "length in items");
            assert_eq!(&body[24..35], b"hello world");
        }
        m => panic!("{m:?}"),
    }
    // offset 1 word, length 1 word: bytes_after = 11 - 8 = 3
    match t.get_property(w, name, string, 1, 1, false) {
        Msg::Reply { body, .. } => {
            assert_eq!(le32(&body, 4), 3);
            assert_eq!(le32(&body, 8), 4);
            assert_eq!(&body[24..28], b"o wo");
        }
        m => panic!("{m:?}"),
    }
    // wrong type: returns type without data
    let cardinal = t.intern("CARDINAL");
    match t.get_property(w, name, cardinal, 0, 100, false) {
        Msg::Reply { data, body, .. } => {
            assert_eq!(data, 8);
            assert_eq!(le32(&body, 0), string);
            assert_eq!(le32(&body, 8), 0);
            assert_eq!(body.len(), 24, "no data");
        }
        m => panic!("{m:?}"),
    }
    // missing property
    let other = t.intern("OTHER");
    match t.get_property(w, other, 0, 0, 10, false) {
        Msg::Reply { data, body, .. } => {
            assert_eq!(data, 0);
            assert_eq!(le32(&body, 0), 0);
        }
        m => panic!("{m:?}"),
    }
    // offset beyond the end: BadValue
    match t.get_property(w, name, 0, 100, 1, false) {
        Msg::Error(e) => assert_eq!(e[1], 2),
        m => panic!("{m:?}"),
    }
    // append / prepend
    t.change_property(w, 2, name, string, 8, b"!!!!");
    t.change_property(w, 1, name, string, 8, b">>");
    t.msgs();
    match t.get_property(w, name, 0, 0, 100, false) {
        Msg::Reply { body, .. } => assert_eq!(&body[24..24 + 17], b">>hello world!!!!"),
        m => panic!("{m:?}"),
    }
    // append with a different format is BadMatch
    t.change_property(w, 2, name, string, 16, &[1, 2]);
    match &t.msgs().last().unwrap() {
        Msg::Error(e) => assert_eq!(e[1], 8),
        m => panic!("{m:?}"),
    }
    // delete + notify
    let mut b = Vec::new();
    for v in [w, name, 0, 0, 100] {
        b.extend(u32le(v));
    }
    t.req(20, 1, &b);
    let m = t.msgs();
    assert!(matches!(m[0], Msg::Reply { .. }));
    let e = events(&m);
    assert_eq!(e.len(), 1);
    assert_eq!(e[0][16], 1, "PropertyNotify state: deleted");
}

#[test]
fn replace_with_same_format_takes_the_new_type() {
    let mut t = T::new();
    let (w, root) = (t.id(1), t.s.root);
    t.create_window(w, root, 0, 0, 10, 10, 0, &[]);
    t.msgs();
    let a = t.intern("A");
    let (ty1, ty2) = (t.intern("T1"), t.intern("T2"));
    t.change_property(w, 0, a, ty1, 8, b"abcd");
    t.change_property(w, 0, a, ty2, 8, b"efgh");
    match t.get_property(w, a, 0, 0, 10, false) {
        Msg::Reply { body, .. } => {
            assert_eq!(le32(&body, 0), ty2);
            assert_eq!(&body[24..28], b"efgh");
        }
        m => panic!("{m:?}"),
    }
}

#[test]
fn root_has_xcursor_resources() {
    let mut t = T::new();
    let rm = t.intern("RESOURCE_MANAGER");
    let root = t.s.root;
    match t.get_property(root, rm, 0, 0, 100, false) {
        Msg::Reply { body, .. } => {
            let n = le32(&body, 8) as usize;
            assert_eq!(&body[24..24 + n], b"Xcursor.size:\t20\nXcursor.theme:\tdmz\nXcursor.theme_core:\ttrue\n");
        }
        m => panic!("{m:?}"),
    }
}

#[test]
fn draw_and_read_back() {
    let mut t = T::new();
    let (w, root, gc) = (t.id(1), t.s.root, t.id(2));
    t.create_window(w, root, 0, 0, 16, 16, 0, &[]);
    // foreground = red, background = green
    t.create_gc(gc, w, gfx::FLAG_FOREGROUND | gfx::FLAG_BACKGROUND, &[0xff0000, 0x00ff00]);
    // PutImage ZPixmap 2x2 depth 32
    let mut b = Vec::new();
    b.extend(u32le(w));
    b.extend(u32le(gc));
    b.extend(2u16.to_le_bytes());
    b.extend(2u16.to_le_bytes());
    b.extend(3i16.to_le_bytes());
    b.extend(4i16.to_le_bytes());
    b.extend([0, 32, 0, 0]);
    for px in [0x11223344u32, 0x55667788, 0x99aabbcc, 0xddeeff00] {
        b.extend(u32le(px));
    }
    t.req(72, 2, &b);
    assert!(t.msgs().is_empty());
    let img = t.get_image(w, 3, 4, 2, 2);
    let px: Vec<u32> = img.chunks(4).map(|c| u32::from_le_bytes(c.try_into().unwrap())).collect();
    assert_eq!(px, vec![0x11223344, 0x55667788, 0x99aabbcc, 0xddeeff00]);
    // PolyFillRectangle paints with the GC foreground (red here, the background is green)
    let mut b = Vec::new();
    b.extend(u32le(w));
    b.extend(u32le(gc));
    for v in [8i16, 8, 2, 2] {
        b.extend(v.to_le_bytes());
    }
    t.req(70, 0, &b);
    let img = t.get_image(w, 8, 8, 2, 2);
    assert!(img.chunks(4).all(|c| u32::from_le_bytes(c.try_into().unwrap()) & 0xffffff == 0xff0000));
    // CopyArea window -> pixmap -> back
    let pm = t.id(3);
    let mut b = Vec::new();
    b.extend(u32le(pm));
    b.extend(u32le(w));
    b.extend(4u16.to_le_bytes());
    b.extend(4u16.to_le_bytes());
    t.req(53, 32, &b);
    let mut b = Vec::new();
    for v in [w, pm, gc] {
        b.extend(u32le(v));
    }
    for v in [3i16, 4, 0, 0, 2, 2] {
        b.extend(v.to_le_bytes());
    }
    t.req(62, 0, &b);
    assert!(t.msgs().is_empty());
    let img = t.get_image(pm, 0, 0, 2, 2);
    assert_eq!(u32::from_le_bytes(img[0..4].try_into().unwrap()), 0x11223344);
    // GetGeometry on pixmap and window
    t.req(14, 0, &u32le(pm));
    match &t.msgs()[0] {
        Msg::Reply { data, body, .. } => {
            assert_eq!(*data, 32);
            assert_eq!((le16(body, 8), le16(body, 10)), (4, 4));
        }
        m => panic!("{m:?}"),
    }
}

#[test]
fn pixmap_of_a_depth_without_visual_still_has_a_geometry() {
    let mut t = T::new();
    let (root, pm) = (t.s.root, t.id(1));
    let mut b = Vec::new();
    b.extend(u32le(pm));
    b.extend(u32le(root));
    b.extend(4u16.to_le_bytes());
    b.extend(4u16.to_le_bytes());
    t.req(53, 24, &b); // depth 24 has no visual in this server
    assert!(t.msgs().is_empty());
    t.req(14, 0, &u32le(pm)); // GetGeometry needs no visual: it must be answered, not dropped
    match &t.msgs()[0] {
        Msg::Reply { data, body, .. } => {
            assert_eq!(*data, 24);
            assert_eq!((le16(body, 8), le16(body, 10)), (4, 4));
        }
        m => panic!("{m:?}"),
    }
}

#[test]
fn unknown_opcodes_are_ignored_and_fallbacks_answer() {
    let mut t = T::new();
    t.req(61, 0, &[0u8; 16]); // ClearArea: not implemented, no reply
    assert!(t.msgs().is_empty());
    // AllocColor: pixel for 8-bit-per-channel TrueColor
    let mut b = Vec::new();
    b.extend(u32le(0));
    for v in [0xffffu16, 0x8080, 0x0000, 0] {
        b.extend(v.to_le_bytes());
    }
    t.req(84, 0, &b);
    match &t.msgs()[0] {
        Msg::Reply { body, .. } => assert_eq!(le32(body, 8), 0xff8000),
        m => panic!("{m:?}"),
    }
    t.req(99, 0, &[]); // ListExtensions
    match &t.msgs()[0] {
        Msg::Reply { data, body, .. } => {
            assert_eq!(*data as usize, 9);
            let mut names = Vec::new();
            let mut i = 24;
            for _ in 0..*data {
                let n = body[i] as usize;
                names.push(String::from_utf8_lossy(&body[i + 1..i + 1 + n]).to_string());
                i += 1 + n;
            }
            for want in ["BIG-REQUESTS", "RANDR", "SYNC", "MIT-SHM", "Composite", "XInputExtension", "DRI3", "Present", "GLX"] {
                assert!(names.iter().any(|n| n == want), "{want} missing from {names:?}");
            }
        }
        m => panic!("{m:?}"),
    }
}

#[test]
fn query_extension_and_big_requests() {
    let mut t = T::new();
    let mut b = Vec::new();
    b.extend(12u16.to_le_bytes());
    b.extend([0, 0]);
    b.extend(b"BIG-REQUESTS");
    t.req(98, 0, &b);
    let major = match &t.msgs()[0] {
        Msg::Reply { body, .. } => {
            assert_eq!(body[0], 1);
            body[1]
        }
        m => panic!("{m:?}"),
    };
    assert_eq!(major, 156);
    t.req(major, 0, &[]);
    match &t.msgs()[0] {
        Msg::Reply { body, .. } => assert_eq!(le32(body, 0), 4194303),
        m => panic!("{m:?}"),
    }
    // A PutImage sent as a big request (length field 0, 32-bit length at +4).
    let (w, root, gc) = (t.id(1), t.s.root, t.id(2));
    t.create_window(w, root, 0, 0, 8, 8, 0, &[]);
    t.create_gc(gc, w, 0, &[]);
    let mut body = Vec::new();
    for v in [w, gc] {
        body.extend(u32le(v));
    }
    body.extend(8u16.to_le_bytes());
    body.extend(8u16.to_le_bytes());
    body.extend([0u8; 4]);
    body.extend([0, 32, 0, 0]);
    body.extend(std::iter::repeat(0x7f).take(8 * 8 * 4));
    let total_words = (body.len() / 4 + 2) as u32;
    let mut v = vec![72u8, 2, 0, 0];
    v.extend(u32le(total_words));
    v.extend(&body);
    t.s.feed(t.c, &v);
    assert!(t.msgs().is_empty());
    let img = t.get_image(w, 0, 0, 8, 8);
    assert!(img.iter().all(|b| *b == 0x7f));
}

#[test]
fn selection_clear_goes_to_previous_owner() {
    let mut t = T::new();
    let (c2, base2) = t.second();
    let root = t.s.root;
    let (w1, w2) = (t.id(1), base2 + 1);
    t.create_window(w1, root, 0, 0, 10, 10, 0, &[]);
    let sel = t.intern("PRIMARY");
    let mut b = Vec::new();
    for v in [w1, sel, 5] {
        b.extend(u32le(v));
    }
    t.req(22, 0, &b);
    assert!(t.msgs().is_empty());
    t.create_window_as(c2, w2, root, 0, 0, 10, 10, 0, &[]);
    let mut b = Vec::new();
    for v in [w2, sel, 9] {
        b.extend(u32le(v));
    }
    t.req_as(c2, 22, 0, &b);
    let m = t.msgs();
    assert_eq!(codes(&m), vec![29]);
    let e = events(&m);
    assert_eq!(le32(&e[0], 4), 9, "timestamp");
    assert_eq!(le32(&e[0], 12), sel);
    t.req_as(c2, 23, 0, &u32le(sel));
    match &parse(&t.s.take_output(c2))[0] {
        Msg::Reply { body, .. } => assert_eq!(le32(body, 0), w2),
        m => panic!("{m:?}"),
    }
}

#[test]
fn disconnect_destroys_windows_and_notifies_others() {
    let mut t = T::new();
    let (c2, base2) = t.second();
    let root = t.s.root;
    let w2 = base2 + 1;
    t.create_window_as(c2, w2, root, 0, 0, 10, 10, 0, &[]);
    t.select(w2, ev::STRUCTURE_NOTIFY);
    t.msgs();
    t.s.client_disconnected(c2);
    assert!(!t.s.windows.contains_key(&w2));
    assert!(t.s.clients[c2].is_none());
    // the id range is reusable
    let c3 = t.s.add_client().unwrap();
    assert_eq!(t.s.client(c3).unwrap().resource_base, base2);
    // the survivor was told about the destruction
    assert_eq!(codes(&t.msgs()), vec![17]);
}

#[test]
fn input_events_reach_the_window_under_the_pointer() {
    let mut t = T::new();
    let (w, root) = (t.id(1), t.s.root);
    t.create_window(w, root, 100, 100, 200, 200, 1 << 11, &[ev::POINTER_MOTION | ev::BUTTON_PRESS | ev::BUTTON_RELEASE | ev::KEY_PRESS | ev::KEY_RELEASE | ev::ENTER_WINDOW | ev::LEAVE_WINDOW]);
    t.map(w);
    t.msgs();
    t.s.set_pointer_position(150, 170);
    let m = t.msgs();
    let e = events(&m);
    assert_eq!(e.len(), 1);
    assert_eq!(e[0][0], 6, "MotionNotify");
    assert_eq!((le16(&e[0], 20) as i16, le16(&e[0], 22) as i16), (150, 170), "root x,y");
    assert_eq!((le16(&e[0], 24) as i16, le16(&e[0], 26) as i16), (50, 70), "event x,y");
    assert_eq!(le32(&e[0], 12), w, "event window");
    // button press / release
    t.s.set_pointer_button(1, true);
    let m = t.msgs();
    let e = events(&m);
    assert_eq!(e[0][0], 4);
    assert_eq!(e[0][1], 1, "button 1");
    t.s.set_pointer_button(1, false);
    let m = t.msgs();
    assert_eq!(codes(&m), vec![5]);
    // keys go to the focus window
    t.req(42, 2, &[&u32le(w)[..], &[0u8; 4]].concat());
    t.s.key_press(38, 0);
    t.s.key_release(38);
    let m = t.msgs();
    assert_eq!(codes(&m), vec![2, 3]);
    assert_eq!(events(&m)[0][1], 38);
}

#[test]
fn motion_events_collapse() {
    let mut t = T::new();
    let (w, root) = (t.id(1), t.s.root);
    t.create_window(w, root, 0, 0, 500, 500, 1 << 11, &[ev::POINTER_MOTION]);
    t.map(w);
    t.msgs();
    for i in 0..10 {
        t.s.set_pointer_position(10 + i, 10 + i);
    }
    let m = t.msgs();
    let e = events(&m);
    assert_eq!(e.len(), 1, "only the last queued MotionNotify survives");
    assert_eq!(le16(&e[0], 20), 19);
}

#[test]
fn pointer_grab_and_auto_grab() {
    let mut t = T::new();
    let (w, root) = (t.id(1), t.s.root);
    t.create_window(w, root, 0, 0, 500, 500, 1 << 11, &[ev::BUTTON_PRESS | ev::BUTTON_RELEASE | ev::POINTER_MOTION]);
    t.map(w);
    t.msgs();
    t.s.set_pointer_position(5, 5);
    t.msgs();
    t.s.set_pointer_button(1, true);
    assert!(t.s.input.grab.is_some(), "a button press activates an implicit grab");
    t.msgs();
    t.s.set_pointer_button(1, false);
    assert!(t.s.input.grab.is_none(), "released with the last button");
}

#[test]
fn relative_mouse_forwards_buttons() {
    let mut t = T::new();
    t.s.input.relative_mouse = true;
    t.s.notifications.clear();
    t.s.set_pointer_button(4, true);
    assert!(t.s.notifications.contains(&Notify::RelativeMouseButton { button: 4, pressed: true, wheel_delta: 120 }));
    t.s.set_pointer_button(5, true);
    assert!(t.s.notifications.contains(&Notify::RelativeMouseButton { button: 5, pressed: true, wheel_delta: -120 }));
}

#[test]
fn unmap_reverts_focus_and_ends_grab() {
    let mut t = T::new();
    let (w, root) = (t.id(1), t.s.root);
    t.create_window(w, root, 0, 0, 50, 50, 0, &[]);
    t.map(w);
    t.req(42, 2, &[&u32le(w)[..], &[0u8; 4]].concat()); // focus w, revert to parent
    assert_eq!(t.s.focus, Some(w));
    t.req(10, 0, &u32le(w));
    assert_eq!(t.s.focus, Some(root), "revert to the parent");
    t.req(43, 0, &[]);
    match &t.msgs()[0] {
        Msg::Reply { data, body, .. } => {
            assert_eq!(*data, 2, "revert-to PARENT");
            assert_eq!(le32(body, 0), root);
        }
        m => panic!("{m:?}"),
    }
}

#[test]
fn net_wm_state_fullscreen_resizes_to_screen() {
    let mut t = T::new();
    let (w, root) = (t.id(1), t.s.root);
    t.create_window(w, root, 5, 5, 100, 100, 1 << 11, &[ev::STRUCTURE_NOTIFY]);
    t.map(w);
    t.msgs();
    let state = t.intern("_NET_WM_STATE");
    let fs = t.intern("_NET_WM_STATE_FULLSCREEN");
    // ClientMessage format 32 to the root: window w, message type _NET_WM_STATE, [add, fs]
    let mut ev = [0u8; 32];
    ev[0] = 33;
    ev[1] = 32;
    ev[4..8].copy_from_slice(&w.to_le_bytes());
    ev[8..12].copy_from_slice(&state.to_le_bytes());
    ev[12..16].copy_from_slice(&1u32.to_le_bytes());
    ev[16..20].copy_from_slice(&fs.to_le_bytes());
    let mut b = Vec::new();
    b.extend(u32le(root));
    b.extend(u32le(ev::SUBSTRUCTURE_NOTIFY | ev::SUBSTRUCTURE_REDIRECT));
    b.extend(ev);
    t.req(25, 0, &b);
    let win = &t.s.windows[&w];
    assert_eq!((win.x, win.y, win.width, win.height), (0, 0, 1024, 768));
    let m = t.msgs();
    assert!(codes(&m).contains(&22), "ConfigureNotify: {m:?}");
}

#[test]
fn send_event_is_forwarded_with_the_send_event_flag() {
    let mut t = T::new();
    let (w, root) = (t.id(1), t.s.root);
    t.create_window(w, root, 0, 0, 10, 10, 0, &[]);
    let mut ev = [0u8; 32];
    ev[0] = 33;
    ev[1] = 8;
    ev[2] = 0xaa;
    ev[3] = 0xbb;
    ev[4..8].copy_from_slice(&w.to_le_bytes());
    let mut b = Vec::new();
    b.extend(u32le(w));
    b.extend(u32le(0)); // empty mask: to the creator
    b.extend(ev);
    t.req(25, 0, &b);
    let m = t.msgs();
    let got = events(&m)[0];
    assert_eq!(got[0], 33 | 0x80, "the send-event flag");
    assert_eq!(got[1], 8);
    assert_eq!(&got[4..], &ev[4..], "payload untouched");
    // sequence number: the recipient's last request (the SendEvent itself)
    assert_eq!(le16(&got, 2), 2, "sequence of the recipient");
}

#[test]
fn configure_resize_recreates_content_and_sends_events() {
    let mut t = T::new();
    let (w, root) = (t.id(1), t.s.root);
    t.create_window(w, root, 0, 0, 20, 20, 1 << 11, &[ev::STRUCTURE_NOTIFY | ev::EXPOSURE]);
    t.map(w);
    t.msgs();
    let mut b = Vec::new();
    b.extend(u32le(w));
    b.extend((0x4u16 | 0x8).to_le_bytes());
    b.extend([0, 0]);
    b.extend(u32le(64));
    b.extend(u32le(48));
    t.req(12, 0, &b);
    let m = t.msgs();
    assert_eq!(codes(&m), vec![12, 22], "Expose from the geometry change, then ConfigureNotify");
    t.req(14, 0, &u32le(w));
    match &t.msgs()[0] {
        Msg::Reply { body, .. } => assert_eq!((le16(body, 8), le16(body, 10)), (64, 48)),
        m => panic!("{m:?}"),
    }
}

#[test]
fn query_tree_lists_bottom_first() {
    let mut t = T::new();
    let root = t.s.root;
    let (a, b2) = (t.id(1), t.id(2));
    t.create_window(a, root, 0, 0, 10, 10, 0, &[]);
    t.create_window(b2, root, 0, 0, 10, 10, 0, &[]);
    t.req(15, 0, &u32le(root));
    match &t.msgs()[0] {
        Msg::Reply { body, .. } => {
            assert_eq!(le16(body, 8), 2);
            assert_eq!(le32(body, 24), a, "bottom-most first, as the protocol says");
            assert_eq!(le32(body, 28), b2);
        }
        m => panic!("{m:?}"),
    }
}

#[test]
fn keyboard_and_misc_replies() {
    let mut t = T::new();
    // GetKeyboardMapping for keycodes 38..40 (A, S, D)
    t.req(101, 0, &[38, 3, 0, 0]);
    match &t.msgs()[0] {
        Msg::Reply { data, body, .. } => {
            assert_eq!(*data, 2);
            assert_eq!(body.len(), 24 + 3 * 8, "3 keycodes x 2 keysyms");
            assert_eq!(le32(body, 24), 97, "a");
            assert_eq!(le32(body, 28), 65, "A");
        }
        m => panic!("{m:?}"),
    }
    t.req(117, 0, &[]);
    match &t.msgs()[0] {
        Msg::Reply { data, body, .. } => {
            assert_eq!(*data, 7);
            assert_eq!(&body[24..31], &[1, 2, 3, 4, 5, 6, 7]);
        }
        m => panic!("{m:?}"),
    }
    t.req(119, 0, &[]);
    assert!(matches!(&t.msgs()[0], Msg::Reply { data: 1, .. }));
    t.req(108, 0, &[]);
    match &t.msgs()[0] {
        Msg::Reply { body, .. } => assert_eq!(le16(body, 0), 600),
        m => panic!("{m:?}"),
    }
}

#[test]
fn set_dashes_is_not_copy_gc() {
    // Opcode 58 is SetDashes; the Java table had it as CopyGC and answered with BadGC.
    let mut t = T::new();
    t.req(58, 0, &[0u8; 12]);
    assert!(t.msgs().is_empty());
}

#[test]
fn cursor_create_and_window_cursor() {
    let mut t = T::new();
    let (root, src, mask, cur, w) = (t.s.root, t.id(1), t.id(2), t.id(3), t.id(4));
    for pm in [src, mask] {
        let mut b = Vec::new();
        b.extend(u32le(pm));
        b.extend(u32le(root));
        b.extend(8u16.to_le_bytes());
        b.extend(8u16.to_le_bytes());
        t.req(53, 32, &b);
    }
    // depth 32 pixmaps: the mask must have depth 1 -> BadMatch
    let mut b = Vec::new();
    for v in [cur, src, mask] {
        b.extend(u32le(v));
    }
    for v in [0u16; 6] {
        b.extend(v.to_le_bytes());
    }
    b.extend([0u8; 4]);
    t.req(93, 0, &b);
    match &t.msgs()[0] {
        Msg::Error(e) => assert_eq!(e[1], 8),
        m => panic!("{m:?}"),
    }
    // no mask pixmap: the cursor is created and visible
    let mut b = Vec::new();
    for v in [cur, src, 0] {
        b.extend(u32le(v));
    }
    for v in [0xffffu16, 0xffff, 0xffff, 0, 0, 0] {
        b.extend(v.to_le_bytes());
    }
    b.extend([1, 0, 2, 0]);
    t.req(93, 0, &b);
    assert!(t.msgs().is_empty());
    assert!(t.s.cursors[&cur].visible);
    t.create_window(w, root, 0, 0, 10, 10, 1 << 14, &[cur]);
    assert_eq!(t.s.effective_cursor(w), Some(cur));
    // FreeCursor: the window keeps showing it
    t.req(95, 0, &u32le(cur));
    assert_eq!(t.s.effective_cursor(w), Some(cur));
    t.req(4, 0, &u32le(w));
    assert!(!t.s.cursors.contains_key(&cur), "released once nothing uses it");
}

#[test]
fn grab_server_and_misc_no_reply_requests() {
    let mut t = T::new();
    for (op, len) in [(36u8, 0usize), (37, 0), (127, 0), (104, 0), (115, 0), (107, 8), (59, 8), (66, 8), (67, 8), (78, 12), (79, 4), (94, 24)] {
        t.req(op, 0, &vec![0u8; len]);
    }
    assert!(t.msgs().is_empty());
}

#[test]
fn reparent_sends_three_events() {
    let mut t = T::new();
    let root = t.s.root;
    let (a, b2, c) = (t.id(1), t.id(2), t.id(3));
    t.create_window(a, root, 0, 0, 100, 100, 1 << 11, &[ev::SUBSTRUCTURE_NOTIFY]);
    t.create_window(b2, root, 0, 0, 100, 100, 1 << 11, &[ev::SUBSTRUCTURE_NOTIFY]);
    t.create_window(c, a, 0, 0, 10, 10, 1 << 11, &[ev::STRUCTURE_NOTIFY]);
    t.msgs();
    let mut b = Vec::new();
    b.extend(u32le(c));
    b.extend(u32le(b2));
    b.extend(7i16.to_le_bytes());
    b.extend(9i16.to_le_bytes());
    t.req(7, 0, &b);
    let m = t.msgs();
    // StructureNotify on the window, SubstructureNotify on the old and the new parent
    assert_eq!(codes(&m), vec![21, 21, 21]);
    assert_eq!(t.s.windows[&c].parent, Some(b2));
    assert_eq!((t.s.windows[&c].x, t.s.windows[&c].y), (7, 9));
}

/// Tiny deterministic PRNG (xorshift) so the fuzz test is reproducible without a dependency.
struct Rng(u64);
impl Rng {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        self.0
    }
    fn below(&mut self, n: u64) -> u64 {
        self.next() % n.max(1)
    }
}

/// Random but well-framed requests for every opcode, with ids drawn from a small pool so that
/// requests often hit existing resources. The server must never panic, whatever the bodies say.
#[test]
fn fuzz_random_requests_never_panic() {
    let seeds: u64 = std::env::var("XCORE_FUZZ_SEEDS").ok().and_then(|v| v.parse().ok()).unwrap_or(40);
    for seed in 1..=seeds {
        let mut rng = Rng(seed.wrapping_mul(0x9e3779b97f4a7c15));
        let mut t = T::new();
        let (c2, base2) = t.second();
        let root = t.s.root;
        let pool: Vec<u32> = (1..12).map(|i| t.base + i).chain((1..6).map(|i| base2 + i)).chain([root, 1, 2, 0]).collect();
        for _ in 0..3000 {
            let opcode = match rng.below(10) {
                0..=3 => [1u8, 2, 3, 4, 5, 7, 8, 9, 10, 12, 14, 15][rng.below(12) as usize],
                4..=6 => [16u8, 17, 18, 19, 20, 22, 23, 25, 26, 27, 38, 40, 41, 42, 43, 53, 54, 55, 56, 57, 60, 62, 65, 70, 72, 73, 93, 95, 98, 101][rng.below(30) as usize],
                _ => rng.below(256) as u8,
            };
            let words = rng.below(20) as usize;
            let mut body = vec![0u8; words * 4];
            for chunk in body.chunks_mut(4) {
                let v = if rng.below(3) == 0 { pool[rng.below(pool.len() as u64) as usize] } else { rng.next() as u32 };
                chunk.copy_from_slice(&v.to_le_bytes());
            }
            let who = if rng.below(4) == 0 { c2 } else { t.c };
            t.req_as(who, opcode, rng.below(8) as u8, &body);
            // Occasionally drive input and client churn as well.
            match rng.below(40) {
                0 => t.s.set_pointer_position(rng.below(1200) as i32 - 50, rng.below(900) as i32 - 50),
                1 => t.s.set_pointer_button(1 + rng.below(7) as u8, rng.below(2) == 0),
                2 => {
                    let k = rng.below(256) as u8;
                    t.s.key_press(k, rng.below(3) as u32);
                    t.s.key_release(k);
                }
                _ => {}
            }
            t.s.notifications.clear();
            t.s.take_output(t.c);
            t.s.take_output(c2);
        }
        t.s.client_disconnected(c2);
        t.s.client_disconnected(t.c);
    }
}

#[test]
fn garbage_after_handshake_does_not_panic() {
    let mut rng = Rng(0xdeadbeef);
    for _ in 0..50 {
        let mut s = new_server(640, 480);
        let c = s.add_client().unwrap();
        s.feed(c, &[0x6c, 0, 11, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
        s.take_output(c);
        for _ in 0..200 {
            let n = rng.below(64) as usize;
            let junk: Vec<u8> = (0..n).map(|_| rng.next() as u8).collect();
            s.feed(c, &junk);
            s.notifications.clear();
            s.take_output(c);
        }
    }
}

// ====================================================================== extensions (Phase 3)

mod ext_tests {
    use super::*;
    use crate::proto::gfx::{HostPixels, PixelStore};
    use crate::proto::platform::{Platform, ShmMap};
    use std::collections::HashMap;
    use std::sync::{Arc, Mutex};

    #[derive(Default)]
    struct Tp {
        fps: i32,
        now: i64,
        shm: HashMap<i32, Box<[u8]>>,
        attached: Vec<i32>,
        detached: Vec<i32>,
    }

    struct Platf(Arc<Mutex<Tp>>);

    impl Platform for Platf {
        fn now_ns(&mut self) -> i64 {
            self.0.lock().unwrap().now
        }
        fn fps_limit(&mut self) -> i32 {
            self.0.lock().unwrap().fps
        }
        fn shm_attach(&mut self, shmid: i32) -> Option<ShmMap> {
            let mut g = self.0.lock().unwrap();
            let map = g.shm.get_mut(&shmid).map(|b| ShmMap { ptr: b.as_mut_ptr(), len: b.len() })?;
            g.attached.push(shmid);
            Some(map)
        }
        fn shm_detach(&mut self, shmid: i32) {
            self.0.lock().unwrap().detached.push(shmid);
        }
        fn import_buffer(&mut self, _fd: i32) -> Option<Box<dyn PixelStore>> {
            Some(Box::new(HostPixels::new(16, 16, false)))
        }
    }

    fn setup() -> (T, Arc<Mutex<Tp>>) {
        let mut t = T::new();
        let st = Arc::new(Mutex::new(Tp { now: 1_000_000_000, ..Default::default() }));
        t.s.platform = Box::new(Platf(st.clone()));
        (t, st)
    }

    /// Splits output into wire messages (replies and generic events carry extra words).
    fn split(out: &[u8]) -> Vec<Vec<u8>> {
        let mut v = Vec::new();
        let mut i = 0;
        while i < out.len() {
            let code = out[i] & 0x7f;
            let extra = if code == 1 || code == 35 { le32(out, i + 4) as usize * 4 } else { 0 };
            v.push(out[i..i + 32 + extra].to_vec());
            i += 32 + extra;
        }
        v
    }

    fn query(t: &mut T, name: &str) -> (u8, u8, u8) {
        let mut b = Vec::new();
        b.extend((name.len() as u16).to_le_bytes());
        b.extend([0, 0]);
        b.extend(name.as_bytes());
        while b.len() % 4 != 0 {
            b.push(0);
        }
        t.req(98, 0, &b);
        match &t.msgs()[0] {
            Msg::Reply { body, .. } => {
                assert_eq!(body[0], 1, "{name} not present");
                (body[1], body[2], body[3])
            }
            m => panic!("{m:?}"),
        }
    }

    fn pairs(v: &[u32]) -> Vec<u8> {
        v.iter().flat_map(|x| x.to_le_bytes()).collect()
    }

    #[test]
    fn extension_numbers() {
        let (mut t, _) = setup();
        assert_eq!(query(&mut t, "RANDR"), (150, 65, 0));
        assert_eq!(query(&mut t, "SYNC"), (152, 0, 160));
        assert_eq!(query(&mut t, "MIT-SHM"), (155, 64, 128));
        assert_eq!(query(&mut t, "Composite").0, 148);
        assert_eq!(query(&mut t, "XInputExtension"), (146, 67, 150));
        assert_eq!(query(&mut t, "DRI3").0, 154);
        assert_eq!(query(&mut t, "Present").0, 153);
        assert_eq!(query(&mut t, "GLX"), (151, 0, 131));
    }

    #[test]
    fn version_replies() {
        let (mut t, _) = setup();
        for (major, minor, req, expect) in [
            (150u8, 0u8, pairs(&[1, 1]), (1u32, 1u32)),
            (151, 7, pairs(&[1, 4]), (1, 4)),
            (148, 0, pairs(&[0, 4]), (0, 1)),
            (154, 0, pairs(&[1, 2]), (1, 2)),
            (153, 0, pairs(&[1, 2]), (1, 2)),
        ] {
            t.req(major, minor, &req);
            match &t.msgs()[0] {
                Msg::Reply { body, .. } => assert_eq!((le32(body, 0), le32(body, 4)), expect, "ext {major}"),
                m => panic!("{m:?}"),
            }
        }
        // MIT-SHM QueryVersion: a whole 32-byte reply (the Java one was 17 bytes)
        t.req(155, 0, &[]);
        assert_eq!(t.out().len(), 32);
        // XIQueryVersion clamps to 2.2
        t.req(146, 47, &[2, 0, 9, 0]);
        match &t.msgs()[0] {
            Msg::Reply { body, .. } => assert_eq!((le16(body, 0), le16(body, 2)), (2, 2)),
            m => panic!("{m:?}"),
        }
    }

    #[test]
    fn sync_fences_block_and_wake() {
        let (mut t, _) = setup();
        let (c2, _) = t.second();
        let (w, fence) = (t.id(1), t.id(2));
        let root = t.s.root;
        t.create_window(w, root, 0, 0, 8, 8, 0, &[]);
        t.req(152, 14, &[pairs(&[w, fence]), vec![0, 0, 0, 0]].concat()); // CreateFence, not triggered
        assert!(t.msgs().is_empty());
        t.req(152, 19, &pairs(&[fence])); // AwaitFence: the client is on hold now
        t.req(43, 0, &[]); // GetInputFocus
        assert!(t.out().is_empty(), "request ran while waiting for the fence");
        t.req_as(c2, 152, 15, &pairs(&[fence])); // another client triggers it
        let out = t.out();
        assert_eq!(out.len(), 32, "GetInputFocus must be answered after the wake");
        // a triggered fence does not block
        t.req(152, 19, &pairs(&[fence]));
        t.req(43, 0, &[]);
        assert_eq!(t.out().len(), 32);
        // ResetFence, then a second reset is a BadMatch; unknown fences are BadFence
        t.req(152, 16, &pairs(&[fence]));
        assert!(t.msgs().is_empty());
        t.req(152, 16, &pairs(&[fence]));
        match &t.msgs()[0] {
            Msg::Error(e) => assert_eq!(e[1], 8),
            m => panic!("{m:?}"),
        }
        t.req(152, 15, &pairs(&[t.id(9)]));
        match &t.msgs()[0] {
            Msg::Error(e) => assert_eq!(e[1], 162),
            m => panic!("{m:?}"),
        }
        // an empty wait list does not hang the client (the Java server hung for ever)
        t.req(152, 19, &[]);
        t.req(43, 0, &[]);
        assert_eq!(t.out().len(), 32);
    }

    #[test]
    fn sync_fence_destroyed_while_waiting_is_an_error() {
        let (mut t, _) = setup();
        let (c2, _) = t.second();
        let (w, fence) = (t.id(1), t.id(2));
        let root = t.s.root;
        t.create_window(w, root, 0, 0, 8, 8, 0, &[]);
        t.req(152, 14, &[pairs(&[w, fence]), vec![0, 0, 0, 0]].concat());
        t.req(152, 19, &pairs(&[fence]));
        assert!(t.out().is_empty());
        t.req_as(c2, 4, 0, &u32le(w)); // another client destroys the fence's window
        let m = t.msgs();
        assert!(matches!(&m[0], Msg::Error(e) if e[1] == 162), "{m:?}");
    }

    #[test]
    fn mitshm_attach_put_image_detach() {
        let (mut t, st) = setup();
        let mut data = vec![0u8; 8 * 8 * 4];
        for (i, px) in data.chunks_mut(4).enumerate() {
            px.copy_from_slice(&[i as u8, 0x20, 0x30, 0xff]);
        }
        st.lock().unwrap().shm.insert(7, data.clone().into_boxed_slice());
        let (w, gc, seg) = (t.id(1), t.id(2), t.id(3));
        let root = t.s.root;
        t.create_window(w, root, 0, 0, 8, 8, 0, &[]);
        t.create_gc(gc, w, 0, &[]);
        t.req(155, 1, &[pairs(&[seg, 7]), vec![0, 0, 0, 0]].concat()); // Attach
        assert_eq!(st.lock().unwrap().attached, vec![7]);
        let mut b = pairs(&[w, gc]);
        for v in [8i16, 8, 0, 0, 8, 8, 0, 0] {
            b.extend(v.to_le_bytes());
        }
        b.extend([24, 2, 0, 0]); // depth, format ZPixmap, send_event
        b.extend(pairs(&[seg, 0]));
        t.req(155, 3, &b);
        assert!(t.msgs().is_empty());
        let img = t.s.drawables[&w].get_image(0, 0, 8, 8).unwrap();
        assert_eq!(&img[..4], &data[..4]);
        assert_eq!(&img[4 * 9..4 * 9 + 4], &data[4 * 9..4 * 9 + 4]);
        // an unknown segment is BadShmSeg (128)
        let mut b2 = b.clone();
        let n = b2.len();
        b2[n - 8..n - 4].copy_from_slice(&u32le(t.id(99)));
        t.req(155, 3, &b2);
        assert!(matches!(&t.msgs()[0], Msg::Error(e) if e[1] == 128));
        t.req(155, 2, &u32le(seg)); // Detach
        assert_eq!(st.lock().unwrap().detached, vec![7]);
    }

    #[test]
    fn mitshm_segments_end_with_their_client() {
        let (mut t, st) = setup();
        st.lock().unwrap().shm.insert(3, vec![0u8; 64].into_boxed_slice());
        let (c2, base2) = t.second();
        t.req_as(c2, 155, 1, &[pairs(&[base2 + 1, 3]), vec![0, 0, 0, 0]].concat());
        assert_eq!(st.lock().unwrap().attached, vec![3]);
        t.s.client_disconnected(c2);
        assert_eq!(st.lock().unwrap().detached, vec![3], "Xlib never sends ShmDetach on exit");
    }

    #[test]
    fn composite_redirect_follows_client() {
        let (mut t, _) = setup();
        let (c2, base2) = t.second();
        let root = t.s.root;
        let w = base2 + 1;
        t.create_window_as(c2, w, root, 0, 0, 8, 8, 0, &[]);
        t.s.notifications.clear();
        t.req_as(c2, 148, 1, &[u32le(w).to_vec(), vec![0, 0, 0, 0]].concat());
        assert!(t.s.windows[&w].composite_redirected);
        assert!(t.s.notifications.contains(&Notify::CompositeRedirect { window: w, redirected: true }));
        t.s.client_disconnected(c2);
        assert!(t.s.windows.get(&w).map_or(true, |x| !x.composite_redirected));
    }

    #[test]
    fn xinput_raw_events_reach_root_selections_only() {
        let (mut t, _) = setup();
        let (c2, _) = t.second();
        let root = t.s.root;
        // XISelectEvents(root, XIAllMasterDevices: raw button press/release + raw motion)
        let mask: u32 = (1 << 15) | (1 << 16) | (1 << 17);
        let mut b = u32le(root).to_vec();
        b.extend([1, 0, 0, 0]); // 1 mask
        b.extend([1, 0, 1, 0]); // device 1, 1 word
        b.extend(u32le(mask));
        t.req(146, 46, &b);
        assert!(t.msgs().is_empty());

        t.s.raw_motion(3.0, -2.0);
        let ev = split(&t.out());
        assert_eq!(ev.len(), 1);
        let e = &ev[0];
        assert_eq!((e[0], e[1]), (35, 146));
        assert_eq!(le32(e, 4), 9);
        assert_eq!(le16(e, 8), 17);
        assert_eq!(le16(e, 10), 2, "device: virtual core pointer");
        assert_eq!(le16(e, 20), 4, "source: the slave pointer");
        assert_eq!(le16(e, 22), 1, "valuator mask words");
        assert_eq!(le32(e, 32), 0b11);
        assert_eq!((le32(e, 36) as i32, le32(e, 40)), (3, 0));
        assert_eq!((le32(e, 44) as i32, le32(e, 48)), (-2, 0));
        assert_eq!(e.len(), 32 + 36);
        assert!(t.s.take_output(c2).is_empty());

        t.s.set_pointer_button(1, true);
        let ev = split(&t.out());
        let raw: Vec<_> = ev.iter().filter(|e| e[0] == 35).collect();
        assert_eq!(raw.len(), 1);
        assert_eq!((le16(raw[0], 8), le32(raw[0], 16), raw[0].len()), (15, 1, 32));

        // relative mouse mode: the game gets relative events from the core, no raw ones
        t.s.input.relative_mouse = true;
        t.s.raw_motion(1.0, 1.0);
        assert!(t.out().is_empty());
    }

    #[test]
    fn xinput_query_device_is_consistent() {
        let (mut t, _) = setup();
        t.req(146, 48, &[0, 0, 0, 0]); // XIAllDevices
        let out = t.out();
        assert_eq!(out[0], 1);
        let words = le32(&out, 4) as usize;
        assert_eq!(out.len(), 32 + words * 4);
        assert_eq!(le16(&out, 8), 3, "three devices");
        // walk the device infos and land exactly on the end
        let mut i = 32;
        for _ in 0..3 {
            let (nclasses, name_len) = (le16(&out, i + 6) as usize, le16(&out, i + 8) as usize);
            i += 12 + ((name_len + 3) & !3);
            for _ in 0..nclasses {
                i += le16(&out, i + 2) as usize * 4;
            }
        }
        assert_eq!(i, out.len());
        t.req(146, 48, &[9, 0, 0, 0]); // no such device
        assert!(matches!(&t.msgs()[0], Msg::Error(e) if e[1] == 150));
        t.req(146, 1, &[]); // GetExtensionVersion
        assert_eq!(t.out().len(), 32);
        t.req(146, 30, &[0, 0, 0, 0]); // a reply-less unknown request must not hang: BadRequest
        assert!(matches!(&t.msgs()[0], Msg::Error(e) if e[1] == 1));
    }

    fn pipe_fd() -> i32 {
        let mut fds = [0i32; 2];
        assert_eq!(unsafe { libc::pipe(fds.as_mut_ptr()) }, 0);
        unsafe { libc::close(fds[1]) };
        fds[0]
    }

    fn from_buffer(t: &mut T, pixmap: u32, window: u32) {
        let mut b = pairs(&[pixmap, window, 16 * 16 * 4]);
        b.extend(16u16.to_le_bytes());
        b.extend(16u16.to_le_bytes());
        b.extend(64u16.to_le_bytes());
        b.extend([24, 32]);
        let words = (b.len() / 4 + 1) as u16;
        let mut v = vec![154, 2];
        v.extend(words.to_le_bytes());
        v.extend(b);
        let c = t.c;
        t.s.feed_fds(c, &v, vec![pipe_fd()]);
    }

    fn present(t: &mut T, window: u32, pixmap: u32, serial: u32, idle_fence: u32) {
        let mut b = pairs(&[window, pixmap, serial, 0, 0]);
        b.extend([0u8; 4]); // x, y
        b.extend(pairs(&[0, 0, idle_fence]));
        b.extend([0u8; 40]);
        t.req(153, 1, &b);
    }

    #[test]
    fn dri3_pixmap_is_direct_content_and_present_reports() {
        let (mut t, st) = setup();
        let (w, p1, p2, f) = (t.id(1), t.id(2), t.id(3), t.id(4));
        let (ev, root) = (t.id(5), t.s.root);
        t.create_window(w, root, 0, 0, 16, 16, 0, &[]);
        t.req(152, 14, &[pairs(&[w, f]), vec![0, 0, 0, 0]].concat());
        t.s.notifications.clear();
        from_buffer(&mut t, p1, w);
        from_buffer(&mut t, p2, w);
        assert!(t.msgs().is_empty());
        assert!(t.s.notifications.contains(&Notify::DirectContentAdded { window: w, pixmap: p1 }));
        assert_eq!(t.s.drawables[&p1].direct_window, Some(w));
        // fd consumed: a second request without one is dropped silently, not a crash
        // Present.SelectInput: configure, complete and idle
        t.req(153, 3, &pairs(&[ev, w, 0b111]));

        // no FPS limit: the pixmap is idle only when the window presents the next one
        t.s.notifications.clear();
        present(&mut t, w, p1, 10, f);
        let m = split(&t.out());
        assert_eq!(m.len(), 1, "only CompleteNotify yet");
        let c = &m[0];
        assert_eq!((c[0], c[1], le16(c, 8), le32(c, 4), c.len()), (35, 153, 1, 2, 40));
        assert_eq!((le32(c, 12), le32(c, 16), le32(c, 20)), (ev, w, 10));
        assert_eq!(le32(c, 24) as i64, (1_000_000_000i64 / 1000) & 0xffff_ffff);
        assert!(t.s.notifications.contains(&Notify::ContentUpdateDirect { window: w, pixmap: p1 }));
        assert_eq!(t.s.sync.fence_state(f), Some(false));
        present(&mut t, w, p2, 11, 0);
        let m = split(&t.out());
        assert_eq!(m.len(), 2);
        let idle = m.iter().find(|e| le16(e, 8) == 2).expect("IdleNotify of the first pixmap");
        assert_eq!((le32(idle, 12), le32(idle, 20), le32(idle, 24), le32(idle, 28), idle.len()), (ev, 10, p1, f, 32));
        assert_eq!(t.s.sync.fence_state(f), Some(true), "the idle fence is triggered");

        // FPS limit: complete at once, idle one frame interval later, driven by the timer
        st.lock().unwrap().fps = 100;
        present(&mut t, w, p1, 12, 0);
        let m = split(&t.out());
        assert_eq!(m.len(), 1);
        assert_eq!(le16(&m[0], 8), 1);
        let due = t.s.next_timer().expect("release scheduled");
        assert!(due >= 1_000_000_000 + 9_000_000 && due <= 1_000_000_000 + 11_000_000, "due {due}");
        t.s.run_timers(due - 1);
        assert!(t.out().is_empty());
        t.s.run_timers(due);
        let m = split(&t.out());
        assert_eq!(m.len(), 1);
        assert_eq!((le16(&m[0], 8), le32(&m[0], 24)), (2, p1));

        // freeing the pixmap removes the direct content
        t.s.notifications.clear();
        t.req(54, 0, &u32le(p1));
        assert!(t.s.notifications.contains(&Notify::DirectContentRemoved { window: w, pixmap: p1 }));

        // destroying the window: ConfigureNotify with the destroyed flag, selections gone
        t.out();
        t.s.destroy_window(w);
        let m = split(&t.out());
        let conf = m.iter().find(|e| e[0] == 35 && le16(e, 8) == 0).expect("ConfigureNotify");
        assert_eq!(le32(conf, 36), 1);
    }

    #[test]
    fn dri3_without_descriptor_is_dropped() {
        let (mut t, _) = setup();
        let (w, p, root) = (t.id(1), t.id(2), t.s.root);
        t.create_window(w, root, 0, 0, 16, 16, 0, &[]);
        let mut b = pairs(&[p, w, 0]);
        b.extend([0u8; 8]);
        t.req(154, 2, &b);
        assert!(t.msgs().is_empty());
        assert!(!t.s.drawables.contains_key(&p));
    }

    #[test]
    fn glx_answers_metadata() {
        let (mut t, _) = setup();
        t.req(151, 21, &pairs(&[0])); // GetFBConfigs(screen 0)
        let out = t.out();
        assert_eq!(out[0], 1);
        assert_eq!(le32(&out, 8), 6, "six FBConfigs");
        assert_eq!(out.len(), 32 + le32(&out, 4) as usize * 4);
        t.req(151, 99, &[]); // unknown: BadImplementation
        assert!(matches!(&t.msgs()[0], Msg::Error(e) if e[1] == 17));
    }

    #[test]
    fn randr_screen_info() {
        let (mut t, _) = setup();
        t.s.set_refresh_rates(&[60, 90, 120], 90);
        let root = t.s.root;
        t.req(150, 5, &u32le(root));
        let out = t.out();
        assert_eq!(out[0], 1);
        assert_eq!(out.len(), 32 + le32(&out, 4) as usize * 4);
        t.req(150, 77, &[]);
        assert!(matches!(&t.msgs()[0], Msg::Error(e) if e[1] == 17));
    }

    /// Every extension with random minors and bodies, ids from a small pool, descriptors, timers,
    /// fence waits and raw input: nothing may panic.
    #[test]
    fn fuzz_extensions_never_panic() {
        let seeds: u64 = std::env::var("XCORE_FUZZ_SEEDS").ok().and_then(|v| v.parse().ok()).unwrap_or(30);
        for seed in 1..=seeds {
            let mut rng = Rng(seed.wrapping_mul(0x9e3779b97f4a7c15));
            let (mut t, st) = setup();
            for id in 1..4 {
                st.lock().unwrap().shm.insert(id, vec![0xa5u8; 256].into_boxed_slice());
            }
            let (c2, base2) = t.second();
            let root = t.s.root;
            let pool: Vec<u32> = (1..12).map(|i| t.base + i).chain((1..6).map(|i| base2 + i)).chain([root, 1, 2, 3, 0]).collect();
            // some real windows, pixmaps and a fence to hit
            for i in 1..4 {
                t.create_window(t.base + i, root, 0, 0, 16, 16, 0, &[]);
            }
            t.req(53, 24, &[pairs(&[t.base + 5, root]), vec![16, 0, 16, 0]].concat());
            t.req(152, 14, &[pairs(&[t.base + 1, t.base + 6]), vec![0, 0, 0, 0]].concat());
            let majors = [146u8, 148, 150, 151, 152, 153, 154, 155];
            for i in 0..2500u32 {
                let major = majors[rng.below(majors.len() as u64) as usize];
                let minor = if rng.below(2) == 0 { rng.below(64) as u8 } else { [0u8, 1, 2, 3, 7, 14, 15, 16, 17, 19, 21, 46, 47, 48][rng.below(14) as usize] };
                let words = rng.below(24) as usize;
                let mut body = vec![0u8; words * 4];
                for chunk in body.chunks_mut(4) {
                    let v = if rng.below(2) == 0 { pool[rng.below(pool.len() as u64) as usize] } else { rng.next() as u32 };
                    chunk.copy_from_slice(&v.to_le_bytes());
                }
                let who = if rng.below(4) == 0 { c2 } else { t.c };
                if major == 154 && rng.below(2) == 0 {
                    let mut v = vec![major, minor];
                    v.extend(((body.len() / 4 + 1) as u16).to_le_bytes());
                    v.extend(&body);
                    t.s.feed_fds(who, &v, vec![pipe_fd()]);
                } else {
                    t.req_as(who, major, minor, &body);
                }
                match rng.below(12) {
                    0 => t.s.raw_motion(rng.below(9) as f64 - 4.0, rng.below(9) as f64 - 4.0),
                    1 => t.s.set_pointer_button(1 + rng.below(7) as u8, rng.below(2) == 0),
                    2 => st.lock().unwrap().fps = [0, 30, 60, 1000][rng.below(4) as usize],
                    3 => {
                        let now = {
                            let mut g = st.lock().unwrap();
                            g.now += rng.below(40_000_000) as i64;
                            g.now
                        };
                        t.s.run_timers(now);
                    }
                    4 => t.s.set_refresh_rates(&[60, 90], 60),
                    5 if i % 97 == 0 => t.s.destroy_window(t.base + 1 + rng.below(3) as u32),
                    _ => {}
                }
                // an unblocked client must keep working: unblock stuck AwaitFences now and then
                if i % 50 == 0 {
                    for f in 0..16u32 {
                        t.s.sync.trigger(t.base + f);
                        t.s.sync.trigger(base2 + f);
                    }
                    t.s.service_wakes();
                }
                t.s.notifications.clear();
                t.s.take_output(t.c);
                t.s.take_output(c2);
            }
            t.s.client_disconnected(c2);
            t.s.client_disconnected(t.c);
        }
    }

    #[test]
    fn descriptors_nobody_took_are_closed_with_the_client() {
        let (mut t, _) = setup();
        let (c2, _) = t.second();
        let fds: Vec<i32> = (0..3).map(|_| pipe_fd()).collect();
        // requests that never ask for them (a bare QueryVersion of MIT-SHM)
        t.s.feed_fds(c2, &[155, 0, 1, 0], fds.clone());
        t.s.client_disconnected(c2);
        for fd in fds {
            assert_eq!(unsafe { libc::fcntl(fd, libc::F_GETFD) }, -1, "fd {fd} leaked");
        }
        // and descriptors that arrive for a client that is gone are closed at once
        let fd = pipe_fd();
        t.s.feed_fds(c2, &[], vec![fd]);
        assert_eq!(unsafe { libc::fcntl(fd, libc::F_GETFD) }, -1);
    }

    #[test]
    fn dri3_failed_request_does_not_leave_its_descriptor_for_the_next_one() {
        let (mut t, _) = setup();
        let (w, bad_pm, good_pm) = (t.id(1), t.id(2), t.id(3));
        t.create_window(w, t.s.root, 0, 0, 16, 16, 0, &[]);
        t.out();
        // BadWindow: the descriptor that came with the request must be gone with it.
        from_buffer(&mut t, bad_pm, 0x0bad_0bad);
        assert!(matches!(&t.msgs()[0], Msg::Error(e) if e[1] == 3));
        assert_eq!(t.s.client(t.c).unwrap().fds.len(), 0, "the failed request kept its descriptor");
        // the next, valid request gets its own descriptor
        from_buffer(&mut t, good_pm, w);
        assert!(t.msgs().is_empty());
        assert!(t.s.pixmaps.contains(&good_pm));
    }

    #[test]
    fn mitshm_send_event_gets_a_completion_event() {
        let (mut t, st) = setup();
        st.lock().unwrap().shm.insert(7, vec![0u8; 8 * 8 * 4].into_boxed_slice());
        let (w, gc, seg) = (t.id(1), t.id(2), t.id(3));
        let root = t.s.root;
        t.create_window(w, root, 0, 0, 8, 8, 0, &[]);
        t.create_gc(gc, w, 0, &[]);
        t.req(155, 1, &[pairs(&[seg, 7]), vec![0, 0, 0, 0]].concat());
        let mut b = pairs(&[w, gc]);
        for v in [8i16, 8, 0, 0, 8, 8, 0, 0] {
            b.extend(v.to_le_bytes());
        }
        b.extend([24, 2, 1, 0]); // depth, format ZPixmap, send_event = 1
        b.extend(pairs(&[seg, 0]));
        t.req(155, 3, &b);
        let m = t.msgs();
        let ev = events(&m);
        assert_eq!(ev.len(), 1, "{m:?}");
        assert_eq!(ev[0][0], 64, "FirstEvent of MIT-SHM = ShmCompletion");
        assert_eq!(le32(&ev[0], 4), w);
        assert_eq!((le16(&ev[0], 8), ev[0][10]), (3, 155), "minor / major opcode");
        assert_eq!(le32(&ev[0], 12), seg);
    }

    /// Imports that finish when the test says so.
    type Pending = Arc<Mutex<Vec<crate::proto::hooks::ImportDone>>>;

    struct Lazy(Pending);

    impl Platform for Lazy {
        fn import_buffer_async(&mut self, fd: i32, done: crate::proto::hooks::ImportDone) {
            unsafe { libc::close(fd) };
            self.0.lock().unwrap().push(done);
        }
    }

    fn lazy_import() -> (T, Pending) {
        let mut t = T::new();
        let pending: Pending = Default::default();
        t.s.platform = Box::new(Lazy(pending.clone()));
        (t, pending)
    }

    #[test]
    fn dri3_buffer_import_holds_only_its_own_client() {
        let (mut t, pending) = lazy_import();
        let (c2, _) = t.second();
        let (w, pm) = (t.id(1), t.id(2));
        t.create_window(w, t.s.root, 0, 0, 16, 16, 0, &[]);
        t.out();
        from_buffer(&mut t, pm, w);
        assert!(t.out().is_empty());
        assert!(!t.s.pixmaps.contains(&pm), "the buffer has not arrived yet");
        // the next request of the same client waits behind it ...
        t.req(43, 0, &[]); // GetInputFocus
        assert!(t.out().is_empty(), "the client's requests are on hold");
        // ... while everybody else is served
        t.req_as(c2, 43, 0, &[]);
        assert!(!t.s.take_output(c2).is_empty(), "another client must not wait for the buffer");

        // the buffer arrives
        let done = pending.lock().unwrap().pop().unwrap();
        done(Some(Box::new(HostPixels::new(16, 16, false))));
        t.s.service_wakes();
        assert!(t.s.pixmaps.contains(&pm));
        assert!(t.s.notifications.iter().any(|n| matches!(n, Notify::DirectContentAdded { window, pixmap } if *window == w && *pixmap == pm)));
        let m = t.msgs();
        assert!(matches!(&m[..], [Msg::Reply { .. }]), "the held request is answered now: {m:?}");
    }

    #[test]
    fn dri3_buffer_that_never_arrives_is_bad_alloc_and_the_client_goes_on() {
        let (mut t, pending) = lazy_import();
        let (w, pm) = (t.id(1), t.id(2));
        t.create_window(w, t.s.root, 0, 0, 16, 16, 0, &[]);
        t.out();
        from_buffer(&mut t, pm, w);
        t.req(43, 0, &[]);
        let done = pending.lock().unwrap().pop().unwrap();
        done(None);
        t.s.service_wakes();
        let m = t.msgs();
        assert!(matches!(&m[0], Msg::Error(e) if e[1] == 11 && e[8] == 2 && e[10] == 154), "BadAlloc of DRI3 request 2: {m:?}");
        assert!(matches!(&m[1], Msg::Reply { .. }), "and the held request goes on: {m:?}");
        assert!(!t.s.pixmaps.contains(&pm));
    }

    #[test]
    fn dri3_buffer_for_a_client_that_left_or_a_window_that_died_is_dropped() {
        let (mut t, pending) = lazy_import();
        let (c2, base2) = t.second();
        let (w2, pm2) = (base2 + 1, base2 + 2);
        t.create_window_as(c2, w2, t.s.root, 0, 0, 16, 16, 0, &[]);
        let mut b = pairs(&[pm2, w2, 16 * 16 * 4]);
        b.extend([16, 0, 16, 0, 64, 0, 24, 32]);
        let mut v = vec![154, 2, ((b.len() / 4 + 1) as u16).to_le_bytes()[0], 0];
        v.extend(b);
        t.s.feed_fds(c2, &v, vec![pipe_fd()]);
        assert!(t.s.client(c2).unwrap().blocked.is_some());
        t.s.client_disconnected(c2);
        let done = pending.lock().unwrap().pop().unwrap();
        done(Some(Box::new(HostPixels::new(16, 16, false))));
        t.s.service_wakes();
        assert!(!t.s.pixmaps.contains(&pm2));
        assert!(t.s.import_jobs.is_empty());
        assert!(t.s.import_results.is_empty_for_test(), "the result of a gone client is not kept");

        // a window that is destroyed while the buffer is on its way
        let (w, pm) = (t.id(1), t.id(2));
        t.create_window(w, t.s.root, 0, 0, 16, 16, 0, &[]);
        t.out();
        from_buffer(&mut t, pm, w);
        t.s.destroy_window(w);
        let done = pending.lock().unwrap().pop().unwrap();
        done(Some(Box::new(HostPixels::new(16, 16, false))));
        t.s.service_wakes();
        assert!(!t.s.pixmaps.contains(&pm));
        assert!(t.msgs().iter().all(|m| !matches!(m, Msg::Error(_))), "no error for a window that is gone");
    }

    #[test]
    fn a_slow_driver_does_not_hold_up_the_runtime() {
        use crate::host::new_server;
        use crate::proto::hooks::ImportDone;
        use crate::runtime::Runtime;
        use std::time::{Duration, Instant};

        struct Slow;
        impl Platform for Slow {
            fn import_buffer_async(&mut self, fd: i32, done: ImportDone) {
                std::thread::spawn(move || {
                    std::thread::sleep(Duration::from_millis(600));
                    unsafe { libc::close(fd) };
                    done(Some(Box::new(HostPixels::new(16, 16, false))));
                });
            }
        }

        let path = format!("/tmp/xcore-import-{}", std::process::id());
        let mut server = new_server(640, 480);
        server.platform = Box::new(Slow);
        let rt = Runtime::start(&path, server).unwrap();
        let (c, pm, w) = rt.with(|s| {
            let c = s.add_client().unwrap();
            let base = s.client(c).unwrap().resource_base;
            s.feed(c, &[0x6c, 0, 11, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
            let w = base | 1;
            s.create_window(c, w, s.root, 0, 0, 16, 16, 1, None, 0).unwrap();
            (c, base | 2, w)
        });
        let _ = rt.drain_events();
        let mut b = pairs(&[pm, w, 16 * 16 * 4]);
        b.extend([16, 0, 16, 0, 64, 0, 24, 32]);
        let mut req = vec![154, 2, ((b.len() / 4 + 1) as u16).to_le_bytes()[0], 0];
        req.extend(b);
        let t0 = Instant::now();
        rt.with(|s| s.feed_fds(c, &req, vec![pipe_fd()]));
        // Other threads (UI, input) are not kept waiting for the driver.
        rt.with(|s| s.windows.len());
        assert!(t0.elapsed() < Duration::from_millis(300), "the server lock was held while waiting: {:?}", t0.elapsed());
        // The I/O thread picks the result up by itself (no other call into the server needed).
        let t1 = Instant::now();
        let mut seen = false;
        while !seen {
            assert!(t1.elapsed() < Duration::from_secs(5), "the import was never completed");
            seen = rt.drain_events().iter().any(|n| matches!(n, Notify::DirectContentAdded { .. }));
            std::thread::sleep(Duration::from_millis(10));
        }
        assert!(t1.elapsed() >= Duration::from_millis(200), "completed before the driver answered?");
        rt.stop();
    }
}

// ---------------------------------------------------------------------------- regression tests
// Bugs of the Java server that the port had inherited, and the robustness of the port itself.

#[test]
fn get_property_with_delete_keeps_a_property_of_another_type() {
    let mut t = T::new();
    let (w, root) = (t.id(1), t.s.root);
    t.create_window(w, root, 0, 0, 10, 10, 0, &[]);
    let a = t.intern("FOO");
    t.change_property(w, 0, a, 31, 8, b"abcd");
    t.out();
    // another type + delete: the property is reported (empty) and must stay
    match t.get_property(w, a, 6, 0, 100, true) {
        Msg::Reply { body, .. } => assert_eq!((le32(&body, 0), le32(&body, 4)), (31, 4), "actual type, bytes_after"),
        m => panic!("{m:?}"),
    }
    match t.get_property(w, a, 0, 0, 100, false) {
        Msg::Reply { body, .. } => assert_eq!(&body[24..28], b"abcd", "the property was deleted"),
        m => panic!("{m:?}"),
    }
    // the right type + delete reads everything and deletes
    t.get_property(w, a, 31, 0, 100, true);
    match t.get_property(w, a, 0, 0, 100, false) {
        Msg::Reply { body, .. } => assert_eq!(le32(&body, 0), 0, "gone"),
        m => panic!("{m:?}"),
    }
    // a partial read (bytes_after > 0) does not delete either
    t.change_property(w, 0, a, 31, 8, b"abcdefgh");
    t.out();
    t.get_property(w, a, 31, 0, 1, true);
    match t.get_property(w, a, 0, 0, 100, false) {
        Msg::Reply { body, .. } => assert_eq!(le32(&body, 0), 31),
        m => panic!("{m:?}"),
    }
}

#[test]
fn create_window_with_a_bad_attribute_leaves_nothing_behind() {
    let mut t = T::new();
    let (w, root) = (t.id(1), t.s.root);
    // an attribute value the server cannot accept (the request fails after the window exists)
    t.create_window(w, root, 0, 0, 10, 10, 1 << 4, &[99]); // CWBitGravity: 99 is no gravity
    t.out();
    assert!(!t.s.windows.contains_key(&w), "the half-made window is still in the tree");
    assert!(!t.s.windows[&root].children.contains(&w));
    // the id is free again
    t.create_window(w, root, 0, 0, 10, 10, 0, &[]);
    assert!(t.msgs().is_empty());
    assert!(t.s.windows.contains_key(&w));
}

fn set_selection_owner(t: &mut T, c: ClientId, win: u32, sel: u32, time: u32) {
    t.req_as(c, 22, 0, &[u32le(win), u32le(sel), u32le(time)].concat());
}

#[test]
fn selection_owner_release_and_clear_event() {
    let mut t = T::new();
    let c1 = t.c;
    let (c2, base2) = t.second();
    let (w1, w2, root) = (t.id(1), base2 + 1, t.s.root);
    t.create_window(w1, root, 0, 0, 10, 10, 0, &[]);
    t.create_window_as(c2, w2, root, 0, 0, 10, 10, 0, &[]);
    let sel = t.intern("CLIPBOARD");
    t.out();
    t.s.take_output(c2);

    set_selection_owner(&mut t, c1, w1, sel, 5);
    assert!(t.msgs().is_empty());
    // c2 takes it over: c1 hears about it, and the event names c1's own window
    set_selection_owner(&mut t, c2, w2, sel, 6);
    let ev = events(&t.msgs());
    assert_eq!(ev.len(), 1);
    assert_eq!(ev[0][0], 29);
    assert_eq!((le32(&ev[0], 4), le32(&ev[0], 8), le32(&ev[0], 12)), (6, w1, sel));
    // c1 takes it back, c2 releases nothing yet; now c1 gives it up with window None
    set_selection_owner(&mut t, c1, w1, sel, 7);
    assert!(parse(&t.s.take_output(c2)).iter().any(|m| matches!(m, Msg::Event(e) if e[0] == 29 && le32(e, 8) == w2)));
    set_selection_owner(&mut t, c1, 0, sel, 8);
    // giving it up (window None) is no error; the owner is told, naming its own window
    let o = t.msgs();
    assert!(o.len() == 1 && matches!(&o[0], Msg::Event(e) if e[0] == 29 && le32(e, 8) == w1), "{o:?}");
    t.req(23, 0, &u32le(sel));
    match &t.msgs()[0] {
        Msg::Reply { body, .. } => assert_eq!(le32(body, 0), 0, "no owner any more"),
        m => panic!("{m:?}"),
    }
}

#[test]
fn convert_selection_reaches_the_owner_or_says_none() {
    let mut t = T::new();
    let c1 = t.c;
    let (c2, base2) = t.second();
    let (w1, w2, root) = (t.id(1), base2 + 1, t.s.root);
    t.create_window(w1, root, 0, 0, 10, 10, 0, &[]);
    t.create_window_as(c2, w2, root, 0, 0, 10, 10, 0, &[]);
    let (sel, target, prop) = (t.intern("CLIPBOARD"), t.intern("UTF8_STRING"), t.intern("MY_PROP"));
    t.out();
    t.s.take_output(c2);
    let convert = |t: &mut T, time: u32| t.req(24, 0, &[u32le(w1), u32le(sel), u32le(target), u32le(prop), u32le(time)].concat());

    // nobody owns it: the requestor gets SelectionNotify with property None
    convert(&mut t, 9);
    let ev = events(&t.msgs());
    assert_eq!(ev.len(), 1);
    assert_eq!(ev[0][0], 31);
    assert_eq!((le32(&ev[0], 4), le32(&ev[0], 8), le32(&ev[0], 12), le32(&ev[0], 16), le32(&ev[0], 20)), (9, w1, sel, target, 0));

    // c2 owns it: c2 gets the SelectionRequest, c1 hears nothing
    set_selection_owner(&mut t, c2, w2, sel, 1);
    convert(&mut t, 9);
    assert!(t.out().is_empty());
    let ev = events(&parse(&t.s.take_output(c2)));
    assert_eq!(ev.len(), 1);
    assert_eq!(ev[0][0], 30);
    let words: Vec<u32> = (0..6).map(|i| le32(&ev[0], 4 + i * 4)).collect();
    assert_eq!(words, [9, w2, w1, sel, target, prop]);
    let _ = c1;
}

#[test]
fn set_input_focus_uses_the_window_field_not_the_revert_byte() {
    let mut t = T::new();
    let (w, root) = (t.id(1), t.s.root);
    t.create_window(w, root, 0, 0, 10, 10, 0, &[]);
    t.map(w);
    t.out();
    // RevertToNone with a window: the window gets the focus
    t.req(42, 0, &[u32le(w), u32le(0)].concat());
    assert!(t.out().is_empty());
    assert_eq!((t.s.focus, t.s.focus_revert), (Some(w), 0));
    // window None with RevertToParent is not an error
    t.req(42, 2, &[u32le(0), u32le(0)].concat());
    assert!(t.out().is_empty());
    assert_eq!((t.s.focus, t.s.focus_revert), (None, 2));
    // PointerRoot
    t.req(42, 1, &[u32le(1), u32le(0)].concat());
    assert_eq!(t.s.focus, Some(root));
}

#[test]
fn map_subwindows_maps_only_the_children() {
    let mut t = T::new();
    let (p, a, b, g, root) = (t.id(1), t.id(2), t.id(3), t.id(4), t.s.root);
    t.create_window(p, root, 0, 0, 50, 50, 0, &[]);
    t.create_window(a, p, 0, 0, 10, 10, 0, &[]);
    t.create_window(b, p, 0, 0, 10, 10, 0, &[]);
    t.create_window(g, a, 0, 0, 5, 5, 0, &[]);
    t.out();
    t.req(9, 0, &u32le(p));
    assert!(t.out().is_empty());
    let mapped = |t: &T, id: u32| t.s.windows[&id].attrs.mapped;
    assert!(mapped(&t, a) && mapped(&t, b), "the children are mapped");
    assert!(!mapped(&t, p), "the window itself is not");
    assert!(!mapped(&t, g), "and neither are the grandchildren");
}

#[test]
fn reparenting_a_mapped_window_unmaps_and_maps_it() {
    let mut t = T::new();
    let (w, p, root) = (t.id(1), t.id(2), t.s.root);
    t.create_window(w, root, 0, 0, 10, 10, 0, &[]);
    t.create_window(p, root, 0, 0, 50, 50, 0, &[]);
    t.map(p);
    t.map(w);
    t.select(w, 1 << 17); // StructureNotify
    t.out();
    t.req(7, 0, &[u32le(w), u32le(p), [3, 0, 4, 0]].concat());
    let m = t.msgs();
    assert_eq!(codes(&m), vec![18, 21, 19], "UnmapNotify, ReparentNotify, MapNotify: {m:?}");
    assert_eq!(t.s.windows[&w].parent, Some(p));
    assert!(t.s.windows[&w].attrs.mapped);
    // a window cannot be made a child of its own descendant
    t.req(7, 0, &[u32le(p), u32le(w), [0, 0, 0, 0]].concat());
    assert!(matches!(&t.msgs()[0], Msg::Error(e) if e[1] == 8));
}

#[test]
fn resize_request_and_expose_go_to_the_clients_that_asked() {
    let mut t = T::new();
    let (c2, _) = t.second();
    let (w, root) = (t.id(1), t.s.root);
    t.create_window(w, root, 0, 0, 10, 10, 0, &[]);
    t.map(w);
    t.out();
    let select_as = |t: &mut T, c: ClientId, mask: u32| t.req_as(c, 2, 0, &[u32le(w), u32le(1 << 11), u32le(mask)].concat());
    let resize = |t: &mut T| t.req(12, 0, &[u32le(w), [0x0c, 0, 0, 0], u32le(20), u32le(20)].concat());

    // ExposureMask only: Expose arrives, and only for those who selected it
    select_as(&mut t, c2, 1 << 15);
    t.select(w, 1 << 17); // c1: StructureNotify only
    t.out();
    resize(&mut t);
    assert!(!codes(&t.msgs()).contains(&12), "Expose to a client that did not select ExposureMask");
    assert!(codes(&parse(&t.s.take_output(c2))).contains(&12));

    // ResizeRedirect: the request goes to the selector, and the resize is held back
    select_as(&mut t, c2, 1 << 18);
    t.out();
    t.s.take_output(c2);
    resize_to(&mut t, w, 30, 30);
    let got = codes(&parse(&t.s.take_output(c2)));
    assert!(got.contains(&25), "ResizeRequest for the ResizeRedirect client: {got:?}");
}

fn resize_to(t: &mut T, w: u32, width: u32, height: u32) {
    t.req(12, 0, &[u32le(w), [0x0c, 0, 0, 0], u32le(width), u32le(height)].concat());
}

#[test]
fn get_image_failures_are_errors_not_silence() {
    let mut t = T::new();
    let (w, root) = (t.id(1), t.s.root);
    t.create_window(w, root, 0, 0, 16, 16, 0, &[]);
    t.out();
    let body = |size: u16| {
        let mut b = Vec::new();
        b.extend(u32le(w));
        b.extend(0i16.to_le_bytes());
        b.extend(0i16.to_le_bytes());
        b.extend(size.to_le_bytes());
        b.extend(size.to_le_bytes());
        b.extend(u32le(!0));
        b
    };
    t.req(73, 0, &body(4)); // Bitmap is not a GetImage format
    assert!(matches!(&t.msgs()[0], Msg::Error(e) if e[1] == 2), "BadValue");
    t.req(73, 1, &body(4)); // XYPixmap: not supported
    assert!(matches!(&t.msgs()[0], Msg::Error(e) if e[1] == 8), "BadMatch");
    t.req(73, 2, &body(30000)); // a reply of 3.6 GB cannot be sent
    assert!(matches!(&t.msgs()[0], Msg::Error(e) if e[1] == 8), "BadMatch");
}

mod panics {
    use super::*;
    use crate::proto::requests::Extension;
    use crate::proto::{Res, Server};

    /// An extension (major opcode 200) that panics on minor 1 and answers minor 2.
    struct Bomb;

    impl Extension for Bomb {
        fn name(&self) -> &str {
            "BOMB"
        }
        fn major_opcode(&self) -> u8 {
            200
        }
        fn handle(&mut self, s: &mut Server, c: ClientId, minor: u8, _body: &[u8]) -> Res {
            if minor == 1 {
                panic!("boom");
            }
            s.reply(c, 0, 0, |w| {
                w.pad(24);
            });
            Ok(())
        }
    }

    #[test]
    fn a_panicking_extension_is_dropped_for_that_request_only() {
        let mut t = T::new();
        t.s.register_extension(Box::new(Bomb));
        t.req(200, 1, &[]); // panics inside the handler
        assert!(t.msgs().is_empty(), "the request is dropped");
        assert!(t.s.ext_handlers.contains_key(&200), "the handler must be back in place");
        t.req(200, 2, &[]);
        assert!(matches!(&t.msgs()[0], Msg::Reply { .. }), "the extension still works");
    }

    #[test]
    fn a_panic_in_a_hook_or_timer_does_not_lose_the_extension() {
        struct Fussy;
        impl Extension for Fussy {
            fn name(&self) -> &str {
                "FUSSY"
            }
            fn major_opcode(&self) -> u8 {
                201
            }
            fn handle(&mut self, _: &mut Server, _: ClientId, _: u8, _: &[u8]) -> Res {
                Ok(())
            }
            fn on_timer(&mut self, _: &mut Server, _: u64) {
                panic!("timer");
            }
            fn on_hook(&mut self, _: &mut Server, _: &crate::proto::hooks::Hook) {
                panic!("hook");
            }
        }
        let mut t = T::new();
        t.s.register_extension(Box::new(Fussy));
        t.s.schedule(201, 0, 1);
        t.s.run_timers(10);
        t.s.run_hook(crate::proto::hooks::Hook::PixmapFreed(1));
        assert!(t.s.ext_handlers.contains_key(&201));
    }

    #[test]
    fn a_panic_costs_the_client_its_connection_not_the_server_its_io_thread() {
        use crate::host::new_server;
        use crate::runtime::Runtime;
        use std::io::{Read, Write};
        use std::os::unix::net::UnixStream;
        use std::time::{Duration, Instant};

        let path = format!("/tmp/xcore-panic-{}", std::process::id());
        let mut server = new_server(640, 480);
        let root = server.root;
        // Code outside any extension: every PropertyNotify asks the clock, which panics once.
        let armed = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(true));
        let a2 = armed.clone();
        server.clock_ms = Box::new(move || {
            if a2.swap(false, std::sync::atomic::Ordering::SeqCst) {
                panic!("clock");
            }
            0
        });
        let rt = Runtime::start(&path, server).unwrap();
        let connect = || {
            let mut s = UnixStream::connect(&path).unwrap();
            s.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
            s.write_all(&[b'l', 0, 11, 0, 0, 0, 0, 0, 0, 0, 0, 0]).unwrap();
            let mut head = [0u8; 8];
            s.read_exact(&mut head).unwrap();
            assert_eq!(head[0], 1);
            let mut rest = vec![0u8; u16::from_le_bytes([head[6], head[7]]) as usize * 4];
            s.read_exact(&mut rest).unwrap();
            s
        };
        let mut a = connect();
        // InternAtom first (no clock), then ChangeProperty on the root, which does ask for the time
        a.write_all(&[16, 0, 3, 0, 1, 0, 0, 0, b'A', 0, 0, 0]).unwrap();
        let mut reply = [0u8; 32];
        a.read_exact(&mut reply).unwrap();
        let mut req = vec![18, 0, 7, 0];
        req.extend(u32::to_le_bytes(root));
        req.extend(u32::to_le_bytes(u32::from_le_bytes([reply[8], reply[9], reply[10], reply[11]])));
        req.extend(u32::to_le_bytes(31)); // STRING
        req.extend([8, 0, 0, 0]);
        req.extend(u32::to_le_bytes(1));
        req.extend([b'x', 0, 0, 0]);
        a.write_all(&req).unwrap(); // panics inside the server
        // the connection of `a` is closed by the server
        let mut buf = [0u8; 32];
        assert_eq!(a.read(&mut buf).unwrap_or(0), 0, "the offending client is disconnected");
        // the server still serves everybody else
        let mut b = connect();
        b.write_all(&[16, 0, 3, 0, 1, 0, 0, 0, b'B', 0, 0, 0]).unwrap(); // InternAtom
        let mut reply = [0u8; 32];
        b.read_exact(&mut reply).unwrap();
        assert_eq!(reply[0], 1, "the server still answers");
        let t0 = Instant::now();
        while rt.connection_count() != 1 {
            assert!(t0.elapsed() < Duration::from_secs(5), "client of the panic not removed");
            std::thread::sleep(Duration::from_millis(5));
        }
        rt.stop();
    }
}
