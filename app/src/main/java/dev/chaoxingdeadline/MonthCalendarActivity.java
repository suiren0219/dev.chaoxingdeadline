package dev.chaoxingdeadline;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 待办月历：整月截止分布一屏可见。每天一个格子带角标计数，点某天在下方展开当天待办。
 * 数据来自 DeadlineStore，纯本地读取；左右箭头切换月份。
 */
public final class MonthCalendarActivity extends BaseActivity {
    private static final String[] WEEK_HEADERS = {"日", "一", "二", "三", "四", "五", "六"};

    private int year;
    private int month;
    private long selectedDayStart;
    private TextView monthTitle;
    private LinearLayout gridContainer;
    private LinearLayout dayDetail;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applySystemBars();
        Calendar now = Calendar.getInstance();
        year = now.get(Calendar.YEAR);
        month = now.get(Calendar.MONTH) + 1;
        selectedDayStart = WeekGrouping.dayStart(System.currentTimeMillis());
        setContentView(buildContent());
    }

    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(UiTheme.glassBackground(this));
        root.setPadding(dp(16), statusBarHeight() + dp(8), dp(16), dp(16));

        // --- header: ‹ 年月 › ---
        LinearLayout header = hbox();
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = icon("←", 28, UiTheme.accent(this));
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(48)));
        TextView prev = icon("‹", 28, UiTheme.accent(this));
        prev.setOnClickListener(v -> shiftMonth(-1));
        header.addView(prev, new LinearLayout.LayoutParams(dp(44), dp(48)));
        monthTitle = text("", 19, true, UiTheme.text(this));
        monthTitle.setGravity(Gravity.CENTER);
        header.addView(monthTitle, new LinearLayout.LayoutParams(0, dp(48), 1f));
        TextView next = icon("›", 28, UiTheme.accent(this));
        next.setOnClickListener(v -> shiftMonth(1));
        header.addView(next, new LinearLayout.LayoutParams(dp(44), dp(48)));
        TextView today = icon("•", 24, UiTheme.accent(this));
        today.setOnClickListener(v -> jumpToToday());
        header.addView(today, new LinearLayout.LayoutParams(dp(44), dp(48)));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(8), 0, 0);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        // --- weekday header ---
        LinearLayout weekHeader = hbox();
        for (String label : WEEK_HEADERS) {
            TextView cell = text(label, 12, false, UiTheme.muted(this));
            cell.setGravity(Gravity.CENTER);
            weekHeader.addView(cell, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        content.addView(weekHeader, new LinearLayout.LayoutParams(-1, -2));

        // --- 课表同步：把整学期课表拉到本地，月历据此标出放假与补课 ---
        content.addView(timetableCard(), groupParams());

        // --- month grid ---
        gridContainer = new LinearLayout(this);
        gridContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(gridContainer, new LinearLayout.LayoutParams(-1, -2));

        // --- selected day detail ---
        dayDetail = new LinearLayout(this);
        dayDetail.setOrientation(LinearLayout.VERTICAL);
        content.addView(dayDetail, new LinearLayout.LayoutParams(-1, -2));

        render();
        return root;
    }

    private void shiftMonth(int delta) {
        int[] moved = delta < 0 ? MonthGrid.previous(year, month) : MonthGrid.next(year, month);
        year = moved[0];
        month = moved[1];
        render();
    }

    private void jumpToToday() {
        Calendar now = Calendar.getInstance();
        year = now.get(Calendar.YEAR);
        month = now.get(Calendar.MONTH) + 1;
        selectedDayStart = WeekGrouping.dayStart(System.currentTimeMillis());
        render();
    }

    private void render() {
        monthTitle.setText(MonthGrid.monthLabel(year, month));
        List<DeadlineItem> items = new DeadlineStore(this).activeItems();
        long now = System.currentTimeMillis();

        // count only still-live deadlines per day; submitted/revoked/past are noise here.
        Map<Long, Integer> counts = new HashMap<>();
        Map<Long, List<DeadlineItem>> byDay = new HashMap<>();
        for (DeadlineItem item : items) {
            if (item == null || item.submitted || item.dueAt <= now || item.vanishedAt > 0L) {
                continue;
            }
            long dayStart = WeekGrouping.dayStart(item.dueAt);
            Integer count = counts.get(dayStart);
            counts.put(dayStart, count == null ? 1 : count + 1);
            List<DeadlineItem> bucket = byDay.get(dayStart);
            if (bucket == null) {
                bucket = new ArrayList<>();
                byDay.put(dayStart, bucket);
            }
            bucket.add(item);
        }

        renderGrid(counts, now);
        renderDetail(byDay.get(selectedDayStart), now);
    }

    private void renderGrid(Map<Long, Integer> counts, long now) {
        gridContainer.removeAllViews();
        long todayStart = WeekGrouping.dayStart(now);
        List<MonthGrid.Cell> cells = MonthGrid.build(year, month, counts);
        for (int row = 0; row < cells.size(); row += MonthGrid.COLUMNS) {
            LinearLayout line = hbox();
            for (int col = 0; col < MonthGrid.COLUMNS; col++) {
                final MonthGrid.Cell cell = cells.get(row + col);
                line.addView(dayCell(cell, cell.dayStart == todayStart,
                        cell.dayStart == selectedDayStart), cellParams());
            }
            gridContainer.addView(line, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private View dayCell(final MonthGrid.Cell cell, boolean isToday, boolean isSelected) {
        TextView view = new TextView(this);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(2), dp(6), dp(2), dp(6));
        if (cell.isBlank()) {
            view.setText("");
            return view;
        }
        // 调休标注：「休」放假、「班」周末补课。数据由用户按校历填写，没填就不标——
        // 一个错的调休标注比没有更糟，学生会照着错的安排去上课。
        String holiday = HolidayCalendar.labelOf(this, cell.dayStart);
        String label = String.valueOf(cell.dayOfMonth);
        if (!holiday.isEmpty()) {
            label += holiday;
        }
        if (cell.count > 0) {
            label += "\n" + cell.count;
        }
        view.setText(label);
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, cell.count > 0 ? 13 : 12);
        view.setLineSpacing(0f, 1.05f);
        int color;
        if (isSelected) {
            color = UiTheme.accent(this);
        } else if (isToday) {
            color = UiTheme.dangerText(this);
        } else if (!holiday.isEmpty()) {
            color = holiday.equals("休") ? UiTheme.accent(this) : UiTheme.text(this);
        } else if (cell.count > 0) {
            color = UiTheme.text(this);
        } else {
            color = UiTheme.muted(this);
        }
        view.setTextColor(color);
        view.setTypeface(null, isSelected || isToday
                ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        view.setBackground(UiTheme.rounded(this,
                isSelected ? UiTheme.sheet(this) : 0x00000000, dp(10), UiTheme.stroke(this)));
        view.setOnClickListener(v -> {
            selectedDayStart = cell.dayStart;
            render();
        });
        return view;
    }

    private LinearLayout.LayoutParams cellParams() {
        return new LinearLayout.LayoutParams(0, dp(52), 1f);
    }

    private void renderDetail(List<DeadlineItem> items, long now) {
        dayDetail.removeAllViews();
        if (selectedDayStart <= 0L) {
            return;
        }
        dayDetail.addView(sectionHeader(dayHeading()), groupParams());
        String holidayNote = holidayNote();
        if (!holidayNote.isEmpty()) {
            TextView note = text(holidayNote, 12, false, UiTheme.muted(this));
            note.setPadding(dp(4), 0, dp(4), dp(8));
            dayDetail.addView(note, new LinearLayout.LayoutParams(-1, -2));
        }
        LinearLayout card = card();
        if (items == null || items.isEmpty()) {
            card.addView(mutedRow("这一天没有待办。"));
        } else {
            items.sort((a, b) -> Long.compare(a.dueAt, b.dueAt));
            for (DeadlineItem item : items) {
                card.addView(detailRow(item, now));
            }
        }
        dayDetail.addView(card, groupParams());
    }

    private String dayHeading() {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(selectedDayStart);
        long diff = Math.round((selectedDayStart - WeekGrouping.dayStart(System.currentTimeMillis()))
                / (double) (24L * 60L * 60L * 1000L));
        String base = (calendar.get(Calendar.MONTH) + 1) + "月" + calendar.get(Calendar.DAY_OF_MONTH) + "日";
        if (diff == 0L) {
            return base + " · 今天";
        }
        if (diff == 1L) {
            return base + " · 明天";
        }
        return base;
    }

    private View detailRow(DeadlineItem item, long now) {
        LinearLayout row = hbox();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(14), dp(10));
        TextView dot = text("●", 11, false, DeadlineWidgetProvider.typeColor(this, item.type));
        row.addView(dot, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(item.title, 14, true, UiTheme.text(this));
        texts.addView(title, new LinearLayout.LayoutParams(-1, -2));
        String meta = (item.course == null || item.course.isEmpty() ? "" : item.course + " · ")
                + DateText.deadlineTime(item.dueAt) + " · " + WeekGrouping.countdown(item.dueAt, now);
        TextView sub = text(meta, 11, false, UiTheme.muted(this));
        texts.addView(sub, new LinearLayout.LayoutParams(-1, -2));
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        return row;
    }

    private View mutedRow(String value) {
        TextView view = text(value, 13, false, UiTheme.muted(this));
        view.setPadding(dp(14), dp(14), dp(14), dp(14));
        return view;
    }

    /**
     * 课表同步卡片：说明同步会把整学期课表拉到本地，以及月历据此能标出什么。
     *
     * 放在月历上方而不是藏在设置里，是因为「这周上不上课」正是看月历时会问的问题，
     * 而答案取决于课表和调休，所以入口就该在这里。
     */
    private View timetableCard() {
        LinearLayout card = card();
        LinearLayout row = hbox();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        DeadlineStore store = new DeadlineStore(this);
        boolean everFetched = store.scheduleUpdatedAt() > 0L;
        texts.addView(text("课表与调休", 14, true, UiTheme.text(this)),
                new LinearLayout.LayoutParams(-1, -2));
        String sub = everFetched
                ? "已有 " + store.allLessons().size() + " 门课的上课时间"
                + (HolidayCalendar.hasData(this) ? " · " + HolidayCalendar.summary(this) : " · 调休未设置")
                : "还没有课表数据，同步一次即可在月历上看到上课安排";
        texts.addView(text(sub, 11, false, UiTheme.muted(this)),
                new LinearLayout.LayoutParams(-1, -2));
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView action = text(everFetched ? "重新同步" : "同步课表", 13, true, UiTheme.accent(this));
        action.setPadding(dp(10), dp(6), dp(4), dp(6));
        action.setOnClickListener(v -> syncTimetable());
        row.addView(action, new LinearLayout.LayoutParams(-2, -2));

        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
        card.addView(divider());
        LinearLayout holidayRow = hbox();
        holidayRow.setGravity(Gravity.CENTER_VERTICAL);
        holidayRow.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout holidayTexts = new LinearLayout(this);
        holidayTexts.setOrientation(LinearLayout.VERTICAL);
        holidayTexts.addView(text("调休日历", 13, true, UiTheme.text(this)),
                new LinearLayout.LayoutParams(-1, -2));
        holidayTexts.addView(text(HolidayCalendar.summary(this), 11, false, UiTheme.muted(this)),
                new LinearLayout.LayoutParams(-1, -2));
        holidayRow.addView(holidayTexts, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView edit = text("设置", 13, true, UiTheme.accent(this));
        edit.setPadding(dp(10), dp(6), dp(4), dp(6));
        edit.setOnClickListener(v -> showHolidayDialog());
        holidayRow.addView(edit, new LinearLayout.LayoutParams(-2, -2));
        card.addView(holidayRow, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    /** 请求整学期课表（逐周取回，宿主侧串行且有间隔）。 */
    private void syncTimetable() {
        RefreshBridge.requestWholeSemester(this);
        int known = new DeadlineStore(this).scheduleMaxWeek();
        android.widget.Toast.makeText(this, known > 0
                ? "已请求整学期课表（约 " + known + " 周），稍后自动更新"
                : "已请求整学期课表，稍后自动更新",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    /**
     * 调休日历编辑器。
     *
     * 让用户自己填，是因为法定安排每年由国务院单独通知、且各校校历还会再叠加，
     * 内置一份「我猜的」日期只会把学生指错。留空即不标注。
     */
    private void showHolidayDialog() {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setText(HolidayCalendar.spec(this));
        input.setSingleLine(false);
        input.setMinLines(4);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setHint("2026-10-01~2026-10-08=休\n2026-10-10=班");
        int pad = dp(20);
        android.widget.FrameLayout wrapper = new android.widget.FrameLayout(this);
        wrapper.setPadding(pad, dp(8), pad, 0);
        wrapper.addView(input, new android.widget.FrameLayout.LayoutParams(-1, -2));
        new android.app.AlertDialog.Builder(this)
                .setTitle("调休日历")
                .setMessage("每行一条：日期=休 或 日期=班，也可写区间。\n"
                        + "抄自学校校历或国务院放假通知即可；留空则不标注。\n"
                        + "# 开头为注释。")
                .setView(wrapper)
                .setPositiveButton("保存", (dialog, which) -> {
                    HolidayCalendar.setSpec(this, input.getText().toString());
                    render();
                })
                .setNeutralButton("清空", (dialog, which) -> {
                    HolidayCalendar.setSpec(this, "");
                    render();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 选中日期是休是班，或空串。 */
    private String holidayNote() {
        switch (HolidayCalendar.kindOf(this, selectedDayStart)) {
            case HolidayCalendar.REST:
                return "这天的调休安排：放假（不上课）";
            case HolidayCalendar.WORK:
                return HolidayCalendar.isMakeupWorkday(this, selectedDayStart)
                        ? "这天的调休安排：补课，按工作日课表上课"
                        : "这天的调休安排：上班";
            default:
                return "";
        }
    }

    /** 一行分隔线，和设置页的观感一致。 */
    private View divider() {
        View line = new View(this);
        line.setBackgroundColor(UiTheme.stroke(this));
        line.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1)));
        return line;
    }

    private LinearLayout.LayoutParams groupParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, dp(12));
        return p;
    }
}
