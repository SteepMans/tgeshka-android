/*
 * This file is part of TGeshka, an unofficial client based on Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package org.telegram.tgeshka;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.telegram.messenger.LocaleController.getString;

/**
 * "What's new": the TGeshka changes from CHANGELOG-tgeshka.json in the repository root,
 * copied into the assets at build time (no network needed). Shown in Settings → TGeshka
 * and once after an update to a version listed there.
 */
public final class TGeshkaWhatsNew {

    private static final String ASSET = "CHANGELOG-tgeshka.json";
    private static final String PREFS = "tgeshka_whats_new";
    private static final String KEY_SEEN = "seen";
    private static final long SHOW_DELAY = 1500;

    public static final class Release {
        public final String version;
        public final String date;
        public final List<String> items;

        Release(String version, String date, List<String> items) {
            this.version = version;
            this.date = date;
            this.items = items;
        }
    }

    private TGeshkaWhatsNew() {
    }

    public static List<Release> load(Context context) {
        try (InputStream in = context.getAssets().open(ASSET)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            JSONArray array = new JSONArray(new String(out.toByteArray(), StandardCharsets.UTF_8));
            List<Release> result = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) {
                    continue;
                }
                String version = object.optString("version");
                JSONArray items = object.optJSONArray("items");
                List<String> texts = new ArrayList<>();
                for (int j = 0; items != null && j < items.length(); j++) {
                    String text = items.optString(j);
                    if (!TextUtils.isEmpty(text)) {
                        texts.add(text);
                    }
                }
                if (!TextUtils.isEmpty(version) && !texts.isEmpty()) {
                    result.add(new Release(version, object.optString("date"), texts));
                }
            }
            return result;
        } catch (Exception e) {
            FileLog.e(e);
            return Collections.emptyList();
        }
    }

    /** 0.1.2-beta.3 → 0.1.2 */
    private static String baseVersion(String version) {
        int dash = version.indexOf('-');
        return dash < 0 ? version : version.substring(0, dash);
    }

    /** The entry of the version, or of the same version without a beta suffix. */
    public static Release find(List<Release> releases, String version) {
        for (Release release : releases) {
            if (release.version.equals(version)) {
                return release;
            }
        }
        String base = baseVersion(version);
        for (Release release : releases) {
            if (baseVersion(release.version).equals(base)) {
                return release;
            }
        }
        return null;
    }

    /** 2026-11-23 → 23.11.2026 */
    public static String formatDate(String date) {
        if (date == null) {
            return "";
        }
        String[] parts = date.split("-");
        return parts.length == 3 ? parts[2] + "." + parts[1] + "." + parts[0] : date;
    }

    public static String itemsText(Release release) {
        StringBuilder builder = new StringBuilder();
        for (String item : release.items) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append("• ").append(item);
        }
        return builder.toString();
    }

    private static boolean isFreshInstall(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.firstInstallTime == info.lastUpdateTime;
        } catch (Exception e) {
            return false;
        }
    }

    /** Once after an update: "What's new in X.Y.Z". Not on a fresh install or the access key screen. */
    public static void showAfterUpdate(Activity activity) {
        if (!TGeshkaGateway.hasToken()) {
            return;
        }
        Release release = find(load(activity), BuildConfig.TGESHKA_VERSION);
        if (release == null) {
            return;
        }
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String seen = preferences.getString(KEY_SEEN, "");
        if (release.version.equals(seen)) {
            return;
        }
        if (TextUtils.isEmpty(seen) && isFreshInstall(activity)) {
            preferences.edit().putString(KEY_SEEN, release.version).apply();
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            preferences.edit().putString(KEY_SEEN, release.version).apply();
            AlertDialog.Builder builder = new AlertDialog.Builder(activity);
            builder.setTitle(LocaleController.formatString(R.string.TGeshkaWhatsNewIn, BuildConfig.TGESHKA_VERSION));
            builder.setMessage(itemsText(release));
            builder.setPositiveButton(getString(R.string.TGeshkaWhatsNewClose), null);
            builder.setNeutralButton(getString(R.string.TGeshkaWhatsNewAll), (dialog, which) -> {
                BaseFragment fragment = LaunchActivity.getSafeLastFragment();
                if (fragment != null) {
                    fragment.presentFragment(new TGeshkaWhatsNewActivity());
                }
            });
            builder.show();
        }, SHOW_DELAY);
    }
}
