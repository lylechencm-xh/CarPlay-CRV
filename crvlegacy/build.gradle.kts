plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.shihab.diplay.crv"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.shihab.diplay.crv"
        minSdk = 19
        targetSdk = 19
        versionCode = 1
        versionName = "0.1-crv"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
}
