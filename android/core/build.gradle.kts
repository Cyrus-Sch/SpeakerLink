import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin module: speaker catalog, identification, party planning and the JBL control
// protocol codec. No Android dependencies, so it is unit-tested on the plain JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // Compile against the Java 17 API even on a newer JDK (F-Droid builds with JDK 21).
        freeCompilerArgs.add("-Xjdk-release=17")
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
}

tasks.test {
    useJUnit()
}
