import java.util.Properties
import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val props = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use(::load)
    listOf(
        "DSHMOBILE_KEYSTORE_B64", "DSHMOBILE_STORE_PASS", "DSHMOBILE_KEY_PASS", "DSHMOBILE_KEY_ALIAS"
    ).forEach { k -> System.getenv(k)?.let { setProperty(k, it) } }
}

android {
    namespace = "com.dshio.dshmobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dshio.dshmobile"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        // pinned to the NDK version installed by setup-android in CI; without
        // this AGP tries to auto-download its default NDK (license prompt → fail)
        ndkVersion = "26.3.11579264"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        create("release") {
            val b64 = props.getProperty("DSHMOBILE_KEYSTORE_B64")
            if (b64 != null) {
                val f = File("${rootProject.buildDir}/keystore/dshmobile-release.keystore")
                f.parentFile.mkdirs()
                f.writeBytes(Base64.getDecoder().decode(b64))
                storeFile = f
                storePassword = props.getProperty("DSHMOBILE_STORE_PASS")
                keyAlias = props.getProperty("DSHMOBILE_KEY_ALIAS")
                keyPassword = props.getProperty("DSHMOBILE_KEY_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")?.takeIf {
                it.storeFile?.exists() == true
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    androidResources {
        // AGP compresses assets unless listed here; .xz/.gz are already
        // compressed and double compression breaks openFd() + bloats the APK.
        noCompress += listOf("xz", "gz")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("com.github.termux.termux-app:terminal-view:v0.118.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
