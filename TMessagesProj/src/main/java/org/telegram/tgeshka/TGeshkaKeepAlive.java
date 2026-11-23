/*
 * This file is part of TGeshka, an unofficial client based on Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package org.telegram.tgeshka;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.LaunchActivity;

/**
 * "TGeshka на связи": without FCM the only way to get messages in the
 * background is to keep the tgnet push connection open. NotificationsService
 * runs as a foreground service with a quiet permanent notification.
 */
public class TGeshkaKeepAlive {

    private static final String CHANNEL_ID = "tgeshka_keepalive";
    private static final int NOTIFICATION_ID = 0x7e5;
    // ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, API 34.
    private static final int TYPE_SPECIAL_USE = 0x40000000;

    public static boolean isEnabled() {
        SharedPreferences preferences = MessagesController.getGlobalNotificationsSettings();
        return preferences.getBoolean("pushService", true);
    }

    public static void setEnabled(boolean enabled) {
        MessagesController.getGlobalNotificationsSettings().edit()
                .putBoolean("pushService", enabled)
                .putBoolean("pushConnection", enabled)
                .apply();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                ConnectionsManager.getInstance(a).setPushConnectionEnabled(enabled);
            }
        }
        ApplicationLoader.startPushService();
    }

    public static void startForeground(Service service) {
        try {
            Context context = service.getApplicationContext();
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                    NotificationChannel channel = new NotificationChannel(CHANNEL_ID, LocaleController.getString(R.string.TGeshkaKeepAliveChannel), NotificationManager.IMPORTANCE_MIN);
                    channel.setShowBadge(false);
                    channel.enableVibration(false);
                    channel.setSound(null, null);
                    manager.createNotificationChannel(channel);
                }
            }
            Intent open = new Intent(context, LaunchActivity.class);
            open.setAction(Intent.ACTION_MAIN);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, open, flags);
            Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.notification)
                    .setContentTitle(LocaleController.getString(R.string.TGeshkaKeepAliveNotification))
                    .setContentText(LocaleController.getString(R.string.TGeshkaKeepAliveNotificationText))
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setOngoing(true)
                    .setShowWhen(false)
                    .setContentIntent(pendingIntent)
                    .build();
            ServiceCompat.startForeground(service, NOTIFICATION_ID, notification, Build.VERSION.SDK_INT >= 34 ? TYPE_SPECIAL_USE : 0);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static boolean isIgnoringBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        PowerManager powerManager = (PowerManager) ApplicationLoader.applicationContext.getSystemService(Context.POWER_SERVICE);
        return powerManager != null && powerManager.isIgnoringBatteryOptimizations(ApplicationLoader.applicationContext.getPackageName());
    }

    @SuppressLint("BatteryLife")
    public static void requestIgnoreBatteryOptimizations(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + context.getPackageName()));
            context.startActivity(intent);
        } catch (Throwable e) {
            try {
                context.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable e2) {
                FileLog.e(e2);
            }
        }
    }
}
