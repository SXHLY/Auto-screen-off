import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ------------------------------------------------------------------
// 自动递增版本号：每次 Gradle 打包（assembleDebug/assembleRelease）
// 都会读取 version.properties 中的 buildNumber 并 +1，
// versionCode 与 versionName 同步更新（如 1.0.2 -> versionCode=2）。
// 已发布的包版本号：version.properties 里 buildNumber=1（对应 1.0/1.0.1）。
// ------------------------------------------------------------------
val versionPropsFile = file("../version.properties")
val versionProps = Properties().apply {
    if (versionPropsFile.exists()) {
        versionPropsFile.inputStream().use { load(it) }
    }
}
val nextBuildNumber = (versionProps.getProperty("buildNumber")?.toIntOrNull() ?: 0) + 1
versionProps.setProperty("buildNumber", nextBuildNumber.toString())
versionPropsFile.outputStream().use {
    versionProps.store(it, "Auto-incremented build number (每次打包自动 +1)")
}

android {
    namespace = "com.autoscreenoff"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.autoscreenoff"
        minSdk = 26
        targetSdk = 34
        versionCode = nextBuildNumber
        versionName = "1.0.$nextBuildNumber"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../keystore/autoscreenoff.keystore")
            storePassword = "***REMOVED***"
            keyAlias = "autoscreenoff"
            keyPassword = "***REMOVED***"
        }
    }

    buildTypes {
        release {
            // R8 混淆 + 资源收缩：无反射/无 JNI，Manifest 组件由 AGP 自动 keep，可安全开启
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
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

// Material 3 + AppCompat 组件库（现代 UI）
dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.core:core-ktx:1.13.1")
}
