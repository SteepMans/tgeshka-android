/*
 * This file is part of TGeshka, an unofficial client based on Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package org.telegram.tgeshka;

import android.content.ClipboardManager;
import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

import static org.telegram.messenger.LocaleController.getString;

/** Settings → TGeshka: gateway, its state, background connection, updates, what's new, about. */
public class TGeshkaSettingsActivity extends UniversalFragment implements TGeshkaGateway.Listener {

    private static final int ID_ADDRESS = 1;
    private static final int ID_KEY = 2;
    private static final int ID_CHECK = 3;
    private static final int ID_STATUS = 4;
    private static final int ID_RTT = 5;
    private static final int ID_CONNECTIONS = 6;
    private static final int ID_TRAFFIC = 7;
    private static final int ID_LAST_CONNECT = 8;
    private static final int ID_LAST_ERROR = 9;
    private static final int ID_RECONNECT = 10;
    private static final int ID_KEEP_ALIVE = 11;
    private static final int ID_BATTERY = 12;
    private static final int ID_UPDATES = 13;
    private static final int ID_ABOUT = 14;
    private static final int ID_NOTIFICATIONS = 15;
    private static final int ID_LAST_PUSH = 16;
    private static final int ID_WHATS_NEW = 17;

    private boolean paused = true;
    private boolean checking;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            if (paused) {
                return;
            }
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(false);
            }
            AndroidUtilities.runOnUIThread(this, 1000);
        }
    };

    @Override
    public boolean onFragmentCreate() {
        TGeshkaGateway.addListener(this);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        TGeshkaGateway.removeListener(this);
        super.onFragmentDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        paused = false;
        AndroidUtilities.cancelRunOnUIThread(refresh);
        AndroidUtilities.runOnUIThread(refresh);
        if (TGeshkaGateway.hasToken() && TGeshkaGateway.getLastInfo() == null) {
            TGeshkaGateway.fetchInfo(TGeshkaGateway.getUrl(), TGeshkaGateway.getToken(), info -> onGatewayChanged());
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        paused = true;
        AndroidUtilities.cancelRunOnUIThread(refresh);
    }

    @Override
    public void onGatewayChanged() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.TGeshkaSettings);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        boolean hasToken = TGeshkaGateway.hasToken();
        items.add(UItem.asHeader(getString(R.string.TGeshkaGatewayHeader)));
        items.add(UItem.asButton(ID_ADDRESS, getString(R.string.TGeshkaGatewayAddress),
                TGeshkaGateway.hasCustomUrl() ? TGeshkaGateway.getHost() : getString(R.string.TGeshkaGatewayAddressDefault)));
        items.add(UItem.asButton(ID_KEY, getString(R.string.TGeshkaGatewayKey),
                hasToken ? TGeshkaGateway.maskedToken() : getString(R.string.TGeshkaGatewayKeyNone)));
        items.add(UItem.asButton(ID_CHECK, getString(R.string.TGeshkaGatewayCheck)).accent());
        items.add(UItem.asShadow(getString(R.string.TGeshkaGatewayInfo)));

        long[] stats = TGeshkaGateway.getStats();
        TGeshkaGateway.Info info = TGeshkaGateway.getLastInfo();
        TGeshkaGateway.LastError error = TGeshkaGateway.getLastError();

        items.add(UItem.asHeader(getString(R.string.TGeshkaStatusHeader)));
        items.add(UItem.asButton(ID_STATUS, getString(R.string.TGeshkaStatus), statusText(hasToken)));
        if (info != null && info.ok) {
            items.add(UItem.asButton(ID_RTT, getString(R.string.TGeshkaRtt), LocaleController.formatString(R.string.TGeshkaRttValue, (int) info.rttMillis)));
            items.add(UItem.asButton(ID_CONNECTIONS, getString(R.string.TGeshkaConnections),
                    LocaleController.formatString(R.string.TGeshkaConnectionsValue, (int) stats[0], info.activeConnections, info.maxConnections)));
        } else {
            items.add(UItem.asButton(ID_CONNECTIONS, getString(R.string.TGeshkaConnections), String.valueOf(stats[0])));
        }
        items.add(UItem.asButton(ID_TRAFFIC, getString(R.string.TGeshkaTraffic),
                LocaleController.formatString(R.string.TGeshkaTrafficValue, AndroidUtilities.formatFileSize(stats[1]), AndroidUtilities.formatFileSize(stats[2]))));
        if (stats[3] > 0) {
            items.add(UItem.asButton(ID_LAST_CONNECT, getString(R.string.TGeshkaLastConnect), LocaleController.formatString(R.string.TGeshkaRttValue, (int) stats[3])));
        }
        items.add(UItem.asButton(ID_LAST_ERROR, getString(R.string.TGeshkaLastError), errorText(error)));
        items.add(UItem.asButton(ID_RECONNECT, getString(R.string.TGeshkaReconnect)).accent());
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.TGeshkaKeepAliveHeader)));
        items.add(UItem.asButton(ID_NOTIFICATIONS, getString(R.string.TGeshkaNotifications),
                getString(TGeshkaKeepAlive.areNotificationsAllowed() ? R.string.TGeshkaNotificationsAllowed : R.string.TGeshkaNotificationsBlocked)));
        items.add(UItem.asCheck(ID_KEEP_ALIVE, getString(R.string.TGeshkaKeepAlive)).setChecked(TGeshkaKeepAlive.isEnabled()));
        items.add(UItem.asButton(ID_LAST_PUSH, getString(R.string.TGeshkaLastPush), lastPushText()));
        items.add(UItem.asButton(ID_BATTERY, getString(R.string.TGeshkaBattery)));
        items.add(UItem.asShadow(getString(R.string.TGeshkaKeepAliveInfo) + "\n\n" + getString(R.string.TGeshkaLastPushInfo) + "\n\n" + getString(R.string.TGeshkaBatteryInfo)));

        items.add(UItem.asHeader(getString(R.string.TGeshkaUpdatesHeader)));
        items.add(UItem.asButton(ID_UPDATES, getString(R.string.TGeshkaCheckUpdates), BuildConfig.TGESHKA_VERSION));
        items.add(UItem.asButton(ID_WHATS_NEW, getString(R.string.TGeshkaWhatsNew)));
        items.add(UItem.asButton(ID_ABOUT, getString(R.string.TGeshkaAbout)));
        items.add(UItem.asShadow(null));
    }

    private String statusText(boolean hasToken) {
        if (!hasToken) {
            return getString(R.string.TGeshkaStatusNoKey);
        }
        int state = ConnectionsManager.getInstance(currentAccount).getConnectionState();
        if (state == ConnectionsManager.ConnectionStateConnected || state == ConnectionsManager.ConnectionStateUpdating) {
            return getString(R.string.TGeshkaStatusConnected);
        }
        return getString(R.string.TGeshkaStatusConnecting);
    }

    private String lastPushText() {
        long time = TGeshkaKeepAlive.getLastInternalPush();
        if (time == 0) {
            return getString(R.string.TGeshkaLastPushNone);
        }
        return new SimpleDateFormat("dd.MM HH:mm:ss", Locale.US).format(new Date(time));
    }

    private String errorText(TGeshkaGateway.LastError error) {
        if (error == null) {
            return getString(R.string.TGeshkaLastErrorNone);
        }
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(error.time));
        return TGeshkaGateway.errorText(error.code) + ", " + time;
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_ADDRESS:
                editAddress();
                break;
            case ID_KEY:
                editKey();
                break;
            case ID_CHECK:
                check();
                break;
            case ID_LAST_ERROR: {
                TGeshkaGateway.LastError error = TGeshkaGateway.getLastError();
                if (error != null && !TextUtils.isEmpty(error.reason)) {
                    Toast.makeText(getParentActivity(), error.code + ": " + error.reason, Toast.LENGTH_LONG).show();
                }
                break;
            }
            case ID_RECONNECT:
                TGeshkaGateway.applyToNative();
                TGeshkaGateway.reconnect();
                Toast.makeText(getParentActivity(), getString(R.string.TGeshkaReconnectDone), Toast.LENGTH_SHORT).show();
                break;
            case ID_NOTIFICATIONS:
                TGeshkaKeepAlive.openNotificationSettings(getParentActivity());
                break;
            case ID_KEEP_ALIVE:
                TGeshkaKeepAlive.setEnabled(!TGeshkaKeepAlive.isEnabled());
                listView.adapter.update(true);
                break;
            case ID_BATTERY:
                if (TGeshkaKeepAlive.isIgnoringBatteryOptimizations()) {
                    Toast.makeText(getParentActivity(), getString(R.string.TGeshkaBatteryDone), Toast.LENGTH_SHORT).show();
                } else {
                    TGeshkaKeepAlive.requestIgnoreBatteryOptimizations(getParentActivity());
                }
                break;
            case ID_UPDATES:
                TGeshkaUpdater.check(getParentActivity(), true);
                break;
            case ID_WHATS_NEW:
                presentFragment(new TGeshkaWhatsNewActivity());
                break;
            case ID_ABOUT:
                showAbout();
                break;
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private EditText createInput(Context context, String value, String hint, boolean password) {
        EditText editText = new EditText(context);
        editText.setSingleLine(true);
        editText.setText(value);
        editText.setHint(hint);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        editText.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | (password ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_URI));
        if (value != null) {
            editText.setSelection(value.length());
        }
        return editText;
    }

    private void showInputDialog(String title, EditText editText, String neutral, Runnable onNeutral, Utilities1 onSave) {
        Context context = getParentActivity();
        FrameLayout frame = new FrameLayout(context);
        frame.addView(editText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 24, 8, 24, 0));
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(title);
        builder.setView(frame);
        builder.setPositiveButton(getString(R.string.Save), (dialog, which) -> onSave.run(editText.getText().toString().trim()));
        builder.setNegativeButton(getString(R.string.Cancel), null);
        if (neutral != null) {
            builder.setNeutralButton(neutral, (dialog, which) -> onNeutral.run());
        }
        showDialog(builder.create());
        AndroidUtilities.runOnUIThread(() -> {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
        }, 200);
    }

    private interface Utilities1 {
        void run(String value);
    }

    private void editAddress() {
        EditText editText = createInput(getParentActivity(), TGeshkaGateway.getUrl(), getString(R.string.TGeshkaAddressHint), false);
        showInputDialog(getString(R.string.TGeshkaAddressTitle), editText, getString(R.string.TGeshkaAddressReset),
                () -> TGeshkaGateway.save("", null),
                value -> {
                    if (!TGeshkaGateway.isValidUrl(value)) {
                        Toast.makeText(getParentActivity(), getString(R.string.TGeshkaAddressInvalid), Toast.LENGTH_LONG).show();
                        return;
                    }
                    TGeshkaGateway.save(value, null);
                });
    }

    private void editKey() {
        String initial = "";
        try {
            ClipboardManager clipboard = (ClipboardManager) getParentActivity().getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard.hasPrimaryClip() && clipboard.getPrimaryClip().getItemCount() > 0) {
                CharSequence text = clipboard.getPrimaryClip().getItemAt(0).getText();
                if (TGeshkaGateway.parseImport(text == null ? null : text.toString()) != null) {
                    initial = text.toString().trim();
                }
            }
        } catch (Exception ignore) {
        }
        EditText editText = createInput(getParentActivity(), initial, getString(R.string.TGeshkaKeyHint), true);
        showInputDialog(getString(R.string.TGeshkaGatewayKey), editText, null, null, value -> {
            String[] parsed = TGeshkaGateway.parseImport(value);
            if (parsed == null) {
                Toast.makeText(getParentActivity(), getString(R.string.TGeshkaKeyInvalid), Toast.LENGTH_LONG).show();
                return;
            }
            TGeshkaGateway.save(parsed[0], parsed[1]);
        });
    }

    private void check() {
        if (checking) {
            return;
        }
        if (!TGeshkaGateway.hasToken()) {
            Toast.makeText(getParentActivity(), getString(R.string.TGeshkaErrorNoToken), Toast.LENGTH_SHORT).show();
            return;
        }
        checking = true;
        TGeshkaGateway.fetchInfo(TGeshkaGateway.getUrl(), TGeshkaGateway.getToken(), info -> {
            checking = false;
            if (getParentActivity() == null) {
                return;
            }
            String text = info.ok
                    ? LocaleController.formatString(R.string.TGeshkaCheckOk, (int) info.rttMillis, info.activeConnections, info.maxConnections)
                    : LocaleController.formatString(R.string.TGeshkaCheckFailed, info.error);
            Toast.makeText(getParentActivity(), text, Toast.LENGTH_LONG).show();
            onGatewayChanged();
        });
    }

    private void showAbout() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.TGeshkaAbout));
        builder.setMessage(LocaleController.formatString(R.string.TGeshkaAboutText, BuildConfig.TGESHKA_VERSION, BuildVars.BUILD_VERSION_STRING));
        builder.setPositiveButton(getString(R.string.OK), null);
        builder.setNeutralButton(getString(R.string.TGeshkaAboutSources), (dialog, which) ->
                org.telegram.messenger.browser.Browser.openUrl(getParentActivity(), TGeshkaGateway.SOURCES_URL));
        showDialog(builder.create());
    }
}
