package dev.chaoxingdeadline;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.RemoteViews;

import java.util.ArrayList;
import java.util.List;

/**
 * Home screen widgets showing pending homework/exam deadlines.
 * Widgets run in the module's own process and read DeadlineStore directly,
 * so no extra Xposed scope or permission is required. Refresh happens on
 * module data changes (DeadlineReceiver), boot, 30-minute period fallback
 * and configuration (dark mode) changes.
 */
public abstract class DeadlineWidgetProvider extends AppWidgetProvider {
    static final long URGENT_MS = 12L * 60L * 60L * 1000L;
    private static final String CHAOXING_PACKAGE = "com.chaoxing.mobile";
    private static final int MAX_ROWS = 10;

    // Design tokens following the miuix (HyperOS-style) color scheme.
    private static final int ACCENT_LIGHT = 0xFF3482FF;
    private static final int ACCENT_DARK = 0xFF4788FF;
    private static final int TEXT_LIGHT = 0xE6000000;
    private static final int TEXT_DARK = 0xE6FFFFFF;
    private static final int MUTED_LIGHT = 0x99000000;
    private static final int MUTED_DARK = 0xFF787E96;
    /** Secondary text on the merged widget's translucent panel (white in both themes). */
    private static final int GLASS_MUTED = 0x99FFFFFF;
    private static final int DANGER_LIGHT = 0xFFE94634;
    private static final int DANGER_DARK = 0xFFF12522;

    static int accent(Context context) {
        return UiTheme.dark(context) ? ACCENT_DARK : ACCENT_LIGHT;
    }

    static int primaryText(Context context) {
        return primaryText(context, false);
    }

    /**
     * @param onGlass true when the text sits on the merged widget's translucent panel,
     *                which is dark in both themes (it is drawn over the wallpaper, with
     *                its own dark or light scrim), so its text is white in both.
     */
    static int primaryText(Context context, boolean onGlass) {
        if (onGlass) {
            return TEXT_DARK;
        }
        return UiTheme.dark(context) ? TEXT_DARK : TEXT_LIGHT;
    }

    static int mutedText(Context context) {
        return mutedText(context, false);
    }

    /** Muted text for the glass panel; see {@link #primaryText(Context, boolean)}. */
    static int mutedText(Context context, boolean onGlass) {
        if (onGlass) {
            return GLASS_MUTED;
        }
        return UiTheme.dark(context) ? MUTED_DARK : MUTED_LIGHT;
    }

    static int dangerText(Context context) {
        return UiTheme.dark(context) ? DANGER_DARK : DANGER_LIGHT;
    }

    /** Chapter task points use a neutral/teal accent so they read apart from homework. */
    static int chapterText(Context context) {
        return UiTheme.dark(context) ? 0xFF4FD1C5 : 0xFF0E9F8E;
    }

    /** Classroom activities use a violet accent. */
    static int activityText(Context context) {
        return UiTheme.dark(context) ? 0xFFA78BFA : 0xFF7C3AED;
    }

    /** Badge colour for a given deadline type. */
    static int typeColor(Context context, String type) {
        if ("\u8003\u8bd5".equals(type)) {
            return dangerText(context);
        }
        if ("\u7ae0\u8282".equals(type)) {
            return chapterText(context);
        }
        if ("\u8bfe\u5802\u6d3b\u52a8".equals(type)) {
            return activityText(context);
        }
        return accent(context);
    }

    /** Push fresh data into every widget instance of both sizes. */
    public static void updateAll(Context context) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            List<DeadlineItem> items = pendingItems(context);
            updateIds(context, manager, DeadlineWidgetCompact.class,
                    DeadlineWidgetCompact.build(context, items));
            // The merged widget carries the deadline face (and the timetable face), so it
            // is rendered here too: one data change repaints every widget.
            DeadlineScheduleWidget.updateAll(context);
        } catch (Throwable ignored) {
        }
    }

    private static void updateIds(Context context, AppWidgetManager manager,
                                  Class<? extends DeadlineWidgetProvider> provider, RemoteViews views) {
        try {
            int[] ids = manager.getAppWidgetIds(new ComponentName(context, provider));
            if (ids == null || ids.length == 0) {
                return;
            }
            manager.updateAppWidget(ids, views);
        } catch (Throwable ignored) {
        }
    }

    /** Test hook: render the 2x2 card through its real path, with no widgets registered. */
    static RemoteViews buildForTest(Context context) {
        return DeadlineWidgetCompact.build(context, pendingItems(context));
    }

    /** Pending items: not submitted, not due yet, not blocked, inside the widget window. */
    static List<DeadlineItem> pendingItems(Context context) {
        ArrayList<DeadlineItem> pending = new ArrayList<>();
        try {
            long now = System.currentTimeMillis();
            int windowHours = AppSettings.widgetWindowHours(context);
            long horizon = windowHours == AppSettings.OVERLAY_WINDOW_ALL
                    ? Long.MAX_VALUE
                    : now + windowHours * 60L * 60L * 1000L;
            List<DeadlineItem> items = new DeadlineStore(context).activeItems();
            String courseFilter = AppSettings.widgetCourseFilter(context);
            for (DeadlineItem item : items) {
                if (item == null || item.submitted || item.dueAt <= now || item.dueAt > horizon
                        || item.vanishedAt > 0L) {
                    continue;
                }
                if (!passesCourseFilter(item, courseFilter)) {
                    continue;
                }
                pending.add(item);
            }
        } catch (Throwable ignored) {
        }
        return pending;
    }

    /**
     * Widget course filter. An empty filter keeps everything; a non-empty one matches the
     * course name exactly. Items with no course name drop out of a filtered widget — they
     * are the "which course was this?" leftovers, not something the user picked.
     */
    static boolean passesCourseFilter(DeadlineItem item, String filter) {
        if (filter == null || filter.isEmpty()) {
            return true;
        }
        String course = item.course == null ? "" : item.course;
        return filter.equals(course);
    }

    /** Manual refresh button: asks the module process to re-render every widget. */
    static PendingIntent refreshPendingIntent(Context context) {
        Intent intent = new Intent(context, DeadlineReceiver.class)
                .setAction(DeadlineReceiver.ACTION_WIDGET_REFRESH)
                .setPackage(context.getPackageName());
        BridgeAuth.attach(context, intent);
        return PendingIntent.getBroadcast(
                context,
                "widget_refresh".hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * "Fetch timetable" action offered by the widget's empty state, so a user who has never
     * opened the app can pull a timetable straight from the home screen. Goes through the
     * module app because only it can address the Chaoxing host (see RefreshBridge).
     */
    static PendingIntent fetchSemesterPendingIntent(Context context) {
        Intent intent = new Intent(context, DeadlineReceiver.class)
                .setAction(DeadlineReceiver.ACTION_WIDGET_FETCH_TIMETABLE)
                .setPackage(context.getPackageName());
        BridgeAuth.attach(context, intent);
        return PendingIntent.getBroadcast(
                context,
                "widget_fetch_timetable".hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static PendingIntent mainPendingIntent(Context context) {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(context, MainActivity.class));
        return PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Open the stored Chaoxing page for an item when the host app can handle it. */
    static PendingIntent itemPendingIntent(Context context, DeadlineItem item, int requestCode) {
        Intent intent = null;
        if (item.url != null && item.url.startsWith("http")) {
            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(item.url));
            view.setPackage(CHAOXING_PACKAGE);
            try {
                if (context.getPackageManager().resolveActivity(view, 0) != null) {
                    intent = view;
                }
            } catch (Throwable ignored) {
            }
        }
        if (intent == null) {
            return mainPendingIntent(context);
        }
        return PendingIntent.getActivity(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static boolean urgent(DeadlineItem item) {
        return item.dueAt - System.currentTimeMillis() <= URGENT_MS;
    }


    /**
     * One deadline row: badge, title, course and remaining time.
     *
     * @param onGlass the row sits on the merged widget's translucent panel, which is dark
     *                in both themes, so its text is white in both. Rows drawn on a
     *                theme-following background keep the theme-dependent colours.
     */
    static RemoteViews deadlineRow(Context context, DeadlineItem item, int index, boolean onGlass) {
        RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.widget_row);
        row.setTextViewText(R.id.r_badge, item.type);
        row.setTextColor(R.id.r_badge, typeColor(context, item.type));

        String title = item.title == null || item.title.isEmpty() ? "未命名" : item.title;
        row.setTextViewText(R.id.r_title, title);
        row.setTextColor(R.id.r_title, primaryText(context, onGlass));

        String course = AppSettings.widgetShowCourse(context)
                ? (item.course == null ? "" : item.course.trim())
                : "";
        if (course.isEmpty()) {
            row.setViewVisibility(R.id.r_course, android.view.View.GONE);
        } else {
            row.setViewVisibility(R.id.r_course, android.view.View.VISIBLE);
            row.setTextViewText(R.id.r_course, course);
            row.setTextColor(R.id.r_course, mutedText(context, onGlass));
        }

        row.setTextViewText(R.id.r_due, dueLine(item));
        row.setTextColor(R.id.r_due, urgent(item)
                ? dangerText(context) : mutedText(context, onGlass));
        row.setOnClickPendingIntent(R.id.row_root, itemPendingIntent(context, item, 1000 + index));
        return row;
    }

    /** Number of deadline rows a widget face shows before it reads as a wall of text. */
    static int todoRowLimit() {
        return 4;
    }

    /** Two-line right column: absolute deadline over remaining time. */
    static String dueLine(DeadlineItem item) {
        String time = DateText.deadlineTime(item.dueAt);
        long hours = Math.max(1L, (item.dueAt - System.currentTimeMillis()) / (60L * 60L * 1000L));
        long days = hours / 24L;
        return time + "\n剩 " + (days > 0 ? days + "天" + (hours % 24) + "小时" : hours + "小时");
    }

    static int maxRows() {
        return MAX_ROWS;
    }
}
