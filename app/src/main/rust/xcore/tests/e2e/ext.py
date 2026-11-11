"""Extensions seen by real clients (python-xlib, libXext through xdpyinfo).
Usage: ext.py :77 /tmp/host_in"""
import subprocess, sys, time
from Xlib import X, display
from Xlib.ext import randr, composite, xinput

disp, fifo = sys.argv[1], sys.argv[2]
cmd = open(fifo, "w", buffering=1)
def send(line):
    cmd.write(line + "\n"); cmd.flush(); time.sleep(0.15)

# libXext/libX11 query every extension xdpyinfo knows (MIT-SHM, RANDR, Composite, XInput, GLX ...)
out = subprocess.run(["xdpyinfo", "-display", disp, "-ext", "all"], capture_output=True, text=True, timeout=30)
print("xdpyinfo rc", out.returncode, out.stderr.strip()[:200])
assert out.returncode == 0, out.stderr
for name in ["BIG-REQUESTS", "RANDR", "SYNC", "MIT-SHM", "Composite", "XInputExtension", "DRI3", "Present", "GLX"]:
    assert name in out.stdout, f"{name} missing"
print("xdpyinfo lists all nine extensions")

d = display.Display(disp)
s = d.screen()
root = s.root

r = d.xrandr_query_version(); print("randr", r.major_version, r.minor_version)
info = root.xrandr_get_screen_info()
print("randr screen info", {k: v for k, v in info._data.items() if k in ("rates", "rate", "sizes", "size_id", "rotations", "timestamp")})

c = d.composite_query_version(); print("composite", c.major_version, c.minor_version)
w = root.create_window(10, 10, 100, 100, 0, s.root_depth, X.InputOutput, X.CopyFromParent)
w.map(); d.sync()
w.composite_redirect_window(composite.RedirectAutomatic); d.sync()
w.composite_unredirect_window(composite.RedirectAutomatic); d.sync()
print("composite redirect/unredirect ok")

x = d.xinput_query_version(); print("xi", x.major_version, x.minor_version)
assert (x.major_version, x.minor_version) == (2, 0)  # python-xlib asks for 2.0; the server answers min(client, 2.2)
devs = d.xinput_query_device(xinput.AllDevices)
print("xi devices", [(i.deviceid, i.name, i.use, len(i.classes)) for i in devs.devices])
assert len(devs.devices) == 3

# Raw XI2 events, on a bare socket: python-xlib cannot parse generic events (no GE extension here).
import socket, struct, select
sock = socket.socket(socket.AF_UNIX)
sock.connect("/tmp/.X11-unix/X" + disp.lstrip(":").split(".")[0])
sock.send(struct.pack("<BxHHHHxx", 0x6c, 11, 0, 0, 0))
time.sleep(0.3)
setup = sock.recv(65536)
vlen, nfmt = struct.unpack_from("<H", setup, 24)[0], setup[29]
root_id = struct.unpack_from("<I", setup, 40 + ((vlen + 3) & ~3) + 8 * nfmt)[0]
name = b"XInputExtension"
sock.send(struct.pack("<BxHH2x", 98, 2 + (len(name) + 3) // 4, len(name)) + name + b"\0" * ((4 - len(name) % 4) % 4))
time.sleep(0.2)
qr = sock.recv(100)
major = qr[9]
print('setup', len(setup), 'root', hex(root_id), 'XI major', major, 'present', qr[8])
body = struct.pack("<IHHHHI", root_id, 1, 0, 1, 1, (1 << 15) | (1 << 16) | (1 << 17))
sock.send(struct.pack("<BBH", major, 46, 1 + len(body) // 4) + body)
time.sleep(0.2)
send("raw 5 -3")
send("button 1 1")
send("button 1 0")
buf = b""
t0 = time.time()
while time.time() - t0 < 3 and len(buf) < 68 + 32 + 32:
    if select.select([sock], [], [], 0.2)[0]:
        buf += sock.recv(65536)
events, i = [], 0
while i + 32 <= len(buf):
    code = buf[i] & 0x7f
    extra = struct.unpack_from("<I", buf, i + 4)[0] * 4 if code == 35 else 0
    events.append(buf[i:i + 32 + extra])
    i += 32 + extra
print("wire events", [(e[0], e[1], struct.unpack_from("<H", e, 8)[0], len(e)) for e in events])
raw = [e for e in events if e[0] == 35]
assert [struct.unpack_from("<H", e, 8)[0] for e in raw] == [17, 15, 16], raw
dx, dy = struct.unpack_from("<i", raw[0], 36)[0], struct.unpack_from("<i", raw[0], 44)[0]
assert (dx, dy) == (5, -3), (dx, dy)
assert [len(e) for e in raw] == [68, 32, 32]
print("raw motion (5,-3) and raw buttons delivered")
print("ok")
