package org.telegram.tgeshka;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.ColoredImageSpan;

import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

/**
 * TGeshka members (docs/tg-protocol-v1.md of the gateway, "Участники TGeshka"): the client tells the gateway which
 * Telegram accounts use it (POST /v1/members/me) and gets the ids of other TGeshka users (GET /v1/members) to show
 * the lavender TGeshka badge next to their names. The list is cached on disk and refreshed at start and every hour
 * while the app is open.
 */
public class TGeshkaMembers {

    private static final String PREFS = "tgeshka_members";
    private static final long HOUR = 60 * 60 * 1000L;
    private static final long TICK = 60 * 1000L;

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static volatile HashSet<Long> members;
    private static final HashSet<Long> reported = new HashSet<>();
    private static long lastFetch;
    private static long lastReport;
    private static boolean running;
    private static boolean busy;

    private static final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            check(false);
            AndroidUtilities.runOnUIThread(this, TICK);
        }
    };

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** «Показывать, что я пользуюсь TGeshka», on by default. */
    public static boolean isVisible() {
        return prefs().getBoolean("visible", true);
    }

    public static void setVisible(boolean visible) {
        prefs().edit().putBoolean("visible", visible).apply();
        synchronized (reported) {
            reported.clear();
        }
        notifyUi();
        check(true);
    }

    private static HashSet<Long> load() {
        HashSet<Long> set = members;
        if (set != null) {
            return set;
        }
        set = new HashSet<>();
        String saved = prefs().getString("ids", "");
        if (!TextUtils.isEmpty(saved)) {
            for (String part : saved.split(",")) {
                try {
                    set.add(Long.parseLong(part));
                } catch (NumberFormatException ignore) {
                }
            }
        }
        members = set;
        return set;
    }

    /** True for TGeshka users from the gateway list and for the own accounts while the switch is on. */
    public static boolean isMember(long userId) {
        if (userId <= 0) {
            return false;
        }
        if (isVisible()) {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                UserConfig config = UserConfig.getInstance(a);
                if (config.isClientActivated() && config.getClientUserId() == userId) {
                    return true;
                }
            }
        }
        return load().contains(userId);
    }

    public static boolean isMember(TLRPC.User user) {
        return user != null && !user.bot && !user.deleted && isMember(user.id);
    }

    /** The badge drawable at the given size (px). */
    public static Drawable badgeDrawable(int size) {
        Drawable drawable = ContextCompat.getDrawable(ApplicationLoader.applicationContext, R.drawable.tgeshka_badge).mutate();
        drawable.setBounds(0, 0, size, size);
        return drawable;
    }

    /** Width the badge adds after a name drawn with the paint (gap included). */
    public static int badgeWidth(Paint paint) {
        return badgeSize(paint) + AndroidUtilities.dp(4);
    }

    private static int badgeSize(Paint paint) {
        Paint.FontMetricsInt fm = paint.getFontMetricsInt();
        int textHeight = fm.descent - fm.ascent;
        return Math.max(AndroidUtilities.dp(12), Math.min(AndroidUtilities.dp(18), (int) (textHeight * 0.82f)));
    }

    /** The name followed by the badge, vertically centred on the text, the size of a verified mark for this paint. */
    public static CharSequence appendBadge(CharSequence name, Paint paint) {
        if (name == null) {
            return null;
        }
        int size = badgeSize(paint);
        ColoredImageSpan span = new ColoredImageSpan(badgeDrawable(size), ColoredImageSpan.ALIGN_CENTER);
        span.recolorDrawable = false;
        span.setSize(size);
        span.setWidth(size + AndroidUtilities.dp(4));
        span.setTranslateX(AndroidUtilities.dp(4));
        SpannableStringBuilder builder = new SpannableStringBuilder(name);
        int start = builder.length();
        builder.append("⁠");
        builder.setSpan(span, start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return builder;
    }

    /** The name with the badge when the user is a TGeshka member, otherwise the name. */
    public static CharSequence withBadge(CharSequence name, TLRPC.User user, Paint paint) {
        return isMember(user) ? appendBadge(name, paint) : name;
    }

    public static void onResumed() {
        if (running) {
            return;
        }
        running = true;
        AndroidUtilities.runOnUIThread(tick, 3000);
    }

    public static void onPaused() {
        running = false;
        AndroidUtilities.cancelRunOnUIThread(tick);
    }

    /** UI thread: reports new accounts and refreshes the list once an hour (now when forced). */
    private static void check(boolean force) {
        if (busy || !TGeshkaGateway.hasToken()) {
            return;
        }
        long now = System.currentTimeMillis();
        final HashSet<Long> toReport = new HashSet<>();
        boolean all = force || now - lastReport > HOUR;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig config = UserConfig.getInstance(a);
            if (!config.isClientActivated()) {
                continue;
            }
            long id = config.getClientUserId();
            synchronized (reported) {
                if (id > 0 && (all || !reported.contains(id))) {
                    toReport.add(id);
                }
            }
        }
        boolean fetch = force || !toReport.isEmpty() || now - lastFetch > HOUR;
        if (!fetch) {
            return;
        }
        busy = true;
        final boolean visible = isVisible();
        final String url = TGeshkaGateway.getUrl();
        final String token = TGeshkaGateway.getToken();
        executor.execute(() -> {
            boolean reportedAll = true;
            for (long id : toReport) {
                if (post(url, token, id, visible)) {
                    synchronized (reported) {
                        reported.add(id);
                    }
                } else {
                    reportedAll = false;
                }
            }
            boolean fetched = fetch(url, token);
            final boolean okReport = reportedAll;
            AndroidUtilities.runOnUIThread(() -> {
                busy = false;
                long t = System.currentTimeMillis();
                if (fetched) {
                    lastFetch = t;
                }
                if (okReport && !toReport.isEmpty()) {
                    lastReport = t;
                }
            });
        });
    }

    static String endpoint(String gatewayUrl, String path) {
        android.net.Uri uri = android.net.Uri.parse(gatewayUrl);
        return "https://" + uri.getEncodedAuthority() + path;
    }

    private static boolean post(String url, String token, long userId, boolean visible) {
        HttpsURLConnection connection = null;
        try {
            connection = (HttpsURLConnection) new URL(endpoint(url, "/v1/members/me")).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("X-TGeshka-Client", TGeshkaGateway.clientName());
            connection.setRequestProperty("Content-Type", "application/json");
            JSONObject body = new JSONObject();
            body.put("user_id", userId);
            body.put("visible", visible);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = connection.getResponseCode();
            // 429: the key already claims its limit of accounts; repeating every minute would not help.
            return code == 200 || code == 429 || code == 400;
        } catch (Exception e) {
            FileLog.e(e, false);
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static boolean fetch(String url, String token) {
        HttpsURLConnection connection = null;
        try {
            connection = (HttpsURLConnection) new URL(endpoint(url, "/v1/members")).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("X-TGeshka-Client", TGeshkaGateway.clientName());
            String version = prefs().getString("version", "");
            if (!TextUtils.isEmpty(version) && members != null) {
                connection.setRequestProperty("If-None-Match", "\"" + version + "\"");
            }
            int code = connection.getResponseCode();
            if (code == 304) {
                return true;
            }
            if (code != 200) {
                return false;
            }
            JSONObject json = new JSONObject(TGeshkaGateway.readAll(connection.getInputStream()));
            JSONArray ids = json.optJSONArray("user_ids");
            HashSet<Long> set = new HashSet<>();
            StringBuilder saved = new StringBuilder();
            if (ids != null) {
                for (int i = 0; i < ids.length(); i++) {
                    long id = ids.optLong(i);
                    if (id > 0) {
                        set.add(id);
                        if (saved.length() > 0) {
                            saved.append(',');
                        }
                        saved.append(id);
                    }
                }
            }
            prefs().edit().putString("ids", saved.toString()).putString("version", json.optString("version")).apply();
            HashSet<Long> old = members;
            members = set;
            if (old == null || !old.equals(set)) {
                AndroidUtilities.runOnUIThread(TGeshkaMembers::notifyUi);
            }
            return true;
        } catch (Exception e) {
            FileLog.e(e, false);
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** Names are laid out again: chat list, headers, profiles and messages pick the badge up. */
    private static void notifyUi() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_NAME);
            }
        }
    }
}
