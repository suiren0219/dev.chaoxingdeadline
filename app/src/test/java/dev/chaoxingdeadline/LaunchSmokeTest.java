package dev.chaoxingdeadline;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.widget.RemoteViews;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * Smoke coverage for every user-visible entry point.
 *
 * An earlier release crashed on launch because one tab wrote to a view that only exists on
 * another, and the widget's RemoteViews work fails the same way — at runtime only. So this
 * boots each activity, and inflates and applies the widget layouts through the real
 * RemoteViews path, which is where an unknown view id or an unsupported remote method
 * surfaces. Neither the compiler nor a test of a pure helper can see those failures.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LaunchSmokeTest {

    private static Context context() {
        return RuntimeEnvironment.getApplication();
    }

    @Test
    public void everyActivityLaunches() {
        Class<?>[] activities = {
                MainActivity.class,
                SettingsActivity.class,
                AboutActivity.class,
                CourseBlockActivity.class,
        };
        for (Class<?> activityClass : activities) {
            ActivityController<? extends Activity> controller =
                    Robolectric.buildActivity((Class<Activity>) activityClass).setup();
            assertNotNull(activityClass.getSimpleName() + " survived creation", controller.get());
            controller.pause().stop().destroy();
        }
    }

    @Test
    public void widgetRendersWithRealRemoteViews() {
        Context context = context();
        DeadlineItem item = new DeadlineItem();
        item.type = DeadlineStore.TYPE_HOMEWORK;
        item.title = "\u793a\u4f8b\u4f5c\u4e1a";
        item.course = "\u793a\u4f8b\u8bfe\u7a0b";
        item.dueAt = System.currentTimeMillis() + 3_600_000L;
        item.setSubmissionState(DeadlineItem.SUBMISSION_UNSUBMITTED);
        new DeadlineStore(context).upsert(item);

        RemoteViews views = DeadlineScheduleWidget.buildForTest(context, 1, -1);
        assertNotNull("widget built", views);
        assertNotNull("applied without throwing", views.apply(context, null));
    }

    /** The empty state, which a fresh install always shows first. */
    @Test
    public void widgetRendersEmptyState() {
        Context context = context();
        RemoteViews empty = DeadlineScheduleWidget.buildForTest(context, 2, -1);
        assertNotNull(empty.apply(context, null));
    }

    /** Any height a launcher might report must still produce a renderable panel. */
    @Test
    public void widgetRendersAtEveryRowBudget() {
        Context context = context();
        for (int rows = 1; rows <= 8; rows++) {
            RemoteViews views = DeadlineScheduleWidget.buildForTest(context, 10 + rows, rows);
            assertNotNull("rows=" + rows + " applied", views.apply(context, null));
        }
    }

    /** The 2x2 deadline card and the list widget, which share the provider's row builders. */
    @Test
    public void otherWidgetsStillRender() {
        Context context = context();
        assertNotNull(DeadlineWidgetProvider.buildForTest(context));
    }

    @Test
    public void appStartupPathRunsWithoutCrashing() {
        App app = (App) context();
        app.onCreate();
        assertTrue("startup completed", true);
    }
}
