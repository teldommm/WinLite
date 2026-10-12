package com.winlator.cmod.alsaserver;

import com.winlator.cmod.sysvshm.SysVSharedMemory;
import com.winlator.cmod.xconnector.Client;
import com.winlator.cmod.xconnector.RequestHandler;
import com.winlator.cmod.xconnector.XConnectorEpoll;
import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;

import java.io.IOException;
import java.nio.ByteBuffer;

public class ALSARequestHandler implements RequestHandler {
    private int maxSHMemoryId = 0;

    @Override
    public boolean handleRequest(Client client) throws IOException {
        ALSAClient alsaClient = (ALSAClient)client.getTag();
        XInputStream inputStream = client.getInputStream();
        XOutputStream outputStream = client.getOutputStream();

        if (inputStream.available() < 5) return false;
        byte requestCode = inputStream.readByte();
        int requestLength = inputStream.readInt();

        switch (requestCode) {
            case RequestCodes.CLOSE:
                alsaClient.release();
                break;
            case RequestCodes.START:
                alsaClient.start();
                break;
            case RequestCodes.STOP:
                alsaClient.stop();
                break;
            case RequestCodes.PAUSE:
                alsaClient.pause();
                break;
            case RequestCodes.PREPARE:
                if (inputStream.available() < requestLength) return false;

                alsaClient.setChannelCount(inputStream.readByte());
                int dataType = inputStream.readByte();
                ALSAClient.DataType[] dataTypes = ALSAClient.DataType.values();
                alsaClient.setDataType(dataTypes[dataType >= 0 && dataType < dataTypes.length ? dataType : ALSAClient.DataType.S16LE.ordinal()]);
                alsaClient.setSampleRate(inputStream.readInt());
                alsaClient.setBufferSize(inputStream.readInt());
                alsaClient.prepare();

                createSharedMemory(alsaClient, outputStream);
                break;
            case RequestCodes.WRITE:
                ByteBuffer buffer = alsaClient.getSharedBuffer();
                if (buffer != null) {
                    // A length past the shared segment would make limit() throw and kill the client thread.
                    if (requestLength < 0 || requestLength > buffer.capacity()) break;
                    buffer.limit(requestLength);
                    alsaClient.writeDataToStream(buffer);
                }
                else {
                    if (requestLength < 0) break;
                    if (inputStream.available() < requestLength) return false;
                    alsaClient.writeDataToStream(inputStream.readByteBuffer(requestLength));
                }
                break;
            case RequestCodes.DRAIN:
                alsaClient.drain();
                break;
            case RequestCodes.POINTER:
                try (XStreamLock lock = outputStream.lock()) {
                    outputStream.writeInt(alsaClient.pointer());
                }
                break;
        }
        return true;
    }

    private void createSharedMemory(ALSAClient alsaClient, XOutputStream outputStream) throws IOException {
        int size = alsaClient.getBufferSizeInBytes();
        int fd = SysVSharedMemory.createMemoryFd("alsa-shm"+(++maxSHMemoryId), size);

        if (fd >= 0) {
            ByteBuffer buffer = SysVSharedMemory.mapSHMSegment(fd, size, 0, true);
            if (buffer != null) alsaClient.setSharedBuffer(buffer);
        }

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte((byte)0);
            outputStream.setAncillaryFd(fd);
        }
        finally {
            if (fd >= 0) XConnectorEpoll.closeFd(fd);
        }
    }
}
