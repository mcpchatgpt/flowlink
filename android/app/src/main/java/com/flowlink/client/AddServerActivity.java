package com.flowlink.client;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.wireguard.crypto.KeyPair;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AddServerActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private FlowLinkStore store;
    private EditText profileName;
    private EditText serverHost;
    private EditText fingerprint;
    private EditText enrollmentToken;
    private TextView message;
    private Button registerButton;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        store = new FlowLinkStore(this);
        buildUi();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BACKGROUND);
        LinearLayout root = Ui.column(this, 22, 18, 22, 28);
        root.setFocusableInTouchMode(true);
        root.requestFocus();

        LinearLayout header = Ui.row(this);
        TextView back = Ui.iconButton(this, "‹");
        back.setContentDescription("返回");
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(
                Ui.dp(this, 46), Ui.dp(this, 46)));
        TextView title = Ui.text(this, "添加 VPS", 23, Ui.INK, true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        titleParams.leftMargin = Ui.dp(this, 15);
        header.addView(title, titleParams);
        root.addView(header);

        TextView intro = Ui.text(this, "连接一台新的 FlowLink 服务器", 15,
                Ui.MUTED, false);
        intro.setPadding(Ui.dp(this, 2), Ui.dp(this, 25), 0, Ui.dp(this, 16));
        root.addView(intro);

        LinearLayout form = Ui.card(this, 20);
        profileName = addField(form, "服务器名称", "例如：马来西亚节点", false);
        serverHost = addField(form, "服务器地址", "IP 地址或域名", false);
        fingerprint = addField(form, "TLS 证书指纹", "粘贴 SHA-256 指纹", true);
        enrollmentToken = addField(form, "一次性配对令牌", "粘贴 15 分钟令牌", true);
        if (store.profiles().isEmpty()) {
            serverHost.setText(BuildConfig.FLOWLINK_HOST);
            fingerprint.setText(BuildConfig.FLOWLINK_CERT_SHA256);
        }

        message = Ui.text(this, "", 13, Ui.RED, false);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, Ui.dp(this, 14), 0, 0);
        form.addView(message);

        registerButton = Ui.primaryButton(this, "安全添加服务器");
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 56));
        buttonParams.topMargin = Ui.dp(this, 18);
        form.addView(registerButton, buttonParams);
        registerButton.setOnClickListener(v -> registerDevice());
        root.addView(form, Ui.matchWrap(this));

        TextView note = Ui.text(this,
                "服务器私钥不会进入手机；手机私钥仅在本机生成并加密保存。",
                12, Ui.MUTED, false);
        note.setGravity(Gravity.CENTER);
        note.setPadding(Ui.dp(this, 12), Ui.dp(this, 22),
                Ui.dp(this, 12), 0);
        root.addView(note);
        scroll.addView(root);
        setContentView(scroll);
    }

    private EditText addField(LinearLayout parent, String label,
                              String hint, boolean visiblePassword) {
        TextView name = Ui.text(this, label, 13, Ui.INK, true);
        LinearLayout.LayoutParams labelParams = Ui.matchWrap(this);
        labelParams.topMargin = parent.getChildCount() == 0 ? 0 : Ui.dp(this, 17);
        parent.addView(name, labelParams);
        EditText input = Ui.input(this, hint, visiblePassword);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 55));
        inputParams.topMargin = Ui.dp(this, 8);
        parent.addView(input, inputParams);
        return input;
    }

    private void registerDevice() {
        final String name = profileName.getText().toString().trim();
        final String rawHost = serverHost.getText().toString().trim();
        final String rawPin = fingerprint.getText().toString().trim();
        final String token = enrollmentToken.getText().toString().trim();
        if (rawHost.isEmpty() || rawPin.isEmpty() || token.isEmpty()) {
            message.setText("请完整填写服务器地址、证书指纹和配对令牌");
            return;
        }
        registerButton.setEnabled(false);
        registerButton.setText("正在安全注册…");
        message.setText("");
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
                    Toast.makeText(this, "服务器添加成功", Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    finish();
                });
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    message.setText(friendly(exception));
                    registerButton.setEnabled(true);
                    registerButton.setText("安全添加服务器");
                });
            }
        });
    }

    private static String friendly(Exception exception) {
        String value = exception.getMessage();
        if (value == null) return "添加失败，请检查网络和令牌";
        if (value.contains("400")) return "配对令牌无效、已过期或已经使用";
        if (value.contains("fingerprint") || value.contains("pin"))
            return "TLS 证书指纹不正确";
        return "无法连接服务器，请检查地址、网络和证书指纹";
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
