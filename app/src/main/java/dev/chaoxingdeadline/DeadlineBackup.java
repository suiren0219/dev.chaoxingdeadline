package dev.chaoxingdeadline;

import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Export / import helpers. The module owns no cloud storage and holds no INTERNET
 * permission, so backup is a plain file the user picks: JSON round-trips this module's
 * own data, ICS drops the same deadlines into any calendar app.
 */
public final class DeadlineBackup {
    public static final String FORMAT = "chaoxingdeadline-backup";
    public static final int VERSION = 1;
    private static final String PRODID = "-//dev.chaoxingdeadline//Deadline//CN";

    private DeadlineBackup() {
    }

    public static JSONObject exportJson(Context context) throws Exception {
        JSONObject root = new JSONObject();
        root.put("format", FORMAT);
        root.put("version", VERSION);
        root.put("exportedAt", System.currentTimeMillis());
        JSONArray items = new JSONArray();
        for (DeadlineItem item : new DeadlineStore(context).activeItems()) {
            items.put(item.toJson());
        }
        root.put("items", items);
        return root;
    }

    /** Number of items actually restored; -1 when the payload is not a backup file. */
    public static int importJson(Context context, String payload, boolean replaceExisting) {
        if (payload == null || payload.trim().isEmpty()) {
            return -1;
        }
        try {
            JSONObject root = new JSONObject(payload);
            if (!FORMAT.equals(root.optString("format"))) {
                return -1;
            }
            JSONArray items = root.optJSONArray("items");
            if (items == null) {
                return -1;
            }
            // Parse and validate the whole payload before touching the store, so a
            // malformed entry can never leave a replace-import with a cleared,
            // half-restored database.
            ArrayList<DeadlineItem> parsed = new ArrayList<>(items.length());
            for (int i = 0; i < items.length(); i++) {
                DeadlineItem item = DeadlineItem.fromJson(items.getJSONObject(i).toString());
                if (item.title == null || item.title.isEmpty() || item.dueAt <= 0L) {
                    continue;
                }
                if (!DeadlineStore.isSupportedType(item.type)) {
                    continue;
                }
                if (item.id == null || item.id.isEmpty()) {
                    item.id = item.stableId();
                }
                parsed.add(item);
            }
            DeadlineStore store = new DeadlineStore(context);
            if (replaceExisting) {
                store.clear();
            }
            int restored = 0;
            for (DeadlineItem item : parsed) {
                store.upsert(item);
                restored++;
            }
            DeadlineNotifier.rescheduleAll(context);
            OverlayBridge.publish(context);
            DeadlineWidgetProvider.updateAll(context);
            context.sendBroadcast(new Intent(DeadlineReceiver.ACTION_REFRESH)
                    .setPackage(context.getPackageName()));
            return restored;
        } catch (Throwable throwable) {
            return -1;
        }
    }

    /** RFC 5545 calendar with one VEVENT per deadline and a single advance alarm. */
    public static String exportIcs(Context context) {
        List<DeadlineItem> items = new DeadlineStore(context).activeItems();
        SimpleDateFormat stamp = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
        stamp.setTimeZone(TimeZone.getTimeZone("UTC"));
        StringBuilder builder = new StringBuilder();
        builder.append("BEGIN:VCALENDAR\r\n")
                .append("VERSION:2.0\r\n")
                .append("PRODID:").append(PRODID).append("\r\n")
                .append("CALSCALE:GREGORIAN\r\n")
                .append("METHOD:PUBLISH\r\n")
                .append("X-WR-CALNAME:").append(escapeText("学习通待办")).append("\r\n");
        int hours = AppSettings.notifyHours(context);
        for (DeadlineItem item : items) {
            if (item == null || item.dueAt <= 0L) {
                continue;
            }
            String start = stamp.format(new Date(item.dueAt));
            String end = stamp.format(new Date(item.dueAt + TimeUnit.MINUTES.toMillis(30)));
            String stampNow = stamp.format(new Date());
            builder.append("BEGIN:VEVENT\r\n")
                    .append("UID:").append(safe(item.id)).append("@chaoxingdeadline\r\n")
                    .append("DTSTAMP:").append(stampNow).append("\r\n")
                    .append("DTSTART:").append(start).append("\r\n")
                    .append("DTEND:").append(end).append("\r\n")
                    .append("SUMMARY:").append(escapeText("[" + safe(item.type) + "] " + safe(item.title)))
                    .append("\r\n")
                    .append("DESCRIPTION:").append(escapeText(describe(item))).append("\r\n")
                    .append("BEGIN:VALARM\r\n")
                    .append("ACTION:DISPLAY\r\n")
                    .append("TRIGGER:-PT").append(Math.max(1, hours)).append("H\r\n")
                    .append("DESCRIPTION:").append(escapeText(safe(item.title))).append("\r\n")
                    .append("END:VALARM\r\n")
                    .append("END:VEVENT\r\n");
        }
        builder.append("END:VCALENDAR\r\n");
        return builder.toString();
    }

    private static String describe(DeadlineItem item) {
        StringBuilder text = new StringBuilder();
        if (item.course != null && !item.course.isEmpty()) {
            text.append(item.course).append(" / ");
        }
        text.append(safe(item.type));
        if (item.url != null && item.url.startsWith("http")) {
            text.append("\n").append(item.url);
        }
        return text.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String escapeText(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r\n", "\n")
                .replace("\n", "\\n");
    }
}
