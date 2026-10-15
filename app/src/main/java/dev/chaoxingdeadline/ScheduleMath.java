package dev.chaoxingdeadline;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Weekly-timetable math, pure and Android-free so it can be unit tested.
 *
 * The timetable is a set of recurring weekly slots; real timestamps come from the
 * curriculum-level {@code firstWeekDate} (Monday 00:00 of week 1) plus the school's
 * section-time table. When the school published no section table, a 45-minute section
 * with 10-minute breaks starting at 08:00 is assumed.
 */
public final class ScheduleMath {
    /** Fallback section table: 45 min each, 10 min breaks, first section at 08:00. */
    private static final int DEFAULT_SECTION_MINUTES = 45;
    private static final int DEFAULT_BREAK_MINUTES = 10;
    private static final int DEFAULT_FIRST_START = 8 * 60;
    /** Upper bound for a single "a-b" range in a weeks string. */
    private static final int MAX_WEEKS_PER_RANGE = 40;

    private ScheduleMath() {
    }

    /** 1 = Monday … 7 = Sunday, matching the timetable's dayOfWeek. */
    public static int todayDayOfWeek(long now) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(now);
        int sundayFirst = calendar.get(Calendar.DAY_OF_WEEK);
        return sundayFirst == Calendar.SUNDAY ? 7 : sundayFirst - 1;
    }

    /** 1-based teaching week; 0 when {@code now} is before the semester started. */
    public static int currentWeek(long now, long firstWeekDate) {
        if (firstWeekDate <= 0L) {
            return 0;
        }
        long delta = now - firstWeekDate;
        if (delta < 0L) {
            return 0;
        }
        return (int) (delta / (7L * 24L * 60L * 60L * 1000L)) + 1;
    }

    /**
     * Whether {@code week} is listed by a weeks string. Accepts "3", "1-16",
     * "1-8,10-16", "1，3，5" and stray suffixes like "1-16周".
     */
    public static boolean weeksContains(String weeks, int week) {
        if (weeks == null || weeks.trim().isEmpty() || week <= 0) {
            return false;
        }
        String[] tokens = weeks.split("[,;，；]");
        for (String token : tokens) {
            String cleaned = digitsAndDash(token);
            if (cleaned.isEmpty()) {
                continue;
            }
            int dash = cleaned.indexOf('-');
            if (dash < 0) {
                if (parseIntOrZero(cleaned) == week) {
                    return true;
                }
                continue;
            }
            int from = parseIntOrZero(cleaned.substring(0, dash));
            int to = parseIntOrZero(cleaned.substring(dash + 1));
            if (from > 0 && to >= from && week >= from && week <= to) {
                return true;
            }
        }
        return false;
    }

    /**
     * Enumerates every week listed by a weeks string: {@code "3"} → [3],
     * {@code "1-8,10"} → [1…8, 10]. Empty or unparsable input yields an empty list
     * (which callers read as "every week", matching {@link #isActiveInWeek}).
     * Ranges are capped so a typo like {@code "1-9999"} cannot allocate wildly.
     */
    public static List<Integer> weeksList(String weeks) {
        if (weeks == null || weeks.trim().isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<Integer> result = new LinkedHashSet<>();
        for (String token : weeks.split("[,;，；]")) {
            String cleaned = digitsAndDash(token);
            if (cleaned.isEmpty()) {
                continue;
            }
            int dash = cleaned.indexOf('-');
            if (dash < 0) {
                int single = parseIntOrZero(cleaned);
                if (single > 0) {
                    result.add(single);
                }
                continue;
            }
            int from = parseIntOrZero(cleaned.substring(0, dash));
            int to = parseIntOrZero(cleaned.substring(dash + 1));
            if (from <= 0 || to < from) {
                continue;
            }
            for (int week = from; week <= Math.min(to, from + MAX_WEEKS_PER_RANGE - 1); week++) {
                result.add(week);
            }
        }
        return new ArrayList<>(result);
    }

    /**
     * Merges two weeks strings for one lesson, honouring the "empty means every week"
     * rule that the rest of the app uses (see {@link #isActiveInWeek}).
     *
     * A plain union would be wrong here: unioning "every week" with "7" yields "7", which
     * silently narrows a lesson that actually runs all term down to a single week. An
     * empty side therefore wins and the result stays empty.
     */
    public static String weeksMerge(String first, String second) {
        if (isEveryWeek(first) || isEveryWeek(second)) {
            return "";
        }
        return weeksUnion(first, second);
    }

    /** Whether a weeks string means "every week" (absent or blank). */
    public static boolean isEveryWeek(String weeks) {
        return weeks == null || weeks.trim().isEmpty();
    }

    /**
     * Union of two weeks strings, normalised: {@code "1-6"} + {@code "7"} → {@code "1-7"}.
     *
     * Needed because the mobile timetable endpoint answers one week at a time and stamps
     * each lesson with just that week (weeks fields read "7", "8", …). Reconstructing the
     * real week list means unioning what each sweep found.
     */
    public static String weeksUnion(String first, String second) {
        LinkedHashSet<Integer> all = new LinkedHashSet<>(weeksList(first));
        all.addAll(weeksList(second));
        return weeksNormalized(all);
    }

    /** Formats a week set as the compact "1-8,10-16" form the servers use. */
    public static String weeksNormalized(Collection<Integer> weeks) {
        if (weeks == null || weeks.isEmpty()) {
            return "";
        }
        ArrayList<Integer> sorted = new ArrayList<>(new LinkedHashSet<>(weeks));
        Collections.sort(sorted);
        StringBuilder out = new StringBuilder();
        int runStart = sorted.get(0);
        int runEnd = runStart;
        for (int i = 1; i <= sorted.size(); i++) {
            Integer week = i < sorted.size() ? sorted.get(i) : null;
            if (week != null && week == runEnd + 1) {
                runEnd = week;
                continue;
            }
            if (out.length() > 0) {
                out.append(',');
            }
            if (runStart == runEnd) {
                out.append(runStart);
            } else {
                out.append(runStart).append('-').append(runEnd);
            }
            if (week != null) {
                runStart = week;
                runEnd = week;
            }
        }
        return out.toString();
    }

    /**
     * Rewrites a section-time array into the two shapes {@link #sectionBounds} reads.
     *
     * Schools publish the bell table as {@code ["8:00-8:45", …]}, as
     * {@code [["8:00","8:45"], …]}, or as objects ({@code {"startTime":"8:00","endTime":"8:45"}}).
     * Only the first two are understood downstream, so object entries are flattened into the
     * first form here and everything else is passed through untouched — the timetable payload
     * is stored as text, so a school's own spelling survives whenever it is already usable.
     */
    public static String normalizeSectionTimes(JSONArray times) {
        if (times == null || times.length() == 0) {
            return "";
        }
        JSONArray normalized = new JSONArray();
        boolean changed = false;
        for (int i = 0; i < times.length(); i++) {
            Object entry = times.opt(i);
            if (entry instanceof JSONArray) {
                normalized.put(entry);
                continue;
            }
            if (entry instanceof org.json.JSONObject) {
                org.json.JSONObject json = (org.json.JSONObject) entry;
                String start = firstText(json, "startTime", "beginTime", "start", "begin", "start_time");
                String end = firstText(json, "endTime", "finishTime", "end", "end_time");
                if (!start.isEmpty() && !end.isEmpty()) {
                    normalized.put(start + "-" + end);
                    changed = true;
                    continue;
                }
            }
            String text = entry == null ? "" : String.valueOf(entry).trim();
            if (parseRange(text) != null) {
                normalized.put(text);
                continue;
            }
            // An unreadable entry is kept as-is rather than dropped: sectionBounds ignores
            // what it cannot parse and falls back per section, so the others still work.
            normalized.put(text);
        }
        if (!changed) {
            return times.toString();
        }
        return normalized.toString();
    }

    private static String firstText(org.json.JSONObject json, String... keys) {
        for (String key : keys) {
            String value = json.optString(key, "");
            if (!value.isEmpty() && !"null".equalsIgnoreCase(value)) {
                return value.trim();
            }
        }
        return "";
    }

    /** Section start as minutes since midnight; falls back to the default table. */
    public static int sectionStartMinutes(String sectionTimesJson, int section) {
        int[] bounds = sectionBounds(sectionTimesJson, section);
        return bounds[0];
    }

    /** Section end as minutes since midnight (exclusive-ish: the bell time). */
    public static int sectionEndMinutes(String sectionTimesJson, int section) {
        int[] bounds = sectionBounds(sectionTimesJson, section);
        return bounds[1];
    }

    public static long lessonStartAt(long firstWeekDate, int week, int dayOfWeek,
                                     int beginSection, String sectionTimesJson) {
        if (firstWeekDate <= 0L || week <= 0) {
            return 0L;
        }
        return atSection(firstWeekDate, week, dayOfWeek,
                sectionStartMinutes(sectionTimesJson, beginSection));
    }

    public static long lessonEndAt(long firstWeekDate, int week, int dayOfWeek,
                                   int beginSection, int sections, String sectionTimesJson) {
        if (firstWeekDate <= 0L || week <= 0) {
            return 0L;
        }
        int last = beginSection + Math.max(1, sections) - 1;
        return atSection(firstWeekDate, week, dayOfWeek,
                sectionEndMinutes(sectionTimesJson, last));
    }

    public static boolean isActiveInWeek(Lesson lesson, int week) {
        if (lesson == null || week <= 0) {
            return false;
        }
        // An empty weeks string means "every week" (some schools omit it).
        if (lesson.weeks == null || lesson.weeks.trim().isEmpty()) {
            return true;
        }
        return weeksContains(lesson.weeks, week);
    }

    /**
     * Lessons of the calendar day containing {@code when}, earliest first.
     *
     * Same as {@link #todayLessons} except the day is passed in rather than read from the
     * clock, which lets the widget preview tomorrow evening without a second code path.
     */
    public static List<Lesson> lessonsOn(List<Lesson> lessons, long when, long firstWeekDate) {
        int week = currentWeek(when, firstWeekDate);
        int day = todayDayOfWeek(when);
        ArrayList<Lesson> result = new ArrayList<>();
        if (lessons == null || week <= 0) {
            return result;
        }
        for (Lesson lesson : lessons) {
            if (lesson.dayOfWeek == day && isActiveInWeek(lesson, week)) {
                result.add(lesson);
            }
        }
        Collections.sort(result, Comparator.comparingInt(l -> l.beginSection));
        return result;
    }

    /** Today's lessons, earliest section first; empty when nothing is scheduled. */
    public static List<Lesson> todayLessons(List<Lesson> lessons, long now, long firstWeekDate) {
        int week = currentWeek(now, firstWeekDate);
        int day = todayDayOfWeek(now);
        ArrayList<Lesson> result = new ArrayList<>();
        if (lessons == null || week <= 0) {
            return result;
        }
        for (Lesson lesson : lessons) {
            if (lesson.dayOfWeek == day && isActiveInWeek(lesson, week)) {
                result.add(lesson);
            }
        }
        Collections.sort(result, Comparator.comparingInt(l -> l.beginSection));
        return result;
    }

    /**
     * The next lesson that has not started yet as of {@code now}, or null.
     *
     * Only the calendar day of {@code now} is considered: a reminder the evening before
     * makes no sense, and the widget/notification pair both work off "today". A lesson in
     * progress is skipped rather than returned, because every caller here is asking about
     * something still to come.
     */
    public static Lesson nextLesson(List<Lesson> lessons, long now, long firstWeekDate,
                                    String sectionTimesJson) {
        int week = currentWeek(now, firstWeekDate);
        if (week <= 0) {
            return null;
        }
        for (Lesson lesson : lessonsOn(lessons, now, firstWeekDate)) {
            long start = lessonStartAt(firstWeekDate, week, lesson.dayOfWeek,
                    lesson.beginSection, sectionTimesJson);
            if (start > now) {
                return lesson;
            }
        }
        return null;
    }

    /**
     * When to fire a reminder {@code leadMinutes} before the next lesson, or 0 when there
     * is none left today.
     *
     * A lesson already inside the lead window fires at once instead of being skipped: the
     * timetable may have just arrived, and a reminder that never comes because the class
     * was already close is worse than one a few minutes late. The one-second floor keeps
     * the alarm in the future, which {@link android.app.AlarmManager} requires.
     */
    public static long reminderTriggerAt(List<Lesson> lessons, long now, long firstWeekDate,
                                         String sectionTimesJson, int leadMinutes) {
        if (leadMinutes <= 0) {
            return 0L;
        }
        Lesson next = nextLesson(lessons, now, firstWeekDate, sectionTimesJson);
        if (next == null) {
            return 0L;
        }
        int week = currentWeek(now, firstWeekDate);
        long start = lessonStartAt(firstWeekDate, week, next.dayOfWeek,
                next.beginSection, sectionTimesJson);
        if (start <= 0L) {
            return 0L;
        }
        long fireAt = start - leadMinutes * 60_000L;
        return Math.max(fireAt, now + 1_000L);
    }

    /** The lesson in session right now, or null. */
    public static Lesson currentLesson(List<Lesson> lessons, long now, long firstWeekDate,
                                       String sectionTimesJson) {
        for (Lesson lesson : todayLessons(lessons, now, firstWeekDate)) {
            long start = lessonStartAt(firstWeekDate, currentWeek(now, firstWeekDate),
                    lesson.dayOfWeek, lesson.beginSection, sectionTimesJson);
            long end = lessonEndAt(firstWeekDate, currentWeek(now, firstWeekDate),
                    lesson.dayOfWeek, lesson.beginSection, lesson.sections, sectionTimesJson);
            if (start > 0L && now >= start && now < end) {
                return lesson;
            }
        }
        return null;
    }

    public static String formatSections(int beginSection, int sections) {
        int count = Math.max(1, sections);
        if (count == 1) {
            return "第 " + beginSection + " 节";
        }
        return "第 " + beginSection + "-" + (beginSection + count - 1) + " 节";
    }

    public static String formatMinutes(int minutes) {
        return String.format(Locale.CHINA, "%02d:%02d", minutes / 60, minutes % 60);
    }

    // -- internals --

    private static long atSection(long firstWeekDate, int week, int dayOfWeek, int minutes) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(firstWeekDate);
        calendar.add(Calendar.DAY_OF_MONTH, (week - 1) * 7 + (dayOfWeek - 1));
        calendar.set(Calendar.HOUR_OF_DAY, minutes / 60);
        calendar.set(Calendar.MINUTE, minutes % 60);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    /** @return {startMinutes, endMinutes}; index i of the table is section i+1. */
    private static int[] sectionBounds(String sectionTimesJson, int section) {
        int fallbackStart = DEFAULT_FIRST_START + (section - 1) * (DEFAULT_SECTION_MINUTES + DEFAULT_BREAK_MINUTES);
        int fallbackEnd = fallbackStart + DEFAULT_SECTION_MINUTES;
        if (sectionTimesJson == null || sectionTimesJson.trim().isEmpty()) {
            return new int[]{fallbackStart, fallbackEnd};
        }
        try {
            JSONArray array = new JSONArray(sectionTimesJson);
            if (array.length() == 0) {
                return new int[]{fallbackStart, fallbackEnd};
            }
            // Two shapes exist: ["8:00-8:45", …] and [["8:00","8:45"], …].
            JSONArray entry = array.optJSONArray(section - 1);
            if (entry != null && entry.length() >= 2) {
                int start = parseClock(entry.optString(0, ""));
                int end = parseClock(entry.optString(1, ""));
                if (start >= 0 && end >= 0) {
                    return new int[]{start, end};
                }
            } else {
                int[] bounds = parseRange(String.valueOf(array.opt(section - 1)));
                if (bounds != null) {
                    return bounds;
                }
            }
        } catch (Throwable ignored) {
        }
        return new int[]{fallbackStart, fallbackEnd};
    }

    /** Parses "8:00-8:45", "8:00~8:45", "08时00分-08时45分"-ish into minutes. */
    static int[] parseRange(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = text.replace('—', '-').replace('~', '-').replace('～', '-');
        int dash = cleaned.indexOf('-');
        if (dash < 0) {
            return null;
        }
        int start = parseClock(cleaned.substring(0, dash));
        int end = parseClock(cleaned.substring(dash + 1));
        if (start < 0 || end < 0) {
            return null;
        }
        return new int[]{start, end};
    }

    private static int parseClock(String text) {
        String digits = text.replaceAll("[^0-9:]", "");
        int colon = digits.indexOf(':');
        if (colon < 0) {
            return -1;
        }
        try {
            int hour = Integer.parseInt(digits.substring(0, colon));
            int minute = Integer.parseInt(digits.substring(colon + 1));
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                return -1;
            }
            return hour * 60 + minute;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static String digitsAndDash(String text) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= '0' && c <= '9') || c == '-') {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    private static int parseIntOrZero(String text) {
        try {
            return Integer.parseInt(text);
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
