package dev.chaoxingdeadline;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Request headers must be set before the POST body is written.
 *
 * {@code HttpURLConnection} connects on the first {@code getOutputStream()} call, and a
 * {@code setRequestProperty} after that throws {@code IllegalStateException}. The timetable
 * endpoint is the only fetch that POSTs (the week travels in the body), so when the cookie
 * was set after the body write every timetable fetch died with that exception while every
 * GET-based fetch worked. The visible symptom was a semester sweep that silently produced
 * nothing: no week ever reached {@code fetched_weeks}, and the timetable page showed only
 * whatever a passive page capture had happened to collect.
 *
 * The check is a source scan because the failure depends on statement order inside one
 * method, which no JVM-level test of the surrounding behaviour can observe.
 */
public class HttpHeaderOrderTest {

    @Test
    public void cookieIsSetBeforeThePostBodyIsWritten() throws IOException {
        File source = new File("src/main/java/dev/chaoxingdeadline/ChaoxingHook.java");
        assertTrue("hook source found: " + source.getAbsolutePath(), source.isFile());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        int cookie = text.indexOf("connection.setRequestProperty(\"Cookie\", cookie)");
        int body = text.indexOf("connection.getOutputStream()");
        assertTrue("cookie header is set somewhere", cookie > 0);
        assertTrue("POST body is written somewhere", body > 0);
        assertTrue("the cookie must be set before getOutputStream() connects the request:"
                        + " cookie at " + cookie + ", body at " + body,
                cookie < body);

        // And it must still be inside the same fetch helper, not some unrelated method.
        int method = text.lastIndexOf("private int fetchUrl(", cookie);
        assertTrue("cookie is set inside fetchUrl", method > 0);
    }
}
