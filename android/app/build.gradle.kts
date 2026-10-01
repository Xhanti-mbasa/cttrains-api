import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
}
val appVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val releaseStore = System.getenv("CTTRAINS_KEYSTORE_PATH")
android {
    namespace = "za.co.cttrains.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "za.co.cttrains.app"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("CTTRAINS_VERSION_CODE") ?: appVersion.getProperty("versionCode"))
            .toInt().also { require(it > 0) { "versionCode must be positive" } }
        versionName = System.getenv("CTTRAINS_VERSION_NAME") ?: appVersion.getProperty("versionName")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (!releaseStore.isNullOrBlank()) {
            create("production") {
                storeFile = file(releaseStore)
                storePassword = requireNotNull(System.getenv("CTTRAINS_KEYSTORE_PASSWORD"))
                keyAlias = requireNotNull(System.getenv("CTTRAINS_KEY_ALIAS"))
                keyPassword = requireNotNull(System.getenv("CTTRAINS_KEY_PASSWORD"))
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = when {
                !releaseStore.isNullOrBlank() -> signingConfigs.getByName("production")
                // Explicit opt-in for installable GitHub prereleases; never a production key.
                System.getenv("CTTRAINS_USE_DEVELOPMENT_SIGNING") == "true" -> signingConfigs.getByName("debug")
                else -> null
            }
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.datastore:datastore-preferences:1.1.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
