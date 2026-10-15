package dev.chaoxingdeadline;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 调休日历：哪些日子放假（休）、哪些周末要补课（班）。
 *
 * <p><b>为什么不能推算</b>：中国的法定节假日安排由国务院办公厅每年单独发通知，
 * 没有可推导的规律——同样是国庆，有的年份放 7 天补 2 天班，有的年份连休 8 天；同样是周末，
 * 可能因为调休变成工作日。学校的校历还会再叠加自己的安排。所以只能显式记录。
 *
 * <p><b>数据是可编辑的，而且默认是空的</b>：本类不自带任何「我猜的」日期。
 * 一个错的调休标注比没有标注更糟——学生可能照着错的安排去上课。因此：
 * <ul>
 *   <li>没有数据的年份一律**不标**，月历上什么都不显示；</li>
 *   <li>用户在「设置 → 管理 → 调休日历」里按 {@code 2026-10-01=休} 一行一条填写，
 *       抄自学校校历或国务院通知即可；</li>
 *   <li>数据存在应用私有 preferences 里，随时可改，不需要重新打包。</li>
 * </ul>
 *
 * <p>格式：每行 {@code yyyy-MM-dd=休} 或 {@code yyyy-MM-dd=班}，也接受
 * {@code 2026-10-01~2026-10-08=休} 的区间写法。{@code #} 开头为注释。
 */
public final class HolidayCalendar {
    /** 一天的性质。 */
    public static final int NORMAL = 0;
    /** 放假（不上课）。 */
    public static final int REST = 1;
    /** 调休上班/补课。 */
    public static final int WORK = 2;

    private static final String PREFS = "holiday_calendar";
    private static final String KEY_SPEC = "spec";
    /** 一条区间最多展开多少天，避免手滑写出超长区间。 */
    private static final int MAX_RANGE_DAYS = 40;

    /** 解析后的表：dayStart（当天 00:00）→ 性质。解析后缓存，写入时失效。 */
    private static volatile Map<Long, Integer> cache;
    private static volatile String cachedSpec;

    private HolidayCalendar() {
    }

    public static String spec(Context context) {
        return prefs(context).getString(KEY_SPEC, "");
    }

    /** 写入用户在设置页填的安排；空串表示清空（回到「什么都不标」）。 */
    public static void setSpec(Context context, String spec) {
        String value = spec == null ? "" : spec.trim();
        prefs(context).edit().putString(KEY_SPEC, value).apply();
        cachedSpec = null;
        cache = null;
    }

    /** 这一天是假日、补课日，还是普通日子。 */
    public static int kindOf(Context context, long dayStartMillis) {
        Integer kind = table(context).get(WeekGrouping.dayStart(dayStartMillis));
        return kind == null ? NORMAL : kind;
    }

    /** 是否放假（不上课）。 */
    public static boolean isRest(Context context, long dayStartMillis) {
        return kindOf(context, dayStartMillis) == REST;
    }

    /**
     * 是否是补课日：标为「班」且落在周末。
     *
     * 工作日的调休上班对课表没影响——课表本来按周一到周五排，标出来只是噪音。
     */
    public static boolean isMakeupWorkday(Context context, long dayStartMillis) {
        if (kindOf(context, dayStartMillis) != WORK) {
            return false;
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(dayStartMillis);
        int day = calendar.get(Calendar.DAY_OF_WEEK);
        return day == Calendar.SATURDAY || day == Calendar.SUNDAY;
    }

    /** 「休」「班」小标；没有安排时返回空串。 */
    public static String labelOf(Context context, long dayStartMillis) {
        switch (kindOf(context, dayStartMillis)) {
            case REST:
                return "休";
            case WORK:
                return isMakeupWorkday(context, dayStartMillis) ? "班" : "";
            default:
                return "";
        }
    }

    /** 是否有任何数据；没有时月历不显示这一行说明。 */
    public static boolean hasData(Context context) {
        return !table(context).isEmpty();
    }

    /** 当月假期摘要，如「休 8 天 · 补课 2 天」；无则空串。 */
    public static String monthSummary(Context context, int year, int month) {
        Map<Long, Integer> days = table(context);
        if (days.isEmpty()) {
            return "";
        }
        int restDays = 0;
        int workDays = 0;
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(year, month - 1, 1, 0, 0, 0);
        int daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH);
        for (int day = 1; day <= daysInMonth; day++) {
            calendar.set(year, month - 1, day, 0, 0, 0);
            long start = calendar.getTimeInMillis();
            Integer kind = days.get(start);
            if (kind == null) {
                continue;
            }
            if (kind == REST) {
                restDays++;
            } else if (isMakeupWorkday(context, start)) {
                workDays++;
            }
        }
        StringBuilder summary = new StringBuilder();
        if (restDays > 0) {
            summary.append("本月休 ").append(restDays).append(" 天");
        }
        if (workDays > 0) {
            if (summary.length() > 0) {
                summary.append(" · ");
            }
            summary.append("补课 ").append(workDays).append(" 天");
        }
        return summary.toString();
    }

    /** 解析并缓存；规格没变就直接复用。 */
    private static Map<Long, Integer> table(Context context) {
        String spec = spec(context);
        if (cache != null && spec.equals(cachedSpec)) {
            return cache;
        }
        Map<Long, Integer> parsed = parse(spec);
        cache = parsed;
        cachedSpec = spec;
        return parsed;
    }

    /** 解析用户填写的规格；无法识别的行跳过，不影响其它行。 */
    static Map<Long, Integer> parse(String spec) {
        Map<Long, Integer> result = new HashMap<>();
        if (spec == null || spec.trim().isEmpty()) {
            return result;
        }
        for (String rawLine : spec.split("[\n\r;；]+")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String rangePart = line.substring(0, eq).trim();
            String kindPart = line.substring(eq + 1).trim();
            Integer kind = kindOfLabel(kindPart);
            if (kind == null) {
                continue;
            }
            for (long day : expandRange(rangePart)) {
                result.put(day, kind);
            }
        }
        return result;
    }

    private static Integer kindOfLabel(String label) {
        if (label.isEmpty()) {
            return null;
        }
        // 「休」/「假」/「rest」都算放假；「班」/「work」算补课。
        if (label.startsWith("休") || label.startsWith("假")
                || label.equalsIgnoreCase("rest") || label.equalsIgnoreCase("off")) {
            return REST;
        }
        if (label.startsWith("班") || label.startsWith("课")
                || label.equalsIgnoreCase("work")) {
            return WORK;
        }
        return null;
    }

    /** {@code 2026-10-01} 或 {@code 2026-10-01~2026-10-08} → 每天的 00:00。 */
    private static List<Long> expandRange(String text) {
        ArrayList<Long> days = new ArrayList<>();
        if (text.isEmpty()) {
            return days;
        }
        String[] bounds = text.split("[~～—]");
        long from = parseDate(bounds[0].trim());
        if (from <= 0L) {
            return days;
        }
        long to = bounds.length > 1 ? parseDate(bounds[1].trim()) : from;
        if (to < from) {
            to = from;
        }
        long dayMillis = 24L * 60L * 60L * 1000L;
        for (int i = 0; i < MAX_RANGE_DAYS && from + i * dayMillis <= to; i++) {
            days.add(from + i * dayMillis);
        }
        return days;
    }

    /**
     * {@code 2026-10-01} → 当天 00:00；解析不了返回 0。
     *
     * 月与日都要自己校验范围，因为 {@link Calendar} 是**宽松**的：{@code set(2026, 12, 99)}
     * 不报错，而是悄悄滚成 2027 年的某天。那样一个手滑的日期就会被当成真实假期标出来，
     * 正是这里最不能接受的错误。
     */
    private static long parseDate(String text) {
        if (!text.matches("\\d{4}-\\d{1,2}-\\d{1,2}")) {
            return 0L;
        }
        try {
            String[] parts = text.split("-");
            int year = Integer.parseInt(parts[0]);
            int month = Integer.parseInt(parts[1]);
            int day = Integer.parseInt(parts[2]);
            if (year < 2000 || year > 2100 || month < 1 || month > 12
                    || day < 1 || day > 31) {
                return 0L;
            }
            Calendar calendar = Calendar.getInstance();
            calendar.clear();
            calendar.set(year, month - 1, day, 0, 0, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            // 即便在范围内，日号也可能不存在（如 2 月 30 日）——宽松模式下会滚到下个月，
            // 所以回读一次确认仍在同一天。
            if (calendar.get(Calendar.YEAR) != year
                    || calendar.get(Calendar.MONTH) != month - 1
                    || calendar.get(Calendar.DAY_OF_MONTH) != day) {
                return 0L;
            }
            return calendar.getTimeInMillis();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /** 规格的可读摘要，用于设置项副标题：「已设置 N 天」。 */
    public static String summary(Context context) {
        int size = table(context).size();
        if (size == 0) {
            return "未设置；填写后月历会标出「休」与「班」";
        }
        Set<String> years = new LinkedHashSet<>();
        SimpleDateFormat format = new SimpleDateFormat("yyyy", Locale.ROOT);
        for (Long day : table(context).keySet()) {
            years.add(format.format(new java.util.Date(day)));
        }
        return "已设置 " + size + " 天（" + String.join("、", years) + "）";
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
