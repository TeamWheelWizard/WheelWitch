plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.spotless)
}

spotless {
  kotlin { ktfmt() }
  kotlinGradle { ktfmt() }
}

android {
  namespace = "com.skiletro.wheelwitch"
  compileSdk {
    version =
        release(36) {
          minorApiLevel = 1
        }
  }

  defaultConfig {
    applicationId = "com.skiletro.wheelwitch"
    minSdk = 31
    targetSdk = 36
    val commitCount =
        try {
          Runtime.getRuntime()
              .exec("git rev-list --count HEAD")
              .inputStream
              .readBytes()
              .decodeToString()
              .trim()
              .toInt()
        } catch (_: Exception) {
          1
        }
    val gitHash =
        try {
          Runtime.getRuntime()
              .exec("git rev-parse --short HEAD")
              .inputStream
              .readBytes()
              .decodeToString()
              .trim()
              .take(7)
        } catch (_: Exception) {
          "unknown"
        }
    versionCode = commitCount
    versionName = "0.$commitCount.0+$gitHash"
    buildConfigField("String", "GIT_HASH", "\"$gitHash\"")
    buildConfigField("String", "LOGS_EMAIL", "\"wheelwitch@skilet.ro\"")
  }

  signingConfigs {
    create("release") {
      enableV3Signing = true
      enableV4Signing = true
      val keystorePath = System.getenv("KEYSTORE_PATH")
      if (keystorePath != null) {
        storeFile = rootProject.file(keystorePath)
        storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
        keyAlias = System.getenv("KEY_ALIAS") ?: ""
        keyPassword = System.getenv("KEY_PASSWORD") ?: ""
      }
    }
  }

  buildTypes {
    debug {
      applicationIdSuffix = ".debug"
      buildConfigField("String", "GIT_HASH", "\"debug\"")
    }
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(
          getDefaultProguardFile("proguard-android-optimize.txt"),
          "proguard-rules.pro",
      )
      signingConfig = signingConfigs.getByName("release")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions {
    unitTests.isReturnDefaultValues = true
  }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.foundation)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.documentfile)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.material)
  implementation(libs.okhttp)
  implementation(libs.rollingnumbers)
  implementation(libs.timber)
  implementation(libs.coil.compose)
  implementation(libs.coil.network.okhttp)
  debugImplementation(libs.androidx.compose.ui.tooling)

  testImplementation(libs.junit.jupiter.api)
  testImplementation(libs.junit.jupiter.params)
  testImplementation(libs.mockk)
  testImplementation(libs.truth)
  testImplementation(libs.okhttp.mockwebserver)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation("org.json:json:20260522")
  testRuntimeOnly(libs.junit.jupiter.engine)
  testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test> {
  useJUnitPlatform()
  maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
  forkEvery = 200
}

tasks.register("printVersionName") {
  group = "versioning"
  description = "Prints the versionName for CI consumption (strips build metadata)"
  doLast { println(android.defaultConfig.versionName?.substringBefore("+") ?: "unknown") }
}
