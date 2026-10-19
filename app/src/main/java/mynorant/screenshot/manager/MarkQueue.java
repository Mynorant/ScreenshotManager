package mynorant.screenshot.manager;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 落盘队列的接收端。
 *
 * <p>模块在「截屏」进程里写标记时，如果本 App 进程已经被杀/被冻结，
 * Provider 和广播都可能失败。这种情况下模块会把标记写成
 * {@code Download/ScreenshotManager/sm_mark_*.json}，等 App 下次启动时补录。</p>
 */
public final class MarkQueue {

    private static final String TAG = "ScreenshotManager";
    private static final String PREFIX = "sm_mark_";
    private static final java.util.concurrent.atomic.AtomicBoolean RUNNING =
            new java.util.concurrent.atomic.AtomicBoolean();

    private MarkQueue() {
    }

    public static void importPending(Context context) {
        if (!RUNNING.compareAndSet(false, true)) {
            return;
        }
        Context appContext = context.getApplicationContext();
        new Thread(() -> {
            try {
                importNow(appContext);
            } catch (Throwable t) {
                Log.e(TAG, "MarkQueue: 导入失败", t);
            } finally {
                RUNNING.set(false);
            }
        }, "mark-queue").start();
    }

    private static void importNow(Context context) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            return;
        }
        String[] projection = {
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
        };
        String selection = MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?";
        String[] args = {PREFIX + "%"};
        int imported = 0;
        try (Cursor cursor = context.getContentResolver().query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args, null)) {
            if (cursor == null) {
                return;
            }
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                Uri file = ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, id);
                if (importOne(context, file)) {
                    imported++;
                    try {
                        context.getContentResolver().delete(file, null, null);
                    } catch (Throwable t) {
                        Log.w(TAG, "MarkQueue: 删除队列文件失败 " + file, t);
                    }
                } else {
                    Log.w(TAG, "MarkQueue: 保留未导入文件 " + file);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "MarkQueue: 查询队列失败", t);
        }
        if (imported > 0) {
            Log.i(TAG, "MarkQueue: 补录 " + imported + " 条标记");
            CleanupScheduler.scheduleNext(context);
        }
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static boolean importOne(Context context, Uri file) {
        try (InputStream in = context.getContentResolver().openInputStream(file)) {
            if (in == null) {
                return false;
            }
            String text = readAll(in);
            JSONObject json = new JSONObject(text);
            String imageUri = json.optString("image_uri", null);
            long dateTaken = json.optLong("date_taken", 0L);
            int days = json.optInt("days", 0);
            long dueAt = json.optLong("due_at", 0L);
            if (imageUri == null || days <= 0 || dueAt <= 0) {
                return false;
            }
            ContentValues values = new ContentValues();
            values.put(MarkProvider.DbHelper.COL_IMAGE_URI, imageUri);
            values.put(MarkProvider.DbHelper.COL_DATE_TAKEN, dateTaken);
            values.put(MarkProvider.DbHelper.COL_DAYS, days);
            values.put(MarkProvider.DbHelper.COL_DUE_AT, dueAt);
            values.put(MarkProvider.DbHelper.COL_CREATED_AT, System.currentTimeMillis());
            values.put(MarkProvider.DbHelper.COL_DISPLAY_NAME,
                    json.optString("display_name", null));
            Uri row = context.getContentResolver().insert(MarkProvider.CONTENT_URI, values);
            Log.i(TAG, "MarkQueue: 补录 " + imageUri + " -> " + row);
            return row != null;
        } catch (Throwable t) {
            Log.w(TAG, "MarkQueue: 解析失败 " + file, t);
            return false;
        }
    }
}
