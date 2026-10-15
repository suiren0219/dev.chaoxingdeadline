package dev.chaoxingdeadline;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

import java.util.List;

/**
 * "今日课程" home-screen widget: today's timetable, one lesson per card.
 *
 * Data comes from the module's own lessons table (captured by the hook from Chaoxing's
 * timetable), so no extra Xposed scope or permission is needed. Cards live in a
 * {@link android.widget.ViewFlipper}: tapping a card flips to the next lesson, which is
 * a tap (not an inner scroll), so it stays within the HyperOS widget guideline.
 *
 * Why a flipper and not a StackView: StackView extends AdapterView, whose addView()
 * throws UnsupportedOperationException, so it cannot be filled with static RemoteViews
 * at all — only through a RemoteViewsService. ViewFlipper extends ViewAnimator
 * (a FrameLayout), so addView works and setDisplayedChild drives it remotely.
 */
public final class DeadlineScheduleWidget extends AppWidgetProvider {
    /** Per-widget page index, so two widget instances do not share a card. */
    private static final String PREFS = "schedule_widget_page";
    private static final String KEY_PAGE_PREFIX = "page_";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        updateAll(context);
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        if (appWidgetIds == null) {
            return;
        }
        SharedPreferences.Editor editor = pagePrefs(context).edit();
        for (int id : appWidgetIds) {
            editor.remove(KEY_PAGE_PREFIX + id);
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
                // Built per widget because each instance keeps its own card page.
                manager.updateAppWidget(id, build(context, id));
            }
        } catch (Throwable ignored) {
        }
    }

    /** Advances this widget's card by one, wrapping around; called from the receiver. */
    static void advancePage(Context context, int appWidgetId) {
        try {
            int count = todayLessons(context).size();
            if (count <= 1) {
                return;
            }
            SharedPreferences prefs = pagePrefs(context);
            String key = KEY_PAGE_PREFIX + appWidgetId;
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
        long now = System.currentTimeMillis();
        DeadlineStore store = new DeadlineStore(context);
        long firstWeekDate = store.scheduleFirstWeekDate();
        String sectionTimes = store.scheduleSectionTimes();
        int week = ScheduleMath.currentWeek(now, firstWeekDate);
        List<Lesson> today = todayLessons(context);
        Lesson current = ScheduleMath.currentLesson(today, now, firstWeekDate, sectionTimes);

        views.setTextViewText(R.id.s_header,
                week > 0 ? "第 " + week + " 周 · 今天 " + today.size() + " 节课"
                        : "课表未获取");
        views.setTextColor(R.id.s_header, DeadlineWidgetProvider.primaryText(context));

        long updatedAt = store.scheduleUpdatedAt();
        if (updatedAt > 0L) {
            views.setTextViewText(R.id.s_updated, "更新于 " + DateText.deadlineTime(updatedAt));
            views.setViewVisibility(R.id.s_updated, android.view.View.VISIBLE);
        } else {
            views.setViewVisibility(R.id.s_updated, android.view.View.GONE);
        }
        views.setTextColor(R.id.s_updated, DeadlineWidgetProvider.mutedText(context));

        views.removeAllViews(R.id.s_flipper);
        if (today.isEmpty()) {
            views.setViewVisibility(R.id.s_flipper, android.view.View.GONE);
            views.setViewVisibility(R.id.s_empty, android.view.View.VISIBLE);
            views.setTextColor(R.id.s_empty, DeadlineWidgetProvider.mutedText(context));
        } else {
            views.setViewVisibility(R.id.s_flipper, android.view.View.VISIBLE);
            views.setViewVisibility(R.id.s_empty, android.view.View.GONE);
            for (int i = 0; i < today.size(); i++) {
                views.addView(R.id.s_flipper, card(context, appWidgetId, today.get(i), current,
                        today.size(), i, now, firstWeekDate, sectionTimes));
            }
            // The stored page is clamped: a lesson removed since the last tap must not
            // leave the flipper pointing past the end.
            int page = pagePrefs(context).getInt(KEY_PAGE_PREFIX + appWidgetId, 0) % today.size();
            views.setInt(R.id.s_flipper, "setDisplayedChild", page);
        }

        views.setOnClickPendingIntent(R.id.widget_schedule_root, schedulePendingIntent(context));
        views.setTextColor(R.id.s_refresh, DeadlineWidgetProvider.mutedText(context));
        views.setOnClickPendingIntent(R.id.s_refresh, DeadlineWidgetProvider.refreshPendingIntent(context));
        return views;
    }

    private static RemoteViews card(Context context, int appWidgetId, Lesson lesson, Lesson current,
                                    int total, int index, long now, long firstWeekDate,
                                    String sectionTimes) {
        RemoteViews card = new RemoteViews(context.getPackageName(), R.layout.widget_schedule_card);
        int week = ScheduleMath.currentWeek(now, firstWeekDate);
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
        card.setTextColor(R.id.card_name, isPast
                ? DeadlineWidgetProvider.mutedText(context)
                : DeadlineWidgetProvider.primaryText(context));

        String range = (start > 0L ? DateText.clock(start) : "--:--")
                + "-" + (end > 0L ? DateText.clock(end) : "--:--");
        card.setTextViewText(R.id.card_time, range + " · "
                + ScheduleMath.formatSections(lesson.beginSection, lesson.sections));
        card.setTextColor(R.id.card_time, isCurrent
                ? DeadlineWidgetProvider.accent(context)
                : DeadlineWidgetProvider.mutedText(context));

        String label = lesson.label();
        card.setTextViewText(R.id.card_meta, label.isEmpty() ? "地点未填写" : label);
        card.setTextColor(R.id.card_meta, DeadlineWidgetProvider.mutedText(context));

        card.setTextViewText(R.id.card_index, (index + 1) + "/" + total);
        card.setTextColor(R.id.card_index, DeadlineWidgetProvider.mutedText(context));

        boolean showLive = isCurrent || isPast;
        card.setViewVisibility(R.id.card_live, showLive
                ? android.view.View.VISIBLE : android.view.View.GONE);
        if (showLive) {
            card.setTextViewText(R.id.card_live, isCurrent ? "上课中" : "已结束");
            card.setInt(R.id.card_live, "setBackgroundColor", isCurrent
                    ? DeadlineWidgetProvider.accent(context) : DeadlineWidgetProvider.mutedText(context));
            card.setTextColor(R.id.card_live, 0xFFFFFFFF);
        }
        // Tapping the card flips to the next lesson; with a single lesson there is
        // nothing to flip to, so the tap opens the schedule page instead.
        card.setOnClickPendingIntent(R.id.card_root, total > 1
                ? nextPagePendingIntent(context, appWidgetId)
                : schedulePendingIntent(context));
        return card;
    }

    /** Fires the page-advance broadcast for one widget instance. */
    private static PendingIntent nextPagePendingIntent(Context context, int appWidgetId) {
        Intent intent = new Intent(context, DeadlineReceiver.class)
                .setAction(DeadlineReceiver.ACTION_SCHEDULE_WIDGET_NEXT)
                .setPackage(context.getPackageName());
        intent.putExtra(DeadlineReceiver.EXTRA_APPWIDGET_ID, appWidgetId);
        BridgeAuth.attach(context, intent);
        // One PendingIntent per widget: extras are part of the identity, so a shared
        // request code would leak one instance's widget id into another.
        return PendingIntent.getBroadcast(context, "schedule_next".hashCode() + appWidgetId,
                intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent schedulePendingIntent(Context context) {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(context, ScheduleActivity.class));
        return PendingIntent.getActivity(context, "schedule_open".hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static SharedPreferences pagePrefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
