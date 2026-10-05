import java.util.Properties
import java.net.URI

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
    id("com.google.devtools.ksp")
}
val appVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val releaseStore = System.getenv("CTTRAINS_KEYSTORE_PATH")
val localSecrets = Properties().apply {
    val source = rootProject.file("local.properties")
    if (source.exists()) source.inputStream().use { load(it) }
}
val transitUrl = System.getenv("CTTRAINS_TRANSIT_BASE_URL") ?: localSecrets.getProperty("CTTRAINS_TRANSIT_BASE_URL", "")
if (transitUrl.isNotBlank()) {
    val endpoint = URI(transitUrl)
    require(endpoint.scheme == "https" && endpoint.host != null && endpoint.userInfo == null && endpoint.query == null && endpoint.fragment == null) { "Use an HTTPS transit base URL without credentials, query or fragment." }
}
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
android {
    namespace = "za.co.cttrains.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "za.co.cttrains.app"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("CTTRAINS_VERSION_CODE") ?: appVersion.getProperty("versionCode"))
            .toInt().also { require(it > 0) { "versionCode must be positive" } }
        versionName = System.getenv("CTTRAINS_VERSION_NAME") ?: appVersion.getProperty("versionName")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "TRANSIT_BASE_URL", quoted(transitUrl.trimEnd('/')))
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", quoted(System.getenv("CTTRAINS_GOOGLE_WEB_CLIENT_ID") ?: localSecrets.getProperty("CTTRAINS_GOOGLE_WEB_CLIENT_ID", "")))
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
    implementation("com.android.tools.build:apksig:8.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.9")
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")
    implementation("androidx.work:work-runtime-ktx:2.10.3")
    implementation("androidx.health.connect:connect-client:1.1.0")
    // Health Connect supplies Guava at runtime; WorkManager exposes its Future on the compile API.
    implementation("com.google.guava:guava:33.4.0-android")
    implementation("org.maplibre.gl:android-sdk:13.6.1")
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
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0") {
        // Only text extraction from unencrypted public timetables; no PDF certificate/EST clients.
        exclude(group="org.bouncycastle",module="bcpkix-jdk15to18")
    }
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
