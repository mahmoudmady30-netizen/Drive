plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.drivevoice.mvp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.drivevoice.mvp"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "1.2.1"
    }

    val releaseStoreFilePath = providers.gradleProperty("DRIVEVOICE_STORE_FILE").orNull
    val releaseStorePassword = providers.gradleProperty("DRIVEVOICE_STORE_PASSWORD").orNull
    val releaseKeyAlias = providers.gradleProperty("DRIVEVOICE_KEY_ALIAS").orNull
    val releaseKeyPassword = providers.gradleProperty("DRIVEVOICE_KEY_PASSWORD").orNull
    val releaseSigningConfigured = !releaseStoreFilePath.isNullOrBlank() &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank() &&
        file(releaseStoreFilePath).exists()

    if (releaseSigningConfigured) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.maplibre.gl:android-sdk:13.6.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.car.app:app-testing:1.7.0")
}
