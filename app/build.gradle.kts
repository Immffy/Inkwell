plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ════════════════════════════════════════════════════════════════
//  墨境 Inkwell —— 彩色墨水屏模式（LSPosed 模块）
//
//  包名 com.inkwell.mode（⚠️ 已发布后不可再改，改了等于新 App）
//
//  版本唯一数据源 —— 发版时只改这四行，其他全自动
//
//  语义化版本 MAJOR.MINOR.PATCH（SemVer 2.0）：
//    MAJOR  不兼容变更：改名、换架构、旧配置失效、删功能
//    MINOR  新增功能且向下兼容：加预设、加开关、加参数
//    PATCH  修 bug、调默认值、改文案，不影响功能
//
//  0.x.x = 原型验证期（随时可能大改）；1.0.0 = 自己日常用稳定
//
//  versionCode 由版本号自动推导：MAJOR*10000 + MINOR*100 + PATCH
//    0.1.0 → 100 / 0.2.0 → 200 / 1.0.0 → 10000 / 1.2.3 → 10203
//  好处：天然单调递增，永远不会出现「改了 versionName 忘了 versionCode」
//
//  ⚠️ 已发布过的 versionCode 绝不能回退，只能往上走
// ════════════════════════════════════════════════════════════════
val versionMajor = 0
val versionMinor = 1
val versionPatch = 0
val versionSuffix = "" // 预发布用 "-alpha.1" / "-beta.1"，正式版留空

val computedVersionCode = versionMajor * 10000 + versionMinor * 100 + versionPatch
val computedVersionName = "$versionMajor.$versionMinor.$versionPatch$versionSuffix"

android {
    namespace = "com.inkwell.mode"

    // compileSdk = 35 不用追到 36。手机是 Android 16(API 36)，
    // 但用 35 编译在上面跑得好好的；追最新反而可能因云端 runner
    // 没预装 API 36 而编译失败。
    compileSdk = 35

    defaultConfig {
        applicationId = "com.inkwell.mode"
        minSdk = 29
        targetSdk = 35
        versionCode = computedVersionCode
        versionName = computedVersionName

        // 把版本写进 BuildConfig，App 里可显示（便于排查"装的到底是哪个版本"）
        buildConfigField("String", "GIT_VERSION", "\"$computedVersionName\"")
        buildConfigField("int", "VERSION_CODE", "$computedVersionCode")
    }

    buildFeatures {
        buildConfig = true
    }

    // APK 文件名自动带版本号：eink-mode-v0.1.0.apk
    // 避免下载一堆都叫 app-debug.apk 的文件分不清
    androidComponents {
        onVariants(selector().all) { variant ->
            variant.outputs.forEach { output ->
                output.outputFileName.set(
                    "墨境-v${computedVersionName}${if (variant.name == "debug") "-debug" else ""}.apk"
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Xposed API：仅编译期需要，运行时由 LSPosed 提供
    compileOnly("de.robv.android.xposed:api:82")
}
