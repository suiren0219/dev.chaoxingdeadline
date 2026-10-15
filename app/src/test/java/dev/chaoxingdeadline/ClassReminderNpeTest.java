package dev.chaoxingdeadline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The reminder must survive an empty timetable.
 *
 * updateClassReminder is reached from the settings dialog (UI thread) and from the alarm
 * receiver, and "no class left today" is its most common state — every evening, and every
 * day before a semester is captured. It dereferenced the lesson without checking for null.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ClassReminderNpeTest {

    @Test
    public void noLessonTodayLeavesNothingToRemindAbout() {
        // Empty timetable: reminderLesson() returns null by contract.
        assertNull(DeadlineNotifier.reminderLesson(RuntimeEnvironment.getApplication()));
    }

    @Test
    public void postingWithNoLessonTodayMustNotThrow() {
        // This is the call the settings dialog and the alarm both make.
        DeadlineNotifier.updateClassReminder(RuntimeEnvironment.getApplication());
    }

    @Test
    public void alarmFiringLateForAClassAlreadyStartedMustNotThrow() {
        // An alarm deferred by doze arrives after the lesson began. nextLesson() skips
        // lessons in progress, so the reminder is left with nothing to name — which is the
        // same null the empty timetable produces, and the case that would crash in the field.
        android.content.Context context = RuntimeEnvironment.getApplication();
        DeadlineStore store = new DeadlineStore(context);
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.add(java.util.Calendar.DAY_OF_MONTH,
                -(ScheduleMath.todayDayOfWeek(calendar.getTimeInMillis()) - 1));
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0);
        calendar.set(java.util.Calendar.MINUTE, 0);
        calendar.set(java.util.Calendar.SECOND, 0);
        calendar.set(java.util.Calendar.MILLISECOND, 0);
        long monday = calendar.getTimeInMillis();
        // A lesson in section 1 today, i.e. long since started whenever this test runs.
        store.replaceLessons(java.util.Collections.singletonList(
                new Lesson("L1", "示例课程", "", "", 
                        ScheduleMath.todayDayOfWeek(System.currentTimeMillis()), 1, 1, "1-16")),
                monday, "[\"08:00-08:45\"]");
        DeadlineNotifier.updateClassReminder(context);
        DeadlineNotifier.scheduleClassReminder(context);
    }

    @Test
    public void timetableListWithANullElementStillRenders() {
        // Defensive: a null among the stored lessons must not take down the widget or the
        // reminder, both of which walk that list on every repaint.
        java.util.List<Lesson> withNull = new java.util.ArrayList<>();
        withNull.add(null);
        withNull.add(new Lesson("L2", "示例课程", "", "", 1, 1, 1, ""));
        assertEquals(1, ScheduleMath.lessonsOn(withNull, System.currentTimeMillis(),
                System.currentTimeMillis()).size()
                + (ScheduleMath.todayDayOfWeek(System.currentTimeMillis()) == 1 ? 0 : 1));
    }
}
