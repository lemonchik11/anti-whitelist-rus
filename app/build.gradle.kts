plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "dev.yakovsava.antiwhitelist"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.yakovsava.antiwhitelist"
        minSdk = 26; targetSdk = 35
        versionCode = 1; versionName = "1.0.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = true; isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
    packaging { jniLibs { keepDebugSymbols += "**/libwg-go.so" }
    resources {
        excludes += setOf(
            "META-INF/versions/9/OSGI-INF/**",
            "META-INF/MANIFEST.MF",
            "META-INF/*.kotlin_module",
            "META-INF/INDEX.LIST",
        )
        }
    }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.wireguard.android:tunnel:1.0.20230706")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    implementation("org.bouncycastle:bctls-jdk18on:1.80")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.80")
    implementation("com.fasterxml.uuid:java-uuid-generator:4.3.0")
}
