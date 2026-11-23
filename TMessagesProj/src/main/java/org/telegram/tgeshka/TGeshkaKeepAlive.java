/*
 * This file is part of TGeshka, an unofficial client based on Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package org.telegram.tgeshka;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.PermissionRequest;
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

    private static final String PREFS = "tgeshka_keepalive";
    private static final String PREF_NOTIFICATIONS_ASKED = "notifications_asked";
    private static final long NOTIFICATIONS_ASK_INTERVAL = 24L * 60 * 60 * 1000;
    private static final long NOTIFICATIONS_ASK_DELAY = 5_000;

    private static Activity resumedActivity;

    private static SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean areNotificationsAllowed() {
        try {
            return NotificationManagerCompat.from(ApplicationLoader.applicationContext).areNotificationsEnabled();
        } catch (Throwable e) {
            FileLog.e(e);
            return true;
        }
    }

    public static void openNotificationSettings(Context context) {
        try {
            Intent intent;
            if (Build.VERSION.SDK_INT >= 26) {
                intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
            } else {
                intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.getPackageName()));
            }
            context.startActivity(intent);
        } catch (Throwable e) {
            try {
                context.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.getPackageName())));
            } catch (Throwable e2) {
                FileLog.e(e2);
            }
        }
    }

    private static final Runnable checkNotificationsRunnable = () -> {
        Activity activity = resumedActivity;
        if (activity != null && !activity.isFinishing()) {
            checkNotificationsAllowed(activity);
        }
    };

    /**
     * Without the permission to post notifications the background connection is useless:
     * messages arrive, but the phone stays silent. A few seconds after the app comes to the
     * screen ask for the permission or point to the settings, at most once a day.
     */
    public static void onActivityResumed(Activity activity) {
        resumedActivity = activity;
        AndroidUtilities.cancelRunOnUIThread(checkNotificationsRunnable);
        AndroidUtilities.runOnUIThread(checkNotificationsRunnable, NOTIFICATIONS_ASK_DELAY);
    }

    public static void onActivityPaused(Activity activity) {
        if (resumedActivity == activity) {
            resumedActivity = null;
        }
        AndroidUtilities.cancelRunOnUIThread(checkNotificationsRunnable);
    }

    private static void checkNotificationsAllowed(Activity activity) {
        if (areNotificationsAllowed() || !UserConfig.getInstance(UserConfig.selectedAccount).isClientActivated()) {
            return;
        }
        SharedPreferences preferences = preferences();
        long now = System.currentTimeMillis();
        if (Math.abs(now - preferences.getLong(PREF_NOTIFICATIONS_ASKED, 0)) < NOTIFICATIONS_ASK_INTERVAL) {
            return;
        }
        preferences.edit().putLong(PREF_NOTIFICATIONS_ASKED, now).apply();
        if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            PermissionRequest.requestPermission(Manifest.permission.POST_NOTIFICATIONS, granted -> {
                if (!granted || !areNotificationsAllowed()) {
                    showNotificationsBlocked(activity);
                }
            });
        } else {
            showNotificationsBlocked(activity);
        }
    }

    private static void showNotificationsBlocked(Activity activity) {
        if (activity == null || activity.isFinishing() || areNotificationsAllowed()) {
            return;
        }
        try {
            new AlertDialog.Builder(activity)
                    .setTitle(LocaleController.getString(R.string.TGeshkaNotificationsOffTitle))
                    .setMessage(LocaleController.getString(R.string.TGeshkaNotificationsOffText))
                    .setPositiveButton(LocaleController.getString(R.string.TGeshkaNotificationsOpenSettings), (dialog, which) -> openNotificationSettings(activity))
                    .setNegativeButton(LocaleController.getString(R.string.TGeshkaNotificationsLater), null)
                    .show();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

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
