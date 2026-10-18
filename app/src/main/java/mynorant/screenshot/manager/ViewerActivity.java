package mynorant.screenshot.manager;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 全屏查看截图，左右滑动切换上一张 / 下一张。 */
public final class ViewerActivity extends AppCompatActivity {

    public static final String EXTRA_URIS = "uris";
    public static final String EXTRA_INDEX = "index";

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ViewPager2 pager;
    private TextView counter;
    private List<Uri> uris = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewer);
        pager = findViewById(R.id.pager);
        counter = findViewById(R.id.counter);

        String[] raw = getIntent().getStringArrayExtra(EXTRA_URIS);
        if (raw != null) {
            for (String text : raw) {
                uris.add(Uri.parse(text));
            }
        }
        if (uris.isEmpty()) {
            finish();
            return;
        }
        int index = Math.max(0, Math.min(getIntent().getIntExtra(EXTRA_INDEX, 0), uris.size() - 1));

        pager.setAdapter(new ImageAdapter());
        pager.setCurrentItem(index, false);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateCounter(position);
            }
        });
        updateCounter(index);
    }

    private void updateCounter(int position) {
        counter.setText((position + 1) + " / " + uris.size());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private final class ImageAdapter extends RecyclerView.Adapter<ImageHolder> {

        @NonNull
        @Override
        public ImageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ImageView image = new ImageView(parent.getContext());
            image.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setBackgroundColor(0xFF000000);
            return new ImageHolder(image);
        }

        @Override
        public void onBindViewHolder(@NonNull ImageHolder holder, int position) {
            Uri uri = uris.get(position);
            holder.image.setTag(uri);
            holder.image.setImageDrawable(null);
            executor.execute(() -> {
                Bitmap bitmap = load(uri);
                mainHandler.post(() -> {
                    if (bitmap != null && uri.equals(holder.image.getTag())) {
                        holder.image.setImageBitmap(bitmap);
                    }
                });
            });
        }

        @Override
        public int getItemCount() {
            return uris.size();
        }
    }

    /**
     * 直接解码原图（按屏幕尺寸做采样，避免 OOM），不是系统缩略图。
     * API 28+ 用 ImageDecoder，能自动套用 EXIF 旋转。
     */
    private Bitmap load(Uri uri) {
        int maxWidth = getResources().getDisplayMetrics().widthPixels * 2;
        int maxHeight = getResources().getDisplayMetrics().heightPixels * 2;
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                android.graphics.ImageDecoder.Source source =
                        android.graphics.ImageDecoder.createSource(getContentResolver(), uri);
                return android.graphics.ImageDecoder.decodeBitmap(source, (decoder, info, s) -> {
                    int width = info.getSize().getWidth();
                    int height = info.getSize().getHeight();
                    int sample = 1;
                    while (width / sample > maxWidth || height / sample > maxHeight) {
                        sample *= 2;
                    }
                    decoder.setTargetSampleSize(sample);
                });
            }
            android.graphics.BitmapFactory.Options bounds =
                    new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                android.graphics.BitmapFactory.decodeStream(in, null, bounds);
            }
            int sample = 1;
            while (bounds.outWidth / sample > maxWidth || bounds.outHeight / sample > maxHeight) {
                sample *= 2;
            }
            android.graphics.BitmapFactory.Options options =
                    new android.graphics.BitmapFactory.Options();
            options.inSampleSize = sample;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                return android.graphics.BitmapFactory.decodeStream(in, null, options);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static final class ImageHolder extends RecyclerView.ViewHolder {
        final ImageView image;

        ImageHolder(ImageView image) {
            super(image);
            this.image = image;
            image.setForegroundGravity(Gravity.CENTER);
        }
    }
}
