plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.protectt.methodtrace")
}

android {
    namespace = "com.protectt.sdk"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

methodTrace {
    enabled = true
    reportApplicationId ="com.example.app"
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
}
