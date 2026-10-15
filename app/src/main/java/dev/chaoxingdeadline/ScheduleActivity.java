package dev.chaoxingdeadline;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Calendar;
import java.util.List;

/**
 * 课表页：拾光风格的玻璃界面，三个板块 —— 今日 / 周 / 设置。
 *
 * 「周」是节次行 × 星期列的网格（多节课程跨行成色块、今天高亮、上课中描边）；
 * 「今日」是当天课程的纵向卡片流；「设置」只收课表自己的开关（全局设置仍在模块设置页，
 * 避免两份设置漂移）。玻璃观感照 NexioSchedule：柔和色底 + 半透明面板 + 细描边，
 * 用原生 drawable 实现，不引入第三方依赖，API 26 起表现一致。
 *
 * 数据来自 Hook 捕获后经广播落入本进程 lessons 表，纯本地读取；刷新通过
 * {@link RefreshBridge} 请求宿主（学习通进程）重新拉取。
 */
public final class ScheduleActivity extends BaseActivity {
    private static final int MAX_WEEK = 30;
    private static final long REFRESH_GUARD_MS = 10_000L;
    private static final String[] TAB_LABELS = {"今日", "周", "设置"};
    private static final int TAB_TODAY = 0;
    private static final int TAB_WEEK = 1;
    private static final int TAB_SETTINGS = 2;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Week the grid shows; <= 0 means "follow the current teaching week". */
    private int displayedWeek;
    private int selectedTab = TAB_TODAY;
    private String lastSignature;
    private long refreshBlockedUntil;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            reloadIfChanged();
            handler.postDelayed(this, 3000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applySystemBars();
        setContentView(buildContent());
    }

    @Override
    protected void onStart() {
        super.onStart();
        lastSignature = null; // a returning user always gets fresh data
        reloadIfChanged();
        handler.postDelayed(tick, 3000L);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(tick);
        super.onStop();
    }

    /** Rebuilds only when the stored timetable actually changed, so scrolling survives. */
    private void reloadIfChanged() {
        String signature = dataSignature();
        if (signature.equals(lastSignature)) {
            return;
        }
        lastSignature = signature;
        setContentView(buildContent());
    }

    /** Forces a rebuild even when the data signature is unchanged (week switches). */
    private void rebuild() {
        lastSignature = null;
        reloadIfChanged();
    }

    private String dataSignature() {
        DeadlineStore store = new DeadlineStore(this);
        List<Lesson> lessons = store.allLessons();
        int hash = 0;
        for (Lesson lesson : lessons) {
            hash = hash * 31 + (lesson.id == null ? 0 : lesson.id.hashCode());
        }
        return store.scheduleUpdatedAt() + ":" + lessons.size() + ":" + hash
                + ":" + store.scheduleFirstWeekDate();
    }

    private View buildContent() {
        FrameLayout root = new FrameLayout(this);
        // The glass wash sits behind everything; panels above it are translucent.
        root.addView(GlassUi.backdrop(this), new FrameLayout.LayoutParams(-1, -1));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(12), statusBarHeight() + dp(8), dp(12), dp(16));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        page.addView(titleBar(), new LinearLayout.LayoutParams(-1, dp(48)));
        page.addView(GlassUi.tabStrip(this, TAB_LABELS, selectedTab,
                index -> {
                    if (index != selectedTab) {
                        selectedTab = index;
                        rebuild();
                    }
                }), new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(10), 0, 0);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        DeadlineStore store = new DeadlineStore(this);
        long now = System.currentTimeMillis();
        long firstWeekDate = store.scheduleFirstWeekDate();
        String sectionTimes = store.scheduleSectionTimes();
        int currentWeek = ScheduleMath.currentWeek(now, firstWeekDate);
        List<Lesson> lessons = store.allLessons();
        int week = displayedWeek > 0 ? displayedWeek : Math.max(1, currentWeek);
        List<Lesson> today = ScheduleMath.todayLessons(lessons, now, firstWeekDate);
        Lesson current = ScheduleMath.currentLesson(today, now, firstWeekDate, sectionTimes);

        if (selectedTab == TAB_SETTINGS) {
            buildSettingsTab(content);
            return root;
        }
        if (lessons.isEmpty()) {
            content.addView(emptyCard(), groupParams());
            return root;
        }
        if (selectedTab == TAB_TODAY) {
            buildTodayTab(content, today, current, currentWeek, firstWeekDate, sectionTimes, now);
        } else {
            content.addView(weekSwitcher(week, currentWeek, firstWeekDate), groupParams());
            TimetableGrid.Grid grid = TimetableGrid.build(lessons, week);
            if (grid.rows == 0) {
                content.addView(emptyWeekCard(week), groupParams());
            } else {
                content.addView(gridView(grid, week, currentWeek, current,
                        firstWeekDate, sectionTimes, now), groupParams());
            }
        }

        long updatedAt = store.scheduleUpdatedAt();
        if (updatedAt > 0L) {
            // The caveat belongs next to the data it describes: timetables sync from the
            // school's system, so they can lag behind a room change or a cancellation.
            TextView stamp = text("更新于 " + DateText.deadlineTime(updatedAt)
                    + " · 来自学校教务系统同步，可能有延迟，请以学习通内课表为准",
                    11, false, UiTheme.muted(this));
            stamp.setPadding(dp(4), dp(2), dp(4), dp(8));
            content.addView(stamp, new LinearLayout.LayoutParams(-1, -2));
        }
        return root;
    }

    // -- 今日 --

    /** Today's lessons as a vertical card flow, earliest first, in-session called out. */
    private void buildTodayTab(LinearLayout content, List<Lesson> today, Lesson current,
                               int currentWeek, long firstWeekDate, String sectionTimes, long now) {
        int day = ScheduleMath.todayDayOfWeek(now);
        String head = TimetableViews.DAY_NAMES[day - 1] + " · " + (currentWeek > 0 ? "第 " + currentWeek + " 周" : "寒暑假")
                + " · " + today.size() + " 节课";
        content.addView(GlassUi.sectionLabel(this, head), new LinearLayout.LayoutParams(-1, -2));

        if (today.isEmpty()) {
            LinearLayout panel = GlassUi.panel(this);
            panel.setPadding(dp(20), dp(22), dp(20), dp(22));
            panel.addView(GlassUi.title(this, "今天没课", 16), new LinearLayout.LayoutParams(-1, -2));
            TextView body = GlassUi.body(this, currentWeek > 0
                    ? "今天没有排课，好好休息。"
                    : "还没有获取到课表：打开学习通的「课表」页面即可自动捕获。", 13);
            body.setPadding(0, dp(8), 0, 0);
            panel.addView(body, new LinearLayout.LayoutParams(-1, -2));
            content.addView(panel, groupParams());
            return;
        }
        for (Lesson lesson : today) {
            content.addView(todayCard(lesson, current, currentWeek, firstWeekDate,
                    sectionTimes, now), groupParams());
        }
    }

    private View todayCard(Lesson lesson, Lesson current, int currentWeek, long firstWeekDate,
                           String sectionTimes, long now) {
        LinearLayout panel = GlassUi.panel(this);
        panel.setPadding(dp(14), dp(14), dp(16), dp(14));

        long start = currentWeek > 0 ? ScheduleMath.lessonStartAt(firstWeekDate, currentWeek,
                lesson.dayOfWeek, lesson.beginSection, sectionTimes) : 0L;
        long end = currentWeek > 0 ? ScheduleMath.lessonEndAt(firstWeekDate, currentWeek,
                lesson.dayOfWeek, lesson.beginSection, lesson.sections, sectionTimes) : 0L;
        boolean isCurrent = current != null && current.id != null && current.id.equals(lesson.id);
        boolean isPast = end > 0L && now >= end;
        int[] colors = courseColors(lesson.name);

        // Colour strip on the left, like the widget card.
        LinearLayout body = GlassUi.row(this);
        View strip = new View(this);
        strip.setBackground(UiTheme.fillOnly(this, colors[1], dp(2)));
        LinearLayout.LayoutParams stripParams = new LinearLayout.LayoutParams(dp(4), -1);
        stripParams.setMargins(0, 0, dp(12), 0);
        body.addView(strip, stripParams);

        LinearLayout texts = GlassUi.column(this);
        TextView name = GlassUi.title(this, lesson.name == null || lesson.name.isEmpty()
                ? "未命名课程" : lesson.name, 16);
        name.setAlpha(isPast && !isCurrent ? 0.6f : 1f);
        texts.addView(name, new LinearLayout.LayoutParams(-1, -2));

        String range = (start > 0L ? DateText.clock(start) : "--:--")
                + "–" + (end > 0L ? DateText.clock(end) : "--:--");
        TextView time = GlassUi.body(this, range + " · "
                + ScheduleMath.formatSections(lesson.beginSection, lesson.sections), 13);
        time.setPadding(0, dp(4), 0, 0);
        texts.addView(time, new LinearLayout.LayoutParams(-1, -2));

        String label = lesson.label();
        if (!label.isEmpty()) {
            TextView place = GlassUi.body(this, label, 12);
            place.setPadding(0, dp(2), 0, 0);
            texts.addView(place, new LinearLayout.LayoutParams(-1, -2));
        }
        body.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        String chipText = isCurrent ? "上课中" : (isPast ? "已结束" : "");
        if (!chipText.isEmpty()) {
            TextView chip = GlassUi.chip(this, chipText,
                    isCurrent ? UiTheme.accent(this) : UiTheme.muted(this), Color.WHITE);
            LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(-2, -2);
            chipParams.setMargins(dp(8), 0, 0, 0);
            body.addView(chip, chipParams);
        }
        panel.addView(body, new LinearLayout.LayoutParams(-1, -2));
        panel.setOnClickListener(v -> showLessonDialog(lesson));
        return panel;
    }

    // -- 课表设置 --

    /**
     * Only the timetable's own toggles live here. Anything global stays in the module's
     * own settings screen, so the two screens can never disagree about one flag.
     */
    private void buildSettingsTab(LinearLayout content) {
        content.addView(GlassUi.sectionLabel(this, "上课增强"), new LinearLayout.LayoutParams(-1, -2));
        LinearLayout enhance = GlassUi.panel(this);
        enhance.addView(GlassUi.switchRow(this, "上课期间加强检测",
                "上课前 15 分钟起，对当期课程的课堂活动每 2 分钟刷新一次（签到、投票不错过）",
                AppSettings.classSessionBoostEnabled(this),
                (buttonView, checked) -> AppSettings.setClassSessionBoostEnabled(this, checked)),
                new LinearLayout.LayoutParams(-1, -2));
        content.addView(enhance, groupParams());

        content.addView(GlassUi.sectionLabel(this, "数据"), new LinearLayout.LayoutParams(-1, -2));
        LinearLayout data = GlassUi.panel(this);
        DeadlineStore store = new DeadlineStore(this);
        int total = store.allLessons().size();
        TextView summary = GlassUi.body(this, "已保存 " + total + " 门课的上课时间"
                + (store.scheduleUpdatedAt() > 0L
                ? "，更新于 " + DateText.deadlineTime(store.scheduleUpdatedAt()) : ""), 12);
        summary.setPadding(dp(16), dp(12), dp(16), dp(4));
        data.addView(summary, new LinearLayout.LayoutParams(-1, -2));

        View refresh = GlassUi.actionRow(this, "重新获取课表",
                "向学习通请求当前周的课表；已取回的周次会保留");
        refresh.setOnClickListener(v -> requestRefresh());
        data.addView(refresh, new LinearLayout.LayoutParams(-1, -2));

        View allWeeks = GlassUi.actionRow(this, "获取整学期课表",
                store.scheduleMaxWeek() > 0
                        ? "逐周取回整学期（共 " + store.scheduleMaxWeek() + " 周），需要一点时间"
                        : "逐周取回整学期，需要一点时间");
        allWeeks.setOnClickListener(v -> requestWholeSemester());
        data.addView(allWeeks, new LinearLayout.LayoutParams(-1, -2));
        content.addView(data, groupParams());

        content.addView(GlassUi.sectionLabel(this, "跳转"), new LinearLayout.LayoutParams(-1, -2));
        LinearLayout links = GlassUi.panel(this);
        View widget = GlassUi.actionRow(this, "桌面小组件设置",
                "今日课程小组件的外观与显示范围");
        widget.setOnClickListener(v -> {
            try {
                startActivity(new Intent(this, SettingsActivity.class));
            } catch (Throwable ignored) {
            }
        });
        links.addView(widget, new LinearLayout.LayoutParams(-1, -2));
        content.addView(links, groupParams());
    }

    /** Asks the host for the whole semester, in one request. */
    private void requestWholeSemester() {
        long now = System.currentTimeMillis();
        if (now < refreshBlockedUntil) {
            Toast.makeText(this, "刷新刚请求过，稍候几秒", Toast.LENGTH_SHORT).show();
            return;
        }
        refreshBlockedUntil = now + REFRESH_GUARD_MS;
        // One broadcast; the host sweeps week by week with a pause between requests and
        // stops at the curriculum's own maxWeek, so the endpoint never sees a burst.
        RefreshBridge.requestWholeSemester(this);
        int known = new DeadlineStore(this).scheduleMaxWeek();
        Toast.makeText(this, known > 0
                ? "已请求整学期课表（约 " + known + " 周），稍后自动更新"
                : "已请求整学期课表，稍后自动更新", Toast.LENGTH_SHORT).show();
    }

    // -- chrome --

    private View titleBar() {
        LinearLayout top = hbox();
        TextView back = icon("←", 26, UiTheme.accent(this));
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(40), -1));
        TextView title = text("课表", 20, true, UiTheme.text(this));
        title.setGravity(Gravity.CENTER);
        top.addView(title, new LinearLayout.LayoutParams(0, -1, 1f));
        TextView refresh = icon("⟳", 22, UiTheme.accent(this));
        refresh.setOnClickListener(v -> requestRefresh());
        top.addView(refresh, new LinearLayout.LayoutParams(dp(40), -1));
        return top;
    }

    private View weekSwitcher(int week, int currentWeek, long firstWeekDate) {
        LinearLayout bar = hbox();
        bar.setPadding(dp(6), dp(6), dp(6), dp(6));
        bar.setBackground(UiTheme.glassCard(this, dp(14)));

        TextView prev = icon("‹", 26, UiTheme.accent(this));
        prev.setOnClickListener(v -> {
            displayedWeek = Math.max(1, week - 1);
            rebuild();
        });
        bar.addView(prev, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);
        TextView weekLabel = text("第 " + week + " 周", 15, true, UiTheme.text(this));
        weekLabel.setGravity(Gravity.CENTER);
        center.addView(weekLabel, new LinearLayout.LayoutParams(-1, -2));
        String range = weekRangeLabel(firstWeekDate, week);
        if (currentWeek > 0 && week == currentWeek) {
            range = range + " · 本周";
        }
        TextView dateLabel = text(range, 11, false, UiTheme.muted(this));
        dateLabel.setGravity(Gravity.CENTER);
        center.addView(dateLabel, new LinearLayout.LayoutParams(-1, -2));
        bar.addView(center, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView next = icon("›", 26, UiTheme.accent(this));
        next.setOnClickListener(v -> {
            displayedWeek = Math.min(MAX_WEEK, week + 1);
            rebuild();
        });
        bar.addView(next, new LinearLayout.LayoutParams(dp(40), dp(40)));

        if (currentWeek > 0 && week != currentWeek) {
            TextView jump = text("本周", 13, true, UiTheme.accent(this));
            jump.setGravity(Gravity.CENTER);
            jump.setOnClickListener(v -> {
                displayedWeek = currentWeek;
                rebuild();
            });
            bar.addView(jump, new LinearLayout.LayoutParams(dp(44), -1));
        }
        return bar;
    }

    // -- grid --

    private View gridView(TimetableGrid.Grid grid, int week, int currentWeek, Lesson current,
                          long firstWeekDate, String sectionTimes, long now) {
        return TimetableViews.grid(this, grid, week, currentWeek, current,
                firstWeekDate, sectionTimes, now, this::showLessonDialog);
    }

    // -- empty states --

    private View emptyCard() {
        LinearLayout card = card();
        card.setPadding(dp(20), dp(24), dp(20), dp(24));
        TextView title = text("还没有获取到课表", 16, true, UiTheme.text(this));
        card.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView body = text("打开学习通的「课表」页面，模块会自动捕获页面数据；"
                + "随后每次刷新也会主动拉取。", 13, false, UiTheme.muted(this));
        body.setPadding(0, dp(8), 0, dp(16));
        body.setLineSpacing(0f, 1.25f);
        card.addView(body, new LinearLayout.LayoutParams(-1, -2));
        card.addView(refreshButton("刷新课表", 0), buttonParams());
        return card;
    }

    private View emptyWeekCard(int week) {
        LinearLayout card = card();
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        boolean fetched = new DeadlineStore(this).hasFetchedWeek(week);
        // A week can look empty for two very different reasons, and telling them apart
        // is the difference between "your timetable has a gap" and "we never asked".
        TextView title = text(fetched ? "第 " + week + " 周没有课" : "第 " + week + " 周还没有数据",
                15, true, UiTheme.text(this));
        card.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView body = text(fetched
                ? "这一周的课表已经取回，周内没有安排课程。"
                : "点下面按钮向学习通请求这一周的课表；已取回的周次会保留，不会被覆盖。",
                12, false, UiTheme.muted(this));
        body.setPadding(0, dp(6), 0, dp(14));
        body.setLineSpacing(0f, 1.25f);
        card.addView(body, new LinearLayout.LayoutParams(-1, -2));
        if (!fetched) {
            card.addView(refreshButton("获取第 " + week + " 周", week), buttonParams());
        }
        return card;
    }

    private View refreshButton(String label, int week) {
        TextView button = text(label, 14, true, Color.WHITE);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(22), dp(10), dp(22), dp(10));
        button.setBackground(UiTheme.fillOnly(this, UiTheme.accent(this), dp(12)));
        button.setOnClickListener(v -> {
            if (week > 0) {
                RefreshBridge.requestWeek(this, week);
                Toast.makeText(this, "已请求第 " + week + " 周课表", Toast.LENGTH_SHORT).show();
            } else {
                requestRefresh();
            }
        });
        return button;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.gravity = Gravity.CENTER_HORIZONTAL;
        return p;
    }

    // -- actions --

    private void requestRefresh() {
        long now = System.currentTimeMillis();
        if (now < refreshBlockedUntil) {
            Toast.makeText(this, "刷新刚请求过，稍候几秒", Toast.LENGTH_SHORT).show();
            return;
        }
        refreshBlockedUntil = now + REFRESH_GUARD_MS;
        RefreshBridge.requestFull(this);
        Toast.makeText(this, "已请求刷新，获取到新数据会自动更新", Toast.LENGTH_SHORT).show();
    }

    private void showLessonDialog(Lesson lesson) {
        String day = lesson.dayOfWeek >= 1 && lesson.dayOfWeek <= 7
                ? TimetableViews.DAY_NAMES[lesson.dayOfWeek - 1] : "周" + lesson.dayOfWeek;
        StringBuilder message = new StringBuilder();
        message.append("时间：").append(day).append(" ")
                .append(ScheduleMath.formatSections(lesson.beginSection, lesson.sections));
        String label = lesson.label();
        if (!label.isEmpty()) {
            message.append("\n地点／教师：").append(label);
        }
        message.append("\n周次：").append(
                lesson.weeks == null || lesson.weeks.trim().isEmpty()
                        ? "每周" : lesson.weeks + " 周");
        new AlertDialog.Builder(this)
                .setTitle(lesson.name)
                .setMessage(message.toString())
                .setPositiveButton("知道了", null)
                .show();
    }

    // -- helpers --

    private int[] courseColors(String name) {
        return UiTheme.courseColors(this, name);
    }


    private String weekRangeLabel(long firstWeekDate, int week) {
        return TimetableViews.weekRangeLabel(firstWeekDate, week);
    }

    private LinearLayout.LayoutParams groupParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, dp(12));
        return p;
    }

}
