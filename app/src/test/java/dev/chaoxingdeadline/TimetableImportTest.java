package dev.chaoxingdeadline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;
import java.util.List;

/**
 * 课表导入：JSON 与 ICS。
 *
 * ICS 的难点在于它给的是**具体日期**而课表按「第几周 + 星期几 + 第几节」建模，
 * 所以要先把日期换算回周次，再用同一天内的开始时刻排序编号节次。这里的用例覆盖
 * 那个换算、折行、时区后缀、以及「同一门课多周是多个事件」这个 ICS 的常见形态。
 */
public class TimetableImportTest {

    /** 构造一个 ICS，周一开始的那一周作为第 1 周。 */
    private static String ics(String... events) {
        StringBuilder builder = new StringBuilder();
        builder.append("BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//test//CN\n");
        for (String event : events) {
            builder.append("BEGIN:VEVENT\n").append(event).append("END:VEVENT\n");
        }
        builder.append("END:VCALENDAR\n");
        return builder.toString();
    }

    /** 本学期第 1 周周一的日期（yyyyMMdd），保证测试在任何时候运行都自洽。 */
    private static String firstMondayStamp() {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(System.currentTimeMillis());
        calendar.add(Calendar.DAY_OF_MONTH,
                -(ScheduleMath.todayDayOfWeek(calendar.getTimeInMillis()) - 1));
        return new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT)
                .format(calendar.getTime());
    }

    /** 第 week 周、星期 dayOfWeek 的日期戳。 */
    private static String stamp(int week, int dayOfWeek, String time) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(System.currentTimeMillis());
        calendar.add(Calendar.DAY_OF_MONTH,
                -(ScheduleMath.todayDayOfWeek(calendar.getTimeInMillis()) - 1));
        calendar.add(Calendar.DAY_OF_MONTH, (week - 1) * 7 + (dayOfWeek - 1));
        return new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT)
                .format(calendar.getTime()) + "T" + time;
    }

    @Test
    public void parsesAnIcsWeekIntoTheRightWeekdayAndWeeks() {
        String text = ics("SUMMARY:示例课程甲\nLOCATION:教一101\n"
                + "DTSTART:" + stamp(1, 3, "080000") + "\n"
                + "DTEND:" + stamp(1, 3, "084500") + "\n",
                "SUMMARY:示例课程甲\nLOCATION:教一101\n"
                + "DTSTART:" + stamp(2, 3, "080000") + "\n");
        TimetableImport.Imported imported = TimetableImport.parse(text, "t.ics");
        assertNotNull(imported);
        assertEquals("ICS", imported.format);
        assertEquals(1, imported.lessons.size());
        Lesson lesson = imported.lessons.get(0);
        assertEquals("示例课程甲", lesson.name);
        assertEquals("教一101", lesson.location);
        assertEquals("第 1 周落在周三", 3, lesson.dayOfWeek);
        assertEquals("两周一节课合并成 1-2", "1-2", lesson.weeks);
        assertTrue("首周周一被识别", imported.firstWeekDate > 0L);
    }

    @Test
    public void numbersSectionsByStartTimeWithinADay() {
        // 同一天三节课，不同开始时刻；节次应按时间先后编成 1、2、3。
        String text = ics(
                "SUMMARY:上午一\nDTSTART:" + stamp(1, 1, "080000") + "\n",
                "SUMMARY:上午二\nDTSTART:" + stamp(1, 1, "100000") + "\n",
                "SUMMARY:下午一\nDTSTART:" + stamp(1, 1, "140000") + "\n");
        TimetableImport.Imported imported = TimetableImport.parse(text, "t.ics");
        assertNotNull(imported);
        assertEquals(3, imported.lessons.size());
        // 列表已按星期+节次排序
        assertEquals("上午一", imported.lessons.get(0).name);
        assertEquals(1, imported.lessons.get(0).beginSection);
        assertEquals("上午二", imported.lessons.get(1).name);
        assertEquals(2, imported.lessons.get(1).beginSection);
        assertEquals("下午一", imported.lessons.get(2).name);
        assertEquals(3, imported.lessons.get(2).beginSection);
    }

    @Test
    public void differentDaysGetIndependentSectionNumbering() {
        // 周三第 1 节与周一第 1 节是不同节次槽，但都该从 1 开始编号。
        String text = ics(
                "SUMMARY:周一课\nDTSTART:" + stamp(1, 1, "080000") + "\n",
                "SUMMARY:周三课\nDTSTART:" + stamp(1, 3, "140000") + "\n");
        TimetableImport.Imported imported = TimetableImport.parse(text, "t.ics");
        assertNotNull(imported);
        assertEquals(2, imported.lessons.size());
        for (Lesson lesson : imported.lessons) {
            assertEquals("每天各排各的", 1, lesson.beginSection);
        }
    }

    @Test
    public void unfoldsContinuationLinesAndReadsUtcSuffix() {
        // ICS 规定长行折行续行；DTSTART 也可能带 Z（UTC）。两者都要能读。
        String text = ics("SUMMARY:很长的一门课程名称会被日历折"
                + "\n 行写在这里\n"
                + "DTSTART:" + stamp(1, 2, "080000") + "Z\n");
        TimetableImport.Imported imported = TimetableImport.parse(text, "t.ics");
        assertNotNull("折行后仍能解析", imported);
        Lesson lesson = imported.lessons.get(0);
        assertTrue("摘要被拼接起来", lesson.name.startsWith("很长的一门课程名称"));
        assertEquals(2, lesson.dayOfWeek);
    }

    @Test
    public void skipsVeveventsWithoutASummary() {
        String text = ics("DTSTART:" + stamp(1, 1, "080000") + "\n",
                "SUMMARY:有名字的课\nDTSTART:" + stamp(1, 1, "080000") + "\n");
        TimetableImport.Imported imported = TimetableImport.parse(text, "t.ics");
        assertNotNull(imported);
        assertEquals("没有标题的事件被丢掉", 1, imported.lessons.size());
        assertEquals("有名字的课", imported.lessons.get(0).name);
    }

    @Test
    public void readsTheTeacherOutOfTheDescription() {
        String text = ics("SUMMARY:示例课程乙\nDESCRIPTION:教师：老师乙\\n学分：3\n"
                + "DTSTART:" + stamp(1, 4, "100000") + "\n");
        TimetableImport.Imported imported = TimetableImport.parse(text, "t.ics");
        assertNotNull(imported);
        assertEquals("老师乙", imported.lessons.get(0).teacher);
    }

    @Test
    public void rejectsPayloadsThatAreNotATimetable() {
        assertNull(TimetableImport.parse("BEGIN:VCALENDAR\nEND:VCALENDAR\n", "t.ics"));
        assertNull(TimetableImport.parse("这不是课表", "t.txt"));
        assertNull(TimetableImport.parse("", "t.json"));
        assertNull(TimetableImport.parse(null, "t.json"));
        // 一个没有 DTSTART 的事件不该被当成课。
        assertNull(TimetableImport.parse(ics("SUMMARY:无时间\n"), "t.ics"));
    }

    @Test
    public void parsesOurOwnJsonExport() {
        // tools/fetch_semester.py 的产物形态，也应当能直接导入。
        String json = "{\"lessons\":["
                + "{\"name\":\"示例课程丙\",\"teacher\":\"老师丙\",\"location\":\"教二202\","
                + "\"dayOfWeek\":2,\"beginSection\":3,\"sections\":2,\"weeks\":\"1-16\"}],"
                + "\"firstWeekDate\":1789948800000}";
        TimetableImport.Imported imported = TimetableImport.parse(json, "semester.json");
        assertNotNull(imported);
        assertEquals("JSON", imported.format);
        assertEquals(1, imported.lessons.size());
        assertEquals(3, imported.lessons.get(0).beginSection);
        assertEquals("1-16", imported.lessons.get(0).weeks);
    }

    @Test
    public void detectsFormatFromContentNotJustExtension() {
        // 扩展名骗人时以内容为准：这也是 ICS 优先按 BEGIN:VCALENDAR 判定的原因。
        String text = ics("SUMMARY:课\nDTSTART:" + stamp(1, 1, "080000") + "\n");
        assertNotNull("a .txt holding an ICS still parses",
                TimetableImport.parse(text, "mislabeled.txt"));
        assertEquals("ICS", TimetableImport.parse(text, "mislabeled.txt").format);
    }

    @Test
    public void handlesDateOnlyEvents() {
        // VALUE=DATE 形式（全天事件）没有时间，只能定到天；仍应产出可用的星期与周次。
        String text = ics("SUMMARY:全天事件\nDTSTART;VALUE=DATE:"
                + stamp(1, 5, "000000").substring(0, 8) + "\n");
        TimetableImport.Imported imported = TimetableImport.parse(text, "t.ics");
        assertNotNull(imported);
        assertEquals(1, imported.lessons.size());
        assertEquals(5, imported.lessons.get(0).dayOfWeek);
    }

    @Test
    public void importResultsAreStableAcrossRuns() {
        // 同样的输入解析两次必须完全一样，否则「导入两次」会看到不同课表。
        String text = ics(
                "SUMMARY:甲\nDTSTART:" + stamp(1, 1, "080000") + "\n",
                "SUMMARY:乙\nDTSTART:" + stamp(1, 3, "100000") + "\n");
        TimetableImport.Imported first = TimetableImport.parse(text, "t.ics");
        TimetableImport.Imported second = TimetableImport.parse(text, "t.ics");
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(first.lessons.size(), second.lessons.size());
        List<Lesson> a = first.lessons;
        List<Lesson> b = second.lessons;
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).name, b.get(i).name);
            assertEquals(a.get(i).dayOfWeek, b.get(i).dayOfWeek);
            assertEquals(a.get(i).beginSection, b.get(i).beginSection);
            assertEquals(a.get(i).weeks, b.get(i).weeks);
        }
        assertNotNull(firstWeekStamp());
    }

    private static String firstWeekStamp() {
        return firstMondayStamp();
    }
}
