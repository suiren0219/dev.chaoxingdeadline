package dev.chaoxingdeadline;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 课程详情：一门课的全部未完成工作，加上章节任务点进度。
 *
 * Two questions, one screen: what is still open for this course (deadline order, with the
 * same one-tap 完成 the overview has) and how far through its chapters the user is. The
 * chapter part is read-only on purpose — the numbers come from the scraped chapter tree,
 * and nothing here plays or submits anything inside the course.
 */
public final class CourseDetailActivity extends BaseActivity {
    /** Course name to show; the overview passes the display name it grouped by. */
    public static final String EXTRA_COURSE = "course";

    private DeadlineStore store;
    private String course = "";
    private LinearLayout body;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applySystemBars();
        store = new DeadlineStore(this);
        Intent intent = getIntent();
        if (intent != null) {
            String extra = intent.getStringExtra(EXTRA_COURSE);
            if (extra != null && !extra.trim().isEmpty()) {
                course = extra.trim();
            }
        }
        setContentView(buildContent());
        reload();
    }

    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(UiTheme.glassBackground(this));
        root.setPadding(dp(16), statusBarHeight() + dp(8), dp(16), dp(16));

        LinearLayout header = hbox();
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = icon("←", 28, UiTheme.accent(this));
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(48)));
        TextView title = text(course.isEmpty() ? "课程详情" : course, 19, true, UiTheme.text(this));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1f));
        header.addView(new View(this), new LinearLayout.LayoutParams(dp(44), dp(48)));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(6), 0, 0);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        return root;
    }

    private void reload() {
        body.removeAllViews();
        List<DeadlineItem> open = store.openItemsOfCourse(course);
        List<DeadlineItem> finished = store.recentlyFinishedOfCourse(course,
                DeadlineStore.SETTLED_WINDOW_MS);

        // -- chapter progress, read-only --
        ChapterProgress.Summary progress = ChapterProgress.summarize(store.allItems(), course);
        body.addView(sectionLabel("章节进度"), new LinearLayout.LayoutParams(-1, -2));
        body.addView(progressCard(progress), groupParams());

        // -- open work --
        body.addView(sectionLabel(open.isEmpty() ? "没有未完成的任务" : "未完成 " + open.size() + " 项"),
                new LinearLayout.LayoutParams(-1, -2));
        if (open.isEmpty()) {
            body.addView(mutedCard("这门课的任务都完成了。"), groupParams());
        } else {
            for (DeadlineItem item : open) {
                body.addView(taskCard(item), groupParams());
            }
        }

        if (!finished.isEmpty()) {
            body.addView(sectionLabel("最近完成 " + finished.size() + " 项"),
                    new LinearLayout.LayoutParams(-1, -2));
            for (DeadlineItem item : finished) {
                body.addView(finishedCard(item), groupParams());
            }
        }
    }

    /** The progress board: a bar, the counts, and the per-section breakdown. */
    private View progressCard(ChapterProgress.Summary progress) {
        LinearLayout card = card();
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        if (progress.isEmpty()) {
            // No counters captured yet is normal before the course has ever been scanned,
            // and saying so is better than showing a 0% that looks like a real measurement.
            TextView empty = text("还没有取到这门课的章节任务点。\n打开学习通进入该课程一次，或点下面的按钮重新获取。",
                    13, false, UiTheme.muted(this));
            card.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        } else {
            TextView headline = text(progress.describe(), 15, true, UiTheme.text(this));
            card.addView(headline, new LinearLayout.LayoutParams(-1, -2));

            LinearLayout bar = new LinearLayout(this);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setBackground(UiTheme.rounded(this, UiTheme.sheet(this), dp(6), 0));
            LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(-1, dp(10));
            barParams.topMargin = dp(10);
            card.addView(bar, barParams);

            View filled = new View(this);
            filled.setBackground(UiTheme.rounded(this,
                    progress.unfinished <= 0 ? UiTheme.accent(this) : UiTheme.warningText(this),
                    dp(6), 0));
            float weight = progress.total <= 0 ? 0f : progress.finished() * 1f / progress.total;
            LinearLayout.LayoutParams filledParams =
                    new LinearLayout.LayoutParams(0, dp(10), Math.max(0.001f, weight));
            bar.addView(filled, filledParams);
            if (weight < 1f) {
                bar.addView(new View(this), new LinearLayout.LayoutParams(0, dp(10), 1f - weight));
            }

            TextView percent = text(progress.percent() + "%", 13, true, UiTheme.accent(this));
            percent.setPadding(0, dp(8), 0, 0);
            card.addView(percent, new LinearLayout.LayoutParams(-1, -2));

            for (ChapterProgress.Node node : progress.nodes) {
                View row = sectionRow(node);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
                p.topMargin = dp(8);
                card.addView(row, p);
            }
        }

        View refresh = actionRow("获取这门课的进度", "向学习通重新拉取章节任务点");
        refresh.setOnClickListener(v -> {
            RefreshBridge.requestFull(this);
            android.widget.Toast.makeText(this, "已请求刷新，稍后回来看", android.widget.Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(-1, -2);
        refreshParams.topMargin = dp(12);
        card.addView(refresh, refreshParams);
        return card;
    }

    private View sectionRow(ChapterProgress.Node node) {
        LinearLayout row = hbox();
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(node.title, 13, false,
                node.unfinished > 0 ? UiTheme.text(this) : UiTheme.muted(this));
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        String state = node.unfinished > 0 ? "还差 " + node.unfinished : "已完成";
        TextView value = text(state, 12, node.unfinished > 0, node.unfinished > 0
                ? UiTheme.warningText(this) : UiTheme.accent(this));
        value.setPadding(dp(10), 0, 0, 0);
        row.addView(value, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private View taskCard(DeadlineItem item) {
        LinearLayout card = card();
        card.setPadding(dp(16), dp(13), dp(16), dp(13));

        LinearLayout head = hbox();
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView type = text(item.type, 12, true, UiTheme.accent(this));
        type.setBackground(UiTheme.rounded(this, UiTheme.sheet(this), dp(8), 0));
        type.setPadding(dp(8), dp(3), dp(8), dp(3));
        head.addView(type, new LinearLayout.LayoutParams(-2, -2));
        TextView title = text(item.title, 14, true, UiTheme.text(this));
        title.setPadding(dp(8), 0, 0, 0);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView done = text("完成", 12, true, UiTheme.accent(this));
        done.setPadding(dp(10), dp(6), dp(4), dp(6));
        done.setOnClickListener(v -> {
            store.setSubmitted(item.id, true);
            OverlayBridge.publish(this);
            DeadlineNotifier.rescheduleUpcomingOnly(this);
            DeadlineWidgetProvider.updateAll(this);
            reload();
        });
        head.addView(done, new LinearLayout.LayoutParams(-2, -2));
        card.addView(head, new LinearLayout.LayoutParams(-1, -2));

        String due = DateText.dueLine(item.dueAt);
        if (item.vanishedAt > 0L) {
            due = due + " · 可能已撤销";
        }
        TextView dueText = text(due, 12, false,
                item.vanishedAt > 0L ? UiTheme.warningText(this) : UiTheme.accent(this));
        dueText.setPadding(0, dp(6), 0, 0);
        card.addView(dueText, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    private View finishedCard(DeadlineItem item) {
        LinearLayout card = card();
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        card.setAlpha(0.6f);
        TextView title = text(item.type + " · " + item.title, 13, false, UiTheme.muted(this));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView when = text("已完成 · " + DateText.deadlineTime(item.dueAt), 11, false, UiTheme.muted(this));
        when.setPadding(0, dp(3), 0, 0);
        card.addView(when, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    private View mutedCard(String value) {
        LinearLayout card = card();
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.addView(text(value, 13, false, UiTheme.muted(this)), new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    private View actionRow(String title, String subtitle) {
        LinearLayout row = hbox();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackground(UiTheme.glassCard(this, dp(12)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(title, 14, true, UiTheme.text(this)), new LinearLayout.LayoutParams(-1, -2));
        TextView sub = text(subtitle, 11, false, UiTheme.muted(this));
        sub.setPadding(0, dp(2), 0, 0);
        texts.addView(sub, new LinearLayout.LayoutParams(-1, -2));
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        return row;
    }

    private TextView sectionLabel(String value) {
        TextView label = text(value, 12, true, UiTheme.muted(this));
        label.setPadding(dp(4), dp(10), dp(4), dp(8));
        return label;
    }

    private LinearLayout.LayoutParams groupParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, dp(10));
        return p;
    }
}
