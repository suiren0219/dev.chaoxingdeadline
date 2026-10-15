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

import java.util.Arrays;
import java.util.List;

/**
 * Smoke coverage for every user-visible entry point.
 *
 * The 1.16.0 launch crash (an unguarded write to a view that only exists on another tab)
 * and the widget's RemoteViews work both fail only at runtime — compiling and unit-testing
 * pure helpers proves nothing about them. So this boots each activity, and inflates and
 * applies the widget layouts through the real RemoteViews path, which is where an unknown
 * view id or an unsupported remote method surfaces.
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
                ScheduleActivity.class,
                SettingsActivity.class,
                AboutActivity.class,
                StatsActivity.class,
                BackupActivity.class,
                MonthCalendarActivity.class,
        };
        for (Class<?> activityClass : activities) {
            ActivityController<? extends Activity> controller =
                    Robolectric.buildActivity((Class<Activity>) activityClass).setup();
            assertNotNull(activityClass.getSimpleName() + " survived creation", controller.get());
            controller.pause().stop().destroy();
        }
    }

    @Test
    public void courseBlockActivityLaunchesWithALesson() {
        android.content.Intent intent = new android.content.Intent(context(),
                CourseBlockActivity.class);
        intent.putExtra("lesson_name", "\u793a\u4f8b\u8bfe\u7a0b");
        intent.putExtra("lesson_day", 1);
        intent.putExtra("lesson_begin", 1);
        intent.putExtra("lesson_sections", 2);
        intent.putExtra("lesson_weeks", "1-16");
        ActivityController<CourseBlockActivity> controller =
                Robolectric.buildActivity(CourseBlockActivity.class, intent).setup();
        assertNotNull(controller.get());
        controller.pause().stop().destroy();
    }

    /**
     * The merged widget's timetable face, rendered with real RemoteViews.
     *
     * A lesson with everything filled in, so every view in widget_schedule_card.xml is
     * touched: the strip, the two-line time, the name, the meta line and the live chip.
     */
    @Test
    public void widgetRendersTheTimetableFaceWithRealRemoteViews() {
        Context context = context();
        DeadlineStore store = new DeadlineStore(context);
        long firstWeekDate = mondayOfThisWeek();
        store.replaceLessons(
                Arrays.asList(
                        new Lesson("L1", "\u793a\u4f8b\u8bfe\u7a0b\u7532", "\u8001\u5e08\u7532",
                                "\u6559\u4e00\u4e00\u4e00", dayOfWeek(), 1, 2, "1-16"),
                        new Lesson("L2", "\u793a\u4f8b\u8bfe\u7a0b\u4e59", "",
                                "", dayOfWeek(), 4, 1, "1-16")),
                firstWeekDate, "[\"08:00-08:45\",\"08:55-09:40\",\"10:00-10:45\","
                        + "\"10:55-11:40\"]");

        RemoteViews views = DeadlineScheduleWidget.buildForTest(context, 1, 0);
        assertNotNull("\u8bfe\u8868\u9762 built", views);
        assertNotNull("applied \u8bfe\u8868\u9762 without throwing", views.apply(context, null));
    }

    /** The deadline face, which builds its rows from a different layout and provider. */
    @Test
    public void widgetRendersTheTodoFaceWithRealRemoteViews() {
        Context context = context();
        DeadlineItem item = new DeadlineItem();
        item.id = "T1";
        item.type = "\u4f5c\u4e1a";
        item.title = "\u793a\u4f8b\u4f5c\u4e1a";
        item.course = "\u793a\u4f8b\u8bfe\u7a0b";
        item.dueAt = System.currentTimeMillis() + 3_600_000L;
        item.setSubmissionState(DeadlineItem.SUBMISSION_UNSUBMITTED);
        new DeadlineStore(context).upsert(item);

        RemoteViews views = DeadlineScheduleWidget.buildForTest(context, 1,
                DeadlineScheduleWidget.FACE_TODO);
        assertNotNull("\u5f85\u529e\u9762 built", views);
        assertNotNull("applied \u5f85\u529e\u9762 without throwing", views.apply(context, null));
    }

    /** The empty states, which the earlier design reached through different views. */
    @Test
    public void widgetRendersEmptyStates() {
        Context context = context();
        // No lessons stored at all, so the timetable face shows "课表未获取".
        RemoteViews emptyTimetable = DeadlineScheduleWidget.buildForTest(context, 2,
                DeadlineScheduleWidget.FACE_TIMETABLE);
        assertNotNull(emptyTimetable.apply(context, null));

        RemoteViews emptyTodo = DeadlineScheduleWidget.buildForTest(context, 2,
                DeadlineScheduleWidget.FACE_TODO);
        assertNotNull(emptyTodo.apply(context, null));
    }

    /** Any height a launcher might report must still produce a renderable panel. */
    @Test
    public void widgetRendersAtEveryRowBudgetFromOneToMax() {
        Context context = context();
        DeadlineStore store = new DeadlineStore(context);
        long firstWeekDate = mondayOfThisWeek();
        List<Lesson> lessons = new java.util.ArrayList<>();
        for (int section = 1; section <= 8; section++) {
            lessons.add(new Lesson("S" + section, "\u793a\u4f8b\u8bfe\u7a0b" + section, "",
                    "\u6559\u5ba4" + section, dayOfWeek(), section, 1, "1-16"));
        }
        store.replaceLessons(lessons, firstWeekDate, "[\"08:00-08:45\",\"08:55-09:40\","
                + "\"10:00-10:45\",\"10:55-11:40\",\"14:00-14:45\",\"14:55-15:40\","
                + "\"16:00-16:45\",\"16:55-17:40\"]");

        for (int rows = 1; rows <= 8; rows++) {
            RemoteViews views = DeadlineScheduleWidget.buildForTest(
                    context, 10 + rows, DeadlineScheduleWidget.FACE_TIMETABLE, rows);
            assertNotNull("rows=" + rows + " applied", views.apply(context, null));
        }
    }

    /** The 2x2 deadline card and the list widget, which share the provider's row builders. */
    @Test
    public void otherWidgetsStillRender() {
        Context context = context();
        assertNotNull(DeadlineWidgetProvider.buildForTest(context));
    }

    /**
     * The settings page with an empty timetable, which is where the crash was reported.
     *
     * The class-reminder dialog calls updateClassReminder on the UI thread, and the page is
     * opened before any timetable exists — so this boot is the exact state that crashed.
     */
    @Test
    public void settingsPageOpensWithNoTimetableAndNoDeadlines() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        assertNotNull(controller.get());
        controller.pause().stop().destroy();
    }

    @Test
    public void classReminderCanBePostedAndCancelledOnEveryState() {
        Context context = context();
        // Covers "off", "nothing to remind", and a scheduled lesson, in both entry points.
        AppSettings.setClassReminderMinutes(context, 0);
        DeadlineNotifier.updateClassReminder(context);
        DeadlineNotifier.scheduleClassReminder(context);
        DeadlineNotifier.cancelClassReminder(context);
        for (int minutes : AppSettings.CLASS_REMINDER_OPTIONS) {
            AppSettings.setClassReminderMinutes(context, minutes);
            DeadlineNotifier.updateClassReminder(context);
            DeadlineNotifier.scheduleClassReminder(context);
        }
        AppSettings.setClassReminderMinutes(context, AppSettings.DEFAULT_CLASS_REMINDER_MINUTES);
    }

    @Test
    public void tileServiceInstantiates() {
        assertNotNull(Robolectric.buildService(DeadlineTileService.class).create().get());
    }

    @Test
    public void appStartupPathRunsWithoutCrashing() {
        App app = (App) context();
        app.onCreate();
        assertTrue("startup completed", true);
    }

    // -- helpers --

    /** Monday 00:00 of the current week, so "today" always has lessons on it. */
    private static long mondayOfThisWeek() {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.setTimeInMillis(System.currentTimeMillis());
        calendar.add(java.util.Calendar.DAY_OF_MONTH,
                -(ScheduleMath.todayDayOfWeek(calendar.getTimeInMillis()) - 1));
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0);
        calendar.set(java.util.Calendar.MINUTE, 0);
        calendar.set(java.util.Calendar.SECOND, 0);
        calendar.set(java.util.Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private static int dayOfWeek() {
        return ScheduleMath.todayDayOfWeek(System.currentTimeMillis());
    }
}
