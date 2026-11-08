plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

// versionCode：CI 里用 -PappVersionCode=<run_number> 传入；没传时读
// GITHUB_RUN_NUMBER 环境变量；本地都没有时默认 1。
val appVersionCode: Int =
    (project.findProperty("appVersionCode") as String?)?.toIntOrNull()
        ?: System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        ?: 1

android {
    namespace = "com.xiaodian.ai"; compileSdk = 34
    defaultConfig {
        applicationId = "com.xiaodian.ai"; minSdk = 26; targetSdk = 34
        versionCode = appVersionCode
        versionName = "0.2.0"
    }
    buildTypes { release { isMinifyEnabled = false } }
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
