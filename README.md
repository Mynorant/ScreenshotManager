# 截图管理 / ScreenshotManager

一个给 ColorOS「截屏」(com.oplus.screenshot) 加**倒计时删除**的 Xposed 模块。

截图后，悬浮条上会多出一个「标记」按钮。点它，悬浮条上的按钮会原地变成
「1 天后 / 3 天后 / 7 天后」；选一个，这张截图就会在到期后的第一个 0:00 被自动删除。

> 基于 LibXposed API 102，运行框架为 [Vector](https://github.com/JingMatrix/Vector)。

---

## 功能

- **倒计时删除**：截图时直接标记 1 / 3 / 7 天后删除，到点自动清理，不用手动翻相册。
- **到期时间规则**：`图片创建时间 + N×24 小时` 之后的**第一个 0:00**。
  例如 19 日 13:20 的截图选 3 天，实际删除时间是 23 日 0:00。
- **每天只在 0:00 检查一次**，不做轮询；开机或应用更新后只重排闹钟，不会立刻扫描。
- **标记列表**：主界面能看到已标记的截图（缩略图、真实文件名、到期时间），可点开全屏查看、
  左右滑动切换，也可以随时取消标记。
- **安全删除**：只删除数据库里登记过的那一张，删除前会校验 `DATE_TAKEN`，
  防止 MediaStore 的 `_id` 被复用后误删其他图片。

## 适配情况

| 项目 | 说明 |
|---|---|
| 目标应用 | ColorOS「截屏」`com.oplus.screenshot` **16.16.2**（versionCode 160016002） |
| 系统 | ColorOS 16 / Android 16 |
| 验证设备 | 一加 ACE 5 |
| 其他版本 | **未测试**，类名/方法名或行为可能不同 |
| 其他系统 | **不兼容**，小米/华为/三星等系统自带的截屏应用不适用 |

## 未来方向

- 优先跟进 ColorOS 各版本的「截屏」适配；
- 其他系统 / ROM 尽力而为，不保证。

## 构建

环境：Android Studio 最新版、JDK 17+、Android SDK 37。

```bash
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

模块信息不需要额外配置，APK 内已包含：

```
META-INF/xposed/module.prop      minApiVersion / targetApiVersion / staticScope
META-INF/xposed/java_init.list   入口类
META-INF/xposed/scope.list       作用域 com.oplus.screenshot
```

## 使用

1. 安装 APK，在 Vector 里启用「截图管理」模块，作用域勾选「截屏」。
2. 打开「截图管理」App，授予**所有文件访问权限**（到点删除需要）。
3. 强制停止一次「截屏」应用，让模块注入新进程。
4. 截图后点悬浮条上的「标记」，选择天数即可。

> Toast 提示依赖 App 自身的通知权限；如果收不到提示，检查系统设置里「截图管理」的通知是否被关闭。

## 工作原理

| Hook 点 | 用途 |
|---|---|
| `ScreenshotRequest#c(Bitmap)Z` | 位图就绪，作为每次截图的起点（同时复位标记模式） |
| `FloatWindowLayer#setRequest` | 悬浮层拿到本次请求，记录最近一次 request |
| `FloatWindowWidget#getAlphaAnimator(List,boolean,boolean)` | 往 `animators` 追加动画，把「标记」按钮插进原生按钮条 |
| `FloatButton#updateText(int)` | 标记模式下拦住宿主把按钮文案改回去 |
| `ScreenshotRequest#v0()` | 保存完成，拿到 MediaStore URI 与 `dateTaken` |
| `FloatWindowLayer#actionDragExited` | 缩略图被拖出丢弃（仅日志） |

标记写入通过 `ContentProvider`（宿主持有 `QUERY_ALL_PACKAGES`，可直接解析），
写入后由 Provider 排下一次闹钟并弹出 Toast。

## 项目结构

```
app/src/main/java/mynorant/screenshot/manager/
├── XposedEntry.java      模块入口与全部 hook
├── MarkProvider.java     标记存储（SQLite + ContentProvider）
├── MarkReceiver.java     广播兜底写入
├── MarkCleanup.java      到点删除引擎（含安全校验）
├── CleanupScheduler.java 闹钟调度
├── CleanupReceiver.java  到点执行 / 开机重排
├── MainActivity.java     主界面
├── ViewerActivity.java   全屏查看（可左右滑动）
└── ...
```

## 作者

**半城雨岁辞（Mynorant）、DS、DSH**

## 许可证

本项目使用 [Apache License 2.0](LICENSE)，并附带一份 [NOTICE](NOTICE)。

你可以自由地使用、修改、二次创作甚至商用，但需要：

- 保留版权声明、`LICENSE` 与 `NOTICE`；
- 保留署名「半城雨岁辞、DS、DSH」以及仓库地址
  <https://github.com/Mynorant/ScreenshotManager>。

本项目按「现状」提供，不对任何衍生项目负责；因使用或修改本项目造成的任何后果由使用者自行承担。
