package com.flowlink.client;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class FlowLinkStore {
    private static final String PROFILES = "profiles_v2";
    private static final String ACTIVE = "active_profile";
    private static final String PRIVATE_PREFIX = "profile_private_";
    private static final String TOKEN_PREFIX = "profile_token_";
    private final SharedPreferences prefs;
    final SecureStore secure;

    FlowLinkStore(Context context) {
        prefs = context.getSharedPreferences("flowlink.state", Context.MODE_PRIVATE);
        secure = new SecureStore(context);
        migrateLegacy();
    }

    synchronized List<ServerProfile> profiles() {
        List<ServerProfile> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString(PROFILES, "[]"));
            for (int i = 0; i < array.length(); i++)
                result.add(ServerProfile.fromJson(array.getJSONObject(i)));
        } catch (Exception ignored) {
            // Treat malformed metadata as empty; encrypted secrets remain untouched.
        }
        return result;
    }

    synchronized ServerProfile active() {
        List<ServerProfile> values = profiles();
        if (values.isEmpty()) return null;
        String wanted = prefs.getString(ACTIVE, values.get(0).id);
        for (ServerProfile profile : values)
            if (profile.id.equals(wanted)) return profile;
        return values.get(0);
    }

    synchronized ServerProfile addProfile(String name, String host,
                                           String fingerprint,
                                           String deviceToken,
                                           String privateKey,
                                           FlowConfig config) throws Exception {
        String id = UUID.randomUUID().toString().replace("-", "");
        ServerProfile profile = new ServerProfile(id,
                name == null || name.isBlank() ? host : name.trim(),
                host, fingerprint, config, config.ports.get(0));
        secure.put(PRIVATE_PREFIX + id, privateKey);
        secure.put(TOKEN_PREFIX + id, deviceToken);
        List<ServerProfile> values = profiles();
        values.add(profile);
        saveProfiles(values);
        prefs.edit().putString(ACTIVE, id).apply();
        return profile;
    }

    synchronized void select(String id) {
        for (ServerProfile profile : profiles()) {
            if (profile.id.equals(id)) {
                prefs.edit().putString(ACTIVE, id).apply();
                return;
            }
        }
    }

    synchronized void updateConfig(String id, FlowConfig config) throws Exception {
        List<ServerProfile> values = profiles();
        for (int i = 0; i < values.size(); i++)
            if (values.get(i).id.equals(id)) values.set(i, values.get(i).withConfig(config));
        saveProfiles(values);
    }

    synchronized void setCurrentPort(String id, int port) throws Exception {
        List<ServerProfile> values = profiles();
        for (int i = 0; i < values.size(); i++)
            if (values.get(i).id.equals(id)) values.set(i, values.get(i).withPort(port));
        saveProfiles(values);
    }

    synchronized void removeProfile(String id) throws Exception {
        List<ServerProfile> values = profiles();
        values.removeIf(profile -> profile.id.equals(id));
        saveProfiles(values);
        secure.remove(PRIVATE_PREFIX + id);
        secure.remove(TOKEN_PREFIX + id);
        if (id.equals(prefs.getString(ACTIVE, "")))
            prefs.edit().putString(ACTIVE,
                    values.isEmpty() ? "" : values.get(0).id).apply();
    }

    String privateKey(ServerProfile profile) throws Exception {
        return secure.get(PRIVATE_PREFIX + profile.id);
    }

    String deviceToken(ServerProfile profile) throws Exception {
        return secure.get(TOKEN_PREFIX + profile.id);
    }

    boolean registered() {
        ServerProfile profile = active();
        if (profile == null) return false;
        try {
            return privateKey(profile) != null && deviceToken(profile) != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    boolean autoConnect() {
        return prefs.getBoolean("auto_connect", false);
    }

    void setAutoConnect(boolean enabled) {
        prefs.edit().putBoolean("auto_connect", enabled).apply();
    }

    long lastUpdateCheck() {
        return prefs.getLong("last_update_check", 0);
    }

    void setLastUpdateCheck(long value) {
        prefs.edit().putLong("last_update_check", value).apply();
    }

    private void saveProfiles(List<ServerProfile> values) throws Exception {
        JSONArray array = new JSONArray();
        for (ServerProfile value : values) array.put(value.toJson());
        prefs.edit().putString(PROFILES, array.toString()).apply();
    }

    private void migrateLegacy() {
        if (prefs.contains(PROFILES)) return;
        try {
            String rawConfig = prefs.getString("config", null);
            String privateKey = secure.get("private_key");
            String deviceToken = secure.get("device_token");
            if (rawConfig != null && privateKey != null && deviceToken != null) {
                FlowConfig config = FlowConfig.fromServer(
                        new org.json.JSONObject(rawConfig));
                String id = "legacy-my";
                ServerProfile profile = new ServerProfile(id, "my",
                        BuildConfig.FLOWLINK_HOST,
                        BuildConfig.FLOWLINK_CERT_SHA256, config,
                        prefs.getInt("current_port", config.ports.get(0)));
                secure.put(PRIVATE_PREFIX + id, privateKey);
                secure.put(TOKEN_PREFIX + id, deviceToken);
                saveProfiles(java.util.List.of(profile));
                prefs.edit().putString(ACTIVE, id).apply();
            } else {
                prefs.edit().putString(PROFILES, "[]").apply();
            }
        } catch (Exception ignored) {
            prefs.edit().putString(PROFILES, "[]").apply();
        }
    }
}
