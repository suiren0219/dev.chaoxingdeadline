package dev.chaoxingdeadline;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

public final class UiTheme {
    private UiTheme() {}

    public static boolean dark(Context context) {
        int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    // -- backgrounds --
    public static int background(Context context) {
        return dark(context) ? Color.rgb(10, 12, 18) : Color.rgb(248, 249, 252);
    }

    public static int card(Context context) {
        return dark(context) ? Color.rgb(24, 28, 39) : Color.rgb(255, 255, 255);
    }

    public static int sheet(Context context) {
        return dark(context) ? Color.rgb(20, 23, 33) : Color.rgb(255, 255, 255);
    }

    // -- strokes & dividers --
    public static int stroke(Context context) {
        return dark(context) ? Color.rgb(42, 52, 68) : Color.rgb(232, 236, 244);
    }

    public static int divider(Context context) {
        return dark(context) ? Color.rgb(42, 52, 68) : Color.rgb(241, 243, 249);
    }

    // -- text --
    public static int text(Context context) {
        return dark(context) ? Color.rgb(235, 240, 247) : Color.rgb(15, 23, 42);
    }

    public static int muted(Context context) {
        return dark(context) ? Color.rgb(148, 163, 184) : Color.rgb(100, 116, 139);
    }

    public static int accent(Context context) {
        return dark(context) ? Color.rgb(99, 153, 255) : Color.rgb(59, 111, 240);
    }

    // -- status colors --

    public static int successText(Context context) {
        return dark(context) ? Color.rgb(148, 231, 198) : Color.rgb(20, 120, 84);
    }


    public static int warningText(Context context) {
        return dark(context) ? Color.rgb(255, 200, 129) : Color.rgb(174, 93, 24);
    }

    public static int dangerBg(Context context) {
        return dark(context) ? Color.rgb(68, 35, 42) : Color.rgb(255, 238, 240);
    }

    public static int dangerText(Context context) {
        return dark(context) ? Color.rgb(248, 113, 113) : Color.rgb(194, 65, 72);
    }


    // -- badge colors --
    public static int badgeHomework(Context context) {
        return dark(context) ? Color.rgb(99, 153, 255) : Color.rgb(59, 111, 240);
    }

    public static int badgeHomeworkBg(Context context) {
        return dark(context) ? Color.argb(35, 99, 153, 255) : Color.argb(20, 59, 111, 240);
    }

    public static int badgeExam(Context context) {
        return dark(context) ? Color.rgb(236, 134, 72) : Color.rgb(214, 94, 34);
    }

    public static int badgeExamBg(Context context) {
        return dark(context) ? Color.argb(35, 236, 134, 72) : Color.argb(20, 214, 94, 34);
    }

    public static int badgeChapter(Context context) {
        return dark(context) ? Color.rgb(79, 209, 197) : Color.rgb(14, 159, 142);
    }

    public static int badgeChapterBg(Context context) {
        return dark(context) ? Color.argb(35, 79, 209, 197) : Color.argb(20, 14, 159, 142);
    }

    public static int badgeActivity(Context context) {
        return dark(context) ? Color.rgb(167, 139, 250) : Color.rgb(124, 58, 237);
    }

    public static int badgeActivityBg(Context context) {
        return dark(context) ? Color.argb(35, 167, 139, 250) : Color.argb(20, 124, 58, 237);
    }

    // -- course colours (timetable grid, lesson cards, widgets) --

    /** Course block palettes: {soft background, saturated foreground}, light then dark. */
    private static final int[][] LIGHT_COURSE_COLORS = {
            {0xFFE3EDFF, 0xFF2B5CB8}, {0xFFFFE7EE, 0xFFB5325F},
            {0xFFFFF3D6, 0xFF8F6500}, {0xFFDFF5EC, 0xFF14785A},
            {0xFFEFE6FF, 0xFF6D3FC0}, {0xFFFFE9D6, 0xFFB4531A},
            {0xFFD9F1F5, 0xFF0E7490}, {0xFFFBE3D0, 0xFF9A4A1B},
            {0xFFE8E8F6, 0xFF4A4E8A}, {0xFFF6E2E4, 0xFFA04550},
    };
    private static final int[][] DARK_COURSE_COLORS = {
            {0xFF1D2A45, 0xFF9CC0FF}, {0xFF3A1F2C, 0xFFF3A9C4},
            {0xFF3B3117, 0xFFE8C56A}, {0xFF17362B, 0xFF7FD4B4},
            {0xFF2C2450, 0xFFC4AEF7}, {0xFF3D2717, 0xFFF0B183},
            {0xFF143340, 0xFF7CC7DC}, {0xFF3A2415, 0xFFE5A86F},
            {0xFF232648, 0xFFAAB2E8}, {0xFF3B2226, 0xFFE8A3AE},
    };

    /**
     * Stable per-course colours: the same course keeps its colour across the grid,
     * the lesson cards and the widget. Uses {@code hash & 0x7fffffff} because
     * {@code Math.abs(Integer.MIN_VALUE)} is negative and would index off the array.
     */
    public static int[] courseColors(Context context, String name) {
        int[][] palette = dark(context) ? DARK_COURSE_COLORS : LIGHT_COURSE_COLORS;
        int hash = name == null ? 0 : name.hashCode();
        return palette[(hash & 0x7fffffff) % palette.length];
    }

    /** Tint for the "today" column background in the timetable grid. */
    public static int todayTint(Context context) {
        int accent = accent(context);
        return Color.argb(dark(context) ? 34 : 20, Color.red(accent),
                Color.green(accent), Color.blue(accent));
    }

    // -- drawable builders --
    public static GradientDrawable rounded(Context context, int color) {
        return rounded(context, color, dp(context, 8));
    }

    public static GradientDrawable rounded(Context context, int color, int radius) {
        return rounded(context, color, radius, stroke(context));
    }

    public static GradientDrawable rounded(Context context, int color, int radius, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        if (strokeColor != 0) d.setStroke(dp(context, 1), strokeColor);
        return d;
    }

    public static GradientDrawable cardBg(Context context) {
        return rounded(context, card(context), dp(context, 16), stroke(context));
    }

    public static GradientDrawable cardBg(Context context, int radius) {
        return rounded(context, card(context), radius, stroke(context));
    }

    public static GradientDrawable fillOnly(Context context, int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    public static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}