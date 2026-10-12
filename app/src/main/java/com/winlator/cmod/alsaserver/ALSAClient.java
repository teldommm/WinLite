package com.winlator.cmod.alsaserver;

import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.sysvshm.SysVSharedMemory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public class ALSAClient {
    public enum DataType {
        U8(1), S16LE(2), S16BE(2), FLOATLE(4), FLOATBE(4);
        public final byte byteCount;

        DataType(int byteCount) {
            this.byteCount = (byte)byteCount;
        }
    }

    // Tunables, read once per launch from the container/shortcut environment.
    public static class Options {
        // AAUDIO_PERFORMANCE_MODE_* values, passed straight to the native builder.
        public static final int PERFORMANCE_MODE_NONE = 10;
        public static final int PERFORMANCE_MODE_POWER_SAVING = 11;
        public static final int PERFORMANCE_MODE_LOW_LATENCY = 12;

        // 0 = no floor: the device buffer starts at the guest's ALSA buffer size.
        public int latencyMillis = 0;
        public int performanceMode = PERFORMANCE_MODE_LOW_LATENCY;

        public static Options fromEnvVars(EnvVars envVars) {
            Options options = new Options();
            if (envVars == null) return options;

            String latency = envVars.get("ALSA_LATENCY_MS");
            try {
                if (!latency.isEmpty()) options.latencyMillis = Math.max(0, Integer.parseInt(latency.trim()));
            }
            catch (NumberFormatException ignored) {}

            String mode = envVars.get("ALSA_PERFORMANCE_MODE").trim();
            if (mode.equalsIgnoreCase("none")) options.performanceMode = PERFORMANCE_MODE_NONE;
            else if (mode.equalsIgnoreCase("power_saving")) options.performanceMode = PERFORMANCE_MODE_POWER_SAVING;
            return options;
        }
    }

    // Live clients, so the whole ALSA output can be paused while the activity is in the background.
    private static final Set<ALSAClient> activeClients = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static volatile boolean outputSuspended = false;

    private final Options options;
    private DataType dataType = DataType.U8;
    private byte channelCount = 2;
    private int sampleRate = 0;
    private int position;
    private int bufferSize;
    private int frameBytes;
    private ByteBuffer sharedBuffer;
    private boolean playing = false;
    private boolean suspended = false;
    private long streamPtr = 0;

    static {
        System.loadLibrary("winlator");
    }

    public ALSAClient() {
        this(new Options());
    }

    public ALSAClient(Options options) {
        this.options = options != null ? options : new Options();
        activeClients.add(this);
    }

    // Called off the UI thread from XEnvironment.onPause/onResume.
    public static void setOutputSuspended(boolean suspend) {
        outputSuspended = suspend;
        ALSAClient[] clients;
        synchronized (activeClients) {
            clients = activeClients.toArray(new ALSAClient[0]);
        }
        for (ALSAClient client : clients) {
            if (suspend) client.suspendOutput();
            else client.resumeOutput();
        }
    }

    private synchronized void suspendOutput() {
        if (streamPtr != 0 && playing && !suspended) {
            pause(streamPtr);
            suspended = true;
        }
    }

    private synchronized void resumeOutput() {
        if (streamPtr != 0 && suspended) {
            suspended = false;
            if (playing) start(streamPtr);
        }
    }

    public synchronized void release() {
        if (sharedBuffer != null) {
            SysVSharedMemory.unmapSHMSegment(sharedBuffer, sharedBuffer.capacity());
            sharedBuffer = null;
        }

        if (streamPtr != 0) {
            stop(streamPtr);
            close(streamPtr);
            streamPtr = 0;
        }
        playing = false;
        suspended = false;
    }

    public synchronized void dispose() {
        release();
        activeClients.remove(this);
    }

    public synchronized void prepare() {
        position = 0;
        frameBytes = channelCount * dataType.byteCount;
        release();

        if (!isValidBufferSize()) return;

        int latencyFrames = (int)((long)options.latencyMillis * sampleRate / 1000);
        streamPtr = create(dataType.ordinal(), channelCount, sampleRate, bufferSize, latencyFrames, options.performanceMode);
        if (streamPtr != 0) start();
    }

    public synchronized void start() {
        if (streamPtr != 0 && !playing) {
            playing = true;
            if (outputSuspended) suspended = true;
            else start(streamPtr);
        }
    }

    public synchronized void stop() {
        if (streamPtr != 0 && playing) {
            stop(streamPtr);
            playing = false;
        }
    }

    public synchronized void pause() {
        if (streamPtr != 0) {
            pause(streamPtr);
            playing = false;
        }
    }

    // ALSA drain = play out what is queued, then stop. AAudio's requestStop does exactly that for
    // output streams (requestFlush would discard it, and is a no-op on a running stream anyway).
    public synchronized void drain() {
        if (streamPtr != 0) {
            stop(streamPtr);
            playing = false;
            suspended = false;
        }
    }

    public synchronized void writeDataToStream(ByteBuffer data) {
        if (playing && frameBytes > 0) {
            int numFrames = data.limit() / frameBytes;
            if (suspended) {
                // Backgrounded: the device stream is paused, so swallow the data instead of
                // blocking the request thread on a stream that is not consuming.
                position += numFrames;
            }
            else {
                // Byte order and U8 conversion are handled natively.
                int framesWritten = write(streamPtr, data, numFrames);
                if (framesWritten > 0) position += framesWritten;
            }
        }
        data.rewind();
    }

    public synchronized int pointer() {
        return position;
    }

    public void setDataType(DataType dataType) {
        this.dataType = dataType;
    }

    public void setChannelCount(int channelCount) {
        this.channelCount = (byte)channelCount;
    }

    public void setSampleRate(int sampleRate) {
        this.sampleRate = sampleRate;
    }

    public void setBufferSize(int bufferSize) {
        this.bufferSize = bufferSize;
    }

    public ByteBuffer getSharedBuffer() {
        return sharedBuffer;
    }

    public void setSharedBuffer(ByteBuffer sharedBuffer) {
        this.sharedBuffer = sharedBuffer != null ? sharedBuffer.order(ByteOrder.LITTLE_ENDIAN) : null;
    }

    public DataType getDataType() {
        return dataType;
    }

    public byte getChannelCount() {
        return channelCount;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public int getBufferSize() {
        return bufferSize;
    }

    public int getBufferSizeInBytes() {
        return bufferSize * frameBytes;
    }

    private boolean isValidBufferSize() {
        return bufferSize > 0 && frameBytes > 0 && sampleRate > 0;
    }

    public int computeLatencyMillis() {
        return (int)(((float)bufferSize / sampleRate) * 1000);
    }

    private native long create(int format, byte channelCount, int sampleRate, int bufferSize, int latencyFrames, int performanceMode);

    private native int write(long streamPtr, ByteBuffer buffer, int numFrames);

    private native void start(long streamPtr);

    private native void stop(long streamPtr);

    private native void pause(long streamPtr);

    private native void close(long streamPtr);
}
