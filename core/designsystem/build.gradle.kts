plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose)
}

android {
    namespace = "org.southtyrol.transit.design"
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    api(project(":core:model"))
    api(libs.material3)
    api(libs.material.icons)
    api(libs.compose.ui)
    api(libs.compose.foundation)
    api(libs.compose.preview)
    implementation(libs.core.ktx)
    debugImplementation(libs.compose.tooling)
}
