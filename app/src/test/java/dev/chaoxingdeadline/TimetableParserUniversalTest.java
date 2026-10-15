package dev.chaoxingdeadline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The universal (structural) parse layer: payload shapes that the keyed layer cannot read
 * because their field names, value spellings or nesting differ from the documented API.
 *
 * Each case here stands for a portal build seen in the wild or a plausible variant of one;
 * the point of the layer is that none of them needs a code change to work.
 */
public class TimetableParserUniversalTest {

    @Test
    public void parsesWhenLessonListIsNestedOneLevelDeeper() {
        // A wrapper object: the documented keys are there, but the list is not where the
        // keyed layer looks for it. The structural walk still finds the rows.
        String json = "{\"code\":0,\"result\":{\"content\":{\"rows\":["
                + "{\"courseName\":\"示例课程甲\",\"teacherName\":\"老师甲\",\"roomName\":\"教一101\","
                + "\"dayOfWeek\":1,\"beginSection\":1,\"sectionLength\":2,\"weeks\":\"1-16\"}],"
                + "\"firstWeekDate\":\"2026-09-07\"}}}";
        TimetableParser.Result result = TimetableParser.parse(json);
        assertNotNull("nested list must parse structurally", result);
        assertTrue("recognised structurally", result.structural);
        assertEquals(1, result.lessons.size());
        Lesson lesson = result.lessons.get(0);
        assertEquals("示例课程甲", lesson.name);
        assertEquals("老师甲", lesson.teacher);
        assertEquals("教一101", lesson.location);
        assertEquals(1, lesson.dayOfWeek);
        assertEquals(1, lesson.beginSection);
        assertEquals(2, lesson.sections);
        assertTrue("week-1 anchor found at depth", result.firstWeekDate > 0L);
    }

    @Test
    public void readsChineseWeekdayAndChineseSectionSpellings() {
        String json = "{\"data\":{\"scheduleList\":["
                + "{\"kcmc\":\"示例课程乙\",\"jsxm\":\"老师乙\",\"kcdd\":\"教二202\","
                + "\"xqj\":\"周三\",\"jieci\":\"第3-4节\",\"zs\":\"1-16周\"}]}}";
        TimetableParser.Result result = TimetableParser.parse(json);
        assertNotNull(result);
        Lesson lesson = result.lessons.get(0);
        assertEquals("\u5468\u4e09 is Wednesday", 3, lesson.dayOfWeek);
        assertEquals("\u7b2c3-4\u8282 starts at section 3", 3, lesson.beginSection);
        assertEquals("3-4 spans two sections", 2, lesson.sections);
        assertEquals("1-16\u5468 normalises", "1-16", lesson.weeks);
    }

    @Test
    public void readsWeekdayKeyedMapWithRowsLackingWeekdays() {
        // The shape where the outer object is keyed by weekday and the rows only carry a
        // section: "{"1":[{...section 1...},{...section 2...}]}".
        String json = "{\"data\":{\"lessons\":{"
                + "\"1\":[{\"name\":\"示例课程丙\",\"beginSection\":1,\"weeks\":\"1-16\"}],"
                + "\"5\":[{\"name\":\"示例课程丁\",\"beginSection\":3,\"weeks\":\"1-8\"}]}}}";
        TimetableParser.Result result = TimetableParser.parse(json);
        assertNotNull(result);
        assertEquals(2, result.lessons.size());
        // Both rows must keep the weekday their containing key gave them; the walk visits
        // keys in hash order, so assert on membership rather than position.
        boolean monday = false;
        boolean friday = false;
        for (Lesson lesson : result.lessons) {
            if ("\u793a\u4f8b\u8bfe\u7a0b\u4e19".equals(lesson.name)) {
                assertEquals(1, lesson.dayOfWeek);
                assertEquals(1, lesson.beginSection);
                monday = true;
            }
            if ("\u793a\u4f8b\u8bfe\u7a0b\u4e01".equals(lesson.name)) {
                assertEquals(5, lesson.dayOfWeek);
                assertEquals(3, lesson.beginSection);
                friday = true;
            }
        }
        assertTrue("Monday row kept its key's weekday", monday);
        assertTrue("Friday row kept its key's weekday", friday);
    }

    @Test
    public void readsWeeksAsArrayAndExpandsOddEvenTerms() {
        String json = "{\"data\":{\"lessonArray\":["
                + "{\"name\":\"示例课程戊\",\"dayOfWeek\":2,\"beginSection\":1,"
                + "\"weeks\":[1,2,3,5]}],\"curriculum\":{\"firstWeekDate\":\"2026-09-07\"}}}";
        TimetableParser.Result result = TimetableParser.parse(json);
        assertNotNull(result);
        assertEquals("1-3,5", result.lessons.get(0).weeks);

        String odd = "{\"data\":{\"lessonArray\":["
                + "{\"name\":\"示例课程己\",\"dayOfWeek\":2,\"beginSection\":1,"
                + "\"weeks\":\"1-8\u5355\"}]}}";
        TimetableParser.Result oddResult = TimetableParser.parse(odd);
        assertNotNull(oddResult);
        // An odd-week course has no range spelling in the app's model, so it is expanded
        // into the explicit weeks it means.
        assertEquals("1,3,5,7", oddResult.lessons.get(0).weeks);
        assertEquals(4, ScheduleMath.weeksList(oddResult.lessons.get(0).weeks).size());
    }

    @Test
    public void placesTimeOnlyLessonsOnTheSectionTable() {
        // No section fields at all — only clock times — which some portal builds publish.
        // The school's own section table turns them back into section positions.
        String json = "{\"data\":{\"courseVoList\":["
                + "{\"name\":\"示例课程庚\",\"dayOfWeek\":4,\"startTime\":\"08:00\",\"endTime\":\"09:40\"}],"
                + "\"curriculum\":{\"firstWeekDate\":1789948800000,"
                + "\"lessonTimeConfigArray\":[\"8:00-8:45\",\"8:55-9:40\",\"10:00-10:45\"]}}}";
        TimetableParser.Result result = TimetableParser.parse(json);
        assertNotNull(result);
        assertEquals(1, result.lessons.size());
        Lesson lesson = result.lessons.get(0);
        assertEquals("time-only lesson placed at section 1", 1, lesson.beginSection);
        assertEquals("covering sections 1-2", 2, lesson.sections);
    }

    @Test
    public void readsSectionTablePublishedAsObjects() {
        String json = "{\"data\":{\"courseVoList\":["
                + "{\"name\":\"示例课程辛\",\"dayOfWeek\":4,\"beginSection\":1,\"weeks\":\"1\"}],"
                + "\"curriculum\":{\"firstWeekDate\":1789948800000,"
                + "\"lessonTimeConfigArray\":[{\"startTime\":\"08:00\",\"endTime\":\"08:45\"},"
                + "{\"startTime\":\"08:55\",\"endTime\":\"09:40\"}]}}}";
        TimetableParser.Result result = TimetableParser.parse(json);
        assertNotNull(result);
        assertNotNull(result.sectionTimesJson);
        assertEquals(8 * 60, ScheduleMath.sectionStartMinutes(result.sectionTimesJson, 1));
        assertEquals(8 * 60 + 45, ScheduleMath.sectionEndMinutes(result.sectionTimesJson, 1));
        assertEquals(8 * 60 + 55, ScheduleMath.sectionStartMinutes(result.sectionTimesJson, 2));
    }

    @Test
    public void readsZeroBasedWeekdayAsSunday() {
        // JS Date.getDay() spells Sunday 0, and portals built from JS often serve it that way.
        assertEquals(7, TimetableParser.looseDay(0));
        assertEquals(1, TimetableParser.looseDay(1));
        assertEquals(3, TimetableParser.looseDay("\u661f\u671f\u4e09"));
        assertEquals(7, TimetableParser.looseDay("\u5468\u65e5"));
        assertEquals(2, TimetableParser.looseDay("Tue"));
        assertEquals(0, TimetableParser.looseDay("\u4e0d\u662f\u661f\u671f"));
    }

    @Test
    public void urlHintOpensTheGateForUnfamiliarBodies() {
        // A body in a vocabulary this parser has never seen, carried by a URL that names
        // the timetable: the URL alone is enough to attempt a structural walk.
        String body = "{\"obj\":{\"items\":[{\"xq\":\"1\",\"jc\":\"1\",\"mc\":\"X\"}]}}";
        assertFalse("body alone carries no recognisable token",
                TimetableParser.looksLikeTimetable(body, ""));
        assertTrue("curriculum URL opens the gate",
                TimetableParser.looksLikeTimetable(body,
                        "https://kb.chaoxing.com/pc/curriculum/getMyLessons"));
        assertTrue("schedule URL opens the gate",
                TimetableParser.looksLikeTimetable(body,
                        "https://example.edu/api/schedule/list"));
    }

    @Test
    public void structuralLayerStillRejectsUnrelatedPayloads() {
        // Generosity must not turn every JSON document into a timetable.
        assertEquals(null, TimetableParser.parse(
                "{\"data\":{\"channelList\":[{\"id\":1,\"name\":\"首页\"}]}}"));
        assertEquals(null, TimetableParser.parse(
                "{\"data\":{\"list\":[{\"name\":\"作业一\",\"deadline\":\"2026-10-05 23:59\"}]}}"));
        // A weekday without a name is not a lesson.
        assertEquals(null, TimetableParser.parse(
                "{\"data\":{\"list\":[{\"dayOfWeek\":3,\"beginSection\":2}]}}"));
    }

    @Test
    public void keyedLayerIsPreferredAndStillWorks() {
        String json = "{\"data\":{\"lessonArray\":["
                + "{\"lessonId\":\"L1\",\"name\":\"示例课程壬\",\"dayOfWeek\":1,"
                + "\"beginNumber\":1,\"length\":2,\"weeks\":\"1-16\"}],"
                + "\"curriculum\":{\"firstWeekDate\":1789948800000,"
                + "\"maxWeek\":20,\"realCurrentWeek\":7}}}";
        TimetableParser.Result result = TimetableParser.parse(json);
        assertNotNull(result);
        assertEquals("documented payload uses the keyed layer", false, result.structural);
        assertEquals("L1", result.lessons.get(0).id);
        assertEquals(20, result.maxWeek);
        assertEquals(7, result.currentWeek);
    }
}
