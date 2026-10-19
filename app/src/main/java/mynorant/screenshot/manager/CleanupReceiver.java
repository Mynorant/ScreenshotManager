package mynorant.screenshot.manager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** 闹钟到点（或开机/更新后）执行一次清理，然后排下一次。 */
public final class CleanupReceiver extends BroadcastReceiver {

    private static final String TAG = "ScreenshotManager";
    /** 任务被划掉后用来重拉常驻服务的内部动作。 */
    public static final String ACTION_RESTART = "mynorant.screenshot.manager.action.RESTART";

    @Override
    public void onReceive(Context context, Intent intent) {
        PendingResult pending = goAsync();
        Context appContext = context.getApplicationContext();
        String action = intent == null ? null : intent.getAction();
        if (ACTION_RESTART.equals(action)) {
            MarkQueue.importPending(appContext);
            pending.finish();
            return;
        }
        // 开机 / 应用更新后只重排闹钟，不立即检查；到点才执行删除
        boolean runNow = CleanupScheduler.ACTION_CLEANUP.equals(action);
        new Thread(() -> {
            try {
                if (runNow) {
                    MarkCleanup.run(appContext);
                } else {
                    // 开机 / 更新后把常驻服务拉起来，并补录落盘队列里的标记
                    MarkQueue.importPending(appContext);
                    MarkQueue.importPending(appContext);
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
