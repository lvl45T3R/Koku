plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val requestedAbis = providers.gradleProperty("kokuAbis")
    .orNull
    ?.split(',')
    ?.map(String::trim)
    ?.filter(String::isNotEmpty)
    ?: listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

val releaseStoreFile = System.getenv("KOKU_SIGNING_STORE_FILE")
val releaseStorePassword = System.getenv("KOKU_SIGNING_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("KOKU_SIGNING_KEY_ALIAS")
val releaseKeyPassword = System.getenv("KOKU_SIGNING_KEY_PASSWORD")

android {
    namespace = "io.github.lvl45t3r.koku"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.lvl45t3r.koku"
        minSdk = 24
        targetSdk = 35
        versionCode = 6
        versionName = "0.3.3"

        ndk {
            abiFilters += requestedAbis
        }
    }

    signingConfigs {
        create("release") {
            if (
                !releaseStoreFile.isNullOrBlank() &&
                !releaseStorePassword.isNullOrBlank() &&
                !releaseKeyAlias.isNullOrBlank() &&
                !releaseKeyPassword.isNullOrBlank()
            ) {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xjsr305=strict")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
