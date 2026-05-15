import java.util.Properties

plugins {
    id("com.android.application")
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.isFile) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}

val mapsApiKey = providers.gradleProperty("MAPS_API_KEY")
    .orElse(providers.environmentVariable("MAPS_API_KEY"))
    .orElse(localProperties.getProperty("MAPS_API_KEY") ?: "YOUR_GOOGLE_MAPS_API_KEY")

android {
    namespace = "com.corider.tracker"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.corider.tracker"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey.get()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-maps:20.0.0")
}
