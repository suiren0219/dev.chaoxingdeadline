package dev.chaoxingdeadline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Pure-JVM tests for the deadline parser. The JSON branch needs a real org.json at runtime;
 * the HTML branch (the one that actually produces most items) does not, so it is what these
 * tests pin down.
 */
public class DeadlineParserTest {

    @Test
    public void rejectsBlankAndUnrelatedText() {
        assertFalse(DeadlineParser.looksRelevant(null));
        assertFalse(DeadlineParser.looksRelevant(""));
        assertFalse(DeadlineParser.looksRelevant("太短"));
        assertFalse(DeadlineParser.looksRelevant("今天天气不错，出去走走"));
    }

    @Test
    public void acceptsHomeworkAndExamPages() {
        assertTrue(DeadlineParser.looksRelevant("作业截止时间 2026-10-05 23:59"));
        assertTrue(DeadlineParser.looksRelevant("{\"deadline\":\"2026-10-05 23:59\",\"courseName\":\"高等数学\"}"));
    }

    @Test
    public void parsesWorkPageHtmlItem() {
        String html = "<ul><li data=\"taskId=42&courseId=7\">"
                + "<p>第三章作业</p>"
                + "<span>剩余3天</span><span>高等数学</span>"
                + "<span>截止：2030-01-02 23:59</span>"
                + "</li></ul>";
        List<DeadlineItem> items = DeadlineParser.parsePayload(html, "active.workPage");
        assertEquals(1, items.size());
        DeadlineItem item = items.get(0);
        assertEquals("作业", item.type);
        assertEquals("第三章作业", item.title);
        assertEquals("高等数学", item.course);
        assertTrue("due date must be in the future", item.dueAt > System.currentTimeMillis());
        assertTrue("task id is recovered from the raw html", item.taskId.contains("42"));
    }

    @Test
    public void parsesExamPageHtmlItem() {
        String html = "<ul><li><p>期中考试</p><span>高等数学</span>"
                + "<span>截止时间：2030-05-06 09:30</span></li></ul>";
        List<DeadlineItem> items = DeadlineParser.parsePayload(html, "active.examPage");
        assertEquals(1, items.size());
        assertEquals("考试", items.get(0).type);
        assertEquals("期中考试", items.get(0).title);
    }

    @Test
    public void fallsBackToRelativeRemainingTime() {
        String html = "<ul><li><p>本周作业</p><span>剩余3天</span></li></ul>";
        List<DeadlineItem> items = DeadlineParser.parsePayload(html, "active.workList");
        assertEquals(1, items.size());
        long delta = items.get(0).dueAt - System.currentTimeMillis();
        assertTrue("relative due date should land within ~3 days", delta > 0L
                && delta <= TimeUnit.DAYS.toMillis(3) + TimeUnit.HOURS.toMillis(2));
    }

    @Test
    public void ignoresItemsWithoutAnyDeadline() {
        String html = "<ul><li><p>一个没有截止时间的通知</p><span>高等数学</span></li></ul>";
        assertTrue(DeadlineParser.parsePayload(html, "active.workPage").isEmpty());
    }

    @Test
    public void stableIdIsDeterministic() {
        DeadlineItem a = new DeadlineItem();
        a.type = "作业";
        a.title = "第三章作业";
        a.course = "高等数学";
        a.dueAt = 1_900_000_000_000L;
        DeadlineItem b = new DeadlineItem();
        b.type = "作业";
        b.title = "第三章作业";
        b.course = "高等数学";
        b.dueAt = 1_900_000_000_000L;
        assertEquals(a.stableId(), b.stableId());

        b.dueAt = 1_900_000_060_000L;
        assertFalse("changing the deadline must change the id", a.stableId().equals(b.stableId()));
    }
}
