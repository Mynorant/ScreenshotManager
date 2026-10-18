package mynorant.screenshot.manager;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 到点删除引擎。
 *
 * <p>安全约束（任何一条不满足就跳过，绝不强删）：</p>
 * <ol>
 *     <li>只处理 marks 表里登记过的行，不按目录/条件批量删；</li>
 *     <li>URI 必须是 {@code content://media/external/images/media/<id>}；</li>
 *     <li>删除前重新查该 URI 的 DATE_TAKEN，必须与标记时记录的值完全一致
 *         （防止 MediaStore 的 _id 被回收后误删新图）；</li>
 *     <li>只按 URI 删除，且删除行数必须为 1，否则保留标记等下次；</li>
 *     <li>每轮最多删 {@link #MAX_PER_RUN} 条，异常只记录不重试强删。</li>
 * </ol>
 */
public final class MarkCleanup {

    private static final String TAG = "ScreenshotManager";
    private static final int MAX_PER_RUN = 20;

    private static final String[] MARK_PROJECTION = {
            MarkProvider.DbHelper.COL_ID,
            MarkProvider.DbHelper.COL_IMAGE_URI,
            MarkProvider.DbHelper.COL_DATE_TAKEN,
            MarkProvider.DbHelper.COL_DUE_AT,
            MarkProvider.DbHelper.COL_DAYS,
    };

    private MarkCleanup() {
    }

    public static final class Result {
        public int deleted;
        public int skipped;
        public int failed;
    }

    public static Result run(Context context) {
        Result result = new Result();
        List<long[]> ids = new ArrayList<>();
        List<Uri> uris = new ArrayList<>();
        List<Long> dateTakens = new ArrayList<>();

        long now = System.currentTimeMillis();
        try (Cursor cursor = context.getContentResolver().query(
                MarkProvider.CONTENT_URI, MARK_PROJECTION,
                MarkProvider.DbHelper.COL_DUE_AT + " <= ?", new String[]{String.valueOf(now)},
                MarkProvider.DbHelper.COL_DUE_AT + " ASC")) {
            if (cursor == null) {
                Log.w(TAG, "cleanup: 查询到期标记失败（cursor=null）");
                return result;
            }
            while (cursor.moveToNext() && ids.size() < MAX_PER_RUN) {
                long id = cursor.getLong(0);
                String uriText = cursor.getString(1);
                long dateTaken = cursor.getLong(2);
                Uri uri = uriText == null ? null : Uri.parse(uriText);
                if (uri == null) {
                    result.skipped++;
                    Log.w(TAG, "cleanup: 跳过空 URI, id=" + id);
                    continue;
                }
                ids.add(new long[]{id});
                uris.add(uri);
                dateTakens.add(dateTaken);
            }
        } catch (Throwable t) {
            Log.e(TAG, "cleanup: 查询标记异常", t);
            return result;
        }

        for (int i = 0; i < ids.size(); i++) {
            long id = ids.get(i)[0];
            Uri uri = uris.get(i);
            long expectedDateTaken = dateTakens.get(i);

            Boolean present = isPresent(context, uri);
            if (Boolean.FALSE.equals(present)) {
                // 图片已被外部删除：只移除标记，不做任何删除动作
                removeMark(context, id);
                result.skipped++;
                Log.w(TAG, "cleanup: 图片已不存在，移除标记, id=" + id + " uri=" + uri);
                continue;
            }

            int outcome = deleteImage(context, uri, expectedDateTaken);
            if (outcome == DELETE_OK) {
                removeMark(context, id);
                result.deleted++;
                Log.i(TAG, "cleanup: 已删除 id=" + id + " uri=" + uri);
            } else if (outcome == DELETE_SKIPPED) {
                result.skipped++;
            } else {
                result.failed++;
            }
        }
        Log.i(TAG, "cleanup: 完成 deleted=" + result.deleted
                + " skipped=" + result.skipped + " failed=" + result.failed);
        return result;
    }

    public static final int DELETE_OK = 1;
    public static final int DELETE_SKIPPED = 0;
    public static final int DELETE_FAILED = -1;

    /**
     * 删除一张图片（带全部安全校验）。
     *
     * @return {@link #DELETE_OK} 成功；{@link #DELETE_SKIPPED} 校验不通过而跳过；{@link #DELETE_FAILED} 删除异常
     */
    public static int deleteImage(Context context, Uri uri, long expectedDateTaken) {
        if (!isDeletableMediaUri(uri)) {
            Log.w(TAG, "delete: 非图片 URI，跳过 " + uri);
            return DELETE_SKIPPED;
        }
        Long actualDateTaken = queryDateTaken(context, uri);
        if (actualDateTaken == null) {
            Log.w(TAG, "delete: 图片已不存在，跳过 " + uri);
            return DELETE_SKIPPED;
        }
        if (expectedDateTaken > 0 && actualDateTaken != expectedDateTaken) {
            Log.w(TAG, "delete: DATE_TAKEN 不一致（疑似 _id 被复用），拒绝删除 " + uri
                    + " expected=" + expectedDateTaken + " actual=" + actualDateTaken);
            return DELETE_SKIPPED;
        }
        try {
            int deleted = context.getContentResolver().delete(uri, null, null);
            if (deleted == 1) {
                return DELETE_OK;
            }
            Log.w(TAG, "delete: 删除行数异常(" + deleted + ") " + uri);
            return DELETE_SKIPPED;
        } catch (Throwable t) {
            Log.e(TAG, "delete: 删除失败 " + uri, t);
            return DELETE_FAILED;
        }
    }

    public static void removeMark(Context context, long id) {
        try {
            context.getContentResolver().delete(
                    ContentUris.withAppendedId(MarkProvider.CONTENT_URI, id), null, null);
        } catch (Throwable t) {
            Log.e(TAG, "removeMark 失败 id=" + id, t);
        }
    }

    /**
     * 图片是否还在（包含「回收站」里的图片）。
     *
     * <p>回收站中的图片在普通查询里是不可见的；这里用
     * {@link MediaStore#QUERY_ARG_MATCH_TRASHED} + {@link MediaStore#MATCH_INCLUDE}
     * 把回收站也纳入，避免刚移进回收站就把标记清掉。</p>
     *
     * @return {@code TRUE} 存在、{@code FALSE} 明确不存在、{@code null} 查询异常（不确定，不要据此删标记）
     */
    public static Boolean isPresent(Context context, Uri uri) {
        if (uri == null) {
            return Boolean.FALSE;
        }
        try {
            Cursor cursor;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bundle args = new Bundle();
                args.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);
                cursor = context.getContentResolver().query(uri,
                        new String[]{MediaStore.Images.Media._ID}, args, null);
            } else {
                cursor = context.getContentResolver().query(uri,
                        new String[]{MediaStore.Images.Media._ID}, null, null, null);
            }
            if (cursor == null) {
                return null;
            }
            try (Cursor closeable = cursor) {
                return closeable.getCount() > 0;
            }
        } catch (Throwable t) {
            Log.w(TAG, "isPresent 查询失败 " + uri, t);
            return null;
        }
    }

    /** 只允许 MediaStore 的外部图片 URI，别的一律不碰。 */
    private static boolean isDeletableMediaUri(Uri uri) {
        if (!"content".equals(uri.getScheme())) {
            return false;
        }
        if (!"media".equals(uri.getAuthority())) {
            return false;
        }
        String path = uri.getPath();
        return path != null && path.startsWith("/external/images/media/")
                && uri.getLastPathSegment() != null;
    }

    /** 查 DATE_TAKEN；不存在返回 null。 */
    private static Long queryDateTaken(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN},
                null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }
            if (cursor.getCount() != 1) {
                return null;
            }
            if (cursor.isNull(1)) {
                return null;
            }
            return cursor.getLong(1);
        } catch (Throwable t) {
            Log.w(TAG, "cleanup: 查询 DATE_TAKEN 失败 uri=" + uri, t);
            return null;
        }
    }
}
