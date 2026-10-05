package dev.chaoxingdeadline;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.service.XposedService;

public final class AppSettings {
    public static final String PREFS = "app_settings";
    public static final String WIDGET_PREFS = "widget_settings";
    public static final String LAUNCHER_ALIAS = "dev.chaoxingdeadline.LauncherActivity";
    public static final int OVERLAY_WINDOW_ALL = -1;
    private static final int DEFAULT_OVERLAY_WINDOW_HOURS = 24;
    private static final int[] OVERLAY_WINDOW_OPTIONS = {3, 8, 12, 24, 72, OVERLAY_WINDOW_ALL};

    /** Quiet-hours default: 23:00 -> 07:00 next day, expressed as minutes from midnight. */
    public static final int DEFAULT_QUIET_START = 23 * 60;
    public static final int DEFAULT_QUIET_END = 7 * 60;
    private static final int MINUTES_PER_DAY = 24 * 60;

    /**
     * Setting keys the hook may push from the Chaoxing process. Anything not listed here is
     * ignored, so unrelated broadcast extras can never overwrite local settings.
     */
    private static final Set<String> REMOTE_SETTING_KEYS = new HashSet<>(Arrays.asList(
            "overlay_enabled", "overlay_window_hours", "dark_mode_force",
            "notify_enabled", "notify_homework", "notify_exam", "notify_chapter",
            "notify_at_due", "notify_hours",
            "quiet_hours_enabled", "quiet_start", "quiet_end",
            "auto_delete_expired", "launcher_hidden"));

    private AppSettings() {
    }

    public static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static SharedPreferences widgetPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE);
    }

    public static boolean finalReminderEnabled(Context context) {
        return prefs(context).getBoolean("notify_enabled", true);
    }

    public static boolean notifyHomework(Context context) {
        return prefs(context).getBoolean("notify_homework", true);
    }

    public static boolean notifyExam(Context context) {
        return prefs(context).getBoolean("notify_exam", true);
    }

    /** Chapter task points are opt-in: they can be numerous and noisy when enabled. */
    public static boolean notifyChapter(Context context) {
        return prefs(context).getBoolean("notify_chapter", false);
    }

    /** Remind at the exact deadline in addition to the advance offsets. */
    public static boolean notifyAtDue(Context context) {
        return prefs(context).getBoolean("notify_at_due", true);
    }

    /** Final reminders get their own high-importance notification channel. */
    public static boolean urgentChannelEnabled(Context context) {
        return prefs(context).getBoolean("urgent_channel", true);
    }

    public static int notifyHours(Context context) {
        return Math.max(1, Math.min(168, prefs(context).getInt("notify_hours", 24)));
    }

    public static long[] notifyOffsetsMillis(Context context) {
        long main = TimeUnit.HOURS.toMillis(notifyHours(context));
        LinkedHashSet<Long> offsets = new LinkedHashSet<>();
        offsets.add(main);
        if (finalReminderEnabled(context)) {
            long threeHours = TimeUnit.HOURS.toMillis(3);
            long thirtyMinutes = TimeUnit.MINUTES.toMillis(30);
            if (main > threeHours) {
                offsets.add(threeHours);
            }
            if (main > thirtyMinutes) {
                offsets.add(thirtyMinutes);
            }
        }
        if (notifyAtDue(context)) {
            offsets.add(0L);
        }
        long[] result = new long[offsets.size()];
        int index = 0;
        for (Long offset : offsets) {
            // 0L (the "due right now" reminder) is a valid offset and must be kept.
            if (offset != null && offset >= 0L) {
                result[index++] = offset;
            }
        }
        if (index == result.length) {
            return result;
        }
        long[] compact = new long[index];
        System.arraycopy(result, 0, compact, 0, index);
        return compact;
    }

    public static boolean overlayEnabled(Context context) {
        return prefs(context).getBoolean("overlay_enabled", true);
    }

    public static void setOverlayEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("overlay_enabled", enabled).apply();
        syncRemotePreferences(context);
    }

    public static int overlayWindowHours(Context context) {
        int value = prefs(context).getInt("overlay_window_hours", DEFAULT_OVERLAY_WINDOW_HOURS);
        return isOverlayWindowOption(value) ? value : DEFAULT_OVERLAY_WINDOW_HOURS;
    }

    public static void setOverlayWindowHours(Context context, int hours) {
        int value = isOverlayWindowOption(hours) ? hours : DEFAULT_OVERLAY_WINDOW_HOURS;
        prefs(context).edit().putInt("overlay_window_hours", value).apply();
        syncRemotePreferences(context);
    }

    public static boolean darkModeForceEnabled(Context context) {
        return prefs(context).getBoolean("dark_mode_force", true);
    }

    public static void setDarkModeForceEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("dark_mode_force", enabled).apply();
        syncRemotePreferences(context);
    }

    // -- quiet hours --

    public static boolean quietHoursEnabled(Context context) {
        return prefs(context).getBoolean("quiet_hours_enabled", false);
    }

    public static void setQuietHoursEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("quiet_hours_enabled", enabled).apply();
        syncRemotePreferences(context);
    }

    /** Quiet window start, in minutes from midnight (0 - 1439). */
    public static int quietStartMinute(Context context) {
        return normalizeMinute(prefs(context).getInt("quiet_start", DEFAULT_QUIET_START));
    }

    /** Quiet window end, in minutes from midnight (0 - 1439). */
    public static int quietEndMinute(Context context) {
        return normalizeMinute(prefs(context).getInt("quiet_end", DEFAULT_QUIET_END));
    }

    /** Whether reminders landing inside the quiet window are pushed to its end. */
    public static boolean quietDeferEnabled(Context context) {
        return prefs(context).getBoolean("quiet_defer", true);
    }

    public static void setQuietHours(Context context, int startMinute, int endMinute) {
        prefs(context).edit()
                .putInt("quiet_start", normalizeMinute(startMinute))
                .putInt("quiet_end", normalizeMinute(endMinute))
                .apply();
        syncRemotePreferences(context);
    }

    /** Whether a same-day window is meant (e.g. 09:00-17:00) rather than spanning midnight. */
    public static boolean quietSpansMidnight(Context context) {
        return quietStartMinute(context) > quietEndMinute(context);
    }

    public static boolean inQuietWindow(Context context, int minuteOfDay) {
        int start = quietStartMinute(context);
        int end = quietEndMinute(context);
        if (start == end) {
            return false;
        }
        if (start < end) {
            return minuteOfDay >= start && minuteOfDay < end;
        }
        return minuteOfDay >= start || minuteOfDay < end;
    }

    public static boolean inQuietWindow(Context context, long millis) {
        return inQuietWindow(context, minuteOfDay(millis));
    }

    public static int minuteOfDay(long millis) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(millis);
        return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE);
    }

    /**
     * Push a trigger time out of the quiet window: reminders scheduled inside it fire at
     * the moment the window ends instead of being dropped.
     */
    public static long shiftOutOfQuietHours(Context context, long triggerAt) {
        if (!quietHoursEnabled(context) || !quietDeferEnabled(context)
                || !inQuietWindow(context, triggerAt)) {
            return triggerAt;
        }
        int end = quietEndMinute(context);
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(triggerAt);
        calendar.set(Calendar.HOUR_OF_DAY, end / 60);
        calendar.set(Calendar.MINUTE, end % 60);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        long shifted = calendar.getTimeInMillis();
        if (shifted <= triggerAt) {
            // The window end already passed today, so it belongs to tomorrow.
            shifted += TimeUnit.DAYS.toMillis(1);
        }
        return shifted;
    }

    // -- widget configuration --

    public static boolean widgetShowCourse(Context context) {
        return widgetPrefs(context).getBoolean("show_course", true);
    }

    public static void setWidgetShowCourse(Context context, boolean value) {
        widgetPrefs(context).edit().putBoolean("show_course", value).apply();
    }

    public static int widgetWindowHours(Context context) {
        int value = widgetPrefs(context).getInt("window_hours", OVERLAY_WINDOW_ALL);
        return isOverlayWindowOption(value) ? value : OVERLAY_WINDOW_ALL;
    }

    public static void setWidgetWindowHours(Context context, int hours) {
        int value = isOverlayWindowOption(hours) ? hours : OVERLAY_WINDOW_ALL;
        widgetPrefs(context).edit().putInt("window_hours", value).apply();
    }

    // -- misc --

    public static int[] overlayWindowOptions() {
        return OVERLAY_WINDOW_OPTIONS.clone();
    }

    public static String overlayWindowLabel(Context context) {
        return overlayWindowLabel(overlayWindowHours(context));
    }

    public static String overlayWindowLabel(int hours) {
        if (hours == OVERLAY_WINDOW_ALL) {
            return "所有未完成待办";
        }
        if (hours == 72) {
            return "3天内待办";
        }
        return hours + "小时内待办";
    }

    private static boolean isOverlayWindowOption(int value) {
        for (int option : OVERLAY_WINDOW_OPTIONS) {
            if (option == value) {
                return true;
            }
        }
        return false;
    }

    private static int normalizeMinute(int minute) {
        int value = minute % MINUTES_PER_DAY;
        if (value < 0) {
            value += MINUTES_PER_DAY;
        }
        return value;
    }

    /**
     * Mirror every local setting into the remote preferences the hook reads inside the
     * Chaoxing process. Iterating all keys keeps new settings in sync automatically —
     * the previous hard-coded list silently skipped anything added later.
     */
    public static void syncRemotePreferences(Context context) {
        try {
            XposedService service = App.getService();
            if (service == null) {
                return;
            }
            SharedPreferences.Editor editor = service.getRemotePreferences(PREFS).edit();
            editor.clear();
            for (Map.Entry<String, ?> entry : prefs(context).getAll().entrySet()) {
                putValue(editor, entry.getKey(), entry.getValue());
            }
            editor.apply();
        } catch (Throwable throwable) {
            // Traversal-based sync failing silently used to hide a dead bridge for days.
            Log.w("ChaoxingDeadline", "sync remote prefs failed: " + throwable.getClass().getSimpleName());
        }
    }

    /** Writes every recognised setting carried by a remote update broadcast. */
    public static void applyRemoteUpdate(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        Bundle extras = intent.getExtras();
        if (extras == null || extras.isEmpty()) {
            return;
        }
        SharedPreferences.Editor editor = prefs(context).edit();
        boolean changed = false;
        for (String key : extras.keySet()) {
            if (key == null || !REMOTE_SETTING_KEYS.contains(key)) {
                continue;
            }
            Object value = extras.get(key);
            if (value instanceof Boolean) {
                editor.putBoolean(key, (Boolean) value);
                changed = true;
            } else if (value instanceof Integer) {
                editor.putInt(key, (Integer) value);
                changed = true;
            } else if (value instanceof Long) {
                editor.putLong(key, (Long) value);
                changed = true;
            } else if (value instanceof String) {
                editor.putString(key, (String) value);
                changed = true;
            }
        }
        if (changed) {
            editor.apply();
        }
    }

    @SuppressWarnings("unchecked")
    private static void putValue(SharedPreferences.Editor editor, String key, Object value) {
        if (key == null || value == null) {
            return;
        }
        if (value instanceof Boolean) {
            editor.putBoolean(key, (Boolean) value);
        } else if (value instanceof Integer) {
            editor.putInt(key, (Integer) value);
        } else if (value instanceof Long) {
            editor.putLong(key, (Long) value);
        } else if (value instanceof Float) {
            editor.putFloat(key, (Float) value);
        } else if (value instanceof String) {
            editor.putString(key, (String) value);
        } else if (value instanceof Set) {
            editor.putStringSet(key, (Set<String>) value);
        }
    }

    public static boolean launcherHidden(Context context) {
        try {
            ComponentName alias = new ComponentName(context, LAUNCHER_ALIAS);
            int state = context.getPackageManager().getComponentEnabledSetting(alias);
            if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                return false;
            }
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return prefs(context).getBoolean("launcher_hidden", false);
    }

    public static boolean autoDeleteExpired(Context context) {
        return prefs(context).getBoolean("auto_delete_expired", false);
    }

    public static void setAutoDeleteExpired(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("auto_delete_expired", enabled).apply();
        if (enabled) {
            new DeadlineStore(context).prune();
        }
    }

    public static void setLauncherHidden(Context context, boolean hidden) {
        prefs(context).edit().putBoolean("launcher_hidden", hidden).apply();
        PackageManager manager = context.getPackageManager();
        ComponentName alias = new ComponentName(context, LAUNCHER_ALIAS);
        manager.setComponentEnabledSetting(
                alias,
                hidden ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
    }

    public static boolean shouldNotifyType(Context context, String type) {
        if ("\u4f5c\u4e1a".equals(type)) {
            return notifyHomework(context);
        }
        if ("\u8003\u8bd5".equals(type)) {
            return notifyExam(context);
        }
        if ("\u7ae0\u8282".equals(type)) {
            return notifyChapter(context);
        }
        return false;
    }
}
