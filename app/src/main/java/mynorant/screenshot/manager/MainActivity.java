package mynorant.screenshot.manager;

import android.Manifest;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 主界面：权限状态 + 已标记列表。
 *
 * <p>所有文件访问权限是 0 点自动删除的前提；Toast 依赖 App 自己的通知权限（安装时已声明）。</p>
 */
public final class MainActivity extends AppCompatActivity {

    private static final String[] PROJECTION = {
            MarkProvider.DbHelper.COL_ID,
            MarkProvider.DbHelper.COL_IMAGE_URI,
            MarkProvider.DbHelper.COL_DUE_AT,
            MarkProvider.DbHelper.COL_DAYS,
            MarkProvider.DbHelper.COL_DISPLAY_NAME,
            MarkProvider.DbHelper.COL_DATE_TAKEN,
    };

    private final ExecutorService thumbnailExecutor = Executors.newSingleThreadExecutor();
    private final SimpleDateFormat dueFormat =
            new SimpleDateFormat("MM-dd HH:mm 到期", Locale.getDefault());
    private final ContentObserver markObserver =
            new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    loadMarks();
                }
            };

    private LinearLayout markList;
    private TextView markEmpty;
    /** 0 = 全部；1/3/7 = 对应天数。 */
    private int filterDays;
    private final List<MarkRow> allRows = new ArrayList<>();
    /** 主动跳转到系统设置时，不要顺手把自己的任务也移除。 */
    private boolean launchingExternal;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        markList = findViewById(R.id.mark_list);
        markEmpty = findViewById(R.id.mark_empty);

        hideFromRecents();


        com.google.android.material.appbar.MaterialToolbar toolbar =
                findViewById(R.id.toolbar);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_about) {
                showAbout();
                return true;
            }
            if (item.getItemId() == R.id.action_permissions) {
                showPermissions();
                return true;
            }
            return false;
        });

        com.google.android.material.chip.ChipGroup filterGroup =
                findViewById(R.id.filter_group);
        filterGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            int id = checkedIds.isEmpty() ? R.id.chip_all : checkedIds.get(0);
            if (id == R.id.chip_1) {
                filterDays = 1;
            } else if (id == R.id.chip_3) {
                filterDays = 3;
            } else if (id == R.id.chip_7) {
                filterDays = 7;
            } else {
                filterDays = 0;
            }
            loadMarks();
        });
        findViewById(R.id.delete_all).setOnClickListener(v -> confirmDeleteAll());

        // 标记一写入就刷新列表，不需要手动下拉
        getContentResolver().registerContentObserver(
                MarkProvider.CONTENT_URI, true, markObserver);
    }

    /** 关于：适配情况、未来方向、仓库地址。 */
    private void showAbout() {
        String message = getString(R.string.about_body);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_about)
                .setMessage(message)
                .setPositiveButton(R.string.about_open_repo, (dialog, which) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW,
                                Uri.parse(getString(R.string.repo_url))));
                    } catch (Throwable ignored) {
                    }
                })
                .setNegativeButton(R.string.about_close, null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadMarks();
        MarkQueue.importPending(this);        // 打开界面时重新排一次，防止闹钟被系统清掉
        CleanupScheduler.scheduleNext(this);
    }

    @Override
    protected void onDestroy() {
        getContentResolver().unregisterContentObserver(markObserver);
        super.onDestroy();
        thumbnailExecutor.shutdownNow();
    }

    /** 离开界面时把任务从最近任务里移除，避免被顺手划掉；进程由前台服务维持。 */
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (launchingExternal) {
            launchingExternal = false;
            return;
        }
        finishAndRemoveTask();
    }

    private void hideFromRecents() {
        try {
            android.app.ActivityManager manager =
                    getSystemService(android.app.ActivityManager.class);
            if (manager == null) {
                return;
            }
            for (android.app.ActivityManager.AppTask task : manager.getAppTasks()) {
                task.setExcludeFromRecents(true);
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 权限
    // ------------------------------------------------------------------

    /** 权限二级菜单：文件访问、电池优化、通知状态。 */
    private void showPermissions() {
        boolean storage = allFilesAccess();
        boolean notifications = notificationsEnabled();
        String message = getString(R.string.permissions_body,
                getString(storage ? R.string.status_enabled : R.string.status_disabled),
                getString(notifications ? R.string.status_enabled : R.string.status_disabled));
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_permissions)
                .setMessage(message)
                // 按钮顺序（左→右）：所有文件、电池优化、关闭
                .setNegativeButton(R.string.permission_storage,
                        (dialog, which) -> openAllFilesSettings())
                .setNeutralButton(R.string.action_battery,
                        (dialog, which) -> openBatterySettings())
                .setPositiveButton(R.string.about_close, null)
                .show();
    }

    private boolean notificationsEnabled() {
        android.app.NotificationManager manager =
                getSystemService(android.app.NotificationManager.class);
        return manager != null && manager.areNotificationsEnabled();
    }

    private boolean allFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void openBatterySettings() {
        // ColorOS 对 ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 支持不稳定，逐级降级
        launchingExternal = true;
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
            return;
        } catch (Throwable ignored) {
        }
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            return;
        } catch (Throwable ignored) {
        }
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            launchingExternal = false;
            Toast.makeText(this, "打不开电池优化设置", Toast.LENGTH_SHORT).show();
        }
    }

    private void openAllFilesSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            launchingExternal = true;
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            try {
                startActivity(intent);
            } catch (Throwable t) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Throwable ignored) {
                    launchingExternal = false;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 已标记列表
    // ------------------------------------------------------------------

    private void loadMarks() {
        markList.removeAllViews();
        allRows.clear();
        try (Cursor cursor = getContentResolver().query(
                MarkProvider.CONTENT_URI, PROJECTION, null, null, null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    MarkRow row = new MarkRow();
                    row.id = cursor.getLong(0);
                    row.uri = cursor.getString(1);
                    row.dueAt = cursor.getLong(2);
                    row.days = cursor.getInt(3);
                    row.displayName = cursor.isNull(4) ? null : cursor.getString(4);
                    row.dateTaken = cursor.getLong(5);
                    allRows.add(row);
                }
            }
        } catch (Throwable t) {
            Toast.makeText(this, "读取标记失败：" + t, Toast.LENGTH_LONG).show();
        }

        // 图片已被外部彻底删除（无法预览）时，移除失效标记
        int pruned = 0;
        java.util.Iterator<MarkRow> iterator = allRows.iterator();
        while (iterator.hasNext()) {
            MarkRow row = iterator.next();
            Boolean present = MarkCleanup.isPresent(this, Uri.parse(row.uri));
            if (Boolean.FALSE.equals(present)) {
                MarkCleanup.removeMark(this, row.id);
                iterator.remove();
                pruned++;
            }
        }
        if (pruned > 0) {
            Toast.makeText(this, getString(R.string.toast_pruned, pruned), Toast.LENGTH_SHORT).show();
        }

        List<MarkRow> visible = filteredRows();
        List<String> visibleUris = new ArrayList<>();
        for (MarkRow row : visible) {
            visibleUris.add(row.uri);
        }
        for (int i = 0; i < visible.size(); i++) {
            addMarkRow(visible.get(i), i, visibleUris);
        }
        markEmpty.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private List<MarkRow> filteredRows() {
        if (filterDays == 0) {
            return new ArrayList<>(allRows);
        }
        List<MarkRow> result = new ArrayList<>();
        for (MarkRow row : allRows) {
            if (row.days == filterDays) {
                result.add(row);
            }
        }
        return result;
    }

    /** 删除当前分组：只有一次确认，删的就是屏幕上这一组。 */
    private void confirmDeleteAll() {
        final List<MarkRow> target = filteredRows();
        if (target.isEmpty()) {
            Toast.makeText(this, R.string.marks_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_confirm_title)
                .setMessage(getString(R.string.delete_confirm_message, target.size()))
                .setPositiveButton(R.string.action_delete, (dialog, which) -> deleteRows(target))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void deleteRows(List<MarkRow> target) {
        Toast.makeText(this, "正在删除…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            int deleted = 0;
            for (MarkRow row : target) {
                int outcome = MarkCleanup.deleteImage(this, Uri.parse(row.uri), row.dateTaken);
                if (outcome == MarkCleanup.DELETE_OK) {
                    MarkCleanup.removeMark(this, row.id);
                    deleted++;
                }
            }
            final int count = deleted;
            runOnUiThread(() -> {
                Toast.makeText(this, getString(R.string.toast_deleted, count),
                        Toast.LENGTH_SHORT).show();
                loadMarks();
                CleanupScheduler.scheduleNext(this);
            });
        }, "delete-marks").start();
    }

    private void addMarkRow(MarkRow data, int index, List<String> allUris) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_mark, markList, false);
        TextView name = row.findViewById(R.id.name);
        TextView due = row.findViewById(R.id.due);
        ImageView thumb = row.findViewById(R.id.thumb);

        Uri uri = Uri.parse(data.uri);
        name.setText(resolveDisplayName(data, uri));
        due.setText(dueFormat.format(new Date(data.dueAt)) + " · " + data.days + " 天");

        View.OnClickListener open = v -> openViewer(allUris, index);
        row.setOnClickListener(open);
        name.setOnClickListener(open);
        thumb.setOnClickListener(open);

        row.findViewById(R.id.cancel).setOnClickListener(v -> cancelMark(data.id));
        markList.addView(row);

        thumbnailExecutor.execute(() -> {
            Bitmap bitmap = loadThumbnail(uri);
            if (bitmap != null) {
                runOnUiThread(() -> thumb.setImageBitmap(bitmap));
            }
        });
    }

    private String resolveDisplayName(MarkRow row, Uri uri) {
        if (row.displayName != null && !row.displayName.isEmpty()) {
            return row.displayName;
        }
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{android.provider.MediaStore.Images.Media.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (Throwable ignored) {
        }
        return row.uri;
    }

    private void openViewer(List<String> uris, int index) {
        Intent intent = new Intent(this, ViewerActivity.class);
        intent.putExtra(ViewerActivity.EXTRA_URIS, uris.toArray(new String[0]));
        intent.putExtra(ViewerActivity.EXTRA_INDEX, index);
        startActivity(intent);
    }

    private static final class MarkRow {
        long id;
        String uri;
        long dueAt;
        int days;
        String displayName;
        long dateTaken;
    }

    private Bitmap loadThumbnail(Uri uri) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return getContentResolver().loadThumbnail(uri, new Size(160, 160), null);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void cancelMark(long id) {
        try {
            Uri target = ContentUris.withAppendedId(MarkProvider.CONTENT_URI, id);
            getContentResolver().delete(target, null, null);
            Toast.makeText(this, R.string.toast_canceled, Toast.LENGTH_SHORT).show();
            loadMarks();
        } catch (Throwable t) {
            Toast.makeText(this, "取消失败：" + t, Toast.LENGTH_LONG).show();
        }
    }
}
