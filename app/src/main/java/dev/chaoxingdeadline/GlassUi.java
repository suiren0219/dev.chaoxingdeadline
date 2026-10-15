package dev.chaoxingdeadline;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Builder for the glass segment: a translucent card, a two-state tab strip and the
 * small "chip" used for inline status. Shared by the timetable page's three tabs
 * (今日 / 周 / 设置) so they stay visually identical.
 *
 * The visual language follows the NexioSchedule reference: soft colour wash behind,
 * frosted panels on top, generous 18-22dp radii, thin light edges and 12-13sp muted
 * secondary text. No blur library — see {@link UiTheme#glassBackground}.
 */
final class GlassUi {

    private GlassUi() {
    }

    /** Page backdrop; call once per page, under the content. */
    static View backdrop(Activity activity) {
        View view = new View(activity);
        view.setBackground(UiTheme.glassBackground(activity));
        return view;
    }

    /** Frosted panel with the standard radius. */
    static LinearLayout panel(Activity activity) {
        LinearLayout v = new LinearLayout(activity);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setBackground(UiTheme.glassCard(activity, UiTheme.dp(activity, 20)));
        return v;
    }

    static LinearLayout column(Activity activity) {
        LinearLayout v = new LinearLayout(activity);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    static LinearLayout row(Activity activity) {
        LinearLayout v = new LinearLayout(activity);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    static TextView title(Activity activity, String value, int sizeSp) {
        TextView v = new TextView(activity);
        v.setText(value);
        v.setTextSize(sizeSp);
        v.setTextColor(UiTheme.text(activity));
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    static TextView body(Activity activity, String value, int sizeSp) {
        TextView v = new TextView(activity);
        v.setText(value);
        v.setTextSize(sizeSp);
        v.setTextColor(UiTheme.muted(activity));
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setLineSpacing(0f, 1.25f);
        return v;
    }

    /** Small rounded label, e.g. 上课中 / 已结束 / 本周. */
    static TextView chip(Activity activity, String value, int fill, int textColor) {
        TextView v = new TextView(activity);
        v.setText(value);
        v.setTextSize(11);
        v.setTextColor(textColor);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setGravity(Gravity.CENTER);
        int padH = UiTheme.dp(activity, 8);
        v.setPadding(padH, UiTheme.dp(activity, 3), padH, UiTheme.dp(activity, 3));
        v.setBackground(UiTheme.glassFill(activity, fill, UiTheme.dp(activity, 9)));
        return v;
    }

    /**
     * Tab strip: the selected tab gets an accent wash, the others stay transparent so
     * the page backdrop shows through.
     */
    static LinearLayout tabStrip(Activity activity, String[] labels, int selected,
                                 final TabListener listener) {
        LinearLayout strip = row(activity);
        strip.setPadding(UiTheme.dp(activity, 4), UiTheme.dp(activity, 4),
                UiTheme.dp(activity, 4), UiTheme.dp(activity, 4));
        strip.setBackground(UiTheme.glassCard(activity, UiTheme.dp(activity, 16)));
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView tab = new TextView(activity);
            tab.setText(labels[i]);
            tab.setTextSize(14);
            tab.setGravity(Gravity.CENTER);
            boolean active = i == selected;
            tab.setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            tab.setTextColor(active ? Color.WHITE : UiTheme.muted(activity));
            tab.setBackground(active
                    ? UiTheme.glassFill(activity, UiTheme.accent(activity), UiTheme.dp(activity, 13))
                    : null);
            tab.setPadding(0, UiTheme.dp(activity, 9), 0, UiTheme.dp(activity, 9));
            tab.setOnClickListener(v -> listener.onTabSelected(index));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1f);
            params.setMargins(UiTheme.dp(activity, 2), 0, UiTheme.dp(activity, 2), 0);
            strip.addView(tab, params);
        }
        return strip;
    }

    interface TabListener {
        void onTabSelected(int index);
    }

    /** Rounded action button; {@code filled} selects the primary (accent) look. */
    static TextView button(Activity activity, String label, boolean filled) {
        TextView v = new TextView(activity);
        v.setText(label);
        v.setTextSize(14);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setGravity(Gravity.CENTER);
        v.setPadding(UiTheme.dp(activity, 20), UiTheme.dp(activity, 10),
                UiTheme.dp(activity, 20), UiTheme.dp(activity, 10));
        if (filled) {
            v.setTextColor(Color.WHITE);
            v.setBackground(UiTheme.glassFill(activity, UiTheme.accent(activity),
                    UiTheme.dp(activity, 13)));
        } else {
            v.setTextColor(UiTheme.accent(activity));
            v.setBackground(UiTheme.glassCard(activity, UiTheme.dp(activity, 13)));
        }
        return v;
    }

    static TextView sectionLabel(Activity activity, String value) {
        TextView v = new TextView(activity);
        v.setText(value);
        v.setTextSize(13);
        v.setTextColor(UiTheme.muted(activity));
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(UiTheme.dp(activity, 6), UiTheme.dp(activity, 14),
                UiTheme.dp(activity, 6), UiTheme.dp(activity, 8));
        return v;
    }

    /** A labelled switch row inside a panel. */
    static View switchRow(Activity activity, String title, String subtitle, boolean checked,
                          android.widget.CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout container = row(activity);
        container.setPadding(UiTheme.dp(activity, 16), UiTheme.dp(activity, 12),
                UiTheme.dp(activity, 12), UiTheme.dp(activity, 12));
        LinearLayout texts = column(activity);
        texts.addView(title(activity, title, 15), new LinearLayout.LayoutParams(-1, -2));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = body(activity, subtitle, 12);
            sub.setPadding(0, UiTheme.dp(activity, 2), 0, 0);
            texts.addView(sub, new LinearLayout.LayoutParams(-1, -2));
        }
        container.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        android.widget.Switch toggle = new android.widget.Switch(activity);
        toggle.setText("");
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener(listener);
        container.addView(toggle, new LinearLayout.LayoutParams(-2, -2));
        return container;
    }

    /** Tappable row that opens a subsection. */
    static View actionRow(Activity activity, String title, String subtitle) {
        LinearLayout container = row(activity);
        container.setPadding(UiTheme.dp(activity, 16), UiTheme.dp(activity, 14),
                UiTheme.dp(activity, 16), UiTheme.dp(activity, 14));
        LinearLayout texts = column(activity);
        texts.addView(title(activity, title, 15), new LinearLayout.LayoutParams(-1, -2));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = body(activity, subtitle, 12);
            sub.setPadding(0, UiTheme.dp(activity, 2), 0, 0);
            texts.addView(sub, new LinearLayout.LayoutParams(-1, -2));
        }
        container.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView arrow = new TextView(activity);
        arrow.setText("›");
        arrow.setTextSize(20);
        arrow.setTextColor(UiTheme.muted(activity));
        arrow.setGravity(Gravity.CENTER);
        container.addView(arrow, new LinearLayout.LayoutParams(-2, -2));
        return container;
    }
}
