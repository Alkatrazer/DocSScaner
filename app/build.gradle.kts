import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.aboutLibrariesAndroid)
    alias(libs.plugins.kotlin.serialization)
}

val abiCodes = mapOf(
    "arm64-v8a" to 0,
    "armeabi-v7a" to -1,
    "x86_64" to -2,
)

val releaseSigningPropertiesFile = rootProject.file("release-signing.properties")
val releaseSigningProperties = Properties().apply {
    if (releaseSigningPropertiesFile.isFile) {
        releaseSigningPropertiesFile.inputStream().use(::load)
    }
}

fun releaseSigningValue(name: String): String? =
    providers.gradleProperty(name).orNull
        ?: providers.environmentVariable(name).orNull
        ?: releaseSigningProperties.getProperty(name)

val releaseSigning = mapOf(
    "RELEASE_STORE_FILE" to releaseSigningValue("RELEASE_STORE_FILE"),
    "RELEASE_STORE_PASSWORD" to releaseSigningValue("RELEASE_STORE_PASSWORD"),
    "RELEASE_KEY_ALIAS" to releaseSigningValue("RELEASE_KEY_ALIAS"),
    "RELEASE_KEY_PASSWORD" to releaseSigningValue("RELEASE_KEY_PASSWORD"),
)
val hasReleaseSigning = releaseSigning.values.all { !it.isNullOrBlank() }
val isReleaseBuildRequested = gradle.startParameter.taskNames.any {
    it.contains("release", ignoreCase = true)
}

if (isReleaseBuildRequested && !hasReleaseSigning) {
    throw GradleException(
        "Release signing is not configured. Copy release-signing.properties.example " +
            "to release-signing.properties and fill in all RELEASE_* values."
    )
}

android {
    namespace = "ru.alkatrazer.docscaner"
    compileSdk = 36
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/assets"))

    defaultConfig {
        applicationId = "ru.alkatrazer.docscaner"
        // Based on tests against virtual devices, the app works with Android 8.0 (API level 26).
        // It crashes because of LiteRT on earlier versions.
        // LiteRT documentation only states that version 1.2.0 requires Android 12:
        // https://ai.google.dev/edge/litert/android/index
        minSdk = 26
        targetSdk = 36
        versionCode = 90 // increment by 3 because of ABI-specific APKs
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(requireNotNull(releaseSigning["RELEASE_STORE_FILE"]))
                storePassword = releaseSigning["RELEASE_STORE_PASSWORD"]
                keyAlias = releaseSigning["RELEASE_KEY_ALIAS"]
                keyPassword = releaseSigning["RELEASE_KEY_PASSWORD"]
            }
        }
    }

    buildTypes {
        release {
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

    // See https://developer.android.com/build/configure-apk-splits
    val isBuildingBundle = gradle.startParameter.taskNames.any { it.lowercase().contains("bundle") }
    splits {
        abi {
            // Disable split ABIs when building appBundle: https://issuetracker.google.com/issues/402800800
            isEnable = !isBuildingBundle
            reset()
            include(*abiCodes.keys.toTypedArray())
            isUniversalApk = true
        }
    }
    applicationVariants.all {
        val variant = this
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output ->
                val abi = output.getFilter("ABI")
                output.outputFileName = "DocSScaner-${variant.versionName}-${abi ?: "universal"}.apk"
            }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

apply(from = file("download-tflite.gradle.kts"))

dependencies {

    implementation(project(":imageprocessing")) {
        exclude(group = "org.openpnp", module = "opencv")
    }

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.litert)
    implementation(libs.litert.support)
    implementation(libs.litert.metadata)
    implementation(libs.opencv)
    implementation(libs.pdfbox) {
        // To reduce APK size
        exclude("org.bouncycastle")
    }
    implementation(libs.icons.extended)
    implementation(libs.zoomable)
    implementation(libs.reorderable)
    implementation(libs.aboutlibraries.compose.m3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.tesseract4android)

    testImplementation(libs.junit)
    testImplementation(libs.assertj)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

aboutLibraries {
    library {
        // Enable the duplication mode, allows to merge, or link dependencies which relate
        duplicationMode = com.mikepenz.aboutlibraries.plugin.DuplicateMode.MERGE
        // Configure the duplication rule, to match "duplicates" with
        duplicationRule = com.mikepenz.aboutlibraries.plugin.DuplicateRule.SIMPLE
    }
}

// See https://developer.android.com/build/configure-apk-splits
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val name = output.filters.find { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }?.identifier
            val baseAbiCode = abiCodes[name]
            if (baseAbiCode != null) {
                output.versionCode.set(output.versionCode.get() + baseAbiCode)
            }
        }
    }
}
