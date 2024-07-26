plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    id("kotlin-parcelize")
}

android {
    namespace = "app.revanced.manager.plugin.downloader.play.store"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.revanced.manager.plugin.downloader.play.store"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            if (project.hasProperty("signAsDebug")) {
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    composeOptions.kotlinCompilerExtensionVersion = "1.5.10"
    buildFeatures {
        compose = true
        aidl = true
    }
}

dependencies {
    compileOnly(libs.manager.plugin.api)

    implementation(libs.gplayapi)
    implementation(libs.arsclib)

    implementation(libs.ktor.core)
    implementation(libs.ktor.logging)
    implementation(libs.ktor.okhttp)

    implementation(libs.compose.activity)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling)
    implementation(libs.compose.material3)
    implementation(libs.compose.webview)
}