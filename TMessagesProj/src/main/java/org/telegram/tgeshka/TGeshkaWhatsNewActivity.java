/*
 * This file is part of TGeshka, an unofficial client based on Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package org.telegram.tgeshka;

import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.List;

import static org.telegram.messenger.LocaleController.getString;

/** Settings → TGeshka → What's new: every version from CHANGELOG-tgeshka.json, newest first. */
public class TGeshkaWhatsNewActivity extends UniversalFragment {

    private List<TGeshkaWhatsNew.Release> releases;

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.TGeshkaWhatsNew);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (releases == null) {
            releases = TGeshkaWhatsNew.load(getContext());
        }
        if (releases.isEmpty()) {
            items.add(UItem.asShadow(getString(R.string.TGeshkaWhatsNewEmpty)));
            return;
        }
        for (TGeshkaWhatsNew.Release release : releases) {
            String title = release.version;
            if (!TextUtils.isEmpty(release.date)) {
                title += " · " + TGeshkaWhatsNew.formatDate(release.date);
            }
            items.add(UItem.asHeader(title));
            items.add(UItem.asCustom(createItemsView(release)));
            items.add(UItem.asShadow(null));
        }
    }

    private View createItemsView(TGeshkaWhatsNew.Release release) {
        TextView textView = new TextView(getContext());
        textView.setText(TGeshkaWhatsNew.itemsText(release));
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        textView.setLineSpacing(AndroidUtilities.dp(4), 1f);
        textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        textView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        textView.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(4), AndroidUtilities.dp(21), AndroidUtilities.dp(14));
        return textView;
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
