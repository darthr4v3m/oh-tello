plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// The version this branch is working towards. CI parses this line, so keep it
// a plain string literal.
val baseVersionName = "0.1.0"

android {
    namespace = "io.github.darthr4v3m.ohtello"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.darthr4v3m.ohtello"
        minSdk = 24
        targetSdk = 35
        versionCode = 1

        // CI passes something like "pr1-27b04cd" here. A descriptive APK
        // filename only helps until the moment you install it; stamping the
        // same string into the version means an installed build can still say
        // where it came from, in Settings > Apps and in the app's own header.
        val buildLabel = providers.environmentVariable("OH_TELLO_BUILD_LABEL").getOrElse("")
        versionName = if (buildLabel.isEmpty()) baseVersionName else "$baseVersionName-$buildLabel"
    }

    signingConfigs {
        getByName("debug") {
            // CI decodes the DEBUG_KEYSTORE_BASE64 secret to this path before
            // building, so every CI build is signed with the same key and they
            // replace each other on a device rather than needing an uninstall
            // between them. The key is not in the repository.
            //
            // Locally the file is normally absent and AGP's own per-machine
            // debug key is used, which is why a local build and a CI build will
            // not install over one another. Drop a copy here if you want them to.
            val shared = file("debug.keystore")
            if (shared.exists()) {
                storeFile = shared
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        // For BuildConfig.VERSION_NAME, shown in the app header.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
