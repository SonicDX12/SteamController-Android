import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Load signing config from keystore.properties (gitignored) if present.
// Falls back to env vars for CI builds. If neither is set, release builds are unsigned.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        FileInputStream(keystorePropsFile).use { load(it) }
    }
}

fun signingValue(propKey: String, envKey: String): String? =
    keystoreProps.getProperty(propKey) ?: System.getenv(envKey)

android {
    namespace = "com.steamcontroller.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.steamcontroller.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        viewBinding = true
        aidl = true
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            val storeFilePath = signingValue("storeFile", "SIGNING_STORE_FILE")
            val storePass     = signingValue("storePassword", "SIGNING_STORE_PASSWORD")
            val alias         = signingValue("keyAlias", "SIGNING_KEY_ALIAS")
            val keyPass       = signingValue("keyPassword", "SIGNING_KEY_PASSWORD")

            if (storeFilePath != null && storePass != null && alias != null && keyPass != null) {
                storeFile = rootProject.file(storeFilePath)
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val cfg = signingConfigs.getByName("release")
            // Only attach the signing config if it was actually populated above.
            if (cfg.storeFile != null) {
                signingConfig = cfg
            }
        }
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.kotlinx.coroutines.android)
}
