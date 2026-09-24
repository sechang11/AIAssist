import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Prototype key plumbing. The key ends up inside the APK, where anyone can pull
// it out with `apktool`. Fine for a debug build on your own phone, not fine for
// anything you hand to another person. See "Before you ship" in README.md.
val anthropicApiKey: String = Properties().run {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
    getProperty("ANTHROPIC_API_KEY", "")
}

// Passed in by scratchpad/sync-build.sh, which counts the commits on the
// machine that has the git history. The build box holds an unpacked copy of the
// tree and no .git, and a version that does not climb makes the in-app updater
// useless: the phone compares these two numbers and nothing else.
val buildNumber: Int = (providers.gradleProperty("remixVersionCode").orNull ?: "1").toInt()
val buildName: String = providers.gradleProperty("remixVersionName").orNull ?: "0.1"

android {
    namespace = "com.aitextassistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aitextassistant"
        minSdk = 26          // java.time / Optional / Stream, used by anthropic-java
        targetSdk = 35
        versionCode = buildNumber
        versionName = buildName
        buildConfigField("String", "ANTHROPIC_API_KEY", "\"$anthropicApiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/INDEX.LIST",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.serialization.json)

    // Spring physics for the chat head: edge snap and fling.
    implementation(libs.androidx.dynamicanimation)

    // The one dependency that has not been proven on Android in this project.
    // It is a JVM SDK pulling in Jackson. If it fights the build, ClaudeVariantGenerator
    // is the only file that touches it - see README.md "If anthropic-java fights Android".
    implementation(libs.anthropic.java)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
}
