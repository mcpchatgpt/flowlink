package com.flowlink.client;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        FlowLinkStore store = new FlowLinkStore(context);
        if (store.registered() && store.autoConnect()) {
            Intent service = new Intent(context, FlowLinkMonitorService.class)
                    .setAction(FlowLinkMonitorService.ACTION_START);
            try {
                context.startForegroundService(service);
            } catch (RuntimeException ignored) {
                // Android may defer foreground starts after boot; opening the app retries.
            }
        }
    }
}
