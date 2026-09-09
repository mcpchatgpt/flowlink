package com.flowlink.client;

import android.content.Context;
import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Statistics;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.Config;
import com.wireguard.crypto.Key;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

final class TunnelController {
    static final class Snapshot {
        final long latestHandshakeMillis;
        final long receivedBytes;
        final long sentBytes;

        Snapshot(long latestHandshakeMillis, long receivedBytes, long sentBytes) {
            this.latestHandshakeMillis = latestHandshakeMillis;
            this.receivedBytes = receivedBytes;
            this.sentBytes = sentBytes;
        }
    }
    private static volatile TunnelController instance;
    private final GoBackend backend;
    private final FlowLinkTunnel tunnel = new FlowLinkTunnel();

    static TunnelController get(Context context) {
        if (instance == null) {
            synchronized (TunnelController.class) {
                if (instance == null)
                    instance = new TunnelController(context.getApplicationContext());
            }
        }
        return instance;
    }

    private TunnelController(Context context) {
        backend = new GoBackend(context);
    }

    synchronized void connect(FlowConfig config, String privateKey, int port)
            throws Exception {
        backend.setState(tunnel, Tunnel.State.UP, wireGuardConfig(config, privateKey, port));
    }

    synchronized void updateEndpoint(FlowConfig config, String privateKey, int port)
            throws Exception {
        backend.updateConfig(tunnel, wireGuardConfig(config, privateKey, port));
    }

    private Config wireGuardConfig(FlowConfig config, String privateKey, int port)
            throws Exception {
        String text = "[Interface]\n"
                + "PrivateKey = " + privateKey + "\n"
                + "Address = " + config.address + "\n"
                + "DNS = " + String.join(", ", config.dns) + "\n"
                + "MTU = " + config.mtu + "\n\n"
                + "[Peer]\n"
                + "PublicKey = " + config.serverPublicKey + "\n"
                + "AllowedIPs = 0.0.0.0/0\n"
                + "Endpoint = " + config.endpoint + ":" + port + "\n"
                + "PersistentKeepalive = " + config.keepalive + "\n";
        return Config.parse(new ByteArrayInputStream(
                text.getBytes(StandardCharsets.UTF_8)));
    }

    synchronized void disconnect() throws Exception {
        backend.setState(tunnel, Tunnel.State.DOWN, null);
    }

    synchronized boolean isUp() {
        try {
            return backend.getState(tunnel) == Tunnel.State.UP;
        } catch (Exception ignored) {
            return false;
        }
    }

    synchronized Statistics statistics() throws Exception {
        return backend.getStatistics(tunnel);
    }

    synchronized Snapshot snapshot() throws Exception {
        Statistics statistics = backend.getStatistics(tunnel);
        long latest = 0;
        for (Key key : statistics.peers()) {
            Statistics.PeerStats peer = statistics.peer(key);
            if (peer != null)
                latest = Math.max(latest, peer.latestHandshakeEpochMillis());
        }
        return new Snapshot(latest, statistics.totalRx(), statistics.totalTx());
    }

    synchronized boolean hasRecentHandshake(long maximumAgeMillis) {
        if (!isUp()) return false;
        try {
            long latest = snapshot().latestHandshakeMillis;
            return latest > 0 && System.currentTimeMillis() - latest <= maximumAgeMillis;
        } catch (Exception ignored) {
            return false;
        }
    }
}
