import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { alias(libs.plugins.kotlin.jvm) }

// Target Java 21 bytecode with whatever JDK runs Gradle (Android Studio's or the command line's),
// instead of requiring a separately installed JDK 21 toolchain.
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        allWarningsAsErrors.set(false)
    }
}

dependencies {
    testImplementation(libs.junit)
}
