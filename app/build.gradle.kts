import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Reads keystore.properties when it exists, so a release build is signed with
// the real key. That file holds a password and is never committed, so a fresh
// clone still builds -- it just produces an unsigned release. See CONTRIBUTING.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "cat.doorman.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "cat.doorman.app"
        // 29 = Android 10. Below that, TYPE_ACCESSIBILITY_OVERLAY behaviour and
        // the scroll-delta reporting that Phase 5 depends on get unreliable.
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"

        resourceConfigurations += listOf("en", "ca", "es")
    }

    signingConfigs {
        if (keystoreProperties.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Shrinking stays off for now: the screen labels are resolved by
            // name from rules.json, and res/raw/keep.xml is the only thing
            // stopping them being stripped. Turning this on needs a release
            // build actually checked on a device first.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
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

    lint {
        // The app ships translated, so a hardcoded string in a layout is a bug,
        // not a style nit. Android lint has no equivalent check for Compose
        // text, so Compose strings rely on review until a detekt rule is added
        // in Phase 6 -- see docs note in README.
        error += listOf("HardcodedText", "SetTextI18n")
        // Warning rather than error: a contributor adding an English string
        // should not have their build fail before a translator gets to it.
        warning += listOf("MissingTranslation")
        abortOnError = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
