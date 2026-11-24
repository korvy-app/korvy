plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.androidx.baselineprofile)
}

// Startup speed: profiles recorded on a phone by the :baselineprofile module
// (./gradlew :app:generatePlayReleaseBaselineProfile). The startup profile lets R8 put startup code first in the
// dex files ("dex layout optimization"); the baseline profile is precompiled by Android at install.
baselineProfile {
    automaticGenerationDuringBuild = false
    dexLayoutOptimization = true
    mergeIntoMain = true
}

android {
    namespace = "app.korvy.launcher"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // Placeholder ID until the final name is chosen. It can't change after the first Play upload.
        applicationId = "app.korvy.launcher"
        // minSdk 26 (Android 8.0), same as Piano Notes: ~97% of active devices, adaptive icons, java.time.
        minSdk = 26
        targetSdk = 37
        versionCode = 25
        versionName = "0.11.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The assistant's llama.cpp engine. Phones only (64-bit ARM); the emulator isn't supported yet.
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DBUILD_SHARED_LIBS=ON",
                    "-DLLAMA_BUILD_COMMON=ON",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_TOOLS=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DLLAMA_BUILD_APP=OFF",
                    "-DLLAMA_OPENSSL=OFF",
                    "-DLLAMA_CURL=OFF",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_BACKEND_DL=ON",
                    "-DGGML_CPU_ALL_VARIANTS=ON",
                    "-DGGML_LLAMAFILE=OFF",
                )
            }
        }
    }
    ndkVersion = "30.0.16248370"
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
    packaging {
        // ggml picks the best CPU backend at runtime by loading it from the app's native library folder,
        // so the libraries must be extracted on install.
        jniLibs { useLegacyPackaging = true }
    }

    // Two builds from day one (REQUIREMENTS.md → Distribution):
    //   play: Google Play (the Pro unlock will use Play Billing here only)
    //   foss: F-Droid / GitHub, everything unlocked, no Google code at all
    flavorDimensions += "store"
    productFlavors {
        create("play") { dimension = "store" }
        create("foss") {
            dimension = "store"
            applicationIdSuffix = ".foss"
            versionNameSuffix = "-foss"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug") // real upload key later
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
    // Natural voices: sherpa-onnx (prebuilt, fetched by scripts/fetch-libs.sh) and tar.bz2 unpacking for voice packs.
    val sherpa = file("libs/sherpa-onnx-1.13.8.aar")
    if (!sherpa.exists()) throw GradleException("Missing ${sherpa.name}: run ./scripts/fetch-libs.sh")
    implementation(files(sherpa))
    implementation(libs.commons.compress)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Pro unlock: Play Billing exists only in the Play build. The F-Droid build has no Google code at all.
    "playImplementation"(libs.billing)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
