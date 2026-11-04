plugins { id("com.android.library") }
android {
    namespace = "com.headswap.desk.component.api"
    compileSdk = 34
    defaultConfig { minSdk = 33 }
    buildFeatures { aidl = true; buildConfig = false }
    testOptions { unitTests.isReturnDefaultValues = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
dependencies { testImplementation("junit:junit:4.13.2") }
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
