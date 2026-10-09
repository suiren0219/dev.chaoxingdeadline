package dev.chaoxingdeadline;

import java.util.concurrent.TimeUnit;

/**
 * Wording for a reminder. The label describes the *offset that was configured*, which is
 * only honest while the notification fires on time: a reminder deferred out of quiet hours,
 * or a catch-up after a missed window, can go out hours later, and calling that
 * "提前 3 小时" while one hour is actually left would be misleading. In those cases the
 * label falls back to the real remaining time.
 *
 * No Android dependencies — unit tested in ReminderTextTest.
 */
public final class ReminderText {
    /** Tolerance for normal alarm jitter before the reminder counts as late. */
    static final long DRIFT_TOLERANCE = TimeUnit.MINUTES.toMillis(5);

    private ReminderText() {
    }

    public static String label(DeadlineItem item, long offsetMillis, long now) {
        long remaining = (item == null ? 0L : item.dueAt) - now;
        if (offsetMillis <= 0L) {
            return remaining <= 0L ? "已截止" : "现在截止";
        }
        if (item == null) {
            return offsetLabel(offsetMillis);
        }
        long plannedTrigger = item.dueAt - offsetMillis;
        if (now - plannedTrigger > DRIFT_TOLERANCE) {
            // Sent well after its configured moment (catch-up or quiet-hours deferral):
            // report what is actually left instead of the nominal advance notice.
            return remaining <= 0L ? "已截止" : "还剩 " + remaining(remaining);
        }
        return offsetLabel(offsetMillis);
    }

    /** Human-readable duration: "3 天 2 小时", "5 小时", "45 分钟". */
    public static String remaining(long millis) {
        if (millis <= 0L) {
            return "已过";
        }
        long minutes = Math.max(1L, TimeUnit.MILLISECONDS.toMinutes(millis));
        if (minutes < 60L) {
            return minutes + " 分钟";
        }
        long hours = TimeUnit.MILLISECONDS.toHours(millis);
        if (hours < 24L) {
            return hours + " 小时";
        }
        long days = hours / 24L;
        long restHours = hours % 24L;
        return restHours == 0L ? days + " 天" : days + " 天 " + restHours + " 小时";
    }

    private static String offsetLabel(long offsetMillis) {
        if (offsetMillis >= TimeUnit.HOURS.toMillis(1)) {
            return "提前 " + offsetMillis / TimeUnit.HOURS.toMillis(1) + " 小时";
        }
        return "提前 " + Math.max(1L, offsetMillis / TimeUnit.MINUTES.toMillis(1)) + " 分钟";
    }
}
