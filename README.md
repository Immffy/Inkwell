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

---

## 九、界面结构（0.2.2 起）

| 界面 | 内容 |
|---|---|
| **主界面** | 模块/root 状态、当前模式、总开关、**恢复默认显示**红色大按钮、四个入口 |
| **显示模式** | 一屏三模大按钮 + 进阶档位（折叠）+ 四个微调滑块 |
| **按 App 指定模式** | 给每个 App 指定原色 / 彩纸 / 墨纸，切前台自动换档 |
| **快捷按钮** | 桌面快捷图标、通知栏 Action、QS 磁贴的设置与创建 |
| **诊断与关于** | 通道实时状态、SurfaceFlinger 自测、复制诊断信息 |

### 一屏三模

参考联想小新 10.1 彩墨柔光版按内容分档的思路，不按电子纸面板型号分档：

| 模式 | 适合 |
|---|---|
| 原色模式 | 动画课程、绘画上色、照片视频 |
| 彩纸模式 | 故事绘本、科普启蒙、漫画 |
| 墨纸模式 | 古典诗词、外语短文、小说 PDF |

六个面板型号档位收进「进阶档位」折叠区，没删。

**米白纸色**：`warmth` 参数给矩阵第 4 列 RGB 三通道不同偏移，让白底变成米白而非灰白，对比度不受影响。墨纸档默认 0.85–1.0。

### 恢复默认显示的入口

1. 主界面红色「恢复默认显示」按钮
2. 桌面 / 长按图标的「原色」快捷方式
3. 通知栏常驻通知的「原色」按钮
4. QS 磁贴（ROM 允许添加时）
5. 显示模式页选「原色模式」

全部走同一套逻辑：下发单位矩阵 + 把关闭状态写入配置文件。
**关闭后重启手机会保持关闭**，不会自己变回墨水屏。

### 控制通道（0.2.2 修正）

| 通道 | 需要 root | 作用 |
|---|---|---|
| 广播 `com.inkwell.mode.ACTION_APPLY` | 否 | **主通道**，直达 system_server，即时生效 |
| `setprop persist.inkwell.*` | 是 | 重启后保持上次选择 |
| 文件 `/data/system/inkwell.conf` | 是 | 兼容性备份，由 su 写入 |

⚠️ **Android 16 的 SELinux 不允许普通 App 直接写 `/data/local/tmp`
或 `/data/system`**。0.2.1 及更早版本把文件通道当"root-free 主通道"是错的，
会导致配置文件写不进去、开关失效。0.2.2 起改由广播承担 root-free 通道，
文件与属性只作为有 root 时的持久化手段。

只要广播能送达，开关就完整可用，**不需要 root、不需要悬浮窗**。
有 root 并给墨境授权 su 后，重启会保持上次选择。

---

## 十、系统快捷开关与快捷按钮（澎湃 OS / HyperOS 重点）

### 更正：第三方磁贴**能**进「系统快捷开关」

此前版本在这里写过「澎湃 OS 只列系统认可的开关，第三方 QS Tile 不会出现」，
**这是误判**。HyperOS 的「编辑与排序」同样读取 `Settings.Secure` 的
`sysui_qs_tiles`：已添加磁贴以逗号分隔，系统原生的是单个 spec
（`wifi` / `bt` / `hotspot` / `night` ...），第三方 TileService 是
`custom(<包名>/<类名>)`，例如
`custom(com.google.android.apps.wellbeing/.screen.ui.GrayscaleTileService)` [citation:16]。
HyperOS 的 `MiuiQSCustomizerController` 把两者一起列在「系统快捷开关」区域 [citation:14]，
在 HyperOS 上发布的第三方磁贴项目也明确写着「编辑页往下滚找到磁贴、拖到常用区」 [citation:10]。

所以找不到「墨境」通常只是：**列表长需要往下滚**，或装完 App 后
SystemUI 还缓存着旧的磁贴枚举、需要刷新一次 [citation:22][citation:26]。
不是 ROM 禁止，也不是 manifest 声明缺失。

### 一键加入（有 root）

`QsTileInstaller` 用 root shell 读 `settings get secure sysui_qs_tiles`，
按逗号切分后**精确匹配**，没有就追加 `custom(com.inkwell.mode/.EinkTileService)`，
再 `settings put secure` 写回，最后发 `QUICK_SETTINGS_CHANGED` 广播刷新 [citation:5]。

- 只追加一项，**不改其它磁贴的顺序**，重复调用不会加出重复项
- 若 ROM 同时存在 `sysui_quick_qs_tiles`，也一并同步；两个 key 都不存在时
  **不凭空创建**（会覆盖默认列表），改为提示手动添加
- 用精确 `==` 而非 `contains`：HyperOS 编辑页判断重复用的是
  `contains(tile)`，曾导致第三方包名碰巧含系统 spec 时该系统磁贴消失 [citation:14]
- 无 root 时给出手动路径，功能完全一样

加入后就是**系统快捷开关**：和护眼模式并列、点按切换开/关、
长按进显示模式页、可在编辑页拖动排序或移除。
`AndroidManifest.xml` 里额外声明了 `TOGGLEABLE_TILE` 与
`TILE_CATEGORY = CATEGORY_DISPLAY`，Android 13+ 会按「显示类开关」渲染 [citation:25]。

### 四条入口

| 入口 | 怎么用 | 需要设置吗 |
|---|---|---|
| **系统快捷开关** | 快捷按钮页点「一键加入」；或编辑页手动添加 | 需 root 或手动加一次 |
| **桌面快捷图标** | 快捷按钮页点「添加到桌面」 | 需创建一次 |
| **长按 App 图标** | 桌面长按墨境图标，弹出 4 个快捷方式，也可拖到桌面 | 装完就有 |
| **通知栏 Action** | 快捷按钮页开启常驻通知，下拉点「原色/彩纸/墨纸/开·关」 | 需开启一次 |

四条最终都汇总到 `QuickActions`，行为完全一致：
切模式 → 广播直达 system_server → 刷新磁贴和通知，全程不启动任何界面。

### 实现要点

- `QuickActionActivity` 用 `Theme.NoDisplay`，执行完立刻 `finish()`，不会闪出界面
- 静态快捷方式写在 `res/xml/shortcuts.xml`，安装后即注册，不需要先打开一次 App
- 桌面固定用标准 `ShortcutManager.requestPinShortcut`（API 26+），
  比 `INSTALL_SHORTCUT` 广播在国产 ROM 上可靠得多
- 常驻通知默认关闭、低优先级不响铃；Android 13+ 首次开启会申请通知权限
- **所有快捷动作的下发都在单线程后台执行**：`ConfigStore` 里的 `su` 持久化会
  `waitFor()`，Magisk / KernelSU 弹授权框时同步调用会卡住调用方主线程 ——
  而 QS 磁贴的 `onClick()` 跑在 SystemUI 绑定线程上，卡住整个控制中心会顿。
  现在 UI 先本地翻转状态即时反馈，指令在后台送
- 原先 QS 磁贴 `onClick()` 里的 `startActivityAndCollapse()` 已移除 ——
  它会强制拉起主界面，与「点一下就切」矛盾

---

## 十一、彩纸模式参数校准（0.2.2）

### 先定「最好」指哪块屏

市面上三条量产彩色电子纸路线，用途完全不同：

| 面板 | 关键参数 | 为什么不是它 |
|---|---|---|
| **Kaleido 3** | 4096 色、黑白 300 PPI / 彩色 150 PPI、CFA 滤光片 | 主流但底色很灰、粉彩感，是"有颜色"不是"颜色好" |
| **Spectra 6** | 六色粒子、对比 30:1、色域宽 | 零售标牌面板，全刷约 12 秒，浓艳到不像纸 [citation:4] |
| **Gallery 3** | ACeP 四色粒子、无滤光片、全分辨率、约 2 万色 | **底色更白、显色最好**，reMarkable Paper Pro 量产 [citation:8][citation:24] |

阅读向能买到的**色彩最好**的是 Gallery 3。所以彩纸模式对标它，
Kaleido 3 的实测数据用来定「淡到什么程度算像电子纸」，
Spectra 6 只用来确认对比度量级，不作为观感目标。

### 关键判断：两处不能照抄

**① 底色灰是缺陷，不是纸感。** Kaleido 3 的灰底来自滤光片吸光，
实测彩膜透光率仅 63%、白亮度从 162 掉到 115 cd/m² [citation:7][citation:9][citation:23]。
Gallery 3 没有滤光片，底色明显更白 [citation:24]。
而且本机是**自发光 OLED**：白是发光像素，压白位只会把白压成灰、显脏，
并不会变成"反射纸的柔光"。所以纸感改由「抬黑位 + 暖色偏移」承担，白位几乎不压。

**② 冷色弱是 CFA 缺陷，不复刻。** 实测 Kaleido 3 在 470nm 蓝光波段反射率
19.1%、620nm 橙红 38.6%，蓝紫亮度 42 vs 红黄 85 cd/m²，
厂商方案甚至要手动补 30% 蓝通道增益 [citation:7]。
Gallery 3 用四色粒子从根上消掉了这个偏差 [citation:11]。
对标更好的面板，就应该保持三通道均衡，而不是把画面染黄。

### 参数映射

Kaleido 3 实测 sRGB 覆盖 **41.3%**（Kobo Clara Colour，CS-2000A 分光辐射计）
到 **58.7%**（海信 A9 Pro）[citation:7][citation:9]。色域面积 ∝ 饱和度系数²，
等效 `s ≈ 0.64–0.77`，取中值 0.68；Gallery 3 显色更好，故不取下限。

| 参数 | 旧值 | 新值 | 依据 |
|---|---|---|---|
| 饱和度 | 0.45 | **0.68** | 落在实测等效区间内，比旧版更有颜色 |
| 黑位 | 0.16 | **0.09** | 电子纸墨黑不纯黑；彩色层对比比单色低 10–15% [citation:19] |
| 白位 | 0.92 | **0.97** | Gallery 3 底白；OLED 压白 = 变灰 |
| 暖度 | 0.55 | **0.32** | 纸白本身中性偏冷，暖味来自前光；暖前光会把彩色染黄、褪色 [citation:23] |

合成后显示端对比（按 Rec.709 亮度加权）**从约 5.6:1 提到约 10.5:1**：
旧值纯白只有 0.93、纯黑 0.17，整幅画面蒙灰、文字发虚；
新值纯白 0.97、纯黑 0.10，落在彩色电子纸对比量级，
且 `white − black` 从 0.76 增到 0.88，画面明显更通透。

### 为什么专门按 Redmi K70 Ultra 调

官方规格：6.67" 2712×1220 OLED、**对比 5,000,000:1**、全局激发 1600nit /
峰值 4000nit、**12bit 色深 / P3 广色域 / 专业原色屏 ΔE≈0.26**、144Hz，
60nit 以下 3840Hz 高频 PWM、以上 DC 调光 [citation:16][citation:17][citation:20]。

| 本机特性 | 对参数的约束 |
|---|---|
| 原生黑近乎 0、对比无限 | 必须**抬黑位**才有"墨黑不纯黑"的纸感；0.09 也避开 OLED 极低驱动电流下的低灰阶 mura / 绿粉偏色 [citation:21] |
| P3 + 12bit + 原色屏 | 源色域比 sRGB 宽，同样 `s` 观感更艳，所以不取 0.8 以上；12bit 保证抬黑后暗部无 banding |
| 自发光、1600/4000nit | 白位保留 0.97，不牺牲亮度优势；不靠压亮度模拟反射 |
| 3840Hz PWM / 60nit 分界 | 极暗亮度下低灰阶一致性最差，见下方实操建议 |
| 144Hz / 天玑 9300+ MiraVision | 矩阵在合成阶段，零开销、与刷新率无关；但厂商画质增强会二次改色，必须清场 |

### 实操建议（比参数本身更影响观感）

1. **先清场**：护眼模式、真彩显示、AI 大师画质引擎、视频画质增强全关，
   色彩风格设「原色」，模拟颜色空间「已停用」。详见第七章。
   MiraVision 会把校准好的饱和度再改一遍。
2. **亮度条别压到最低**：把硬件亮度保持在 60nit 以上（中高段），
   让屏幕走 DC 调光；夜间想暗一点用系统「额外暗化」之类的软件压暗，
   而不是把亮度条拉到底再叠一层矩阵 [citation:21]。
3. **别开暖色前光/护眼**：暖光会把彩纸模式的蓝绿染黄，正好抵消这次校准 [citation:23]。
4. 想回到旧观感：显示模式页 →「进阶档位」→ 选 **Kaleido 3（默认）**，
   旧参数完整保留，可随时对比。

### 矩阵做不到的部分

Gallery 3 的 AG 防眩光是硬件蚀刻，纸纹颗粒、局部压高光是非线性色调映射，
**4×4 色彩矩阵只能做线性变换**，这三样都做不了。
另外电子纸是反射式、白底亮度随环境光变化，OLED 是恒定自发光 ——
这条物理差异无法用矩阵消除，只能靠上面第 2 条把亮度环境调舒服。
