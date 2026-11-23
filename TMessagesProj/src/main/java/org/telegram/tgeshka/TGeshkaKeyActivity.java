/*
 * This file is part of TGeshka, an unofficial client based on Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package org.telegram.tgeshka;

import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.IntroActivity;

import static org.telegram.messenger.LocaleController.getString;

/** First start: "Вставьте ключ доступа". Without a key there is no connection at all. */
public class TGeshkaKeyActivity extends BaseFragment implements TGeshkaGateway.Listener {

    private EditText keyField;
    private TextView errorView;
    private TextView continueButton;
    private String customUrl;
    private boolean checking;
    private boolean finished;

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
    public void onGatewayChanged() {
        // A tgeshka://gateway link was opened while this screen is shown.
        if (TGeshkaGateway.hasToken() && !checking) {
            proceed();
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setTitle(getString(R.string.TGeshkaSettings));
        actionBar.setCastShadows(false);

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        scrollView.addView(layout, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        ImageView logo = new ImageView(context);
        logo.setImageResource(R.mipmap.ic_launcher);
        layout.addView(logo, LayoutHelper.createLinear(96, 96, Gravity.CENTER_HORIZONTAL, 0, 40, 0, 0));

        TextView title = new TextView(context);
        title.setText(getString(R.string.TGeshkaKeyTitle));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setGravity(Gravity.CENTER);
        layout.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 24, 24, 24, 0));

        TextView text = new TextView(context);
        text.setText(getString(R.string.TGeshkaKeyText));
        text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        text.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText6));
        text.setGravity(Gravity.CENTER);
        layout.addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 32, 12, 32, 0));

        keyField = new EditText(context);
        keyField.setSingleLine(true);
        keyField.setHint(getString(R.string.TGeshkaKeyHint));
        keyField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        keyField.setTypeface(Typeface.MONOSPACE);
        keyField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        keyField.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        keyField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        layout.addView(keyField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 32, 24, 32, 0));

        errorView = new TextView(context);
        errorView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        errorView.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
        errorView.setGravity(Gravity.CENTER);
        errorView.setVisibility(View.GONE);
        layout.addView(errorView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 32, 8, 32, 0));

        TextView paste = link(context, getString(R.string.TGeshkaKeyPaste));
        paste.setOnClickListener(v -> pasteFromClipboard(true));
        layout.addView(paste, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 12, 0, 0));

        continueButton = new TextView(context);
        continueButton.setText(getString(R.string.TGeshkaKeyContinue));
        continueButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        continueButton.setTypeface(AndroidUtilities.bold());
        continueButton.setGravity(Gravity.CENTER);
        continueButton.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        continueButton.setBackground(Theme.AdaptiveRipple.filledRectByKey(Theme.key_featuredStickers_addButton, 8));
        continueButton.setOnClickListener(v -> submit());
        layout.addView(continueButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 32, 24, 32, 0));

        TextView other = link(context, getString(R.string.TGeshkaKeyOtherGateway));
        other.setOnClickListener(v -> editUrl(context));
        layout.addView(other, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 16, 0, 32));

        fragmentView = scrollView;
        pasteFromClipboard(false);
        return fragmentView;
    }

    private TextView link(Context context, String value) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        view.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(8));
        view.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
        return view;
    }

    private void pasteFromClipboard(boolean explicit) {
        try {
            ClipboardManager clipboard = (ClipboardManager) getParentActivity().getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard.hasPrimaryClip() && clipboard.getPrimaryClip().getItemCount() > 0) {
                CharSequence value = clipboard.getPrimaryClip().getItemAt(0).getText();
                if (value != null && (explicit || TGeshkaGateway.parseImport(value.toString()) != null)) {
                    keyField.setText(value.toString().trim());
                    keyField.setSelection(keyField.length());
                }
            }
        } catch (Exception ignore) {
        }
    }

    private void editUrl(Context context) {
        EditText editText = new EditText(context);
        editText.setSingleLine(true);
        editText.setText(customUrl != null ? customUrl : TGeshkaGateway.getUrl());
        editText.setHint(getString(R.string.TGeshkaAddressHint));
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        FrameLayout frame = new FrameLayout(context);
        frame.addView(editText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 24, 8, 24, 0));
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(getString(R.string.TGeshkaAddressTitle));
        builder.setView(frame);
        builder.setPositiveButton(getString(R.string.Save), (dialog, which) -> {
            String value = editText.getText().toString().trim();
            if (TGeshkaGateway.isValidUrl(value)) {
                customUrl = value;
            } else {
                showError(getString(R.string.TGeshkaAddressInvalid));
            }
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void showError(String error) {
        errorView.setText(error);
        errorView.setVisibility(error == null ? View.GONE : View.VISIBLE);
    }

    private void submit() {
        if (checking) {
            return;
        }
        String[] parsed = TGeshkaGateway.parseImport(keyField.getText().toString());
        if (parsed == null) {
            showError(getString(R.string.TGeshkaKeyInvalid));
            return;
        }
        String url = parsed[0] != null ? parsed[0] : (customUrl != null ? customUrl : TGeshkaGateway.getUrl());
        String token = parsed[1];
        checking = true;
        showError(null);
        continueButton.setText(getString(R.string.TGeshkaKeyChecking));
        TGeshkaGateway.fetchInfo(url, token, info -> {
            checking = false;
            if (fragmentView == null) {
                return;
            }
            continueButton.setText(getString(R.string.TGeshkaKeyContinue));
            if (!info.ok && info.httpCode == 404) {
                showError(getString(R.string.TGeshkaErrorRejected));
                return;
            }
            // Other errors (no network right now) do not block: the key looks valid,
            // the connection screen will show the state.
            TGeshkaGateway.save(url, token);
            proceed();
        });
    }

    private void proceed() {
        if (finished) {
            return;
        }
        finished = true;
        AndroidUtilities.hideKeyboard(keyField);
        presentFragment(new IntroActivity(), true);
    }
}
