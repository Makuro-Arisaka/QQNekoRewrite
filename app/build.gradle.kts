plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.neko.rewrite"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.neko.rewrite"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.91"
    }

    // 仅当环境变量 KEYSTORE_PASS 存在时才启用签名；否则 release 仍产出 unsigned。
    // 密码绝不写入本文件或仓库（.gitignore 已排除 *.keystore）。
    signingConfigs {
        create("release") {
            System.getenv("KEYSTORE_PASS")?.let { pass ->
                storeFile = File(rootDir, "neko-rewrite.keystore")
                storePassword = pass
                keyAlias = "nekorewrite"
                keyPassword = pass
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
            System.getenv("KEYSTORE_PASS")?.let {
                signingConfig = signingConfigs.getByName("release")
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
}

dependencies {
    // Xposed API (compileOnly — 不打包进 APK)
    compileOnly("de.robv.android.xposed:api:82")
    compileOnly("de.robv.android.xposed:api:82:sources")

    // Material Design 3
    implementation("com.google.android.material:material:1.11.0")

    // AppCompat
    implementation("androidx.appcompat:appcompat:1.6.1")

    // Fragment
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // OkHttp — HTTP 客户端（AI API 调用）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines — 异步处理
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // Serialization — JSON 序列化
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
}