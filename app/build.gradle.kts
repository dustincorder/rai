plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val gitSha: String = runCatching {
    providers.exec { commandLine("git", "rev-parse", "--short", "HEAD") }
        .standardOutput.asText.get()
        .trim()
}.getOrDefault("unknown")

val gitDirty: Boolean = runCatching {
    providers.exec { commandLine("git", "status", "--porcelain") }
        .standardOutput.asText.get()
        .isNotBlank()
}.getOrDefault(false)

val buildId: String = when {
    gitSha == "unknown" -> "unknown"
    gitDirty -> "$gitSha-dirty"
    else -> gitSha
}

android {
    namespace = "com.dustincorder.rai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dustincorder.rai"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "GIT_SHA", "\"$buildId\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        create("release") {
            val privateDir = file(System.getProperty("user.home") + "/.glazegram")
            val envFile = listOf(
                file(privateDir.absolutePath + "/.env"),
                file(privateDir.absolutePath + "/secrets.env"),
            ).firstOrNull { it.isFile }
            val values = if (envFile != null) {
                envFile.readLines()
                    .mapNotNull { line -> line.substringBefore('#').trim().takeIf { it.contains('=') } }
                    .associate { line -> line.substringBefore('=').trim() to line.substringAfter('=').trim().trim('"', '\'') }
            } else {
                emptyMap()
            }
            val storeFilePath = values["RELEASE_KEYSTORE"] ?: values["KEYSTORE_PATH"] ?:
                file(privateDir.absolutePath + "/release.keystore").takeIf { it.isFile }?.absolutePath
            if (!storeFilePath.isNullOrBlank() && values["RELEASE_KEY_ALIAS"] != null &&
                values["RELEASE_STORE_PASSWORD"] != null && values["RELEASE_KEY_PASSWORD"] != null
            ) {
                storeFile = file(storeFilePath)
                keyAlias = values["RELEASE_KEY_ALIAS"]
                storePassword = values["RELEASE_STORE_PASSWORD"]
                keyPassword = values["RELEASE_KEY_PASSWORD"]
            }
        }
    }

    buildTypes.getByName("release") {
        signingConfig = signingConfigs.getByName("release")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")

    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("dev.harrel:json-schema:1.9.1")
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")

    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
