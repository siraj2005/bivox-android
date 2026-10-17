// إعدادات المشروع: مصادر الإضافات والمكتبات
// Project settings: plugin and dependency repositories
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "BiVoxAndroid"
include(":app")
