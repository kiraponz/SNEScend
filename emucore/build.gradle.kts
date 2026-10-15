plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

// The emulation core: MesenCE (external/MesenCE, a git submodule) with the Android host in
// src/main/cpp. MesenCE has no CMake of its own; src/main/cpp/CMakeLists.txt builds it.
val coreDir = rootProject.file("external/MesenCE")

android {
    namespace = "com.kira3.ametrine.emucore"
    compileSdk {
        version = release(37)
    }

    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters.add("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    buildTypes {
        // The core is built -O3 in every build type (CMakeLists.txt): an unoptimized one runs games
        // far below full speed.
        debug {
            externalNativeBuild { cmake { arguments += "-DCMAKE_BUILD_TYPE=RelWithDebInfo" } }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Fail early with the fix, rather than deep inside CMake.
tasks.matching { it.name.startsWith("configureCMake") }.configureEach {
    // A local, so the action doesn't capture the build script (the configuration cache can't store it).
    val coreDir = coreDir
    doFirst {
        check(File(coreDir, "Core").isDirectory) { "external/MesenCE is missing: run `git submodule update --init`" }
    }
}

dependencies {
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
