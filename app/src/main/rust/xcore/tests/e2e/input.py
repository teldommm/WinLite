"""Input through the host command channel (a FIFO the host reads as stdin) seen by a real client.
Usage: input.py :77 /tmp/host_in"""
import sys, time
from Xlib import X, display
d = display.Display(sys.argv[1])
cmd = open(sys.argv[2], "w", buffering=1)
def send(line):
    cmd.write(line + "\n"); cmd.flush(); time.sleep(0.15)
s = d.screen()
w = s.root.create_window(100, 100, 300, 300, 0, s.root_depth, X.InputOutput, X.CopyFromParent,
        event_mask=X.PointerMotionMask | X.ButtonPressMask | X.ButtonReleaseMask | X.KeyPressMask | X.KeyReleaseMask | X.EnterWindowMask | X.LeaveWindowMask)
w.map(); d.sync()
d.set_input_focus(w, X.RevertToParent, X.CurrentTime); d.sync()
def events():
    d.sync(); r = []
    while d.pending_events():
        e = d.next_event()
        n = type(e).__name__
        if n in ("MotionNotify", "ButtonPress", "ButtonRelease", "KeyPress", "KeyRelease", "EnterNotify", "LeaveNotify"):
            r.append((n, getattr(e, "detail", None), e.event_x, e.event_y, e.root_x, e.root_y, e.state))
        else:
            r.append((n,))
    return r
events()
send("move 150 180"); print("move:", events())
send("button 1 1"); print("press:", events())
send("move 160 190"); print("drag:", events())
send("button 1 0"); print("release:", events())
send("key 38 1"); send("key 38 0"); print("key a:", events())
send("key 50 1"); send("key 38 1"); send("key 38 0"); send("key 50 0"); print("shift+a:", events())
send("move 900 700"); print("leave:", events())
send("move 150 150"); print("enter:", events())
q = s.root.query_pointer(); print("querypointer root", q.root_x, q.root_y, q.mask)
w.destroy(); d.sync()
print("done")
