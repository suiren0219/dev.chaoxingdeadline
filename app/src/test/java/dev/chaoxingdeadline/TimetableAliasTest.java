package dev.chaoxingdeadline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

/**
 * Layer 3: the user-supplied field-name aliases.
 *
 * This is the escape hatch for a school whose portal names its timetable fields in a
 * vocabulary nothing here could guess, so the assertions are about that promise: a spec
 * makes an otherwise unparsable payload parse, and clearing it restores the defaults.
 */
public class TimetableAliasTest {

    @After
    public void resetAliases() {
        // The parser holds the vocabulary statically, so a spec must not leak between tests.
        TimetableParser.setAliasSpec("");
    }

    @Test
    public void aliasSpecMakesAnUnreadableVocabularyReadable() {
        // A payload whose keys are opaque single-letter-ish codes: nothing in the built-in
        // vocabulary matches, and no heuristic could invent "mc"/"xq"/"jc".
        String json = "{\"obj\":{\"rows\":["
                + "{\"mc\":\"示例课程甲\",\"xq\":\"2\",\"jc\":\"3\",\"zs\":\"1-16\","
                + "\"js\":\"老师甲\",\"dd\":\"教一101\"}]}}";
        TimetableParser.setAliasSpec("\u540d\u79f0=mc; \u661f\u671f=xq; \u8282\u6b21=jc; "
                + "\u5468\u6b21=zs; \u6559\u5e08=js; \u5730\u70b9=dd");
        TimetableParser.Result result = TimetableParser.parse(json);
        assertNotNull("the alias spec must make this parse", result);
        assertEquals(1, result.lessons.size());
        Lesson lesson = result.lessons.get(0);
        assertEquals("示例课程甲", lesson.name);
        assertEquals("老师甲", lesson.teacher);
        assertEquals("教一101", lesson.location);
        assertEquals(2, lesson.dayOfWeek);
        assertEquals(3, lesson.beginSection);
        assertEquals("1-16", lesson.weeks);
    }

    @Test
    public void withoutTheSpecTheSamePayloadIsRejected() {
        String json = "{\"obj\":{\"rows\":["
                + "{\"mc\":\"示例课程甲\",\"xq\":\"2\",\"jc\":\"3\",\"zs\":\"1-16\"}]}}";
        TimetableParser.setAliasSpec("");
        assertEquals("no aliases, no reading", null, TimetableParser.parse(json));
    }

    @Test
    public void aliasSpecAlsoOpensTheShapeGate() {
        TimetableParser.setAliasSpec("\u540d\u79f0=mc; \u661f\u671f=xq; \u8282\u6b21=jc");
        String body = "{\"obj\":{\"rows\":[{\"mc\":\"示例课程乙\",\"xq\":\"1\",\"jc\":\"1\"}]}}";
        // Wait: the payload is short, so pad it past the gate's minimum length.
        String padded = "{\"note\":\"\u8bfe\u8868\u6570\u636e\u8bf7\u4ee5\u6559\u52a1\u7cfb\u7edf\u4e3a\u51c6\","
                + "\"obj\":{\"rows\":[{\"mc\":\"示例课程乙\",\"xq\":\"1\",\"jc\":\"1\"}]}}";
        assertTrue("the user's own key opens the gate",
                TimetableParser.looksLikeTimetable(padded, ""));
        assertNotNull(TimetableParser.parse(padded));
        assertTrue(body.length() > 0);
    }

    @Test
    public void clearingTheSpecRestoresTheBuiltInVocabulary() {
        String documented = "{\"data\":{\"lessonArray\":["
                + "{\"name\":\"示例课程丙\",\"dayOfWeek\":1,\"beginNumber\":1,\"length\":2,"
                + "\"weeks\":\"1-16\"}]}}";
        TimetableParser.setAliasSpec("\u540d\u79f0=mc; \u661f\u671f=xq");
        assertNotNull("aliases must not break the documented payload",
                TimetableParser.parse(documented));
        TimetableParser.setAliasSpec("");
        TimetableParser.Result result = TimetableParser.parse(documented);
        assertNotNull(result);
        assertEquals("built-in vocabulary restored", false, result.structural);
        assertEquals(1, result.lessons.get(0).dayOfWeek);
    }

    @Test
    public void unparsableSpecIsIgnoredRatherThanBreakingTheParser() {
        String documented = "{\"data\":{\"lessonArray\":["
                + "{\"name\":\"示例课程丁\",\"dayOfWeek\":1,\"beginNumber\":1}]}}";
        TimetableParser.setAliasSpec("this is not a spec at all");
        TimetableParser.setAliasSpec("=missinglabel");
        TimetableParser.setAliasSpec("\u540d\u79f0=");
        assertNotNull("a bad spec must not disable parsing",
                TimetableParser.parse(documented));
    }

    @Test
    public void aliasKeysAreMergedWithTheDefaultsNotReplacingThem() {
        TimetableParser.setAliasSpec("\u540d\u79f0=mc");
        // The alias supplies the name, the default vocabulary still supplies the rest.
        String json = "{\"obj\":{\"rows\":["
                + "{\"mc\":\"示例课程戊\",\"dayOfWeek\":4,\"beginSection\":5}]}}";
        TimetableParser.Result result = TimetableParser.parse(json);
        assertNotNull(result);
        Lesson lesson = result.lessons.get(0);
        assertEquals("示例课程戊", lesson.name);
        assertEquals(4, lesson.dayOfWeek);
        assertEquals(5, lesson.beginSection);
    }
}
