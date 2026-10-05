package dev.chaoxingdeadline;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Quick Settings tile: one tap opens the to-do list without hunting for a hidden
 * launcher icon. The subtitle shows how many items are still pending.
 */
public final class DeadlineTileService extends TileService {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "chaoxingdeadline-tile");
        thread.setDaemon(true);
        return thread;
    });
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onStartListening() {
        super.onStartListening();
        refresh();
    }

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        refresh();
    }

    @Override
    public void onClick() {
        super.onClick();
        launchPanel();
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private void launchPanel() {
        Intent intent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                PendingIntent pending = PendingIntent.getActivity(
                        this,
                        1,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                startActivityAndCollapse(pending);
            } else {
                startActivityAndCollapse(intent);
            }
        } catch (Throwable throwable) {
            try {
                startActivity(intent);
            } catch (Throwable ignored) {
            }
        }
    }

    private void refresh() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        // SQLite stays off the main thread (same rule as the receiver); the tile is
        // applied back on the main looper once the count is in.
        EXECUTOR.execute(() -> {
            int pending = 0;
            try {
                long now = System.currentTimeMillis();
                for (DeadlineItem item : new DeadlineStore(this).activeItems()) {
                    if (item != null && !item.submitted && item.dueAt > now) {
                        pending++;
                    }
                }
            } catch (Throwable ignored) {
            }
            final int count = pending;
            mainHandler.post(() -> applyTile(tile, count));
        });
    }

    private void applyTile(Tile tile, int pending) {
        tile.setState(pending > 0 ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(pending > 0 ? "待办 " + pending : "待办");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                tile.setSubtitle(pending > 0 ? pending + " 项未完成" : "暂无待办");
            } catch (Throwable ignored) {
            }
        }
        try {
            tile.setIcon(Icon.createWithResource(this, R.drawable.ic_notification));
        } catch (Throwable ignored) {
        }
        tile.updateTile();
    }
}
