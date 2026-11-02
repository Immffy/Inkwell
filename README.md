# 墨境 Inkwell · 彩色墨水屏模式（LSPosed 模块）

> **第一次来？直接看 [`发布指南.md`](发布指南.md)** —— 从建仓库到 APK 装进手机的 9 步全流程。
>
> 其他文档：`怎么用.md`（编译后怎么用）、`怎么上传到GitHub.md`（上传细节）、
> `VERSIONING.md`（版本号规范）、`CHANGELOG.md`（更新日志）


在 **system_server** 进程中向 `DisplayTransformManager` 注入全局色彩矩阵，
把整块屏幕渲染成彩色墨水屏观感。

相比 MediaProjection 方案：**零延迟、零额外耗电、全局生效、开机自动生效、不会被 App 检测到悬浮窗**。

---

## 一、原理

`DisplayTransformManager` 是 Android 管理系统色彩变换的服务，内部用 `SparseArray` 按 **level 升序组合**所有矩阵（低 level 先作用，高 level 后作用）。

我们注册一个 **level = 999** 的矩阵（系统最大常量 `LEVEL_COLOR_MATRIX_INVERT_COLOR = 300`），保证我们的变换最后叠加，不会被护眼、夜览、色彩模式覆盖。

### ⚠️ 矩阵是列主序

这是最容易踩的坑。AOSP 的 `ColorDisplayService.MATRIX_GRAYSCALE` 定义为：

```
{ .2126 .2126 .2126 0,
  .7152 .7152 .7152 0,
  .0722 .0722 .0722 0,
  0     0     0     1 }
```

而灰度的数学形式（行主序）应是：

```
{ .2126 .7152 .0722 0,
  .2126 .7152 .0722 0,
  .2126 .7152 .0722 0,
  0     0     0     1 }
```

两者互为**转置** → 证明 AOSP 按**列主序**存储。`ColorMatrixBuilder` 已处理转置。
传错布局会导致颜色通道串扰（天空变紫、肤色发绿）。

### 配置通道：SystemProperties

App（有 root）执行 `setprop persist.inkwell.sat 0.45`，模块在 system_server 读取。

选它的理由：
1. system_server 无法可靠读取其他 App 的 `/data/data` 私有文件；
2. `persist.` 属性写入 `/data/property`，**重启后保留** → 效果开机自动生效，App 不必常驻。

> 不要用 `service call SurfaceFlinger 1015` 直接下发：它绕过 DTM 的 SparseArray 缓存，
> 之后任何系统色彩变化（切护眼、改色彩模式）都会把它覆盖掉。

---

## 二、能做什么，不能做什么

| 效果 | 矩阵方案 | 说明 |
|---|---|---|
| 降饱和 | ✅ | 线性 |
| 白位下压 / 黑位抬升 | ✅ | 线性，**纸感的主要来源** |
| 对比度压缩 | ✅ | 线性 |
| 通道增益（色温） | ✅ | 线性 |
| 色阶量化（4096 色） | ❌ | 非线性 |
| 有序抖动 | ❌ | 非线性 |
| 彩色滤光网格 | ❌ | 非线性 |
| 纸纤维噪点 | ❌ | 非线性 |

**好消息**：纸感主要来自白黑位映射，这部分矩阵能做。
缺失的量化/抖动/网格主要影响"彩色的粗糙感"，黑白档下几乎看不出差别。

需要完整效果时，用另一个 MediaProjection + shader 工程。

---

## 三、安装激活

1. 编译出 APK（Android Studio 或 GitHub Actions），装到手机
2. 打开 **LSPosed 管理器** → 模块 → 找到「墨境」→ 启用
3. **作用域勾选「系统框架 / android」，并勾选「墨境」本身**
   - android：必须勾，模块靠它注入 system_server
   - 墨境：勾上后 App 才能正确显示「已激活」（不勾也能正常工作，只是状态显示不准）
4. **重启手机**
5. 打开 App，顶部应显示「模块状态：已激活 ✓」

若显示未激活：
- 确认 LSPosed 里模块开关已打开
- 确认作用域包含 android
- 重启了没有（Xposed 模块必须重启才加载）
- 去 LSPosed → 日志，搜索 `Inkwell` 看有没有报错

---

## 四、调参

App 界面三部分：

**预设**：Kaleido 3（默认）/ Gallery 3 / 漫画优化 / 黑白纸质 / Spectra 6 / 关闭效果

**微调**：饱和度、黑位、白位三个滑块，点「应用微调」立即生效（会覆盖当前预设）

**按 App 切黑白档**：填入包名（如 `com.tencent.weread`），这些 App 在前台时自动切黑白纸质档。
比按画面彩度判断更实用——阅读类用黑白档更亮更锐利。

模块每 **2 秒**轮询一次配置变化，所以改完最多 2 秒生效。

---

## 五、诊断

如果屏幕没变化，按顺序排查：

**第 1 步：确认模块加载**
LSPosed → 日志，搜 `Inkwell`，应有：
```
Inkwell: loaded in system_server
Inkwell: found DTM: com.android.server.display.color.DisplayTransformManager
Inkwell: DTM instance captured
Inkwell: applied: sat=0.45 black=0.16 white=0.92
```

**第 2 步：确认 root**
App 顶部应显示「root：已获取 ✓」。没有就在 Magisk 里给本 App 授权。

**第 3 步：确认参数真的写进去了**
```
adb shell getprop persist.inkwell.sat
adb shell getprop persist.inkwell.black
adb shell getprop persist.inkwell.white
adb shell getprop persist.inkwell.enabled
```
应有值且 `enabled=1`。

**第 4 步：确认 SurfaceFlinger 侧可用**
App 里点「测试 1015：直接下发矩阵」。屏幕立刻变化说明 SF 侧没问题；
没变化说明本机 transaction code 不是 1015，需查 AOSP `ISurfaceComposer.aidl` 里
`setColorMatrix` 的实际序号（Android 15/16 有增删）。

**第 5 步：小米 HyperOS 特别注意**
小米可能魔改了色彩服务，导致 DTM 被替换或矩阵被二次覆盖。
若以上都正常但效果不生效，看日志里有没有 `our matrix was dropped` —
有的话说明守护逻辑在工作但被反复覆盖，需要调整 apply 时机。

---

## 六、风险提示

1. **改的是系统全局色彩**，写错矩阵最坏情况是颜色错乱。
   随时可用 App 的「恢复：1015 i32 0」或把 `persist.inkwell.enabled` 设为 0 复原。
2. **卡在开机动画**的极端情况：进 recovery 或 fastboot 删除 `/data/property/persist.inkwell.*`。
   实际概率极低（模块只在 boot completed 后才 apply）。
3. **抬黑位更耗电**（OLED），如果省电是目标，这条路走不通。
4. **银行 / DRM 视频 App** 不受影响——矩阵在硬件合成阶段生效，不涉及 overlay 权限，
   这是本方案相对 MediaProjection 的一大优势。

---

## 文件结构

```
app/src/main/
├── assets/xposed_init              模块入口声明
├── java/com/inkwell/mode/
│   ├── HookEntry.kt                ★ 模块主逻辑：hook DTM、注入、守护、轮询
│   ├── ColorMatrixBuilder.kt       ★ 列主序矩阵构建
│   ├── PropConfig.kt               模块端读 SystemProperties
│   ├── PropWriter.kt               App 端写 SystemProperties（root）
│   ├── EinkParams.kt               参数模型与预设
│   └── MainActivity.kt             调参 UI
└── AndroidManifest.xml             xposedmodule 元数据 + xposedscope=android
```

---

## 七、Redmi K70 Ultra（天玑 9300+ / HyperOS 3 / Android 16）专属注意事项

### 好消息：条件很好

- **Android 16（API 36）** + LSPosed 2.2.1 已激活 → 框架层面没问题
- **2712×1220 / 6.67" = 446 PPI** → 印证了 `cellPx = 3px ≈ 150PPI 彩色` 的设定
- DisplayTransformManager 是 **AOSP 通用机制**，与 SoC 平台无关 → 本模块能工作

### ⚠️ 关键差异：这是联发科，不是高通

天玑 9300+ 是 **MTK 平台**，带 **MiraVision** 显示增强（AI-PQ 场景画质、区域画质优化、
SDR→HDR、记忆色增强）。小米在 HyperOS 里把它包装成「AI 大师画质引擎」「视频画质增强」等开关。

它和我们的矩阵**是叠加关系，不是冲突关系**——MiraVision 在面板驱动 / HAL 层做增强，
SurfaceFlinger 矩阵在合成阶段做全局线性变换，两者独立生效。

但后果是：**厂商增强会把你的调参结果再改一遍**，导致参数对不上预期。
所以调参前必须"清场"。

### 调参前必须关掉的开关

| 设置项 | 路径 | 为什么关 |
|---|---|---|
| 护眼模式 / 纸质护眼 | 设置 → 显示与亮度 → 护眼模式 | 它本身就是一层色彩 + 纹理变换，会叠加 |
| 真彩显示 | 显示与亮度 | 随环境光动态改色温，参数会飘 |
| 色彩风格 → 改「原色」 | 显示与亮度 → 色彩风格 | 别关，是选"原色"。生动/鲜艳会额外加饱和 |
| AI 大师画质引擎 | 显示与亮度 → 高级设置 | MTK MiraVision 的入口，会改饱和度/对比 |
| 视频画质增强 / 超分 | 视频工具箱 | 同上，且只在视频播放时介入 |
| 开发者选项 → 模拟颜色空间 | 开发者选项 | 确认设为"已停用" |
| 屏幕刷新率 | 显示与亮度 → 屏幕刷新率 | 调 60Hz，更像墨水屏（可选） |

清场后再调参，参数才对应真实效果。

### 一个有用的判断

MiraVision 的 PQ 增强**主要在视频播放场景**介入（AI-PQ 是逐帧场景识别）。
而墨水屏模式主要用于**阅读和静态 UI**——这两块 MTK 增强介入很弱。

所以：**阅读、漫画、系统 UI 下效果可控；看视频时可能有额外偏色。**
如果只在阅读场景用，可以不管视频增强；要全场景一致就把上面的开关都关掉。

### 验证顺序

1. 按上表清场
2. 装模块、勾作用域 android、重启
3. App 里选「黑白纸质」——**先验证黑白档**，因为矩阵方案在黑白下效果最确定
4. 确认黑白档生效后，再试彩色档
5. 若彩色档观感偏差大，检查 AI 大师画质引擎是否真的关掉了

### 若矩阵被反复覆盖

日志里出现 `our matrix was dropped` 且频繁刷屏，说明 HyperOS 有服务在持续重置色彩。
此时把 apply 延迟从 3000ms 调大（改 `HookEntry.scheduleApply(3000)` 的参数），
或在 `setColorMatrix` 的守护逻辑里加更短的复查间隔。把日志发我，我按实际情况调。

---

## 八、版本管理

版本号遵循 **语义化版本 2.0**（`MAJOR.MINOR.PATCH`），完整规范见 **`VERSIONING.md`**。

- 版本唯一数据源在 `app/build.gradle.kts` 顶部四行，改那里即可
- `versionCode` 自动推导（`MAJOR*10000 + MINOR*100 + PATCH`），保证单调递增
- APK 文件名自动带版本号，App 界面右上角也显示
- 每次发版要更新 **`CHANGELOG.md`**，写「用户能感知的变化」
- 打 `v0.1.0` 这样的 tag 并 push，GitHub Actions 会自动创建 Release 并附上 APK

当前版本见 `CHANGELOG.md`。
