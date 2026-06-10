import com.android.build.gradle.internal.api.BaseVariantOutputImpl

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ============================================
// 📌 מקור אמת יחיד לגרסה: login.html (window.APP_VERSION בראש הקובץ)
// ============================================
// קוראים את login.html בזמן הקימפול ומחלצים ממנו את APP_VERSION.
// ⚠️ התאם את הנתיב למיקום האמיתי של login.html בפרויקט.
val loginHtmlFile = file("src/main/assets/login.html")

val appVersionName: String = run {
    val fallback = "1.0.0"
    if (!loginHtmlFile.exists()) {
        println("login.html not found at ${loginHtmlFile.path} - using fallback $fallback")
        return@run fallback
    }
    val text = loginHtmlFile.readText()
    val m = Regex("""APP_VERSION\s*=\s*['"]([^'"]+)['"]""").find(text)
    m?.groupValues?.get(1)?.trim() ?: fallback
}

// derive versionCode from the name: 1.0.4 -> 10004 (Android requires an increasing code)
val appVersionCode: Int = run {
    val parts = appVersionName.split(".").map { it.toIntOrNull() ?: 0 }
    val major = parts.getOrElse(0) { 0 }
    val minor = parts.getOrElse(1) { 0 }
    val patch = parts.getOrElse(2) { 0 }
    major * 10000 + minor * 100 + patch
}

android {
    namespace = "com.heatmind.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.heatmind.app"
        minSdk = 24
        targetSdk = 35
        versionCode = appVersionCode      // derived from login.html
        versionName = appVersionName      // derived from login.html
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

    // APK file name: heatmind-<version>.apk
    applicationVariants.all {
        val variantVersionName = versionName
        outputs.all {
            (this as BaseVariantOutputImpl).outputFileName = "heatmind-$variantVersionName.apk"
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
    implementation("androidx.webkit:webkit:1.9.0")
    implementation("com.google.android.material:material:1.12.0")
}