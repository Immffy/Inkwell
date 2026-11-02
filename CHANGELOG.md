# 更新日志

本项目遵循 [语义化版本 2.0](https://semver.org/lang/zh-CN/)。
格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

**每次发版都要在这里加一条**，写清楚「用户能感知到什么变化」，而不是「改了哪行代码」。

---

## [未发布]

（开发中，还没打 tag 的改动记在这里）

---

## [0.1.0] - 2026-11-02

首个版本，原型验证阶段。
定名 **墨境 Inkwell**，包名 `com.inkwell.mode`。

### 新增
- 在 `system_server` 中 hook `DisplayTransformManager`，注入 level 999 的全局色彩矩阵
- 六套预设：Kaleido 3 / Gallery 3 / 漫画优化 / 黑白纸质 / Spectra 6 / 关闭效果
- 饱和度、黑位、白位三个滑块微调
- 按 App 包名自动切黑白档（阅读类 App 用黑白档更亮更锐利）
- 配置经 `persist.inkwell.*` 系统属性下发，重启后自动生效，App 无需常驻
- 矩阵守护：检测到被系统覆盖时自动重新注入
- 「测试 1015」按钮用于验证 SurfaceFlinger 侧可用性
- 语义化版本体系：`versionCode` 由版本号自动推导，APK 文件名带版本号
- App 界面右上角显示当前版本，便于排查
- 应用图标：墨滴 + 四色点意象，纸白 + 墨绿配色

### 修复
- 修正矩阵存储为**列主序**（已用 AOSP `MATRIX_GRAYSCALE` 逐位验证），
  此前按行主序会导致颜色通道串扰

### 文档
- `VERSIONING.md` 版本规范
- `怎么用.md` 从编译到激活的完整步骤
- README 含 Redmi K70 Ultra（天玑 9300+）专属注意事项

### 已知限制
- 矩阵只能做线性变换：色阶量化、抖动、彩色滤光网格、纸纹**无法实现**，
  需另走 MediaProjection + GPU shader 方案
- 未做刷新行为模拟（翻页闪烁、滚动残影）
