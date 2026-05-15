plugins {
    id("com.android.application")
    id("com.google.gms.google-services")
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
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:34.2.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-database")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}
