// بناء التطبيق. بلا أي مكتبات خارجية: حجم صغير وتوافق أوسع.
// App build. No external libraries: small size and wider compatibility.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// رقم البناء يأتي تلقائيًا من GitHub Actions (-PbivoxVersionCode=رقم التشغيل)
// Build number comes automatically from GitHub Actions (-PbivoxVersionCode=run number)
val buildNumber: Int = (project.findProperty("bivoxVersionCode") as String?)?.toIntOrNull() ?: 5

// مفتاح التوقيع الثابت يصل عبر متغيرات بيئة من Secrets؛ دونه يُستعمل مفتاح التجربة
// The fixed signing key arrives via env vars from Secrets; without it the debug key is used
val hasReleaseKey: Boolean = System.getenv("BIVOX_KEYSTORE_FILE") != null

android {
    namespace = "app.bivox.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.bivox.android"
        // الحد الأدنى أندرويد 7.0 (API 24) — أقل قيمة تدعم كل ميزات المراحل القادمة بلا مكتبات
        // Minimum Android 7.0 (API 24) — lowest value that supports later phases without extra libs
        minSdk = 24
        targetSdk = 34
        versionCode = buildNumber
        // يُرفع مع كل تحديث ويظهر داخل التطبيق / bumped every update and shown in the app
        versionName = "0.4.3"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKey) {
                storeFile = file(System.getenv("BIVOX_KEYSTORE_FILE"))
                storePassword = System.getenv("BIVOX_STORE_PASS")
                keyAlias = System.getenv("BIVOX_KEY_ALIAS")
                keyPassword = System.getenv("BIVOX_KEY_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
