package dev.chaoxingdeadline;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

/**
 * 从 JSON 或 ICS 文件导入课表。
 *
 * <p><b>为什么需要</b>：学习通接口一次只答一周（见 tools/fetch_semester.py 的说明），
 * 逐周抓一遍要几十个请求。而课表数据往往已经在别的形式里存在——自己用脚本抓下来的整学期
 * JSON、其它课表 App 导出的 ICS、学校教务系统给的日历文件。能直接导入就不必再逐周请求。
 *
 * <p>JSON 交给 {@link TimetableParser} 的三层解析（键名 → 结构 → 别名），所以既能吃
 * {@code tools/fetch_semester.py} 的产物，也能吃第三方工具导出的 JSON。
 *
 * <p>ICS 由本类解析，做法是先把「具体日期」换算回「第几周 + 星期几」，再用**同一天内
 * 开始时间的先后**给节次编号。之所以能这样编号：ICS 里一节课的每个周次是一个独立事件，
 * 同一门课在同一时刻出现多次，按时间排序后第几个时刻就是第几节——不需要节次表也能自洽。
 */
public final class TimetableImport {
    /** 导入结果。 */
    public static final class Imported {
        public final List<Lesson> lessons;
        /** 首周周一 00:00；0 表示没能确定。 */
        public final long firstWeekDate;
        /** 节次表（仅 JSON 来源可能有）；空串表示没有。 */
        public final String sectionTimesJson;
        /** 识别到的格式，用于回报：「JSON」「ICS」。 */
        public final String format;

        Imported(List<Lesson> lessons, long firstWeekDate, String sectionTimesJson,
                 String format) {
            this.lessons = lessons;
            this.firstWeekDate = firstWeekDate;
            this.sectionTimesJson = sectionTimesJson;
            this.format = format;
        }
    }

    private static final long WEEK_MILLIS = 7L * 24L * 60L * 60L * 1000L;

    private TimetableImport() {
    }

    /** 按内容判断格式并解析；认不出返回 null。 */
    public static Imported parse(String text, String fileName) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        String trimmed = text.trim();
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        // BEGIN:VCALENDAR 是最可靠的判据；扩展名作为补充（内容被截断时也认得出）。
        if (trimmed.contains("BEGIN:VCALENDAR") || name.endsWith(".ics")) {
            return parseIcs(trimmed);
        }
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return parseJson(trimmed);
        }
        return null;
    }

    /** JSON 走通用课表解析（键名 → 结构 → 别名）。 */
    static Imported parseJson(String text) {
        TimetableParser.Result result = TimetableParser.parse(text);
        if (result == null || result.lessons.isEmpty()) {
            return null;
        }
        return new Imported(result.lessons, result.firstWeekDate, result.sectionTimesJson, "JSON");
    }

    /**
     * 解析 ICS。
     *
     * 同一门课在不同周次是多个事件，因此先按「课程名 + 星期 + 开始时刻」归并，
     * 把各事件的周次并成一个 weeks 列表；再按每天开始时刻的先后把时刻映射成节次。
     */
    static Imported parseIcs(String text) {
        List<Event> events = readEvents(text);
        if (events.isEmpty()) {
            return null;
        }
        long firstWeekDate = firstMonday(events);
        if (firstWeekDate <= 0L) {
            return null;
        }

        // slot -> 累积器；slot 用「课程名 + 星期 + 开始时刻」而不是课程名，
        // 因为同一门课一周可能上两次。
        LinkedHashMap<String, Accumulator> bySlot = new LinkedHashMap<>();
        // 每天出现过的开始时刻，用来给节次编号。
        Map<Integer, LinkedHashSet<String>> clocksByDay = new TreeMap<>();
        for (Event event : events) {
            if (event.startMillis <= 0L) {
                continue;
            }
            long dayStart = WeekGrouping.dayStart(event.startMillis);
            int dayOfWeek = ScheduleMath.todayDayOfWeek(dayStart);
            int week = (int) ((dayStart - firstWeekDate) / WEEK_MILLIS) + 1;
            if (week < 1 || dayOfWeek < 1 || dayOfWeek > 7) {
                continue;
            }
            String name = event.summary.isEmpty() ? "未命名课程" : event.summary;
            String clock = clockOf(event.startMillis);
            String slot = name + "|" + dayOfWeek + "|" + clock;
            Accumulator accumulator = bySlot.get(slot);
            if (accumulator == null) {
                accumulator = new Accumulator(name, dayOfWeek, clock, event);
                bySlot.put(slot, accumulator);
            }
            accumulator.weeks.add(week);

            LinkedHashSet<String> clocks = clocksByDay.get(dayOfWeek);
            if (clocks == null) {
                clocks = new LinkedHashSet<>();
                clocksByDay.put(dayOfWeek, clocks);
            }
            clocks.add(clock);
        }
        if (bySlot.isEmpty()) {
            return null;
        }

        // 同一天的开始时刻排序后，第 i 个就是第 i 节。
        Map<Integer, Map<String, Integer>> sectionOf = new HashMap<>();
        for (Map.Entry<Integer, LinkedHashSet<String>> entry : clocksByDay.entrySet()) {
            ArrayList<String> sorted = new ArrayList<>(entry.getValue());
            Collections.sort(sorted);
            Map<String, Integer> mapping = new HashMap<>();
            for (int i = 0; i < sorted.size(); i++) {
                mapping.put(sorted.get(i), i + 1);
            }
            sectionOf.put(entry.getKey(), mapping);
        }

        ArrayList<Lesson> lessons = new ArrayList<>(bySlot.size());
        for (Accumulator accumulator : bySlot.values()) {
            Map<String, Integer> mapping = sectionOf.get(accumulator.dayOfWeek);
            Integer section = mapping == null ? null : mapping.get(accumulator.clock);
            lessons.add(accumulator.toLesson(section == null ? 1 : section));
        }
        Collections.sort(lessons, Comparator
                .comparingInt((Lesson lesson) -> lesson.dayOfWeek)
                .thenComparingInt(lesson -> lesson.beginSection));
        return new Imported(lessons, firstWeekDate, "", "ICS");
    }

    /** 事件里最早的周一 00:00，作为第 1 周。 */
    private static long firstMonday(List<Event> events) {
        long earliest = Long.MAX_VALUE;
        for (Event event : events) {
            if (event.startMillis > 0L && event.startMillis < earliest) {
                earliest = event.startMillis;
            }
        }
        if (earliest == Long.MAX_VALUE) {
            return 0L;
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(WeekGrouping.dayStart(earliest));
        int day = ScheduleMath.todayDayOfWeek(calendar.getTimeInMillis());
        calendar.add(Calendar.DAY_OF_MONTH, -(day - 1));
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private static String clockOf(long millis) {
        return new SimpleDateFormat("HH:mm", Locale.ROOT).format(new java.util.Date(millis));
    }

    /** 一个 VEVENT 里我们关心的字段。 */
    private static final class Event {
        String summary = "";
        String location = "";
        String description = "";
        long startMillis;
    }

    /**
     * 读 VEVENT 块。
     *
     * 处理 ICS 的两个现实细节：**折行**（长行以空格/Tab 续行）与**参数**
     * （{@code DTSTART;TZID=Asia/Shanghai:20260907T080000}）。日期可能带 {@code Z} 后缀，
     * 也可能只是日期（{@code VALUE=DATE:20260907}）。
     */
    private static List<Event> readEvents(String text) {
        ArrayList<Event> events = new ArrayList<>();
        String unfolded = text.replaceAll("\r\n[ \t]", "").replaceAll("\n[ \t]", "");
        Event current = null;
        for (String rawLine : unfolded.split("\r?\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.equalsIgnoreCase("BEGIN:VEVENT")) {
                current = new Event();
                continue;
            }
            if (line.equalsIgnoreCase("END:VEVENT")) {
                if (current != null && !current.summary.isEmpty()) {
                    events.add(current);
                }
                current = null;
                continue;
            }
            if (current == null) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String name = line.substring(0, colon).toUpperCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (name.startsWith("SUMMARY")) {
                current.summary = unescapeIcs(value);
            } else if (name.startsWith("LOCATION")) {
                current.location = unescapeIcs(value);
            } else if (name.startsWith("DESCRIPTION")) {
                current.description = unescapeIcs(value);
            } else if (name.startsWith("DTSTART")) {
                current.startMillis = parseIcsDate(value);
            }
        }
        return events;
    }

    /** {@code 20260907T080000} / {@code 20260907T080000Z} / {@code 20260907} → 毫秒。 */
    static long parseIcsDate(String value) {
        String text = value == null ? "" : value.trim();
        boolean utc = text.endsWith("Z");
        if (utc) {
            text = text.substring(0, text.length() - 1);
        }
        boolean dateOnly = text.length() == 8;
        try {
            SimpleDateFormat format = new SimpleDateFormat(
                    dateOnly ? "yyyyMMdd" : "yyyyMMdd'T'HHmmss", Locale.ROOT);
            // 带 Z 的按 UTC 读；其余按设备本地时区读——课表是给学生自己看的，
            // 本地时间才对应他实际要去的时刻。
            format.setTimeZone(utc ? TimeZone.getTimeZone("UTC") : TimeZone.getDefault());
            java.util.Date parsed = format.parse(text);
            return parsed == null ? 0L : parsed.getTime();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /** ICS 文本转义：{@code \n} → 换行，{@code \,} → 逗号等。 */
    private static String unescapeIcs(String value) {
        return value.replace("\\n", "\n").replace("\\N", "\n")
                .replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\");
    }

    /** 同一门课同一时刻的多周事件累积成一条。 */
    private static final class Accumulator {
        final String name;
        final int dayOfWeek;
        final String clock;
        final String location;
        final String teacher;
        final LinkedHashSet<Integer> weeks = new LinkedHashSet<>();

        Accumulator(String name, int dayOfWeek, String clock, Event event) {
            this.name = name;
            this.dayOfWeek = dayOfWeek;
            this.clock = clock;
            this.location = event.location;
            this.teacher = teacherFrom(event.description);
        }

        Lesson toLesson(int section) {
            ArrayList<Integer> sorted = new ArrayList<>(weeks);
            Collections.sort(sorted);
            Lesson lesson = new Lesson("", name, teacher, location, dayOfWeek, section, 1,
                    ScheduleMath.weeksNormalized(sorted));
            lesson.id = lesson.stableId();
            return lesson;
        }

        /** 教师常被放在 DESCRIPTION 里，形式不定，取第一个像人名的片段。 */
        private static String teacherFrom(String description) {
            if (description == null || description.isEmpty()) {
                return "";
            }
            for (String line : description.split("[\n;]")) {
                String trimmed = line.trim();
                String lower = trimmed.toLowerCase(Locale.ROOT);
                int index = lower.indexOf("teacher");
                if (index >= 0) {
                    String rest = trimmed.substring(index + "teacher".length())
                            .replaceFirst("^[:：=\\s]+", "").trim();
                    if (!rest.isEmpty()) {
                        return rest;
                    }
                }
                if (trimmed.startsWith("教师") || trimmed.startsWith("老师")) {
                    String rest = trimmed.replaceFirst("^(教师|老师)[:：=\\s]*", "").trim();
                    if (!rest.isEmpty()) {
                        return rest;
                    }
                }
            }
            return "";
        }
    }
}
