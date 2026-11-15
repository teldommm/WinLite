/*
 * wl_proto.h - WinLite <-> Vulkan wrapper: the WL Direct protocol and the wl.control page.
 *
 * One header, two identical copies:
 *   WinLite  app/src/main/cpp/winlator/wl_proto.h  (Direct server, wl.control writer)
 *   mesa26   src/vulkan/wsi/wl_proto.h             (WL swapchain)
 * Change both together and bump the version of what changed.
 *
 * Plain C11 / C++17, no dependencies beyond libc. Bionic arm64 only (little endian).
 *
 * All times are CLOCK_MONOTONIC nanoseconds (Java's System.nanoTime(), std::chrono::steady_clock).
 *
 * SPDX-License-Identifier: MIT
 */
#ifndef WL_PROTO_H
#define WL_PROTO_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <errno.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/types.h>

#ifdef __cplusplus
#define WL_STATIC_ASSERT(c, m) static_assert(c, m)
extern "C" {
#else
#define WL_STATIC_ASSERT(c, m) _Static_assert(c, m)
#endif

WL_STATIC_ASSERT(__BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__, "wl_proto is little endian");

/* ------------------------------------------------------------------------------------------- */
/* Names shared by both sides                                                                  */
/* ------------------------------------------------------------------------------------------- */

/* Paths. WinLite sets both variables for the guest; without them the wrapper looks in $TMPDIR
 * (WinLite points it at <imagefs>/usr/tmp, next to .X11-unix). */
#define WL_DIRECT_SOCK_ENV       "WL_DIRECT_SOCK"
#define WL_CONTROL_PATH_ENV      "WL_CONTROL_PATH"
#define WL_DIRECT_SOCK_NAME      ".wl-direct"
#define WL_CONTROL_NAME          ".wl-control"

/* Set by the WL swapchain on its X window (XA_CARDINAL, 32 bit, value = protocol version).
 * xcore does not apply its Present FPS limit to a window that has it: the swapchain paces
 * itself. */
#define WL_SWAPCHAIN_PROPERTY    "_WL_SWAPCHAIN"

/* ------------------------------------------------------------------------------------------- */
/* WL Direct protocol, version 1                                                               */
/* ------------------------------------------------------------------------------------------- */
/*
 * Transport: AF_UNIX SOCK_SEQPACKET (one message per packet), one connection per swapchain; a new
 * swapchain opens a new connection. Several clients may be connected; at most one holds the grant.
 *
 * Every message starts with wl_direct_hdr; `size` is the size of the whole packet. File
 * descriptors travel as SCM_RIGHTS on the packet they belong to, at most one per packet.
 *
 * Connection:
 *   C->S HELLO, then exactly image_count packets sent with AHardwareBuffer_sendHandleToUnixSocket()
 *        in image index order (nothing else in between).
 *   S->C WELCOME: version accepted, the grant as of now. A version the server does not speak:
 *        it closes the connection, the client stays on X.
 *   S->C GRANT whenever the grant changes.
 *
 * Frames:
 *   C->S FRAME only while granted; otherwise the client presents through X (PresentPixmap).
 *   The client does not touch an image sent with FRAME until RELEASE gives it back. RELEASE may
 *   carry a release fence (sync_fd): the image is free once it signals (import it into the
 *   acquire semaphore of the image, or wait for it). No fence: free now.
 *   S->C TIMING per shown frame (latch / present time) for pacing.
 *
 * Revoke (GRANT granted=0): the client presents its latest image through X right away. The
 * server keeps the Direct layer up until the X composite shows a frame of this window, then
 * hides it, so the switch shows no gap. Images still held by the server come back with RELEASE
 * as usual.
 *
 * Errors: any malformed message, a broken socket or an unexpected state closes the connection.
 * The client then works through X and does not try to reconnect more often than
 * WL_DIRECT_RECONNECT_MS.
 */

#define WL_DIRECT_MAGIC          0x52444c57u   /* 'W','L','D','R' in memory order */
#define WL_DIRECT_VERSION        1u
#define WL_DIRECT_MAX_IMAGES     16u
#define WL_DIRECT_MAX_PACKET     64u
#define WL_DIRECT_RECONNECT_MS   2000u

enum wl_direct_type {
   WL_DIRECT_HELLO   = 1,   /* C->S */
   WL_DIRECT_WELCOME = 2,   /* S->C */
   WL_DIRECT_GRANT   = 3,   /* S->C */
   WL_DIRECT_FRAME   = 4,   /* C->S, + acquire sync_fd if WL_FRAME_HAS_FENCE */
   WL_DIRECT_RELEASE = 5,   /* S->C, + release sync_fd if WL_RELEASE_HAS_FENCE */
   WL_DIRECT_TIMING  = 6,   /* S->C */
   WL_DIRECT_BYE     = 7,   /* C->S */
};

/* Why the grant is what it is (logs, HUD). */
enum wl_direct_reason {
   WL_REASON_OK            = 0,   /* granted */
   WL_REASON_OFF           = 1,   /* output mode Off */
   WL_REASON_SETTLING      = 2,   /* conditions hold, hysteresis not over yet */
   WL_REASON_NO_WINDOW     = 3,   /* x_window unknown, unmapped or without content */
   WL_REASON_NOT_TOP       = 4,   /* another window with content is the top-most one */
   WL_REASON_WINDOWED      = 5,   /* does not cover the X screen */
   WL_REASON_OVERLAP       = 6,   /* a window over it (dialog, IME, menu) */
   WL_REASON_ROTATION      = 7,   /* rotation not a multiple of 90 degrees */
   WL_REASON_SCISSOR       = 8,   /* a crop HWC cannot repeat */
   WL_REASON_DETACHED      = 9,   /* the app's surface is gone (background, PiP switch) */
   WL_REASON_API           = 10,  /* SurfaceControl unavailable (API < 29) */
   WL_REASON_OTHER_CLIENT  = 11,  /* another swapchain holds the grant */
   WL_REASON_FORMAT        = 12,  /* buffers the server cannot show */
   WL_REASON_ERROR         = 13,  /* server side failure, see its log */
};

/* wl_direct_hello.flags */
#define WL_HELLO_UBWC            (1u << 0)   /* the images are UBWC compressed (VENDOR_0) */

/* wl_direct_frame.flags */
#define WL_FRAME_GENERATED       (1u << 0)   /* a frame generation output, not a game frame */
#define WL_FRAME_HAS_FENCE       (1u << 1)   /* an acquire sync_fd is attached */

/* wl_direct_release.flags */
#define WL_RELEASE_HAS_FENCE     (1u << 0)   /* a release sync_fd is attached */

struct wl_direct_hdr {
   uint16_t type;
   uint16_t size;          /* whole packet */
   uint32_t seq;           /* sender's message counter, for logs */
};

struct wl_direct_hello {
   struct wl_direct_hdr hdr;
   uint32_t magic;         /* WL_DIRECT_MAGIC */
   uint16_t version;       /* WL_DIRECT_VERSION */
   uint16_t flags;         /* WL_HELLO_* */
   uint32_t x_window;      /* the swapchain's X window: the server finds it in xcore's tree */
   uint32_t image_count;   /* 1..WL_DIRECT_MAX_IMAGES */
   uint32_t width;
   uint32_t height;
   uint32_t ahb_format;    /* AHARDWAREBUFFER_FORMAT_* (1 RGBA8888, 5 BGRA8888) */
   uint32_t pid;           /* the client process, for logs and wl.control status ownership */
   uint64_t ahb_usage;     /* AHARDWAREBUFFER_USAGE_* the images were allocated with */
};

struct wl_direct_welcome {
   struct wl_direct_hdr hdr;
   uint32_t client_id;
   uint32_t version;       /* accepted */
   uint32_t granted;       /* 0 / 1 */
   uint32_t reason;        /* enum wl_direct_reason */
};

struct wl_direct_grant {
   struct wl_direct_hdr hdr;
   uint32_t granted;       /* 0 / 1 */
   uint32_t reason;        /* enum wl_direct_reason */
   /* Where the game lands on the panel (physical pixels), for information and FG timing. */
   int32_t  panel_x, panel_y, panel_w, panel_h;
   uint64_t refresh_ns;    /* display refresh period, 0 = unknown */
};

struct wl_direct_frame {
   struct wl_direct_hdr hdr;
   uint32_t image_index;
   uint32_t flags;         /* WL_FRAME_* */
   uint64_t frame_id;      /* increases by one per FRAME of this connection */
   uint64_t desired_present_ns;   /* 0 = as soon as possible */
};

struct wl_direct_release {
   struct wl_direct_hdr hdr;
   uint32_t image_index;
   uint32_t flags;         /* WL_RELEASE_* */
};

struct wl_direct_timing {
   struct wl_direct_hdr hdr;
   uint64_t frame_id;
   uint64_t latch_ns;      /* SurfaceFlinger latched it, 0 = unknown */
   uint64_t present_ns;    /* it reached the display, 0 = unknown / dropped */
};

struct wl_direct_bye {
   struct wl_direct_hdr hdr;
};

WL_STATIC_ASSERT(sizeof(struct wl_direct_hdr)     == 8,  "wl_direct_hdr");
WL_STATIC_ASSERT(sizeof(struct wl_direct_hello)   == 48, "wl_direct_hello");
WL_STATIC_ASSERT(offsetof(struct wl_direct_hello, ahb_usage) == 40, "wl_direct_hello");
WL_STATIC_ASSERT(sizeof(struct wl_direct_welcome) == 24, "wl_direct_welcome");
WL_STATIC_ASSERT(sizeof(struct wl_direct_grant)   == 40, "wl_direct_grant");
WL_STATIC_ASSERT(offsetof(struct wl_direct_grant, refresh_ns) == 32, "wl_direct_grant");
WL_STATIC_ASSERT(sizeof(struct wl_direct_frame)   == 32, "wl_direct_frame");
WL_STATIC_ASSERT(sizeof(struct wl_direct_release) == 16, "wl_direct_release");
WL_STATIC_ASSERT(sizeof(struct wl_direct_timing)  == 32, "wl_direct_timing");
WL_STATIC_ASSERT(sizeof(struct wl_direct_bye)     == 8,  "wl_direct_bye");

/* Exact packet size of a message type, 0 for an unknown type. */
static inline size_t
wl_direct_msg_size(uint16_t type)
{
   switch (type) {
   case WL_DIRECT_HELLO:   return sizeof(struct wl_direct_hello);
   case WL_DIRECT_WELCOME: return sizeof(struct wl_direct_welcome);
   case WL_DIRECT_GRANT:   return sizeof(struct wl_direct_grant);
   case WL_DIRECT_FRAME:   return sizeof(struct wl_direct_frame);
   case WL_DIRECT_RELEASE: return sizeof(struct wl_direct_release);
   case WL_DIRECT_TIMING:  return sizeof(struct wl_direct_timing);
   case WL_DIRECT_BYE:     return sizeof(struct wl_direct_bye);
   default:                return 0;
   }
}

/* Only these may carry a descriptor. */
static inline bool
wl_direct_msg_takes_fd(uint16_t type)
{
   return type == WL_DIRECT_FRAME || type == WL_DIRECT_RELEASE;
}

static inline void
wl_direct_hdr_init(struct wl_direct_hdr *h, uint16_t type, uint32_t seq)
{
   h->type = type;
   h->size = (uint16_t)wl_direct_msg_size(type);
   h->seq = seq;
}

/*
 * Sends one message (h->size bytes), with `fd` attached unless it is negative. The caller keeps
 * its descriptor (the kernel duplicates it). Returns 0, or -errno. Never raises SIGPIPE.
 */
static inline int
wl_direct_send(int sock, const struct wl_direct_hdr *h, int fd)
{
   struct iovec iov;
   struct msghdr msg;
   union { char buf[CMSG_SPACE(sizeof(int))]; struct cmsghdr align; } ctl;

   iov.iov_base = (void *)h;
   iov.iov_len = h->size;
   memset(&msg, 0, sizeof(msg));
   msg.msg_iov = &iov;
   msg.msg_iovlen = 1;
   if (fd >= 0) {
      struct cmsghdr *c;
      memset(&ctl, 0, sizeof(ctl));
      msg.msg_control = ctl.buf;
      msg.msg_controllen = sizeof(ctl.buf);
      c = CMSG_FIRSTHDR(&msg);
      c->cmsg_level = SOL_SOCKET;
      c->cmsg_type = SCM_RIGHTS;
      c->cmsg_len = CMSG_LEN(sizeof(int));
      memcpy(CMSG_DATA(c), &fd, sizeof(int));
   }
   for (;;) {
      ssize_t n = sendmsg(sock, &msg, MSG_NOSIGNAL);
      if (n == (ssize_t)h->size)
         return 0;
      if (n < 0 && errno == EINTR)
         continue;
      return n < 0 ? -errno : -EMSGSIZE;
   }
}

/*
 * Receives one message into `buf` (at least WL_DIRECT_MAX_PACKET bytes) and checks it: known
 * type, exact size, a descriptor only where one may be. *fd gets the attached descriptor (the
 * caller owns it) or -1. Returns the message size, 0 when the peer closed, or -errno
 * (-EAGAIN on a non-blocking socket with nothing to read, -EPROTO for a malformed message).
 * Descriptors that come with a malformed message are closed.
 */
static inline ssize_t
wl_direct_recv(int sock, void *buf, int *fd)
{
   struct iovec iov;
   struct msghdr msg;
   union { char buf[CMSG_SPACE(sizeof(int) * 4)]; struct cmsghdr align; } ctl;
   int got[4];
   int ngot = 0;
   ssize_t n;
   struct cmsghdr *c;

   *fd = -1;
   iov.iov_base = buf;
   iov.iov_len = WL_DIRECT_MAX_PACKET;
   memset(&msg, 0, sizeof(msg));
   msg.msg_iov = &iov;
   msg.msg_iovlen = 1;
   msg.msg_control = ctl.buf;
   msg.msg_controllen = sizeof(ctl.buf);

   do {
      n = recvmsg(sock, &msg, MSG_CMSG_CLOEXEC);
   } while (n < 0 && errno == EINTR);
   if (n < 0)
      return -errno;

   for (c = CMSG_FIRSTHDR(&msg); c; c = CMSG_NXTHDR(&msg, c)) {
      if (c->cmsg_level != SOL_SOCKET || c->cmsg_type != SCM_RIGHTS)
         continue;
      size_t cnt = (c->cmsg_len - CMSG_LEN(0)) / sizeof(int);
      for (size_t i = 0; i < cnt && ngot < 4; i++)
         memcpy(&got[ngot++], CMSG_DATA(c) + i * sizeof(int), sizeof(int));
   }
   if (n == 0) {
      for (int i = 0; i < ngot; i++)
         close(got[i]);
      return 0;
   }

   const struct wl_direct_hdr *h = (const struct wl_direct_hdr *)buf;
   const bool bad = (msg.msg_flags & (MSG_TRUNC | MSG_CTRUNC)) ||
                    (size_t)n < sizeof(*h) ||
                    wl_direct_msg_size(h->type) == 0 ||
                    h->size != (size_t)n ||
                    wl_direct_msg_size(h->type) != (size_t)n ||
                    ngot > 1 ||
                    (ngot == 1 && !wl_direct_msg_takes_fd(h->type));
   if (bad) {
      for (int i = 0; i < ngot; i++)
         close(got[i]);
      return -EPROTO;
   }
   if (ngot == 1)
      *fd = got[0];
   return n;
}

/* ------------------------------------------------------------------------------------------- */
/* wl.control, version 1: a 128-byte file both sides mmap MAP_SHARED                           */
/* ------------------------------------------------------------------------------------------- */
/*
 * Three regions, each behind its own seqlock (odd while it is being written), each with one
 * writer:
 *   settings  WinLite (libwinlator, JNI)   what the user chose
 *   clock     WinLite (renderer)           the display's refresh grid
 *   status    wrapper (WL swapchain)       what the swapchain does
 * Regions are copied as 32-bit words with relaxed atomics between the sequence reads, so no
 * reader ever sees a torn value. A wrapper that finds another magic, version or size ignores
 * the file and runs on its defaults.
 *
 * Status ownership with several WL swapchains: the one holding the Direct grant writes, else the
 * one that presented last; `pid` says whose numbers they are.
 */

#define WL_CONTROL_MAGIC         0x54434c57u   /* 'W','L','C','T' in memory order */
#define WL_CONTROL_VERSION       1u
#define WL_CONTROL_SIZE          128u

enum wl_output_mode {
   WL_OUTPUT_OFF    = 0,   /* X only */
   WL_OUTPUT_AUTO   = 1,   /* Direct when the admission rule holds */
   WL_OUTPUT_ALWAYS = 2,   /* Direct whenever possible, windows over the game ignored */
};

enum wl_fg_mode {
   WL_FG_OFF    = 0,
   WL_FG_FIXED  = 1,       /* fg_multiplier outputs per game frame */
   WL_FG_TARGET = 2,       /* chase fg_target_fps */
};

/* wl_control_settings.fg_flags */
#define WL_FG_FAST_INTERP        (1u << 0)

enum wl_sink {
   WL_SINK_NONE   = 0,
   WL_SINK_X      = 1,
   WL_SINK_DIRECT = 2,
};

enum wl_fg_state {
   WL_FG_STATE_OFF    = 0,
   WL_FG_STATE_ACTIVE = 1,
   WL_FG_STATE_PAUSED = 2, /* below fg_min_fps (adaptive mode), waiting for fg_resume_fps */
};

struct wl_control_settings {
   uint8_t  output_mode;     /* enum wl_output_mode */
   uint8_t  fg_mode;         /* enum wl_fg_mode */
   uint8_t  fg_multiplier;   /* 2..4, WL_FG_FIXED */
   uint8_t  fg_flags;        /* WL_FG_* */
   uint16_t fps_limit;       /* game frames per second, 0 = none */
   uint16_t fg_target_fps;   /* WL_FG_TARGET, 0 = the display refresh rate */
   uint16_t fg_flow_side;    /* px on the shorter side the motion is analysed at, 0 = default */
   uint16_t fg_min_fps;      /* adaptive: FG pauses below this source rate, 0 = no floor */
   uint16_t fg_resume_fps;   /* adaptive: and resumes above this one (>= fg_min_fps) */
   uint16_t reserved[9];
};

struct wl_control_clock {
   uint64_t period_ns;       /* refresh period, 0 = unknown */
   uint64_t anchor_ns;       /* a refresh the display really showed a frame at */
};

struct wl_control_status {
   uint32_t pid;             /* whose status this is */
   uint32_t sink;            /* enum wl_sink */
   uint32_t fg_state;        /* enum wl_fg_state */
   uint32_t reason;          /* enum wl_direct_reason of the latest grant */
   uint64_t real_frames;     /* game frames presented */
   uint64_t generated_frames;
   uint64_t dropped_frames;
};

struct wl_control {
   uint32_t magic;           /*   0 */
   uint16_t version;         /*   4 */
   uint16_t size;            /*   6 */
   uint32_t reserved0[2];    /*   8 */
   uint32_t settings_seq;    /*  16 */
   uint32_t reserved1;       /*  20 */
   struct wl_control_settings settings;   /*  24..55 */
   uint32_t clock_seq;       /*  56 */
   uint32_t reserved2;       /*  60 */
   struct wl_control_clock clock;         /*  64..79 */
   uint32_t status_seq;      /*  80 */
   uint32_t reserved3;       /*  84 */
   struct wl_control_status status;       /*  88..127 */
};

WL_STATIC_ASSERT(sizeof(struct wl_control_settings) == 32, "wl_control_settings");
WL_STATIC_ASSERT(sizeof(struct wl_control_clock)    == 16, "wl_control_clock");
WL_STATIC_ASSERT(sizeof(struct wl_control_status)   == 40, "wl_control_status");
WL_STATIC_ASSERT(offsetof(struct wl_control, settings_seq) == 16, "wl_control");
WL_STATIC_ASSERT(offsetof(struct wl_control, settings) == 24, "wl_control");
WL_STATIC_ASSERT(offsetof(struct wl_control, clock_seq) == 56, "wl_control");
WL_STATIC_ASSERT(offsetof(struct wl_control, clock) == 64, "wl_control");
WL_STATIC_ASSERT(offsetof(struct wl_control, status_seq) == 80, "wl_control");
WL_STATIC_ASSERT(offsetof(struct wl_control, status) == 88, "wl_control");
WL_STATIC_ASSERT(sizeof(struct wl_control) == WL_CONTROL_SIZE, "wl_control");

/* -- seqlock -------------------------------------------------------------------------------- */

static inline void
wl_words_store(void *dst, const void *src, size_t bytes)
{
   uint32_t *d = (uint32_t *)dst;
   const uint32_t *s = (const uint32_t *)src;
   for (size_t i = 0; i < bytes / 4; i++)
      __atomic_store_n(&d[i], s[i], __ATOMIC_RELAXED);
}

static inline void
wl_words_load(void *dst, const void *src, size_t bytes)
{
   uint32_t *d = (uint32_t *)dst;
   const uint32_t *s = (const uint32_t *)src;
   for (size_t i = 0; i < bytes / 4; i++)
      d[i] = __atomic_load_n(&s[i], __ATOMIC_RELAXED);
}

/* Single writer per region. */
static inline void
wl_seq_write(uint32_t *seq, void *region, const void *value, size_t bytes)
{
   const uint32_t s = __atomic_load_n(seq, __ATOMIC_RELAXED);
   __atomic_store_n(seq, s + 1, __ATOMIC_RELAXED);
   __atomic_thread_fence(__ATOMIC_RELEASE);
   wl_words_store(region, value, bytes);
   __atomic_store_n(seq, s + 2, __ATOMIC_RELEASE);
}

/* false when no consistent copy was had in a few tries (a writer stuck mid-write). *seq_out,
 * if given, gets the sequence the copy belongs to (changes with every write). */
static inline bool
wl_seq_read(const uint32_t *seq, const void *region, void *out, size_t bytes, uint32_t *seq_out)
{
   for (int tries = 0; tries < 64; tries++) {
      const uint32_t s1 = __atomic_load_n(seq, __ATOMIC_ACQUIRE);
      if (s1 & 1u)
         continue;
      wl_words_load(out, region, bytes);
      __atomic_thread_fence(__ATOMIC_ACQUIRE);
      if (__atomic_load_n(seq, __ATOMIC_RELAXED) == s1) {
         if (seq_out)
            *seq_out = s1;
         return true;
      }
   }
   return false;
}

/* -- the page ------------------------------------------------------------------------------- */

/* The creator (WinLite) fills a fresh page: everything zero, then the identity last. */
static inline void
wl_control_init(struct wl_control *c)
{
   memset((void *)c, 0, sizeof(*c));
   __atomic_store_n(&c->size, (uint16_t)WL_CONTROL_SIZE, __ATOMIC_RELAXED);
   __atomic_store_n(&c->version, (uint16_t)WL_CONTROL_VERSION, __ATOMIC_RELAXED);
   __atomic_store_n(&c->magic, WL_CONTROL_MAGIC, __ATOMIC_RELEASE);
}

static inline bool
wl_control_valid(const struct wl_control *c)
{
   return c &&
          __atomic_load_n(&c->magic, __ATOMIC_ACQUIRE) == WL_CONTROL_MAGIC &&
          __atomic_load_n(&c->version, __ATOMIC_RELAXED) == WL_CONTROL_VERSION &&
          __atomic_load_n(&c->size, __ATOMIC_RELAXED) == WL_CONTROL_SIZE;
}

static inline void
wl_control_write_settings(struct wl_control *c, const struct wl_control_settings *v)
{
   wl_seq_write(&c->settings_seq, &c->settings, v, sizeof(*v));
}

static inline bool
wl_control_read_settings(const struct wl_control *c, struct wl_control_settings *v, uint32_t *seq)
{
   return wl_seq_read(&c->settings_seq, &c->settings, v, sizeof(*v), seq);
}

static inline void
wl_control_write_clock(struct wl_control *c, const struct wl_control_clock *v)
{
   wl_seq_write(&c->clock_seq, &c->clock, v, sizeof(*v));
}

static inline bool
wl_control_read_clock(const struct wl_control *c, struct wl_control_clock *v, uint32_t *seq)
{
   return wl_seq_read(&c->clock_seq, &c->clock, v, sizeof(*v), seq);
}

static inline void
wl_control_write_status(struct wl_control *c, const struct wl_control_status *v)
{
   wl_seq_write(&c->status_seq, &c->status, v, sizeof(*v));
}

static inline bool
wl_control_read_status(const struct wl_control *c, struct wl_control_status *v, uint32_t *seq)
{
   return wl_seq_read(&c->status_seq, &c->status, v, sizeof(*v), seq);
}

#ifdef __cplusplus
}
#endif

#endif /* WL_PROTO_H */
