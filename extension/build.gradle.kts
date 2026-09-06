plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "eu.kanade.tachiyomi.extension.zh.lightshelf"
    compileSdk = 36

    defaultConfig {
        applicationId = "eu.kanade.tachiyomi.extension.zh.lightshelf"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.4.3"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8) } }

dependencies {
    // Host implementations must never be bundled in the extension APK.
    // Put full Preference signatures before the extension library's reduced stubs.
    compileOnly("androidx.preference:preference:1.2.1")
    compileOnly("com.github.keiyoushi:extensions-lib:18a8e26be2") { isTransitive = false }
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.3.10")
    compileOnly("com.squareup.okhttp3:okhttp:5.3.2")
    compileOnly("io.reactivex:rxjava:1.3.8")
    compileOnly("org.jsoup:jsoup:1.22.1")
    compileOnly("com.github.null2264.injekt:injekt-core:4135455a2a")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.10")
    testImplementation("com.squareup.okhttp3:okhttp:5.3.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
    testImplementation("org.json:json:20240303")
}
