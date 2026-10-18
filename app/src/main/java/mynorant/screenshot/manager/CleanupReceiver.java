package mynorant.screenshot.manager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** 闹钟到点（或开机/更新后）执行一次清理，然后排下一次。 */
public final class CleanupReceiver extends BroadcastReceiver {

    private static final String TAG = "ScreenshotManager";

    @Override
    public void onReceive(Context context, Intent intent) {
        PendingResult pending = goAsync();
        Context appContext = context.getApplicationContext();
        String action = intent == null ? null : intent.getAction();
        // 开机 / 应用更新后只重排闹钟，不立即检查；到点才执行删除
        boolean runNow = CleanupScheduler.ACTION_CLEANUP.equals(action);
        new Thread(() -> {
            try {
                if (runNow) {
                    MarkCleanup.run(appContext);
                }
            } catch (Throwable t) {
                Log.e(TAG, "cleanup receiver: 执行失败", t);
            } finally {
                CleanupScheduler.scheduleNext(appContext);
                pending.finish();
            }
        }, "mark-cleanup").start();
    }
}
