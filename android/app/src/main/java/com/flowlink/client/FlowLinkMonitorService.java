package com.flowlink.client;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.IBinder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FlowLinkMonitorService extends Service {
    static final String ACTION_START = "com.flowlink.client.START";
    static final String ACTION_STOP = "com.flowlink.client.STOP";
    static final String ACTION_SWITCH = "com.flowlink.client.SWITCH";
    static final String ACTION_CHECK_UPDATE = "com.flowlink.client.CHECK_UPDATE";
    static final String ACTION_STATUS = "com.flowlink.client.STATUS";
    private static final String CHANNEL = "flowlink_vpn";
    private static final int NOTIFICATION_ID = 77;
    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean monitoring = new AtomicBoolean();
    private final AtomicBoolean repairing = new AtomicBoolean();
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback callback;
    private FlowLinkStore store;
    private TunnelController tunnel;
    private int consecutiveFailures;
    private long lastRefresh;
    private String currentProfileId;

    @Override public void onCreate() {
        super.onCreate();
        store = new FlowLinkStore(this);
        tunnel = TunnelController.get(this);
        connectivity = (ConnectivityManager)
                getSystemService(Context.CONNECTIVITY_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "FlowLink VPN", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        startForeground(NOTIFICATION_ID, notification("正在准备连接"));
        callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                worker.execute(() -> checkNow(true));
            }
        };
        connectivity.registerDefaultNetworkCallback(callback);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            store.setAutoConnect(false);
            worker.execute(() -> {
                try { tunnel.disconnect(); } catch (Exception ignored) {}
                currentProfileId = null;
                sendStatus("已断开", null, -1);
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            });
            return START_NOT_STICKY;
        }
        if (ACTION_CHECK_UPDATE.equals(action)) {
            worker.execute(() -> {
                maybeCheckUpdate(store.active(), true);
                if (!store.autoConnect()) {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                }
            });
            return START_STICKY;
        }
        store.setAutoConnect(true);
        if (monitoring.compareAndSet(false, true)) {
            worker.execute(this::connectBest);
            worker.scheduleWithFixedDelay(
                    () -> checkNow(false), 8, 8, TimeUnit.SECONDS);
        } else if (ACTION_SWITCH.equals(action)) {
            worker.execute(this::connectBest);
        }
        return START_STICKY;
    }

    private void checkNow(boolean networkChanged) {
        if (!store.autoConnect() || !store.registered()) return;
        ServerProfile profile = store.active();
        if (profile == null) return;
        if (!profile.id.equals(currentProfileId) || !tunnel.isUp()) {
            connectBest();
            return;
        }
        try {
            ApiClient api = new ApiClient(profile.host, profile.fingerprint);
            if (api.health(null)) {
                consecutiveFailures = 0;
                sendStatus(networkChanged ? "网络已切换，VPN正常" : "已连接",
                        profile, profile.currentPort);
                if (System.currentTimeMillis() - lastRefresh > 6 * 60 * 60 * 1000L)
                    refreshConfig(profile);
                maybeCheckUpdate(profile, false);
            } else if (++consecutiveFailures >= 2) {
                connectBest();
            } else {
                sendStatus("正在检查连接", profile, profile.currentPort);
            }
        } catch (Exception exception) {
            if (++consecutiveFailures >= 2) connectBest();
        }
    }

    private ServerProfile refreshConfig(ServerProfile profile) {
        try {
            ApiClient api = new ApiClient(profile.host, profile.fingerprint);
            FlowConfig latest = FlowConfig.fromServer(api.config(
                    store.deviceToken(profile), underlyingNetwork()));
            store.updateConfig(profile.id, latest);
            lastRefresh = System.currentTimeMillis();
            for (ServerProfile value : store.profiles())
                if (value.id.equals(profile.id)) return value;
        } catch (Exception ignored) {
            // Cached endpoints remain usable while a control endpoint is unreachable.
        }
        return profile;
    }

    private void connectBest() {
        if (!store.autoConnect() || !repairing.compareAndSet(false, true)) return;
        try {
            List<ServerProfile> all = store.profiles();
            if (all.isEmpty()) return;
            ServerProfile selected = store.active();
            List<ServerProfile> ordered = new ArrayList<>();
            if (selected != null) ordered.add(selected);
            for (ServerProfile profile : all)
                if (selected == null || !profile.id.equals(selected.id)) ordered.add(profile);
            for (ServerProfile original : ordered) {
                if (!store.autoConnect()) return;
                ServerProfile profile = refreshConfig(original);
                List<Integer> ports = new ArrayList<>();
                if (profile.config.ports.contains(profile.currentPort))
                    ports.add(profile.currentPort);
                for (int port : profile.config.ports)
                    if (!ports.contains(port)) ports.add(port);
                sendStatus("正在自动选择服务器和端口", profile, profile.currentPort);
                for (int port : ports) {
                    if (!store.autoConnect()) return;
                    sendStatus("正在尝试端口", profile, port);
                    String privateKey = store.privateKey(profile);
                    if (tunnel.isUp() && profile.id.equals(currentProfileId))
                        tunnel.updateEndpoint(profile.config, privateKey, port);
                    else
                        tunnel.connect(profile.config, privateKey, port);
                    currentProfileId = profile.id;
                    Thread.sleep(1800);
                    ApiClient api = new ApiClient(profile.host, profile.fingerprint);
                    if (api.health(null)) {
                        store.select(profile.id);
                        store.setCurrentPort(profile.id, port);
                        consecutiveFailures = 0;
                        ServerProfile connected = store.active();
                        sendStatus("已连接", connected, port);
                        maybeCheckUpdate(connected, false);
                        return;
                    }
                }
            }
            consecutiveFailures = 0;
            sendStatus("所有服务器暂时不可用，将继续重试", null, -1);
        } catch (Exception exception) {
            sendStatus("连接失败，将自动重试", null, -1);
        } finally {
            repairing.set(false);
        }
    }

    private void maybeCheckUpdate(ServerProfile profile, boolean forced) {
        if (profile == null) return;
        long now = System.currentTimeMillis();
        if (!forced && now - store.lastUpdateCheck() < 12 * 60 * 60 * 1000L) return;
        try {
            String version = UpdateManager.checkAndDownload(
                    this, profile, underlyingNetwork());
            store.setLastUpdateCheck(now);
            if (forced)
                sendStatus(version == null ? "当前已是最新版本"
                        : "新版本 " + version + " 已下载", profile,
                        profile.currentPort);
        } catch (Exception exception) {
            if (forced) sendStatus("检查更新失败", profile, profile.currentPort);
        }
    }

    private Network underlyingNetwork() {
        Network fallback = null;
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
            if (caps == null || !caps.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            fallback = network;
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                return network;
        }
        return fallback;
    }

    private void sendStatus(String text, ServerProfile profile, int port) {
        Intent intent = new Intent(ACTION_STATUS).setPackage(getPackageName());
        intent.putExtra("status", text);
        intent.putExtra("port", port);
        if (profile != null) {
            intent.putExtra("profile", profile.name);
            intent.putExtra("host", profile.host);
        }
        sendBroadcast(intent);
        String detail = profile == null ? text : profile.name + " · " + text;
        getSystemService(NotificationManager.class).notify(
                NOTIFICATION_ID, notification(port > 0 ? detail + " · UDP " + port : detail));
    }

    private Notification notification(String text) {
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_flowlink)
                .setContentTitle("FlowLink")
                .setContentText(text)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    @Override public void onDestroy() {
        if (callback != null) connectivity.unregisterNetworkCallback(callback);
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
