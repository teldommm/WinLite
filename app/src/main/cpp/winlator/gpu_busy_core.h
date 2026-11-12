/*
 * GPU busy sampling on Adreno without root: the pure part (no Android, no I/O), so it can be tested
 *
 * What it relies on. The KGSL driver lets any client map the device's "memstore" read-only
 * (KGSL_PROP_DEVICE_SHADOW). It is an array of fixed-size entries, ten 32-bit words each. In every
 * entry word 0 holds the start-of-pipe timestamp and word 2 the end-of-pipe timestamp of the last
 * submission of whoever owns the entry; the GPU writes the first before the submission's commands
 * and the second after them. So "start != end" means that owner has work on the GPU right now.
 * Sampling that often enough gives the busy share of the GPU as busy samples / samples.
 *
 * Two layouts are seen, depending on how the kernel schedules the GPU:
 *  - ringbuffer kernels: the owners are the hardware ringbuffers, whose entries sit just below the
 *    end of the memstore (the last six entries hold five of them, depending on whether the kernel
 *    has the LPAC slot);
 *  - hardware-scheduled kernels (the firmware schedules, newer SoCs): the ringbuffer entries stay
 *    zero and each GPU context's own entry is written instead. Context ids start at 1 and use the
 *    entries below the ringbuffer ones; a context's entry is zeroed when it is created or destroyed.
 */
#ifndef WINLATOR_GPU_BUSY_CORE_H
#define WINLATOR_GPU_BUSY_CORE_H

#include <stddef.h>
#include <stdint.h>

#define GB_ENTRY_WORDS   10
#define GB_WORD_START    0
#define GB_WORD_END      2
#define GB_RB_SLOTS      5     /* ringbuffer entries watched */
#define GB_MAX_WATCH     64    /* entries watched at most (context mode) */

/* The entries that are being watched and the start stamp each had at the previous sample. */
struct gb_watch {
    int count;
    int index[GB_MAX_WATCH];
    uint32_t last_start[GB_MAX_WATCH];
};

static inline const volatile uint32_t *gb_entry(const volatile uint32_t *store, int index)
{
    return store + (size_t)index * GB_ENTRY_WORDS;
}

/* Index of the first ringbuffer entry, or -1 when the memstore is too small to be the real one. */
static inline int gb_ringbuffer_base(size_t store_bytes)
{
    size_t entries = store_bytes / (GB_ENTRY_WORDS * sizeof(uint32_t));
    return entries < 8 ? -1 : (int)(entries - 6);
}

/* Is any ringbuffer entry in use (a start stamp was ever written)? Picks the layout at open. */
static inline int gb_ringbuffer_in_use(const volatile uint32_t *store, int base)
{
    for (int i = 0; i < GB_RB_SLOTS; i++)
        if (gb_entry(store, base + i)[GB_WORD_START] != 0) return 1;
    return 0;
}

/* Watch the ringbuffer entries. */
static inline void gb_watch_ringbuffers(struct gb_watch *w, const volatile uint32_t *store, int base)
{
    w->count = GB_RB_SLOTS;
    for (int i = 0; i < GB_RB_SLOTS; i++) {
        w->index[i] = base + i;
        w->last_start[i] = gb_entry(store, base + i)[GB_WORD_START];
    }
}

static inline int gb_watch_find(const struct gb_watch *w, int index)
{
    for (int i = 0; i < w->count; i++)
        if (w->index[i] == index) return i;
    return -1;
}

/*
 * Context mode: rebuild the list from the entries below the ringbuffer ones that hold a start
 * stamp. Entries that look active (work in flight, or the start stamp changed since the last
 * sample) are taken first, so the cap only ever drops idle ones. Entries that stay in the list keep
 * their remembered stamp; a new one starts from what it holds now.
 */
static inline void gb_watch_contexts(struct gb_watch *w, const volatile uint32_t *store, int base)
{
    int index[GB_MAX_WATCH];
    uint32_t last[GB_MAX_WATCH];
    int n = 0;

    for (int pass = 0; pass < 2; pass++) {
        int want_active = pass == 0;
        for (int id = 1; id < base && n < GB_MAX_WATCH; id++) {
            const volatile uint32_t *e = gb_entry(store, id);
            uint32_t start = e[GB_WORD_START], end = e[GB_WORD_END];
            if (start == 0) continue;
            int known = gb_watch_find(w, id);
            int active = start != end || (known >= 0 && w->last_start[known] != start);
            if (active != want_active) continue;
            index[n] = id;
            last[n] = known >= 0 ? w->last_start[known] : start;
            n++;
        }
    }
    w->count = n;
    for (int i = 0; i < n; i++) {
        w->index[i] = index[i];
        w->last_start[i] = last[i];
    }
}

/*
 * One sample: returns 1 when a watched entry has a submission in flight. *moved is set when a start
 * stamp differs from the previous sample's (proof that the stamps are alive).
 */
static inline int gb_sample(const volatile uint32_t *store, struct gb_watch *w, int *moved)
{
    int busy = 0;
    for (int i = 0; i < w->count; i++) {
        const volatile uint32_t *e = gb_entry(store, w->index[i]);
        uint32_t start = e[GB_WORD_START], end = e[GB_WORD_END];
        if (start != end) busy = 1;
        if (start != w->last_start[i]) {
            *moved = 1;
            w->last_start[i] = start;
        }
    }
    return busy;
}

#endif /* WINLATOR_GPU_BUSY_CORE_H */
