pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Xposed API 仓库
        maven { url = uri("https://api.xposed.info/") }
        // JitPack（备选 Xposed 镜像）
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "QQNekoRewrite"
include(":app")
