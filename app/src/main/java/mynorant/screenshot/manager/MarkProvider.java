package mynorant.screenshot.manager;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.widget.Toast;

/**
 * 倒计时删除清单的存储端。
 *
 * <p>标记动作发生在「截屏」进程里（模块 hook 的上下文），而数据必须落到我们自己的 App，
 * 所以用 ContentProvider 做跨进程写入：宿主用 {@code ContentResolver.insert} 提交，
 * 这里校验调用方 UID 只能是「截屏」或我们自己。</p>
 */
public final class MarkProvider extends ContentProvider {

    private static final String TAG = "ScreenshotManager";
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    public static final String AUTHORITY = "mynorant.screenshot.manager.marks";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/marks");

    /** 允许写入的宿主包名。 */
    private static final String TARGET_PACKAGE = "com.oplus.screenshot";

    private static final int MARKS = 1;
    private static final int MARK_ID = 2;
    private static final UriMatcher MATCHER = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        MATCHER.addURI(AUTHORITY, "marks", MARKS);
        MATCHER.addURI(AUTHORITY, "marks/#", MARK_ID);
    }

    private DbHelper helper;

    @Override
    public boolean onCreate() {
        helper = new DbHelper(getContext());
        return true;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        if (MATCHER.match(uri) != MARKS) {
            throw new IllegalArgumentException("不支持的 uri: " + uri);
        }
        if (!isTrustedCaller()) {
            throw new SecurityException("调用方不允许写入: uid=" + Binder.getCallingUid());
        }
        ContentValues copy = new ContentValues(values);
        if (!copy.containsKey(DbHelper.COL_CREATED_AT)) {
            copy.put(DbHelper.COL_CREATED_AT, System.currentTimeMillis());
        }
        long id = helper.getWritableDatabase()
                .insertWithOnConflict(DbHelper.TABLE, null, copy, SQLiteDatabase.CONFLICT_REPLACE);
        if (id < 0) {
            Log.e(TAG, "写入标记失败: " + copy);
            return null;
        }
        Uri inserted = ContentUris.withAppendedId(CONTENT_URI, id);
        Context context = getContext();
        if (context != null) {
            context.getContentResolver().notifyChange(inserted, null);
            // 写入即刻把下一次清理排到最早的到期时间
            CleanupScheduler.scheduleNext(context);
            showToast(context, copy.getAsInteger(DbHelper.COL_DAYS));
        }
        return inserted;
    }

    /** Toast 必须由我们自己的 App 弹：宿主的通知权限是被关掉的。 */
    private static void showToast(Context context, Integer days) {
        String text = days != null && days > 0
                ? "已标记：" + days + " 天后删除"
                : "已标记";
        MAIN_HANDLER.post(() -> Toast.makeText(context, text, Toast.LENGTH_SHORT).show());
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        if (!isTrustedCaller()) {
            throw new SecurityException("调用方不允许查询");
        }
        SQLiteDatabase database = helper.getReadableDatabase();
        Cursor cursor;
        switch (MATCHER.match(uri)) {
            case MARKS:
                cursor = database.query(DbHelper.TABLE, projection, selection,
                        selectionArgs, null, null,
                        sortOrder != null ? sortOrder : DbHelper.COL_DUE_AT + " ASC");
                break;
            case MARK_ID:
                cursor = database.query(DbHelper.TABLE, projection,
                        DbHelper.COL_ID + " = ?", new String[]{uri.getLastPathSegment()},
                        null, null, null);
                break;
            default:
                throw new IllegalArgumentException("不支持的 uri: " + uri);
        }
        Context context = getContext();
        if (context != null) {
            cursor.setNotificationUri(context.getContentResolver(), uri);
        }
        return cursor;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        if (!isTrustedCaller()) {
            throw new SecurityException("调用方不允许删除");
        }
        SQLiteDatabase database = helper.getWritableDatabase();
        int count;
        if (MATCHER.match(uri) == MARK_ID) {
            count = database.delete(DbHelper.TABLE, DbHelper.COL_ID + " = ?",
                    new String[]{uri.getLastPathSegment()});
        } else {
            count = database.delete(DbHelper.TABLE, selection, selectionArgs);
        }
        Context context = getContext();
        if (context != null && count > 0) {
            context.getContentResolver().notifyChange(uri, null);
        }
        return count;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        if (!isTrustedCaller()) {
            throw new SecurityException("调用方不允许更新");
        }
        SQLiteDatabase database = helper.getWritableDatabase();
        int count = database.update(DbHelper.TABLE, values, selection, selectionArgs);
        Context context = getContext();
        if (context != null && count > 0) {
            context.getContentResolver().notifyChange(uri, null);
        }
        return count;
    }

    @Override
    public String getType(Uri uri) {
        return MATCHER.match(uri) == MARKS
                ? "vnd.android.cursor.dir/vnd.screenshot.mark"
                : "vnd.android.cursor.item/vnd.screenshot.mark";
    }

    /** 只接受「截屏」进程（模块在那边运行）和我们自己。 */
    private boolean isTrustedCaller() {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) {
            return true;
        }
        Context context = getContext();
        if (context == null) {
            return false;
        }
        try {
            int targetUid = context.getPackageManager().getPackageUid(TARGET_PACKAGE, 0);
            return uid == targetUid;
        } catch (Throwable t) {
            return false;
        }
    }

    static final class DbHelper extends SQLiteOpenHelper {

        static final String TABLE = "marks";
        static final String COL_ID = "_id";
        static final String COL_IMAGE_URI = "image_uri";
        static final String COL_DATE_TAKEN = "date_taken";
        static final String COL_DAYS = "days";
        static final String COL_DUE_AT = "due_at";
        static final String COL_CREATED_AT = "created_at";
        static final String COL_DISPLAY_NAME = "display_name";

        private static final String DB_NAME = "marks.db";
        private static final int DB_VERSION = 1;

        DbHelper(Context context) {
            super(context, DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE + " ("
                    + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + COL_IMAGE_URI + " TEXT NOT NULL UNIQUE, "
                    + COL_DATE_TAKEN + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_DAYS + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_DUE_AT + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_CREATED_AT + " INTEGER NOT NULL DEFAULT 0, "
                    + COL_DISPLAY_NAME + " TEXT)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            db.execSQL("DROP TABLE IF EXISTS " + TABLE);
            onCreate(db);
        }
    }
}
