package dev.chaoxingdeadline;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.widget.RemoteViews;

import java.util.List;

/**
 * 4x2 (resizable) list widget: up to six pending deadlines with badge,
 * title, course and remaining time. Static rows, no inner scrolling —
 * per the HyperOS widget guideline.
 */
public final class DeadlineWidgetList extends DeadlineWidgetProvider {

    static RemoteViews build(Context context, List<DeadlineItem> items) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_list);
        views.setTextViewText(R.id.w_count, "共 " + items.size() + " 项");
        views.setTextColor(R.id.w_count, DeadlineWidgetProvider.mutedText(context));
        views.setTextColor(R.id.w_header_title, DeadlineWidgetProvider.primaryText(context));

        long updatedAt = 0L;
        try {
            if (App.getService() != null) {
                updatedAt = App.getService().getRemotePreferences(OverlayBridge.PREFS)
                        .getLong(OverlayBridge.KEY_UPDATED_AT, 0L);
            }
        } catch (Throwable ignored) {
        }
        if (updatedAt > 0L) {
            views.setTextViewText(R.id.w_updated, "更新于 " + DateText.deadlineTime(updatedAt));
            views.setTextColor(R.id.w_updated, DeadlineWidgetProvider.mutedText(context));
            views.setViewVisibility(R.id.w_updated, android.view.View.VISIBLE);
        } else {
            views.setViewVisibility(R.id.w_updated, android.view.View.GONE);
        }

        views.removeAllViews(R.id.w_rows);
        int shown = Math.min(items.size(), maxRows());
        for (int i = 0; i < shown; i++) {
            views.addView(R.id.w_rows, row(context, items.get(i), i));
        }
        views.setViewVisibility(R.id.w_rows, shown > 0 ? android.view.View.VISIBLE : android.view.View.GONE);
        views.setViewVisibility(R.id.w_empty, shown > 0 ? android.view.View.GONE : android.view.View.VISIBLE);
        views.setOnClickPendingIntent(R.id.widget_list_root, mainPendingIntent(context));
        views.setTextColor(R.id.w_refresh, DeadlineWidgetProvider.mutedText(context));
        views.setOnClickPendingIntent(R.id.w_refresh, refreshPendingIntent(context));
        return views;
    }

    private static RemoteViews row(Context context, DeadlineItem item, int index) {
        RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.widget_row);
        row.setTextViewText(R.id.r_badge, item.type);
        row.setTextColor(R.id.r_badge, DeadlineWidgetProvider.typeColor(context, item.type));

        String title = item.title == null || item.title.isEmpty() ? "未命名" : item.title;
        row.setTextViewText(R.id.r_title, title);
        row.setTextColor(R.id.r_title, DeadlineWidgetProvider.primaryText(context));

        String course = AppSettings.widgetShowCourse(context)
                ? (item.course == null ? "" : item.course.trim())
                : "";
        if (course.isEmpty()) {
            row.setViewVisibility(R.id.r_course, android.view.View.GONE);
        } else {
            row.setViewVisibility(R.id.r_course, android.view.View.VISIBLE);
            row.setTextViewText(R.id.r_course, course);
            row.setTextColor(R.id.r_course, DeadlineWidgetProvider.mutedText(context));
        }

        row.setTextViewText(R.id.r_due, dueLine(item));
        row.setTextColor(R.id.r_due, urgent(item)
                ? DeadlineWidgetProvider.dangerText(context) : DeadlineWidgetProvider.mutedText(context));
        row.setOnClickPendingIntent(R.id.row_root, itemPendingIntent(context, item, 1000 + index));
        return row;
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        DeadlineWidgetProvider.updateAll(context);
    }
}
