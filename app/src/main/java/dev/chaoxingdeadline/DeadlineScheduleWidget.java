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

import java.util.ArrayList;
import java.util.List;

/**
 * The home-screen widget: one panel with two faces — today's timetable and the deadline
 * list — switched by the ⇄ button in the header.
 *
 * Why one widget with a flipper rather than two separate widgets: seeing both without
 * losing half the screen is the point, and the user picks which matters right now. This
 * replaces the separate timetable and deadline-list widgets, so the home screen has one
 * panel showing the same data instead of two competing ones.
 *
 * {@link android.widget.ViewFlipper} is the容器 for the faces because it extends
 * ViewAnimator (a FrameLayout), so static RemoteViews fill it and setDisplayedChild drives
 * it remotely. StackView/ListView extend AdapterView, whose addView throws
 * UnsupportedOperationException — they need a RemoteViewsService, which would mean an
 * extra bound service and a blank widget whenever the launcher kills the process.
 *
 * Each widget instance remembers its own face and card page, so two widgets on the same
 * home screen do not fight over the display.
 */
public final class DeadlineScheduleWidget extends AppWidgetProvider {
    /** Per-widget UI state: which face is showing and which lesson card. */
    private static final String PREFS = "schedule_widget_state";
    private static final String KEY_FACE = "face_";
    private static final String KEY_PAGE = "page_";

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
            editor.remove(KEY_PAGE + id);
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
                // Built per widget: each instance keeps its own face and card.
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

    /** Advances the timetable face's card by one, wrapping around. */
    static void advancePage(Context context, int appWidgetId) {
        try {
            int count = todayLessons(context).size();
            if (count <= 1) {
                return;
            }
            SharedPreferences prefs = statePrefs(context);
            String key = KEY_PAGE + appWidgetId;
            prefs.edit().putInt(key, (prefs.getInt(key, 0) + 1) % count).apply();
            updateAll(context);
        } catch (Throwable ignored) {
        }
    }

    private static List<Lesson> todayLessons(Context context) {
        DeadlineStore store = new DeadlineStore(context);
        return ScheduleMath.todayLessons(store.allLessons(), System.currentTimeMillis(),
                store.scheduleFirstWeekDate());
    }

    private static RemoteViews build(Context context, int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_schedule);
        SharedPreferences prefs = statePrefs(context);
        boolean timetableFace = prefs.getInt(KEY_FACE + appWidgetId, FACE_TIMETABLE) == FACE_TIMETABLE;

        long now = System.currentTimeMillis();
        DeadlineStore store = new DeadlineStore(context);
        long firstWeekDate = store.scheduleFirstWeekDate();
        String sectionTimes = store.scheduleSectionTimes();
        int week = ScheduleMath.currentWeek(now, firstWeekDate);
        List<Lesson> today = todayLessons(context);
        Lesson current = ScheduleMath.currentLesson(today, now, firstWeekDate, sectionTimes);
        List<DeadlineItem> pending = DeadlineWidgetProvider.pendingItems(context);

        views.setTextViewText(R.id.s_updated, "更新于 " + DateText.deadlineTime(now));
        views.setViewVisibility(R.id.s_updated, View.VISIBLE);
        views.setTextColor(R.id.s_header, DeadlineWidgetProvider.primaryText(context, true));
        views.setTextColor(R.id.s_updated, DeadlineWidgetProvider.mutedText(context, true));
        views.setTextColor(R.id.s_switch, DeadlineWidgetProvider.mutedText(context, true));
        views.setTextColor(R.id.s_refresh, DeadlineWidgetProvider.mutedText(context, true));
        views.setTextColor(R.id.s_empty_text, DeadlineWidgetProvider.mutedText(context, true));
        views.setInt(R.id.s_empty_action, "setBackgroundColor",
                DeadlineWidgetProvider.accent(context));

        views.removeAllViews(R.id.s_flipper);
        if (timetableFace) {
            views.setTextViewText(R.id.s_header, week > 0
                    ? "第 " + week + " 周 · 今天 " + today.size() + " 节课" : "课表未获取");
            buildTimetableFace(context, views, appWidgetId, today, current, week,
                    firstWeekDate, sectionTimes, now, prefs);
        } else {
            int count = pending.size();
            views.setTextViewText(R.id.s_header, "Deadline 待办");
            views.setTextViewText(R.id.s_updated, count > 0
                    ? "共 " + count + " 项 · 更新于 " + DateText.deadlineTime(now)
                    : "更新于 " + DateText.deadlineTime(now));
            buildTodoFace(context, views, pending);
        }

        // The dot strip only means something on the timetable face.
        views.setViewVisibility(R.id.s_dots, timetableFace ? View.VISIBLE : View.GONE);

        views.setOnClickPendingIntent(R.id.s_switch, switchFacePendingIntent(context, appWidgetId));
        views.setOnClickPendingIntent(R.id.s_refresh,
                DeadlineWidgetProvider.refreshPendingIntent(context));
        return views;
    }

    private static void buildTimetableFace(Context context, RemoteViews views, int appWidgetId,
                                           List<Lesson> today, Lesson current, int week,
                                           long firstWeekDate, String sectionTimes, long now,
                                           SharedPreferences prefs) {
        views.setViewVisibility(R.id.s_todo_empty, View.GONE);
        if (today.isEmpty()) {
            views.setViewVisibility(R.id.s_flipper, View.GONE);
            views.setViewVisibility(R.id.s_empty, View.VISIBLE);
            views.setViewVisibility(R.id.s_dots, View.GONE);
            boolean everFetched = new DeadlineStore(context).scheduleUpdatedAt() > 0L;
            views.setTextViewText(R.id.s_empty_text, everFetched
                    ? "今天没有课" : "还没有课表数据\n打开学习通课表页面即可自动获取");
            // The action is a fetch request, so only offer it when there is nothing yet.
            views.setViewVisibility(R.id.s_empty_action, everFetched ? View.GONE : View.VISIBLE);
            views.setOnClickPendingIntent(R.id.s_empty_action,
                    DeadlineWidgetProvider.fetchSemesterPendingIntent(context));
            return;
        }
        views.setViewVisibility(R.id.s_empty, View.GONE);
        views.setViewVisibility(R.id.s_flipper, View.VISIBLE);

        // In-session lesson first (so the widget answers "what now?"), then the rest of the
        // day in order — the reference app's ordering, and the useful one at a glance.
        List<Lesson> ordered = new ArrayList<>(today);
        if (current != null) {
            for (int i = 0; i < ordered.size(); i++) {
                if (current.id != null && current.id.equals(ordered.get(i).id)) {
                    ordered.add(0, ordered.remove(i));
                    break;
                }
            }
        }
        for (int i = 0; i < ordered.size(); i++) {
            views.addView(R.id.s_flipper, card(context, appWidgetId, ordered.get(i), current,
                    ordered.size(), i, week, firstWeekDate, sectionTimes, now));
        }
        fillDots(context, views, ordered, current, now, firstWeekDate, week, sectionTimes);
        // Clamped: a lesson removed since the last tap must not point past the end.
        int page = prefs.getInt(KEY_PAGE + appWidgetId, 0) % today.size();
        views.setInt(R.id.s_flipper, "setDisplayedChild", page);
    }

    private static void buildTodoFace(Context context, RemoteViews views,
                                      List<DeadlineItem> pending) {
        views.setViewVisibility(R.id.s_empty, View.GONE);
        if (pending.isEmpty()) {
            views.setViewVisibility(R.id.s_flipper, View.GONE);
            views.setViewVisibility(R.id.s_todo_empty, View.VISIBLE);
            return;
        }
        views.setViewVisibility(R.id.s_todo_empty, View.GONE);
        views.setViewVisibility(R.id.s_flipper, View.VISIBLE);
        int shown = Math.min(pending.size(), DeadlineWidgetProvider.todoRowLimit());
        for (int i = 0; i < shown; i++) {
            views.addView(R.id.s_flipper, DeadlineWidgetProvider.deadlineRow(context, pending.get(i), i, true));
        }
        views.setInt(R.id.s_flipper, "setDisplayedChild", 0);
    }

    private static RemoteViews card(Context context, int appWidgetId, Lesson lesson, Lesson current,
                                    int total, int index, int week, long firstWeekDate,
                                    String sectionTimes, long now) {
        RemoteViews card = new RemoteViews(context.getPackageName(), R.layout.widget_schedule_card);
        long start = ScheduleMath.lessonStartAt(firstWeekDate, week, lesson.dayOfWeek,
                lesson.beginSection, sectionTimes);
        long end = ScheduleMath.lessonEndAt(firstWeekDate, week, lesson.dayOfWeek,
                lesson.beginSection, lesson.sections, sectionTimes);
        boolean isCurrent = current != null && current.id != null && current.id.equals(lesson.id);
        boolean isPast = end > 0L && now >= end;

        int[] colors = UiTheme.courseColors(context, lesson.name);
        card.setInt(R.id.card_strip, "setBackgroundColor", colors[1]);

        card.setTextViewText(R.id.card_name, lesson.name == null || lesson.name.isEmpty()
                ? "未命名课程" : lesson.name);
        card.setTextColor(R.id.card_name, DeadlineWidgetProvider.primaryText(context, true));
        // Dim finished lessons without hiding them; the day's shape still reads at a glance.
        card.setFloat(R.id.card_name, "setAlpha", isPast && !isCurrent ? 0.55f : 1f);
        card.setFloat(R.id.card_meta, "setAlpha", isPast && !isCurrent ? 0.55f : 1f);

        String range = (start > 0L ? DateText.clock(start) : "--:--")
                + "-" + (end > 0L ? DateText.clock(end) : "--:--");
        card.setTextViewText(R.id.card_time, range);
        card.setTextColor(R.id.card_time, isCurrent
                ? DeadlineWidgetProvider.accent(context)
                : DeadlineWidgetProvider.mutedText(context, true));

        String label = lesson.label();
        card.setTextViewText(R.id.card_meta, label.isEmpty() ? "地点未填写" : label);
        card.setTextColor(R.id.card_meta, DeadlineWidgetProvider.mutedText(context, true));
        card.setTextViewText(R.id.card_index, (index + 1) + "/" + total);
        card.setTextColor(R.id.card_index, DeadlineWidgetProvider.mutedText(context, true));

        card.setViewVisibility(R.id.card_live, isCurrent ? View.VISIBLE : View.GONE);
        if (isCurrent) {
            card.setInt(R.id.card_live, "setBackgroundColor", DeadlineWidgetProvider.accent(context));
            card.setTextColor(R.id.card_live, Color.WHITE);
        }
        // Tapping the card advances to the next lesson; with a single lesson there is
        // nothing to advance to, so it opens the app instead.
        card.setOnClickPendingIntent(R.id.card_root, total > 1
                ? lessonPagePendingIntent(context, appWidgetId)
                : DeadlineWidgetProvider.mainPendingIntent(context));
        return card;
    }

    /**
     * Colours the section-dot strip: one dot per lesson of the day, in course colours,
     * with the one in session bright and the finished ones dimmed. The trailing text
     * counts what is still ahead, so the widget answers "how much of my day is left?"
     * without opening anything — the pattern the reference app uses.
     */
    private static void fillDots(Context context, RemoteViews views, List<Lesson> ordered,
                                 Lesson current, long now, long firstWeekDate, int week,
                                 String sectionTimes) {
        int[] dots = {R.id.s_dot1, R.id.s_dot2, R.id.s_dot3, R.id.s_dot4,
                R.id.s_dot5, R.id.s_dot6, R.id.s_dot7, R.id.s_dot8};
        int finished = 0;
        int remaining = 0;
        for (int i = 0; i < dots.length; i++) {
            if (i >= ordered.size()) {
                views.setViewVisibility(dots[i], View.GONE);
                continue;
            }
            Lesson lesson = ordered.get(i);
            boolean isCurrent = current != null && current.id != null
                    && current.id.equals(lesson.id);
            long end = ScheduleMath.lessonEndAt(firstWeekDate, week, lesson.dayOfWeek,
                    lesson.beginSection, lesson.sections, sectionTimes);
            boolean isPast = end > 0L && now >= end;
            if (isPast) {
                finished++;
            } else if (!isCurrent) {
                remaining++;
            }
            int[] colors = UiTheme.courseColors(context, lesson.name);
            views.setViewVisibility(dots[i], View.VISIBLE);
            views.setInt(dots[i], "setBackgroundColor",
                    isPast ? DeadlineWidgetProvider.mutedText(context, true) : colors[1]);
            views.setFloat(dots[i], "setAlpha", isCurrent ? 1f : (isPast ? 0.45f : 0.8f));
        }
        // While a class is in session the wording points at the current one.
        String label;
        if (current != null) {
            label = remaining > 0 ? "本节之后还有 " + remaining + " 节" : "今天是最后一节";
        } else if (remaining > 0) {
            label = "还有 " + remaining + " 节课";
        } else if (finished > 0) {
            label = "今天的课已上完";
        } else {
            label = "";
        }
        views.setTextViewText(R.id.s_remaining, label);
        views.setTextColor(R.id.s_remaining, DeadlineWidgetProvider.mutedText(context, true));
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

    private static PendingIntent lessonPagePendingIntent(Context context, int appWidgetId) {
        Intent intent = new Intent(context, DeadlineReceiver.class)
                .setAction(DeadlineReceiver.ACTION_SCHEDULE_WIDGET_NEXT)
                .setPackage(context.getPackageName());
        intent.putExtra(DeadlineReceiver.EXTRA_APPWIDGET_ID, appWidgetId);
        BridgeAuth.attach(context, intent);
        return PendingIntent.getBroadcast(context, "schedule_next".hashCode() + appWidgetId,
                intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static SharedPreferences statePrefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
