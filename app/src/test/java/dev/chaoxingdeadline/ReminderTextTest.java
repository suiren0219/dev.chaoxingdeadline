package dev.chaoxingdeadline;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

/**
 * Pins down the reminder wording: the configured offset is only used while the reminder
 * actually fires on time. A deferred or caught-up reminder must describe the real
 * remaining time instead of claiming an advance notice that no longer applies.
 */
public class ReminderTextTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final long MINUTE = TimeUnit.MINUTES.toMillis(1);
    private static final long HOUR = TimeUnit.HOURS.toMillis(1);

    private static DeadlineItem itemDueIn(long millisFromNow) {
        DeadlineItem item = new DeadlineItem();
        item.type = "作业";
        item.title = "第三章作业";
        item.dueAt = NOW + millisFromNow;
        return item;
    }

    @Test
    public void usesConfiguredOffsetWhenFiredOnTime() {
        assertEquals("提前 3 小时", ReminderText.label(itemDueIn(3 * HOUR), 3 * HOUR, NOW));
        assertEquals("提前 24 小时", ReminderText.label(itemDueIn(24 * HOUR), 24 * HOUR, NOW));
        assertEquals("提前 30 分钟", ReminderText.label(itemDueIn(30 * MINUTE), 30 * MINUTE, NOW));
    }

    @Test
    public void toleratesNormalAlarmJitter() {
        // Two minutes late is jitter, not a deferral: keep the configured wording.
        DeadlineItem item = itemDueIn(3 * HOUR - 2 * MINUTE);
        assertEquals("提前 3 小时", ReminderText.label(item, 3 * HOUR, NOW));
    }

    @Test
    public void reportsRealRemainingTimeAfterDeferral() {
        // The "3 hours ahead" trigger was deferred out of quiet hours: it now goes out with
        // one hour left, so claiming "提前 3 小时" would be wrong.
        DeadlineItem item = itemDueIn(1 * HOUR);
        assertEquals("还剩 1 小时", ReminderText.label(item, 3 * HOUR, NOW));
    }

    @Test
    public void reportsRealRemainingTimeForCatchUp() {
        // A caught-up 24h reminder for something due in 10 hours.
        DeadlineItem item = itemDueIn(10 * HOUR);
        assertEquals("还剩 10 小时", ReminderText.label(item, 24 * HOUR, NOW));
    }

    @Test
    public void dueTimeOffsetReadsNaturally() {
        assertEquals("现在截止", ReminderText.label(itemDueIn(5 * MINUTE), 0L, NOW));
        assertEquals("已截止", ReminderText.label(itemDueIn(-5 * MINUTE), 0L, NOW));
        assertEquals("已截止", ReminderText.label(itemDueIn(-1 * HOUR), 24 * HOUR, NOW));
    }

    @Test
    public void remainingIsHumanReadable() {
        assertEquals("3 天 2 小时", ReminderText.remaining(3 * 24 * HOUR + 2 * HOUR));
        assertEquals("3 天", ReminderText.remaining(3 * 24 * HOUR));
        assertEquals("5 小时", ReminderText.remaining(5 * HOUR));
        assertEquals("45 分钟", ReminderText.remaining(45 * MINUTE));
        assertEquals("1 分钟", ReminderText.remaining(20_000L));
        assertEquals("已过", ReminderText.remaining(0L));
        assertEquals("已过", ReminderText.remaining(-1L));
    }
}
