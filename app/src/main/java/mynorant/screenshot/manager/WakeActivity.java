package mynorant.screenshot.manager;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

/**
 * 无界面唤醒页：宿主在 Provider 写不进去时（进程已被系统回收）显式启动它，
 * 由它把标记写进数据库后立刻结束。
 *
 * <p>不显示任何 UI，主题是透明的；正常情况下用户完全感知不到。</p>
 */
public final class WakeActivity extends Activity {

    private static final String TAG = "ScreenshotManager";

    public static final String EXTRA_URI = "image_uri";
    public static final String EXTRA_DATE_TAKEN = "date_taken";
    public static final String EXTRA_DAYS = "days";
    public static final String EXTRA_DUE_AT = "due_at";
    public static final String EXTRA_NAME = "display_name";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            Intent intent = getIntent();
            int callerUid = getLaunchedFromUid();
            if (callerUid >= 0 && !isTrustedUid(callerUid)) {
                Log.w(TAG, "WakeActivity: 拒绝未知调用方 uid=" + callerUid);
                return;
            }
            String imageUri = intent.getStringExtra(EXTRA_URI);
            long dateTaken = intent.getLongExtra(EXTRA_DATE_TAKEN, 0L);
            int days = intent.getIntExtra(EXTRA_DAYS, 0);
            long dueAt = intent.getLongExtra(EXTRA_DUE_AT, 0L);
            if (imageUri == null || days <= 0 || dueAt <= 0) {
                Log.w(TAG, "WakeActivity: 参数不完整");
                return;
            }
            ContentValues values = new ContentValues();
            values.put(MarkProvider.DbHelper.COL_IMAGE_URI, imageUri);
            values.put(MarkProvider.DbHelper.COL_DATE_TAKEN, dateTaken);
            values.put(MarkProvider.DbHelper.COL_DAYS, days);
            values.put(MarkProvider.DbHelper.COL_DUE_AT, dueAt);
            values.put(MarkProvider.DbHelper.COL_CREATED_AT, System.currentTimeMillis());
            values.put(MarkProvider.DbHelper.COL_DISPLAY_NAME,
                    intent.getStringExtra(EXTRA_NAME));
            Uri row = getContentResolver().insert(MarkProvider.CONTENT_URI, values);
            Log.i(TAG, "WakeActivity: 已写入 " + imageUri + " -> " + row);
            CleanupScheduler.scheduleNext(this);
        } catch (Throwable t) {
            Log.e(TAG, "WakeActivity: 写入失败", t);
        } finally {
            finish();
            overridePendingTransition(0, 0);
        }
    }

    private boolean isTrustedUid(int uid) {
        if (uid == android.os.Process.myUid()) {
            return true;
        }
        try {
            int target = getPackageManager().getPackageUid("com.oplus.screenshot", 0);
            return uid == target;
        } catch (Throwable t) {
            return false;
        }
    }
}
