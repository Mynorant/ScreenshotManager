package mynorant.screenshot.manager;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Build;
import android.util.Log;

/** 把下一次清理定到「最早的到期时间」。到期时间本身都是 0 点，所以生产环境天然是每天 0 点跑。 */
public final class CleanupScheduler {

    private static final String TAG = "ScreenshotManager";
    public static final String ACTION_CLEANUP = "mynorant.screenshot.manager.action.CLEANUP";

    private CleanupScheduler() {
    }

    public static void scheduleNext(Context context) {
        AlarmManager alarmManager = context.getSystemService(AlarmManager.class);
        if (alarmManager == null) {
            return;
        }
        PendingIntent pendingIntent = pendingIntent(context);
        alarmManager.cancel(pendingIntent);

        long next = nextDueAt(context);
        if (next <= 0) {
            Log.i(TAG, "scheduler: 没有待删除项");
            return;
        }
        long triggerAt = Math.max(next, System.currentTimeMillis() + 2000L);
        try {
            boolean exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                    || alarmManager.canScheduleExactAlarms();
            if (exact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
            }
            Log.i(TAG, "scheduler: 下次清理 " + new java.util.Date(triggerAt) + " exact=" + exact);
        } catch (Throwable t) {
            Log.e(TAG, "scheduler: 设置闹钟失败", t);
        }
    }

    private static long nextDueAt(Context context) {
        try (Cursor cursor = context.getContentResolver().query(
                MarkProvider.CONTENT_URI,
                new String[]{MarkProvider.DbHelper.COL_DUE_AT},
                null, null,
                MarkProvider.DbHelper.COL_DUE_AT + " ASC")) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
        } catch (Throwable t) {
            Log.e(TAG, "scheduler: 查询最早到期时间失败", t);
        }
        return 0L;
    }

    private static PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(context, CleanupReceiver.class).setAction(ACTION_CLEANUP);
        return PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
