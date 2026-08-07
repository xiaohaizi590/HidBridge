import java.io.File
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

android {
  namespace = "dev.hid.demo"
  compileSdk = 36

  defaultConfig {
    applicationId = "dev.hid.demo"
    minSdk = 28
    targetSdk = 36
    versionCode = 2
    versionName = "1.2"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  buildTypes {
    release {
      // R8 混淆 + 资源裁剪，APK 从 ~25MB 降到 ~5MB
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
      )
      // 自用 demo：暂用 debug 签名，方便直接安装；正式发布请换成正式签名
      signingConfig = signingConfigs.getByName("debug")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    compose = true
  }
}

// 自定义 APK 输出文件名：HidBridge-v版本号.apk
val releaseApkDir = project.layout.buildDirectory.dir("outputs/apk/release")
tasks.matching { it.name == "assembleRelease" }.configureEach {
    doLast {
        val dir = releaseApkDir.get().asFile
        fileTree(dir) { include("*.apk") }.forEach { apk ->
            if (apk.name == "app-release.apk") {
                val newFile = File(dir, "HidBridge-v1.2.apk")
                apk.copyTo(newFile, overwrite = true)
                apk.delete()
            }
        }
    }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  debugImplementation(libs.androidx.compose.ui.tooling)
}

kotlin {
  compilerOptions {
    jvmTarget = JvmTarget.JVM_17
  }
}
