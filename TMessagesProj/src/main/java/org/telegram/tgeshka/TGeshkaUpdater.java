/*
 * This file is part of TGeshka, an unofficial client based on Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package org.telegram.tgeshka;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Base64;
import android.widget.Toast;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.AlertDialog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

import static org.telegram.messenger.LocaleController.getString;

/**
 * Updates through the gateway (SteepMans/api-gateway, docs/update-v1.md):
 * the manifest and the APK come from the gateway with the same token, the APK
 * is checked by size, sha256 and an ed25519 signature over the raw sha256
 * digest, then installed with PackageInstaller after the user confirms.
 */
public class TGeshkaUpdater {

    // Public part of the Android update signing key (private one is a CI secret).
    private static final String UPDATE_PUBLIC_KEY = "Os4IRw5pUrnRiO66yLcgenivvt/vlWjWl1AhdpSwSbQ=";
    private static final long AUTO_CHECK_INTERVAL = 12 * 60 * 60 * 1000L;
    private static final String PREFS = "tgeshka_updates";

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static boolean running;

    public static class Manifest {
        public String version;
        public String minVersion;
        public String sha256;
        public long size;
        public String signature;
        public String notes;
        public String url;
    }

    /** Called on start, checks at most twice a day and shows only available updates. */
    public static void checkPeriodically(Activity activity) {
        if (!TGeshkaGateway.hasToken()) {
            return;
        }
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long last = preferences.getLong("lastCheck", 0);
        if (Math.abs(System.currentTimeMillis() - last) < AUTO_CHECK_INTERVAL) {
            return;
        }
        preferences.edit().putLong("lastCheck", System.currentTimeMillis()).apply();
        check(activity, false);
    }

    public static void check(Activity activity, boolean interactive) {
        if (running) {
            return;
        }
        if (!TGeshkaGateway.hasToken()) {
            if (interactive) {
                toast(activity, getString(R.string.TGeshkaErrorNoToken));
            }
            return;
        }
        running = true;
        String base = baseUrl();
        String token = TGeshkaGateway.getToken();
        executor.execute(() -> {
            Manifest manifest = null;
            String error = null;
            HttpsURLConnection connection = null;
            try {
                connection = open(base + "/v1/update/manifest?platform=android&channel=stable", token);
                int code = connection.getResponseCode();
                if (code == 200) {
                    JSONObject json = new JSONObject(TGeshkaGateway.readAll(connection.getInputStream()));
                    manifest = new Manifest();
                    manifest.version = json.optString("version");
                    manifest.minVersion = json.optString("min_version");
                    manifest.sha256 = json.optString("sha256");
                    manifest.size = json.optLong("size");
                    manifest.signature = json.optString("signature");
                    manifest.notes = json.optString("notes");
                    manifest.url = json.optString("url");
                } else if (code != 204) {
                    error = "HTTP " + code;
                }
            } catch (Exception e) {
                FileLog.e(e, false);
                error = getString(R.string.TGeshkaErrorNetwork);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            Manifest result = manifest;
            String failure = error;
            AndroidUtilities.runOnUIThread(() -> {
                running = false;
                if (activity.isFinishing()) {
                    return;
                }
                if (failure != null) {
                    if (interactive) {
                        toast(activity, LocaleController.formatString(R.string.TGeshkaUpdateFailed, failure));
                    }
                } else if (result == null || compareVersions(result.version, BuildConfig.TGESHKA_VERSION) <= 0) {
                    if (interactive) {
                        toast(activity, getString(R.string.TGeshkaUpdateLatest));
                    }
                } else {
                    offer(activity, result);
                }
            });
        });
    }

    private static void offer(Activity activity, Manifest manifest) {
        boolean required = !TextUtils.isEmpty(manifest.minVersion) && compareVersions(BuildConfig.TGESHKA_VERSION, manifest.minVersion) < 0;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.formatString(R.string.TGeshkaUpdateAvailable, manifest.version));
        if (!TextUtils.isEmpty(manifest.notes)) {
            builder.setMessage(manifest.notes);
        }
        builder.setPositiveButton(getString(R.string.TGeshkaUpdateInstall), (dialog, which) -> download(activity, manifest));
        if (!required) {
            builder.setNegativeButton(getString(R.string.Cancel), null);
        }
        AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(!required);
        dialog.setCancelable(!required);
        dialog.show();
    }

    private static void download(Activity activity, Manifest manifest) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            toast(activity, getString(R.string.TGeshkaUpdateInstallPermission));
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName())));
            } catch (Exception e) {
                FileLog.e(e);
            }
            return;
        }
        AlertDialog progress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(false);
        progress.show();
        toast(activity, getString(R.string.TGeshkaUpdateDownloading));
        String base = baseUrl();
        String token = TGeshkaGateway.getToken();
        executor.execute(() -> {
            File file = new File(activity.getCacheDir(), "tgeshka-update.apk");
            String error = null;
            try {
                fetch(base + manifest.url, token, file, manifest.size);
                if (!verify(file, manifest)) {
                    file.delete();
                    error = getString(R.string.TGeshkaUpdateBadSignature);
                }
            } catch (Exception e) {
                FileLog.e(e, false);
                error = getString(R.string.TGeshkaErrorNetwork);
            }
            String failure = error;
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    progress.dismiss();
                } catch (Exception ignore) {
                }
                if (failure != null) {
                    toast(activity, LocaleController.formatString(R.string.TGeshkaUpdateFailed, failure));
                } else {
                    install(activity, file);
                }
            });
        });
    }

    /** Downloads with resume through Range when a part of the file is already there. */
    private static void fetch(String url, String token, File file, long size) throws Exception {
        long have = file.exists() ? file.length() : 0;
        if (have > size) {
            file.delete();
            have = 0;
        }
        if (have == size && size > 0) {
            return;
        }
        HttpsURLConnection connection = open(url, token);
        connection.setReadTimeout(60000);
        if (have > 0) {
            connection.setRequestProperty("Range", "bytes=" + have + "-");
        }
        int code = connection.getResponseCode();
        boolean append = code == 206 && have > 0;
        if (code != 200 && code != 206) {
            connection.disconnect();
            throw new Exception("HTTP " + code);
        }
        try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(file, append)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        } finally {
            connection.disconnect();
        }
    }

    static boolean verify(File file, Manifest manifest) throws Exception {
        if (file.length() != manifest.size) {
            return false;
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        byte[] sha = digest.digest();
        if (!toHex(sha).equals(manifest.sha256)) {
            return false;
        }
        byte[] signature = Base64.decode(manifest.signature, Base64.DEFAULT);
        byte[] key = Base64.decode(UPDATE_PUBLIC_KEY, Base64.DEFAULT);
        return ConnectionsManager.native_verifyEd25519(signature, sha, key);
    }

    private static void install(Activity activity, File file) {
        try {
            PackageInstaller installer = activity.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(activity.getPackageName());
            if (Build.VERSION.SDK_INT >= 31) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
            }
            int sessionId = installer.createSession(params);
            try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                try (InputStream in = new FileInputStream(file); OutputStream out = session.openWrite("tgeshka.apk", 0, file.length())) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                    session.fsync(out);
                }
                Intent intent = new Intent(activity, TGeshkaInstallReceiver.class);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent pendingIntent = PendingIntent.getBroadcast(activity, sessionId, intent, flags);
                session.commit(pendingIntent.getIntentSender());
            }
        } catch (Exception e) {
            FileLog.e(e);
            toast(activity, LocaleController.formatString(R.string.TGeshkaUpdateFailed, e.getMessage()));
        }
    }

    private static HttpsURLConnection open(String url, String token) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("X-TGeshka-Client", TGeshkaGateway.clientName());
        return connection;
    }

    private static String baseUrl() {
        Uri uri = Uri.parse(TGeshkaGateway.getUrl());
        return "https://" + uri.getEncodedAuthority();
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(String.format("%02x", b & 0xff));
        }
        return builder.toString();
    }

    /** 1.2.3 > 1.2.3-beta.1 > 1.2.2; numeric parts are compared as numbers. */
    public static int compareVersions(String a, String b) {
        String[] aMain = (a == null ? "" : a).split("-", 2);
        String[] bMain = (b == null ? "" : b).split("-", 2);
        String[] aParts = aMain[0].split("\\.");
        String[] bParts = bMain[0].split("\\.");
        for (int i = 0; i < Math.max(aParts.length, bParts.length); i++) {
            int x = i < aParts.length ? parse(aParts[i]) : 0;
            int y = i < bParts.length ? parse(bParts[i]) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        boolean aPre = aMain.length > 1;
        boolean bPre = bMain.length > 1;
        if (aPre != bPre) {
            return aPre ? -1 : 1;
        }
        return aPre ? aMain[1].compareTo(bMain[1]) : 0;
    }

    private static int parse(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return 0;
        }
    }

    private static void toast(Context context, String text) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show();
    }
}
