// بناء التطبيق. بلا أي مكتبات خارجية: حجم صغير وتوافق أوسع.
// App build. No external libraries: small size and wider compatibility.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.bivox.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.bivox.android"
        // الحد الأدنى أندرويد 7.0 (API 24) — أقل قيمة تدعم كل ميزات المراحل القادمة بلا مكتبات
        // Minimum Android 7.0 (API 24) — lowest value that supports later phases without extra libs
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        // يُرفع مع كل تحديث ويظهر داخل التطبيق / bumped every update and shown in the app
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
