import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// 可选的发布签名：根目录存在 keystore.properties 时启用，否则回退到 debug 签名
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        // 必须用 UTF-8 读：别名是中文，Properties 默认按 ISO-8859-1 会乱码
        keystorePropertiesFile.reader(Charsets.UTF_8).use { load(it) }
    }
}

android {
    namespace = "mynorant.screenshot.manager"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "mynorant.screenshot.manager"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Xposed 模块不能被 R8 混淆/裁剪：入口类、hook 回调依赖名字原样保留
            optimization {
                enable = false
            }
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // 仅编译期依赖：运行时由 Xposed 框架（Vector）提供，绝不能打进 APK
    compileOnly(libs.libxposed.api)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.viewpager2)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}
