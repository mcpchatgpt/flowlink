package com.flowlink.client;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Network;
import android.net.Uri;
import androidx.core.content.FileProvider;
import org.json.JSONObject;
import java.io.File;

final class UpdateManager {
    private static final String CHANNEL = "flowlink_updates";
    private static final int NOTIFICATION_ID = 78;

    private UpdateManager() {}

    static String checkAndDownload(Context context, ServerProfile profile,
                                   Network network) throws Exception {
        ApiClient api = new ApiClient(profile.host, profile.fingerprint);
        JSONObject update = api.appUpdate(network);
        if (!update.optBoolean("available", false)) return null;
        int versionCode = update.getInt("version_code");
        if (versionCode <= BuildConfig.VERSION_CODE) return null;
        long size = update.getLong("size");
        String wantedHash = update.getString("sha256").toLowerCase();
        File directory = new File(context.getCacheDir(), "updates");
        if (!directory.exists() && !directory.mkdirs())
            throw new IllegalStateException("cannot create update directory");
        File temporary = new File(directory, "FlowLink-update.apk.part");
        File apk = new File(directory, "FlowLink-update.apk");
        api.download(update.getString("url"), temporary, size, network);
        if (!wantedHash.equals(ApiClient.sha256(temporary))) {
            temporary.delete();
            throw new SecurityException("update checksum mismatch");
        }
        if (apk.exists() && !apk.delete())
            throw new IllegalStateException("cannot replace old update");
        if (!temporary.renameTo(apk))
            throw new IllegalStateException("cannot finalize update");
        notifyInstall(context, apk, update.getString("version_name"));
        return update.getString("version_name");
    }

    private static void notifyInstall(Context context, File apk, String version) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                "FlowLink 更新", NotificationManager.IMPORTANCE_HIGH));
        Uri uri = FileProvider.getUriForFile(context,
                context.getPackageName() + ".files", apk);
        Intent action = new Intent(Intent.ACTION_VIEW).setDataAndType(uri,
                "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_ACTIVITY_NEW_TASK);
        String text = "版本 " + version + " 已验证，点击安装";
        PendingIntent pending = PendingIntent.getActivity(context, 78, action,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_flowlink)
                .setContentTitle("FlowLink 有新版本")
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build();
        manager.notify(NOTIFICATION_ID, notification);
    }
}
