import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose)
    alias(libs.plugins.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/** Optional overrides from local.properties or environment; nothing secret is required by default. */
val local = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
fun setting(key: String, env: String): String = (local.getProperty(key) ?: System.getenv(env) ?: "").replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "org.southtyrol.transit"
    compileSdk { version = release(37) { minorApiLevel = 1 } }

    defaultConfig {
        applicationId = "org.southtyrol.transit"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "MAP_STYLE_LIGHT", "\"${setting("map.styleUrl", "TRANSIT_MAP_STYLE_URL")}\"")
        buildConfigField("String", "MAP_STYLE_DARK", "\"${setting("map.styleUrlDark", "TRANSIT_MAP_STYLE_URL_DARK")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.maxHeapSize = "3g" }
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = true
        xmlReport = false
        htmlReport = true
        // Version-bump suggestions are handled deliberately via the version catalog.
        disable += setOf("NewerVersionAvailable", "GradleDependency", "AndroidGradlePluginVersion", "OldTargetApi")
        // Ladin intentionally falls back to German via the locale list (lld,de,it); en/de/it
        // completeness is enforced by TranslationsTest instead.
        warning += "MissingTranslation"
    }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:map"))

    implementation(libs.activity)
    implementation(libs.appcompat)
    implementation(libs.core.ktx)
    implementation(libs.splashscreen)
    implementation(libs.lifecycle.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.process)
    implementation(libs.navigation)
    implementation(libs.serialization.json)
    implementation(libs.material3.navigation.suite)
    implementation(libs.adaptive)
    implementation(libs.okhttp)

    implementation(libs.hilt)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)
    implementation(libs.work)

    debugImplementation(libs.compose.tooling)
    debugImplementation(libs.compose.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.test)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.test.core)
    testImplementation(libs.work.testing)

    androidTestImplementation(libs.compose.test)
    androidTestImplementation(libs.android.test)
    androidTestImplementation(libs.test.runner)
}
