package com.winlator.cmod.xenvironment.components;

import com.winlator.cmod.xenvironment.EnvironmentComponent;
import com.winlator.cmod.xconnector.XConnectorEpoll;
import com.winlator.cmod.xconnector.UnixSocketConfig;
import com.winlator.cmod.xserver.XClientConnectionHandler;
import com.winlator.cmod.xserver.XClientRequestHandler;
import com.winlator.cmod.xserver.XServer;

public class XServerComponent extends EnvironmentComponent {
    private XConnectorEpoll connector;
    private final XServer xServer;
    private final UnixSocketConfig socketConfig;

    public XServerComponent(XServer xServer, UnixSocketConfig socketConfig) {
        this.xServer = xServer;
        this.socketConfig = socketConfig;
    }

    @Override
    public void start() {
        if (connector != null) return;
        connector = new XConnectorEpoll(socketConfig, new XClientConnectionHandler(xServer), new XClientRequestHandler());
        connector.setInitialInputBufferCapacity(262144);
        connector.setCanReceiveAncillaryMessages(true);
        // One thread serves every X client: every guest present, every input event to the guest
        // and every cursor update passes through it, so it must not queue behind the emulator's
        // guest threads (default nice 0). Same level as the present limiter.
        connector.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
        connector.setPerfHintThread(true);   // joins the renderer's ADPF session when it is on
        connector.start();
    }

    @Override
    public void stop() {
        if (connector != null) {
            connector.stop();
            connector = null;
        }
    }

    public XServer getXServer() {
        return xServer;
    }
}
