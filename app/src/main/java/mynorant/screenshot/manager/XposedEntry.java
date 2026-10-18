package mynorant.screenshot.manager;

import android.animation.ObjectAnimator;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.provider.MediaStore;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.DragEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * 截屏管理器 —— LibXposed API 102 测试模块。
 *
 * <p>Step 1（已验证）：只挂 hook、只打日志，确认模块能加载、类能定位、各 Hook 点的调用时序。</p>
 * <p>Step 2（当前）：在 {@code FloatWindowWidget#getAlphaAnimator} 的 after 阶段插入一个
 * 原生 {@code FloatButton}，并把自己的 alpha 动画追加进同一个 {@code animators} 列表，
 * 让它与系统按钮同帧出现；点击只弹 Toast。不改目标应用的任何参数与返回值。</p>
 *
 * <p>入口由 {@code META-INF/xposed/java_init.list} 声明（Vector 的 MODERN 加载路径，
 * 需要 {@code META-INF/xposed/module.prop} 里 {@code targetApiVersion >= 101}）。</p>
 */
public final class XposedEntry extends XposedModule {

    private static final String TAG = "ScreenshotManager";
    private static final String TARGET_PACKAGE = "com.oplus.screenshot";

    // 目标类（实测：com.oplus.screenshot 16.16.2 / ColorOS 16 / Android 16）
    private static final String CLS_REQUEST = "com.oplus.screenshot.common.core.ScreenshotRequest";
    private static final String CLS_LAYER = "com.oplus.screenshot.ui.widget.floating.FloatWindowLayer";
    private static final String CLS_WIDGET = "com.oplus.screenshot.ui.widget.floating.FloatWindowWidget";
    private static final String CLS_BUTTON = "com.oplus.screenshot.ui.drag.FloatButton";
    // 注意：是 screenshot.screenshot.ui，不是 ui.ScreenshotUI
    private static final String CLS_UI = "com.oplus.screenshot.screenshot.ui.ScreenshotUI";

    private static final String T_REQUEST = "com.oplus.screenshot.common.core.ScreenshotRequest";
    /** 标记落到本 App 的 Provider。 */
    private static final Uri MARK_URI =
            Uri.parse("content://mynorant.screenshot.manager.marks/marks");

    /** Step 2 开关：关掉就退回 Step 1 的纯日志行为。 */
    private static final boolean INSERT_BUTTON = true;
    private static final String BUTTON_TEXT = "标记";
    /** 构建标记：每次改代码改一下，用来确认设备上跑的是不是新版本。 */
    private static final String BUILD_TAG = "build-11";

    /**
     * 幂等：同一张截图 {@code getAlphaAnimator} 会被调用多次，不加记录会插出多个按钮。
     * key 用弱引用，避免长期持有 Activity/窗口上下文。
     */
    private static final Map<View, WeakReference<View>> INJECTED = new WeakHashMap<>();

    /** 点击「标记」之后、文件落盘之前，先按请求实例挂起；v0() 到达时再补 URI。 */
    private static final Map<Object, Integer> PENDING =
            Collections.synchronizedMap(new WeakHashMap<>());
    /** 等待保存完成时对应的悬浮条，用来在写入后收起它。 */
    private static volatile View PENDING_WIDGET;
    /** 最近一次 setRequest 的请求，作为取不到 Layer 字段时的兜底。 */
    private static volatile Object lastRequest;
    /** 标记模式：拦住宿主把按钮文案改回「长截屏 / 分享」。 */
    private static volatile boolean MARK_MODE;
    /** 宿主进程的 Application Context，供保存回调里写 Provider 用。 */
    private static volatile Context hostContext;

    private volatile String process = "?";
    private volatile boolean installed;

    public XposedEntry() {
        // 框架通过无参构造反射实例化入口类
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        process = param.getProcessName();
        String framework;
        try {
            framework = getFrameworkName() + " " + getFrameworkVersion()
                    + " (code " + getFrameworkVersionCode() + ")";
        } catch (Throwable t) {
            framework = "<unavailable: " + t + ">";
        }
        info("══ onModuleLoaded  process=" + process + "  api=" + safeApi()
                + "  framework=" + framework + "  build=" + BUILD_TAG);
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        info("onPackageLoaded  pkg=" + param.getPackageName()
                + "  first=" + param.isFirstPackage()
                + "  defaultCl=" + param.getDefaultClassLoader());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        info("onPackageReady   pkg=" + param.getPackageName()
                + "  cl=" + param.getClassLoader());
        if (installed) {
            info("hooks 已安装，跳过重复安装");
            return;
        }
        installed = true;
        installHooks(param.getClassLoader());
    }

    // ------------------------------------------------------------------
    // 安装 hook
    // ------------------------------------------------------------------

    private void installHooks(ClassLoader cl) {
        long start = SystemClock.uptimeMillis();
        int ok = 0;
        if (hookCaptureReady(cl)) ok++;
        if (hookSetRequest(cl)) ok++;
        if (hookAlphaAnimator(cl)) ok++;
        if (hookUiShow(cl)) ok++;
        if (hookDragExited(cl)) ok++;
        if (hookRefreshSave(cl)) ok++;
        if (hookUpdateText(cl)) ok++;
        info("── installHooks: " + ok + "/7 成功，耗时 "
                + (SystemClock.uptimeMillis() - start) + " ms");
    }

    /** ScreenshotRequest#c(Bitmap)Z —— 位图就绪（最早、覆盖全部触发方式）。 */
    private boolean hookCaptureReady(ClassLoader cl) {
        return hook(cl, CLS_REQUEST, "c", new String[]{"android.graphics.Bitmap"},
                "ScreenshotRequest#c(Bitmap)Z", chain -> {
                    // 新一次截图开始：清掉上一张可能残留的标记模式
                    MARK_MODE = false;
                    Object self = chain.getThisObject();
                    Bitmap bitmap = (Bitmap) chain.getArg(0);
                    info("capture>  bitmap=" + describe(bitmap)
                            + "  trigger=" + str(call(self, "V"))
                            + "  startTime=" + str(call(self, "W")));
                    Object result = chain.proceed();
                    info("capture<  result=" + result + "  bitmap=" + describe(bitmap));
                    return result;
                });
    }

    /** FloatWindowLayer#setRequest(ScreenshotRequest, boolean) —— 悬浮层拿到请求。 */
    private boolean hookSetRequest(ClassLoader cl) {
        return hook(cl, CLS_LAYER, "setRequest", new String[]{T_REQUEST, "boolean"},
                "FloatWindowLayer#setRequest", chain -> {
                    Object self = chain.getThisObject();
                    Object request = chain.getArg(0);
                    lastRequest = request;
                    info("setRequest>  request=" + id(request)
                            + "  trigger=" + str(call(request, "V"))
                            + "  bitmap=" + describe(call(request, "k"))
                            + "  arg1=" + chain.getArg(1)
                            + "  field.before=" + id(field(self, "screenshotRequest")));
                    Object result = chain.proceed();
                    info("setRequest<  field.after=" + id(field(self, "screenshotRequest"))
                            + "  bitmapsEqual=" + (field(self, "screenshotRequest") == request));
                    return result;
                });
    }

    /**
     * FloatWindowWidget#getAlphaAnimator(List, boolean, boolean) —— 悬浮按钮条动画。
     * 这里往 animators 里追一条动画，等于和系统按钮共用同一个 AnimatorSet、同一帧上屏。
     */
    private boolean hookAlphaAnimator(ClassLoader cl) {
        return hook(cl, CLS_WIDGET, "getAlphaAnimator",
                new String[]{"java.util.List", "boolean", "boolean"},
                "FloatWindowWidget#getAlphaAnimator", chain -> {
                    Object self = chain.getThisObject();
                    List<?> animators = chain.getArg(0) instanceof List ? (List<?>) chain.getArg(0) : null;
                    boolean enter = Boolean.TRUE.equals(chain.getArg(1));
                    boolean extra = Boolean.TRUE.equals(chain.getArg(2));
                    info("alphaAnim>  enter=" + enter + "  extra=" + extra
                            + "  children=" + childCount(self)
                            + "  animators=" + (animators == null ? -1 : animators.size())
                            + "  kids=[" + kidNames(self) + "]");
                    Object result = chain.proceed();
                    if (INSERT_BUTTON) {
                        try {
                            ensureButton(self, animators, enter, extra);
                        } catch (Throwable t) {
                            error("插按钮失败", t);
                        }
                    }
                    info("alphaAnim<  children=" + childCount(self)
                            + "  animators=" + (animators == null ? -1 : animators.size())
                            + "  kids=[" + kidNames(self) + "]");
                    return result;
                });
    }

    /** ScreenshotUI#show(ScreenshotRequest) —— 某个具体 UI（缩略图/编辑器…）开始显示。 */
    private boolean hookUiShow(ClassLoader cl) {
        return hook(cl, CLS_UI, "show", new String[]{T_REQUEST},
                "ScreenshotUI#show", chain -> {
                    Object self = chain.getThisObject();
                    Object request = chain.getArg(0);
                    info("UI.show>  impl=" + shortName(self)
                            + "  request=" + id(request)
                            + "  trigger=" + str(call(request, "V")));
                    Object result = chain.proceed();
                    info("UI.show<  impl=" + shortName(self));
                    return result;
                });
    }

    /** FloatWindowLayer#actionDragExited(DragEvent)Z —— 缩略图被拖出丢弃。 */    private boolean hookDragExited(ClassLoader cl) {
        return hook(cl, CLS_LAYER, "actionDragExited",
                new String[]{"android.view.DragEvent"},
                "FloatWindowLayer#actionDragExited", chain -> {
                    Object event = chain.getArg(0);
                    String action = event instanceof DragEvent
                            ? String.valueOf(((DragEvent) event).getAction()) : "?";
                    info("dragExited>  action=" + action);
                    Object result = chain.proceed();
                    info("dragExited<  result=" + result);
                    return result;
                });
    }

    // ------------------------------------------------------------------
    // Step 3：保存结果（只读）
    // ------------------------------------------------------------------

    /**
     * ScreenshotRequest#v0() —— refreshSave：保存完成后把 Uri 刷进请求对象。
     * 这里只读不写，用来确认「点标记时能不能拿到文件路径」以及相对点击的时机。
     */
    private boolean hookRefreshSave(ClassLoader cl) {
        return hook(cl, CLS_REQUEST, "v0", new String[]{},
                "ScreenshotRequest#v0(refreshSave)", chain -> {
                    Object result = chain.proceed();
                    Object request = chain.getThisObject();
                    Object captureInfo = field(request, "f");
                    Object uri = call(captureInfo, "i");
                    Integer pendingDays = PENDING.remove(request);
                    View pendingWidget = PENDING_WIDGET;
                    PENDING_WIDGET = null;
                    info("save>  uri=" + uri + "  pending=" + pendingDays);
                    if (pendingDays != null) {
                        Context context = hostContext;
                        if (uri instanceof Uri && context != null) {
                            commitMark(context, pendingWidget, (Uri) uri,
                                    captureTimeOf(request), pendingDays);
                        } else {
                            info("mark>  保存完成但拿不到 uri/context，丢弃挂起");
                        }
                    }
                    return result;
                });
    }

    /** FloatButton#updateText(int) —— 标记模式下拦住宿主把文案改回原样。 */
    private boolean hookUpdateText(ClassLoader cl) {
        return hook(cl, CLS_BUTTON, "updateText", new String[]{"int"},
                "FloatButton#updateText", chain -> {
                    if (MARK_MODE) {
                        return null;
                    }
                    return chain.proceed();
                });
    }

    // ------------------------------------------------------------------
    // Step 2：插入按钮
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void ensureButton(Object widget, List<?> animators, boolean enter, boolean extra) {
        if (!(widget instanceof ViewGroup) || animators == null) {
            return;
        }
        ViewGroup parent = (ViewGroup) widget;
        View button = lookup(parent);

        if (button == null) {
            if (!enter) {
                // 退场阶段没插过就没必要补；已经插过则继续走下面的淡出
                return;
            }
            button = createButton(parent);
            if (button == null) {
                return;
            }
            INJECTED.put(parent, new WeakReference<>(button));
        }

        float from = enter ? 0f : 1f;
        float to = enter ? 1f : 0f;
        button.setAlpha(enter ? 0f : button.getAlpha());

        ObjectAnimator animator = ObjectAnimator.ofFloat(button, "alpha", from, to);
        animator.setDuration(duration(parent, enter));
        Interpolator interpolator = interpolator(parent, enter);
        if (interpolator != null) {
            animator.setInterpolator(interpolator);
        }
        if (enter && !extra) {
            animator.setStartDelay(delay(parent));
        }
        ((List<Object>) animators).add(animator);

        info("btn>  " + (enter ? "enter" : "exit")
                + "  alpha=" + from + "->" + to
                + "  duration=" + animator.getDuration()
                + "  delay=" + animator.getStartDelay()
                + "  animators=" + animators.size());
    }

    private View createButton(ViewGroup parent) {
        try {
            hostContext = parent.getContext().getApplicationContext();
            ClassLoader cl = parent.getClass().getClassLoader();
            Class<?> buttonClass = Class.forName(CLS_BUTTON, true, cl);
            Object instance = buttonClass.getConstructor(Context.class)
                    .newInstance(parent.getContext());
            if (!(instance instanceof View)) {
                error("FloatButton 构造结果不是 View", null);
                return null;
            }
            View button = (View) instance;

            // 套用原生尺寸（原生在构造后调用一次）
            try {
                buttonClass.getMethod("setBtnSize").invoke(button);
            } catch (Throwable t) {
                info("! setBtnSize 调用失败：" + t);
            }

            View reference = findReferenceButton(parent, buttonClass);
            if (reference != null) {
                copyStyle(reference, button);
                // 尺寸也对齐原生按钮：原生是固定宽度，只复制文字/内边距会窄一圈
                if (reference.getWidth() > 0) {
                    button.setMinimumWidth(reference.getWidth());
                }
                if (reference.getHeight() > 0) {
                    button.setMinimumHeight(reference.getHeight());
                }
            }

            if (button instanceof TextView) {
                ((TextView) button).setText(BUTTON_TEXT);
            }
            button.setContentDescription(BUTTON_TEXT);
            button.setClickable(true);
            button.setFocusable(false);
            button.setOnClickListener(v -> {
                info("btn>  clicked");
                Object request = currentRequest(v);
                info("btn>  request=" + id(request));
                enterMarkMode((ViewGroup) v.getParent(), request);
            });

            int index = reference != null ? parent.indexOfChild(reference) : parent.getChildCount();
            ViewGroup.LayoutParams params = reference != null && reference.getLayoutParams() != null
                    ? new LinearLayout.LayoutParams(reference.getLayoutParams())
                    : new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            button.setAlpha(0f);
            parent.addView(button, index, params);
            info("btn>  inserted  index=" + index + "  total=" + parent.getChildCount()
                    + "  ref=" + shortName(reference) + "  textSize=" + textSizeOf(button));
            return button;
        } catch (Throwable t) {
            error("创建 FloatButton 失败", t);
            return null;
        }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * 让「模块本体」弹 Toast。
     *
     * <p>宿主进程自己调 Toast 会被它关闭的通知权限拦掉（NotificationService:
     * Suppressing toast by user request）。这里改为显式广播交给我们的 App：
     * Toast 的归属变成 {@code mynorant.screenshot.manager}，位置由系统统一放在底部，
     * 也不会再盖住缩略图。</p>
     */
    private void toast(Context anchorContext, String message) {
        Context context = anchorContext.getApplicationContext();
        if (context == null) {
            context = anchorContext;
        }
        try {
            Intent intent = new Intent(MarkReceiver.ACTION_TOAST);
            intent.setComponent(new ComponentName("mynorant.screenshot.manager",
                    "mynorant.screenshot.manager.MarkReceiver"));
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            intent.putExtra(MarkReceiver.EXTRA_TEXT, message);
            context.sendBroadcast(intent);
            info("toast>  sent: " + message);
        } catch (Throwable t) {
            error("toast>  发送失败", t);
        }
    }

    // ------------------------------------------------------------------
    // 标记模式：把悬浮条上的按钮原地替换为天数选项
    // ------------------------------------------------------------------

    /** 可选天数；0 表示 1 分钟（临时测试用）。 */
    private static final int[] DAY_OPTIONS = {1, 3, 7};

    /**
     * 点「标记」后，把悬浮条上现有的按钮原地改成「1 天后 / 3 天后 / 7 天后」。
     *
     * <p>不新建窗口：这样按钮天然复用原有的入场/退场动画，缩略图被划走时一起消失，
     * 也就等于取消标记。多出来的按钮（分享）暂时留空并隐藏。</p>
     */
    private void enterMarkMode(ViewGroup widget, Object request) {
        try {
            MARK_MODE = true;
            List<TextView> buttons = new ArrayList<>();
            for (int i = 0; i < widget.getChildCount(); i++) {
                View child = widget.getChildAt(i);
                if (child instanceof TextView && child.getVisibility() == View.VISIBLE) {
                    buttons.add((TextView) child);
                }
            }
            if (buttons.size() < 2) {
                toast(widget.getContext(), "按钮数量不足");
                return;
            }
            int converted = 0;
            for (TextView button : buttons) {
                if (converted < DAY_OPTIONS.length) {
                    final int days = DAY_OPTIONS[converted];
                    button.setText(days + " 天后");
                    button.setOnClickListener(v -> onPickDays(widget.getContext(), widget, request, days));
                    converted++;
                } else {
                    // 还没有其它功能，这个位置先空出来
                    button.setText("");
                    button.setEnabled(false);
                    button.setClickable(false);
                    button.setVisibility(View.INVISIBLE);
                }
            }
            info("mark>  mode on  buttons=" + buttons.size() + "  request=" + id(request));
        } catch (Throwable t) {
            error("mark>  进入标记模式失败", t);
        }
    }

    private void onPickDays(Context context, View widget, Object request, int days) {
        if (request == null) {
            toast(context, "拿不到这次截图的请求");
            return;
        }
        Object captureInfo = field(request, "f");
        Object uri = call(captureInfo, "i");
        if (uri instanceof Uri) {
            commitMark(context, widget, (Uri) uri, captureTimeOf(request), days);
        } else {
            PENDING.put(request, days);
            PENDING_WIDGET = widget;
            info("mark>  pending  days=" + days + "  request=" + id(request));
        }
    }

    /** 标记完成后收起悬浮条：复用宿主自己的退场动画。 */
    private void dismissFloating(View widget) {
        MARK_MODE = false;
        try {
            Object layer = widget != null ? widget.getParent() : null;
            if (layer == null) {
                return;
            }
            Method method = layer.getClass().getMethod("startDismiss");
            method.invoke(layer);
            info("mark>  dismiss 已调用");
        } catch (Throwable t) {
            info("mark>  dismiss 调用失败: " + t);
        }
    }
    /** 写入标记：优先 ContentProvider（宿主有 QUERY_ALL_PACKAGES），失败再退回显式广播。 */
    private void commitMark(Context context, View widget, Uri uri, long dateTaken, int days) {
        long due = dueAt(dateTaken, days);
        boolean written = false;
        try {
            ContentValues values = new ContentValues();
            values.put("image_uri", uri.toString());
            values.put("date_taken", dateTaken);
            values.put("days", days);
            values.put("due_at", due);
            values.put("display_name", displayNameOf(context, uri));
            Uri row = context.getContentResolver().insert(MARK_URI, values);
            written = row != null;
            info("mark>  provider insert  row=" + row + "  uri=" + uri
                    + "  days=" + days + "  dueAt=" + formatTime(due));
        } catch (Throwable t) {
            info("mark>  provider 失败，改走广播: " + t);
        }
        if (!written) {
            try {
                Intent intent = new Intent(MarkReceiver.ACTION);
                intent.setComponent(new ComponentName("mynorant.screenshot.manager",
                        "mynorant.screenshot.manager.MarkReceiver"));
                intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                intent.putExtra(MarkReceiver.EXTRA_URI, uri.toString());
                intent.putExtra(MarkReceiver.EXTRA_DATE_TAKEN, dateTaken);
                intent.putExtra(MarkReceiver.EXTRA_DAYS, days);
                intent.putExtra(MarkReceiver.EXTRA_DUE_AT, due);
                intent.putExtra(MarkReceiver.EXTRA_NAME, displayNameOf(context, uri));
                context.sendBroadcast(intent);
                info("mark>  broadcast sent  uri=" + uri + "  days=" + days);
            } catch (Throwable t) {
                error("mark>  发送标记失败", t);
                toast(context, "标记失败，见日志");
                return;
            }
        }
        // Toast 由我们的 App 在 Provider/Receiver 里弹，避免受宿主进程限制
        dismissFloating(widget);
    }

    /** 取 MediaStore 里的原始文件名。 */
    private static String displayNameOf(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{MediaStore.Images.Media.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 创建时间 + days×24h 之后的第一个 0 点。 */
    private static long dueAt(long dateTaken, int days) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(dateTaken + days * 24L * 60L * 60L * 1000L);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        calendar.add(Calendar.DAY_OF_MONTH, 1);
        return calendar.getTimeInMillis();
    }

    private static long captureTimeOf(Object request) {
        try {
            Object info = field(request, "f");
            Object time = call(info, "f");
            Object taken = field(time, "dateTaken");
            if (taken instanceof Number && ((Number) taken).longValue() > 0) {
                return ((Number) taken).longValue();
            }
        } catch (Throwable ignored) {
        }
        return System.currentTimeMillis();
    }

    private static String formatTime(long millis) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(millis));
    }

    /** 优先从 FloatWindowLayer.screenshotRequest 取，兜底用最近一次 setRequest。 */
    private static Object currentRequest(View button) {
        try {
            Object parent = button.getParent();
            if (parent instanceof View) {
                Object layer = ((View) parent).getParent();
                Object request = field(layer, "screenshotRequest");
                if (request != null) {
                    return request;
                }
            }
        } catch (Throwable ignored) {
        }
        return lastRequest;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /**
     * 逐个复制原生按钮的视觉属性。少复制任何一项都会出现「隐形」或「错位」，
     * 尤其是 background（不复制就是透明）与 textColors（不是 int，而是 ColorStateList）。
     */
    private static void copyStyle(View reference, View button) {
        try {
            Drawable background = reference.getBackground();
            if (background != null && background.getConstantState() != null) {
                button.setBackground(background.getConstantState().newDrawable());
            }
        } catch (Throwable ignored) {
        }
        if (!(reference instanceof TextView) || !(button instanceof TextView)) {
            return;
        }
        TextView ref = (TextView) reference;
        TextView target = (TextView) button;
        try {
            target.setTextColor(ref.getTextColors());
        } catch (Throwable ignored) {
        }
        try {
            target.setTextSize(TypedValue.COMPLEX_UNIT_PX, ref.getTextSize());
        } catch (Throwable ignored) {
        }
        try {
            target.setGravity(ref.getGravity());
        } catch (Throwable ignored) {
        }
        try {
            target.setPadding(ref.getPaddingLeft(), ref.getPaddingTop(),
                    ref.getPaddingRight(), ref.getPaddingBottom());
        } catch (Throwable ignored) {
        }
        try {
            target.setMinimumHeight(ref.getMinimumHeight());
            target.setMinWidth(ref.getMinWidth());
        } catch (Throwable ignored) {
        }
    }

    private static View findReferenceButton(ViewGroup parent, Class<?> buttonClass) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (buttonClass.isInstance(child) && child != lookup(parent)) {
                return child;
            }
        }
        return null;
    }

    private static View lookup(ViewGroup parent) {
        WeakReference<View> reference = INJECTED.get(parent);
        View view = reference == null ? null : reference.get();
        if (view != null && view.getParent() == parent) {
            return view;
        }
        return null;
    }

    private static long duration(Object widget, boolean enter) {
        Object value = field(widget, enter ? "enterDuration" : "exitDuration");
        return value instanceof Number ? ((Number) value).longValue() : 300L;
    }

    private static long delay(Object widget) {
        Object value = field(widget, "enterDelay");
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static Interpolator interpolator(Object widget, boolean enter) {
        Object value = field(widget, enter ? "alphaEnterInterpolator" : "alphaExitInterpolator");
        return value instanceof Interpolator ? (Interpolator) value : null;
    }

    private static String textSizeOf(View view) {
        return view instanceof TextView ? String.valueOf(((TextView) view).getTextSize()) : "?";
    }

    // ------------------------------------------------------------------
    // hook 基础设施
    // ------------------------------------------------------------------

    private boolean hook(ClassLoader cl, String className, String methodName,
                         String[] paramTypeNames, String label,
                         io.github.libxposed.api.XposedInterface.Hooker hooker) {
        try {
            Class<?> clazz = Class.forName(className, false, cl);
            Method method = findMethod(clazz, methodName, paramTypeNames);

            // 短名（c/t/U/V…）随版本变化时的兜底：仅按「参数表」唯一定位
            if (method == null) {
                Method fallback = findUniqueBySignature(clazz, paramTypeNames);
                if (fallback != null) {
                    info("! " + label + " 短名变化，按签名回退命中 "
                            + clazz.getSimpleName() + "#" + fallback.getName());
                    method = fallback;
                }
            }

            if (method == null) {
                error("hook 失败  " + label + "  " + className + "#" + methodName
                        + Arrays.toString(paramTypeNames) + " 未找到；同类候选="
                        + candidateNames(clazz, paramTypeNames.length), null);
                return false;
            }

            method.setAccessible(true);
            hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(hooker);
            info("hooked  " + label + "  ->  " + method);
            return true;
        } catch (Throwable t) {
            error("hook 失败  " + label + "  " + className + "#" + methodName, t);
            return false;
        }
    }

    private static Method findMethod(Class<?> clazz, String name, String[] paramTypeNames) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.isSynthetic() || m.isBridge()) continue;
            if (!m.getName().equals(name)) continue;
            if (paramsMatch(m, paramTypeNames)) return m;
        }
        return null;
    }

    private static Method findUniqueBySignature(Class<?> clazz, String[] paramTypeNames) {
        Method found = null;
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.isSynthetic() || m.isBridge()) continue;
            if (!paramsMatch(m, paramTypeNames)) continue;
            if (found != null) return null; // 有歧义就不猜
            found = m;
        }
        return found;
    }

    private static boolean paramsMatch(Method m, String[] names) {
        Class<?>[] types = m.getParameterTypes();
        if (types.length != names.length) return false;
        for (int i = 0; i < types.length; i++) {
            if (!types[i].getName().equals(names[i])) return false;
        }
        return true;
    }

    private static String candidateNames(Class<?> clazz, int paramCount) {
        StringBuilder sb = new StringBuilder();
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getParameterCount() != paramCount) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(m.getName());
        }
        return sb.length() == 0 ? "<无>" : sb.toString();
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private void info(String message) {
        log(Log.INFO, TAG, "[" + process + "] " + message);
    }

    private void error(String message, Throwable t) {
        log(Log.ERROR, TAG, "[" + process + "] " + message, t);
    }

    private int safeApi() {
        try {
            return getApiVersion();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static Object call(Object target, String methodName) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(methodName);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable ignored) {
        }
        try {
            Method m = target.getClass().getDeclaredMethod(methodName);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object field(Object target, String fieldName) {
        if (target == null) return null;
        try {
            Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? "null" : String.valueOf(o);
    }

    private static String id(Object o) {
        return o == null ? "null" : "0x" + Integer.toHexString(System.identityHashCode(o));
    }

    private static String shortName(Object o) {
        return o == null ? "null" : o.getClass().getSimpleName();
    }

    private static String describe(Object o) {
        if (!(o instanceof Bitmap)) return str(o);
        Bitmap b = (Bitmap) o;
        try {
            return b.getWidth() + "x" + b.getHeight() + "@" + b.getConfig()
                    + "/" + b.getByteCount() + "B";
        } catch (Throwable t) {
            return "<bitmap " + id(b) + " 已回收>";
        }
    }

    private static int childCount(Object view) {
        return view instanceof ViewGroup ? ((ViewGroup) view).getChildCount() : -1;
    }

    private static String kidNames(Object view) {
        if (!(view instanceof ViewGroup)) return "";
        ViewGroup group = (ViewGroup) view;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (sb.length() > 0) sb.append(',');
            sb.append(i).append(':').append(shortName(child))
                    .append('(').append(child.getVisibility()).append(')');
        }
        return sb.toString();
    }
}
