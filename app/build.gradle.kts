plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dk.daaseoel"
    compileSdk = 34

    defaultConfig {
        applicationId = "dk.daaseoel"
        minSdk = 30
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Rigtig org.json i stedet for Androids tomme stubs, sÃ¥ parseren kan testes pÃ¥ JVM'en.
    testImplementation("org.json:json:20240303")
}
