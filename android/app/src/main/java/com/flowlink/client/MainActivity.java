package com.flowlink.client;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import com.wireguard.crypto.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int VPN_REQUEST = 10;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private FlowLinkStore store;
    private Spinner profileSpinner;
    private EditText profileName;
    private EditText serverHost;
    private EditText fingerprint;
    private EditText enrollmentToken;
    private TextView status;
    private TextView detail;
    private Button registerButton;
    private Button connectButton;
    private Button disconnectButton;
    private Button removeButton;
    private boolean refreshingProfiles;
    private List<ServerProfile> shownProfiles = new ArrayList<>();

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String value = intent.getStringExtra("status");
            String name = intent.getStringExtra("profile");
            String host = intent.getStringExtra("host");
            int port = intent.getIntExtra("port", -1);
            status.setText(value == null ? "状态未知" : value);
            if (host != null)
                detail.setText((name == null ? "" : name + " · ") + host
                        + (port > 0 ? " · UDP " + port : ""));
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new FlowLinkStore(this);
        buildUi();
        refreshProfiles();
        IntentFilter filter = new IntentFilter(FlowLinkMonitorService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED);
        else
            registerReceiver(statusReceiver, filter);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != getPackageManager().PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 20);
    }

    private void buildUi() {
        int padding = dp(24);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, dp(40), padding, padding);
        root.setBackgroundColor(Color.rgb(244, 247, 246));

        TextView title = text("FlowLink", 30, Color.rgb(20, 55, 49));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);
        TextView subtitle = text("多服务器 · 自动换端口 · 自动更新", 15,
                Color.rgb(83, 105, 100));
        subtitle.setPadding(0, dp(6), 0, dp(22));
        root.addView(subtitle);

        LinearLayout card = card();
        status = text("未注册", 24, Color.rgb(23, 107, 91));
        status.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(status);
        detail = text("尚未添加服务器", 14, Color.rgb(93, 108, 104));
        detail.setPadding(0, dp(8), 0, dp(12));
        card.addView(detail);

        profileSpinner = new Spinner(this);
        profileSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent,
                                                 View view, int position, long id) {
                if (refreshingProfiles || position < 0 || position >= shownProfiles.size()) return;
                ServerProfile selected = shownProfiles.get(position);
                store.select(selected.id);
                detail.setText(selected.name + " · " + selected.host);
                if (store.autoConnect()) startServiceAction(FlowLinkMonitorService.ACTION_SWITCH);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        card.addView(profileSpinner, matchWrap());

        connectButton = button("连接 VPN");
        connectButton.setOnClickListener(v -> requestVpn());
        card.addView(connectButton, matchWrap());
        disconnectButton = button("断开连接");
        disconnectButton.setOnClickListener(v -> stopVpn());
        card.addView(disconnectButton, matchWrap());
        Button updateButton = button("检查应用更新");
        updateButton.setOnClickListener(v -> checkUpdate());
        card.addView(updateButton, matchWrap());
        removeButton = button("删除当前服务器");
        removeButton.setOnClickListener(v -> removeCurrent());
        card.addView(removeButton, matchWrap());
        root.addView(card, matchWrap());

        TextView addTitle = text("添加另一台 VPS", 20, Color.rgb(20, 55, 49));
        addTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        addTitle.setPadding(0, dp(26), 0, dp(8));
        root.addView(addTitle);
        LinearLayout addCard = card();
        profileName = input("名称，例如 vps-2", false);
        serverHost = input("服务器 IP 或域名", false);
        fingerprint = input("TLS SHA-256 指纹", true);
        enrollmentToken = input("一次性配对令牌", true);
        serverHost.setText(BuildConfig.FLOWLINK_HOST);
        fingerprint.setText(BuildConfig.FLOWLINK_CERT_SHA256);
        addCard.addView(profileName, matchWrap());
        addCard.addView(serverHost, matchWrap());
        addCard.addView(fingerprint, matchWrap());
        addCard.addView(enrollmentToken, matchWrap());
        registerButton = button("添加并注册服务器");
        registerButton.setOnClickListener(v -> registerDevice());
        addCard.addView(registerButton, matchWrap());
        root.addView(addCard, matchWrap());

        TextView note = text("同一时间只运行一个 VPN。当前服务器全部端口失败后，"
                + "FlowLink 会尝试下一台已注册 VPS。应用更新会自动下载和校验，"
                + "安装时仍需通过 Android 系统确认。", 14, Color.rgb(83, 105, 100));
        note.setPadding(0, dp(24), 0, 0);
        root.addView(note);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void registerDevice() {
        final String name = profileName.getText().toString().trim();
        final String rawHost = serverHost.getText().toString().trim();
        final String rawPin = fingerprint.getText().toString().trim();
        final String token = enrollmentToken.getText().toString().trim();
        if (rawHost.isEmpty() || rawPin.isEmpty() || token.isEmpty()) {
            status.setText("请填写服务器、证书指纹和令牌");
            return;
        }
        registerButton.setEnabled(false);
        status.setText("正在安全注册");
        worker.execute(() -> {
            try {
                String host = ServerProfile.normalizeHost(rawHost);
                String pin = ServerProfile.normalizeFingerprint(rawPin);
                KeyPair pair = new KeyPair();
                ApiClient api = new ApiClient(host, pin);
                org.json.JSONObject response = api.enroll(token,
                        pair.getPublicKey().toBase64(),
                        Build.MODEL == null ? "Android" : Build.MODEL, null);
                FlowConfig config = FlowConfig.fromServer(response);
                store.addProfile(name, host, pin, response.getString("device_token"),
                        pair.getPrivateKey().toBase64(), config);
                runOnUiThread(() -> {
                    enrollmentToken.setText("");
                    profileName.setText("");
                    status.setText("注册完成");
                    registerButton.setEnabled(true);
                    refreshProfiles();
                });
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    status.setText("注册失败：" + friendly(exception));
                    registerButton.setEnabled(true);
                });
            }
        });
    }

    private void requestVpn() {
        if (!store.registered()) {
            status.setText("请先添加并注册服务器");
            return;
        }
        Intent permission = VpnService.prepare(this);
        if (permission == null) startVpn();
        else startActivityForResult(permission, VPN_REQUEST);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode,
                                               Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST && resultCode == RESULT_OK) startVpn();
    }

    private void startVpn() {
        startServiceAction(FlowLinkMonitorService.ACTION_START);
        status.setText("正在连接");
    }

    private void stopVpn() {
        startServiceAction(FlowLinkMonitorService.ACTION_STOP);
    }

    private void checkUpdate() {
        if (!store.registered()) {
            status.setText("请先注册服务器");
            return;
        }
        startServiceAction(FlowLinkMonitorService.ACTION_CHECK_UPDATE);
        status.setText("正在检查更新");
    }

    private void removeCurrent() {
        ServerProfile profile = store.active();
        if (profile == null) return;
        stopVpn();
        try {
            store.removeProfile(profile.id);
            status.setText("服务器已从手机删除");
            refreshProfiles();
        } catch (Exception exception) {
            status.setText("删除失败");
        }
    }

    private void startServiceAction(String action) {
        Intent intent = new Intent(this, FlowLinkMonitorService.class).setAction(action);
        startForegroundService(intent);
    }

    private void refreshProfiles() {
        refreshingProfiles = true;
        shownProfiles = store.profiles();
        ArrayAdapter<ServerProfile> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, shownProfiles);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        profileSpinner.setAdapter(adapter);
        ServerProfile active = store.active();
        int selected = 0;
        for (int i = 0; active != null && i < shownProfiles.size(); i++)
            if (shownProfiles.get(i).id.equals(active.id)) selected = i;
        if (!shownProfiles.isEmpty()) profileSpinner.setSelection(selected);
        boolean any = !shownProfiles.isEmpty();
        connectButton.setEnabled(any);
        disconnectButton.setEnabled(any);
        removeButton.setEnabled(any);
        if (active != null) {
            detail.setText(active.name + " · " + active.host);
            if (!store.autoConnect()) status.setText("已注册，等待连接");
        } else {
            detail.setText("尚未添加服务器");
            status.setText("未注册");
        }
        refreshingProfiles = false;
    }

    private static String friendly(Exception exception) {
        String value = exception.getMessage();
        if (value == null) return "请检查网络和令牌";
        if (value.contains("400")) return "令牌无效、过期或已经使用";
        if (value.contains("fingerprint") || value.contains("pin"))
            return "服务器证书指纹不正确";
        return "请检查服务器地址、网络和证书指纹";
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        card.setBackgroundColor(Color.WHITE);
        return card;
    }

    private EditText input(String hint, boolean visiblePassword) {
        EditText value = new EditText(this);
        value.setHint(hint);
        value.setSingleLine(true);
        value.setInputType(InputType.TYPE_CLASS_TEXT | (visiblePassword
                ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                : InputType.TYPE_TEXT_VARIATION_NORMAL));
        return value;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(10);
        button.setLayoutParams(params);
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onDestroy() {
        unregisterReceiver(statusReceiver);
        worker.shutdownNow();
        super.onDestroy();
    }
}
