plugins {
    alias(libs.plugins.android.application)
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

    buildTypes {
        release {
            // Xposed 模块不能被 R8 混淆/裁剪：入口类、hook 回调依赖名字原样保留
            optimization {
                enable = false
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