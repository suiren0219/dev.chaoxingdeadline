package dev.chaoxingdeadline;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * Parser for Chaoxing timetable payloads. Three layers, tried in order.
 *
 * <p><b>1. Keyed.</b> The documented endpoint
 * {@code POST kb.chaoxing.com/pc/curriculum/getMyLessons} answers
 * {@code {data:{lessonArray:[…],curriculum:{…}}}}, and the mobile app's own variant is nearly
 * the same. Read directly by key: exact, cheap, and what the tests pin down.
 *
 * <p><b>2. Structural.</b> Schools run different builds of the campus portal, so what a phone
 * receives varies well beyond field names — the lesson list nested a wrapper deeper, a weekday
 * spelled {@code "周三"} or {@code 0} (JS Sunday), weeks as {@code [1,2,3]} or {@code "1-16单"},
 * a section range {@code "3-4节"} instead of a count, clock times with no section at all, or a
 * map keyed by weekday whose rows carry no weekday of their own. This layer walks the whole
 * document and recognises lessons by <em>shape</em>: an object yielding a weekday in 1-7 plus a
 * section (or a start time the school's own section table can place) plus a non-empty name is a
 * lesson, whatever its keys are called. Key names are then only hints that sharpen the reading.
 *
 * <p><b>3. Aliases.</b> A school may name its fields in a vocabulary nothing here would guess
 * ({@code mc}/{@code xq}/{@code jc}). No heuristic can invent those names reliably, and guessing
 * harder would start misreading unrelated payloads, so the mapping is made explicit and
 * user-supplied: {@link #setAliasSpec} takes a spec like
 * {@code "名称=mc; 星期=xq; 节次=jc"} from the module settings. That is the universal escape
 * hatch — an unknown school works without a code change, and the hook logs one sample per
 * unrecognised payload so the spec can be filled in from evidence rather than guesswork.
 */
public final class TimetableParser {
    // -- field-name hints: layer 1 needs these, layer 2 uses them to sharpen its reading --
    private static final String[] NAME_KEYS = {
            "name", "courseName", "coursename", "lessonName", "course_name", "title",
            "kbName", "kcmc"};
    private static final String[] TEACHER_KEYS = {
            "teacherName", "teacher", "teachName", "teacher_name", "teacherNames", "jsxm"};
    private static final String[] LOCATION_KEYS = {
            "location", "classRoom", "classroom", "room", "roomName", "address", "place",
            "kcdd", "area"};
    private static final String[] WEEKS_KEYS = {
            "weeks", "weekList", "weekStr", "weekText", "weekNums", "weekNumbers",
            "weeksList", "week", "zs"};
    private static final String[] DAY_KEYS = {
            "dayOfWeek", "dayofweek", "day_of_week", "weekDay", "weekday", "week_day",
            "dayIndex", "xqj"};
    private static final String[] BEGIN_KEYS = {
            "beginNumber", "beginnumber", "beginNum", "beginSection", "begin_section",
            "startSection", "sectionIndex", "section", "sectionStart", "jieci"};
    private static final String[] LENGTH_KEYS = {
            "length", "sectionLength", "sections", "sectionCount", "sectionNum", "span"};
    private static final String[] START_TIME_KEYS = {
            "startTime", "beginTime", "start", "begin", "sectionStartTime", "start_time"};
    private static final String[] END_TIME_KEYS = {
            "endTime", "finishTime", "end", "sectionEndTime", "end_time"};
    /** Objects that commonly wrap the real lesson fields (the course nested under these). */
    private static final String[] NESTED_KEYS = {
            "course", "lesson", "courseInfo", "lessonInfo", "courseDetail", "detail", "info"};
    /** Curriculum-level metadata, which moves around between portal builds. */
    private static final String[] FIRST_WEEK_KEYS = {
            "firstWeekDate", "firstweekdate", "firstWeekDateReal", "firstWeekDay",
            "firstWeekStartDate", "termStartDate", "semesterStartDate", "curriculumStartDate"};
    private static final String[] MAX_WEEK_KEYS = {
            "maxWeek", "maxweek", "max_week", "totalWeek", "totalWeeks", "weekCount",
            "semesterWeeks", "maxWeekNum"};
    private static final String[] CURRENT_WEEK_KEYS = {
            "realCurrentWeek", "realcurrentweek", "currentWeek", "currentweek", "nowWeek",
            "weekNow"};
    private static final String[] SECTION_TIME_KEYS = {
            "lessonTimeConfigArray", "lessonTimeConfig", "lessonTimes", "sectionTimes",
            "sectionTimeArray", "timeConfigArray", "timeTable", "sectionTimeConfig"};
    /** Keys naming the lesson list itself in the documented payloads. */
    private static final String[] LIST_KEYS = {
            "lessonArray", "lessons", "lessonList", "courseList", "kbList", "myLessons", "list"};

    /** Tokens that on their own identify a lesson list. */
    private static final String[] LIST_TOKENS = {
            "lessonarray", "lessonlist", "lessons", "courselist", "kblist", "myLessons"
                    .toLowerCase(Locale.ROOT), "kebiaolist", "schedulelist", "timetable"};
    /** Tokens naming a weekday. */
    private static final String[] DAY_TOKENS = {
            "dayofweek", "weekday", "week_day", "\u661f\u671f", "weekdayindex"};
    /** Tokens naming a section position. */
    private static final String[] SECTION_TOKENS = {
            "beginnumber", "beginsection", "startsection", "section", "period", "jieci"};
    /** Index == the digit it spells (\u4e00 is 1, so index 0 is a harmless placeholder). */
    private static final String CHINESE_DIGITS = "\u96f6\u4e00\u4e8c\u4e09\u56db\u4e94\u516d\u4e03\u516b\u4e5d";

    /** Structural walk bounds: deep enough for any sane payload, cheap enough for the hot path. */
    private static final int MAX_DEPTH = 8;
    private static final int MAX_NODES = 20_000;
    /** A single payload cannot hold more lessons than a semester has slots. */
    private static final int MAX_LESSONS = 600;
    /** Sections consulted when placing a time-only lesson onto the school's section table. */
    private static final int MAX_SECTION_LOOKUP = 15;
    /** Clock-time slack when matching a lesson's start against a section's start. */
    private static final int CLOCK_SLACK_MINUTES = 3;

    /** User-supplied field aliases; see the class docs (layer 3). */
    private static volatile Aliases aliases = Aliases.empty();

    private TimetableParser() {
    }

    /**
     * Teaches the parser a school's own field names.
     *
     * The spec is {@code label=key,key} groups separated by {@code ;} or newlines, e.g.
     * {@code "名称=mc; 星期=xq; 节次=jc; 周次=zs"}. Labels are recognised by keyword
     * (名称/课程, 星期, 节次, 周次, 教师, 地点, 时长), so order and wording are forgiving.
     * An empty or unparsable spec restores the built-in vocabulary.
     */
    public static void setAliasSpec(String spec) {
        Aliases current = aliases;
        if (spec == null ? current.spec.isEmpty() : spec.equals(current.spec)) {
            return;
        }
        aliases = Aliases.parse(spec);
    }

    /** Cheap shape gate so the hot intercept path never pays for a JSON walk. */
    public static boolean looksLikeTimetable(String text) {
        return looksLikeTimetable(text, "");
    }

    /**
     * Shape gate; {@code url} is an optional hint from the request that carried the payload.
     *
     * Deliberately generous — the cost of a false positive is one wasted structural walk,
     * while the cost of a false negative is a school whose timetable silently never loads.
     * Both the body and the URL are considered, because the URL often says "curriculum" while
     * an unfamiliar body carries no recognisable key at all.
     */
    public static boolean looksLikeTimetable(String text, String url) {
        if (text == null || text.length() < 20 || text.length() > 4_000_000) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (containsAny(lower, LIST_TOKENS)) {
            return true;
        }
        if (aliases.matches(lower)) {
            return true;
        }
        boolean day = containsAny(lower, DAY_TOKENS);
        boolean section = containsAny(lower, SECTION_TOKENS);
        if (day && section) {
            return true;
        }
        boolean curriculumWord = lower.contains("curriculum") || lower.contains("timetable")
                || lower.contains("schedule") || lower.contains("kebiao");
        if (curriculumWord && (day || section || lower.contains("lesson") || lower.contains("course"))) {
            return true;
        }
        if (url != null && !url.isEmpty() && text.indexOf('[') >= 0) {
            String urlLower = url.toLowerCase(Locale.ROOT);
            // A timetable URL plus JSON holding a list is enough to try: the body may name
            // every field in a vocabulary nothing here knows, and a wasted walk costs nothing
            // next to a timetable that never loads.
            if (urlLower.contains("curriculum") || urlLower.contains("lesson")
                    || urlLower.contains("schedule") || urlLower.contains("timetable")
                    || urlLower.contains("kebiao")) {
                return true;
            }
        }
        return false;
    }

    public static final class Result {
        public final List<Lesson> lessons;
        /** Monday 00:00 of week 1, epoch millis; 0 when the payload had none. */
        public final long firstWeekDate;
        /** School section-time table as raw JSON text, e.g. ["8:00-8:45", …]. */
        public final String sectionTimesJson;
        /**
         * Semester length as stated by the curriculum, 0 when absent. The mobile
         * endpoint reports maxWeek, which is how a sweep knows where to stop instead of
         * guessing a bound.
         */
        public final int maxWeek;
        /** Teaching week this payload answers for, 0 when the response did not say. */
        public final int currentWeek;
        /**
         * True when the payload was recognised structurally rather than by field name.
         * Only interesting for logs: it means this school's portal build differs from the
         * documented one and would not have parsed without the structural pass.
         */
        public final boolean structural;

        Result(List<Lesson> lessons, long firstWeekDate, String sectionTimesJson,
               int maxWeek, int currentWeek, boolean structural) {
            this.lessons = lessons;
            this.firstWeekDate = firstWeekDate;
            this.sectionTimesJson = sectionTimesJson;
            this.maxWeek = maxWeek;
            this.currentWeek = currentWeek;
            this.structural = structural;
        }
    }

    public static Result parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        String trimmed = text.trim();
        try {
            // A whole document that is itself an array is a legitimate lesson list.
            if (trimmed.startsWith("[")) {
                return parseStructural(new JSONArray(trimmed));
            }
            if (!trimmed.startsWith("{")) {
                return null;
            }
            JSONObject root = new JSONObject(trimmed);
            Result known = parseKnown(root);
            if (known != null) {
                return known;
            }
            return parseStructural(root);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Layer 1: the documented payload, read by key. */
    private static Result parseKnown(JSONObject root) {
        JSONObject data = root.optJSONObject("data");
        JSONObject source = data != null ? data : root;
        JSONArray array = firstArray(source, LIST_KEYS);
        if (array == null) {
            return null;
        }
        ArrayList<Lesson> lessons = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject json = array.optJSONObject(i);
            if (json == null) {
                continue;
            }
            Lesson lesson = readLesson(json);
            if (lesson != null) {
                lessons.add(lesson);
            }
        }
        if (lessons.isEmpty()) {
            return null;
        }
        JSONObject curriculum = source.optJSONObject("curriculum");
        if (curriculum == null) {
            curriculum = source;
        }
        long firstWeekDate = parseTimestamp(firstValue(curriculum, FIRST_WEEK_KEYS));
        String sectionTimesJson = "";
        JSONArray times = firstArray(curriculum, SECTION_TIME_KEYS);
        if (times != null) {
            sectionTimesJson = ScheduleMath.normalizeSectionTimes(times);
        }
        int maxWeek = looseInt(firstValue(curriculum, MAX_WEEK_KEYS));
        int currentWeek = looseInt(firstValue(curriculum, CURRENT_WEEK_KEYS));
        return new Result(lessons, firstWeekDate, sectionTimesJson, maxWeek, currentWeek, false);
    }

    /** Layer 2: recognise lessons anywhere in the document, by shape. */
    private static Result parseStructural(Object root) {
        Scan scan = new Scan();
        scan.walk(root, 0, 0);
        List<Lesson> lessons = scan.lessons();
        if (lessons.isEmpty()) {
            return null;
        }
        return new Result(lessons, scan.firstWeekDate, scan.sectionTimesJson,
                scan.maxWeek, scan.currentWeek, true);
    }

    /**
     * One structural walk of the payload.
     *
     * Records every lesson-shaped object it meets, bucketed by the depth it was found at, and
     * keeps whatever curriculum metadata it passes. The buckets matter: a weekday-keyed map
     * ({@code {"1":[…],"5":[…]}}) puts each day's rows in a separate array, and those siblings
     * must end up in one timetable — so the winner is the depth holding the most rows, with the
     * shallowest depth breaking ties. Leaf objects are recorded too (that is what makes an
     * oddly wrapped single lesson work), but at a deeper bucket, so a leaf can never outbid the
     * array it came from.
     */
    private static final class Scan {
        /** Ordered by depth, so the "shallowest wins a tie" rule is just "first wins". */
        private final TreeMap<Integer, List<Draft>> byDepth = new TreeMap<>();
        private int nodes;
        private long firstWeekDate;
        private String sectionTimesJson = "";
        private int maxWeek;
        private int currentWeek;

        private boolean spend() {
            return nodes++ < MAX_NODES;
        }

        void walk(Object node, int depth, int dayHint) {
            if (node == null || depth > MAX_DEPTH || !spend()) {
                return;
            }
            if (node instanceof JSONObject) {
                JSONObject json = (JSONObject) node;
                absorbMeta(json);
                if (dayHint > 0) {
                    // The containing key named a weekday, so this object need not repeat it.
                    Draft single = readDraft(json, dayHint);
                    if (single != null) {
                        offer(Collections.singletonList(single), depth);
                    }
                }
                Iterator<String> keys = json.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    walk(json.opt(key), depth + 1, weekdayOf(key));
                }
                return;
            }
            if (node instanceof JSONArray) {
                JSONArray array = (JSONArray) node;
                ArrayList<Draft> drafts = new ArrayList<>();
                for (int i = 0; i < array.length() && drafts.size() < MAX_LESSONS; i++) {
                    JSONObject element = array.optJSONObject(i);
                    Draft draft = element == null ? null : readDraft(element, dayHint);
                    if (draft != null) {
                        drafts.add(draft);
                    }
                }
                offer(drafts, depth);
                for (int i = 0; i < array.length(); i++) {
                    walk(array.opt(i), depth + 1, dayHint);
                }
            }
        }

        /** Keeps the payload's own curriculum facts, found wherever they sit. */
        private void absorbMeta(JSONObject json) {
            if (firstWeekDate <= 0L) {
                firstWeekDate = parseTimestamp(firstValue(json, FIRST_WEEK_KEYS));
            }
            if (maxWeek <= 0) {
                maxWeek = looseInt(firstValue(json, MAX_WEEK_KEYS));
            }
            if (currentWeek <= 0) {
                currentWeek = looseInt(firstValue(json, CURRENT_WEEK_KEYS));
            }
            if (sectionTimesJson.isEmpty()) {
                JSONArray times = firstArray(json, SECTION_TIME_KEYS);
                if (times != null) {
                    sectionTimesJson = ScheduleMath.normalizeSectionTimes(times);
                }
            }
        }

        private void offer(List<Draft> drafts, int depth) {
            if (drafts == null || drafts.isEmpty()) {
                return;
            }
            List<Draft> group = byDepth.get(depth);
            if (group == null) {
                group = new ArrayList<>();
                byDepth.put(depth, group);
            }
            group.addAll(drafts);
        }

        /** The winning bucket: most rows, shallowest on a tie. */
        private List<Draft> winner() {
            List<Draft> best = null;
            for (List<Draft> group : byDepth.values()) {
                if (best == null || group.size() > best.size()) {
                    best = group;
                }
            }
            return best;
        }

        /**
         * The finished lesson list: the winning bucket, with time-only rows placed on the
         * school's section table and unusable ones dropped.
         *
         * A lesson whose clock time matches no section is dropped rather than guessed at — a
         * wrong slot in a timetable is worse than a missing one — and a row a payload repeats
         * keeps one entry, with the weeks unioned.
         */
        List<Lesson> lessons() {
            List<Draft> group = winner();
            if (group == null) {
                return Collections.emptyList();
            }
            LinkedHashMap<String, Lesson> seen = new LinkedHashMap<>();
            for (Draft draft : group) {
                if (draft.lesson.beginSection <= 0) {
                    int[] slot = placeByTime(sectionTimesJson, draft.startMinutes, draft.endMinutes);
                    if (slot == null) {
                        continue;
                    }
                    draft.lesson.beginSection = slot[0];
                    draft.lesson.sections = slot[1];
                }
                String slotKey = Lesson.slotKey(draft.lesson);
                Lesson existing = seen.get(slotKey);
                if (existing == null) {
                    seen.put(slotKey, draft.lesson);
                } else {
                    existing.weeks = ScheduleMath.weeksMerge(existing.weeks, draft.lesson.weeks);
                }
            }
            return new ArrayList<>(seen.values());
        }

        /** @return {firstSection, sectionCount}, or null when no section starts then. */
        private static int[] placeByTime(String table, int startMinutes, int endMinutes) {
            if (table == null || table.isEmpty() || startMinutes < 0) {
                return null;
            }
            int first = 0;
            int last = 0;
            for (int section = 1; section <= MAX_SECTION_LOOKUP; section++) {
                int start = ScheduleMath.sectionStartMinutes(table, section);
                int end = ScheduleMath.sectionEndMinutes(table, section);
                if (first == 0 && Math.abs(start - startMinutes) <= CLOCK_SLACK_MINUTES) {
                    first = section;
                }
                if (first > 0 && endMinutes > 0 && endMinutes <= end + CLOCK_SLACK_MINUTES) {
                    last = section;
                    break;
                }
            }
            if (first == 0) {
                return null;
            }
            return new int[]{first, Math.max(1, (last > 0 ? last : first) - first + 1)};
        }
    }

    /** A lesson as read structurally, before its section is known for certain. */
    private static final class Draft {
        final Lesson lesson;
        final int startMinutes;
        final int endMinutes;

        Draft(Lesson lesson, int startMinutes, int endMinutes) {
            this.lesson = lesson;
            this.startMinutes = startMinutes;
            this.endMinutes = endMinutes;
        }
    }

    /**
     * The field-name vocabulary in force: the built-in hints plus whatever the user aliased.
     *
     * Combined up front so the walk never merges key lists per lesson, and immutable so a spec
     * arriving mid-parse cannot yield a half-aliased reading.
     */
    static final class Aliases {
        final String spec;
        final String[] name;
        final String[] day;
        final String[] begin;
        final String[] length;
        final String[] weeks;
        final String[] teacher;
        final String[] location;
        /** Only the user's own keys: used to cheaply open the shape gate. */
        private final List<String> extra;

        private Aliases(String spec, String[] name, String[] day, String[] begin, String[] length,
                        String[] weeks, String[] teacher, String[] location, List<String> extra) {
            this.spec = spec;
            this.name = name;
            this.day = day;
            this.begin = begin;
            this.length = length;
            this.weeks = weeks;
            this.teacher = teacher;
            this.location = location;
            this.extra = extra;
        }

        static Aliases empty() {
            return new Aliases("", NAME_KEYS, DAY_KEYS, BEGIN_KEYS, LENGTH_KEYS, WEEKS_KEYS,
                    TEACHER_KEYS, LOCATION_KEYS, Collections.<String>emptyList());
        }

        static Aliases parse(String rawSpec) {
            String spec = rawSpec == null ? "" : rawSpec.trim();
            if (spec.isEmpty()) {
                return empty();
            }
            List<String> name = new ArrayList<>();
            List<String> day = new ArrayList<>();
            List<String> begin = new ArrayList<>();
            List<String> length = new ArrayList<>();
            List<String> weeks = new ArrayList<>();
            List<String> teacher = new ArrayList<>();
            List<String> location = new ArrayList<>();
            for (String part : spec.split("[;\uff1b\\n]")) {
                int eq = part.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String label = part.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                List<String> keys = splitKeys(part.substring(eq + 1));
                if (keys.isEmpty()) {
                    continue;
                }
                if (label.contains("\u540d\u79f0") || label.contains("\u8bfe\u7a0b")
                        || label.contains("name")) {
                    name.addAll(keys);
                } else if (label.contains("\u661f\u671f") || label.contains("\u5468\u51e0")
                        || label.contains("day")) {
                    day.addAll(keys);
                } else if (label.contains("\u8282") || label.contains("section")
                        || label.contains("period")) {
                    begin.addAll(keys);
                } else if (label.contains("\u5468\u6b21") || label.contains("\u5468\u6570")
                        || label.contains("\u5b66\u671f") || label.contains("week")) {
                    weeks.addAll(keys);
                } else if (label.contains("\u6559\u5e08") || label.contains("\u8001\u5e08")
                        || label.contains("teacher")) {
                    teacher.addAll(keys);
                } else if (label.contains("\u5730\u70b9") || label.contains("\u6559\u5ba4")
                        || label.contains("location") || label.contains("room")) {
                    location.addAll(keys);
                } else if (label.contains("\u65f6\u957f") || label.contains("\u957f\u5ea6")
                        || label.contains("length") || label.contains("count")) {
                    length.addAll(keys);
                }
            }
            List<String> extra = new ArrayList<>();
            extra.addAll(name);
            extra.addAll(day);
            extra.addAll(begin);
            extra.addAll(length);
            extra.addAll(weeks);
            extra.addAll(teacher);
            extra.addAll(location);
            if (extra.isEmpty()) {
                return empty();
            }
            return new Aliases(spec,
                    combine(NAME_KEYS, name), combine(DAY_KEYS, day), combine(BEGIN_KEYS, begin),
                    combine(LENGTH_KEYS, length), combine(WEEKS_KEYS, weeks),
                    combine(TEACHER_KEYS, teacher), combine(LOCATION_KEYS, location), extra);
        }

        /** True when the payload carries one of the user's own keys as a JSON key. */
        boolean matches(String lowercasedText) {
            for (String key : extra) {
                String needle = key.toLowerCase(Locale.ROOT);
                if (lowercasedText.contains("\"" + needle + "\"")
                        || lowercasedText.contains(needle + ":")) {
                    return true;
                }
            }
            return false;
        }

        private static List<String> splitKeys(String text) {
            List<String> keys = new ArrayList<>();
            for (String key : text.split("[,,\\uff0c|/\\u3001\\s]+")) {
                String trimmed = key.trim();
                if (!trimmed.isEmpty()) {
                    keys.add(trimmed);
                }
            }
            return keys;
        }

        private static String[] combine(String[] base, List<String> extras) {
            if (extras.isEmpty()) {
                return base;
            }
            LinkedHashSet<String> all = new LinkedHashSet<>(extras);
            Collections.addAll(all, base);
            return all.toArray(new String[0]);
        }
    }

    /**
     * Reads one candidate object by shape: weekday + name are required, a section or a start
     * time is required for placement, everything else is optional.
     */
    private static Draft readDraft(JSONObject json, int dayHint) {
        Aliases current = aliases;
        int day = looseDay(digValue(json, current.day, 1));
        if (day <= 0) {
            day = dayHint;
        }
        if (day <= 0 || day > 7) {
            return null;
        }
        String name = digString(json, current.name, 1);
        if (name.isEmpty()) {
            return null;
        }
        Object beginRaw = digValue(json, current.begin, 1);
        int begin = looseSection(beginRaw);
        // "3-4节" both starts at 3 and covers two sections, so a range doubles as a count.
        int sections = looseInt(digValue(json, current.length, 1));
        if (sections <= 0) {
            sections = rangeSpan(beginRaw);
        }
        if (sections <= 0) {
            sections = 1;
        }
        Draft draft = new Draft(
                new Lesson("", name, digString(json, current.teacher, 1),
                        digString(json, current.location, 1), day, Math.max(0, begin), sections,
                        looseWeeks(digValue(json, current.weeks, 0))),
                clockMinutes(digValue(json, START_TIME_KEYS, 1)),
                clockMinutes(digValue(json, END_TIME_KEYS, 1)));
        String id = digString(json, new String[]{"lessonId", "id", "lessonConfigId", "uuid"}, 0);
        draft.lesson.id = id.isEmpty() ? draft.lesson.stableId() : id;
        return draft;
    }

    /** Layer 1's row reader: the documented keys, with lenient value spellings. */
    private static Lesson readLesson(JSONObject json) {
        Aliases current = aliases;
        int dayOfWeek = looseDay(digValue(json, current.day, 1));
        Object beginRaw = digValue(json, current.begin, 1);
        int beginSection = looseSection(beginRaw);
        if (dayOfWeek <= 0 || dayOfWeek > 7 || beginSection <= 0) {
            return null;
        }
        String name = digString(json, current.name, 1);
        if (name.isEmpty()) {
            return null;
        }
        int sections = looseInt(digValue(json, current.length, 1));
        if (sections <= 0) {
            sections = rangeSpan(beginRaw);
        }
        if (sections <= 0) {
            sections = 1;
        }
        String id = digString(json, new String[]{"lessonId", "lessonid", "id"}, 0);
        Lesson lesson = new Lesson(id, name, digString(json, current.teacher, 1),
                digString(json, current.location, 1), dayOfWeek, beginSection, sections,
                looseWeeks(digValue(json, current.weeks, 1)));
        if (lesson.id.isEmpty()) {
            lesson.id = lesson.stableId();
        }
        return lesson;
    }

    // -- lenient value reading --

    /** Weekday from any spelling: 3, "3", "周三", "星期三", "Wed", "wednesday". 0 = unknown. */
    static int looseDay(Object raw) {
        if (raw == null || raw == JSONObject.NULL) {
            return 0;
        }
        if (raw instanceof Number) {
            int value = ((Number) raw).intValue();
            // 0 means Sunday in the zero-based spellings (JS Date.getDay, most portals).
            return value == 0 ? 7 : (value >= 1 && value <= 7 ? value : 0);
        }
        return weekdayOf(String.valueOf(raw));
    }

    private static int weekdayOf(String raw) {
        if (raw == null) {
            return 0;
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return 0;
        }
        if (text.contains("\u65e5") || text.contains("\u5929")
                || text.startsWith("sun") || text.contains("sunday")) {
            return 7;
        }
        if (text.startsWith("mon") || text.contains("monday")) {
            return 1;
        }
        if (text.startsWith("tue") || text.contains("tuesday")) {
            return 2;
        }
        if (text.startsWith("wed") || text.contains("wednesday")) {
            return 3;
        }
        if (text.startsWith("thu") || text.contains("thursday")) {
            return 4;
        }
        if (text.startsWith("fri") || text.contains("friday")) {
            return 5;
        }
        if (text.startsWith("sat") || text.contains("saturday")) {
            return 6;
        }
        String digits = digitsOnly(text);
        if (!digits.isEmpty()) {
            int value = parseIntOrZero(digits);
            if (value == 0) {
                return 7;
            }
            return value >= 1 && value <= 7 ? value : 0;
        }
        int chinese = chineseNumber(text);
        return chinese >= 1 && chinese <= 7 ? chinese : 0;
    }

    /** Lenient integer: 3, "3", "第3节", "3-4节", "三" → 3. Unreadable input yields 0. */
    static int looseInt(Object raw) {
        if (raw == null || raw == JSONObject.NULL) {
            return 0;
        }
        if (raw instanceof Number) {
            return ((Number) raw).intValue();
        }
        if (raw instanceof JSONArray) {
            JSONArray array = (JSONArray) raw;
            return array.length() > 0 ? looseInt(array.opt(0)) : 0;
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            return 0;
        }
        String digits = digitsOnly(text);
        if (!digits.isEmpty()) {
            return parseIntOrZero(digits);
        }
        return chineseNumber(text);
    }

    /** Section start, refusing clock strings so "08:00" cannot read as section 8. */
    private static int looseSection(Object raw) {
        if (raw instanceof String) {
            String text = (String) raw;
            if (text.indexOf(':') >= 0 || text.indexOf('\uff1a') >= 0) {
                return 0;
            }
        }
        return looseInt(raw);
    }

    /** Sections covered by a "3-4" style range: 2. Single values and non-ranges yield 0. */
    private static int rangeSpan(Object raw) {
        if (!(raw instanceof String)) {
            return 0;
        }
        String text = ((String) raw).replace('\uff5e', '-').replace('~', '-').replace('\u2014', '-');
        int dash = text.indexOf('-');
        if (dash < 0) {
            return 0;
        }
        int from = looseInt(text.substring(0, dash));
        int to = looseInt(text.substring(dash + 1));
        if (from <= 0 || to < from || to - from > 20) {
            return 0;
        }
        return to - from + 1;
    }

    /**
     * Weeks as a normalised range string: {@code [1,2,3]} → "1-3", "第1-16周" → "1-16".
     *
     * Odd/even terms ("1-16单") have no range spelling in the rest of the app, so they are
     * expanded into the explicit week list they mean — which {@link ScheduleMath} already
     * understands, because its week parser enumerates ranges into members.
     */
    static String looseWeeks(Object raw) {
        if (raw == null || raw == JSONObject.NULL) {
            return "";
        }
        if (raw instanceof JSONArray) {
            LinkedHashSet<Integer> weeks = new LinkedHashSet<>();
            JSONArray array = (JSONArray) raw;
            for (int i = 0; i < array.length(); i++) {
                weeks.addAll(ScheduleMath.weeksList(String.valueOf(array.opt(i))));
            }
            return ScheduleMath.weeksNormalized(weeks);
        }
        if (raw instanceof Number) {
            int week = ((Number) raw).intValue();
            return week > 0 ? String.valueOf(week) : "";
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            return "";
        }
        String lower = text.toLowerCase(Locale.ROOT);
        boolean odd = text.contains("\u5355") || lower.contains("odd");
        boolean even = text.contains("\u53cc") || lower.contains("even");
        if (!odd && !even) {
            return ScheduleMath.weeksNormalized(ScheduleMath.weeksList(text));
        }
        LinkedHashSet<Integer> weeks = new LinkedHashSet<>();
        for (int week : ScheduleMath.weeksList(text)) {
            if (odd && week % 2 == 0) {
                continue;
            }
            if (even && week % 2 == 1) {
                continue;
            }
            weeks.add(week);
        }
        return ScheduleMath.weeksNormalized(weeks);
    }

    /** Minutes since midnight from "08:00" / "8:00:00"; -1 when it is not a clock. */
    static int clockMinutes(Object raw) {
        if (raw == null || raw == JSONObject.NULL) {
            return -1;
        }
        String text = String.valueOf(raw).replaceAll("[^0-9:]", "");
        int colon = text.indexOf(':');
        if (colon < 0) {
            return -1;
        }
        try {
            int hour = Integer.parseInt(text.substring(0, colon));
            int minute = Integer.parseInt(text.substring(colon + 1,
                    Math.min(text.length(), colon + 3)));
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                return -1;
            }
            return hour * 60 + minute;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    // -- structural helpers --

    /** One-level descent into the wrapper objects portals like to nest course fields under. */
    private static Object digValue(JSONObject json, String[] keys, int depth) {
        Object direct = firstValue(json, keys);
        if (direct != null || depth <= 0) {
            return direct;
        }
        for (String nested : NESTED_KEYS) {
            JSONObject child = json.optJSONObject(nested);
            if (child == null) {
                continue;
            }
            Object found = digValue(child, keys, depth - 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** Same descent, but only for fields that read as text (a nested object is not a name). */
    private static String digString(JSONObject json, String[] keys, int depth) {
        Object value = digValue(json, keys, depth);
        if (value instanceof String) {
            String text = ((String) value).trim();
            return "null".equalsIgnoreCase(text) ? "" : text;
        }
        if (value instanceof Number) {
            return String.valueOf(value);
        }
        return "";
    }

    private static Object firstValue(JSONObject json, String[] keys) {
        for (String key : keys) {
            if (!json.has(key)) {
                continue;
            }
            Object value = json.opt(key);
            if (usable(value)) {
                return value;
            }
        }
        return null;
    }

    private static boolean usable(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return false;
        }
        if (value instanceof String) {
            String text = ((String) value).trim();
            return !text.isEmpty() && !"null".equalsIgnoreCase(text);
        }
        if (value instanceof JSONObject) {
            return ((JSONObject) value).length() > 0;
        }
        if (value instanceof JSONArray) {
            return ((JSONArray) value).length() > 0;
        }
        return true;
    }

    private static boolean containsAny(String haystack, String[] needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** Chinese numerals, for sections spelled "第三节" rather than "第3节". */
    private static int chineseNumber(String text) {
        int tens = 0;
        int ones = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u5341') {
                tens += (ones == 0 ? 1 : ones) * 10;
                ones = 0;
                continue;
            }
            int digit = CHINESE_DIGITS.indexOf(c);
            if (digit > 0) {
                ones = digit;
            }
        }
        return tens + ones;
    }

    private static String digitsOnly(String text) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            } else if (digits.length() > 0) {
                break;
            }
        }
        return digits.toString();
    }

    private static int parseIntOrZero(String text) {
        try {
            return Integer.parseInt(text);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static long parseTimestamp(Object value) {
        if (value instanceof Number) {
            long number = ((Number) value).longValue();
            if (number > 0 && number < 10_000_000_000L) {
                number *= 1000L;
            }
            return number;
        }
        if (value == null || value == JSONObject.NULL) {
            return 0L;
        }
        String text = String.valueOf(value).trim();
        if (text.matches("\\d{10,13}")) {
            long number = Long.parseLong(text);
            return text.length() == 10 ? number * 1000L : number;
        }
        // Some payloads spell firstWeekDate as a plain date ("2026-09-01").
        if (text.matches("\\d{4}-\\d{1,2}-\\d{1,2}")) {
            try {
                java.text.SimpleDateFormat format =
                        new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
                format.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
                return format.parse(text).getTime();
            } catch (Throwable ignored) {
            }
        }
        return 0L;
    }

    private static JSONArray firstArray(JSONObject json, String... keys) {
        for (String key : keys) {
            JSONArray array = json.optJSONArray(key);
            if (array != null) {
                return array;
            }
        }
        return null;
    }
}
