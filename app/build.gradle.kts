import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    // No kotlin-android plugin: AGP 9 provides Kotlin support itself and rejects it.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Signing config is opt-in: without keystore.properties the release build still assembles
// (unsigned), so a fresh clone or CI checkout is not broken by the missing key.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

// Artifacts build as zipshare-<variant>.apk instead of the module-named app-<variant>.apk.
base.archivesName.set("zipshare")

// An unsigned release APK installs nowhere - Android rejects it with "There's a problem with the
// app file", which says nothing about the cause. Warn at the moment it is produced, since the
// build otherwise succeeds and looks fine. Only on release: debug is signed automatically.
tasks.matching { it.name == "assembleRelease" }.configureEach {
    doLast {
        if (keystoreProps.isEmpty()) {
            logger.warn(
                """
                |
                |  ============================================================
                |   WARNING: this release APK is UNSIGNED and cannot be installed.
                |   Android will report "There's a problem with the app file".
                |
                |   keystore.properties was not found in the project root. Either
                |   create it (storeFile / storePassword / keyAlias / keyPassword)
                |   or use the debug build instead:  ./gradlew assembleDebug
                |  ============================================================
                |
                """.trimMargin(),
            )
        }
    }
}

android {
    namespace = "dev.zipshare"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.zipshare"
        minSdk = 26
        targetSdk = 36
        // versionCode is what Android compares to decide an upgrade; versionName is only a label.
        // It must increase for an existing install to accept the new APK.
        versionCode = 8
        versionName = "1.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // v2 covers API 24+, which is everything minSdk 26 can run on, so AGP correctly
                // skips v1 (JAR) signing here regardless of this flag.
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // AGP 9 removed the kotlinOptions block along with the standalone Kotlin plugin; the JVM
    // target now goes through Kotlin's own compilerOptions.
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true

        // Name every test as it runs and print full stack traces on failure. Without this a
        // `gradlew testDebugUnitTest` says only "BUILD SUCCESSFUL", which tells a contributor
        // nothing about what ran - and on a failure, nothing about why.
        unitTests.all { test ->
            test.testLogging {
                events(TestLogEvent.PASSED, TestLogEvent.SKIPPED, TestLogEvent.FAILED)
                exceptionFormat = TestExceptionFormat.FULL
            }
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.biometric)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp)
}
