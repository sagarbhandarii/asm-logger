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
 /*   includePackagePrefixes = listOf("com/protectt/sdk")
    excludeClassPrefixes = listOf(
        "com/protectt/sdk/trace/MethodTraceRuntime",
        "com/protectt/sdk/BuildConfig",
        "com/protectt/sdk/R",
        "com/protectt/sdk/R$"
    )*/
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
