plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.compose.compiler)
    publishing
    signing
}

dependencies {
    compileOnly(libs.plugin.api)
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

android {
    val packageName = "app.revanced.manager.plugin.downloader.play.store"

    namespace = packageName
    compileSdk = 35

    defaultConfig {
        applicationId = packageName
        minSdk = 26
        targetSdk = 35
        versionName = version.toString()
        versionCode = versionName!!.filter { it.isDigit() }.toInt()
    }

    buildTypes {
        release {
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )

            val keystoreFile = file("keystore.jks")
            signingConfig =
                if (keystoreFile.exists()) {
                    signingConfigs.create("release") {
                        storeFile = keystoreFile
                        storePassword = System.getenv("KEYSTORE_PASSWORD")
                        keyAlias = System.getenv("KEYSTORE_ENTRY_ALIAS")
                        keyPassword = System.getenv("KEYSTORE_ENTRY_PASSWORD")
                    }
                } else {
                    signingConfigs["debug"]
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

    buildFeatures {
        compose = true
        aidl = true
    }

    applicationVariants.all {
        outputs.all {
            this as com.android.build.gradle.internal.api.ApkVariantOutputImpl

            outputFileName = "${rootProject.name}-$version.apk"
        }
    }

    sourceSets["main"].aidl.srcDirs("src/main/aidl")
}

tasks {
    val assembleReleaseSignApk by registering {
        dependsOn("assembleRelease")

        val apk = layout.buildDirectory.file("outputs/apk/release/${rootProject.name}-$version.apk")

        inputs.file(apk).withPropertyName("input")
        outputs.file(apk.map { it.asFile.resolveSibling("${it.asFile.name}.asc") })

        doLast {
            signing {
                useGpgCmd()
                sign(*inputs.files.files.toTypedArray())
            }
        }
    }

    // Used by gradle-semantic-release-plugin.
    // Tracking: https://github.com/KengoTODA/gradle-semantic-release-plugin/issues/435.
    publish {
        dependsOn(assembleReleaseSignApk)
    }
}
