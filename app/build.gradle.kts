plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "uz.ishtixon.scatdriver"
    compileSdk = 35

    defaultConfig {
        applicationId = "uz.ishtixon.scatdriver"
        minSdk = 24
        // targetSdk как у штатного (легаси): без гейта Android 14 на fullScreenIntent,
        // поэтому входящий заказ разворачивается в окно/heads-up без «поверх других окон»
        targetSdk = 30
        versionCode = 83
        versionName = "8.12"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")   // карта (тайлы OSM, как в штатном клиенте)
}
