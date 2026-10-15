package dev.chaoxingdeadline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The chapter task-point roll-up behind the course detail screen.
 *
 * The numbers come from the scraped chapter tree's own counters (jobcount /
 * jobUnfinishedCount), so the interesting cases are the payloads that do not carry both:
 * showing a confident "0%" or a wrong denominator is worse than showing nothing, because
 * the user reads it as progress toward finishing a course.
 */
public class ChapterProgressTest {

    @Test
    public void rollsUpCountersAcrossSections() {
        List<DeadlineItem> items = Arrays.asList(
                chapter("第一章 绪论", 5, 3),
                chapter("第二章 极限", 4, 0));

        ChapterProgress.Summary summary = ChapterProgress.summarize(items, "高等数学");

        assertEquals(9, summary.total);
        assertEquals(3, summary.unfinished);
        assertEquals(6, summary.finished());
        assertEquals(67, summary.percent());
        assertTrue(summary.describe().contains("6/9"));
        assertTrue(summary.describe().contains("还差 3"));
    }

    @Test
    public void finishedCourseReadsAsComplete() {
        ChapterProgress.Summary summary = ChapterProgress.summarize(
                Collections.singletonList(chapter("第一章", 6, 0)), "高等数学");

        assertEquals(0, summary.unfinished);
        assertEquals(100, summary.percent());
        assertTrue("a cleared course must say so, not show a bare 100%",
                summary.describe().contains("已全部完成"));
    }

    @Test
    public void payloadWithoutCountersContributesNothing() {
        // A chapter row captured from a payload that carried no counters must not be
        // counted as 0/0 and must not drag the percentage down.
        DeadlineItem noCounters = new DeadlineItem();
        noCounters.type = DeadlineStore.TYPE_CHAPTER;
        noCounters.course = "高等数学";
        noCounters.title = "只有时间窗的节点";
        noCounters.dueAt = System.currentTimeMillis() + 86_400_000L;
        noCounters.raw = "{\"name\":\"只有时间窗的节点\",\"endtime\":1790000000000}";

        ChapterProgress.Summary summary = ChapterProgress.summarize(
                Arrays.asList(chapter("第一章", 4, 1), noCounters), "高等数学");

        assertEquals("the counterless node is skipped, not counted as zero", 4, summary.total);
        assertEquals(1, summary.unfinished);
        assertEquals(1, summary.nodes.size());
    }

    @Test
    public void emptyOrForeignInputIsSafe() {
        assertTrue(ChapterProgress.summarize(null, "高等数学").isEmpty());
        assertTrue(ChapterProgress.summarize(Collections.emptyList(), "高等数学").isEmpty());
        assertTrue(ChapterProgress.summarize(
                Collections.singletonList(chapter("第一章", 4, 1)), "").isEmpty());
        assertTrue(ChapterProgress.summarize(
                Collections.singletonList(chapter("第一章", 4, 1)), "别的课").isEmpty());
    }

    @Test
    public void onlyChapterRowsCount() {
        // Homework also carries jobcount in some payloads; counting it here would inflate
        // the chapter progress with work that is not a chapter task point.
        DeadlineItem homework = chapter("作业节点", 10, 10);
        homework.type = DeadlineStore.TYPE_HOMEWORK;

        assertTrue(ChapterProgress.summarize(
                Collections.singletonList(homework), "高等数学").isEmpty());
    }

    @Test
    public void missingUnfinishedCountIsReadAsOne() {
        // The row only reached the table because it was filtered as unfinished, so treating
        // the absent counter as "one open point" beats reporting it as fully done.
        DeadlineItem item = new DeadlineItem();
        item.type = DeadlineStore.TYPE_CHAPTER;
        item.course = "高等数学";
        item.title = "只有总数字";
        item.dueAt = System.currentTimeMillis() + 86_400_000L;
        item.raw = "{\"name\":\"只有总数字\",\"jobcount\":4}";

        ChapterProgress.Node node = ChapterProgress.nodeOf(item);

        assertEquals(4, node.total);
        assertEquals(1, node.unfinished);
        assertTrue(node.hasNumbers());
    }

    @Test
    public void unfinishedIsClampedToTotal() {
        // A payload that contradicts itself (more open than exist) must not produce a
        // negative finished count or a >100% bar.
        ChapterProgress.Node node = ChapterProgress.nodeOf(chapter("矛盾数据", 3, 9));

        assertEquals(3, node.total);
        assertEquals(3, node.unfinished);
        assertEquals(0, node.finished());
        assertEquals(0, node.percent());
    }

    @Test
    public void titleFallsBackToTheStoredTitle() {
        DeadlineItem item = new DeadlineItem();
        item.type = DeadlineStore.TYPE_CHAPTER;
        item.course = "高等数学";
        item.title = "存储时的标题";
        item.dueAt = System.currentTimeMillis() + 86_400_000L;
        item.raw = "{\"jobcount\":2,\"jobUnfinishedCount\":1}";

        assertEquals("存储时的标题", ChapterProgress.nodeOf(item).title);
    }

    @Test
    public void malformedRawIsIgnored() {
        DeadlineItem item = new DeadlineItem();
        item.type = DeadlineStore.TYPE_CHAPTER;
        item.course = "高等数学";
        item.title = "坏数据";
        item.dueAt = System.currentTimeMillis() + 86_400_000L;
        item.raw = "not json at all";

        assertEquals(null, ChapterProgress.nodeOf(item));
        assertTrue(ChapterProgress.summarize(
                Collections.singletonList(item), "高等数学").isEmpty());
    }

    @Test
    public void numbersWithoutPayloadIsStatedPlainly() {
        ChapterProgress.Summary summary = ChapterProgress.summarize(
                Collections.<DeadlineItem>emptyList(), "高等数学");
        assertFalse(summary.describe().contains("%"));
        assertTrue(summary.describe().contains("没有取到"));
    }

    private static DeadlineItem chapter(String name, int total, int unfinished) {
        DeadlineItem item = new DeadlineItem();
        item.type = DeadlineStore.TYPE_CHAPTER;
        item.course = "高等数学";
        item.title = name;
        item.dueAt = System.currentTimeMillis() + 86_400_000L;
        item.courseId = "7";
        item.classId = "9";
        item.raw = "{\"name\":\"" + name + "\",\"jobcount\":" + total
                + ",\"jobUnfinishedCount\":" + unfinished + "}";
        return item;
    }
}
