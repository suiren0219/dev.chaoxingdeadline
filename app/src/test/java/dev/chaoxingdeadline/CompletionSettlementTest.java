package dev.chaoxingdeadline;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The completion rules behind the course-task overview.
 *
 * The school's task lists only describe what is still outstanding, so "done" cannot be read
 * off a single payload: it has to be decided from the item's own fields plus what repeated
 * authoritative scans report. These tests pin that decision, because getting it wrong is
 * invisible in the happy path — a wrongly-settled row silently disappears from the open
 * list and looks like the module lost it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CompletionSettlementTest {
    private Context context;
    private DeadlineStore store;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        DeadlineStore.invalidateSettingCaches();
        store = new DeadlineStore(context);
    }

    @After
    public void tearDown() {
        store.clear();
        DeadlineStore.invalidateSettingCaches();
    }

    @Test
    public void openItemSurvivesSettlement() {
        store.upsert(item("作业", "还没到期", dueIn(48)));
        store.settleFinished();

        List<DeadlineItem> open = store.openItems();
        assertEquals("an unsubmitted, not-yet-due item stays open", 1, open.size());
        assertTrue(store.recentlyFinished(0L).isEmpty());
    }

    @Test
    public void overdueItemIsSettledWithoutBeingSubmitted() {
        store.upsert(item("作业", "早就过期", dueIn(-TimeUnit.HOURS.toMillis(2))));
        store.settleFinished();

        assertTrue("an item past its deadline leaves the open list", store.openItems().isEmpty());
        assertEquals(1, store.recentlyFinished(0L).size());
    }

    @Test
    public void submissionStateSettlesEvenBeforeTheDeadline() {
        DeadlineItem early = item("作业", "提前交的", dueIn(48));
        early.setSubmissionState(DeadlineItem.SUBMISSION_SUBMITTED);
        store.upsert(early);
        store.settleFinished();

        assertTrue("submitted early must not sit in the open list", store.openItems().isEmpty());
        assertEquals(1, store.recentlyFinished(0L).size());
    }

    @Test
    public void manualMarkSettlesImmediately() {
        store.upsert(item("作业", "手动勾掉", dueIn(48)));
        assertEquals(1, store.openItems().size());
        String id = store.openItems().get(0).id;

        store.setSubmitted(id, true);

        assertTrue("the manual tap settles the row without waiting for a scan", store.openItems().isEmpty());
        assertEquals(1, store.recentlyFinished(0L).size());
    }

    @Test
    public void unmarkingReopensTheItem() {
        store.upsert(item("作业", "勾错了要撤", dueIn(48)));
        String id = store.openItems().get(0).id;
        store.setSubmitted(id, true);
        store.setSubmitted(id, false);

        assertEquals("un-marking must put the item back in the open list", 1, store.openItems().size());
        assertTrue(store.recentlyFinished(0L).isEmpty());
    }

    @Test
    public void vanishedAfterTwoAuthoritativeRoundsSettles() {
        store.upsert(item("作业", "被撤销了", dueIn(48)));
        String courseKey = "7|9";

        // Round one: not reported. One miss is not proof — a passive capture can arrive
        // after the round snapshot.
        store.applyScanRound(new String[]{courseKey}, new String[]{});
        assertEquals("one miss must not settle anything", 1, store.openItems().size());

        // Round two: still absent, so the item is judged gone.
        store.applyScanRound(new String[]{courseKey}, new String[]{});
        assertTrue("two authoritative misses settle the row", store.openItems().isEmpty());
        assertEquals(1, store.recentlyFinished(0L).size());
    }

    @Test
    public void reappearingItemIsResurrected() {
        store.upsert(item("作业", "又回来了", dueIn(48)));
        String id = store.openItems().get(0).id;
        String courseKey = "7|9";

        store.applyScanRound(new String[]{courseKey}, new String[]{});
        store.applyScanRound(new String[]{courseKey}, new String[]{});
        assertTrue(store.openItems().isEmpty());

        // The teacher re-opened it, or it was only temporarily missing: a later round
        // reporting it again must clear the miss state.
        store.applyScanRound(new String[]{courseKey}, new String[]{id});

        assertEquals("a re-reported item returns to the open list", 1, store.openItems().size());
        assertTrue(store.recentlyFinished(0L).isEmpty());
    }

    @Test
    public void chapterPointsAreNotSettledByAbsence() {
        // Chapter task points are passive-only: no active scan endpoint reports them, so
        // "not re-reported" carries no information and must not close them.
        store.upsert(item("章节", "章节任务点", dueIn(48)));
        String courseKey = "7|9";

        store.applyScanRound(new String[]{courseKey}, new String[]{});
        store.applyScanRound(new String[]{courseKey}, new String[]{});
        store.settleFinished();

        assertEquals("a chapter point stays open until it is done or due",
                1, store.openItems().size());
    }

    @Test
    public void finishedWindowHidesOldEntries() {
        store.upsert(item("作业", "上周做完的", dueIn(-TimeUnit.DAYS.toMillis(30))));
        store.settleFinished();

        assertEquals("a month-old completion still counts as finished",
                1, store.recentlyFinished(0L).size());
        assertTrue("but it is outside the two-week display window",
                store.recentlyFinished(DeadlineStore.SETTLED_WINDOW_MS).isEmpty());
    }

    @Test
    public void openItemsAreOrderedByDeadline() {
        // Distinct task ids per row: the store deliberately converges same-task captures
        // into one row, so reusing the title/id shape would test the dedup, not the order.
        store.upsert(item("作业", "晚的", dueIn(72), "w-3"));
        store.upsert(item("作业", "早的", dueIn(24), "w-1"));
        store.upsert(item("作业", "中间的", dueIn(48), "w-2"));

        List<DeadlineItem> open = store.openItems();
        assertEquals(3, open.size());
        assertTrue("soonest deadline first",
                open.get(0).dueAt < open.get(1).dueAt && open.get(1).dueAt < open.get(2).dueAt);
    }

    @Test
    public void courseDetailListsOnlyThatCourse() {
        store.upsert(item("作业", "甲课的作业", dueIn(24)));
        DeadlineItem other = item("作业", "乙课的作业", dueIn(48));
        other.course = "概率论";
        store.upsert(other);

        assertEquals(1, store.openItemsOfCourse("高等数学").size());
        assertEquals("概率论", store.openItemsOfCourse("概率论").get(0).course);
    }

    @Test
    public void settlementIsIdempotent() {
        store.upsert(item("作业", "过期了", dueIn(-TimeUnit.HOURS.toMillis(1))));
        int first = store.settleFinished();
        int second = store.settleFinished();

        assertEquals("the first pass settles the row", 1, first);
        assertEquals("a second pass finds nothing left to settle", 0, second);
        assertEquals("and the window list does not duplicate it",
                1, store.recentlyFinished(0L).size());
    }

    private long dueIn(long millis) {
        return System.currentTimeMillis() + millis;
    }

    private static DeadlineItem item(String type, String title, long dueAt) {
        return item(type, title, dueAt, "");
    }

    private static DeadlineItem item(String type, String title, long dueAt, String taskId) {
        DeadlineItem item = new DeadlineItem();
        item.taskId = taskId;
        item.type = type;
        item.title = title;
        item.course = "高等数学";
        item.courseConfidence = 100;
        item.dueAt = dueAt;
        item.courseId = "7";
        item.classId = "9";
        item.setSubmissionState(DeadlineItem.SUBMISSION_UNSUBMITTED);
        item.source = "active.workList";
        return item;
    }
}
