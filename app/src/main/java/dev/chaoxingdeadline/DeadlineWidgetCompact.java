package dev.chaoxingdeadline;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.widget.RemoteViews;

import java.util.List;

/**
 * 2x2 compact widget: pending count plus the nearest deadline.
 */
public final class DeadlineWidgetCompact extends DeadlineWidgetProvider {

    static RemoteViews build(Context context, List<DeadlineItem> items) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_compact);
        int pending = items.size();
        views.setTextViewText(R.id.w_count, String.valueOf(pending));
        views.setTextColor(R.id.w_count, pending > 0
                ? DeadlineWidgetProvider.accent(context) : DeadlineWidgetProvider.mutedText(context));
        if (items.isEmpty()) {
            views.setTextViewText(R.id.w_nearest_title, "暂无待办");
            views.setTextColor(R.id.w_nearest_title, DeadlineWidgetProvider.mutedText(context));
            views.setTextViewText(R.id.w_nearest_due, "打开学习通自动刷新");
            views.setTextColor(R.id.w_nearest_due, DeadlineWidgetProvider.mutedText(context));
        } else {
            DeadlineItem nearest = items.get(0);
            views.setTextViewText(R.id.w_nearest_title, nearest.title);
            views.setTextColor(R.id.w_nearest_title, DeadlineWidgetProvider.primaryText(context));
            views.setTextViewText(R.id.w_nearest_due, dueLine(nearest));
            views.setTextColor(R.id.w_nearest_due, urgent(nearest)
                    ? DeadlineWidgetProvider.dangerText(context) : DeadlineWidgetProvider.mutedText(context));
        }
        views.setOnClickPendingIntent(R.id.widget_compact_root, mainPendingIntent(context));
        return views;
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        DeadlineWidgetProvider.updateAll(context);
    }
}
