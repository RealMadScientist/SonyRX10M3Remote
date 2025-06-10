plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.example.sonyrx10m3remote"
    compileSdk = 33

    defaultConfig {
        applicationId = "com.example.sonyrx10m3remote"
        minSdk = 29
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.9.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
    implementation("org.jsoup:jsoup:1.15.4")
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.1")
    implementation("com.google.android.material:material:1.8.0")
    // For instrumented Android tests (androidTest)
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}