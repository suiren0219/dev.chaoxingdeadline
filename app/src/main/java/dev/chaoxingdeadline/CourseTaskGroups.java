package dev.chaoxingdeadline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Grouping for the course-task overview: one section per course, open work first.
 *
 * The overview answers "what do I still owe, and for which course", which is a different
 * question from the flat deadline list: order inside a course is by deadline, courses are
 * ordered by how urgent their nearest item is, and finished work is kept apart so it cannot
 * push an open task off the screen.
 *
 * Pure data, no Android types, so the ordering and counting rules are unit-testable — they
 * are what the user reads as "3 项未完成".
 */
final class CourseTaskGroups {

    private CourseTaskGroups() {
    }

    /** One course's slice of the overview. */
    static final class Group {
        final String course;
        final List<DeadlineItem> open;
        final List<DeadlineItem> finished;
        /** Nearest open deadline, 0 when nothing is open. Drives group ordering. */
        final long nextDueAt;

        Group(String course, List<DeadlineItem> open, List<DeadlineItem> finished, long nextDueAt) {
            this.course = course;
            this.open = open;
            this.finished = finished;
            this.nextDueAt = nextDueAt;
        }

        boolean hasOpen() {
            return !open.isEmpty();
        }

        int openCount() {
            return open.size();
        }
    }

    /**
     * Builds the overview from open and finished items.
     *
     * @param open     items the user can still act on (see {@link DeadlineStore#openItems()})
     * @param finished recently finished items, shown collapsed per course
     */
    static List<Group> build(List<DeadlineItem> open, List<DeadlineItem> finished) {
        LinkedHashMap<String, List<DeadlineItem>> openByCourse = new LinkedHashMap<>();
        LinkedHashMap<String, List<DeadlineItem>> doneByCourse = new LinkedHashMap<>();
        if (open != null) {
            for (DeadlineItem item : open) {
                if (item == null) {
                    continue;
                }
                openByCourse.computeIfAbsent(courseOf(item), k -> new ArrayList<>()).add(item);
            }
        }
        if (finished != null) {
            for (DeadlineItem item : finished) {
                if (item == null) {
                    continue;
                }
                doneByCourse.computeIfAbsent(courseOf(item), k -> new ArrayList<>()).add(item);
            }
        }

        // Every course that has either kind of work gets a section; a course with only
        // finished work still appears, because "this one is cleared" is information.
        LinkedHashMap<String, Boolean> courses = new LinkedHashMap<>();
        for (String course : openByCourse.keySet()) {
            courses.put(course, Boolean.TRUE);
        }
        for (String course : doneByCourse.keySet()) {
            courses.putIfAbsent(course, Boolean.TRUE);
        }

        ArrayList<Group> groups = new ArrayList<>();
        for (String course : courses.keySet()) {
            List<DeadlineItem> openItems = openByCourse.getOrDefault(course, Collections.emptyList());
            List<DeadlineItem> doneItems = doneByCourse.getOrDefault(course, Collections.emptyList());
            // Inside a course, soonest deadline first — the order the user works in.
            sortByDue(openItems);
            sortByDue(doneItems);
            long next = 0L;
            for (DeadlineItem item : openItems) {
                if (next == 0L || (item.dueAt > 0L && item.dueAt < next)) {
                    next = item.dueAt;
                }
            }
            groups.add(new Group(course, openItems, doneItems, next));
        }

        // Courses needing attention float to the top; among them, the one whose next
        // deadline is closest wins. Courses with nothing open sort last by name so their
        // position is stable instead of shuffling between refreshes.
        groups.sort(Comparator
                .comparing((Group group) -> group.hasOpen() ? 0 : 1)
                .thenComparingLong(group -> group.hasOpen() && group.nextDueAt > 0L
                        ? group.nextDueAt : Long.MAX_VALUE)
                .thenComparing(group -> group.course, String.CASE_INSENSITIVE_ORDER));
        return groups;
    }

    /** Items of one type across every group, for the type filter. */
    static List<DeadlineItem> filterByType(List<Group> groups, String type) {
        ArrayList<DeadlineItem> result = new ArrayList<>();
        if (groups == null) {
            return result;
        }
        for (Group group : groups) {
            for (DeadlineItem item : group.open) {
                if (type == null || type.isEmpty() || type.equals(item.type)) {
                    result.add(item);
                }
            }
        }
        return result;
    }

    /** Case-insensitive match against the task title or course name. */
    static boolean matches(DeadlineItem item, String query) {
        if (item == null) {
            return false;
        }
        if (query == null || query.trim().isEmpty()) {
            return true;
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        return contains(item.title, needle) || contains(item.course, needle);
    }

    /** Keeps the groups that still have something to show; drops empty ones. */
    static List<Group> narrow(List<Group> groups, String query, String type) {
        ArrayList<Group> result = new ArrayList<>();
        if (groups == null) {
            return result;
        }
        for (Group group : groups) {
            ArrayList<DeadlineItem> keptOpen = new ArrayList<>();
            for (DeadlineItem item : group.open) {
                if (matches(item, query) && (type == null || type.isEmpty() || type.equals(item.type))) {
                    keptOpen.add(item);
                }
            }
            ArrayList<DeadlineItem> keptDone = new ArrayList<>();
            for (DeadlineItem item : group.finished) {
                if (matches(item, query) && (type == null || type.isEmpty() || type.equals(item.type))) {
                    keptDone.add(item);
                }
            }
            if (keptOpen.isEmpty() && keptDone.isEmpty()) {
                continue;
            }
            long next = 0L;
            for (DeadlineItem item : keptOpen) {
                if (next == 0L || (item.dueAt > 0L && item.dueAt < next)) {
                    next = item.dueAt;
                }
            }
            result.add(new Group(group.course, keptOpen, keptDone, next));
        }
        return result;
    }

    /** Total open items across every group — the number shown in the tab badge. */
    static int openCount(List<Group> groups) {
        int total = 0;
        if (groups == null) {
            return 0;
        }
        for (Group group : groups) {
            total += group.open.size();
        }
        return total;
    }

    private static void sortByDue(List<DeadlineItem> items) {
        items.sort(Comparator
                .comparingLong((DeadlineItem item) -> item.dueAt <= 0L ? Long.MAX_VALUE : item.dueAt)
                .thenComparing(item -> item.title == null ? "" : item.title));
    }

    private static String courseOf(DeadlineItem item) {
        return item.course == null || item.course.trim().isEmpty()
                ? "未识别课程" : item.course.trim();
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }
}
