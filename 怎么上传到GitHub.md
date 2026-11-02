# 怎么把工程传到 GitHub（手机操作）

## ⚠️ 最大的坑：目录结构不能乱

GitHub 网页上传文件时，如果你一次性选中多个文件，**它们会被平铺到根目录，丢掉文件夹结构**。
而 Android 工程必须保持目录结构，否则编译一定失败。

所以别图省事一次全选——**分目录上传，或用下面的 Termux 方案**。

---

## 方案 A：Termux + git（推荐，一次搞定）

### 1. 装 Termux

**别装 Google Play 版**（已停更）。去 F-Droid 官网 `f-droid.org` 下载 Termux，
或直接搜 "Termux F-Droid apk"。

### 2. 装 git 并授权访问存储

打开 Termux，依次执行：
```
pkg update
pkg install git unzip
termux-setup-storage
```
第三条会弹权限请求，**允许**。

### 3. 生成 GitHub 令牌（关键，密码已不能用）

GitHub 网页 → 右上角头像 → `Settings`
→ 左侧最下面 `Developer settings`
→ `Personal access tokens` → `Tokens (classic)`
→ `Generate new token (classic)`
- Note 随便填，比如 `phone`
- 勾选 **`repo`**（整个勾上）
- 拉到最下面 `Generate token`

**token 只显示一次，立刻复制保存到备忘录。** 格式是 `ghp_xxxx...`

### 4. 推送

假设你的 GitHub 用户名是 `YOURNAME`，仓库名是 `Inkwell`：
```
cd /sdcard/Download
unzip Inkwell.zip
cd Inkwell
git init
git config user.name "YOURNAME"
git config user.email "you@example.com"
git add -A
git commit -m "init: 墨境 Inkwell 0.1.0"
git branch -M main
git remote add origin https://github.com/YOURNAME/Inkwell.git
git push -u origin main
```

会提示输入用户名密码：
- 用户名：填你的 GitHub 用户名
- 密码：**粘贴刚才那个 token**（不是登录密码）

看到 `main -> main` 就成功了。回 GitHub 网页刷新，文件都在，目录结构完整。

---

## 方案 B：GitHub 网页手工上传（不用装东西，但要做 11 轮）

### 前提
仓库已建好（空的，勾了 README 的那个也行）。

### 通用套路
进仓库 → 点 `Add file` → `Upload files`
→ 点 "choose your files" 从文件管理器**多选**（长按可多选）
→ 拉到底 `Commit changes`

### 按这个顺序，一个目录一轮

| 轮次 | 先做 | 上传哪些文件 |
|---|---|---|
| 1 | 在仓库**根目录** | `CHANGELOG.md` `README.md` `VERSIONING.md` `怎么用.md` `build.gradle.kts` `gradle.properties` `settings.gradle.kts` |
| 2 | 根目录 → Add file → **Create new file**，文件名填 `.github/workflows/build-apk.yml`，粘贴内容提交 | （这轮用 Create new file，不是 Upload） |
| 3 | 同上，建 `app/build.gradle.kts` | 同轮再建 `app/proguard-rules.pro` |
| 4 | 同上，建 `app/src/main/AndroidManifest.xml` | — |
| 5 | 同上，建 `app/src/main/assets/xposed_init` | **内容只有一行**：`com.inkwell.mode.HookEntry` |
| 6 | 同上，逐个建 `app/src/main/java/com/inkwell/mode/` 下的 6 个 `.kt` | ColorMatrixBuilder / EinkParams / HookEntry / MainActivity / PropConfig / PropWriter |
| 7 | 同上，建 `app/src/main/res/drawable/ic_launcher_foreground.xml` | — |
| 8 | 同上，建 `app/src/main/res/layout/activity_main.xml` | — |
| 9 | 同上，建 `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` | — |
| 10 | 同上，建 `app/src/main/res/values/` 下 3 个 | colors / strings / themes |
| 11 | 同上，建 `gradle/wrapper/gradle-wrapper.properties` | — |

**关键技巧**：在 `Create new file` 的文件名框里输入**带斜杠的完整路径**，
比如 `app/src/main/java/com/inkwell/mode/HookEntry.kt`，
GitHub 会**自动创建沿途所有目录**。这是手工方案能成立的原因。

一轮轮做，做完 11 轮目录结构就对了。

### 哪里能拿到文件内容

zip 解压后，用手机上的文本编辑器（如「MT 管理器」「920 文本编辑器」）
打开每个文件，全选复制，粘贴到 GitHub 网页。

---

## 上传完怎么确认

回仓库首页，应该能看到这些目录（点得进去就说明结构对了）：

```
.github/
app/
gradle/
CHANGELOG.md
README.md
VERSIONING.md
build.gradle.kts
gradle.properties
settings.gradle.kts
怎么用.md
```

**重点检查**：
- `.github/workflows/build-apk.yml` 在不在（隐藏目录，容易漏）
- `app/src/main/java/com/inkwell/mode/` 下面有 6 个 `.kt`

---

## 然后触发编译

`Actions` 标签 → 首次要点 "I understand my workflows, go ahead and enable them"
→ 左侧点 `Build APK` → 右侧 `Run workflow` → 绿色 `Run workflow`

等 3–5 分钟刷新，页面最下面 Artifacts 下载 APK。

失败的话点进去看红色日志，把报错发我。
