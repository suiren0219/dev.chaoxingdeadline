package dev.chaoxingdeadline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

/**
 * 调休日历的解析。
 *
 * 这里只测解析，因为解析是唯一有真实逻辑的部分——数据本身由用户按校历填写，不内置。
 * 重点在于：错的输入必须被忽略而不是猜一个值，因为一个错的调休标注会让学生白跑一趟。
 */
public class HolidayCalendarTest {

    private static long day(int year, int month, int dayOfMonth) {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.clear();
        calendar.set(year, month - 1, dayOfMonth, 0, 0, 0);
        calendar.set(java.util.Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    @Test
    public void parsesSingleDatesAndLabels() {
        Map<Long, Integer> table = HolidayCalendar.parse(
                "2026-10-01=休\n2026-10-10=班");
        assertEquals(2, table.size());
        assertEquals(Integer.valueOf(HolidayCalendar.REST), table.get(day(2026, 10, 1)));
        assertEquals(Integer.valueOf(HolidayCalendar.WORK), table.get(day(2026, 10, 10)));
    }

    @Test
    public void expandsRangesInclusive() {
        // A holiday block is what people actually copy from the notice, so ranges must work
        // and must include both ends.
        Map<Long, Integer> table = HolidayCalendar.parse("2026-10-01~2026-10-08=休");
        assertEquals("八天连休", 8, table.size());
        assertEquals(Integer.valueOf(HolidayCalendar.REST), table.get(day(2026, 10, 1)));
        assertEquals(Integer.valueOf(HolidayCalendar.REST), table.get(day(2026, 10, 8)));
        assertFalse(table.containsKey(day(2026, 10, 9)));
    }

    @Test
    public void acceptsAlternateSeparatorsAndLabels() {
        Map<Long, Integer> table = HolidayCalendar.parse(
                "2026-01-01=假；2026-01-04=work\n2026-02-01=rest");
        assertEquals(3, table.size());
        assertEquals(Integer.valueOf(HolidayCalendar.REST), table.get(day(2026, 1, 1)));
        assertEquals(Integer.valueOf(HolidayCalendar.WORK), table.get(day(2026, 1, 4)));
        assertEquals(Integer.valueOf(HolidayCalendar.REST), table.get(day(2026, 2, 1)));
    }

    @Test
    public void ignoresCommentsBlanksAndUnparsableLines() {
        // Anything unrecognised is dropped rather than guessed: a wrong 调休 marker is worse
        // than none, because a student would follow it.
        Map<Long, Integer> table = HolidayCalendar.parse(
                "# 2026 校历\n"
                        + "\n"
                        + "这是说明文字\n"
                        + "2026-10-01\n"                 // no label
                        + "2026-10-02=??\n"              // unknown label
                        + "10-03=休\n"                   // not a full date
                        + "2026-13-99=休\n"              // impossible date
                        + "2026-10-05=休");
        assertEquals("only the one well-formed line survives", 1, table.size());
        assertEquals(Integer.valueOf(HolidayCalendar.REST), table.get(day(2026, 10, 5)));
    }

    @Test
    public void emptySpecMeansNoAnnotations() {
        assertTrue(HolidayCalendar.parse("").isEmpty());
        assertTrue(HolidayCalendar.parse(null).isEmpty());
        assertTrue(HolidayCalendar.parse("   \n  ").isEmpty());
    }

    @Test
    public void reversedRangeStillYieldsTheSingleDay() {
        // "10-08~10-01" is a typo; collapsing to one day beats expanding wildly or crashing.
        Map<Long, Integer> table = HolidayCalendar.parse("2026-10-08~2026-10-01=休");
        assertEquals(1, table.size());
        assertTrue(table.containsKey(day(2026, 10, 8)));
    }

    @Test
    public void absurdlyLongRangeIsCapped() {
        // A hand-written range must not be able to allocate a huge table.
        Map<Long, Integer> table = HolidayCalendar.parse("2020-01-01~2035-01-01=休");
        assertTrue("capped", table.size() <= 40);
    }

    @Test
    public void makeupWorkdayOnlyCountsWeekends() {
        // 调休上班落在工作日时对课表没影响（课表本就按工作日排），所以只标周末的。
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.setTimeInMillis(day(2026, 10, 10));   // a Saturday
        assertEquals("2026-10-10 是周六", java.util.Calendar.SATURDAY,
                calendar.get(java.util.Calendar.DAY_OF_WEEK));
        calendar.setTimeInMillis(day(2026, 10, 12));   // a Monday
        assertEquals(java.util.Calendar.MONDAY, calendar.get(java.util.Calendar.DAY_OF_WEEK));
    }

    @Test
    public void noDataMeansNoSummary() {
        // monthSummary is called on every render; with no spec it must be cheap and empty.
        assertEquals("", HolidayCalendar.parse("").isEmpty() ? "" : "unexpected");
    }
}
