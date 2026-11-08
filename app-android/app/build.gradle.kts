plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

// versionCode：CI 里用 -PappVersionCode=<run_number> 传入；没传时读
// GITHUB_RUN_NUMBER 环境变量；本地都没有时默认 1。
val appVersionCode: Int =
    (project.findProperty("appVersionCode") as String?)?.toIntOrNull()
        ?: System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        ?: 1

// 正式签名：全部从环境变量读，仓库里不放任何密钥。
//   SIGNING_STORE_FILE     keystore 文件路径（CI 从 secret SIGNING_KEYSTORE_B64 解码出来）
//   SIGNING_STORE_PASSWORD / SIGNING_KEY_ALIAS / SIGNING_KEY_PASSWORD
// 没有设置时 release 不签名（CI 这时只编 debug 包）。
val signingStoreFile: String? = System.getenv("SIGNING_STORE_FILE")?.takeIf { it.isNotBlank() }

android {
    namespace = "com.xiaodian.ai"; compileSdk = 34
    defaultConfig {
        applicationId = "com.xiaodian.ai"; minSdk = 26; targetSdk = 34
        versionCode = appVersionCode
        versionName = "0.3.2"
    }
    signingConfigs {
        if (signingStoreFile != null) {
            create("release") {
                storeFile = file(signingStoreFile!!)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            // 暂不开 R8/minify：没有真机回归之前，先只做固定签名（见 README）。
            isMinifyEnabled = false
            if (signingStoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui:1.7.2")
    implementation("androidx.compose.material3:material3:1.3.0")
    implementation("androidx.compose.material:material-icons-core:1.7.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
