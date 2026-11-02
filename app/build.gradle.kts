plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val versionMajor = 0
val versionMinor = 1
val versionPatch = 0
val versionSuffix = ""

val computedVersionCode = versionMajor * 10000 + versionMinor * 100 + versionPatch
val computedVersionName = "$versionMajor.$versionMinor.$versionPatch$versionSuffix"

android {
    namespace = "com.inkwell.mode"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.inkwell.mode"
        minSdk = 29
        targetSdk = 35
        versionCode = computedVersionCode
        versionName = computedVersionName
        buildConfigField("String", "GIT_VERSION", "\"$computedVersionName\"")
        buildConfigField("int", "VERSION_CODE", "$computedVersionCode")
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
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    compileOnly("de.robv.android.xposed:api:82")
}
