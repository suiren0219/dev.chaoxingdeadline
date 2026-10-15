package dev.chaoxingdeadline;

import android.app.Activity;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;
import java.util.List;

/**
 * Shared timetable renderers: the section-row × weekday-column grid used by the
 * 课程表 tab and by {@link ScheduleActivity}, plus the day-column helpers it needs.
 *
 * Extracted so the home screen can show the same grid the dedicated page shows
 * without a second implementation drifting from it. Pure view building — all data
 * comes in as arguments, and nothing here reads or writes storage.
 */
final class TimetableViews {
    static final String[] DAY_NAMES = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    private static final int CELL_HEIGHT_DP = 52;

    private TimetableViews() {
    }

    /** Colour for a lesson block: {soft background, saturated foreground}. */
    private static int[] courseColors(Activity activity, String name) {
        return UiTheme.courseColors(activity, name);
    }

    static int dp(Activity activity, int value) {
        return UiTheme.dp(activity, value);
    }

    /**
     * The weekly grid. Section rows down the left, Monday–Sunday across, multi-section
     * lessons spanning rows as colour blocks; today's column is tinted and the lesson
     * in session is outlined.
     */
    static View grid(Activity activity, TimetableGrid.Grid grid, int week, int currentWeek,
                     Lesson current, long firstWeekDate, String sectionTimes, long now,
                     LessonTapListener listener) {
        LinearLayout table = new LinearLayout(activity);
        table.setOrientation(LinearLayout.HORIZONTAL);
        table.setBackground(UiTheme.glassCard(activity, dp(activity, 18)));
        int pad = dp(activity, 4);
        table.setPadding(pad, pad, pad, pad);

        LinearLayout axis = new LinearLayout(activity);
        axis.setOrientation(LinearLayout.VERTICAL);
        axis.addView(axisCorner(activity), axisParams(activity));
        for (int section = 1; section <= grid.rows; section++) {
            axis.addView(axisLabel(activity, section, sectionTimes), axisParams(activity));
        }
        table.addView(axis, new LinearLayout.LayoutParams(dp(activity, 28), -2));

        int todayDay = ScheduleMath.todayDayOfWeek(now);
        for (int day = 1; day <= 7; day++) {
            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);
            boolean isToday = currentWeek > 0 && week == currentWeek && day == todayDay;
            column.addView(dayHeader(activity, day, week, firstWeekDate, isToday),
                    headerParams(activity));
            for (TimetableGrid.Cell cell : grid.column(day)) {
                if (cell.isBlock()) {
                    column.addView(block(activity, cell, current, week, currentWeek,
                            firstWeekDate, sectionTimes, now, listener), cellParams(activity, cell));
                } else {
                    column.addView(emptyCell(activity, isToday), emptyParams(activity));
                }
            }
            table.addView(column, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        return table;
    }

    private static View axisCorner(Activity activity) {
        TextView view = new TextView(activity);
        view.setText("节");
        view.setTextSize(9);
        view.setTextColor(UiTheme.muted(activity));
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private static View axisLabel(Activity activity, int section, String sectionTimes) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        TextView number = new TextView(activity);
        number.setText(String.valueOf(section));
        number.setTextSize(11);
        number.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        number.setTextColor(UiTheme.muted(activity));
        number.setGravity(Gravity.CENTER);
        box.addView(number, new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView clock = new TextView(activity);
        clock.setText(ScheduleMath.formatMinutes(
                ScheduleMath.sectionStartMinutes(sectionTimes, section)));
        clock.setTextSize(8);
        clock.setTextColor(UiTheme.muted(activity));
        clock.setGravity(Gravity.CENTER);
        box.addView(clock, new LinearLayout.LayoutParams(-1, 0, 1f));
        return box;
    }

    private static View dayHeader(Activity activity, int day, int week, long firstWeekDate,
                                  boolean isToday) {
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER);
        if (isToday) {
            header.setBackground(UiTheme.glassFill(activity, UiTheme.accent(activity),
                    dp(activity, 9)));
        }
        TextView name = new TextView(activity);
        name.setText(DAY_NAMES[day - 1]);
        name.setTextSize(10);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        name.setTextColor(isToday ? Color.WHITE : UiTheme.muted(activity));
        name.setGravity(Gravity.CENTER);
        header.addView(name, new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView date = new TextView(activity);
        date.setText(isToday ? "今天" : dateLabel(firstWeekDate, week, day));
        date.setTextSize(8);
        date.setTextColor(isToday ? Color.WHITE : UiTheme.muted(activity));
        date.setGravity(Gravity.CENTER);
        header.addView(date, new LinearLayout.LayoutParams(-1, 0, 1f));
        return header;
    }

    private static View emptyCell(Activity activity, boolean isToday) {
        View view = new View(activity);
        view.setBackgroundColor(isToday ? UiTheme.todayTint(activity) : UiTheme.divider(activity));
        return view;
    }

    private static View block(Activity activity, TimetableGrid.Cell cell, Lesson current,
                              int week, int currentWeek, long firstWeekDate, String sectionTimes,
                              long now, LessonTapListener listener) {
        Lesson lesson = cell.lesson;
        int[] colors = courseColors(activity, lesson.name);
        boolean isCurrent = current != null && current.id != null && current.id.equals(lesson.id);
        boolean isPast = false;
        if (currentWeek > 0 && week == currentWeek) {
            long end = ScheduleMath.lessonEndAt(firstWeekDate, currentWeek, lesson.dayOfWeek,
                    lesson.beginSection, lesson.sections, sectionTimes);
            isPast = end > 0L && now >= end;
        }

        LinearLayout block = new LinearLayout(activity);
        block.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 3);
        block.setPadding(pad, pad, pad, pad);
        block.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        int radius = dp(activity, 7);
        block.setBackground(isCurrent
                ? UiTheme.rounded(activity, colors[0], radius, UiTheme.accent(activity))
                : UiTheme.fillOnly(activity, colors[0], radius));
        block.setAlpha(isPast ? 0.55f : 1f);
        if (listener != null) {
            block.setOnClickListener(v -> listener.onLessonTap(lesson));
        }

        TextView name = new TextView(activity);
        name.setText(lesson.name);
        name.setTextSize(9);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        name.setTextColor(colors[1]);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER_HORIZONTAL);
        block.addView(name, new LinearLayout.LayoutParams(-1, 0, 1f));

        String meta = lesson.location != null && !lesson.location.isEmpty()
                ? lesson.location : (lesson.teacher == null ? "" : lesson.teacher);
        if (cell.span >= 2 && !meta.isEmpty()) {
            TextView metaView = new TextView(activity);
            metaView.setText(meta);
            metaView.setTextSize(8);
            metaView.setTextColor(colors[1]);
            metaView.setGravity(Gravity.CENTER_HORIZONTAL);
            metaView.setSingleLine();
            metaView.setEllipsize(TextUtils.TruncateAt.END);
            block.addView(metaView, new LinearLayout.LayoutParams(-1, 0, 1f));
        }
        return block;
    }

    static String dateLabel(long firstWeekDate, int week, int day) {
        if (firstWeekDate <= 0L) {
            return "";
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(firstWeekDate);
        calendar.add(Calendar.DAY_OF_MONTH, (week - 1) * 7 + (day - 1));
        return (calendar.get(Calendar.MONTH) + 1) + "/" + calendar.get(Calendar.DAY_OF_MONTH);
    }

    static String weekRangeLabel(long firstWeekDate, int week) {
        if (firstWeekDate <= 0L) {
            return "开学时间未知";
        }
        return dateLabel(firstWeekDate, week, 1) + " – " + dateLabel(firstWeekDate, week, 7);
    }

    /** Callback so callers decide what a tap does (dialog, page, …). */
    interface LessonTapListener {
        void onLessonTap(Lesson lesson);
    }

    // -- layout params --

    private static LinearLayout.LayoutParams axisParams(Activity activity) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(activity, CELL_HEIGHT_DP));
        int one = dp(activity, 1);
        p.setMargins(one, one, one, one);
        return p;
    }

    private static LinearLayout.LayoutParams headerParams(Activity activity) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(activity, 38));
        int one = dp(activity, 1);
        p.setMargins(one, one, one, dp(activity, 2));
        return p;
    }

    private static LinearLayout.LayoutParams emptyParams(Activity activity) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(activity, CELL_HEIGHT_DP));
        int one = dp(activity, 1);
        p.setMargins(one, one, one, one);
        return p;
    }

    private static LinearLayout.LayoutParams cellParams(Activity activity, TimetableGrid.Cell cell) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,
                cell.span * dp(activity, CELL_HEIGHT_DP));
        int one = dp(activity, 1);
        p.setMargins(one, one, one, one);
        return p;
    }

    /** Lessons of {@code lessons} that run in {@code week}, for a caller-side check. */
    static int countInWeek(List<Lesson> lessons, int week) {
        int count = 0;
        for (Lesson lesson : lessons) {
            if (ScheduleMath.isActiveInWeek(lesson, week)) {
                count++;
            }
        }
        return count;
    }
}
