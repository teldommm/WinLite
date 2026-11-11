/* MIT-SHM through libXext: XShmQueryVersion, XShmAttach, XShmPutImage, XShmGetImage-free check by XGetImage.
 * cc shm_client.c -o shm_client -lX11 -lXext ; ./shm_client :77 */
#include <X11/Xlib.h>
#include <X11/Xutil.h>
#include <X11/extensions/XShm.h>
#include <sys/ipc.h>
#include <sys/shm.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main(int argc, char **argv) {
    Display *d = XOpenDisplay(argv[1]);
    if (!d) { fprintf(stderr, "cannot open %s\n", argv[1]); return 2; }
    int maj, min, pix;
    if (!XShmQueryVersion(d, &maj, &min, &pix)) { puts("XShmQueryVersion failed"); return 3; }
    printf("shm %d.%d pixmaps=%d\n", maj, min, pix);
    int scr = DefaultScreen(d);
    int w = 64, h = 48;
    Window win = XCreateSimpleWindow(d, RootWindow(d, scr), 0, 0, w, h, 0, 0, 0);
    XMapWindow(d, win);
    XSync(d, False);
    GC gc = XCreateGC(d, win, 0, NULL);
    XShmSegmentInfo si;
    XImage *img = XShmCreateImage(d, DefaultVisual(d, scr), DefaultDepth(d, scr), ZPixmap, NULL, &si, w, h);
    if (!img) { puts("XShmCreateImage failed"); return 4; }
    si.shmid = shmget(IPC_PRIVATE, img->bytes_per_line * img->height, IPC_CREAT | 0600);
    si.shmaddr = img->data = shmat(si.shmid, NULL, 0);
    si.readOnly = False;
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++)
            ((unsigned *)img->data)[y * (img->bytes_per_line / 4) + x] = ((x * 4) << 16) | ((y * 5) << 8) | ((x + y) & 0xff);
    if (!XShmAttach(d, &si)) { puts("XShmAttach failed"); return 5; }
    XSync(d, False);
    XShmPutImage(d, win, gc, img, 0, 0, 0, 0, w, h, False);
    XSync(d, False);
    XImage *back = XGetImage(d, win, 0, 0, w, h, AllPlanes, ZPixmap);
    if (!back) { puts("XGetImage failed"); return 6; }
    unsigned long sum = 0;
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++)
            sum = sum * 31 + (XGetPixel(back, x, y) & 0xffffff);
    printf("checksum %lu\n", sum);
    XShmDetach(d, &si);
    XSync(d, False);
    shmdt(si.shmaddr);
    shmctl(si.shmid, IPC_RMID, NULL);
    puts("ok");
    return 0;
}
