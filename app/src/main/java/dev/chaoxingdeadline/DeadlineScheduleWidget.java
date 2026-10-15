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
 * The timetable face lists several lessons at once rather than one per page: 4x2 is the
 * common placement, and a timetable you have to tap through is not a timetable. The rows
 * are static RemoteViews added to a plain {@link android.widget.LinearLayout}, which is the
 * container that can hold them: {@code RemoteViews.addView} supports any ViewGroup, and a
 * LinearLayout shows every child at once. The alternatives do not work — StackView and
 * ListView extend AdapterView, whose {@code addView} throws UnsupportedOperationException
 * (they need a RemoteViewsService, i.e. an extra bound service and a blank widget whenever
 * the launcher kills the process), and a ViewFlipper accepts children but displays only one
 * at a time.
 *
 * How many rows fit is read from the widget's own height, not assumed: a 4x2 panel and a
 * 4x4 panel get different counts, so the same layout serves both without scrolling.
 *
 * After {@link #EVENING_HOUR} the face switches to tomorrow's timetable, since that is
 * what someone checking their phone in the evening wants to see; it rolls back to today
 * after midnight. Each widget instance remembers its own face, so two widgets on the same
 * home screen do not fight over the display.
 */
public final class DeadlineScheduleWidget extends AppWidgetProvider {
    /** Per-widget UI state: which face is showing. */
    private static final String PREFS = "schedule_widget_state";
    private static final String KEY_FACE = "face_";
    /** Absolute cap, so a tall panel cannot fill the home screen with timetable rows. */
    private static final int MAX_ROWS = 8;
    /** Height of one lesson row, from widget_schedule_card.xml (34dp strip + padding). */
    private static final int ROW_HEIGHT_DP = 48;
    /** Header plus the "reference only" footnote, which the rows share the panel with. */
    private static final int CHROME_HEIGHT_DP = 96;
    /** Rows assumed when the launcher reports no size: what a 4x2 panel fits. */
    static final int DEFAULT_ROWS = 3;
    /** From this hour on, the timetable face previews tomorrow instead of today. */
    static final int EVENING_HOUR = 21;

    static final int FACE_TIMETABLE = 0;
    static final int FACE_TODO = 1;

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        updateAll(context);
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        if (appWidgetIds == null) {
            return;
        }
        SharedPreferences.Editor editor = statePrefs(context).edit();
        for (int id : appWidgetIds) {
            editor.remove(KEY_FACE + id);
        }
        editor.apply();
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

    /** Flips this widget to the other face. */
    static void toggleFace(Context context, int appWidgetId) {
        try {
            SharedPreferences prefs = statePrefs(context);
            int current = prefs.getInt(KEY_FACE + appWidgetId, FACE_TIMETABLE);
            prefs.edit().putInt(KEY_FACE + appWidgetId,
                    current == FACE_TIMETABLE ? FACE_TODO : FACE_TIMETABLE).apply();
            updateAll(context);
        } catch (Throwable ignored) {
        }
    }

    /** The day the timetable face is showing: today, or tomorrow from the evening on. */
    static boolean showingTomorrow(long now) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(now);
        return calendar.get(Calendar.HOUR_OF_DAY) >= EVENING_HOUR;
    }

    /**
     * Test hook: build one face through the real path, with the row budget forced.
     *
     * {@code build} reads its face from SharedPreferences and its row count from the
     * launcher's reported widget size, neither of which a unit test can control from
     * outside. This enters the same code with both pinned.
     */
    static RemoteViews buildForTest(Context context, int appWidgetId, int face) {
        return buildForTest(context, appWidgetId, face, -1);
    }

    static RemoteViews buildForTest(Context context, int appWidgetId, int face, int forcedRows) {
        statePrefs(context).edit().putInt(KEY_FACE + appWidgetId, face).apply();
        return forcedRows > 0
                ? buildWithBudget(context, appWidgetId, forcedRows)
                : build(context, appWidgetId);
    }

    private static RemoteViews build(Context context, int appWidgetId) {
        return buildWithBudget(context, appWidgetId, -1);
    }

    /** @param forcedRows row budget to use instead of reading the widget's height; -1 to read it. */
    private static RemoteViews buildWithBudget(Context context, int appWidgetId, int forcedRows) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_schedule);
        SharedPreferences prefs = statePrefs(context);
        boolean timetableFace = prefs.getInt(KEY_FACE + appWidgetId, FACE_TIMETABLE) == FACE_TIMETABLE;

        long now = System.currentTimeMillis();
        DeadlineStore store = new DeadlineStore(context);
        long firstWeekDate = store.scheduleFirstWeekDate();
        String sectionTimes = store.scheduleSectionTimes();
        List<DeadlineItem> pending = DeadlineWidgetProvider.pendingItems(context);

        views.setTextColor(R.id.s_header, DeadlineWidgetProvider.primaryText(context, true));
        views.setTextColor(R.id.s_updated, DeadlineWidgetProvider.mutedText(context, true));
        views.setTextColor(R.id.s_switch, DeadlineWidgetProvider.mutedText(context, true));
        views.setTextColor(R.id.s_refresh, DeadlineWidgetProvider.mutedText(context, true));
        views.setTextColor(R.id.s_note, DeadlineWidgetProvider.mutedText(context, true));
        views.setTextColor(R.id.s_empty_text, DeadlineWidgetProvider.mutedText(context, true));
        views.setInt(R.id.s_empty_action, "setBackgroundColor",
                DeadlineWidgetProvider.accent(context));

        views.removeAllViews(R.id.s_rows);
        int rowBudget = forcedRows > 0 ? forcedRows : rowBudget(context, appWidgetId);
        if (timetableFace) {
            buildTimetableFace(context, views, store, now, firstWeekDate, sectionTimes, rowBudget);
            views.setViewVisibility(R.id.s_note, View.VISIBLE);
        } else {
            int count = pending.size();
            views.setTextViewText(R.id.s_header, "Deadline 待办");
            views.setTextViewText(R.id.s_updated, count > 0
                    ? "共 " + count + " 项 · 更新于 " + DateText.deadlineTime(now)
                    : "更新于 " + DateText.deadlineTime(now));
            views.setViewVisibility(R.id.s_updated, View.VISIBLE);
            views.setViewVisibility(R.id.s_note, View.GONE);
            buildTodoFace(context, views, pending, rowBudget);
        }

        // The title is the way into the timetable page — the widget's rows are read-only.
        views.setOnClickPendingIntent(R.id.s_header, schedulePendingIntent(context));
        views.setOnClickPendingIntent(R.id.s_switch, switchFacePendingIntent(context, appWidgetId));
        views.setOnClickPendingIntent(R.id.s_refresh,
                DeadlineWidgetProvider.refreshPendingIntent(context));
        return views;
    }

    private static void buildTimetableFace(Context context, RemoteViews views, DeadlineStore store,
                                           long now, long firstWeekDate, String sectionTimes,
                                           int rowBudget) {
        views.setViewVisibility(R.id.s_todo_empty, View.GONE);
        boolean tomorrow = showingTomorrow(now);
        // The evening preview belongs to the next calendar day, so both the lessons and
        // their timestamps come from that day.
        long dayStart = now + (tomorrow ? 24L * 60L * 60L * 1000L : 0L);
        int week = ScheduleMath.currentWeek(dayStart, firstWeekDate);
        List<Lesson> lessons = ScheduleMath.lessonsOn(store.allLessons(), dayStart, firstWeekDate);
        Lesson current = tomorrow ? null
                : ScheduleMath.currentLesson(lessons, now, firstWeekDate, sectionTimes);

        String weekday = new SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(new Date(dayStart));
        String dayWord = tomorrow ? "明天" : "今天";
        views.setTextViewText(R.id.s_header, week > 0
                ? "第 " + week + " 周 · " + dayWord + " " + lessons.size() + " 节"
                : "课表未获取");
        views.setTextViewText(R.id.s_updated, weekday + " · 更新于 "
                + DateText.deadlineTime(now));
        views.setViewVisibility(R.id.s_updated, View.VISIBLE);

        if (lessons.isEmpty()) {
            views.setViewVisibility(R.id.s_rows, View.GONE);
            views.setViewVisibility(R.id.s_empty, View.VISIBLE);
            boolean everFetched = store.scheduleUpdatedAt() > 0L;
            views.setTextViewText(R.id.s_empty_text, everFetched
                    ? (tomorrow ? "明天没有课" : "今天没有课")
                    : "还没有课表数据\n打开学习通课表页面即可自动获取");
            views.setViewVisibility(R.id.s_empty_action, everFetched ? View.GONE : View.VISIBLE);
            views.setOnClickPendingIntent(R.id.s_empty_action,
                    DeadlineWidgetProvider.fetchSemesterPendingIntent(context));
            return;
        }

        views.setViewVisibility(R.id.s_empty, View.GONE);
        views.setViewVisibility(R.id.s_rows, View.VISIBLE);
        // While a class is in session it heads the list (the widget answers "what now?");
        // otherwise plain time order, which is what the day reads as.
        List<Lesson> ordered = new ArrayList<>(lessons);
        if (current != null) {
            for (int i = 0; i < ordered.size(); i++) {
                if (current.id != null && current.id.equals(ordered.get(i).id)) {
                    ordered.add(0, ordered.remove(i));
                    break;
                }
            }
        }
        int shown = Math.min(ordered.size(), rowBudget);
        for (int i = 0; i < shown; i++) {
            views.addView(R.id.s_rows, row(context, ordered.get(i), current, week,
                    firstWeekDate, sectionTimes, now, tomorrow, i < shown - 1));
        }
    }

    /**
     * How many lesson rows this instance can show.
     *
     * Read from the widget's own size rather than fixed, because the same layout backs both
     * the 4x2 placement (two or three rows) and a taller one (up to {@link #MAX_ROWS}).
     * The launcher reports the size in dp via {@link AppWidgetManager#getAppWidgetOptions},
     * so the arithmetic is done in dp and converted at the end. A launcher that reports
     * nothing gets the 4x2-friendly default rather than a guess that overflows.
     */
    private static int rowBudget(Context context, int appWidgetId) {
        int heightDp = 0;
        try {
            android.os.Bundle options =
                    AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId);
            if (options != null) {
                String key = android.os.Build.VERSION.SDK_INT >= 31
                        ? AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
                        : AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT;
                heightDp = options.getInt(key, 0);
            }
        } catch (Throwable ignored) {
        }
        return rowsForHeight(heightDp);
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
     * Rows that fit in a panel {@code heightDp} tall: everything above the chrome goes to
     * rows, at {@link #ROW_HEIGHT_DP} each, clamped to [1, {@link #MAX_ROWS}].
     *
     * Pure dp arithmetic — no density conversion and no Android calls — so the sizing rules
     * are unit-testable, and a launcher reporting 0 (which happens) falls back to the 4x2
     * default rather than a value that overflows.
     */
    static int rowsForHeight(int heightDp) {
        if (heightDp <= 0) {
            return DEFAULT_ROWS;
        }
        int usable = heightDp - CHROME_HEIGHT_DP;
        if (usable <= 0) {
            return 1;
        }
        return Math.max(1, Math.min(MAX_ROWS, usable / ROW_HEIGHT_DP));
    }

    private static RemoteViews row(Context context, Lesson lesson, Lesson current, int week,
                                   long firstWeekDate, String sectionTimes, long now,
                                   boolean tomorrow, boolean moreBelow) {
        RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.widget_schedule_card);
        row.setViewVisibility(R.id.card_gap, moreBelow ? View.VISIBLE : View.GONE);
        long start = ScheduleMath.lessonStartAt(firstWeekDate, week, lesson.dayOfWeek,
                lesson.beginSection, sectionTimes);
        long end = ScheduleMath.lessonEndAt(firstWeekDate, week, lesson.dayOfWeek,
                lesson.beginSection, lesson.sections, sectionTimes);
        boolean isCurrent = !tomorrow && current != null && current.id != null
                && current.id.equals(lesson.id);
        boolean isPast = !tomorrow && end > 0L && now >= end;

        int[] colors = UiTheme.courseColors(context, lesson.name);
        row.setInt(R.id.card_strip, "setBackgroundColor", colors[1]);

        row.setTextViewText(R.id.card_time, (start > 0L ? DateText.clock(start) : "--:--")
                + "\n" + (end > 0L ? DateText.clock(end) : "--:--"));
        row.setTextColor(R.id.card_time, isCurrent
                ? DeadlineWidgetProvider.accent(context)
                : DeadlineWidgetProvider.mutedText(context, true));

        row.setTextViewText(R.id.card_name, lesson.name == null || lesson.name.isEmpty()
                ? "未命名课程" : lesson.name);
        row.setTextColor(R.id.card_name, DeadlineWidgetProvider.primaryText(context, true));
        // Finished lessons stay visible but recede; the day's shape still reads at a glance.
        float alpha = isPast && !isCurrent ? 0.55f : 1f;
        row.setFloat(R.id.card_name, "setAlpha", alpha);
        row.setFloat(R.id.card_meta, "setAlpha", alpha);

        row.setTextViewText(R.id.card_meta, placeLabel(context, lesson));
        row.setTextColor(R.id.card_meta, DeadlineWidgetProvider.mutedText(context, true));

        row.setViewVisibility(R.id.card_live, isCurrent ? View.VISIBLE : View.GONE);
        if (isCurrent) {
            row.setInt(R.id.card_live, "setBackgroundColor", DeadlineWidgetProvider.accent(context));
            row.setTextColor(R.id.card_live, Color.WHITE);
        }
        row.setOnClickPendingIntent(R.id.card_root, schedulePendingIntent(context));
        return row;
    }

    /** "room · teacher", "room", "teacher", or a placeholder — per the user's setting. */
    private static String placeLabel(Context context, Lesson lesson) {
        String place = lesson.location == null ? "" : lesson.location.trim();
        String teacher = AppSettings.widgetScheduleShowTeacher(context)
                ? (lesson.teacher == null ? "" : lesson.teacher.trim())
                : "";
        if (!place.isEmpty() && !teacher.isEmpty()) {
            return place + " · " + teacher;
        }
        if (!place.isEmpty()) {
            return place;
        }
        if (!teacher.isEmpty()) {
            return teacher;
        }
        return "地点未填写";
    }

    private static PendingIntent switchFacePendingIntent(Context context, int appWidgetId) {
        Intent intent = new Intent(context, DeadlineReceiver.class)
                .setAction(DeadlineReceiver.ACTION_SCHEDULE_WIDGET_FACE)
                .setPackage(context.getPackageName());
        intent.putExtra(DeadlineReceiver.EXTRA_APPWIDGET_ID, appWidgetId);
        BridgeAuth.attach(context, intent);
        // One PendingIntent per widget: extras are part of the identity, so a shared
        // request code would leak one instance's id into another.
        return PendingIntent.getBroadcast(context, "schedule_face".hashCode() + appWidgetId,
                intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent schedulePendingIntent(Context context) {
        Intent intent = new Intent(context, ScheduleActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, "schedule_open".hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static SharedPreferences statePrefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
