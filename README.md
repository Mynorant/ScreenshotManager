# 截图管理 / ScreenshotManager

一个给 ColorOS「截屏」(com.oplus.screenshot) 加**倒计时删除**的 Xposed 模块。

截图后，悬浮条上会多出一个「标记」按钮。点它，悬浮条上的按钮会原地变成
「1 天后 / 3 天后 / 7 天后」；选一个，这张截图就会在到期后的第一个 0:00 被自动删除。

> 由 DeepSeek、DeepSeek Harness 辅助生成代码。

> 基于 LibXposed API 102，运行框架为 [Vector](https://github.com/JingMatrix/Vector)。

---

## 功能

- **倒计时删除**：截图时直接标记 1 / 3 / 7 天后删除，到点自动清理，不用手动翻相册。
- **到期时间规则**：`图片创建时间 + N×24 小时` 之后的**第一个 0:00**。
  例如 19 日 13:20 的截图选 3 天，实际删除时间是 23 日 0:00。
- **每天只在 0:00 检查一次**，不做轮询；开机或应用更新后只重排闹钟，不会立刻扫描。
- **标记列表**：主界面能看到已标记的截图（缩略图、真实文件名、到期时间），
  可按天数筛选，点开全屏查看、左右滑动切换，也可以随时取消标记或按组「全部删除」。
- **失效标记自动清理**：图片在相册里被彻底删除后，标记会自动移除；
  仅移入回收站的图片仍保留标记。
- **安全删除**：只删除数据库里登记过的那一张，删除前会校验 `DATE_TAKEN`，
  防止 MediaStore 的 `_id` 被复用后误删其他图片；每轮最多删 20 条。
- **进程被清理也不丢标记**：写入失败时会显式唤醒 App 补写；仍然失败才落盘排队，下次启动自动补录。

## 适配情况

| 项目 | 说明 |
|---|---|
| 目标应用 | ColorOS「截屏」`com.oplus.screenshot` **16.16.2**（versionCode 160016002） |
| 系统 | ColorOS 16 / Android 16 |
| 验证设备 | 一加 ACE 5 |
| 其他版本 | **未测试**，类名/方法名或行为可能不同 |
| 其他系统 | **不兼容**，小米/vivo/三星等手机系统自带的截屏应用不适用 |

## 未来方向

- 优先跟进 ColorOS 各版本的「截屏」适配；
- 尝试增加将指定包名的截图转移至专用相册的功能……（画大饼 ing）
- 其他系统 / ROM 尽力而为，不保证。

## 构建

环境：Android Studio 最新版、JDK 17+、Android SDK 37。

```bash
# 调试版（本机 debug 签名）
./gradlew :app:assembleDebug

# 发布版（使用 keystore.properties 配置的签名）
./gradlew :app:assembleRelease
```

产物：`app/build/outputs/apk/{debug,release}/`。

### 发布签名

在项目根目录建一个 `keystore.properties`（已在 `.gitignore` 中）：

```properties
storeFile=keystore/release.jks
storePassword=******
keyAlias=your-alias
keyPassword=******
```

没有这个文件时会回退到 debug 签名，不影响日常编译。`keyAlias` 可以是中文，
Gradle 侧已用 UTF-8 读取该文件。**更换签名后必须先卸载旧版本**（签名不同无法覆盖安装）。

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

> 模块不常驻前台服务。App 进程被系统清理时，标记会通过「唤醒页」补写；
> 如果连唤醒都被系统拒绝，会写入 `Download/ScreenshotManager/` 排队，等下次打开 App 自动补录。

## 工作原理

| Hook 点 | 用途 |
|---|---|
| `ScreenshotRequest#c(Bitmap)Z` | 位图就绪，作为每次截图的起点（同时复位标记模式） |
| `FloatWindowLayer#setRequest` | 悬浮层拿到本次请求，记录最近一次 request |
| `FloatWindowWidget#getAlphaAnimator(List,boolean,boolean)` | 往 `animators` 追加动画，把「标记」按钮插进原生按钮条 |
| `FloatButton#updateText(int)` | 标记模式下拦住宿主把按钮文案改回去 |
| `ScreenshotRequest#v0()` | 保存完成，拿到 MediaStore URI 与 `dateTaken` |
| `FloatWindowLayer#actionDragExited` | 缩略图被拖出丢弃（仅日志） |

写入链路（按顺序降级）：

1. `ContentResolver.insert` 写 `MarkProvider`（宿主有 `QUERY_ALL_PACKAGES`，可直接解析）；
2. 失败后延迟 1 秒重试一次；
3. 仍失败则显式启动无界面 `WakeActivity` 唤醒 App 进程写库；
4. 再失败才把标记写成 `Download/ScreenshotManager/sm_mark_*.json`，由 App 启动时补录并删除。

## 项目结构

```
app/src/main/java/mynorant/screenshot/manager/
├── XposedEntry.java      模块入口与全部 hook
├── MarkProvider.java     标记存储（SQLite + ContentProvider）
├── MarkReceiver.java     广播写入（兜底）
├── MarkQueue.java        落盘队列，App 启动时补录
├── WakeActivity.java     无界面唤醒页（进程被回收时补写）
├── MarkCleanup.java      到点删除（含安全检查）
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
- 保留署名「半城雨岁辞（Mynorant）、DS、DSH」以及仓库地址
  <https://github.com/Mynorant/ScreenshotManager>。

本项目按「现状」提供，不对任何衍生项目负责；因使用或修改本项目造成的任何后果由使用者自行承担。
