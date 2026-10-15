package dev.chaoxingdeadline;

import android.content.ContentValues;
import android.database.Cursor;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

public final class DeadlineItem {
    public static final int SUBMISSION_UNKNOWN = -1;
    public static final int SUBMISSION_UNSUBMITTED = 0;
    public static final int SUBMISSION_SUBMITTED = 1;

    public String id;
    public String type;
    public String title;
    public String course;
    public String courseId;
    public String classId;
    public String cpi;
    public String uid;
    public String taskId;
    public int courseConfidence;
    public long dueAt;
    public boolean submitted;
    public int submissionState = SUBMISSION_UNKNOWN;
    /** Consecutive authoritative scans that did not re-report this item. */
    public int misses;
    /** When the item was flagged as probably revoked/cancelled; 0 = not flagged. */
    public long vanishedAt;
    /**
     * When the item stopped being actionable: submitted (per the school's own fields or a
     * manual tap), flagged revoked, or past its deadline. 0 = still open. Drives the
     * 已完成 section and the history list.
     */
    public long settledAt;
    /**
     * Last authoritative scan that re-reported this item, 0 when it was only ever captured
     * passively. History says "最后出现于 …" from this, which is honest about the fact that
     * a school-side removal and a finished task look identical from the outside.
     */
    public long lastSeenAt;
    public String source;
    public String url;
    public String raw;

    public ContentValues toValues() {
        ContentValues values = new ContentValues();
        values.put("id", id);
        values.put("type", type);
        values.put("title", title);
        values.put("course", course);
        values.put("course_id", courseId);
        values.put("class_id", classId);
        values.put("cpi", cpi);
        values.put("uid", uid);
        values.put("task_id", taskId);
        values.put("course_confidence", courseConfidence);
        values.put("due_at", dueAt);
        int storedSubmissionState = submitted && submissionState == SUBMISSION_UNKNOWN
                ? SUBMISSION_SUBMITTED : submissionState;
        values.put("submitted", submitted ? 1 : 0);
        values.put("submit_state", storedSubmissionState);
        values.put("misses", misses);
        values.put("vanished_at", vanishedAt);
        values.put("settled_at", settledAt);
        values.put("last_seen_at", lastSeenAt);
        values.put("source", source);
        values.put("url", url);
        values.put("updated_at", System.currentTimeMillis());
        values.put("raw", raw);
        return values;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("type", type);
        json.put("title", title);
        json.put("course", course);
        json.put("courseId", courseId);
        json.put("classId", classId);
        json.put("cpi", cpi);
        json.put("uid", uid);
        json.put("taskId", taskId);
        json.put("courseConfidence", courseConfidence);
        json.put("dueAt", dueAt);
        json.put("submitted", submitted);
        json.put("submitState", submissionState);
        json.put("misses", misses);
        json.put("vanishedAt", vanishedAt);
        json.put("settledAt", settledAt);
        json.put("lastSeenAt", lastSeenAt);
        json.put("source", source);
        json.put("url", url);
        json.put("raw", raw);
        return json;
    }

    public static DeadlineItem fromJson(String value) throws JSONException {
        JSONObject json = new JSONObject(value);
        DeadlineItem item = new DeadlineItem();
        item.id = json.optString("id");
        item.type = json.optString("type", "事项");
        item.title = json.optString("title", "未命名");
        item.course = json.optString("course", "");
        item.courseId = json.optString("courseId", "");
        item.classId = json.optString("classId", "");
        item.cpi = json.optString("cpi", "");
        item.uid = json.optString("uid", "");
        item.taskId = json.optString("taskId", "");
        item.courseConfidence = json.optInt("courseConfidence", item.course == null || item.course.isEmpty() ? 0 : 60);
        item.dueAt = json.optLong("dueAt", 0L);
        item.misses = json.optInt("misses", 0);
        item.vanishedAt = json.optLong("vanishedAt", 0L);
        item.settledAt = json.optLong("settledAt", 0L);
        item.lastSeenAt = json.optLong("lastSeenAt", 0L);
        if (json.has("submitState")) {
            item.setSubmissionState(json.optInt("submitState", SUBMISSION_UNKNOWN));
        } else if (json.optBoolean("submitted", false)) {
            item.setSubmissionState(SUBMISSION_SUBMITTED);
        }
        item.source = json.optString("source", "");
        item.url = json.optString("url", "");
        item.raw = json.optString("raw", "");
        return item;
    }

    public static DeadlineItem fromCursor(Cursor cursor) {
        DeadlineItem item = new DeadlineItem();
        item.id = cursor.getString(cursor.getColumnIndexOrThrow("id"));
        item.type = cursor.getString(cursor.getColumnIndexOrThrow("type"));
        item.title = cursor.getString(cursor.getColumnIndexOrThrow("title"));
        item.course = stringOrEmpty(cursor, "course");
        item.courseId = stringOrEmpty(cursor, "course_id");
        item.classId = stringOrEmpty(cursor, "class_id");
        item.cpi = stringOrEmpty(cursor, "cpi");
        item.uid = stringOrEmpty(cursor, "uid");
        item.taskId = stringOrEmpty(cursor, "task_id");
        item.courseConfidence = intOrZero(cursor, "course_confidence");
        item.dueAt = cursor.getLong(cursor.getColumnIndexOrThrow("due_at"));
        item.submitted = cursor.getInt(cursor.getColumnIndexOrThrow("submitted")) != 0;
        item.setSubmissionState(intOrDefault(cursor, "submit_state",
                item.submitted ? SUBMISSION_SUBMITTED : SUBMISSION_UNKNOWN));
        item.misses = intOrDefault(cursor, "misses", 0);
        item.vanishedAt = longOrDefault(cursor, "vanished_at", 0L);
        item.settledAt = longOrDefault(cursor, "settled_at", 0L);
        item.lastSeenAt = longOrDefault(cursor, "last_seen_at", 0L);
        item.source = stringOrEmpty(cursor, "source");
        item.url = stringOrEmpty(cursor, "url");
        item.raw = stringOrEmpty(cursor, "raw");
        return item;
    }

    public String stableId() {
        String basis;
        if (taskId != null && !taskId.isEmpty()) {
            basis = safe(type) + "|" + safe(courseId) + "|" + safe(classId) + "|" + taskId;
        } else {
            basis = safe(type) + "|" + safe(courseId) + "|" + safe(classId)
                    + "|" + safe(course) + "|" + safe(title) + "|" + dueAt;
        }
        return safe(type) + "_" + Integer.toHexString(basis.toLowerCase(Locale.ROOT).hashCode());
    }

    public void applyContext(ParseContext context) {
        if (context == null) {
            return;
        }
        if (empty(source)) source = context.source;
        if (empty(url)) url = context.url;
        if (empty(courseId)) courseId = context.courseId;
        if (empty(classId)) classId = context.classId;
        if (empty(cpi)) cpi = context.cpi;
        if (empty(uid)) uid = context.uid;
        if (!empty(context.courseName) && (empty(course) || context.courseConfidence >= courseConfidence)) {
            course = context.courseName;
            courseConfidence = context.courseConfidence;
        }
    }

    private static String stringOrEmpty(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        if (index < 0 || cursor.isNull(index)) {
            return "";
        }
        return cursor.getString(index);
    }

    private static int intOrZero(Cursor cursor, String column) {
        return intOrDefault(cursor, column, 0);
    }

    private static long longOrDefault(Cursor cursor, String column, long fallback) {
        int index = cursor.getColumnIndex(column);
        return index < 0 ? fallback : cursor.getLong(index);
    }

    private static int intOrDefault(Cursor cursor, String column, int fallback) {
        int index = cursor.getColumnIndex(column);
        if (index < 0 || cursor.isNull(index)) {
            return fallback;
        }
        return cursor.getInt(index);
    }

    public void setSubmissionState(int state) {
        if (state < SUBMISSION_UNKNOWN || state > SUBMISSION_SUBMITTED) {
            state = SUBMISSION_UNKNOWN;
        }
        submissionState = state;
        submitted = state == SUBMISSION_SUBMITTED;
    }

    /**
     * Whether this item still needs the user's attention.
     *
     * "Open" is deliberately the conservative reading: an item is finished only when the
     * school's own fields say so, the user marked it done, or an authoritative scan stopped
     * reporting it after its deadline had passed. A chapter point that a scan never covers
     * (those are passive-only) must not count as finished just because nobody re-reported it,
     * so {@link #lastSeenAt} has to be non-zero before the scan-based rule applies.
     */
    public boolean isOpen(long now) {
        if (settledAt > 0L) {
            return false;
        }
        if (submitted || submissionState == SUBMISSION_SUBMITTED) {
            return false;
        }
        return dueAt <= 0L || dueAt > now;
    }

    /** Whether this item is done but recent enough to show in the 已完成 list. */
    public boolean isSettled(long now, long windowMs) {
        if (settledAt <= 0L) {
            return false;
        }
        return windowMs <= 0L || now - settledAt <= windowMs;
    }

    private static boolean empty(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
