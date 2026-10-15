package dev.chaoxingdeadline;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * A verification challenge on the course endpoints must not stop a timetable sweep.
 *
 * {@code antiSpiderUntil} is set when the course-list endpoint answers "verify first", and
 * the guard that reads it used to cover every {@code active.*} fetch including
 * {@code active.timetable}. That made the semester sweep lock itself out: the module's own
 * course refresh trips the challenge, the cooldown it just created then stops the sweep at
 * week 1, and the module reports a confident "0 weeks with lessons" for a semester it never
 * asked about. The timetable endpoint is a separate read-only path that kept answering 200
 * throughout.
 *
 * Both halves are source-scanned because they are properties of a guard's scope, which no
 * JVM-level test of the sweep can exercise.
 */
public class TimetableCooldownScopeTest {

    @Test
    public void courseCooldownGuardExemptsTheTimetableEndpoint() throws IOException {
        String text = hookSource();
        int guard = text.indexOf("System.currentTimeMillis() < antiSpiderUntil");
        assertTrue("course cooldown guard exists", guard > 0);
        // The guard's own condition carries the exemption; reading a separate field is the
        // other accepted shape, so either satisfies this.
        int condStart = text.lastIndexOf("if (", guard);
        String condition = text.substring(condStart, text.indexOf('{', guard));
        assertTrue("the course cooldown must not gate active.timetable: " + condition,
                condition.contains("!timetableFetch"));

        assertTrue("timetable fetches are recognised",
                text.contains("startsWith(\"active.timetable\")"));
    }

    @Test
    public void timetableVerificationUsesItsOwnCooldown() throws IOException {
        String text = hookSource();
        assertTrue("timetable cooldown field exists", text.contains("timetableBlockedUntil"));

        // The sweep loop tests the timetable's own clock, not the course scans'.
        int sweep = text.indexOf("private int sweepTimetableWeeks(");
        assertTrue("sweep found", sweep > 0);
        int sweepEnd = text.indexOf("return weeksWithLessons;", sweep);
        String body = text.substring(sweep, sweepEnd > sweep ? sweepEnd : text.length());
        assertTrue("the sweep waits on the timetable cooldown",
                body.contains("timetableBlockedUntil"));
        assertTrue("the sweep is no longer gated by the course cooldown",
                !body.contains("antiSpiderUntil"));
    }

    private static String hookSource() throws IOException {
        File source = new File("src/main/java/dev/chaoxingdeadline/ChaoxingHook.java");
        assertTrue("hook source found: " + source.getAbsolutePath(), source.isFile());
        return new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
    }
}
