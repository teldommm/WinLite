package com.winlator.cmod.xconnector;

import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

public class ClientSocket {
    public final int fd;

    static {
        System.loadLibrary("winlator");
    }

    public ClientSocket(int fd) {
        this.fd = fd;
    }

    public int read(ByteBuffer data) throws IOException {
        int position = data.position();
        int bytesRead = read(fd, data, position, data.remaining());
        if (bytesRead > 0) {
            data.position(position + bytesRead);
            return bytesRead;
        }
        else if (bytesRead == 0) {
            return -1;
        }
        else throw new IOException("Failed to read data.");
    }

    public void write(ByteBuffer data) throws IOException {
        int bytesWritten = write(fd, data, data.limit());
        if (bytesWritten >= 0) {
            data.position(bytesWritten);
        }
        else Log.d("ClientSocket", "Failed to write data.");
    }

    public static final int EAGAIN = 11;

    /** Writes data[position, limit) without blocking and advances position past what was sent.
     *  Returns the bytes written (0 when the socket is full), or -errno for a dead connection. */
    public int writeNonBlocking(ByteBuffer data) {
        int position = data.position();
        int n = writeNonBlocking(fd, data, position, data.remaining());
        if (n > 0) {
            data.position(position + n);
            return n;
        }
        return n == -EAGAIN ? 0 : n;
    }

    public void sendAncillaryMsg(ByteBuffer data, int ancillaryFd) throws IOException {
        int bytesSent = sendAncillaryMsg(fd, data, data.limit(), ancillaryFd);
        if (bytesSent >= 0) {
            data.position(bytesSent);
        }
        else throw new IOException("Failed to send ancillary messages.");
    }

    private native int read(int fd, ByteBuffer data, int offset, int length);

    private native int write(int fd, ByteBuffer data, int length);

    private native int writeNonBlocking(int fd, ByteBuffer data, int offset, int length);

    private native int sendAncillaryMsg(int clientFd, ByteBuffer data, int length, int ancillaryFd);
}
