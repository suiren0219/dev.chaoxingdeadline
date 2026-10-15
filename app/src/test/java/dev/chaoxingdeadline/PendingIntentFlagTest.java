package dev.chaoxingdeadline;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every PendingIntent must declare its mutability.
 *
 * On Android 12+ creating one without {@code FLAG_IMMUTABLE} or {@code FLAG_MUTABLE} throws
 * {@code IllegalArgumentException}. That is invisible to the JVM unit tests — Robolectric's
 * shadow does not enforce the rule — and invisible to the compiler, so a single omission
 * ships as an instant launch crash. It did: {@code classReminderAlarmIntent} lacked the flag,
 * and because it is reached from {@code App.onCreate} through {@code rescheduleAll}, the app
 * died on every launch with a black screen and no crash dialog (Android suppresses the dialog
 * for repeatedly crashing apps).
 *
 * The check is a source scan rather than a behavioural test because the failure mode is a
 * property of the call site. Reading the constructors' source is the only way to see every
 * one of them; a runtime test can only cover the paths it happens to execute.
 */
public class PendingIntentFlagTest {

    /** The two flags that satisfy the platform requirement. */
    private static final Pattern MUTABILITY =
            Pattern.compile("FLAG_IMMUTABLE|FLAG_MUTABLE");
    /** A PendingIntent factory call, up to the end of its argument list. */
    private static final Pattern CREATION = Pattern.compile(
            "PendingIntent\\.get(?:Activity|Broadcast|Service|ForegroundService)\\s*\\(");

    @Test
    public void everyPendingIntentCreationDeclaresMutability() throws IOException {
        File sourceRoot = new File("src/main/java/dev/chaoxingdeadline");
        assertTrue("source root found: " + sourceRoot.getAbsolutePath(), sourceRoot.isDirectory());

        List<String> offenders = new ArrayList<>();
        int checked = 0;
        List<File> sources = new ArrayList<>();
        collectJavaFiles(sourceRoot, sources);
        assertTrue("found source files to scan", sources.size() > 5);

        for (File file : sources) {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            Matcher matcher = CREATION.matcher(text);
            while (matcher.find()) {
                // Walk to the matching close paren so multi-line argument lists are covered.
                int depth = 1;
                int index = matcher.end();
                while (index < text.length() && depth > 0) {
                    char c = text.charAt(index);
                    if (c == '(') {
                        depth++;
                    } else if (c == ')') {
                        depth--;
                    }
                    index++;
                }
                String call = text.substring(matcher.start(), index);
                checked++;
                if (!MUTABILITY.matcher(call).find()) {
                    int line = text.substring(0, matcher.start()).split("\n", -1).length;
                    offenders.add(file.getName() + ":" + line);
                }
            }
        }

        assertTrue("scanned a meaningful number of call sites (found " + checked + ")",
                checked >= 8);
        assertTrue("这些 PendingIntent 创建点缺少 FLAG_IMMUTABLE / FLAG_MUTABLE"
                        + "（Android 12+ 会抛 IllegalArgumentException，启动即闪退）: " + offenders,
                offenders.isEmpty());
    }

    private static void collectJavaFiles(File directory, List<File> out) {
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectJavaFiles(child, out);
            } else if (child.getName().endsWith(".java")) {
                out.add(child);
            }
        }
    }
}
