import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
    id("com.android.application")
}

kotlin {
    jvmToolchain(17)
    androidTarget()
    jvm("desktop")

    sourceSets {
        val desktopMain by getting
        androidMain.get().kotlin.srcDir("src/jvmShared/kotlin")
        desktopMain.kotlin.srcDir("src/jvmShared/kotlin")
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        }
        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.9.3")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
            implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")
            implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
            implementation("com.google.mlkit:translate:17.0.3")
            implementation("net.java.dev.jna:jna:5.13.0@aar")
            implementation("com.alphacephei:vosk-android:0.3.47")
            implementation("androidx.media3:media3-transformer:1.4.1")
            implementation("androidx.media3:media3-effect:1.4.1")
            implementation("androidx.media3:media3-common:1.4.1")
        }
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
            implementation("com.microsoft.onnxruntime:onnxruntime:1.19.2")
            implementation("com.alphacephei:vosk:0.3.45")
        }
    }
}

android {
    namespace = "com.zyntavo"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.zyntavo.editor"
        minSdk = 29
        targetSdk = 34
        versionCode = 4
        versionName = "3.1"
        ndk { abiFilters.add("arm64-v8a") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // FFmpeg ships as libffmpeg.so and must be unpacked on install so it can be run.
    packaging { jniLibs { useLegacyPackaging = true } }
    sourceSets["main"].jniLibs.srcDirs("src/androidMain/jniLibs")
}

compose.desktop {
    application {
        mainClass = "com.zyntavo.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Zyntavo"
            packageVersion = "3.1.0"
            includeAllModules = true
            windows { iconFile.set(project.file("src/desktopMain/resources/zyntavo.ico")) }
        }
    }
}
