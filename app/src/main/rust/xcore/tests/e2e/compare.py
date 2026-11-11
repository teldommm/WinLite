"""Runs the same battery of X requests against a display and prints a normalized transcript.
Usage: compare.py :77   (xcore host)  /  compare.py :78 (Xvfb oracle); then diff the outputs."""
import sys
from Xlib import X, display, Xatom, error, protocol
from Xlib.protocol import rq

d = display.Display(sys.argv[1])
s = d.screen()
root = s.root
out = []
def log(*a): out.append(" ".join(str(x) for x in a))

def drain(tag):
    d.sync()
    names = []
    while d.pending_events():
        e = d.next_event()
        extra = ""
        if isinstance(e, protocol.event.PropertyNotify):
            extra = "(atom=%s state=%d)" % (d.get_atom_name(e.atom), e.state)
        elif isinstance(e, (protocol.event.ConfigureNotify,)):
            extra = "(%d,%d %dx%d)" % (e.x, e.y, e.width, e.height)
        elif isinstance(e, (protocol.event.ReparentNotify,)):
            extra = "(%d,%d)" % (e.x, e.y)
        elif isinstance(e, protocol.event.ClientMessage):
            extra = "(fmt=%d)" % e.client_type
        names.append(type(e).__name__ + extra)
    log(tag, "events:", names)

# ---- atoms
log("atom WM_NAME", d.intern_atom("WM_NAME"), d.get_atom_name(39))
log("atom only_if_exists missing", d.intern_atom("NO_SUCH_ATOM_XYZ", only_if_exists=True))
a1 = d.intern_atom("MY_ATOM_1"); a2 = d.intern_atom("MY_ATOM_2"); log("atom order", a2 - a1)
log("atom again", d.intern_atom("MY_ATOM_1") == a1)

# ---- windows & events
root.change_attributes(event_mask=X.SubstructureNotifyMask)
w = root.create_window(10, 20, 200, 100, 0, s.root_depth, X.InputOutput, X.CopyFromParent,
                       event_mask=X.StructureNotifyMask | X.PropertyChangeMask | X.ExposureMask)
drain("create")
w.map(); drain("map")
w.configure(x=30, y=40, width=210, height=110); drain("configure")
g = w.get_geometry(); log("geom", g.x, g.y, g.width, g.height, g.border_width)
w.configure(border_width=2); drain("border")
attrs = w.get_attributes()
log("attrs", attrs.map_state, attrs.win_class, attrs.override_redirect, attrs.your_event_mask & X.StructureNotifyMask != 0)
sub = w.create_window(5, 6, 20, 20, 0, s.root_depth, X.InputOutput, X.CopyFromParent, event_mask=X.StructureNotifyMask)
sub.map(); drain("submap")
log("tree", [c.id == sub.id for c in w.query_tree().children], w.query_tree().parent.id == root.id)
sub.reparent(root, 1, 2); drain("reparent")
sub.unmap(); drain("unmap")
sub.destroy(); drain("destroy sub")

# ---- translate / pointer
t = w.translate_coords(root, 0, 0); log("translate", t.x, t.y)
q = w.query_pointer(); log("querypointer same_screen", q.same_screen)

# ---- properties
w.change_property(Xatom.WM_NAME, Xatom.STRING, 8, b"hello world"); drain("prop set")
p = w.get_full_property(Xatom.WM_NAME, X.AnyPropertyType); log("prop", p.property_type, p.format, p.value)
w.change_property(Xatom.WM_NAME, Xatom.STRING, 8, b"!!!", mode=X.PropModeAppend)
w.change_property(Xatom.WM_NAME, Xatom.STRING, 8, b">>", mode=X.PropModePrepend)
p = w.get_full_property(Xatom.WM_NAME, X.AnyPropertyType); log("prop2", p.value); drain("prop mods")
r = w.get_property(Xatom.WM_NAME, X.AnyPropertyType, 1, 1); log("slice", r.value, r.bytes_after)
r = w.get_property(Xatom.WM_NAME, Xatom.CARDINAL, 0, 10); log("type mismatch", r.property_type, r.format, r.value, r.bytes_after)
w.change_property(a1, Xatom.CARDINAL, 32, [1, 2, 3, 0xffffffff])
r = w.get_property(a1, Xatom.CARDINAL, 0, 10); log("cardinal", list(r.value), r.format)
w.change_property(a2, Xatom.CARDINAL, 16, [7, 8, 9])
r = w.get_property(a2, Xatom.CARDINAL, 0, 10); log("c16", list(r.value), r.format)
log("props list", sorted(d.get_atom_name(a) for a in w.list_properties()))
w.delete_property(a2); drain("prop delete")
log("deleted", w.get_property(a2, X.AnyPropertyType, 0, 10))

# ---- selections
w2 = root.create_window(0, 0, 10, 10, 0, s.root_depth, X.InputOutput, X.CopyFromParent)
sel = d.intern_atom("MY_SELECTION")
log("owner none", d.get_selection_owner(sel) == X.NONE)
w2.set_selection_owner(sel, X.CurrentTime)
log("owner set", d.get_selection_owner(sel).id == w2.id)

# ---- images
gc = w.create_gc(foreground=0xff0000, background=0x00ff00)
pm = w.create_pixmap(8, 8, s.root_depth)
log("pixmap geom", pm.get_geometry().width, pm.get_geometry().height)
data = bytes(range(64)) * 1
w.put_image(gc, 0, 0, 4, 4, X.ZPixmap, s.root_depth, 0, bytes(4 * 4 * 4))
im = w.get_image(0, 0, 4, 4, X.ZPixmap, 0xffffffff); log("getimage depth", im.depth, len(im.data))
pm.copy_area(gc, w, 0, 0, 0, 0, 4, 4)
d.sync()

# ---- errors
errs = []
def on_err(e, *a):
    errs.append((type(e).__name__, getattr(e, "resource_id", None) and getattr(e, "resource_id").id if hasattr(getattr(e, "resource_id", None), "id") else getattr(e, "resource_id", None), e.major_opcode if hasattr(e, "major_opcode") else None))
d.set_error_handler = None
ec = error.CatchError()
for what, fn in [
    ("bad window", lambda: d.display.send_and_recv  and root.__class__(d.display, 0x7777777).get_geometry()),
    ("bad atom", lambda: d.get_atom_name(0x77777)),
]:
    try:
        fn(); d.sync(); log("err", what, "none")
    except Exception as e:
        log("err", what, type(e).__name__)

# ---- keyboard / misc replies
km = d.get_keyboard_mapping(d.display.info.min_keycode, 5); log("kbmap rows", len(km))
pmap = d.get_pointer_mapping(); log("pointer mapping len>=5", len(pmap) >= 5)
log("input focus revert", d.get_input_focus().revert_to)
d.set_input_focus(X.NONE, X.RevertToNone, X.CurrentTime)
f = d.get_input_focus(); log("focus after none", f.focus == X.NONE, f.revert_to)
ss = d.get_screen_saver(); log("screensaver", ss.timeout, ss.interval, ss.prefer_blanking, ss.allow_exposures)
for line in out: print(line)
