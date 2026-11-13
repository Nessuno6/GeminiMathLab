import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "com.deang.geminimath"
  compileSdk = 37
  defaultConfig {
    applicationId = "com.deang.geminimath"
    minSdk = 31
    targetSdk = 37
    // A release: raise both, then tag it v<versionName> on GitHub (see README); the app's updater
    // compares versionName with the release tag
    versionCode = 3
    versionName = "1.2.0"
    // 64-bit only: every Android 17 phone, tablet and Googlebook is 64-bit, and the OCR runtime is
    // large, so the 32-bit and x86 copies only made the APK bigger
    ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
  }

  // Release signing from keystore.properties in the project folder (kept out of git)
  val signing = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
  }
  signingConfigs {
    if (signing != null) create("release") {
      storeFile = file(signing.getProperty("storeFile"))
      storePassword = signing.getProperty("storePassword")
      keyAlias = signing.getProperty("keyAlias")
      keyPassword = signing.getProperty("keyPassword")
    }
  }

  buildTypes {
    release {
      if (signing != null) signingConfig = signingConfigs.getByName("release")
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    aidl = false
    buildConfig = false
    shaders = false
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
      // Licence/notice files repeated across the Claude SDK's libraries
      excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.kotlin_module", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
  }
  androidResources {
    noCompress += listOf("onnx")
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // On-Device Gemini Nano (ML Kit GenAI Prompt API via AICore) & Digital Ink
  implementation("com.google.mlkit:genai-prompt:1.0.0-beta4")
  implementation("com.google.mlkit:digital-ink-recognition:19.0.0")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
  implementation("com.google.android.gms:play-services-auth:21.3.0")
  // Phone as toolbar: links the phone and the tablet/Googlebook over Bluetooth and Wi-Fi
  implementation("com.google.android.gms:play-services-nearby:19.5.1")
  // Laser pointer: predicts where the pen is going so the laser keeps up with it
  implementation("androidx.input:input-motionprediction:1.0.0")
  // AI assistant (your own API key): Anthropic's official SDK for Claude
  implementation("com.anthropic:anthropic-java:2.34.0")

  // ONNX Runtime Mobile for On-Device Pix2Text-MFR 1.5 Math-to-LaTeX OCR
  // 1.20 shipped a 4 KB-aligned JNI library; newer releases are built for 16 KB page sizes
  implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.1")
}
