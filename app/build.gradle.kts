import dev.detekt.gradle.Detekt
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "social.waddle.android"
    compileSdk = 36

    signingConfigs {
        // Configured dynamically from env vars in CI:
        //  - RELEASE_KEYSTORE_PATH: absolute path to the decoded .keystore
        //  - RELEASE_KEYSTORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD
        // When any of these are missing we fall back to a throwaway keystore at
        // build/ci/waddle-ci-release.keystore so local CI smoke-tests still work.
        create("release") {
            val envKeystorePath = providers.environmentVariable("RELEASE_KEYSTORE_PATH").orNull
            val envStorePassword = providers.environmentVariable("RELEASE_KEYSTORE_PASSWORD").orNull
            val envKeyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").orNull
            val envKeyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD").orNull
            val hasRealKey =
                !envKeystorePath.isNullOrBlank() &&
                    !envStorePassword.isNullOrBlank() &&
                    !envKeyAlias.isNullOrBlank() &&
                    !envKeyPassword.isNullOrBlank()
            if (hasRealKey) {
                storeFile = file(envKeystorePath!!)
                storePassword = envStorePassword
                keyAlias = envKeyAlias
                keyPassword = envKeyPassword
            } else {
                storeFile =
                    rootProject.layout.buildDirectory
                        .file("ci/waddle-ci-release.keystore")
                        .get()
                        .asFile
                storePassword = "android"
                keyAlias = "waddle-ci-release"
                keyPassword = "android"
            }
        }
    }

    defaultConfig {
        applicationId = "social.waddle.android"
        minSdk = 26
        targetSdk = 36
        val baseVersionName = "0.1.0"
        val runNumber = providers.environmentVariable("VERSION_CODE").orNull?.toIntOrNull() ?: 1
        val versionSuffix = providers.environmentVariable("VERSION_SUFFIX").orNull
        versionCode = runNumber
        versionName =
            if (versionSuffix.isNullOrBlank()) {
                baseVersionName
            } else {
                "$baseVersionName+$versionSuffix"
            }
        testInstrumentationRunner = "social.waddle.android.WaddleTestRunner"

        manifestPlaceholders["appAuthRedirectScheme"] = "social.waddle.android"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            if (providers.environmentVariable("CI").isPresent) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                rootProject.file("proguard-rules.pro"),
            )
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    kotlin {
        compilerOptions {
            allWarningsAsErrors.set(true)
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=kotlin.RequiresOptIn",
                "-Xconsistent-data-class-copy-visibility",
            )
        }
    }

    lint {
        abortOnError = true
        checkDependencies = true
        htmlReport = true
        textReport = true
        warningsAsErrors = true
        xmlReport = true
        disable += "TrustAllX509TrustManager"
    }

    packaging {
        resources {
            excludes +=
                setOf(
                    "META-INF/AL2.0",
                    "META-INF/LGPL2.1",
                    "META-INF/INDEX.LIST",
                    "META-INF/io.netty.versions.properties",
                )
        }
    }
}

hilt {
    enableAggregatingTask = true
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
}

tasks.withType<Detekt>().configureEach {
    jvmTarget.set("17")
}

ktlint {
    android.set(true)
    version.set("1.8.0")
}

configurations.configureEach {
    exclude(group = "org.codelibs", module = "xpp3")
    exclude(group = "xpp3", module = "xpp3_min")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.biometric)
    implementation(libs.coil.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    implementation(libs.appauth)
    implementation(libs.hilt.android)
    implementation(libs.jsoup)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.smack.android)
    implementation(libs.smack.android.extensions)
    implementation(libs.smack.experimental)
    implementation(libs.smack.im)
    implementation(libs.smack.tcp)
    implementation(libs.smack.websocket.okhttp)

    ksp(libs.androidx.room.compiler)
    ksp(libs.androidx.hilt.compiler)
    ksp(libs.hilt.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)
}
