plugins { alias(libs.plugins.kotlin.jvm) }

kotlin {
    jvmToolchain(21)
    compilerOptions { allWarningsAsErrors.set(false) }
}

dependencies {
    testImplementation(libs.junit)
}
