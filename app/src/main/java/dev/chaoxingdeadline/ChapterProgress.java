package dev.chaoxingdeadline;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Chapter task-point progress for one course, read out of the captured chapter payloads.
 *
 * The school's chapter tree gives each leaf two numbers: {@code jobcount} (task points the
 * section contains) and {@code jobUnfinishedCount} (how many of them are still open). That
 * pair is the whole progress story — no extra request is needed, and none is sent: this is
 * deliberately a read-only view of what the scrape already collected, because the module
 * does not act on the user's behalf inside a course.
 *
 * Everything here is derived from {@link DeadlineItem#raw}, so a node that was captured
 * before the counters existed simply contributes nothing rather than a wrong number.
 */
final class ChapterProgress {

    private ChapterProgress() {
    }

    /** One section's progress. */
    static final class Node {
        final String title;
        /** Task points the section holds; 0 when the payload carried no counter. */
        final int total;
        /** Task points still open. */
        final int unfinished;

        Node(String title, int total, int unfinished) {
            this.title = title;
            this.total = total;
            this.unfinished = Math.max(0, Math.min(unfinished, total));
        }

        boolean hasNumbers() {
            return total > 0;
        }

        int finished() {
            return Math.max(0, total - unfinished);
        }

        /** Completion percentage, 0 when no counter was captured. */
        int percent() {
            return total <= 0 ? 0 : Math.round(finished() * 100f / total);
        }
    }

    /** Course-level roll-up. */
    static final class Summary {
        final List<Node> nodes;
        final int total;
        final int unfinished;

        Summary(List<Node> nodes, int total, int unfinished) {
            this.nodes = nodes;
            this.total = total;
            this.unfinished = unfinished;
        }

        boolean isEmpty() {
            return nodes.isEmpty();
        }

        int finished() {
            return Math.max(0, total - unfinished);
        }

        int percent() {
            return total <= 0 ? 0 : Math.round(finished() * 100f / total);
        }

        /** "已看 12/20 个任务点 · 还差 8 个" — what the read-only board shows. */
        String describe() {
            if (total <= 0) {
                return "没有取到任务点进度";
            }
            if (unfinished <= 0) {
                return "已全部完成（共 " + total + " 个任务点）";
            }
            return "已完成 " + finished() + "/" + total + " 个任务点 · 还差 " + unfinished + " 个";
        }
    }

    /**
     * Reads the chapter nodes of one course.
     *
     * @param items every stored item; only 章节 rows whose course matches are considered
     */
    static Summary summarize(List<DeadlineItem> items, String course) {
        ArrayList<Node> nodes = new ArrayList<>();
        int total = 0;
        int unfinished = 0;
        if (items == null || course == null || course.isEmpty()) {
            return new Summary(nodes, 0, 0);
        }
        for (DeadlineItem item : items) {
            if (item == null || !course.equals(item.course)
                    || !DeadlineStore.TYPE_CHAPTER.equals(item.type)
                    || item.raw == null || item.raw.isEmpty()) {
                continue;
            }
            Node node = nodeOf(item);
            if (node == null) {
                continue;
            }
            nodes.add(node);
            total += node.total;
            unfinished += node.unfinished;
        }
        return new Summary(nodes, total, unfinished);
    }

    /** One stored chapter row as a node; null when its payload has no counters at all. */
    static Node nodeOf(DeadlineItem item) {
        if (item == null || item.raw == null || item.raw.isEmpty()) {
            return null;
        }
        try {
            JSONObject object = new JSONObject(item.raw);
            int total = object.optInt("jobcount", 0);
            int unfinished = object.optInt("jobUnfinishedCount", -1);
            if (total <= 0) {
                return null;
            }
            // Some payloads carry only the count of jobs, without saying how many are left;
            // an item reaching this table was filtered on being unfinished, so it counts as
            // one open point rather than as zero.
            if (unfinished < 0) {
                unfinished = Math.min(1, total);
            }
            String title = object.optString("name", "");
            if (title.isEmpty()) {
                title = item.title;
            }
            return new Node(title, total, unfinished);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
