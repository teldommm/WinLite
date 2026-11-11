package com.winlator.cmod.xenvironment.components;

import com.winlator.cmod.sysvshm.SysVSHMConnectionHandler;
import com.winlator.cmod.sysvshm.SysVSHMRequestHandler;
import com.winlator.cmod.sysvshm.SysVSharedMemory;
import com.winlator.cmod.xconnector.UnixSocketConfig;
import com.winlator.cmod.xconnector.XConnectorEpoll;
import com.winlator.cmod.xenvironment.EnvironmentComponent;
import com.winlator.cmod.xserver.XCore;

public class SysVSharedMemoryComponent extends EnvironmentComponent {
    private XConnectorEpoll connector;
    public final UnixSocketConfig socketConfig;
    private SysVSharedMemory sysVSharedMemory;
    public SysVSharedMemoryComponent(UnixSocketConfig socketConfig) {
        this.socketConfig = socketConfig;
    }

    @Override
    public void start() {
        if (connector != null) return;
        sysVSharedMemory = new SysVSharedMemory();
        connector = new XConnectorEpoll(socketConfig, new SysVSHMConnectionHandler(sysVSharedMemory), new SysVSHMRequestHandler());
        connector.start();

        // MIT-SHM of the X server attaches the guest's segments from here.
        XCore.setShmProvider(sysVSharedMemory);
    }

    @Override
    public void stop() {
        if (connector != null) {
            connector.stop();
            connector = null;
        }

        XCore.setShmProvider(null);
        if (sysVSharedMemory != null) sysVSharedMemory.deleteAll();
    }
}
