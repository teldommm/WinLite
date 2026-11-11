package com.winlator.cmod.xconnector;

import android.util.Log;


import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

public class XOutputStream {
    public ByteBuffer buffer;
    public final ClientSocket clientSocket;
    private final ReentrantLock lock = new ReentrantLock();
    private int ancillaryFd = -1;

    // Output the client hasn't taken yet. A client that stops reading fills its socket, and a
    // blocking write would then stall the connector thread that produced the output. So writes
    // never block: what doesn't fit queues here in order, and a background task delivers it once
    // the client reads again. Guarded by lock.
    private static final long BACKLOG_WARN_BYTES = 8L << 20;
    private static final ExecutorService DRAINER = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "XOutputDrain");
        thread.setDaemon(true);
        return thread;
    });

    private static class Pending {
        final ByteBuffer data;
        final int fd;
        final int size;

        Pending(ByteBuffer data, int fd) {
            this.data = data;
            this.fd = fd;
            this.size = data.remaining();
        }
    }

    private final ArrayDeque<Pending> backlog = new ArrayDeque<>();
    private long backlogBytes = 0;
    private boolean drainScheduled = false;
    private boolean backlogWarned = false;
    private boolean dead = false;
    private Pending inFlight = null;

    public XOutputStream(ClientSocket clientSocket, int initialCapacity) {
        this.clientSocket = clientSocket;
        buffer = ByteBuffer.allocateDirect(initialCapacity);
    }

    public void setByteOrder(ByteOrder byteOrder) {
        buffer.order(byteOrder);
    }

    public void setAncillaryFd(int ancillaryFd) {
        this.ancillaryFd = ancillaryFd;
    }

    public void writeByte(byte value) {
        ensureSpaceIsAvailable(1);
        buffer.put(value);
    }

    public void writeInt(int value) {
        ensureSpaceIsAvailable(4);
        buffer.putInt(value);
    }

    private void flush() throws IOException {
        if (buffer.position() == 0) return;
        buffer.flip();
        try {
            if (clientSocket == null || dead) return;

            if (ancillaryFd != -1) {
                int fd = ancillaryFd;
                ancillaryFd = -1;
                // An fd-passing reply answers this client's own request, so it is reading; send it
                // directly unless older output is still queued ahead of it.
                if (backlog.isEmpty()) clientSocket.sendAncillaryMsg(buffer, fd);
                else enqueue(buffer, fd);
                return;
            }

            if (backlog.isEmpty()) {
                int written = clientSocket.writeNonBlocking(buffer);
                if (written < 0) {
                    dead = true;
                    Log.w("XOutputStream", "client fd " + clientSocket.fd + " write failed (errno " + -written + "), dropping its output");
                    return;
                }
                if (!buffer.hasRemaining()) return;
            }
            enqueue(buffer, -1);
        }
        finally {
            buffer.clear();
        }
    }

    private void enqueue(ByteBuffer src, int fd) {
        ByteBuffer copy = ByteBuffer.allocateDirect(src.remaining()).order(src.order());
        copy.put(src);
        copy.flip();
        Pending pending = new Pending(copy, fd);
        backlog.addLast(pending);
        backlogBytes += pending.size;

        if (backlogBytes > BACKLOG_WARN_BYTES && !backlogWarned) {
            backlogWarned = true;
            Log.w("XOutputStream", "client fd " + clientSocket.fd + " has not read " + backlogBytes + " bytes of output");
        }
        if (!drainScheduled) {
            drainScheduled = true;
            DRAINER.execute(this::drain);
        }
    }

    /** Delivers the backlog in order. Only this task removes entries, so the head is written
     *  outside the lock; producers meanwhile append behind it and never wait on the socket. */
    private void drain() {
        int sleepMs = 1;
        while (true) {
            Pending head;
            lock.lock();
            try {
                head = backlog.peekFirst();
                if (head == null || dead) {
                    backlog.clear();
                    backlogBytes = 0;
                    backlogWarned = false;
                    inFlight = null;
                    drainScheduled = false;
                    return;
                }
                inFlight = head;
            }
            finally {
                lock.unlock();
            }

            boolean sent = false;
            boolean failed = false;
            try {
                if (head.fd != -1) {
                    clientSocket.sendAncillaryMsg(head.data, head.fd);
                    sent = true;
                }
                else {
                    int written = clientSocket.writeNonBlocking(head.data);
                    if (written < 0) failed = true;
                    else if (!head.data.hasRemaining()) sent = true;
                    if (written == 0) {
                        try {
                            Thread.sleep(sleepMs);
                        }
                        catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        sleepMs = Math.min(sleepMs * 2, 16);
                    }
                    else sleepMs = 1;
                }
            }
            catch (IOException e) {
                failed = true;
            }

            lock.lock();
            try {
                inFlight = null;
                if (failed) dead = true;
                else if (sent) {
                    backlog.pollFirst();
                    backlogBytes -= head.size;
                }
            }
            finally {
                lock.unlock();
            }
        }
    }

    /** Stops all output before the connection's fd is closed: drops the backlog and waits briefly
     *  for a write already in progress, so nothing lands on a closed (or reused) fd. */
    public void close() {
        lock.lock();
        try {
            dead = true;
        }
        finally {
            lock.unlock();
        }
        for (int i = 0; i < 200; i++) {
            lock.lock();
            try {
                if (inFlight == null) return;
            }
            finally {
                lock.unlock();
            }
            try {
                Thread.sleep(1);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public XStreamLock lock() {
        return new OutputStreamLock();
    }

    private void ensureSpaceIsAvailable(int length) {
        int position = buffer.position();
        if ((buffer.capacity() - position) >= length) return;
        ByteBuffer newBuffer = ByteBuffer.allocateDirect(buffer.capacity() + length).order(buffer.order());
        buffer.rewind();
        newBuffer.put(buffer).position(position);
        buffer = newBuffer;
    }

    private class OutputStreamLock implements XStreamLock {
        public OutputStreamLock() {
            lock.lock();
        }

        @Override
        public void close() throws IOException {
            try {
                flush();
            } finally {
                lock.unlock();
            }
        }
    }

}
