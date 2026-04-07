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
    implementation("org.ow2.asm:asm-commons:9.6")
    testImplementation(kotlin("test"))
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
