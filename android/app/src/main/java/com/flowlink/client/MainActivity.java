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
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private static final int VPN_REQUEST = 10;
    private FlowLinkStore store;
    private TextView status;
    private TextView detail;
    private TextView power;
    private TextView serverName;
    private TextView serverDetail;
    private Button connectButton;
    private boolean connected;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String value = intent.getStringExtra("status");
            String name = intent.getStringExtra("profile");
            String host = intent.getStringExtra("host");
            int port = intent.getIntExtra("port", -1);
            connected = value != null && (value.startsWith("已连接")
                    || value.contains("VPN正常"));
            renderConnection(value == null ? "状态未知" : value,
                    name, host, port);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new FlowLinkStore(this);
        buildUi();
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
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BACKGROUND);
        LinearLayout root = Ui.column(this, 22, 22, 22, 28);
        root.setFocusableInTouchMode(true);
        root.requestFocus();

        LinearLayout header = Ui.row(this);
        LinearLayout brand = Ui.column(this, 0, 0, 0, 0);
        TextView title = Ui.text(this, "FlowLink", 29, Ui.INK, true);
        TextView subtitle = Ui.text(this, "智能守护你的网络连接", 14, Ui.MUTED, false);
        subtitle.setPadding(0, Ui.dp(this, 4), 0, 0);
        brand.addView(title);
        brand.addView(subtitle);
        header.addView(brand, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView add = Ui.iconButton(this, "＋");
        add.setContentDescription("添加 VPS");
        add.setOnClickListener(v -> startActivity(
                new Intent(this, AddServerActivity.class)));
        header.addView(add, new LinearLayout.LayoutParams(
                Ui.dp(this, 48), Ui.dp(this, 48)));
        root.addView(header);

        LinearLayout hero = Ui.card(this, 24);
        LinearLayout.LayoutParams heroParams = Ui.matchWrap(this);
        heroParams.topMargin = Ui.dp(this, 28);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);

        hero.addView(Ui.pill(this, "  安全连接  ", Ui.SOFT_GREEN, Ui.GREEN));
        power = Ui.text(this, "⌁", 52, Ui.GREEN, true);
        power.setGravity(Gravity.CENTER);
        power.setBackground(Ui.oval(Ui.PALE_GREEN));
        LinearLayout.LayoutParams powerParams = new LinearLayout.LayoutParams(
                Ui.dp(this, 116), Ui.dp(this, 116));
        powerParams.topMargin = Ui.dp(this, 24);
        powerParams.bottomMargin = Ui.dp(this, 20);
        hero.addView(power, powerParams);

        status = Ui.text(this, "未连接", 25, Ui.INK, true);
        status.setGravity(Gravity.CENTER);
        hero.addView(status);
        detail = Ui.text(this, "选择服务器后即可安全连接", 14, Ui.MUTED, false);
        detail.setGravity(Gravity.CENTER);
        detail.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 22));
        hero.addView(detail);

        connectButton = Ui.primaryButton(this, "连接");
        connectButton.setOnClickListener(v -> {
            if (store.autoConnect()) stopVpn(); else requestVpn();
        });
        hero.addView(connectButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 56)));
        root.addView(hero, heroParams);

        TextView section = Ui.text(this, "当前服务器", 14, Ui.MUTED, true);
        section.setPadding(Ui.dp(this, 2), Ui.dp(this, 26), 0, Ui.dp(this, 10));
        root.addView(section);

        LinearLayout serverCard = Ui.card(this, 18);
        serverCard.setGravity(Gravity.CENTER_VERTICAL);
        serverCard.setOrientation(LinearLayout.HORIZONTAL);
        TextView serverIcon = Ui.text(this, "◎", 25, Ui.GREEN, true);
        serverIcon.setGravity(Gravity.CENTER);
        serverIcon.setBackground(Ui.oval(Ui.PALE_GREEN));
        serverCard.addView(serverIcon, new LinearLayout.LayoutParams(
                Ui.dp(this, 48), Ui.dp(this, 48)));
        LinearLayout serverText = Ui.column(this, 0, 0, 0, 0);
        serverName = Ui.text(this, "尚未添加服务器", 17, Ui.INK, true);
        serverDetail = Ui.text(this, "点击右上角＋添加", 13, Ui.MUTED, false);
        serverDetail.setPadding(0, Ui.dp(this, 4), 0, 0);
        serverText.addView(serverName);
        serverText.addView(serverDetail);
        LinearLayout.LayoutParams serverTextParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        serverTextParams.leftMargin = Ui.dp(this, 14);
        serverCard.addView(serverText, serverTextParams);
        serverCard.addView(Ui.text(this, "›", 30, Ui.MUTED, false));
        serverCard.setOnClickListener(v -> startActivity(
                new Intent(this, ServerListActivity.class)));
        root.addView(serverCard, Ui.matchWrap(this));

        TextView update = Ui.text(this, "检查应用更新", 15, Ui.GREEN, true);
        update.setGravity(Gravity.CENTER);
        update.setPadding(0, Ui.dp(this, 25), 0, Ui.dp(this, 14));
        update.setOnClickListener(v -> checkUpdate());
        root.addView(update);

        TextView foot = Ui.text(this, "端口故障时自动切换 · 节点不可用时自动尝试下一台 VPS",
                12, Ui.MUTED, false);
        foot.setGravity(Gravity.CENTER);
        root.addView(foot);

        scroll.addView(root);
        setContentView(scroll);
    }

    @Override protected void onResume() {
        super.onResume();
        refreshProfile();
    }

    private void refreshProfile() {
        ServerProfile profile = store.active();
        if (profile == null) {
            serverName.setText("尚未添加服务器");
            serverDetail.setText("点击右上角＋添加");
            connected = false;
            renderConnection("未连接", null, null, -1);
            connectButton.setEnabled(false);
            return;
        }
        serverName.setText(profile.name);
        serverDetail.setText(profile.host + "  ·  UDP " + profile.currentPort);
        connectButton.setEnabled(true);
        connected = store.autoConnect() && TunnelController.get(this).isUp();
        renderConnection(connected ? "已连接" :
                        (store.autoConnect() ? "正在恢复连接" : "准备就绪"),
                profile.name, profile.host, profile.currentPort);
    }

    private void renderConnection(String value, String name, String host, int port) {
        status.setText(value);
        boolean active = connected || store.autoConnect();
        power.setText(active ? "✓" : "⌁");
        power.setTextColor(active ? Color.WHITE : Ui.GREEN);
        power.setBackground(Ui.oval(active ? Ui.GREEN : Ui.PALE_GREEN));
        connectButton.setText(active ? "断开连接" : "连接");
        connectButton.setBackground(Ui.rounded(active ? Ui.SOFT_RED : Ui.GREEN, 18));
        connectButton.setTextColor(active ? Ui.RED : Color.WHITE);
        if (host != null) {
            detail.setText((name == null ? "" : name + "  ·  ") + host
                    + (port > 0 ? "  ·  UDP " + port : ""));
            serverName.setText(name == null ? host : name);
            serverDetail.setText(host + (port > 0 ? "  ·  UDP " + port : ""));
        } else {
            detail.setText(store.registered() ? "点击连接以启动安全隧道"
                    : "添加一台 VPS 后即可开始");
        }
    }

    private void requestVpn() {
        if (!store.registered()) {
            startActivity(new Intent(this, AddServerActivity.class));
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
        store.setAutoConnect(true);
        startServiceAction(FlowLinkMonitorService.ACTION_START);
        connected = false;
        renderConnection("正在连接", null, null, -1);
    }

    private void stopVpn() {
        store.setAutoConnect(false);
        startServiceAction(FlowLinkMonitorService.ACTION_STOP);
        connected = false;
        renderConnection("已断开", null, null, -1);
    }

    private void checkUpdate() {
        if (!store.registered()) {
            startActivity(new Intent(this, AddServerActivity.class));
            return;
        }
        startServiceAction(FlowLinkMonitorService.ACTION_CHECK_UPDATE);
        status.setText("正在检查更新");
    }

    private void startServiceAction(String action) {
        Intent intent = new Intent(this, FlowLinkMonitorService.class).setAction(action);
        startForegroundService(intent);
    }

    @Override protected void onDestroy() {
        unregisterReceiver(statusReceiver);
        super.onDestroy();
    }
}
