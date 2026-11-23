/*
 * This file is part of TGeshka, an unofficial client based on Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package org.telegram.tgeshka;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

/**
 * Gateway settings and state. All connections of tgnet go through the
 * gateway (SteepMans/api-gateway, docs/tg-protocol-v1.md): a WebSocket over
 * TLS per DC connection, the token in the Authorization header.
 */
public class TGeshkaGateway {

    public static final String URL_SCHEME = "tgeshka";
    public static final String SOURCES_URL = "https://github.com/SteepMans/tgeshka-android";

    // Close codes of the gateway protocol and own codes from tgnet (Gateway.h).
    public static final int ERROR_NO_TOKEN = 1;
    public static final int ERROR_REJECTED = 2;
    public static final int ERROR_TLS = 3;
    public static final int ERROR_RESOLVE = 4;
    public static final int ERROR_PROTOCOL = 5;
    public static final int ERROR_TOKEN_REVOKED = 4401;
    public static final int ERROR_LIMIT = 4429;
    public static final int ERROR_LEGS_DOWN = 4502;
    public static final int ERROR_DC_UNREACHABLE = 4504;

    private static final Pattern TOKEN = Pattern.compile("^tc_[0-9A-Za-z]{43}$");
    private static final String PREFS = "tgeshka_gateway";

    public interface Listener {
        void onGatewayChanged();
    }

    public static class Info {
        public boolean ok;
        public int httpCode;
        public long rttMillis;
        public int maxConnections;
        public int activeConnections;
        public String status;
        public String description;
        public String error;
    }

    public static class LastError {
        public int code;
        public String reason;
        public long time;
    }

    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final ArrayList<Listener> listeners = new ArrayList<>();
    private static final boolean[] initialized = new boolean[UserConfig.MAX_ACCOUNT_COUNT];
    private static volatile LastError lastError;
    private static volatile Info lastInfo;
    private static long lastErrorNotify;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getDefaultUrl() {
        return BuildConfig.TGESHKA_GATEWAY_URL == null ? "" : BuildConfig.TGESHKA_GATEWAY_URL;
    }

    public static String getUrl() {
        String url = prefs().getString("url", "");
        return isValidUrl(url) ? url : getDefaultUrl();
    }

    public static boolean hasCustomUrl() {
        String url = prefs().getString("url", "");
        return isValidUrl(url) && !url.equals(getDefaultUrl());
    }

    public static String getToken() {
        return prefs().getString("token", "");
    }

    public static boolean hasToken() {
        return isValidToken(getToken());
    }

    public static boolean isValidToken(String token) {
        return token != null && TOKEN.matcher(token).matches();
    }

    public static boolean isValidUrl(String url) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }
        try {
            Uri uri = Uri.parse(url);
            return "wss".equals(uri.getScheme()) && !TextUtils.isEmpty(uri.getHost());
        } catch (Exception e) {
            return false;
        }
    }

    public static String maskedToken() {
        String token = getToken();
        if (!isValidToken(token)) {
            return "";
        }
        return token.substring(0, 3) + "…" + token.substring(token.length() - 4);
    }

    public static String getHost() {
        try {
            return Uri.parse(getUrl()).getHost();
        } catch (Exception e) {
            return "";
        }
    }

    public static String clientName() {
        return "android/" + BuildConfig.TGESHKA_VERSION;
    }

    public static synchronized void save(String url, String token) {
        SharedPreferences.Editor editor = prefs().edit();
        if (url != null) {
            editor.putString("url", isValidUrl(url) && !url.equals(getDefaultUrl()) ? url : "");
        }
        if (token != null) {
            editor.putString("token", token.trim());
        }
        editor.apply();
        lastError = null;
        lastInfo = null;
        applyToNative();
        reconnect();
        notifyListeners();
    }

    /** Passes the current settings to tgnet. Called before the first connection. */
    public static synchronized void applyToNative() {
        String host = "";
        int port = 443;
        String path = "/v1/stream";
        try {
            Uri uri = Uri.parse(getUrl());
            if (uri.getHost() != null) {
                host = uri.getHost();
            }
            if (uri.getPort() > 0) {
                port = uri.getPort();
            }
            if (!TextUtils.isEmpty(uri.getPath())) {
                path = uri.getPath();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        String token = getToken();
        ConnectionsManager.native_setGatewayConfig(host, port, path, isValidToken(token) ? token : "", clientName());
    }

    public static void markInitialized(int account) {
        synchronized (initialized) {
            initialized[account] = true;
        }
    }

    /** Drops all connections, tgnet reconnects through the gateway. */
    public static void reconnect() {
        synchronized (initialized) {
            for (int a = 0; a < initialized.length; a++) {
                if (initialized[a]) {
                    ConnectionsManager.native_applyGatewayConfig(a);
                }
            }
        }
    }

    /** The gateway host is resolved only by the system resolver, IPv4. */
    public static void resolveHost(String hostName, long address) {
        executor.execute(() -> {
            String ip = "";
            try {
                for (InetAddress inetAddress : InetAddress.getAllByName(hostName)) {
                    if (inetAddress instanceof Inet4Address) {
                        ip = inetAddress.getHostAddress();
                        break;
                    }
                }
            } catch (Exception e) {
                FileLog.e(e, false);
            }
            ConnectionsManager.native_onHostNameResolved(hostName, address, ip);
        });
    }

    /** Called by tgnet from a network thread. */
    public static void onNativeError(int code, String reason) {
        LastError error = new LastError();
        error.code = code;
        error.reason = reason;
        error.time = System.currentTimeMillis();
        lastError = error;
        long now = SystemClock.elapsedRealtime();
        if (now - lastErrorNotify > 1000) {
            lastErrorNotify = now;
            AndroidUtilities.runOnUIThread(TGeshkaGateway::notifyListeners);
        }
    }

    public static LastError getLastError() {
        return lastError;
    }

    public static Info getLastInfo() {
        return lastInfo;
    }

    /** {open connections, bytes sent, bytes received, last connect ms, last error code} */
    public static long[] getStats() {
        try {
            long[] stats = ConnectionsManager.native_getGatewayStats();
            if (stats != null && stats.length >= 5) {
                return stats;
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return new long[5];
    }

    public static String errorText(int code) {
        switch (code) {
            case ERROR_NO_TOKEN:
                return str(R.string.TGeshkaErrorNoToken);
            case ERROR_REJECTED:
                return str(R.string.TGeshkaErrorRejected);
            case ERROR_TLS:
                return str(R.string.TGeshkaErrorTls);
            case ERROR_RESOLVE:
                return str(R.string.TGeshkaErrorResolve);
            case ERROR_TOKEN_REVOKED:
                return str(R.string.TGeshkaErrorRevoked);
            case ERROR_LIMIT:
                return str(R.string.TGeshkaErrorLimit);
            case ERROR_LEGS_DOWN:
                return str(R.string.TGeshkaErrorLegsDown);
            case ERROR_DC_UNREACHABLE:
                return str(R.string.TGeshkaErrorDcUnreachable);
            default:
                return str(R.string.TGeshkaErrorOther) + " (" + code + ")";
        }
    }

    private static String str(int res) {
        return org.telegram.messenger.LocaleController.getString(res);
    }

    public static String infoUrl(String gatewayUrl) {
        Uri uri = Uri.parse(gatewayUrl);
        String path = uri.getPath();
        if (path != null && path.endsWith("/stream")) {
            path = path.substring(0, path.length() - "stream".length()) + "info";
        } else {
            path = "/v1/info";
        }
        return "https://" + uri.getEncodedAuthority() + path;
    }

    /** GET /v1/info with the token, measures the round trip. Callback on the UI thread. */
    public static void fetchInfo(String url, String token, org.telegram.messenger.Utilities.Callback<Info> callback) {
        executor.execute(() -> {
            Info info = new Info();
            HttpsURLConnection connection = null;
            try {
                connection = (HttpsURLConnection) new URL(infoUrl(url)).openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setRequestProperty("Authorization", "Bearer " + token);
                connection.setRequestProperty("X-TGeshka-Client", clientName());
                long start = SystemClock.elapsedRealtime();
                info.httpCode = connection.getResponseCode();
                info.rttMillis = SystemClock.elapsedRealtime() - start;
                if (info.httpCode == 200) {
                    JSONObject json = new JSONObject(readAll(connection.getInputStream()));
                    info.ok = true;
                    info.maxConnections = json.optInt("max_connections");
                    info.activeConnections = json.optInt("active_connections");
                    info.status = json.optString("status");
                    info.description = json.optString("description");
                } else if (info.httpCode == 404) {
                    info.error = str(R.string.TGeshkaErrorRejected);
                } else {
                    info.error = "HTTP " + info.httpCode;
                }
            } catch (javax.net.ssl.SSLException e) {
                info.error = str(R.string.TGeshkaErrorTls);
            } catch (java.net.UnknownHostException e) {
                info.error = str(R.string.TGeshkaErrorResolve);
            } catch (Exception e) {
                FileLog.e(e, false);
                info.error = str(R.string.TGeshkaErrorNetwork);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            if (url.equals(getUrl()) && token.equals(getToken())) {
                lastInfo = info;
            }
            AndroidUtilities.runOnUIThread(() -> callback.run(info));
        });
    }

    static String readAll(InputStream stream) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = stream.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        stream.close();
        return out.toString("UTF-8");
    }

    /**
     * Parses a pasted key: a bare token or a link
     * tgeshka://gateway?url=wss://...&token=tc_...
     * Returns {url or null, token} or null.
     */
    public static String[] parseImport(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (isValidToken(trimmed)) {
            return new String[]{null, trimmed};
        }
        try {
            Uri uri = Uri.parse(trimmed);
            if (!URL_SCHEME.equalsIgnoreCase(uri.getScheme()) || !"gateway".equalsIgnoreCase(uri.getHost())) {
                return null;
            }
            String token = uri.getQueryParameter("token");
            String url = uri.getQueryParameter("url");
            if (!isValidToken(token == null ? null : token.trim())) {
                return null;
            }
            if (url != null && !isValidUrl(url.trim())) {
                return null;
            }
            return new String[]{url == null ? null : url.trim(), token.trim()};
        } catch (Exception e) {
            return null;
        }
    }

    /** Handles tgeshka://gateway links opened from outside. */
    public static boolean handleIntent(android.app.Activity activity, Intent intent) {
        if (intent == null || intent.getData() == null || !Intent.ACTION_VIEW.equals(intent.getAction())) {
            return false;
        }
        Uri data = intent.getData();
        if (!URL_SCHEME.equalsIgnoreCase(data.getScheme())) {
            return false;
        }
        String[] parsed = parseImport(data.toString());
        intent.setAction(null);
        if (parsed == null) {
            android.widget.Toast.makeText(activity, str(R.string.TGeshkaLinkInvalid), android.widget.Toast.LENGTH_LONG).show();
            return true;
        }
        save(parsed[0], parsed[1]);
        android.widget.Toast.makeText(activity, str(R.string.TGeshkaLinkApplied), android.widget.Toast.LENGTH_LONG).show();
        return true;
    }

    public static void addListener(Listener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public static void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private static void notifyListeners() {
        for (Listener listener : new ArrayList<>(listeners)) {
            listener.onGatewayChanged();
        }
    }
}
