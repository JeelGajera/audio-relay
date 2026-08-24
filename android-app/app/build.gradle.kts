plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Build-time git metadata, surfaced through BuildConfig and shown on the
 * About screen. Mirrors `windows-app/build.rs`. Falls back to "unknown"
 * rather than failing the build when git isn't available (a source tarball,
 * a shallow CI checkout without history).
 */
fun gitOutput(vararg args: String): String =
    providers.exec {
        commandLine("git", *args)
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().ifEmpty { "unknown" }

android {
    namespace = "com.audiorelay.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.audiorelay.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GIT_HASH", "\"${gitOutput("rev-parse", "--short=10", "HEAD")}\"")
        buildConfigField("String", "GIT_COMMIT_DATE", "\"${gitOutput("show", "-s", "--format=%cs", "HEAD")}\"")
        buildConfigField("String", "GITHUB_URL", "\"https://github.com/JeelGajera/audio-relay\"")
    }

    /**
     * Release signing is driven entirely by environment variables so no key
     * material ever enters the repository — see `docs/releasing.md` for the
     * secret names and the keytool invocation.
     *
     * When they're absent (any local build, any fork's CI) the config is
     * simply not created and the release variant builds unsigned, so
     * `assembleRelease` still works as a compile check. That's deliberate:
     * a build that fails without secrets would make the release variant
     * untestable for contributors.
     */
    val keystorePath: String? = System.getenv("ANDROID_KEYSTORE_PATH")
    val hasSigningConfig = !keystorePath.isNullOrBlank() && file(keystorePath).exists()

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.media)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
