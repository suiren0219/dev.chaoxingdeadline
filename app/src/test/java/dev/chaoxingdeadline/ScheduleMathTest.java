package dev.chaoxingdeadline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** ScheduleMath is pure date math, so every branch is pinned down here. */
public class ScheduleMathTest {

    // 2026-10-12 is a Monday. Use fixed timestamps built from local-time fields.
    private static final long MONDAY_0830 = at(2026, 10, 12, 8, 30);
    private static final long MONDAY_0950 = at(2026, 10, 12, 9, 50);
    private static final long TUESDAY_1400 = at(2026, 10, 13, 14, 0);
    private static final long SUNDAY_2000 = at(2026, 10, 18, 20, 0);
    /** Monday 00:00 of week 1 = 2026-09-28, so 2026-10-12 is teaching week 3. */
    private static final long FIRST_WEEK = at(2026, 9, 28, 0, 0);
    private static final String TIMES = "[\"8:00-8:45\",\"8:55-9:40\",\"10:00-10:45\",\"10:55-11:40\"]";

    @Test
    public void dayOfWeekIsOneBasedFromMonday() {
        assertEquals(1, ScheduleMath.todayDayOfWeek(MONDAY_0830));
        assertEquals(2, ScheduleMath.todayDayOfWeek(TUESDAY_1400));
        assertEquals(7, ScheduleMath.todayDayOfWeek(SUNDAY_2000));
    }

    @Test
    public void currentWeekCountsFromFirstWeekMonday() {
        assertEquals(3, ScheduleMath.currentWeek(MONDAY_0830, FIRST_WEEK));
        assertEquals(1, ScheduleMath.currentWeek(FIRST_WEEK + TimeUnit.HOURS.toMillis(1), FIRST_WEEK));
        assertEquals("before the semester it is week 0", 0,
                ScheduleMath.currentWeek(FIRST_WEEK - TimeUnit.DAYS.toMillis(1), FIRST_WEEK));
        assertEquals("no firstWeekDate means unknown", 0,
                ScheduleMath.currentWeek(MONDAY_0830, 0L));
    }

    @Test
    public void weeksStringsParseInEveryShape() {
        assertTrue(ScheduleMath.weeksContains("3", 3));
        assertFalse(ScheduleMath.weeksContains("3", 4));
        assertTrue(ScheduleMath.weeksContains("1-16", 1));
        assertTrue(ScheduleMath.weeksContains("1-16", 16));
        assertFalse(ScheduleMath.weeksContains("1-16", 17));
        assertTrue(ScheduleMath.weeksContains("1-8,10-16", 10));
        assertFalse(ScheduleMath.weeksContains("1-8,10-16", 9));
        assertTrue(ScheduleMath.weeksContains("1，3，5", 5));
        assertTrue(ScheduleMath.weeksContains("1-16周", 12));
        assertFalse("null weeks never matches", ScheduleMath.weeksContains(null, 1));
        assertFalse("empty weeks never matches here", ScheduleMath.weeksContains("", 1));
    }

    @Test
    public void weeksListEnumeratesEveryListedWeek() {
        assertEquals(Arrays.asList(3), ScheduleMath.weeksList("3"));
        assertEquals(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 10),
                ScheduleMath.weeksList("1-8,10"));
        assertEquals("full-width separators parse too", Arrays.asList(1, 3, 5),
                ScheduleMath.weeksList("1，3，5"));
        assertEquals("ranges and singles can repeat without duplicating",
                Arrays.asList(1, 2, 3), ScheduleMath.weeksList("1-3,2,3"));
        assertEquals("a stray suffix is ignored", Arrays.asList(1, 2, 3),
                ScheduleMath.weeksList("1-3周"));
    }

    @Test
    public void weeksListTreatsUnparsableInputAsEveryWeek() {
        // An empty list means "every week" everywhere it is consumed (isActiveInWeek),
        // so a missing or nonsense weeks string must not become "no week".
        assertTrue(ScheduleMath.weeksList(null).isEmpty());
        assertTrue(ScheduleMath.weeksList("").isEmpty());
        assertTrue(ScheduleMath.weeksList("   ").isEmpty());
        assertTrue("letters carry no week numbers", ScheduleMath.weeksList("abc").isEmpty());
        assertTrue("a backwards range is dropped", ScheduleMath.weeksList("9-4").isEmpty());
        assertTrue("zero and negatives are dropped", ScheduleMath.weeksList("0,-3").isEmpty());
    }

    @Test
    public void weeksListCapsRunawayRanges() {
        // A typo like "1-9999" must not allocate thousands of entries.
        List<Integer> capped = ScheduleMath.weeksList("1-9999");
        assertEquals(40, capped.size());
        assertEquals(Integer.valueOf(1), capped.get(0));
        assertEquals(Integer.valueOf(40), capped.get(capped.size() - 1));
    }

    @Test
    public void sectionTimesComeFromTheSchoolTable() {
        assertEquals(8 * 60, ScheduleMath.sectionStartMinutes(TIMES, 1));        assertEquals(8 * 60 + 45, ScheduleMath.sectionEndMinutes(TIMES, 1));
        assertEquals(10 * 60, ScheduleMath.sectionStartMinutes(TIMES, 3));
        // Unknown section falls back to the 45+10 default table.
        assertEquals(8 * 60 + 4 * 55, ScheduleMath.sectionStartMinutes(TIMES, 5));
        assertEquals(8 * 60, ScheduleMath.sectionStartMinutes("", 1));
    }

    @Test
    public void lessonTimestampsUseFirstWeekDateAndSections() {
        // Week 3 Monday section 1-2 = 2026-10-12 08:00-09:40.
        long start = ScheduleMath.lessonStartAt(FIRST_WEEK, 3, 1, 1, TIMES);
        long end = ScheduleMath.lessonEndAt(FIRST_WEEK, 3, 1, 1, 2, TIMES);
        assertEquals(at(2026, 10, 12, 8, 0), start);
        assertEquals(at(2026, 10, 12, 9, 40), end);
        // Week 3 Tuesday section 4 = 2026-10-13 10:55.
        assertEquals(at(2026, 10, 13, 10, 55),
                ScheduleMath.lessonStartAt(FIRST_WEEK, 3, 2, 4, TIMES));
    }

    @Test
    public void todayLessonsFilterByDayAndWeek() {
        List<Lesson> lessons = Arrays.asList(
                lesson("l1", "高等数学", 1, 1, 2, "1-16"),
                lesson("l2", "大学英语", 2, 3, 2, "1-8,10-16"),
                lesson("l3", "体育", 1, 6, 2, "1-2"),      // week 3 excluded
                lesson("l4", "选修", 3, 1, 2, "1-16"));
        List<Lesson> today = ScheduleMath.todayLessons(lessons, MONDAY_0830, FIRST_WEEK);
        assertEquals(1, today.size());
        assertEquals("高等数学", today.get(0).name);
        assertEquals(3, ScheduleMath.currentWeek(MONDAY_0830, FIRST_WEEK));
    }

    @Test
    public void currentLessonTracksTheSession() {
        List<Lesson> lessons = Collections.singletonList(
                lesson("l1", "高等数学", 1, 1, 2, "1-16"));
        assertNotNull("08:30 sits inside the 08:00-09:40 session",
                ScheduleMath.currentLesson(lessons, MONDAY_0830, FIRST_WEEK, TIMES));
        assertNull("09:50 is after the 09:40 bell",
                ScheduleMath.currentLesson(lessons, MONDAY_0950, FIRST_WEEK, TIMES));
        assertNull(ScheduleMath.currentLesson(lessons, TUESDAY_1400, FIRST_WEEK, TIMES));
    }

    @Test
    public void emptyWeeksMeansEveryWeek() {
        Lesson lesson = lesson("l1", "随时", 1, 1, 2, "");
        assertTrue(ScheduleMath.isActiveInWeek(lesson, 3));
        assertTrue(ScheduleMath.isActiveInWeek(lesson, 99));
        assertFalse(ScheduleMath.isActiveInWeek(lesson, 0));
    }

    @Test
    public void sectionLabelReadsNaturally() {
        assertEquals("第 3-4 节", ScheduleMath.formatSections(3, 2));
        assertEquals("第 5 节", ScheduleMath.formatSections(5, 1));
    }

    // -- helpers --

    private static Lesson lesson(String id, String name, int day, int begin, int sections, String weeks) {
        return new Lesson(id, name, "老师", "教一101", day, begin, sections, weeks);
    }

    private static long at(int year, int month, int day, int hour, int minute) {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.clear();
        calendar.set(year, month - 1, day, hour, minute, 0);
        return calendar.getTimeInMillis();
    }
}
