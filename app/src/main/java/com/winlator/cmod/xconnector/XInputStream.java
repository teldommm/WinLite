package com.winlator.cmod.xconnector;


import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class XInputStream {
    private ByteBuffer activeBuffer;
    private ByteBuffer buffer;
    public final ClientSocket clientSocket;

    public XInputStream(ClientSocket clientSocket, int initialCapacity) {
        this.clientSocket = clientSocket;
        this.buffer = ByteBuffer.allocateDirect(initialCapacity);
    }

    public int readMoreData() throws IOException {
        if (activeBuffer != null) {
            if (!activeBuffer.hasRemaining()) {
                buffer.clear();
            }
            else if (activeBuffer.position() > 0) {
                int newLimit = buffer.position();
                buffer.position(activeBuffer.position()).limit(newLimit);
                buffer.compact();
            }
            activeBuffer = null;
        }

        growInputBufferIfNecessary();
        int bytesRead = clientSocket.read(buffer);

        if (bytesRead > 0) {
            int position = buffer.position();
            buffer.flip();
            activeBuffer = buffer.slice().order(buffer.order());
            buffer.limit(buffer.capacity()).position(position);
        }
        return bytesRead;
    }

    private void growInputBufferIfNecessary() {
        if (buffer.position() == buffer.capacity()) {
            ByteBuffer newBuffer = ByteBuffer.allocateDirect(buffer.capacity() * 2).order(buffer.order());
            buffer.rewind();
            newBuffer.put(buffer);
            buffer = newBuffer;
        }
    }

    public void setByteOrder(ByteOrder byteOrder) {
        buffer.order(byteOrder);
        if (activeBuffer != null) activeBuffer.order(byteOrder);
    }

    public int getActivePosition() {
        return activeBuffer.position();
    }

    public void setActivePosition(int activePosition) {
        activeBuffer.position(activePosition);
    }

    public int available() {
        return activeBuffer.remaining();
    }

    public byte readByte() {
        return activeBuffer.get();
    }

    public int readInt() {
        return activeBuffer.getInt();
    }

    public long readUnsignedInt() {
        return Integer.toUnsignedLong(activeBuffer.getInt());
    }

    public ByteBuffer readByteBuffer(int length) {
        ByteBuffer newBuffer = activeBuffer.slice().order(activeBuffer.order());
        newBuffer.limit(length);
        activeBuffer.position(activeBuffer.position() + length);
        return newBuffer;
    }
}
