package mynorant.screenshot.manager;

import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import android.widget.Toast;

/**
 * 接收「截屏」进程（模块）发来的标记。
 *
 * <p>不能用 ContentProvider 让宿主直接写：目标应用看不到我们的包（Android 11+ 包可见性），
 * {@code ContentResolver.insert} 会直接抛 "Unknown URL"。显式广播带 ComponentName，
 * 不受包可见性过滤，所以走这里。</p>
 */
public final class MarkReceiver extends BroadcastReceiver {

    private static final String TAG = "ScreenshotManager";
    public static final String ACTION = "mynorant.screenshot.manager.action.MARK";
    public static final String ACTION_TOAST = "mynorant.screenshot.manager.action.TOAST";

    public static final String EXTRA_URI = "image_uri";
    public static final String EXTRA_DATE_TAKEN = "date_taken";
    public static final String EXTRA_DAYS = "days";
    public static final String EXTRA_DUE_AT = "due_at";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_NAME = "display_name";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        Log.i(TAG, "onReceive action=" + intent.getAction() + " uid=" + safeSentUid());
        if (ACTION_TOAST.equals(intent.getAction())) {
            if (!isTrustedSender(context)) {
                Log.w(TAG, "拒绝提示请求: uid=" + safeSentUid() + " myUid=" + Process.myUid()
                        + " debuggable=" + isDebuggable(context));
                return;
            }
            String text = intent.getStringExtra(EXTRA_TEXT);
            if (text != null && !text.isEmpty()) {
                Toast.makeText(context.getApplicationContext(), text, Toast.LENGTH_SHORT).show();
                Log.i(TAG, "Toast: " + text);
            }
            return;
        }
        if (!ACTION.equals(intent.getAction())) {
            return;
        }
        if (!isTrustedSender(context)) {
            Log.w(TAG, "拒绝来自未知来源的标记请求");
            return;
        }
        String imageUri = intent.getStringExtra(EXTRA_URI);
        long dateTaken = intent.getLongExtra(EXTRA_DATE_TAKEN, 0L);
        int days = intent.getIntExtra(EXTRA_DAYS, 0);
        long dueAt = intent.getLongExtra(EXTRA_DUE_AT, 0L);
        if (imageUri == null || days <= 0 || dueAt <= 0) {
            Log.w(TAG, "标记参数不完整: " + imageUri + " days=" + days + " due=" + dueAt);
            return;
        }
        ContentValues values = new ContentValues();
        values.put(MarkProvider.DbHelper.COL_IMAGE_URI, imageUri);
        values.put(MarkProvider.DbHelper.COL_DATE_TAKEN, dateTaken);
        values.put(MarkProvider.DbHelper.COL_DAYS, days);
        values.put(MarkProvider.DbHelper.COL_DUE_AT, dueAt);
        values.put(MarkProvider.DbHelper.COL_CREATED_AT, System.currentTimeMillis());
        values.put(MarkProvider.DbHelper.COL_DISPLAY_NAME, intent.getStringExtra(EXTRA_NAME));
        Uri row = context.getContentResolver().insert(MarkProvider.CONTENT_URI, values);
        Log.i(TAG, "已写入标记: " + imageUri + " days=" + days + " row=" + row);
        Toast.makeText(context.getApplicationContext(),
                "已标记：" + days + " 天后删除", Toast.LENGTH_SHORT).show();
    }

    /** API 34+ 能拿到发送方 UID；更老的系统上只校验包名。 */
    private int safeSentUid() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return getSentFromUid();
        }
        return -1;
    }

    private boolean isDebuggable(Context context) {        return (context.getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    private boolean isTrustedSender(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            int uid = getSentFromUid();
            if (uid == Process.myUid()) {
                return true;
            }
            if (isDebuggable(context) && uid == 2000) {
                // 仅调试构建：允许 adb shell 发广播，方便不依赖手机点击做验证
                return true;
            }
            try {
                int target = context.getPackageManager()
                        .getPackageUid("com.oplus.screenshot", 0);
                return uid == target;
            } catch (Throwable t) {
                return false;
            }
        }
        return "com.oplus.screenshot".equals(getSentFromPackage());
    }
}
