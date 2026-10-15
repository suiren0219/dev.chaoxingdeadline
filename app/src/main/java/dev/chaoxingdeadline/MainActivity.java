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
    private static final int TAB_TODO = 0;
    private static final int TAB_MINE = 1;
    private FrameLayout contentHost;
    private TextView[] tabTitles = new TextView[2];
    private int selectedTab = TAB_TODO;
    /** "list" (flat) or "week" (grouped by day, next 7 days): the 今日 tab's two views. */

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

    /** Bottom tab bar: 待办 / 我的. */
    private View tabBar() {
        LinearLayout bar = GlassUi.row(this);
        bar.setPadding(dp(10), dp(8), dp(10), dp(8));
        bar.setBackground(UiTheme.glassCard(this, dp(22)));

        tabTitles = new TextView[2];
        String[][] tabs = {{"\u25a6", "待办"}, {"\u25cf", "我的"}};
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
        View content = index == TAB_TODO ? buildTodoTab() : buildMineTab();
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
    /**
     * 待办: outstanding homework and chapter task points.
     *
     * One list, deadline order. Only finished work is filtered out (see
     * {@link DeadlineStore#activeItems}); overdue items stay, because they are the ones
     * that most need doing.
     */
    private View buildTodoTab() {
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
        body.addView(GlassUi.sectionLabel(this, "未完成的作业与章节"), new LinearLayout.LayoutParams(-1, -2));
        body.addView(todoBlock(), new LinearLayout.LayoutParams(-1, -2));
        body.addView(todoActions(), marTop(dp(12)));
        return page;
    }

    /** The two data actions left: fetch now, and pick which courses to follow. */
    private View todoActions() {
        LinearLayout panel = GlassUi.panel(this);
        View refresh = GlassUi.actionRow(this, "获取作业与章节", "逐门课程拉取未完成的作业与章节任务点");
        refresh.setOnClickListener(v -> {
            RefreshBridge.requestFull(this);
            Toast.makeText(this, "已请求刷新", Toast.LENGTH_SHORT).show();
        });
        panel.addView(refresh, new LinearLayout.LayoutParams(-1, -2));

        View manage = GlassUi.actionRow(this, "选择要查询的课程",
                "勾掉不想跟的课程或类型，只保留需要的");
        manage.setOnClickListener(v -> startActivity(new Intent(this, CourseBlockActivity.class)));
        panel.addView(manage, new LinearLayout.LayoutParams(-1, -2));

        TextView caveat = GlassUi.body(this,
                "内容来自学习通同步；已完成的会自动隐藏，请以学习通为准。", 11);
        caveat.setPadding(dp(4), dp(8), dp(4), dp(4));
        panel.addView(caveat, new LinearLayout.LayoutParams(-1, -2));
        return panel;
    }

    /** The deadline list: search, filter and the pending/expired counts. */
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
        View settings = GlassUi.actionRow(this, "设置", "通知、免打扰与课程管理");
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        actions.addView(settings, new LinearLayout.LayoutParams(-1, -2));
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

    private void rebuildItemList() {
        if (itemList == null) {
            return;
        }
        itemList.removeAllViews();
        List<DeadlineItem> sorted = filteredSorted();
        for (DeadlineItem item : sorted) itemList.addView(itemRow(item), rowParams());
        if (sorted.isEmpty() && !items.isEmpty()) {
            TextView noMatch = text("没有匹配的待办\n试试清空搜索或筛选", 13, false, UiTheme.muted(this));
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
