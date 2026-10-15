package dev.chaoxingdeadline;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Shared view builders for every screen.
 *
 * These primitives are what gives the app one visual language: {@link #card()} is a
 * frosted glass panel, {@link #sectionHeader} a muted group label, and page backgrounds
 * come from {@link UiTheme#glassBackground}. Pages built from these inherit the style,
 * so a screen never has to re-implement it (and cannot drift from it).
 */
public class BaseActivity extends Activity {

    protected TextView text(String value, int size, boolean bold, int color) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    protected TextView icon(String value) {
        return icon(value, 24, UiTheme.text(this));
    }

    protected TextView icon(String value, int size) {
        return icon(value, size, UiTheme.muted(this));
    }

    protected TextView icon(String value, int size, int color) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setBackgroundColor(Color.TRANSPARENT);
        return v;
    }

    /** Frosted glass panel — the standard container for a group of rows. */
    protected LinearLayout card() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setBackground(UiTheme.glassCard(this, dp(20)));
        return v;
    }

    protected LinearLayout hbox() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    /**
     * Page background: the glass wash sits behind the content, so translucent panels
     * above it read as frosted rather than flat grey.
     */
    protected View glassBackdrop() {
        View view = new View(this);
        view.setBackground(UiTheme.glassBackground(this));
        return view;
    }

    protected TextView sectionHeader(String title) {
        TextView v = new TextView(this);
        v.setText(title);
        v.setTextSize(13);
        v.setTextColor(UiTheme.muted(this));
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(dp(6), dp(18), dp(6), dp(8));
        return v;
    }

    @SuppressWarnings("deprecation")
    protected void applySystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (!UiTheme.dark(this)) {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    protected int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    protected int dp(int value) {
        return UiTheme.dp(this, value);
    }
}
