package com.winlator.cmod.runtime.display.environment.components;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.util.Log;

import com.winlator.cmod.runtime.display.environment.EnvironmentComponent;
import com.winlator.cmod.shared.io.FileUtils;

import java.io.File;
import java.util.Collections;
import java.util.List;

/**
 * Writes active Android network info into the Wine prefix. Wine reads
 * {@code <tmpDir>/ifaddrs} because Android sandboxes direct interface
 * enumeration. CONNECTIVITY_ACTION refreshes the files mid-session.
 *
 * When Debug > Offline Mode is on, Wine is always told there is no adapter,
 * regardless of Android's real connectivity state.
 */
public class NetworkInfoUpdateComponent extends EnvironmentComponent {
    private static final String TAG = "NetworkInfoUpdateComponent";
    private static final String PREF_OFFLINE_MODE = "enable_offline_mode";
    private BroadcastReceiver broadcastReceiver;

    @Override
    public void start() {
        Log.d(TAG, "Starting...");
        Context context = environment.getContext();
        final NetworkHelper networkHelper = new NetworkHelper(context);
        pushNetworkState(context, networkHelper);

        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                pushNetworkState(ctx, networkHelper);
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        context.registerReceiver(broadcastReceiver, filter);
    }

    private void pushNetworkState(Context context, NetworkHelper networkHelper) {
        boolean offline =
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PREF_OFFLINE_MODE, false);
        if (offline) {
            updateIFAddrsFile(Collections.emptyList());
            updateEtcHostsFile(null);
        } else {
            updateIFAddrsFile(networkHelper.getIFAddresses());
            updateEtcHostsFile(networkHelper.getIPv4Address());
        }
    }

    @Override
    public void stop() {
        Log.d(TAG, "Stopping...");
        if (broadcastReceiver != null) {
            try {
                environment.getContext().unregisterReceiver(broadcastReceiver);
            } catch (Exception e) {
                Log.e(TAG, "Failed to unregister broadcast receiver: " + e);
            }
            broadcastReceiver = null;
        }
    }

    public void updateIFAddrsFile(List<NetworkHelper.IFAddress> ifAddresses) {
        File file = new File(environment.getImageFs().getTmpDir(), "ifaddrs");
        StringBuilder content = new StringBuilder();
        if (!ifAddresses.isEmpty()) {
            for (NetworkHelper.IFAddress ifAddress : ifAddresses) {
                if (content.length() > 0) content.append("\n");
                content.append(ifAddress.toString());
            }
        } else {
            content.append(new NetworkHelper.IFAddress().toString());
        }
        FileUtils.writeString(file, content.toString());
    }

    public void updateEtcHostsFile(String ipAddress) {
        String ip = ipAddress != null ? ipAddress : "127.0.0.1";
        File file = new File(environment.getImageFs().getRootDir(), "etc/hosts");
        FileUtils.writeString(file, ip + "\tlocalhost\n");
    }
}
