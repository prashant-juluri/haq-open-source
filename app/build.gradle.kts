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
        // Bump whenever law.db is regenerated — LawRepository re-copies on mismatch.
        buildConfigField("int", "LAW_DB_VERSION", "1")
        // Bump whenever offices.db is regenerated — OfficeRepository re-copies on mismatch.
        buildConfigField("int", "OFFICES_DB_VERSION", "4")
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
        // litertlm-android:0.10.0 was compiled with Kotlin 2.3; allow reading
        // its metadata without failing the build on version mismatch.
        freeCompilerArgs += listOf("-Xskip-metadata-version-check")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // .litertlm files must not be compressed so LiteRT can mmap them.
    // The model is NOT bundled in the APK (>2 GB); see push instructions below.
    androidResources {
        noCompress += listOf("litertlm")
    }
}

// ── Exclude model files from the APK ──────────────────────────────────────────
// Bundling a >2 GB model inside an APK is impractical. Instead, push the model
// directly to the device once and the app reads it from filesDir at runtime.
//
// One-time setup (replace the package path if you change applicationId):
//   adb push app/src/main/assets/gemma-4-E2B-it.litertlm \
//       /data/data/com.haq.app/files/gemma-4-E2B-it.litertlm
//
// This hook removes *.litertlm from the merged-assets output so that
// compressDebugAssets / compressReleaseAssets never see the file.
tasks.whenTaskAdded {
    if (name.startsWith("merge") && name.endsWith("Assets") && "Test" !in name) {
        doLast {
            outputs.files.forEach { dir ->
                if (dir.isDirectory) {
                    fileTree(dir) { include("**/*.litertlm") }.forEach { f ->
                        println("Haq build: excluding ${f.name} from APK — push via adb")
                        f.delete()
                    }
                }
            }
        }
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
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Lifecycle — ViewModel + viewModelScope + collectAsStateWithLifecycle
    val lifecycleVersion = "2.8.4"
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:$lifecycleVersion")

    // ── LiteRT-LM — on-device Gemma inference via .litertlm format ──────────
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.10.0")

    // ── Room (SQLite) ────────────────────────────────────────────────────────
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // ── sqlite-vec (loaded as native extension at runtime) ───────────────────
    // Add prebuilt .aar to app/libs/ per target ABI when ready
    // implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    // ── ML Kit on-device OCR ─────────────────────────────────────────────────
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")

    // ── Biometric (PIN + face fallback) ─────────────────────────────────────
    implementation("androidx.biometric:biometric:1.1.0")


    // ── OkHttp — one-time model download over WiFi ───────────────────────────
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ── WorkManager — keeps model download alive when app is backgrounded ────
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
