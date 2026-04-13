plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.haq.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.haq.app"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    // Keep model assets out of compression so LiteRT can mmap them directly
    androidResources {
        noCompress += listOf("tflite", "onnx", "bin", "task")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)

    // Jetpack Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ── LiteRT / Gemma on-device inference ──────────────────────────────────
    // MediaPipe Tasks GenAI wraps LiteRT and exposes the LlmInference API
    // used to run Gemma 4 E4B .task files on-device.
    implementation("com.google.mediapipe:tasks-genai:0.10.22")

    // ── Whisper STT via ONNX Runtime ────────────────────────────────────────
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")

    // ── Room (SQLite) ────────────────────────────────────────────────────────
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // ── sqlite-vec (vector similarity search) ───────────────────────────────
    // sqlite-vec is loaded as a native SQLite extension at runtime via
    // SupportSQLiteOpenHelper. Add the prebuilt .aar to app/libs/ for each
    // target ABI once you compile from: https://github.com/asg017/sqlite-vec
    // implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    // ── ML Kit on-device OCR ─────────────────────────────────────────────────
    // Latin (form field labels) + Devanagari (Hindi documents)
    // Telugu/Tamil OCR: ML Kit does not yet ship Dravidian-script models;
    // revisit when available or use Tesseract via JNI as fallback.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")

    // ── Biometric (PIN + face fallback) ─────────────────────────────────────
    implementation("androidx.biometric:biometric:1.1.0")
}
