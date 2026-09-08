package com.flowlink.client;

import android.net.Network;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

final class ApiClient {
    private final String base;
    private final SSLContext sslContext;

    ApiClient(String host, String fingerprint) throws Exception {
        base = "https://" + ServerProfile.normalizeHost(host);
        sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[]{
                new PinnedTrustManager(ServerProfile.normalizeFingerprint(fingerprint))}, null);
    }

    JSONObject enroll(String enrollmentToken, String publicKey,
                      String deviceName, Network network) throws Exception {
        JSONObject request = new JSONObject();
        request.put("enrollment_token", enrollmentToken);
        request.put("public_key", publicKey);
        request.put("device_name", deviceName);
        Response response = request("POST", "/v1/enroll", request.toString(),
                null, network);
        verifySignature(enrollmentToken, response);
        return new JSONObject(response.body);
    }

    JSONObject config(String deviceToken, Network network) throws Exception {
        Response response = request("GET", "/v1/config", null,
                deviceToken, network);
        verifySignature(deviceToken, response);
        return new JSONObject(response.body);
    }

    JSONObject appUpdate(Network network) throws Exception {
        return new JSONObject(request("GET", "/v1/app/update", null,
                null, network).body);
    }

    boolean health(Network network) {
        try {
            Response response = request("GET", "/healthz", null, null, network);
            return new JSONObject(response.body).optBoolean("ok", false);
        } catch (Exception ignored) {
            return false;
        }
    }

    void download(String path, File destination, long expectedSize,
                  Network network) throws Exception {
        if (path == null || !path.startsWith("/") || path.startsWith("//"))
            throw new SecurityException("invalid update path");
        URL url = new URL(base + path);
        HttpsURLConnection connection = open(url, network);
        connection.setRequestMethod("GET");
        int status = connection.getResponseCode();
        if (status != 200) {
            connection.disconnect();
            throw new IllegalStateException("download returned " + status);
        }
        long limit = Math.min(Math.max(expectedSize + 1024, 1024 * 1024),
                128L * 1024 * 1024);
        long total = 0;
        try (InputStream input = connection.getInputStream();
             OutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > limit) throw new SecurityException("update too large");
                output.write(buffer, 0, count);
            }
        } finally {
            connection.disconnect();
        }
        if (expectedSize > 0 && total != expectedSize)
            throw new SecurityException("update size mismatch");
    }

    private Response request(String method, String path, String body,
                             String bearer, Network network) throws Exception {
        HttpsURLConnection connection = open(new URL(base + path), network);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        if (bearer != null)
            connection.setRequestProperty("Authorization", "Bearer " + bearer);
        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
        }
        int status = connection.getResponseCode();
        InputStream input = status >= 400
                ? connection.getErrorStream() : connection.getInputStream();
        String responseBody = input == null ? "" : readFully(input);
        String signature = connection.getHeaderField("X-FlowLink-Signature");
        connection.disconnect();
        if (status < 200 || status >= 300)
            throw new IllegalStateException("Server returned " + status + ": " + responseBody);
        return new Response(responseBody, signature);
    }

    private HttpsURLConnection open(URL url, Network network) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection)
                (network == null ? url.openConnection() : network.openConnection(url));
        connection.setSSLSocketFactory(sslContext.getSocketFactory());
        connection.setHostnameVerifier(HttpsURLConnection.getDefaultHostnameVerifier());
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(15000);
        return connection;
    }

    private static String readFully(InputStream input) throws Exception {
        try (InputStream stream = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void verifySignature(String token, Response response) throws Exception {
        if (response.signature == null) throw new SecurityException("Missing signature");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String actual = hex(mac.doFinal(response.body.getBytes(StandardCharsets.UTF_8)));
        if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),
                response.signature.getBytes(StandardCharsets.US_ASCII)))
            throw new SecurityException("Invalid configuration signature");
    }

    static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) value.append(String.format("%02x", item & 0xff));
        return value.toString();
    }

    private record Response(String body, String signature) {}

    private static final class PinnedTrustManager implements X509TrustManager {
        private final String expected;
        PinnedTrustManager(String expected) { this.expected = expected; }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {
            throw new UnsupportedOperationException();
        }
        @Override public void checkServerTrusted(X509Certificate[] chain,
                                                 String authType)
                throws java.security.cert.CertificateException {
            if (chain == null || chain.length == 0)
                throw new java.security.cert.CertificateException("No server certificate");
            chain[0].checkValidity();
            try {
                String fingerprint = hex(MessageDigest.getInstance("SHA-256")
                        .digest(chain[0].getEncoded())).toUpperCase();
                if (!MessageDigest.isEqual(
                        fingerprint.getBytes(StandardCharsets.US_ASCII),
                        expected.getBytes(StandardCharsets.US_ASCII)))
                    throw new java.security.cert.CertificateException(
                            "FlowLink certificate pin mismatch");
            } catch (java.security.cert.CertificateException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new java.security.cert.CertificateException(exception);
            }
        }
        @Override public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
