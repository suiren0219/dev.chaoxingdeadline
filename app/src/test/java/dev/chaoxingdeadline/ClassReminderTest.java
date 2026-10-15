package dev.chaoxingdeadline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * The 上课提醒 that replaced the removed 超级岛 notice: when it fires and which lesson it
 * names. All of it is the pure {@link ScheduleMath} math, so no Android runtime is needed.
 */
public class ClassReminderTest {
    /** "8:00-8:45" style table so section starts are exact, not the fallback guess. */
    private static final String SECTIONS = "[\"08:00-08:45\",\"08:55-09:40\",\"10:00-10:45\","
            + "\"10:55-11:40\",\"14:00-14:45\"]";

    /** Monday 00:00 of this week, the anchor the app stores as firstWeekDate. */
    private static long mondayStart() {
        Calendar calendar = Calendar.getInstance(Locale.CHINA);
        calendar.setTimeInMillis(System.currentTimeMillis());
        calendar.add(Calendar.DAY_OF_MONTH, -(ScheduleMath.todayDayOfWeek(calendar.getTimeInMillis()) - 1));
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    /** A timestamp on the given weekday (1-7) of the current week at the given time. */
    private static long thisWeek(int dayOfWeek, int hour, int minute) {
        Calendar calendar = Calendar.getInstance(Locale.CHINA);
        calendar.setTimeInMillis(mondayStart());
        calendar.add(Calendar.DAY_OF_MONTH, dayOfWeek - 1);
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        return calendar.getTimeInMillis();
    }

    private static Lesson lesson(String name, int day, int beginSection, int sections) {
        return new Lesson(name, name, "", "", day, beginSection, sections, "");
    }

    @Test
    public void nextLessonSkipsOneAlreadyInProgress() {
        long firstWeek = mondayStart();
        // Monday section 1 is 08:00-08:45; ask at 08:20, mid-lesson.
        long now = thisWeek(1, 8, 20);
        List<Lesson> lessons = Arrays.asList(lesson("上午课", 1, 1, 1), lesson("第二大节", 1, 3, 1));
        Lesson next = ScheduleMath.nextLesson(lessons, now, firstWeek, SECTIONS);
        assertNotNull(next);
        assertEquals("the lesson still to come, not the one running", 3, next.beginSection);
    }

    @Test
    public void nextLessonIsNullAfterTheLastOneStarted() {
        long firstWeek = mondayStart();
        long now = thisWeek(1, 15, 0);
        List<Lesson> lessons = Arrays.asList(lesson("上午课", 1, 1, 1), lesson("下午课", 1, 5, 1));
        assertNull("nothing left today", ScheduleMath.nextLesson(lessons, now, firstWeek, SECTIONS));
    }

    @Test
    public void triggerFiresLeadMinutesBeforeTheClass() {
        long firstWeek = mondayStart();
        long now = thisWeek(1, 7, 0);
        List<Lesson> lessons = Arrays.asList(lesson("上午课", 1, 1, 1));
        long trigger = ScheduleMath.reminderTriggerAt(lessons, now, firstWeek, SECTIONS, 15);
        assertEquals("08:00 minus 15 minutes", thisWeek(1, 7, 45), trigger);
    }

    @Test
    public void triggerFiresImmediatelyWhenInsideTheLeadWindow() {
        long firstWeek = mondayStart();
        // 07:55 is only 5 minutes before an 08:00 class: too late for a 15-minute lead,
        // but the user still must be told, so it fires now rather than being dropped.
        long now = thisWeek(1, 7, 55);
        List<Lesson> lessons = Arrays.asList(lesson("上午课", 1, 1, 1));
        long trigger = ScheduleMath.reminderTriggerAt(lessons, now, firstWeek, SECTIONS, 15);
        assertTrue("fires right away", trigger > now);
        assertTrue("but in the near future, never the past", trigger - now <= 2_000L);
    }

    @Test
    public void triggerIsOffWhenRemindersDisabledOrDayEmpty() {
        long firstWeek = mondayStart();
        long now = thisWeek(1, 7, 0);
        List<Lesson> lessons = Arrays.asList(lesson("上午课", 1, 1, 1));
        assertEquals("0 minutes means off", 0L,
                ScheduleMath.reminderTriggerAt(lessons, now, firstWeek, SECTIONS, 0));
        assertEquals("no lesson left", 0L,
                ScheduleMath.reminderTriggerAt(lessons, thisWeek(1, 20, 0), firstWeek, SECTIONS, 15));
        assertEquals("empty timetable", 0L,
                ScheduleMath.reminderTriggerAt(java.util.Collections.<Lesson>emptyList(),
                        now, firstWeek, SECTIONS, 15));
    }

    @Test
    public void triggerUsesTheWeekTheLessonRunsIn() {
        long firstWeek = mondayStart();
        long now = thisWeek(1, 7, 0);
        Lesson oddOnly = lesson("单周课", 1, 1, 1);
        oddOnly.weeks = "99";  // not this week
        assertEquals("a lesson absent this week must not fire", 0L,
                ScheduleMath.reminderTriggerAt(Arrays.asList(oddOnly), now, firstWeek, SECTIONS, 15));
    }

    @Test
    public void eveningFlipsTheWidgetToTomorrowAt21() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 20);
        calendar.set(Calendar.MINUTE, 59);
        assertFalse("20:59 still shows today",
                DeadlineScheduleWidget.showingTomorrow(calendar.getTimeInMillis()));
        calendar.set(Calendar.HOUR_OF_DAY, 21);
        calendar.set(Calendar.MINUTE, 0);
        assertTrue("21:00 shows tomorrow",
                DeadlineScheduleWidget.showingTomorrow(calendar.getTimeInMillis()));
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 5);
        assertFalse("after midnight shows today again",
                DeadlineScheduleWidget.showingTomorrow(calendar.getTimeInMillis()));
    }

    @Test
    public void lessonsOnFollowsThePassedDayNotTheClock() {
        // The widget asks for tomorrow while the clock still reads today; the day must come
        // from the argument, not from "now".
        long firstWeek = mondayStart();
        long tomorrow = thisWeek(2, 9, 0);
        List<Lesson> lessons = Arrays.asList(lesson("周二课", 2, 1, 1), lesson("周三课", 3, 1, 1));
        List<Lesson> tuesday = ScheduleMath.lessonsOn(lessons, tomorrow, firstWeek);
        assertEquals(1, tuesday.size());
        assertEquals("周二课", tuesday.get(0).name);
    }

    @Test
    public void widgetRowBudgetMatchesThePanelSize() {
        // A 4x2 panel (about 110-140dp tall on most launchers) must show more than one row
        // but not pretend to fit five; a 4x4 panel should use the extra room.
        assertTrue("4x2 shows several rows", DeadlineScheduleWidget.rowsForHeight(140) >= 1);
        assertTrue("and more than one", DeadlineScheduleWidget.rowsForHeight(200) >= 2);
        assertTrue("taller panel shows more",
                DeadlineScheduleWidget.rowsForHeight(300) > DeadlineScheduleWidget.rowsForHeight(140));
        assertEquals("never zero rows", 1, DeadlineScheduleWidget.rowsForHeight(97));
        assertEquals("a launcher reporting nothing gets the 4x2 default",
                DeadlineScheduleWidget.DEFAULT_ROWS, DeadlineScheduleWidget.rowsForHeight(0));
        assertEquals("a huge panel is still capped", 8, DeadlineScheduleWidget.rowsForHeight(4000));
    }

    @Test
    public void reminderAlarmHasItsOwnIdentity() {
        // One stable action and request code per reminder, so re-arming replaces the alarm
        // instead of leaving a queue of them behind.
        assertEquals("dev.chaoxingdeadline.CLASS_REMINDER", DeadlineReceiver.ACTION_CLASS_REMINDER);
        assertNotEquals(DeadlineReceiver.ACTION_DAILY_DIGEST, DeadlineReceiver.ACTION_CLASS_REMINDER);
        assertNotEquals(DeadlineReceiver.ACTION_WIDGET_REFRESH, DeadlineReceiver.ACTION_CLASS_REMINDER);
    }
}
