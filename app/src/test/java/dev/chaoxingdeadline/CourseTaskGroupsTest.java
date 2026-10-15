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
 * Ordering and filtering rules of the course-task overview.
 *
 * These decide what the user sees first, so they are pinned here: an open task must never be
 * pushed below a finished one, and a course with an imminent deadline must outrank a course
 * that is already cleared.
 */
public class CourseTaskGroupsTest {

    @Test
    public void openCoursesOutrankClearedOnes() {
        List<CourseTaskGroups.Group> groups = CourseTaskGroups.build(
                Arrays.asList(item("作业", "还没做的", "甲课", dueIn(1))),
                Arrays.asList(item("作业", "已做完的", "乙课", dueIn(-100))));

        assertEquals("甲课", groups.get(0).course);
        assertTrue("the open course comes first", groups.get(0).hasOpen());
        assertEquals("乙课", groups.get(1).course);
        assertFalse("the cleared course still appears, but last", groups.get(1).hasOpen());
    }

    @Test
    public void urgentCourseSortsAboveLaterOne() {
        List<CourseTaskGroups.Group> groups = CourseTaskGroups.build(Arrays.asList(
                item("作业", "后天的", "乙课", dueIn(200)),
                item("作业", "一小时的", "甲课", dueIn(1))), Collections.emptyList());

        assertEquals("甲课", groups.get(0).course);
        assertEquals("乙课", groups.get(1).course);
    }

    @Test
    public void itemsInsideACourseAreOrderedByDeadline() {
        List<CourseTaskGroups.Group> groups = CourseTaskGroups.build(Arrays.asList(
                item("作业", "晚", "甲课", dueIn(300)),
                item("作业", "早", "甲课", dueIn(50)),
                item("作业", "中", "甲课", dueIn(100))), Collections.emptyList());

        List<DeadlineItem> open = groups.get(0).open;
        assertEquals(3, open.size());
        assertTrue("soonest first", open.get(0).dueAt < open.get(1).dueAt);
        assertTrue(open.get(1).dueAt < open.get(2).dueAt);
    }

    @Test
    public void everyTypeLandsInItsCourseGroup() {
        List<CourseTaskGroups.Group> groups = CourseTaskGroups.build(Arrays.asList(
                item("作业", "作业", "甲课", dueIn(10)),
                item("考试", "考试", "甲课", dueIn(20)),
                item("章节", "章节", "甲课", dueIn(30)),
                item("课堂活动", "活动", "甲课", dueIn(40))), Collections.emptyList());

        assertEquals("one section per course, not per type", 1, groups.size());
        assertEquals(4, groups.get(0).openCount());
    }

    @Test
    public void itemsWithoutACourseGetTheirOwnBucket() {
        List<CourseTaskGroups.Group> groups = CourseTaskGroups.build(Arrays.asList(
                item("作业", "没识别出课的", "", dueIn(10))), Collections.emptyList());

        assertEquals(1, groups.size());
        assertEquals("未识别课程", groups.get(0).course);
    }

    @Test
    public void searchMatchesTitleAndCourse() {
        DeadlineItem byTitle = item("作业", "第三章作业", "高等数学", dueIn(10));
        DeadlineItem byCourse = item("作业", "别的名称", "概率论", dueIn(10));

        assertTrue(CourseTaskGroups.matches(byTitle, "第三"));
        assertTrue("course name is searched too", CourseTaskGroups.matches(byCourse, "概率"));
        assertFalse(CourseTaskGroups.matches(byTitle, "找不到的词"));
        assertTrue("an empty query matches everything", CourseTaskGroups.matches(byTitle, ""));
    }

    @Test
    public void narrowingDropsEmptyGroups() {
        List<CourseTaskGroups.Group> groups = CourseTaskGroups.build(Arrays.asList(
                item("作业", "甲的作业", "甲课", dueIn(10)),
                item("考试", "乙的考试", "乙课", dueIn(20))), Collections.emptyList());

        List<CourseTaskGroups.Group> kept = CourseTaskGroups.narrow(groups, "", "考试");
        assertEquals("only the course with a match survives", 1, kept.size());
        assertEquals("乙课", kept.get(0).course);
        assertEquals("and only its matching item", 1, kept.get(0).openCount());
    }

    @Test
    public void narrowingRecomputesTheNearestDeadline() {
        List<CourseTaskGroups.Group> groups = CourseTaskGroups.build(Arrays.asList(
                item("作业", "很近的作业", "甲课", dueIn(1)),
                item("考试", "很远的考试", "甲课", dueIn(999))), Collections.emptyList());

        List<CourseTaskGroups.Group> kept = CourseTaskGroups.narrow(groups, "", "考试");
        // If the filter left the original nextDueAt in place the group would still claim an
        // imminent deadline and keep sorting as if the urgent item were visible.
        assertTrue("next due reflects the filtered set",
                kept.get(0).nextDueAt > System.currentTimeMillis() + 900_000L);
    }

    @Test
    public void countsAndEmptyInputAreSafe() {
        assertTrue(CourseTaskGroups.build(null, null).isEmpty());
        assertTrue(CourseTaskGroups.narrow(null, "x", "y").isEmpty());
        assertTrue(CourseTaskGroups.filterByType(null, "作业").isEmpty());
        assertEquals(0, CourseTaskGroups.openCount(null));
        assertFalse(CourseTaskGroups.matches(null, "x"));

        List<CourseTaskGroups.Group> groups = CourseTaskGroups.build(Arrays.asList(
                item("作业", "a", "甲", dueIn(1)),
                item("作业", "b", "乙", dueIn(2))), Collections.emptyList());
        assertEquals(2, CourseTaskGroups.openCount(groups));
    }

    @Test
    public void itemsWithoutADeadlineSortLastNotFirst() {
        DeadlineItem noDue = item("章节", "没有截止时间", "甲课", 0L);
        List<CourseTaskGroups.Group> groups = CourseTaskGroups.build(
                Arrays.asList(noDue, item("作业", "有截止", "甲课", dueIn(10))),
                Collections.emptyList());

        List<DeadlineItem> open = groups.get(0).open;
        assertEquals("a dateless item must not be treated as most urgent",
                "有截止", open.get(0).title);
    }

    private static long dueIn(long minutes) {
        return System.currentTimeMillis() + minutes * 60_000L;
    }

    private static DeadlineItem item(String type, String title, String course, long dueAt) {
        DeadlineItem item = new DeadlineItem();
        item.type = type;
        item.title = title;
        item.course = course;
        item.dueAt = dueAt;
        item.courseId = "c-" + course;
        item.classId = "k-" + course;
        item.setSubmissionState(DeadlineItem.SUBMISSION_UNSUBMITTED);
        item.source = "active.workList";
        item.id = type + "|" + title;
        return item;
    }
}
