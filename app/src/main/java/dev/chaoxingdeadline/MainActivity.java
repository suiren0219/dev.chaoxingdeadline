package dev.chaoxingdeadline;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Toast;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import io.github.libxposed.service.HookedTarget;
import io.github.libxposed.service.XposedService;

/**
 * Main screen: displays LSPosed activation status, deadline stats, and the deadline list.
 * The app screen only reloads local data; deadline capture is driven by the Chaoxing hook.
 */
public final class MainActivity extends BaseActivity implements App.ServiceListener {
    public static final String EXTRA_OPEN_SETTINGS = "dev.chaoxingdeadline.extra.OPEN_SETTINGS";
    private static final String TARGET_PACKAGE = "com.chaoxing.mobile";

    private final List<DeadlineItem> items = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private DeadlineStore store;
    private LinearLayout itemList;
    private TextView activeBadge;
    private TextView statusText;
    private TextView countPending;
    private TextView countExpired;
    private TextView countBlocked;
    private LinearLayout emptyContainer;
    private EditText searchInput;
    private Spinner typeFilter;
    private String searchQuery = "";
    private String typeFilterValue = "";
    private static final int TAB_TODAY = 0;
    /** Course-task overview: every course's open work, grouped and sorted. */
    private static final int TAB_TASKS = 1;
    private static final int TAB_MINE = 2;
    private FrameLayout contentHost;
    private TextView[] tabTitles = new TextView[3];
    private int selectedTab = TAB_TODAY;
    /** Free-text filter of the 课程任务 overview (task title or course name). */
    private String taskQuery = "";
    /** Type filter of the 课程任务 overview; empty = every type. */
    private String taskType = "";
    /**
     * The course list under the task filters. Held so a keystroke can repaint just the list
     * instead of rebuilding the tab, which would also drop the caret from the search box.
     */
    private LinearLayout taskListHost;
    /** "list" (flat) or "week" (grouped by day, next 7 days): the 今日 tab's two views. */
    private String viewMode = "list";
    private TextView listTab;
    private TextView weekTab;

    private final Runnable tick = new Runnable() {
        @Override public void run() { reload(false); handler.postDelayed(this, 5000L); }
    };
    private final BroadcastReceiver refreshReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { reload(false); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applySystemBars();
        store = new DeadlineStore(this);
        setContentView(buildContent());
        DeadlineNotifier.ensureChannel(this);
        requestNotificationPermission();
        reload(true);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) {
            return;
        }
        intent.removeExtra(EXTRA_OPEN_SETTINGS);
        handler.post(() -> startActivity(new Intent(this, SettingsActivity.class)));
    }

    @Override
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    protected void onStart() {
        super.onStart();
        App.addServiceListener(this);
        IntentFilter filter = new IntentFilter(DeadlineReceiver.ACTION_REFRESH);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(refreshReceiver, filter, RECEIVER_NOT_EXPORTED);
        else
            registerReceiver(refreshReceiver, filter);
        handler.post(tick);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(tick);
        App.removeServiceListener(this);
        unregisterReceiver(refreshReceiver);
        super.onStop();
    }

    @Override
    public void onServiceChanged(XposedService service) {
        runOnUiThread(() -> reload(false));
    }

    /**
     * Three-tab shell: 今日 (date + today's lessons + deadlines), 课程表 (weekly grid),
     * 我的 (account, data and the settings entry). One glass backdrop behind all three,
     * with the tab bar pinned at the bottom.
     */
    private View buildContent() {
        FrameLayout shell = new FrameLayout(this);
        shell.addView(GlassUi.backdrop(this), new FrameLayout.LayoutParams(-1, -1));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        shell.addView(page, new FrameLayout.LayoutParams(-1, -1));

        // Content swaps per tab; the tab bar below stays put.
        contentHost = new FrameLayout(this);
        page.addView(contentHost, new LinearLayout.LayoutParams(-1, 0, 1f));

        page.addView(tabBar(), new LinearLayout.LayoutParams(-1, -2));
        showTab(selectedTab);
        return shell;
    }

    /** Bottom tab bar: 今日 / 课程表 / 我的. */
    private View tabBar() {
        LinearLayout bar = GlassUi.row(this);
        bar.setPadding(dp(10), dp(8), dp(10), dp(8));
        bar.setBackground(UiTheme.glassCard(this, dp(22)));

        tabTitles = new TextView[3];
        String[][] tabs = {{"\u25a6", "今日"}, {"\u2630", "课程任务"}, {"\u25cf", "我的"}};
        for (int i = 0; i < tabs.length; i++) {
            final int index = i;
            LinearLayout tab = GlassUi.column(this);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(0, dp(6), 0, dp(6));
            TextView glyph = new TextView(this);
            glyph.setText(tabs[i][0]);
            glyph.setTextSize(18);
            glyph.setGravity(Gravity.CENTER);
            tab.addView(glyph, new LinearLayout.LayoutParams(-1, -2));
            TextView label = new TextView(this);
            label.setText(tabs[i][1]);
            label.setTextSize(11);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, dp(2), 0, 0);
            tab.addView(label, new LinearLayout.LayoutParams(-1, -2));
            tab.setOnClickListener(v -> showTab(index));
            tabTitles[i] = label;
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
            p.setMargins(dp(4), 0, dp(4), 0);
            bar.addView(tab, p);
        }
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(-1, -2);
        barParams.setMargins(dp(12), 0, dp(12), dp(12));
        bar.setLayoutParams(barParams);
        return bar;
    }

    /** Test seam: lets the launch test switch tabs the way the tab bar does. */
    void showTabForTest(int index) {
        showTab(index);
    }

    /** Swaps the content area and repaints the tab labels. */
    private void showTab(int index) {
        selectedTab = index;
        for (int i = 0; i < tabTitles.length; i++) {
            if (tabTitles[i] == null) {
                continue;
            }
            boolean active = i == index;
            tabTitles[i].setTextColor(active ? UiTheme.accent(this) : UiTheme.muted(this));
            tabTitles[i].setTypeface(null, active
                    ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
        if (contentHost == null) {
            return;
        }
        // The outgoing tab's views are discarded with it; clearing the references keeps
        // reload() from writing into detached views instead of the new tab's.
        itemList = null;
        emptyContainer = null;
        activeBadge = null;
        statusText = null;
        countPending = null;
        countExpired = null;
        countBlocked = null;
        searchInput = null;
        typeFilter = null;

        contentHost.removeAllViews();
        View content = index == TAB_TODAY ? buildTodayTab()
                : index == TAB_TASKS ? buildTasksTab() : buildMineTab();
        contentHost.addView(content, new FrameLayout.LayoutParams(-1, -1));
        // Paint the views this tab just built (and refresh the deadline list it shows).
        reload(false);
    }

    /** Page frame: title (optional) plus the padded content column below it. */
    private LinearLayout scaffold(String title, String subtitle) {
        LinearLayout root = GlassUi.column(this);
        root.setPadding(dp(16), statusBarHeight() + dp(10), dp(16), 0);
        if (title != null && !title.isEmpty()) {
            root.addView(GlassUi.title(this, title, 24), new LinearLayout.LayoutParams(-1, -2));
            if (subtitle != null && !subtitle.isEmpty()) {
                TextView sub = GlassUi.body(this, subtitle, 13);
                sub.setPadding(0, dp(4), 0, 0);
                root.addView(sub, new LinearLayout.LayoutParams(-1, -2));
            }
        }
        return root;
    }

    /** A scrollable body inside a scaffold. */
    private LinearLayout scrollBody(LinearLayout page) {
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = GlassUi.column(this);
        body.setPadding(0, dp(12), 0, dp(12));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        return body;
    }

    /**
     * 今日: the date, then today's lessons, then the deadline list. Landing here is what
     * makes the day's schedule the first thing seen on opening the app.
     */
    private View buildTodayTab() {
        LinearLayout page = scaffold(null, null);
        Calendar calendar = Calendar.getInstance();
        String[] weekdays = {"", "周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        page.addView(GlassUi.title(this,
                "今天是" + weekdays[calendar.get(Calendar.DAY_OF_WEEK)], 26),
                new LinearLayout.LayoutParams(-1, -2));
        TextView date = GlassUi.body(this, String.format(Locale.CHINA, "%d年%d月%d日",
                calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH)), 16);
        date.setPadding(0, dp(2), 0, 0);
        page.addView(date, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout body = scrollBody(page);
        body.addView(todayLessonsBlock(), new LinearLayout.LayoutParams(-1, -2));
        body.addView(GlassUi.sectionLabel(this, "待办事项"), new LinearLayout.LayoutParams(-1, -2));
        body.addView(todoBlock(), new LinearLayout.LayoutParams(-1, -2));
        return page;
    }

    /** Today's lessons as cards. */
    private View todayLessonsBlock() {
        LinearLayout box = GlassUi.column(this);
        DeadlineStore schedule = new DeadlineStore(this);
        long now = System.currentTimeMillis();
        long firstWeekDate = schedule.scheduleFirstWeekDate();
        String sectionTimes = schedule.scheduleSectionTimes();
        int week = ScheduleMath.currentWeek(now, firstWeekDate);
        List<Lesson> today = ScheduleMath.todayLessons(schedule.allLessons(), now, firstWeekDate);
        Lesson current = ScheduleMath.currentLesson(today, now, firstWeekDate, sectionTimes);

        LinearLayout head = GlassUi.row(this);
        head.addView(GlassUi.body(this, week > 0
                ? "第 " + week + " 周 · 今天 " + today.size() + " 节课" : "课表未获取", 12),
                new LinearLayout.LayoutParams(0, -2, 1f));
        if (current != null) {
            head.addView(GlassUi.chip(this, "上课中", UiTheme.accent(this), Color.WHITE),
                    new LinearLayout.LayoutParams(-2, -2));
        }
        box.addView(head, new LinearLayout.LayoutParams(-1, -2));

        if (today.isEmpty()) {
            TextView empty = GlassUi.body(this, schedule.scheduleUpdatedAt() > 0L
                    ? "今天没有课" : "还没有课表数据，去「课程表」标签获取", 13);
            empty.setPadding(0, dp(8), 0, 0);
            box.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            return box;
        }
        for (Lesson lesson : today) {
            box.addView(todayLessonCard(lesson, current, week, firstWeekDate, sectionTimes, now),
                    marTop(dp(8)));
        }
        return box;
    }

    private View todayLessonCard(Lesson lesson, Lesson current, int week, long firstWeekDate,
                                 String sectionTimes, long now) {
        boolean isCurrent = current != null && current.id != null && current.id.equals(lesson.id);
        long start = ScheduleMath.lessonStartAt(firstWeekDate, week, lesson.dayOfWeek,
                lesson.beginSection, sectionTimes);
        long end = ScheduleMath.lessonEndAt(firstWeekDate, week, lesson.dayOfWeek,
                lesson.beginSection, lesson.sections, sectionTimes);
        boolean isPast = end > 0L && now >= end;
        int[] colors = UiTheme.courseColors(this, lesson.name);

        LinearLayout panel = GlassUi.panel(this);
        panel.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout body = GlassUi.row(this);
        View strip = new View(this);
        strip.setBackground(UiTheme.fillOnly(this, colors[1], dp(2)));
        LinearLayout.LayoutParams stripParams = new LinearLayout.LayoutParams(dp(4), -1);
        stripParams.setMargins(0, 0, dp(10), 0);
        body.addView(strip, stripParams);

        LinearLayout texts = GlassUi.column(this);
        TextView name = GlassUi.title(this, lesson.name, 16);
        name.setAlpha(isPast && !isCurrent ? 0.6f : 1f);
        texts.addView(name, new LinearLayout.LayoutParams(-1, -2));
        String range = (start > 0L ? DateText.clock(start) : "--:--")
                + "–" + (end > 0L ? DateText.clock(end) : "--:--");
        TextView time = GlassUi.body(this, range + " · "
                + ScheduleMath.formatSections(lesson.beginSection, lesson.sections), 13);
        time.setPadding(0, dp(3), 0, 0);
        texts.addView(time, new LinearLayout.LayoutParams(-1, -2));
        String label = lesson.label();
        if (!label.isEmpty()) {
            TextView place = GlassUi.body(this, label, 12);
            place.setPadding(0, dp(2), 0, 0);
            texts.addView(place, new LinearLayout.LayoutParams(-1, -2));
        }
        body.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        body.addView(GlassUi.chip(this, isCurrent ? "上课中" : (isPast ? "已结束" : "未开始"),
                        isCurrent ? UiTheme.accent(this) : UiTheme.muted(this), Color.WHITE),
                new LinearLayout.LayoutParams(-2, -2));
        panel.addView(body, new LinearLayout.LayoutParams(-1, -2));
        panel.setOnClickListener(v -> showLessonDetail(lesson));
        return panel;
    }

    private void showLessonDetail(Lesson lesson) {
        StringBuilder message = new StringBuilder();
        message.append("周次：").append(lesson.weeks == null || lesson.weeks.isEmpty()
                ? "每周" : lesson.weeks + " 周");
        message.append("\n节次：").append(ScheduleMath.formatSections(
                lesson.beginSection, lesson.sections));
        String label = lesson.label();
        if (!label.isEmpty()) {
            message.append("\n地点／教师：").append(label);
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(lesson.name)
                .setMessage(message.toString())
                .setPositiveButton("知道了", null)
                .show();
    }

    /** Stat chips, filters and the deadline list — the 今日 tab's lower half. */
    private View todoBlock() {
        LinearLayout box = GlassUi.column(this);

        LinearLayout stats = GlassUi.row(this);
        countPending = new TextView(this);
        countExpired = new TextView(this);
        countBlocked = new TextView(this);
        stats.addView(statChip("\u23f3", "待办", countPending), chipParams(1f));
        stats.addView(space(dp(10)));
        stats.addView(statChip("\u2713", "已完成", countExpired), chipParams(1f));
        stats.addView(space(dp(10)));
        stats.addView(statChip("\u2298", "已屏蔽", countBlocked), chipParams(1f));
        box.addView(stats, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout filterBar = hbox();
        filterBar.setPadding(dp(14), dp(9), dp(14), dp(9));
        filterBar.setBackground(UiTheme.glassCard(this, dp(14)));
        searchInput = new EditText(this);
        searchInput.setHint("搜索标题或课程");
        searchInput.setSingleLine();
        searchInput.setTextSize(13);
        searchInput.setBackground(null);
        searchInput.setPadding(0, 0, 0, 0);
        searchInput.setTextColor(UiTheme.text(this));
        searchInput.setHintTextColor(UiTheme.muted(this));
        filterBar.addView(searchInput, new LinearLayout.LayoutParams(0, -2, 1f));
        typeFilter = new Spinner(this);
        typeFilter.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"全部", "作业", "考试", "章节", "课堂活动"}));
        typeFilter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String value = String.valueOf(parent.getItemAtPosition(position));
                typeFilterValue = "全部".equals(value) ? "" : value;
                rebuildItemList();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        filterBar.addView(typeFilter, new LinearLayout.LayoutParams(-2, -2));
        box.addView(filterBar, marTop(dp(10)));

        LinearLayout tabs = hbox();
        listTab = tabButton("列表", true);
        weekTab = tabButton("本周", false);
        listTab.setOnClickListener(v -> setViewMode("list"));
        weekTab.setOnClickListener(v -> setViewMode("week"));
        tabs.addView(listTab, new LinearLayout.LayoutParams(0, -2, 1f));
        tabs.addView(space(dp(10)));
        tabs.addView(weekTab, new LinearLayout.LayoutParams(0, -2, 1f));
        box.addView(tabs, marTop(dp(10)));
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                searchQuery = s.toString().trim();
                rebuildItemList();
            }
        });

        itemList = new LinearLayout(this);
        itemList.setOrientation(LinearLayout.VERTICAL);
        box.addView(itemList, marTop(dp(10)));

        emptyContainer = new LinearLayout(this);
        emptyContainer.setGravity(Gravity.CENTER);
        emptyContainer.setPadding(dp(8), dp(20), dp(8), dp(20));
        TextView emptyText = text("", 15, false, UiTheme.muted(this));
        emptyText.setGravity(Gravity.CENTER);
        emptyContainer.addView(emptyText, new LinearLayout.LayoutParams(-1, -2));
        box.addView(emptyContainer, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    /**
     * 课程任务 tab: every course's outstanding work, grouped by course.
     *
     * This tab replaced the weekly grid because the grid answered "when is my next class",
     * which 今日 already covers and the widget shows at a glance; what had no home was
     * "what do I still owe, per course". The timetable is unchanged and opens from 我的.
     */
    private View buildTasksTab() {
        LinearLayout page = scaffold("课程任务", "按课程汇总未完成的作业、考试、章节与活动");
        LinearLayout body = scrollBody(page);

        List<CourseTaskGroups.Group> groups = CourseTaskGroups.build(
                store.openItems(), store.recentlyFinished(DeadlineStore.SETTLED_WINDOW_MS));
        int open = CourseTaskGroups.openCount(groups);

        LinearLayout summary = GlassUi.panel(this);
        summary.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView headline = text(open == 0 ? "没有未完成的任务" : "还有 " + open + " 项未完成",
                17, true, open == 0 ? UiTheme.muted(this) : UiTheme.text(this));
        summary.addView(headline, new LinearLayout.LayoutParams(-1, -2));
        TextView sub = GlassUi.body(this, open == 0
                ? "已取回的任务都已完成，或还没有取回任务。"
                : "共 " + groups.size() + " 门课程；点课程可看该课的未完成清单。", 12);
        sub.setPadding(0, dp(4), 0, 0);
        summary.addView(sub, new LinearLayout.LayoutParams(-1, -2));
        body.addView(summary, new LinearLayout.LayoutParams(-1, -2));

        body.addView(taskFilters(), marTop(dp(10)));

        taskListHost = GlassUi.column(this);
        body.addView(taskListHost, new LinearLayout.LayoutParams(-1, -2));
        refreshTaskList();
        body.addView(taskActions(), marTop(dp(12)));
        return page;
    }

    /** Search box plus the type chips; both narrow the overview in place. */
    private View taskFilters() {
        LinearLayout panel = GlassUi.panel(this);
        panel.setPadding(dp(14), dp(12), dp(14), dp(12));

        searchInput = new EditText(this);
        searchInput.setHint("搜索任务或课程");
        searchInput.setTextSize(14);
        searchInput.setTextColor(UiTheme.text(this));
        searchInput.setHintTextColor(UiTheme.muted(this));
        searchInput.setSingleLine(true);
        searchInput.setText(taskQuery);
        searchInput.setBackground(UiTheme.rounded(this, UiTheme.sheet(this), dp(12),
                UiTheme.stroke(this)));
        searchInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        searchInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                taskQuery = s.toString().trim();
                // Only the list below needs to change; rebuilding the tab here would also
                // work but costs a full page rebuild on every keystroke.
                refreshTaskList();
            }
        });
        panel.addView(searchInput, new LinearLayout.LayoutParams(-1, -2));

        String[] types = {"全部", "作业", "考试", "章节", "课堂活动"};
        LinearLayout chips = GlassUi.row(this);
        chips.setPadding(0, dp(10), 0, 0);
        for (String type : types) {
            boolean active = type.equals(taskType) || (taskType.isEmpty() && "全部".equals(type));
            TextView chip = GlassUi.chip(this, type,
                    active ? UiTheme.accent(this) : UiTheme.sheet(this),
                    active ? Color.WHITE : UiTheme.text(this));
            chip.setOnClickListener(v -> {
                taskType = "全部".equals(type) ? "" : type;
                showTab(TAB_TASKS);
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
            p.rightMargin = dp(8);
            chips.addView(chip, p);
        }
        android.widget.HorizontalScrollView scroller = new android.widget.HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.addView(chips, new android.widget.FrameLayout.LayoutParams(-2, -2));
        panel.addView(scroller, new LinearLayout.LayoutParams(-1, -2));
        return panel;
    }

    /** Rebuilds only the course list under the filters, leaving the search box focused. */
    private void refreshTaskList() {
        if (taskListHost == null) {
            return;
        }
        taskListHost.removeAllViews();
        List<CourseTaskGroups.Group> groups = CourseTaskGroups.narrow(CourseTaskGroups.build(
                store.openItems(), store.recentlyFinished(DeadlineStore.SETTLED_WINDOW_MS)),
                taskQuery, taskType);
        if (groups.isEmpty()) {
            taskListHost.addView(glassEmptyCard(store.countAll() == 0 ? "还没有课程数据" : "没有匹配的任务",
                    store.countAll() == 0
                            ? "打开学习通浏览一次课程，或点下面的按钮获取。"
                            : "换个关键词或类型试试。"), marTop(dp(10)));
            return;
        }
        for (CourseTaskGroups.Group group : groups) {
            taskListHost.addView(courseGroupCard(group), marTop(dp(10)));
        }
    }

    /** One course: open work first, then whatever it finished recently. */
    private View courseGroupCard(CourseTaskGroups.Group group) {
        LinearLayout card = GlassUi.panel(this);
        card.setPadding(dp(16), dp(14), dp(16), dp(12));

        LinearLayout head = GlassUi.row(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text(group.course, 15, true, UiTheme.text(this)),
                new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(GlassUi.chip(this, group.openCount() == 0 ? "已完成" : group.openCount() + " 项",
                        group.openCount() == 0 ? UiTheme.sheet(this) : UiTheme.accent(this),
                        group.openCount() == 0 ? UiTheme.muted(this) : Color.WHITE),
                new LinearLayout.LayoutParams(-2, -2));
        card.addView(head, new LinearLayout.LayoutParams(-1, -2));

        if (group.hasOpen() && group.nextDueAt > 0L) {
            TextView next = GlassUi.body(this, "最近截止：" + DateText.deadlineTime(group.nextDueAt)
                    + " · " + WeekGrouping.countdown(group.nextDueAt, System.currentTimeMillis()), 12);
            next.setPadding(0, dp(6), 0, 0);
            card.addView(next, new LinearLayout.LayoutParams(-1, -2));
        }

        for (DeadlineItem item : group.open) {
            card.addView(taskRow(item, false), marTop(dp(8)));
        }
        if (!group.finished.isEmpty()) {
            TextView doneLabel = GlassUi.body(this, "最近完成 " + group.finished.size() + " 项", 12);
            doneLabel.setPadding(0, dp(10), 0, 0);
            card.addView(doneLabel, new LinearLayout.LayoutParams(-1, -2));
            for (DeadlineItem item : group.finished) {
                card.addView(taskRow(item, true), marTop(dp(6)));
            }
        }

        // Tapping the card opens the course's own screen: its full open list plus the
        // chapter progress a single crowded card cannot show.
        card.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, CourseDetailActivity.class);
            intent.putExtra(CourseDetailActivity.EXTRA_COURSE, group.course);
            try {
                startActivity(intent);
            } catch (Throwable ignored) {
            }
        });
        return card;
    }

    /** A single task row inside a course card. */
    private View taskRow(DeadlineItem item, boolean finished) {
        LinearLayout row = GlassUi.row(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(11), dp(12), dp(11));
        row.setBackground(UiTheme.glassCard(this, dp(12)));
        row.setAlpha(finished ? 0.55f : 1f);

        TextView badge = text(badgeLabel(item.type), 12, true, badgeColor(this, item.type));
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(UiTheme.fillOnly(this, badgeBg(this, item.type), dp(8)));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(30), dp(30));
        bp.rightMargin = dp(10);
        bp.gravity = Gravity.CENTER_VERTICAL;
        row.addView(badge, bp);

        LinearLayout texts = GlassUi.column(this);
        texts.addView(text(item.title, 14, true,
                finished ? UiTheme.muted(this) : UiTheme.text(this)),
                new LinearLayout.LayoutParams(-1, -2));
        String due = finished
                ? "已完成 · " + DateText.deadlineTime(item.dueAt)
                : DateText.dueLine(item.dueAt);
        if (!finished && item.vanishedAt > 0L) {
            due = due + " · 可能已撤销";
        }
        TextView dueText = text(due, 11, false, finished ? UiTheme.muted(this)
                : (item.vanishedAt > 0L ? UiTheme.warningText(this) : UiTheme.accent(this)));
        dueText.setPadding(0, dp(3), 0, 0);
        texts.addView(dueText, new LinearLayout.LayoutParams(-1, -2));
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        if (!finished) {
            // Marking done right here is the fast path; the item menu still has the rest.
            TextView done = text("完成", 12, true, UiTheme.accent(this));
            done.setPadding(dp(10), dp(6), dp(4), dp(6));
            done.setOnClickListener(v -> {
                store.setSubmitted(item.id, true);
                OverlayBridge.publish(this);
                DeadlineNotifier.rescheduleUpcomingOnly(this);
                DeadlineWidgetProvider.updateAll(this);
                showTab(TAB_TASKS);
            });
            row.addView(done, new LinearLayout.LayoutParams(-2, -2));
        }

        row.setOnClickListener(v -> showItemMenu(item));
        return row;
    }

    /** Data actions for the course tasks: fetch them, or pick which courses to follow. */
    private View taskActions() {
        LinearLayout panel = GlassUi.panel(this);
        View refresh = GlassUi.actionRow(this, "获取全学期课程任务",
                "逐门课程拉取作业、考试、章节任务点与课堂活动");
        refresh.setOnClickListener(v -> {
            RefreshBridge.requestFull(this);
            Toast.makeText(this, "已请求刷新课程任务", Toast.LENGTH_SHORT).show();
        });
        panel.addView(refresh, new LinearLayout.LayoutParams(-1, -2));

        View manage = GlassUi.actionRow(this, "选择要查询的课程",
                "勾掉不想跟的课程或类型，只保留需要的");
        manage.setOnClickListener(v -> startActivity(new Intent(this, CourseBlockActivity.class)));
        panel.addView(manage, new LinearLayout.LayoutParams(-1, -2));

        TextView caveat = GlassUi.body(this,
                "任务与章节进度来自学习通同步；章节任务点只读展示，请在学习通内完成。", 11);
        caveat.setPadding(dp(4), dp(8), dp(4), dp(4));
        panel.addView(caveat, new LinearLayout.LayoutParams(-1, -2));
        return panel;
    }

    /** 我的: account state, the app's other surfaces, and the settings entry. */
    private View buildMineTab() {
        LinearLayout page = scaffold("我的", "账号、数据与全部设置");
        LinearLayout body = scrollBody(page);

        LinearLayout status = GlassUi.panel(this);
        status.setPadding(dp(16), dp(14), dp(16), dp(14));
        activeBadge = text("", 15, true, UiTheme.text(this));
        status.addView(activeBadge, new LinearLayout.LayoutParams(-1, -2));
        statusText = text("", 12, false, UiTheme.muted(this));
        statusText.setPadding(0, dp(4), 0, 0);
        status.addView(statusText, new LinearLayout.LayoutParams(-1, -2));
        body.addView(status, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout actions = GlassUi.panel(this);
        View settings = GlassUi.actionRow(this, "设置", "通知、免打扰、摘要、课程管理与备份");
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        actions.addView(settings, new LinearLayout.LayoutParams(-1, -2));
        View timetable = GlassUi.actionRow(this, "课程表", "周网格、导入课表与整学期获取");
        timetable.setOnClickListener(v -> startActivity(new Intent(this, ScheduleActivity.class)));
        actions.addView(timetable, new LinearLayout.LayoutParams(-1, -2));
        View stats = GlassUi.actionRow(this, "统计概览", "未完成／已过期／已完成与课程排行");
        stats.setOnClickListener(v -> startActivity(new Intent(this, StatsActivity.class)));
        actions.addView(stats, new LinearLayout.LayoutParams(-1, -2));
        View calendar = GlassUi.actionRow(this, "月历", "整月截止分布，点某天查看当天待办");
        calendar.setOnClickListener(v -> startActivity(new Intent(this, MonthCalendarActivity.class)));
        actions.addView(calendar, new LinearLayout.LayoutParams(-1, -2));
        View add = GlassUi.actionRow(this, "添加待办", "手动记一条学习通上没有的截止项");
        add.setOnClickListener(v -> ManualDeadlineDialog.show(this, () -> reload(false)));
        actions.addView(add, new LinearLayout.LayoutParams(-1, -2));
        body.addView(actions, marTop(dp(10)));

        LinearLayout links = GlassUi.panel(this);
        View download = GlassUi.actionRow(this, "下载最新版", "按当前签名下载对应安装包");
        download.setOnClickListener(v -> openLatestDownload());
        links.addView(download, new LinearLayout.LayoutParams(-1, -2));
        View about = GlassUi.actionRow(this, "关于", "版本、许可与致谢");
        about.setOnClickListener(v -> startActivity(new Intent(this, AboutActivity.class)));
        links.addView(about, new LinearLayout.LayoutParams(-1, -2));
        body.addView(links, marTop(dp(10)));
        return page;
    }

    private View glassEmptyCard(String title, String body) {
        LinearLayout panel = GlassUi.panel(this);
        panel.setPadding(dp(18), dp(20), dp(18), dp(20));
        panel.addView(GlassUi.title(this, title, 16), new LinearLayout.LayoutParams(-1, -2));
        TextView text = GlassUi.body(this, body, 13);
        text.setPadding(0, dp(6), 0, 0);
        panel.addView(text, new LinearLayout.LayoutParams(-1, -2));
        return panel;
    }

    /** Direct download of the newest build, matching the installed signature. */
    private void openLatestDownload() {
        boolean debuggable = (getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        String asset = debuggable ? "Deadline-debug.apk" : "Deadline-release.apk";
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(
                    "https://github.com/suiren0219/dev.chaoxingdeadline/releases/latest/download/"
                            + asset)));
        } catch (Throwable throwable) {
            Toast.makeText(this, "未找到可用的浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    private LinearLayout statChip(String iconChar, String label, TextView countView) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.VERTICAL);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(12), dp(14), dp(12), dp(14));
        chip.setBackground(UiTheme.glassCard(this, dp(14)));

        countView.setText("0");
        countView.setTextSize(22);
        countView.setTextColor(UiTheme.text(this));
        countView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        countView.setGravity(Gravity.CENTER);
        countView.setPadding(0, 0, 0, dp(3));
        chip.addView(countView, new LinearLayout.LayoutParams(-1, -2));

        TextView lbl = text(label, 11, false, UiTheme.muted(this));
        lbl.setGravity(Gravity.CENTER);
        chip.addView(lbl, new LinearLayout.LayoutParams(-1, -2));
        return chip;
    }

    private LinearLayout.LayoutParams chipParams(float weight) {
        return new LinearLayout.LayoutParams(0, -2, weight);
    }

    private View space(int width) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(width, 0));
        return v;
    }

    private LinearLayout.LayoutParams marTop(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = top;
        return p;
    }

    /**
     * Refreshes data and paints whichever views the current tab owns.
     *
     * Every write is guarded: the three tabs build different views, so the ones that are
     * not on screen are null by design. Writing to them unguarded is what crashed the app
     * on launch once the home screen became tabbed (the activation labels live on 我的,
     * while the app opens on 今日).
     */
    private void reload(boolean firstLoad) {
        items.clear();
        items.addAll(store.activeItems());
        if (firstLoad) {
            DeadlineNotifier.rescheduleUpcomingOnly(this, new ArrayList<>(items));
            OverlayBridge.publish(this);
        }
        rebuildItemList();

        Activation activation = readActivation();
        if (activeBadge != null) {
            activeBadge.setText(activation.title);
            activeBadge.setTextColor(activation.active
                    ? UiTheme.successText(this) : UiTheme.warningText(this));
        }
        if (statusText != null) {
            statusText.setText(activation.detail);
        }

        int pending = 0, expired = 0, blocked = store.blockedCourses().size();
        long now = System.currentTimeMillis();
        for (DeadlineItem item : items) {
            if (item.submitted || item.dueAt <= now) expired++;
            else pending++;
        }
        if (countPending != null) countPending.setText(String.valueOf(pending));
        if (countExpired != null) countExpired.setText(String.valueOf(expired));
        if (countBlocked != null) countBlocked.setText(String.valueOf(blocked));

        if (itemList != null) {
            boolean empty = items.isEmpty();
            itemList.setVisibility(empty ? View.GONE : View.VISIBLE);
            if (emptyContainer != null) {
                emptyContainer.setVisibility(empty ? View.VISIBLE : View.GONE);
                View first = emptyContainer.getChildAt(0);
                if (first instanceof TextView) {
                    ((TextView) first).setText(activation.active
                            ? "暂无待办\n\n打开学习通后会自动捕获作业和考试截止时间。"
                            : "尚未检测到激活\n\n请在 LSPosed 中勾选本模块，作用域选择「学习通」。");
                }
            }
        }
    }

    private TextView tabButton(String label, boolean active) {
        TextView tv = text(label, 14, active, active ? UiTheme.accent(this) : UiTheme.muted(this));
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(12), dp(10), dp(12), dp(10));
        tv.setBackground(UiTheme.glassCard(this, dp(12)));
        return tv;
    }

    private void setViewMode(String mode) {
        if (mode.equals(viewMode)) {
            return;
        }
        viewMode = mode;
        boolean list = "list".equals(mode);
        listTab.setTextColor(list ? UiTheme.accent(this) : UiTheme.muted(this));
        listTab.setTypeface(null, list
                ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        weekTab.setTextColor(list ? UiTheme.muted(this) : UiTheme.accent(this));
        weekTab.setTypeface(null, list
                ? android.graphics.Typeface.NORMAL : android.graphics.Typeface.BOLD);
        rebuildItemList();
    }

    private void rebuildItemList() {
        if (itemList == null) {
            return;
        }
        itemList.removeAllViews();
        if ("week".equals(viewMode)) {
            renderWeek();
            return;
        }
        List<DeadlineItem> sorted = filteredSorted();
        for (DeadlineItem item : sorted) itemList.addView(itemRow(item), rowParams());
        if (sorted.isEmpty() && !items.isEmpty()) {
            TextView noMatch = text("没有匹配的待办\n试试清空搜索或切换类型", 13, false, UiTheme.muted(this));
            noMatch.setGravity(Gravity.CENTER);
            noMatch.setPadding(dp(8), dp(18), dp(8), dp(18));
            itemList.addView(noMatch, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    /** Search + type filter, then the list view's pending-first ordering. */
    private List<DeadlineItem> filteredSorted() {
        long now = System.currentTimeMillis();
        String query = searchQuery.toLowerCase(Locale.ROOT);
        List<DeadlineItem> sorted = new ArrayList<>();
        for (DeadlineItem item : items) {
            if (!typeFilterValue.isEmpty() && !typeFilterValue.equals(item.type)) {
                continue;
            }
            if (!matchesQuery(item, query)) {
                continue;
            }
            sorted.add(item);
        }
        Collections.sort(sorted, (a, b) -> {
            boolean aDone = a.submitted || a.dueAt <= now;
            boolean bDone = b.submitted || b.dueAt <= now;
            if (aDone != bDone) return aDone ? 1 : -1;
            return aDone ? Long.compare(b.dueAt, a.dueAt) : Long.compare(a.dueAt, b.dueAt);
        });
        return sorted;
    }

    /** 本周视图: upcoming deadlines bucketed by calendar day over the next 7 days. */
    private void renderWeek() {
        List<WeekGrouping.DayGroup> groups =
                WeekGrouping.group(filteredSorted(), System.currentTimeMillis());
        if (groups.isEmpty()) {
            TextView empty = text("未来 7 天没有待办\n切换到「列表」可查看全部", 13, false, UiTheme.muted(this));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(8), dp(18), dp(8), dp(18));
            itemList.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        for (WeekGrouping.DayGroup group : groups) {
            itemList.addView(weekHeader(group), rowParams());
            for (DeadlineItem item : group.items) {
                itemList.addView(itemRow(item), rowParams());
            }
        }
    }

    private View weekHeader(WeekGrouping.DayGroup group) {
        LinearLayout header = hbox();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4), dp(14), dp(4), dp(6));
        TextView label = text(group.label, 15, true, UiTheme.accent(this));
        header.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView count = text(group.items.size() + " 项", 12, false, UiTheme.muted(this));
        header.addView(count, new LinearLayout.LayoutParams(-2, -2));
        return header;
    }

    private static boolean matchesQuery(DeadlineItem item, String query) {
        if (query.isEmpty()) {
            return true;
        }
        return (item.title != null && item.title.toLowerCase(Locale.ROOT).contains(query))
                || (item.course != null && item.course.toLowerCase(Locale.ROOT).contains(query));
    }

    private View itemRow(DeadlineItem item) {
        long delta = item.dueAt - System.currentTimeMillis();
        boolean expired = delta <= 0L;
        boolean done = item.submitted || expired;
        boolean urgent = !done && delta <= 24L * 60L * 60L * 1000L;

        // outer wrapper: swipe on expired items
        LinearLayout swipeWrapper = new LinearLayout(this);
        swipeWrapper.setOrientation(LinearLayout.HORIZONTAL);
        swipeWrapper.setClipChildren(false);
        swipeWrapper.setClipToPadding(false);

        // the card
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(dp(16), dp(15), dp(16), dp(15));
        card.setBackground(urgent ? UiTheme.fillOnly(this, UiTheme.dangerBg(this), dp(16))
                : UiTheme.glassCard(this, dp(16)));
        card.setAlpha(done ? 0.55f : 1f);

        TextView badge = new TextView(this);
        badge.setText(badgeLabel(item.type));
        badge.setTextSize(13);
        badge.setGravity(Gravity.CENTER);
        int badgeColor = badgeColor(this, item.type);
        int badgeBg = badgeBg(this, item.type);
        badge.setTextColor(badgeColor);
        badge.setBackground(UiTheme.fillOnly(this, badgeBg, dp(10)));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(36), dp(36));
        bp.rightMargin = dp(14);
        bp.gravity = Gravity.CENTER_VERTICAL;
        card.addView(badge, bp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);

        int titleColor = done ? UiTheme.muted(this) : UiTheme.text(this);
        TextView tvTitle = text(item.title, 15, true, titleColor);
        texts.addView(tvTitle, new LinearLayout.LayoutParams(-1, -2));

        String courseLabel = item.course == null || item.course.isEmpty() ? "未识别课程" : item.course;
        TextView tvCourse = text(courseLabel, 12, false, UiTheme.muted(this));
        tvCourse.setPadding(0, dp(4), 0, 0);
        texts.addView(tvCourse, new LinearLayout.LayoutParams(-1, -2));

        int dueColor = done ? UiTheme.muted(this)
                : (item.vanishedAt > 0L ? UiTheme.warningText(this) : UiTheme.accent(this));
        String dueText = item.submitted ? "已提交 · 截止：" + DateText.deadlineTime(item.dueAt) : DateText.dueLine(item.dueAt);
        if (item.vanishedAt > 0L) {
            dueText = dueText + " · 可能已撤销";
        }
        TextView tvDue = text(dueText, 12, false, dueColor);
        tvDue.setPadding(0, dp(4), 0, 0);
        texts.addView(tvDue, new LinearLayout.LayoutParams(-1, -2));

        card.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        // terminal label on the right for submitted/expired items
        if (done) {
            TextView doneLabel = text(item.submitted ? "已提交" : "已截止", 13, false, UiTheme.muted(this));
            doneLabel.setPadding(dp(10), 0, 0, 0);
            doneLabel.setGravity(Gravity.CENTER_VERTICAL);
            card.addView(doneLabel, new LinearLayout.LayoutParams(-2, -2));
        }

        card.setOnClickListener(v -> showItemMenu(item));

        swipeWrapper.addView(card, new LinearLayout.LayoutParams(-1, -2));

        // swipe-to-delete for terminal items; a tap still opens the item menu
        if (done) {
            card.setTag("swipeable");
            card.setOnTouchListener(new SwipeDismissListener(item, swipeWrapper, card));
        }

        return swipeWrapper;
    }

    private static String badgeLabel(String type) {
        if ("\u4f5c\u4e1a".equals(type)) {
            return "\u4f5c";
        }
        if ("\u8003\u8bd5".equals(type)) {
            return "\u8003";
        }
        if ("\u7ae0\u8282".equals(type)) {
            return "\u7ae0";
        }
        if ("\u8bfe\u5802\u6d3b\u52a8".equals(type)) {
            return "\u8bfe";
        }
        return "\u00b7";
    }

    private static int badgeColor(Context context, String type) {
        if ("\u8003\u8bd5".equals(type)) {
            return UiTheme.badgeExam(context);
        }
        if ("\u7ae0\u8282".equals(type)) {
            return UiTheme.badgeChapter(context);
        }
        if ("\u8bfe\u5802\u6d3b\u52a8".equals(type)) {
            return UiTheme.badgeActivity(context);
        }
        return UiTheme.badgeHomework(context);
    }

    private static int badgeBg(Context context, String type) {
        if ("\u8003\u8bd5".equals(type)) {
            return UiTheme.badgeExamBg(context);
        }
        if ("\u7ae0\u8282".equals(type)) {
            return UiTheme.badgeChapterBg(context);
        }
        if ("\u8bfe\u5802\u6d3b\u52a8".equals(type)) {
            return UiTheme.badgeActivityBg(context);
        }
        return UiTheme.badgeHomeworkBg(context);
    }

    /** Per-item actions: open in Chaoxing, toggle done state, delete. */
    private void showItemMenu(DeadlineItem item) {
        List<String> actions = new ArrayList<>();
        List<Runnable> runners = new ArrayList<>();
        if (item.vanishedAt > 0L) {
            // Two authoritative scans did not re-report it; let the user judge.
            actions.add("仍有效（恢复提醒）");
            runners.add(() -> {
                store.clearVanished(item.id);
                DeadlineNotifier.rescheduleAll(this);
                OverlayBridge.publish(this);
                DeadlineWidgetProvider.updateAll(this);
                reload(false);
            });
            actions.add("确认已撤销（删除）");
            runners.add(() -> DeadlineNotifier.deleteItem(this, item.id));
        }
        if (item.url != null && item.url.startsWith("http")) {
            actions.add("打开学习通页面");
            runners.add(() -> openChaoxingPage(item));
        }
        actions.add(item.submitted ? "标记为未完成" : "标记为已完成");
        runners.add(() -> {
            store.setSubmitted(item.id, !item.submitted);
            DeadlineNotifier.rescheduleAll(this);
            OverlayBridge.publish(this);
            DeadlineWidgetProvider.updateAll(this);
            reload(false);
        });
        actions.add("删除待办");
        runners.add(() -> DeadlineNotifier.deleteItem(this, item.id));
        actions.add("取消");
        runners.add(() -> {
        });
        new android.app.AlertDialog.Builder(this)
                .setTitle(item.title)
                .setItems(actions.toArray(new String[0]),
                        (dialog, which) -> runners.get(which).run())
                .show();
    }

    /**
     * Open the captured page inside Chaoxing.
     *
     * Stored URLs are mostly API endpoints (mooc1-api.chaoxing.com/…) that the Chaoxing
     * app itself cannot ACTION_VIEW — a bare startActivity then threw and the jump died
     * with only a toast. Probe every strategy before committing: Chaoxing-scoped view,
     * any-handler view, and finally the Chaoxing launcher entry.
     */
    private void openChaoxingPage(DeadlineItem item) {
        String url = item.url == null ? "" : item.url.trim();
        if (url.startsWith("http")) {
            if (tryStartActivity(viewIntent(url, true))) {
                return;
            }
            if (tryStartActivity(viewIntent(url, false))) {
                return;
            }
        }
        if (tryStartActivity(chaoxingLaunchIntent())) {
            return;
        }
        Toast.makeText(this, "打开学习通失败", Toast.LENGTH_SHORT).show();
    }

    private Intent viewIntent(String url, boolean scopedToChaoxing) {
        Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
        if (scopedToChaoxing) {
            intent.setPackage(TARGET_PACKAGE);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    private Intent chaoxingLaunchIntent() {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(TARGET_PACKAGE);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            return intent;
        } catch (Throwable throwable) {
            return null;
        }
    }

    private boolean tryStartActivity(Intent intent) {
        if (intent == null) {
            return false;
        }
        try {
            if (getPackageManager().resolveActivity(intent, 0) == null) {
                return false;
            }
            startActivity(intent);
            return true;
        } catch (Throwable throwable) {
            return false;
        }
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, dp(10));
        return p;
    }

    private Activation readActivation() {
        SharedPreferences prefs = getSharedPreferences("status", MODE_PRIVATE);
        long activeAt = prefs.getLong("last_active_at", 0L);
        long captureAt = prefs.getLong("last_capture_at", 0L);
        String lastStatus = prefs.getString("last_status", "");
        String lastSource = prefs.getString("last_source", "");

        XposedService service = App.getService();
        boolean serviceReady = service != null;
        boolean targetRunning = false;
        String framework = "";
        if (service != null) {
            try {
                framework = service.getFrameworkName() + " API " + service.getApiVersion();
                for (HookedTarget target : service.getRunningTargets())
                    if (TARGET_PACKAGE.equals(target.getProcessName())) { targetRunning = true; break; }
            } catch (Throwable ignored) {}
        }

        boolean loaded = serviceReady || activeAt > 0L || targetRunning;
        StringBuilder detail = new StringBuilder();
        detail.append(serviceReady ? "LSPosed 已连接" : "LSPosed 未连接");
        if (!framework.isEmpty()) detail.append(" · ").append(framework);
        detail.append("\n学习通进程：").append(targetRunning ? "运行中" : "未运行");
        if (targetRunning) detail.append("\n最近加载：当前运行中");
        else if (activeAt > 0L) detail.append("\n最近加载：").append(format(activeAt));
        else if (serviceReady) detail.append("\n状态说明：模块已加载，打开学习通后开始捕获");
        if (captureAt > 0L) detail.append("\n最近捕获：").append(format(captureAt));
        // emitStatus writes failure/anti-spider notices here; they were stored but never
        // surfaced, so "自动更新中/失败/触发验证码" was invisible outside the log.
        if (lastStatus != null && !lastStatus.isEmpty()) {
            detail.append("\n最近状态：").append(lastStatus);
        }
        String title = targetRunning ? "LSPosed 已激活" : (loaded ? "LSPosed 状态：已加载" : "LSPosed 状态：未连接");
        return new Activation(title, detail.toString(), loaded);
    }


    private String format(long millis) {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date(millis));
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
    }

    private class SwipeDismissListener implements View.OnTouchListener {
        private float startX;
        private boolean swiping;
        private final DeadlineItem item;
        private final View wrapper;
        private final View card;

        SwipeDismissListener(DeadlineItem item, View wrapper, View card) {
            this.item = item;
            this.wrapper = wrapper;
            this.card = card;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX = event.getRawX();
                    swiping = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - startX;
                    if (dx < -dp(16)) swiping = true;
                    if (swiping && dx < 0) {
                        card.setTranslationX(dx);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    float dx = card.getTranslationX();
                    int threshold = wrapper.getWidth() / 3;
                    if (swiping && dx < -threshold) {
                        // dismiss
                        card.animate().translationX(-wrapper.getWidth()).alpha(0f)
                                .setDuration(250)
                                .withEndAction(() -> {
                                    DeadlineNotifier.deleteItem(MainActivity.this, item.id);
                                    items.remove(item);
                                    itemList.removeView(wrapper);
                                    long now = System.currentTimeMillis();
                                    int pending = 0, expired = 0;
                                    for (DeadlineItem it : items) {
                                        if (it.submitted || it.dueAt <= now) expired++; else pending++;
                                    }
                                    countPending.setText(String.valueOf(pending));
                                    countExpired.setText(String.valueOf(expired));
                                    if (items.isEmpty()) {
                                        itemList.setVisibility(View.GONE);
                                        emptyContainer.setVisibility(View.VISIBLE);
                                    }
                                })
                                .start();
                    } else {
                        // spring back
                        card.animate().translationX(0f).setDuration(200).start();
                        if (!swiping) {
                            // The touch listener swallows ACTION_DOWN, so taps on completed
                            // items have to be forwarded to the click listener by hand.
                            card.performClick();
                        }
                    }
                    swiping = false;
                    return true;
                }
            }
            return false;
        }
    }

    private static final class Activation {
        final String title, detail; final boolean active;
        Activation(String t, String d, boolean a) { title = t; detail = d; active = a; }
    }
}
