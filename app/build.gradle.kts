// Fork modification (2026): release version and build configuration for this maintained derivative.
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.api.JavaVersion
import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val releaseSigningValues = listOf(
    providers.environmentVariable("KEYSTORE_PATH").orNull,
    providers.environmentVariable("KEYSTORE_PASSWORD").orNull,
    providers.environmentVariable("KEY_ALIAS").orNull,
    providers.environmentVariable("KEY_PASSWORD").orNull
)
val hasReleaseSigning = releaseSigningValues.all { !it.isNullOrBlank() }
require(releaseSigningValues.all { it.isNullOrBlank() } || hasReleaseSigning) {
    "Set KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS, and KEY_PASSWORD together to sign a release."
}
if (hasReleaseSigning) {
    require(File(releaseSigningValues.first()!!).isFile) { "KEYSTORE_PATH must point to a keystore file." }
}

val updateRepository = providers.environmentVariable("GITHUB_REPOSITORY")
    .orElse(providers.gradleProperty("updateRepository"))
    .orElse("")
    .get()
    .takeIf { it.matches(Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")) }
    .orEmpty()

android {
    namespace = "com.zeaze.tianyinwallpaper"
    compileSdk = 36

    sourceSets {
        getByName("main") {
            assets.directories.clear()
        }
    }

    defaultConfig {
        applicationId = "com.zeaze.tianyinwallpaper"
        minSdk = 24
        targetSdk = 36
        versionCode = 48
        versionName = "4.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "UPDATE_REPOSITORY", "\"$updateRepository\"")
    }

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseSigningValues[0]!!)
                storePassword = releaseSigningValues[1]
                keyAlias = releaseSigningValues[2]
                keyPassword = releaseSigningValues[3]
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.add("-Xcontext-parameters")
}

dependencies {
    implementation(libs.kyant.shapes)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material.ripple)

    implementation(libs.reorderable)

    implementation(libs.kyant.shapes)

    // UI 与 架构
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.compose.material)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.fragment)

    // 单元测试与安卓测试
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)

    // 工具类库
    implementation(libs.androidx.documentfile)
    implementation(libs.glide)
    implementation(libs.fastjson)
    implementation(libs.android.picker.common)
    implementation(libs.android.picker.wheel)

    // 响应式与网络
    implementation(libs.rxjava)
    implementation(libs.rxandroid)
    implementation(libs.okhttp)
    implementation(libs.backdrop)
    implementation(libs.xpopup)
    
    // SplashScreen
    implementation("androidx.core:core-splashscreen:1.0.1")

    // MediaPlayer-Extended - 用于视频光栅的帧精确seek
    implementation(libs.mediaplayer.extended)

    // 其他
    debugImplementation(libs.androidx.compose.ui.tooling)
}
