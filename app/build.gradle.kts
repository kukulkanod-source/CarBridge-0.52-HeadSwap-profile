plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.carbridge"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.carbridge.headswap"
        minSdk = 33
        targetSdk = 34
        versionCode = 2
        versionName = "0.52-headswap.2"
        testInstrumentationRunner = "com.example.carbridge.ProfileVerification"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
}
dependencies {
    implementation(project(":sdk"))
    testImplementation("junit:junit:4.13.2")
}
