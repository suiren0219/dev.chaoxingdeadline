package dev.chaoxingdeadline;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Recovering the host context must also (re)register the refresh receiver.
 *
 * {@code Application.attach} is hooked to grab the host context, hook the lifecycle callbacks
 * and register the receiver that serves the module app's refresh buttons. At that moment
 * {@code getApplicationContext()} is still null — LoadedApk stores the application only after
 * {@code attach} returns — so the context write was a no-op and the registration returned
 * early, silently. The fallback that recovers the context later (from ActivityThread) only
 * restored the context and never retried the registration, so no refresh request the module
 * app ever sent had a receiver: "获取整学期" and the widget's empty-state action did nothing,
 * with no error anywhere. Both entry points failed the same way.
 *
 * The check is a source scan because the defect is the absence of a call on a recovery path,
 * which no behavioural test of the module app can observe from the JVM.
 */
public class HostContextRecoveryTest {

    @Test
    public void contextRecoveryRetriesTheReceiverRegistration() throws IOException {
        File source = new File("src/main/java/dev/chaoxingdeadline/ChaoxingHook.java");
        assertTrue("hook source found: " + source.getAbsolutePath(), source.isFile());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        // The recovery helper both adopts the context and finishes the registration.
        int adopt = text.indexOf("private void adoptHostContext(");
        assertTrue("recovery helper exists", adopt > 0);
        int adoptEnd = text.indexOf("\n    }", adopt);
        String adoptBody = text.substring(adopt, adoptEnd > adopt ? adoptEnd : text.length());
        assertTrue("recovery retries the refresh receiver registration",
                adoptBody.contains("registerRefreshRequestReceiver()"));

        // And every recovery path goes through it rather than writing hostContext directly.
        int ensure = text.indexOf("Context ensureHostContext()");
        int ensureEnd = text.indexOf("\n    }", ensure);
        String ensureBody = text.substring(ensure, ensureEnd > ensure ? ensureEnd : text.length());
        assertTrue("recovered from currentApplication", ensureBody.contains("adoptHostContext("));
        assertTrue("recovered from ActivityThread",
                ensureBody.indexOf("adoptHostContext(", ensureBody.indexOf("mInitialApplication")) > 0);
        assertTrue("no recovery path writes hostContext on its own",
                !ensureBody.contains("hostContext = "));
    }

    @Test
    public void attachDoesNotStoreANullApplicationContext() throws IOException {
        File source = new File("src/main/java/dev/chaoxingdeadline/ChaoxingHook.java");
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
        int attach = text.indexOf("installApplicationHook()");
        int end = text.indexOf("\n    private void registerForegroundCallbacks", attach);
        String body = text.substring(attach, end > attach ? end : text.length());

        int store = body.indexOf("hostContext = ");
        assertTrue("attach stores the host context", store > 0);
        String line = body.substring(store, Math.min(body.indexOf('\n', store), store + 200));
        // A plain getApplicationContext() here is null at attach time; the Application
        // itself has to be the fallback or the context write is lost.
        assertTrue("attach falls back to the attached Application when getApplicationContext()"
                + " is still null: " + line.trim(), line.contains("appContext != null"));
    }
}
