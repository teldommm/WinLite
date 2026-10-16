package com.winlator.cmod.xserver;

import android.util.SparseArray;

import com.winlator.cmod.sysvshm.SysVSharedMemory;

import java.nio.ByteBuffer;

/**
 * MIT-SHM segments attached by X clients. Guarded by the SHMSEGMENT_MANAGER lock.
 *
 * Each attachment remembers its shmid and its client: Detach releases exactly that attachment
 * (it used to look the segment up by its ByteBuffer, which no longer worked once the guest had
 * deleted the segment), and a client that disconnects without detaching - Xlib never detaches
 * on exit - has its attachments released instead of keeping their mappings for the session.
 */
public class SHMSegmentManager {
    private static final class Segment {
        final int shmid;
        final ByteBuffer data;
        final XClient owner;

        Segment(int shmid, ByteBuffer data, XClient owner) {
            this.shmid = shmid;
            this.data = data;
            this.owner = owner;
        }
    }

    private final SysVSharedMemory sysVSharedMemory;
    private final SparseArray<Segment> shmSegments = new SparseArray<>();

    public SHMSegmentManager(SysVSharedMemory sysVSharedMemory) {
        this.sysVSharedMemory = sysVSharedMemory;
    }

    public void attach(XClient client, int xid, int shmid) {
        if (shmSegments.indexOfKey(xid) >= 0) detach(xid);
        ByteBuffer data = sysVSharedMemory.attach(shmid);
        if (data != null) shmSegments.put(xid, new Segment(shmid, data, client));
    }

    public void detach(int xid) {
        Segment segment = shmSegments.get(xid);
        if (segment != null) {
            shmSegments.remove(xid);
            sysVSharedMemory.detach(segment.shmid);
        }
    }

    /** Releases every segment the client still has attached. */
    public void detachAll(XClient client) {
        for (int i = shmSegments.size() - 1; i >= 0; i--) {
            Segment segment = shmSegments.valueAt(i);
            if (segment.owner == client) {
                shmSegments.removeAt(i);
                sysVSharedMemory.detach(segment.shmid);
            }
        }
    }

    public ByteBuffer getData(int xid) {
        Segment segment = shmSegments.get(xid);
        return segment != null ? segment.data : null;
    }
}
