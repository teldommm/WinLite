package com.winlator.cmod.sysvshm;

import android.os.SharedMemory;
import android.system.ErrnoException;
import android.util.SparseArray;

import com.winlator.cmod.xconnector.XConnectorEpoll;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

public class SysVSharedMemory {
    private final SparseArray<SHMemory> shmemories = new SparseArray<>();
    private int maxSHMemoryId = 0;

    static {
        System.loadLibrary("winlator");
    }

    /*
     * A segment is used from two sides that know nothing of each other: the guest (shmget/shmat/
     * shmctl over the SysV SHM socket, on that connector's thread) and the X server (MIT-SHM
     * Attach/Detach on the X thread). The guest's DELETE (sent once it has detached after
     * IPC_RMID) can arrive before the X server has processed the client's ShmDetach: Xlib only
     * queues that request, and shmdt follows at once. The segment used to be dropped right
     * there, so the X server's later detach found nothing and its mapping was never unmapped -
     * a leak the size of the image (a window surface) every time. A segment now lives until
     * both sides are done with it: deleted by the guest AND detached by every X attachment.
     */
    private static class SHMemory {
        private int fd;
        private long size;
        private ByteBuffer data;      // the X server's mapping, shared by its attachments
        private int attachments;      // live X server attachments (MIT-SHM Attach - Detach)
        private boolean deleted;      // the guest removed it (IPC_RMID, last shmdt)
    }

    public int getFd(int shmid) {
        synchronized (shmemories) {
            SHMemory shmemory = shmemories.get(shmid);
            return shmemory != null && !shmemory.deleted ? shmemory.fd : -1;
        }
    }

    public int get(long size) {
        synchronized (shmemories) {
            int index = shmemories.size();
            int fd = ashmemCreateRegion(index, size);
            if (fd < 0) fd = createSharedMemory("sysvshm-"+index, (int)size);
            if (fd < 0) return -1;

            SHMemory shmemory = new SHMemory();
            int id = ++maxSHMemoryId;
            shmemory.fd = fd;
            shmemory.size = size;
            shmemories.put(id, shmemory);
            return id;
        }
    }

    /** The guest removed the segment. Freed now, or once the X server has detached from it. */
    public void delete(int shmid) {
        synchronized (shmemories) {
            SHMemory shmemory = shmemories.get(shmid);
            if (shmemory == null) return;
            shmemory.deleted = true;
            // The X server's mapping does not need the fd; nobody can attach a removed segment.
            closeFd(shmemory);
            if (shmemory.attachments <= 0) free(shmid, shmemory);
        }
    }

    /** As if the guest deleted every segment: a mapping the X server still has attached is not
     *  pulled from under it (it may be stopped after this component); it goes on its detach. */
    public void deleteAll() {
        synchronized (shmemories) {
            for (int i = shmemories.size() - 1; i >= 0; i--) delete(shmemories.keyAt(i));
        }
    }

    /** An X server attachment (MIT-SHM Attach). Pair every non-null return with detach(shmid). */
    public ByteBuffer attach(int shmid) {
        synchronized (shmemories) {
            SHMemory shmemory = shmemories.get(shmid);
            if (shmemory == null || shmemory.deleted) return null;
            if (shmemory.data == null) {
                if (shmemory.fd < 0) return null;
                shmemory.data = mapSHMSegment(shmemory.fd, shmemory.size, 0, false);
                if (shmemory.data == null) return null;
            }
            shmemory.attachments++;
            return shmemory.data;
        }
    }

    /** Ends one X server attachment of the segment (MIT-SHM Detach, or its client going away). */
    public void detach(int shmid) {
        synchronized (shmemories) {
            SHMemory shmemory = shmemories.get(shmid);
            if (shmemory == null || shmemory.attachments <= 0) return;
            if (--shmemory.attachments > 0) return;
            unmap(shmemory);
            if (shmemory.deleted) free(shmid, shmemory);
        }
    }

    private void free(int shmid, SHMemory shmemory) {
        closeFd(shmemory);
        unmap(shmemory);
        shmemories.remove(shmid);
    }

    private static void closeFd(SHMemory shmemory) {
        if (shmemory.fd != -1) {
            XConnectorEpoll.closeFd(shmemory.fd);
            shmemory.fd = -1;
        }
    }

    private static void unmap(SHMemory shmemory) {
        if (shmemory.data != null) {
            unmapSHMSegment(shmemory.data, shmemory.size);
            shmemory.data = null;
        }
    }

    private static int createSharedMemory(String name, int size) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                SharedMemory sharedMemory = SharedMemory.create(name, size);
                try {
                    Method method = sharedMemory.getClass().getMethod("getFd");
                    Object ret = method.invoke(sharedMemory);
                    if (ret != null) return (int)ret;
                }
                catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {}
            }
        }
        catch (ErrnoException e) {}
        return -1;
    }

    public static native int createMemoryFd(String name, int size);

    private static native int ashmemCreateRegion(int index, long size);

    public static native ByteBuffer mapSHMSegment(int fd, long size, int offset, boolean readonly);

    public static native void unmapSHMSegment(ByteBuffer data, long size);
}