plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
}

group = "com.protectt.trace"
version = "2.0.0"

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    compileOnly("com.android.tools.build:gradle:8.5.2")

    // change this from compileOnly to implementation for easier client distribution
    implementation("org.ow2.asm:asm-commons:9.6")
}

gradlePlugin {
    plugins {
        create("protecttMethodTrace") {
            id = "com.protectt.methodtrace"
            implementationClass = "com.protectt.trace.MethodTracePlugin"
            displayName = "Protectt Method Trace Plugin"
            description = "ASM-based method timing instrumentation for Android modules"
        }
    }
}

publishing {
    repositories {
        maven {
            name = "localPluginRepo"
            url = uri("../repo")
        }
    }
}