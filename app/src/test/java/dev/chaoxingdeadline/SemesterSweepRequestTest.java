package dev.chaoxingdeadline;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A semester sweep must reach the sweep branch of the host's refresh receiver.
 *
 * {@link RefreshBridge#requestWholeSemester} sends neither {@code week} nor
 * {@code through_week} — the semester length is resolved host-side against the
 * curriculum's own {@code maxWeek}, so the request carries only the
 * {@code whole_semester} flag. The receiver's gate used to test just the two week
 * extras, so every "获取整学期" tap fell through to the plain full-refresh branch:
 * one request for the current week, no sweep, and nothing written to
 * {@code fetched_weeks}. The timetable page kept showing the same week however often
 * the button was tapped, and the widget's empty-state action — which calls the same
 * method — did nothing at all.
 *
 * The check is a source scan because the gate is a property of the call site's
 * condition, not of any value the JVM tests can hand it.
 */
public class SemesterSweepRequestTest {

    /** The receiver's refresh gate: the condition built from the two week extras. */
    private static final Pattern GATE = Pattern.compile(
            "if \\([^)]*week > 0[^)]*upToWeek > 0[^)]*\\)");

    @Test
    public void refreshGateAcceptsTheWholeSemesterFlag() throws IOException {
        File source = new File("src/main/java/dev/chaoxingdeadline/ChaoxingHook.java");
        assertTrue("hook source found: " + source.getAbsolutePath(), source.isFile());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        Matcher matcher = GATE.matcher(text);
        assertTrue("refresh gate found", matcher.find());
        String gate = matcher.group();
        assertTrue("the gate accepts whole_semester on its own, otherwise a semester sweep "
                + "falls through to a plain refresh: " + gate, gate.contains("fullSemester"));

        // And the sweep branch is still what the gate guards.
        assertTrue("sweep loop still starts inside the gate",
                text.indexOf("sweepTimetableWeeks(from, to)", matcher.end()) > matcher.end());
    }

    @Test
    public void wholeSemesterRequestCarriesTheFlagOnly() throws IOException {
        File source = new File("src/main/java/dev/chaoxingdeadline/RefreshBridge.java");
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
        int method = text.indexOf("public static void requestWholeSemester(");
        assertTrue("requestWholeSemester found", method > 0);
        int end = text.indexOf("\n    }", method);
        String body = text.substring(method, end > method ? end : text.length());
        assertTrue("request still flags a whole-semester sweep",
                body.contains("EXTRA_WHOLE_SEMESTER, true"));
        // Why the gate has to accept the flag by itself: the request carries no week number.
        assertTrue("request carries no week number",
                !body.contains("EXTRA_WEEK") && !body.contains("EXTRA_THROUGH_WEEK"));
    }
}
