package com.flowlink.client;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

final class FlowConfig {
    final int configVersion;
    final String endpoint;
    final List<Integer> ports;
    final String serverPublicKey;
    final String address;
    final List<String> dns;
    final int mtu;
    final int keepalive;

    FlowConfig(int configVersion, String endpoint, List<Integer> ports,
               String serverPublicKey, String address, List<String> dns,
               int mtu, int keepalive) {
        this.configVersion = configVersion;
        this.endpoint = endpoint;
        this.ports = ports;
        this.serverPublicKey = serverPublicKey;
        this.address = address;
        this.dns = dns;
        this.mtu = mtu;
        this.keepalive = keepalive;
    }

    static FlowConfig fromServer(JSONObject root) throws JSONException {
        JSONObject wg = root.getJSONObject("wireguard");
        List<Integer> ports = new ArrayList<>();
        JSONArray portArray = root.getJSONArray("ports");
        for (int i = 0; i < portArray.length(); i++) ports.add(portArray.getInt(i));
        List<String> dns = new ArrayList<>();
        JSONArray dnsArray = wg.getJSONArray("dns");
        for (int i = 0; i < dnsArray.length(); i++) dns.add(dnsArray.getString(i));
        return new FlowConfig(root.optInt("config_version", 1),
                root.getString("endpoint"), ports,
                wg.getString("server_public_key"), wg.getString("address"),
                dns, wg.getInt("mtu"), wg.getInt("persistent_keepalive"));
    }

    JSONObject toJson() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("config_version", configVersion);
        root.put("endpoint", endpoint);
        root.put("ports", new JSONArray(ports));
        JSONObject wg = new JSONObject();
        wg.put("server_public_key", serverPublicKey);
        wg.put("address", address);
        wg.put("dns", new JSONArray(dns));
        wg.put("mtu", mtu);
        wg.put("persistent_keepalive", keepalive);
        root.put("wireguard", wg);
        return root;
    }
}
