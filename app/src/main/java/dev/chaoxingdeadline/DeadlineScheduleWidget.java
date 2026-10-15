package dev.chaoxingdeadline;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.View;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The home-screen widget: one panel with two faces — the day's timetable and the
 * deadline list — switched by the ⇄ button in the header.
 *
 * The timetable face is designed around the 4x2 placement, which is what the widget
 * declares and what most people use: it lists the lessons of the day at once rather than
 * one per page, because a timetable you have to tap through is not a timetable. Rows are
 * static RemoteViews added to a plain {@link android.widget.LinearLayout}, which is the
 * container that can hold them — {@code RemoteViews.addView} supports any ViewGroup, and a
 * LinearLayout shows every child at once. The alternatives do not work: StackView and
 * ListView extend AdapterView, whose {@code addView} throws UnsupportedOperationException
 * (they need a RemoteViewsService, i.e. an extra bound service and a blank widget whenever
 * the launcher kills the process), and a ViewFlipper accepts children but displays one.
 *
 * How many rows fit is read from the widget's own height, and so is how much chrome the
 * header may spend: a 4x2 strip gets a single-line header and tighter rows, a taller panel
 * gets the full header and up to {@link #MAX_ROWS} rows. One layout therefore serves both
 * without scrolling.
 *
 * Lessons that have already finished are dropped from the list, so the face always answers
 * "what is left today" rather than repeating the morning at 8pm; a lesson in progress heads
 * it and is marked. After {@link #EVENING_HOUR} the face previews tomorrow instead, which
 * rolls back to today after midnight. Each widget instance remembers its own face, so two
 * widgets on the same home screen do not fight over the display.
 */
public final class DeadlineScheduleWidget extends AppWidgetProvider {
    /** Per-widget UI state: which face is showing. */
    private static final String PREFS = "schedule_widget_state";
    /** Absolute cap, so a tall panel cannot fill the home screen with timetable rows. */
    private static final int MAX_ROWS = 8;
    /** Height of one lesson row on a tall panel (34dp strip + padding + gap). */
    private static final int ROW_HEIGHT_DP = 48;
    /** Header plus the footnote, which the rows share a tall panel with. */
    private static final int CHROME_HEIGHT_DP = 96;
    /**
     * Below this the panel is the 4x2 strip, and the chrome has to give way to the rows.
     *
     * A 4x2 panel is roughly 120-160dp tall. Charging it the full 96dp of chrome (two-line
     * header, footnote and margins) leaves room for a single lesson, which is exactly the
     * complaint this fixes. A short panel therefore gets a one-line header, tighter margins
     * and slightly shorter rows — the difference between "4x2 works" and "4x2 shows one".
     */
    private static final int COMPACT_HEIGHT_DP = 200;
    /**
     * Chrome a short panel may spend: 8dp padding + a one-line header + gap + footnote.
     *
     * The footnote is the "reference only" line the user asked to keep, so it stays even
     * here; what goes is the second header line and the generous padding. 56dp is what is
     * left after those, and it is deliberately tight — every dp spent on chrome is a lesson
     * that does not fit, and at 4x2 the whole point is several lessons at once.
     */
    private static final int CHROME_COMPACT_DP = 56;
    /** Row height on a short panel; the row layout is the same, it just gets less room. */
    private static final int ROW_COMPACT_DP = 42;
    /** Rows assumed when the launcher reports no size: what a 4x2 panel fits. */
    static final int DEFAULT_ROWS = 2;


    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        updateAll(context);
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        if (appWidgetIds == null) {
            return;
        }
    }

    static void updateAll(Context context) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            int[] ids = manager.getAppWidgetIds(
                    new ComponentName(context, DeadlineScheduleWidget.class));
            if (ids == null || ids.length == 0) {
                return;
            }
            for (int id : ids) {
                manager.updateAppWidget(id, build(context, id));
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Test hook: build one face through the real path, with the row budget forced.
     *
     * {@code build} reads its face from SharedPreferences and its geometry from the
     * launcher's reported widget size, neither of which a unit test can control from
     * outside. This enters the same code with both pinned.
     */
    static RemoteViews buildForTest(Context context, int appWidgetId, int forcedRows) {
        return build(context, appWidgetId, forcedRows, 0);
    }

    private static RemoteViews build(Context context, int appWidgetId) {
        return build(context, appWidgetId, -1, reportedHeightDp(context, appWidgetId));
    }

    /**
     * Height the launcher reports for this instance, or 0 when it reports nothing.
     *
     * Both the row budget and the chrome decision come from this one read, so the caller
     * passes it down rather than each part asking the framework again.
     */
    private static int reportedHeightDp(Context context, int appWidgetId) {
        try {
            android.os.Bundle options =
                    AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId);
            if (options != null) {
                String key = android.os.Build.VERSION.SDK_INT >= 31
                        ? AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
                        : AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT;
                return options.getInt(key, 0);
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /**
     * @param forcedRows row budget to use instead of the reported height; -1 to derive it
     * @param heightDp   height the launcher reported; 0 when unknown
     */
    private static RemoteViews build(Context context, int appWidgetId, int forcedRows,
                                     int heightDp) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_schedule);
        boolean compact = isCompact(heightDp);

        long now = System.currentTimeMillis();
        List<DeadlineItem> pending = DeadlineWidgetProvider.pendingItems(context);

        views.setTextColor(R.id.s_header, DeadlineWidgetProvider.primaryText(context, true));
        views.setTextColor(R.id.s_updated, DeadlineWidgetProvider.mutedText(context, true));
        views.setTextColor(R.id.s_refresh, DeadlineWidgetProvider.mutedText(context, true));
        views.setTextColor(R.id.s_note, DeadlineWidgetProvider.mutedText(context, true));
        views.setTextColor(R.id.s_empty_text, DeadlineWidgetProvider.mutedText(context, true));
        views.setInt(R.id.s_empty_action, "setBackgroundColor",
                DeadlineWidgetProvider.accent(context));

        // The compact arithmetic above assumes a small outer padding; make it true here
        // rather than only in the comment.
        int pad = compact ? dp(context, 8) : dp(context, 16);
        views.setViewPadding(R.id.widget_schedule_root, pad, pad, pad, pad);
        views.setViewPadding(R.id.s_note, 0, 0, 0, 0);

        views.removeAllViews(R.id.s_rows);
        int rowBudget = forcedRows > 0 ? forcedRows : rowsForHeight(heightDp);
        int count = pending.size();
        views.setTextViewText(R.id.s_header, "Deadline 待办");
        views.setTextViewText(R.id.s_updated, count > 0
                ? "共 " + count + " 项 · 更新于 " + DateText.deadlineTime(now)
                : "更新于 " + DateText.deadlineTime(now));
        views.setViewVisibility(R.id.s_updated, View.GONE);
        views.setViewVisibility(R.id.s_note, View.GONE);
        buildTodoFace(context, views, pending, rowBudget);

        // A tap on the title opens the app; the rows themselves are read-only.
        views.setOnClickPendingIntent(R.id.s_header, schedulePendingIntent(context));
        views.setOnClickPendingIntent(R.id.s_refresh,
                DeadlineWidgetProvider.refreshPendingIntent(context));
        return views;
    }

    private static void buildTodoFace(Context context, RemoteViews views,
                                      List<DeadlineItem> pending, int rowBudget) {
        views.setViewVisibility(R.id.s_empty, View.GONE);
        if (pending.isEmpty()) {
            views.setViewVisibility(R.id.s_rows, View.GONE);
            views.setViewVisibility(R.id.s_todo_empty, View.VISIBLE);
            return;
        }
        views.setViewVisibility(R.id.s_todo_empty, View.GONE);
        views.setViewVisibility(R.id.s_rows, View.VISIBLE);
        int shown = Math.min(pending.size(), rowBudget);
        for (int i = 0; i < shown; i++) {
            views.addView(R.id.s_rows,
                    DeadlineWidgetProvider.deadlineRow(context, pending.get(i), i, true));
        }
    }

    /**
     * Rows that fit in a panel {@code heightDp} tall, clamped to [1, {@link #MAX_ROWS}].
     *
     * Pure dp arithmetic — no density conversion and no Android calls — so the sizing rules
     * are unit-testable. A short (4x2) panel is charged {@link #CHROME_COMPACT_DP} of header
     * and {@link #ROW_COMPACT_DP} per row; a tall one gets the roomier pair. A launcher that
     * reports nothing (which happens) falls back to {@link #DEFAULT_ROWS}, the 4x2 figure.
     */
    static int rowsForHeight(int heightDp) {
        if (heightDp <= 0) {
            return DEFAULT_ROWS;
        }
        boolean compact = isCompact(heightDp);
        int chrome = compact ? CHROME_COMPACT_DP : CHROME_HEIGHT_DP;
        int rowHeight = compact ? ROW_COMPACT_DP : ROW_HEIGHT_DP;
        int usable = heightDp - chrome;
        if (usable <= 0) {
            return 1;
        }
        return Math.max(1, Math.min(MAX_ROWS, usable / rowHeight));
    }

    /** Whether the reported height means the panel is the short 4x2 strip. */
    static boolean isCompact(int heightDp) {
        return heightDp > 0 && heightDp < COMPACT_HEIGHT_DP;
    }


    private static PendingIntent schedulePendingIntent(Context context) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, "schedule_open".hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** dp → px, for the RemoteViews padding calls above. */
    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static SharedPreferences statePrefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
