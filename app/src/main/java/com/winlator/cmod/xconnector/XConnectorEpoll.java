package com.winlator.cmod.xconnector;

import android.util.SparseArray;

import androidx.annotation.Keep;

import java.io.IOException;
import java.nio.ByteBuffer;

public class XConnectorEpoll implements Runnable {
    private final ConnectionHandler connectionHandler;
    private final RequestHandler requestHandler;
    private final int epollFd;
    private final int serverFd;
    private final int shutdownFd;
    private Thread epollThread;
    private volatile boolean running = false;
    private boolean multithreadedClients = false;
    private boolean canReceiveAncillaryMessages = false;
    private int initialInputBufferCapacity = 4096;
    private int initialOutputBufferCapacity = 4096;
    private final SparseArray<Client> connectedClients = new SparseArray<>();

    static {
        System.loadLibrary("winlator");
    }

    public XConnectorEpoll(UnixSocketConfig socketConfig, ConnectionHandler connectionHandler, RequestHandler requestHandler) {
        this.connectionHandler = connectionHandler;
        this.requestHandler = requestHandler;

        serverFd = createAFUnixSocket(socketConfig.path);
        if (serverFd < 0) {
            throw new RuntimeException("Failed to create an AF_UNIX socket.");
        }

        epollFd = createEpollFd();
        if (epollFd < 0) {
            closeFd(serverFd);
            throw new RuntimeException("Failed to create epoll fd.");
        }

        if (!addFdToEpoll(epollFd, serverFd)) {
            closeFd(serverFd);
            closeFd(epollFd);
            throw new RuntimeException("Failed to add server fd to epoll.");
        }

        shutdownFd = createEventFd();
        if (!addFdToEpoll(epollFd, shutdownFd)) {
            closeFd(serverFd);
            closeFd(shutdownFd);
            closeFd(epollFd);
            throw new RuntimeException("Failed to add shutdown fd to epoll.");
        }

        epollThread = new Thread(this);
    }

    public synchronized void start() {
        if (running || epollThread == null) return;
        running = true;
        epollThread.start();
    }

    public synchronized void stop() {
        if (!running || epollThread == null) return;
        running = false;
        requestShutdown();

        while (epollThread.isAlive()) {
            try {
                epollThread.join();
            }
            catch (InterruptedException e) {}
        }
        epollThread = null;
    }

    // Nice value for the threads that serve clients (the epoll thread, and per-client threads in
    // multithreaded mode). 0 keeps the default.
    private volatile int threadPriority = 0;

    public void setThreadPriority(int priority) {
        this.threadPriority = priority;
    }

    private void applyThreadPriority() {
        if (threadPriority == 0) return;
        try {
            android.os.Process.setThreadPriority(threadPriority);
        } catch (RuntimeException e) {
            android.util.Log.w("XConnectorEpoll", "could not set thread priority " + threadPriority, e);
        }
    }

    @Override
    public void run() {
        applyThreadPriority();
        while (running && doEpollIndefinitely(epollFd, serverFd, !multithreadedClients));
        shutdown();
    }

    @Keep
    private void handleNewConnection(int fd) {
        final Client client = new Client(this, new ClientSocket(fd));
        client.connected = true;
        if (multithreadedClients) {
            client.shutdownFd = createEventFd();
            client.pollThread = new Thread(() -> {
                applyThreadPriority();
                connectionHandler.handleNewConnection(client);
                while (client.connected && waitForSocketRead(client.clientSocket.fd, client.shutdownFd));
            });
            client.pollThread.start();
        }
        else connectionHandler.handleNewConnection(client);
        connectedClients.put(fd, client);
    }

    @Keep
    private void handleExistingConnection(int fd) {
        Client client = connectedClients.get(fd);
        if (client == null) return;

        XInputStream inputStream = client.getInputStream();
        try {
            if (inputStream != null) {
                if (inputStream.readMoreData(canReceiveAncillaryMessages) > 0) {
                    int activePosition = 0;
                    while (running && requestHandler.handleRequest(client)) activePosition = inputStream.getActivePosition();
                    inputStream.setActivePosition(activePosition);
                }
                else killConnection(client);
            }
            else requestHandler.handleRequest(client);
        }
        catch (IOException e) {
            killConnection(client);
        }
        catch (RuntimeException e) {
            /* Last line of defence: an exception escaping here went back through JNI into the
             * native epoll loop and ended this connector's thread, i.e. every client of it. Only
             * the offending connection goes now. */
            android.util.Log.e("XConnectorEpoll", "Request handling failed, closing connection fd=" + fd, e);
            try {
                killConnection(client);
            }
            catch (RuntimeException e2) {
                android.util.Log.e("XConnectorEpoll", "Closing connection fd=" + fd + " failed", e2);
            }
        }
    }

    public Client getClient(int fd) {
        return connectedClients.get(fd);
    }

    // Frame-pacing back-pressure: the client's fd leaves epoll until delayNs has elapsed, so the
    // client stalls in its own blocking call (a per-frame XSync) while the epoll thread stays
    // free for everyone else. Single-threaded mode only.
    // Process-wide daemon scheduler, never shut down: stop() is synchronized and joins the epoll
    // thread, so any scheduler cleanup on the shutdown path could deadlock against it.
    private static final class PauseScheduler {
        static final java.util.concurrent.ScheduledExecutorService INSTANCE =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "XConnectorPause");
                    t.setDaemon(true);
                    return t;
                });
    }

    public void pauseClientReads(Client client, long delayNs) {
        if (multithreadedClients || !client.connected || !running || client.pacingExempt) return;
        final long deadlineNs = System.nanoTime() + delayNs;
        synchronized (client) {
            // One pending re-arm per client, keyed to the latest deadline: an earlier task firing
            // first would resume reads before the newest frame's slot.
            if (client.pauseTask != null && !client.pauseTask.isDone()) {
                // Repeated supersedes within one pause window mean the client pipelines frames
                // without a per-frame sync; suspending reads cannot pace such a client smoothly
                // (its loop only feels the brake in whole batches), so it runs uncapped instead.
                if (client.pacedSupersedes++ >= 8) {
                    client.pacingExempt = true;
                    android.util.Log.i("XConnectorEpoll", "client fd " + client.clientSocket.fd
                            + " pipelines frames without a per-frame sync; SHM pacing exempted");
                    return;
                }
                if (client.pauseDeadlineNs >= deadlineNs) return;
                client.pauseTask.cancel(false);
            }
            removeFdFromEpoll(epollFd, client.clientSocket.fd);
            client.pauseDeadlineNs = deadlineNs;
            final int generation = ++client.pauseGeneration;
            client.pauseTask = PauseScheduler.INSTANCE.schedule(() -> {
                synchronized (client) {
                    // Superseded by a newer pause (cancel(false) cannot stop a task that already
                    // started and is waiting for this monitor): that one re-arms the fd.
                    if (client.pauseGeneration != generation) return;
                    client.pauseTask = null;
                    client.pacedSupersedes = 0;
                    if (client.connected && running) addFdToEpoll(epollFd, client.clientSocket.fd);
                }
            }, delayNs, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    private void cancelPendingPause(Client client) {
        synchronized (client) {
            client.pauseGeneration++;
            if (client.pauseTask != null) {
                client.pauseTask.cancel(false);
                client.pauseTask = null;
            }
        }
    }

    public void killConnection(Client client) {
        client.connected = false;
        // After this returns no re-arm task can touch the fd any more (it checks `connected`
        // under the same monitor), so closing it below cannot race a late addFdToEpoll.
        cancelPendingPause(client);
        connectionHandler.handleConnectionShutdown(client);
        if (multithreadedClients) {
            if (Thread.currentThread() != client.pollThread) {
                client.requestShutdown();

                while (client.pollThread.isAlive()) {
                    try {
                        client.pollThread.join();
                    }
                    catch (InterruptedException e) {}
                }

                client.pollThread = null;
            }
            closeFd(client.shutdownFd);
        }
        else removeFdFromEpoll(epollFd, client.clientSocket.fd);
        closeFd(client.clientSocket.fd);
        connectedClients.remove(client.clientSocket.fd);
    }

    private void shutdown() {
        while (connectedClients.size() > 0) {
            Client client = connectedClients.valueAt(connectedClients.size()-1);
            killConnection(client);
        }

        removeFdFromEpoll(epollFd, serverFd);
        removeFdFromEpoll(epollFd, shutdownFd);
        closeFd(serverFd);
        closeFd(shutdownFd);
        closeFd(epollFd);
    }

    public int getInitialInputBufferCapacity() {
        return initialInputBufferCapacity;
    }

    public void setInitialInputBufferCapacity(int initialInputBufferCapacity) {
        this.initialInputBufferCapacity = initialInputBufferCapacity;
    }

    public int getInitialOutputBufferCapacity() {
        return initialOutputBufferCapacity;
    }

    public void setInitialOutputBufferCapacity(int initialOutputBufferCapacity) {
        this.initialOutputBufferCapacity = initialOutputBufferCapacity;
    }

    public boolean isMultithreadedClients() {
        return multithreadedClients;
    }

    public void setMultithreadedClients(boolean multithreadedClients) {
        this.multithreadedClients = multithreadedClients;
    }

    public boolean isCanReceiveAncillaryMessages() {
        return canReceiveAncillaryMessages;
    }

    public void setCanReceiveAncillaryMessages(boolean canReceiveAncillaryMessages) {
        this.canReceiveAncillaryMessages = canReceiveAncillaryMessages;
    }

    private void requestShutdown() {
        try {
            ByteBuffer data = ByteBuffer.allocateDirect(8);
            data.asLongBuffer().put(1);
            (new ClientSocket(shutdownFd)).write(data);
        }
        catch (IOException e) {}
    }

    public static native void closeFd(int fd);

    private native int createEpollFd();

    private native int createEventFd();

    private native boolean doEpollIndefinitely(int epollFd, int serverFd, boolean addClientToEpoll);

    private native boolean addFdToEpoll(int epollFd, int fd);

    private native void removeFdFromEpoll(int epollFd, int fd);

    private native boolean waitForSocketRead(int clientFd, int shutdownFd);

    private native int createAFUnixSocket(String path);
}