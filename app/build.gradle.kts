import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val buildNative = providers.gradleProperty("lian.buildNative").getOrElse("true").toBoolean()
val abis = providers.gradleProperty("lian.abiFilters").getOrElse("arm64-v8a")
    .split(",").map { it.trim() }.filter { it.isNotEmpty() }

// Optional release signing: drop a keystore.properties next to this file (gitignored).
/**
 * The version comes from the release tag, so an update is always a higher
 * versionCode than the build it replaces. Android refuses to install over a
 * package whose versionCode is not greater, so leaving this hardcoded would
 * mean every release after the first had to be installed by hand.
 *
 * 0.2.3 becomes 20003: two digits each for minor and patch leaves room without
 * the number ever having to go backwards.
 */
val appVersionName = providers.gradleProperty("lian.versionName").getOrElse("0.1.0")

val appVersionCode = Regex("""^(\d+)\.(\d+)\.(\d+)""").find(appVersionName)
    ?.destructured
    ?.let { (major, minor, patch) ->
        major.toInt() * 100_000 + minor.toInt() * 1_000 + patch.toInt()
    }
    ?: error("lian.versionName must look like 1.2.3, got '$appVersionName'")

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.lian.plus"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.lian.plus"
        minSdk = 29
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk { abiFilters += abis }

        externalNativeBuild {
            cmake {
                // NEON/dotprod/i8mm are the instructions that make llama.cpp fast on
                // Snapdragon 8 Elite / Exynos 2500 class cores. armv8.2-a+dotprod+i8mm
                // is safe for every arm64 phone from ~2019 onwards; runtime checks in
                // ggml still guard the actual kernel selection.
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DLIAN_BUILD_NATIVE=${if (buildNative) "ON" else "OFF"}"
                )
                cppFlags += listOf("-O3", "-fvisibility=hidden", "-fvisibility-inlines-hidden")
            }
        }
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProps.containsKey("storeFile")) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            // Uncompressed and page-aligned inside the APK, so the loader can
            // map the engines straight from it instead of extracting a second
            // copy to the data partition.
            useLegacyPackaging = false
        }
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.ExperimentalStdlibApi")
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    androidResources {
        generateLocaleConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.documentfile)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
