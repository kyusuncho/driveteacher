plugins {
    // AGP 9 compiles Kotlin itself; no kotlin-android plugin needed.
    id("com.android.application") version "9.1.0"
}

android {
    namespace = "com.kyusun.driveteacher"
    compileSdk = 34
    useLibrary("android.car") // CarPropertyManager / CarUxRestrictionsManager (on BOOTCLASSPATH at runtime)

    defaultConfig {
        applicationId = "com.kyusun.driveteacher"
        minSdk = 28 // Pleos requirement (signing key rotation)
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5") // road speed limit, cameras, caution points
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1") // Mode.ON_DEVICE needs no Playground credentials
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:rules:1.7.0")
}
