package com.winlator.cmod.xenvironment.components;

import com.winlator.cmod.xconnector.UnixSocketConfig;
import com.winlator.cmod.xenvironment.EnvironmentComponent;
import com.winlator.cmod.xserver.XServer;

/** Runs the X server (the Rust {@code xcore}) on its Unix socket for as long as the environment is up. */
public class XServerComponent extends EnvironmentComponent {
    private final XServer xServer;
    private final UnixSocketConfig socketConfig;

    public XServerComponent(XServer xServer, UnixSocketConfig socketConfig) {
        this.xServer = xServer;
        this.socketConfig = socketConfig;
    }

    @Override
    public void start() {
        xServer.start(socketConfig.path);
    }

    @Override
    public void stop() {
        xServer.stop();
    }

    public XServer getXServer() {
        return xServer;
    }
}
