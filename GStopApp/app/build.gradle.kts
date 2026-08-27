import java.util.Properties

/**
 * Release signing. keystore.properties and the .jks it points at are deliberately outside version
 * control: the same key must sign every release, or Obtainium cannot install an update over an
 * existing install. Back both files up.
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseKeystore = keystoreProperties.getProperty("storeFile") != null &&
    rootProject.file(keystoreProperties.getProperty("storeFile")).exists()

/**
 * Which commit a build came from, read here rather than passed in, so it is right however the
 * build was started — release.ps1, dev.ps1 or a bare gradlew. Failures are not fatal: a source
 * tree with no git around it still builds, and simply says "unknown".
 */
fun git(vararg args: String): String = try {
    val process = ProcessBuilder(listOf("git", *args))
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    if (process.waitFor() == 0) output else ""
} catch (e: Exception) {
    ""
}

val gitSha: String = git("rev-parse", "--short", "HEAD").ifEmpty { "unknown" }

/** True if the build carries changes that are not in that commit, so the sha alone would lie. */
val gitDirty: Boolean = git("status", "--porcelain").isNotEmpty()

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.gstop"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gstop"
        minSdk = 26
        targetSdk = 35
        // release.ps1 passes these in; the defaults are for local builds.
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Read by the footer on the main screen, which links to this commit on GitHub.
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("boolean", "GIT_DIRTY", "$gitDirty")
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // Lets a debug build sit alongside an installed release build.
            applicationIdSuffix = ".debug"
            // So Android's own app list says which build is on the phone, without opening it.
            versionNameSuffix = "-g$gitSha"
        }
        release {
            // Kept off so crash traces stay readable — there is nothing here worth obfuscating.
            isMinifyEnabled = false
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // For BuildConfig.DEBUG, which the dev-build banner keys off. Off by default since AGP 8.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.3")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    // The three stop photographs. Bound to the stop screen, never to a service.
    val cameraX = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    // CameraX records the rotation in EXIF rather than rotating the pixels.
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Backups are written through the folder the user picked in the system picker.
    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // The android.jar used by unit tests stubs org.json; the real thing lets BackupCodec run.
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
