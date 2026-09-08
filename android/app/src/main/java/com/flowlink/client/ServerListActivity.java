package com.flowlink.client;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;

public final class ServerListActivity extends Activity {
    private FlowLinkStore store;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new FlowLinkStore(this);
    }

    @Override protected void onResume() {
        super.onResume();
        buildUi();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BACKGROUND);
        LinearLayout root = Ui.column(this, 22, 18, 22, 28);

        LinearLayout header = Ui.row(this);
        TextView back = Ui.iconButton(this, "‹");
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(
                Ui.dp(this, 46), Ui.dp(this, 46)));
        TextView title = Ui.text(this, "服务器", 23, Ui.INK, true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        titleParams.leftMargin = Ui.dp(this, 15);
        header.addView(title, titleParams);
        TextView add = Ui.iconButton(this, "＋");
        add.setOnClickListener(v -> startActivity(
                new Intent(this, AddServerActivity.class)));
        header.addView(add, new LinearLayout.LayoutParams(
                Ui.dp(this, 46), Ui.dp(this, 46)));
        root.addView(header);

        TextView hint = Ui.text(this, "选择要使用的节点", 14, Ui.MUTED, false);
        hint.setPadding(Ui.dp(this, 2), Ui.dp(this, 25), 0, Ui.dp(this, 12));
        root.addView(hint);

        List<ServerProfile> profiles = store.profiles();
        ServerProfile active = store.active();
        if (profiles.isEmpty()) {
            LinearLayout empty = Ui.card(this, 28);
            empty.setGravity(Gravity.CENTER);
            TextView emptyTitle = Ui.text(this, "还没有服务器", 18, Ui.INK, true);
            TextView emptyText = Ui.text(this, "点击右上角＋添加第一台 VPS",
                    14, Ui.MUTED, false);
            emptyText.setPadding(0, Ui.dp(this, 8), 0, 0);
            empty.addView(emptyTitle);
            empty.addView(emptyText);
            root.addView(empty, Ui.matchWrap(this));
        } else {
            for (ServerProfile profile : profiles)
                root.addView(serverRow(profile,
                        active != null && active.id.equals(profile.id)));
        }
        scroll.addView(root);
        setContentView(scroll);
    }

    private LinearLayout serverRow(ServerProfile profile, boolean selected) {
        LinearLayout card = Ui.card(this, 18);
        LinearLayout.LayoutParams cardParams = Ui.matchWrap(this);
        cardParams.bottomMargin = Ui.dp(this, 12);
        card.setLayoutParams(cardParams);

        LinearLayout top = Ui.row(this);
        TextView icon = Ui.text(this, selected ? "✓" : "◎", 20,
                selected ? android.graphics.Color.WHITE : Ui.GREEN, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(Ui.oval(selected ? Ui.GREEN : Ui.PALE_GREEN));
        top.addView(icon, new LinearLayout.LayoutParams(
                Ui.dp(this, 44), Ui.dp(this, 44)));
        LinearLayout labels = Ui.column(this, 0, 0, 0, 0);
        labels.addView(Ui.text(this, profile.name, 17, Ui.INK, true));
        TextView host = Ui.text(this, profile.host + "  ·  UDP " + profile.currentPort,
                13, Ui.MUTED, false);
        host.setPadding(0, Ui.dp(this, 4), 0, 0);
        labels.addView(host);
        LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        labelsParams.leftMargin = Ui.dp(this, 13);
        top.addView(labels, labelsParams);
        if (selected) top.addView(Ui.pill(this, "当前", Ui.SOFT_GREEN, Ui.GREEN));
        card.addView(top);

        LinearLayout actions = Ui.row(this);
        actions.setPadding(Ui.dp(this, 57), Ui.dp(this, 14), 0, 0);
        TextView choose = Ui.text(this, selected ? "正在使用" : "设为当前服务器",
                14, selected ? Ui.MUTED : Ui.GREEN, true);
        choose.setPadding(0, Ui.dp(this, 8), Ui.dp(this, 22), Ui.dp(this, 8));
        choose.setEnabled(!selected);
        choose.setOnClickListener(v -> select(profile));
        actions.addView(choose);
        TextView remove = Ui.text(this, "删除", 14, Ui.RED, true);
        remove.setPadding(Ui.dp(this, 12), Ui.dp(this, 8),
                Ui.dp(this, 12), Ui.dp(this, 8));
        remove.setOnClickListener(v -> confirmRemove(profile, selected));
        actions.addView(remove);
        card.addView(actions);
        card.setOnClickListener(v -> { if (!selected) select(profile); });
        return card;
    }

    private void select(ServerProfile profile) {
        store.select(profile.id);
        if (store.autoConnect()) startServiceAction(FlowLinkMonitorService.ACTION_SWITCH);
        Toast.makeText(this, "已切换到 " + profile.name, Toast.LENGTH_SHORT).show();
        buildUi();
    }

    private void confirmRemove(ServerProfile profile, boolean selected) {
        new AlertDialog.Builder(this)
                .setTitle("删除服务器？")
                .setMessage("只会从这台手机移除“" + profile.name + "”，不会删除 VPS。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> remove(profile, selected))
                .show();
    }

    private void remove(ServerProfile profile, boolean selected) {
        try {
            if (selected && store.autoConnect())
                startServiceAction(FlowLinkMonitorService.ACTION_STOP);
            store.removeProfile(profile.id);
            Toast.makeText(this, "已移除 " + profile.name, Toast.LENGTH_SHORT).show();
            buildUi();
        } catch (Exception exception) {
            Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void startServiceAction(String action) {
        startForegroundService(new Intent(this, FlowLinkMonitorService.class).setAction(action));
    }
}
