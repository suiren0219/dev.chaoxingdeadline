package dev.chaoxingdeadline;

import static org.junit.Assert.assertNotNull;

import android.app.Activity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * Launch-path coverage for the tabbed home screen.
 *
 * The tabs build different views, so most of MainActivity's fields are null while another
 * tab is showing. An unguarded write in reload() then crashes the app on launch — which is
 * exactly what shipped in 1.16.0: the activation labels live on 我的, the app opens on
 * 今日, and onCreate calls reload() immediately. Booting the activity here catches that
 * class of regression long before it reaches a device.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityLaunchTest {

    @Test
    public void launchesOnTheTodoTabWithoutCrashing() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        Activity activity = controller.get();
        assertNotNull("activity survived creation", activity);
        // Resume/stop exercise the polling tick and receiver registration too.
        controller.pause().stop().destroy();
    }

    @Test
    public void rebuildsForEachTabWithoutTouchingDetachedViews() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        // Two tabs now: 待办 and 我的.
        for (int tab = 0; tab < 2; tab++) {
            activity.showTabForTest(tab);
        }
        controller.pause().stop().destroy();
    }
}
