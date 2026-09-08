package com.flowlink.client;

import org.json.JSONException;
import org.json.JSONObject;

final class ServerProfile {
    final String id;
    final String name;
    final String host;
    final String fingerprint;
    final FlowConfig config;
    final int currentPort;

    ServerProfile(String id, String name, String host, String fingerprint,
                  FlowConfig config, int currentPort) {
        this.id = id;
        this.name = name;
        this.host = normalizeHost(host);
        this.fingerprint = normalizeFingerprint(fingerprint);
        this.config = config;
        this.currentPort = currentPort;
    }

    static ServerProfile fromJson(JSONObject root) throws JSONException {
        FlowConfig config = FlowConfig.fromServer(root.getJSONObject("config"));
        return new ServerProfile(root.getString("id"), root.getString("name"),
                root.getString("host"), root.getString("fingerprint"), config,
                root.optInt("current_port", config.ports.get(0)));
    }

    JSONObject toJson() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("id", id);
        root.put("name", name);
        root.put("host", host);
        root.put("fingerprint", fingerprint);
        root.put("config", config.toJson());
        root.put("current_port", currentPort);
        return root;
    }

    ServerProfile withConfig(FlowConfig value) {
        int port = value.ports.contains(currentPort) ? currentPort : value.ports.get(0);
        return new ServerProfile(id, name, host, fingerprint, value, port);
    }

    ServerProfile withPort(int value) {
        return new ServerProfile(id, name, host, fingerprint, config, value);
    }

    static String normalizeHost(String value) {
        String host = value == null ? "" : value.trim();
        host = host.replaceFirst("(?i)^https://", "");
        while (host.endsWith("/")) host = host.substring(0, host.length() - 1);
        if (host.contains("/") || host.isEmpty())
            throw new IllegalArgumentException("invalid server host");
        return host;
    }

    static String normalizeFingerprint(String value) {
        String result = value == null ? "" : value.replace(":", "")
                .replace(" ", "").trim().toUpperCase();
        if (!result.matches("[0-9A-F]{64}"))
            throw new IllegalArgumentException("invalid certificate fingerprint");
        return result;
    }

    @Override public String toString() {
        return name + " · " + host;
    }
}
