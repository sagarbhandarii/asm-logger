plugins {
    kotlin("jvm") version "1.9.24"
    `java-gradle-plugin`
}

group = "com.wrapper.inject"
version = "1.0.0"

repositories {
    google()
    mavenCentral()
}

dependencies {
    compileOnly("com.android.tools.build:gradle:8.5.2")
    implementation("org.ow2.asm:asm:9.6")
    implementation("org.ow2.asm:asm-commons:9.6")
}

gradlePlugin {
    plugins {
        create("wrapperInjectPlugin") {
            id = "com.wrapper.inject"
            implementationClass = "com.wrapper.inject.WrapperInjectPlugin"
            displayName = "Wrapper Inject Plugin"
            description = "Injects SecureWrapper.ensureInit(context) into app startup methods"
        }
    }
}

kotlin {
    jvmToolchain(17)
}
