plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.akula.watermarkremover"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.akula.watermarkremover"
        minSdk = 26
        targetSdk = 34
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.1")

    // FFmpeg для обработки видео (delogo фильтр).
    // ВАЖНО: официальный ffmpeg-kit (Arthenica) архивирован в 2025.
    // Возможные рабочие форки на текущий момент (проверь актуальность перед сборкой):
    //   implementation("com.arthenica:ffmpeg-kit-full-gpl:6.0-2")   // может быть недоступен
    //   implementation("io.github.smedic:ffmpeg-kit-full:6.0.3")     // community fork, пример
    // Если оба недоступны — см. альтернативу в README (сборка своего .aar
    // из https://github.com/ffmpeg-kit форков, либо media3 Transformer + свой native фильтр).
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7")
    implementation("com.arthenica:smart-exception-java:0.2.1")

    // Для "качественного" режима: нейросетевой inpainting (модель LaMa) на CPU.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")
}
