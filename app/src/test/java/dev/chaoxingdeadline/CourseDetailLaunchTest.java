package dev.chaoxingdeadline;

import android.content.Context;
import android.content.Intent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertNotNull;

/**
 * The course detail screen must survive being opened for every shape of course data.
 *
 * It builds its views from three different sources at once — open items, recently finished
 * items and the chapter progress roll-up — and each can be empty independently. A first
 * launch on a fresh install hits all three empty at the same time, which is exactly the case
 * a device test would never reach after real data exists.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CourseDetailLaunchTest {
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
    public void opensForACourseWithNoDataAtAll() {
        launch("高等数学");
    }

    @Test
    public void opensForACourseWithOpenAndFinishedWork() {
        DeadlineItem open = item(DeadlineStore.TYPE_HOMEWORK, "还没做的", dueIn(48));
        store.upsert(open);
        DeadlineItem done = item(DeadlineStore.TYPE_EXAM, "考完的", dueIn(-TimeUnit.HOURS.toMillis(2)));
        store.upsert(done);
        store.settleFinished();

        launch("高等数学");
    }

    @Test
    public void opensWithChapterProgress() {
        DeadlineItem chapter = item(DeadlineStore.TYPE_CHAPTER, "第一章", dueIn(24));
        chapter.raw = "{\"name\":\"第一章\",\"jobcount\":5,\"jobUnfinishedCount\":3}";
        store.upsert(chapter);

        launch("高等数学");
    }

    @Test
    public void opensForAMissingCourseName() {
        // The overview passes a display name, but a deep link or a stale extra could arrive
        // without one; the screen has to fall back to its generic title instead of crashing.
        store.upsert(item(DeadlineStore.TYPE_HOMEWORK, "有课名的", dueIn(24)));
        launch("");
        launch(null);
    }

    private void launch(String course) {
        Intent intent = new Intent(context, CourseDetailActivity.class);
        if (course != null) {
            intent.putExtra(CourseDetailActivity.EXTRA_COURSE, course);
        }
        ActivityController<CourseDetailActivity> controller =
                Robolectric.buildActivity(CourseDetailActivity.class, intent).setup();
        assertNotNull("course detail survived creation for course=" + course, controller.get());
        controller.pause().stop().destroy();
    }

    private static long dueIn(long millis) {
        return System.currentTimeMillis() + millis;
    }

    private static DeadlineItem item(String type, String title, long dueAt) {
        DeadlineItem item = new DeadlineItem();
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
