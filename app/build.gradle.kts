import java.util.Properties

plugins {
    id("com.android.application")
    id("com.google.gms.google-services")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

fun configValue(name: String): String {
    return (localProperties.getProperty(name) ?: System.getenv(name) ?: "").replace("\\", "\\\\").replace("\"", "\\\"")
}

android {
    namespace = "com.corider.tracker"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.corider.tracker"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "AGORA_APP_ID", "\"${configValue("AGORA_APP_ID")}\"")
        buildConfigField("String", "AGORA_TOKEN", "\"${configValue("AGORA_TOKEN")}\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:34.2.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-database")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")
    implementation("io.agora.rtc:voice-rtc-basic:4.6.3")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}
