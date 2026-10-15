package dev.chaoxingdeadline;

import android.content.Context;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Robolectric coverage for DeadlineStore: the upsert/dedup contract, the 1.9.0
 * type-migration helper, cross-type ignore rules and the retention prune.
 * Pinned to SDK 34 so the suite does not depend on a matching android-all drop
 * for the current compileSdk.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class DeadlineStoreTest {
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
    public void mergesSameTaskCapturedWithDifferentTaskIds() {
        // The work task-list and the classroom-activity list number their tasks
        // independently; the same homework therefore arrives with different taskIds.
        store.upsert(item("作业", "第三章作业", dueIn(48), "work-111", "7", "9"));
        store.upsert(item("作业", "第三章作业", dueIn(48), "activity-222", "7", "9"));
        assertEquals("different channel ids must still collapse to one row",
                1, store.activeItems().size());
    }

    @Test
    public void mergesRowCapturedWithoutCourseReference() {
        // A passively intercepted payload carries no course context; whichever row
        // arrives second, the duplicate must still be merged.
        store.upsert(item("作业", "第三章作业", dueIn(48), "", "", ""));
        store.upsert(item("作业", "第三章作业", dueIn(48), "work-111", "7", "9"));
        assertEquals("no-ref row first, reffed row second",
                1, store.activeItems().size());

        store.clear();
        store.upsert(item("作业", "第三章作业", dueIn(48), "work-111", "7", "9"));
        store.upsert(item("作业", "第三章作业", dueIn(48), "", "", ""));
        assertEquals("reffed row first, no-ref row second",
                1, store.activeItems().size());
    }

    @Test
    public void mergesPassiveAndActiveRowsAcrossCourseIdSpaces() {
        // The two id spaces that kept duplicates alive: the active scan writes the real
        // course/class ids, a passive capture writes ids derived from the course name.
        DeadlineItem active = item("作业", "第三章作业", dueIn(48), "work-111", "7", "9");
        DeadlineItem passive = item("作业", "第三章作业", dueIn(48), "", "legacy:9f3a", "legacy:1c2d");
        store.upsert(passive);
        store.upsert(active);

        List<DeadlineItem> items = store.activeItems();
        assertEquals("the passive row must adopt the active row's identity, not sit beside it",
                1, items.size());
        assertEquals("the richer identity survives the merge", "work-111", items.get(0).taskId);
        assertEquals("7", items.get(0).courseId);
    }

    @Test
    public void repairMergesHistoricalDuplicates() {
        // Rows written by an older build: two ids for the same task, inserted straight
        // into the table so the upsert-time dedup cannot hide them.
        DeadlineItem active = item("作业", "第三章作业", dueIn(48), "work-111", "7", "9");
        DeadlineItem passive = item("作业", "第三章作业", dueIn(48), "", "legacy:9f3a", "legacy:1c2d");
        insertRaw(active);
        insertRaw(passive);
        assertEquals("two rows before the repair", 2, store.activeItems().size());

        assertEquals("countDuplicates must not silently read an empty plan", 1, store.countDuplicates());
        assertEquals(1, store.repairDuplicates());

        List<DeadlineItem> items = store.activeItems();
        assertEquals(1, items.size());
        assertEquals("the keeper absorbs the task id of the row it replaces",
                "work-111", items.get(0).taskId);
        assertEquals(0, store.countDuplicates());
    }

    @Test
    public void repairKeepsManualEntriesAndDistinctTasks() {
        DeadlineItem manual = item("作业", "手动作业", dueIn(48), "", "7", "9");
        manual.source = "manual";
        store.upsert(manual);
        store.upsert(item("作业", "第三章作业", dueIn(48), "w1", "7", "9"));
        store.upsert(item("作业", "第四章作业", dueIn(48), "w2", "7", "9"));
        store.upsert(item("考试", "第三章作业", dueIn(48), "e1", "7", "9"));
        assertEquals("manual rows, other titles and other types are untouched",
                0, store.countDuplicates());
    }

    @Test
    public void keepsDistinctTasksApart() {
        store.upsert(item("作业", "第三章作业", dueIn(48), "work-111", "7", "9"));
        store.upsert(item("作业", "第四章作业", dueIn(48), "work-222", "7", "9"));
        store.upsert(item("作业", "第三章作业", dueIn(48), "work-333", "8", "9"));
        assertEquals("different titles or courses are different tasks",
                3, store.activeItems().size());
    }


    @Test
    public void ignoreRuleMatchesAcrossTypeChange() {
        store.upsert(item("作业", "第三讲签到", dueIn(24), "act-1", "7", "9"));
        String id = store.activeItems().get(0).id;
        store.deleteItem(id);
        // The same task re-captured under another tracked type must stay ignored.
        store.upsert(item("章节", "第三讲签到", dueIn(24), "act-1", "7", "9"));
        assertEquals("an ignored task must stay ignored across the type change",
                0, store.activeItems().size());
    }

    @Test
    public void ignoreRuleDoesNotLeakToSameIdDifferentTask() {
        store.upsert(item("作业", "第三讲签到", dueIn(24), "act-1", "7", "9"));
        store.deleteItem(store.activeItems().get(0).id);
        // Same id namespace, different task: the title cross-check must keep it.
        store.upsert(item("章节", "第三周投票", dueIn(24), "act-1", "7", "9"));
        assertEquals(1, store.activeItems().size());
    }

    @Test
    public void keepsOverdueWorkButReclaimsFinishedRows() {
        store.upsert(item("作业", "上古作业",
                System.currentTimeMillis() - TimeUnit.DAYS.toMillis(100), "w-1", "7", "9"));
        assertTrue("overdue work is never reclaimed — it is still owed",
                containsTitle(store.activeItems(), "上古作业"));

        store.upsert(item("作业", "刚截止的作业",
                System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30), "w-2", "7", "9"));
        assertTrue("recently overdue rows stay visible",
                containsTitle(store.activeItems(), "刚截止的作业"));

        // Only finished work eventually goes away, and only after the retention window.
        DeadlineItem done = item("作业", "很久以前做完的",
                System.currentTimeMillis() - TimeUnit.DAYS.toMillis(100), "w-3", "7", "9");
        done.setSubmissionState(DeadlineItem.SUBMISSION_SUBMITTED);
        store.upsert(done);
        assertFalse("a long-finished row is reclaimed",
                containsTitle(store.activeItems(), "很久以前做完的"));
    }


    @Test
    public void flagsItemMissingFromTwoAuthoritativeScans() {
        store.upsert(item("作业", "第三章作业", dueIn(48), "work-1", "7", "9"));
        String id = store.activeItems().get(0).id;
        String[] keys = {"7|9"};
        assertEquals("first miss only counts", 0, store.applyScanRound(keys, new String[]{"other"}));
        assertEquals("second consecutive miss flags it", 1, store.applyScanRound(keys, new String[]{"other"}));
        DeadlineItem stored = store.itemById(id);
        assertNotNull(stored);
        assertTrue("vanished_at is set", stored.vanishedAt > 0L);
        assertEquals(2, stored.misses);
    }

    @Test
    public void seenItemClearsVanishedFlag() {
        store.upsert(item("作业", "第三章作业", dueIn(48), "work-1", "7", "9"));
        String id = store.activeItems().get(0).id;
        String[] keys = {"7|9"};
        store.applyScanRound(keys, new String[0]);
        store.applyScanRound(keys, new String[0]);
        assertTrue(store.itemById(id).vanishedAt > 0L);
        // The teacher put it back: re-captured in a later round, flag clears.
        store.applyScanRound(keys, new String[]{id});
        DeadlineItem after = store.itemById(id);
        assertEquals(0, after.misses);
        assertEquals(0L, after.vanishedAt);
    }

    @Test
    public void untouchedWhenCourseNotScanned() {
        store.upsert(item("作业", "第三章作业", dueIn(48), "work-1", "7", "9"));
        String id = store.activeItems().get(0).id;
        // Another course was scanned; this item's course was never authoritative.
        store.applyScanRound(new String[]{"8|9"}, new String[0]);
        store.applyScanRound(new String[]{"8|9"}, new String[0]);
        DeadlineItem stored = store.itemById(id);
        assertEquals(0, stored.misses);
        assertEquals(0L, stored.vanishedAt);
    }

    @Test
    public void manualAndChapterItemsNeverVanish() {
        DeadlineItem manual = item("作业", "手动作业", dueIn(48), "work-1", "7", "9");
        manual.source = "manual";
        store.upsert(manual);
        store.upsert(item("章节", "第三章任务点", dueIn(48), "k-1", "7", "9"));
        String[] keys = {"7|9"};
        store.applyScanRound(keys, new String[0]);
        store.applyScanRound(keys, new String[0]);
        for (DeadlineItem stored : store.activeItems()) {
            assertEquals("manual entries and chapters are never judged missing",
                    0, stored.misses);
            assertEquals(0L, stored.vanishedAt);
        }
    }

    @Test
    public void autoRemoveDeletesInsteadOfFlagging() {
        AppSettings.setAutoRemoveVanished(context, true);
        try {
            store.upsert(item("作业", "第三章作业", dueIn(48), "work-1", "7", "9"));
            String id = store.activeItems().get(0).id;
            String[] keys = {"7|9"};
            store.applyScanRound(keys, new String[0]);
            assertEquals(1, store.applyScanRound(keys, new String[0]));
            assertNull("auto-remove deletes the row outright", store.itemById(id));
        } finally {
            AppSettings.setAutoRemoveVanished(context, false);
        }
    }

















    /** Writes a row the way an older build would have: straight into the table. */
    private void insertRaw(DeadlineItem item) {
        item.id = item.stableId();
        store.getWritableDatabase().insert("deadlines", null, item.toValues());
    }

    private static boolean containsTitle(List<DeadlineItem> items, String title) {
        for (DeadlineItem item : items) {
            if (title.equals(item.title)) {
                return true;
            }
        }
        return false;
    }

    private static DeadlineItem item(String type, String title, long dueAt,
                                     String taskId, String courseId, String classId) {
        DeadlineItem item = new DeadlineItem();
        item.type = type;
        item.title = title;
        item.course = "高等数学";
        item.courseConfidence = 100;
        item.dueAt = dueAt;
        item.taskId = taskId;
        item.courseId = courseId;
        item.classId = classId;
        item.setSubmissionState(DeadlineItem.SUBMISSION_UNSUBMITTED);
        item.source = "active.workList";
        return item;
    }

    private static long dueIn(long hours) {
        return System.currentTimeMillis() + TimeUnit.HOURS.toMillis(hours);
    }
}
