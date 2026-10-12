/*
 * libssbs.so - keep PSTATE.SSBS set across signal returns (LD_PRELOAD).
 *
 * Problem: Wine's arm64 signal code rebuilds PSTATE from a Windows CONTEXT whose Cpsr has no SSBS
 * bit (bit 12). Every signal return that goes through that code (exception fixups,
 * NtSetContextThread on another thread, the SIGUSR2 suspend path) therefore resumes the thread
 * with SSBS clear, i.e. with speculative store bypass disabled, which is the slow mode. Layers
 * built with the ntdll fix (Wine MR 12262) preserve the bit themselves; this library does the
 * same from the outside for layers that cannot be rebuilt.
 *
 * Idea: every handler the program installs through sigaction()/signal() is installed behind one
 * SA_SIGINFO trampoline. The trampoline calls the program's real handler and then sets SSBS in
 * the ucontext the kernel is about to restore. The kernel accepts SSBS from userspace on
 * sigreturn and keeps it per thread. Handlers that never return (siglongjmp) are unaffected.
 *
 * Approach credit: Sonicadvance1 (FEX-Emu) force_ssbs.diff; upstream fix: Wine MR 12262.
 *
 * Environment:
 *   SSBS_ADAPTER_DISABLE=1  pass everything through untouched.
 *   SSBS_ADAPTER_DEBUG=1    log load state and how many returns had SSBS cleared.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ucontext.h>

#ifndef SSBS_PSTATE_FIELD
#define SSBS_PSTATE_FIELD uc_mcontext.pstate /* overridable only for host-side tests */
#endif
#define SSBS_BIT ((unsigned long long)1 << 12)
#define SLOT_COUNT 65

typedef void (*info_handler_t)(int, siginfo_t *, void *);
typedef void (*plain_handler_t)(int);
typedef int (*sigaction_fn_t)(int, const struct sigaction *, struct sigaction *);

/*
 * Per signal, the handler the program asked for. Function pointers are at least 4-byte aligned,
 * so bit 0 carries "was installed with SA_SIGINFO". Handler and flag live in one word so the
 * trampoline always sees a consistent pair.
 */
#define INFO_FLAG ((uintptr_t)1)
static _Atomic uintptr_t program_handler[SLOT_COUNT];

static sigaction_fn_t libc_sigaction;
static int passthrough;
static int debug;
static atomic_ulong returns_seen, returns_fixed;

static void ensure_init(void)
{
    if (libc_sigaction) return;
    const char *v = getenv("SSBS_ADAPTER_DISABLE");
    passthrough = v && v[0] == '1';
    v = getenv("SSBS_ADAPTER_DEBUG");
    debug = v && v[0] == '1';
    libc_sigaction = (sigaction_fn_t)dlsym(RTLD_NEXT, "sigaction");
}

__attribute__((constructor)) static void on_load(void)
{
    ensure_init();
    if (debug) fprintf(stderr, "libssbs: loaded (%s)\n", passthrough ? "disabled" : "active");
}

static void trampoline(int sig, siginfo_t *info, void *context)
{
    uintptr_t word = atomic_load_explicit(&program_handler[sig], memory_order_acquire);
    void *fn = (void *)(word & ~INFO_FLAG);

    if (fn) {
        if (word & INFO_FLAG) ((info_handler_t)fn)(sig, info, context);
        else ((plain_handler_t)fn)(sig);
    }

    ucontext_t *uc = context;
    if (debug) {
        unsigned long seen = atomic_fetch_add(&returns_seen, 1) + 1;
        if (!((unsigned long long)uc->SSBS_PSTATE_FIELD & SSBS_BIT)) {
            unsigned long fixed = atomic_fetch_add(&returns_fixed, 1) + 1;
            if ((fixed & (fixed - 1)) == 0) /* 1, 2, 4, 8, ... */
                fprintf(stderr, "libssbs: signal %d returned with SSBS clear (%lu of %lu returns)\n",
                        sig, fixed, seen);
        }
    }
    uc->SSBS_PSTATE_FIELD |= SSBS_BIT;
}

static int is_real_handler(const void *h)
{
    return h != (const void *)SIG_DFL && h != (const void *)SIG_IGN && h != (const void *)SIG_ERR;
}

int sigaction(int sig, const struct sigaction *act, struct sigaction *oldact)
{
    ensure_init();
    if (!libc_sigaction) {
        errno = ENOSYS;
        return -1;
    }
    if (passthrough || sig <= 0 || sig >= SLOT_COUNT)
        return libc_sigaction(sig, act, oldact);

    uintptr_t previous = atomic_load(&program_handler[sig]);
    struct sigaction wrapped;
    const struct sigaction *install = act;

    if (act) {
        int has_info = (act->sa_flags & SA_SIGINFO) != 0;
        void *fn = has_info ? (void *)act->sa_sigaction : (void *)act->sa_handler;
        uintptr_t word = 0;

        if (is_real_handler(fn)) {
            word = (uintptr_t)fn | (has_info ? INFO_FLAG : 0);
            wrapped = *act;
            wrapped.sa_flags |= SA_SIGINFO;
            wrapped.sa_sigaction = trampoline;
            install = &wrapped;
        }
        /* Publish before installing so the trampoline never runs against a stale entry. */
        atomic_store_explicit(&program_handler[sig], word, memory_order_release);
    }

    int ret = libc_sigaction(sig, install, oldact);
    if (ret != 0) {
        if (act) atomic_store(&program_handler[sig], previous);
        return ret;
    }

    /* Hand the program its own handler back, not the trampoline, so chaining keeps working. */
    if (oldact && (oldact->sa_flags & SA_SIGINFO) && oldact->sa_sigaction == trampoline && previous) {
        void *fn = (void *)(previous & ~INFO_FLAG);
        if (previous & INFO_FLAG) {
            oldact->sa_sigaction = (info_handler_t)fn;
        } else {
            oldact->sa_flags &= ~SA_SIGINFO;
            oldact->sa_handler = (plain_handler_t)fn;
        }
    }
    return 0;
}

sighandler_t signal(int sig, sighandler_t handler)
{
    struct sigaction sa, old;

    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = handler;
    sa.sa_flags = SA_RESTART;
    sigemptyset(&sa.sa_mask);
    if (sigaction(sig, &sa, &old) != 0) return SIG_ERR;
    return (old.sa_flags & SA_SIGINFO) ? (sighandler_t)(void *)old.sa_sigaction : old.sa_handler;
}

sighandler_t bsd_signal(int sig, sighandler_t handler)
{
    return signal(sig, handler);
}
