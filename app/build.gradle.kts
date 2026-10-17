plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.clamshellds"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.clamshellds"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")

        ndk {
            // RG DS, AYN Thor, Odin, Retroid etc. are all 64-bit ARM.
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static", "-DANDROID_ARM_NEON=TRUE")
            }
        }
    }

    signingConfigs {
        // A fixed key so every build installs over the previous one.
        create("clamshell") {
            storeFile = file("clamshell.keystore")
            storePassword = "clamshellds"
            keyAlias = "clamshell"
            keyPassword = "clamshellds"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("clamshell")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("clamshell")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs.useLegacyPackaging = false
    }
}
