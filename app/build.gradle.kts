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
fun raw(key: String, env: String): String = local.getProperty(key) ?: System.getenv(env) ?: ""
/** Escaped for use inside a BuildConfig string literal. */
fun setting(key: String, env: String): String = raw(key, env).replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "org.southtyrol.transit"
    compileSdk { version = release(37) { minorApiLevel = 1 } }

    defaultConfig {
        applicationId = "org.southtyrol.transit"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "0.1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "MAP_STYLE_LIGHT", "\"${setting("map.styleUrl", "TRANSIT_MAP_STYLE_URL")}\"")
        buildConfigField("String", "MAP_STYLE_DARK", "\"${setting("map.styleUrlDark", "TRANSIT_MAP_STYLE_URL_DARK")}\"")
        // GitHub "owner/repo" whose latest release is offered as an in-app update ("none" disables it).
        buildConfigField("String", "UPDATE_REPO", "\"${setting("update.repo", "TRANSIT_UPDATE_REPO").ifBlank { "ta-samuelece/SouthTyrolTransit" }}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // Optional release signing from local.properties or environment (never committed). In-app updates
    // only install when every release is signed with the same key, so keep that keystore safe.
    val releaseStore = raw("release.storeFile", "TRANSIT_RELEASE_STORE_FILE")
    signingConfigs {
        if (releaseStore.isNotBlank()) create("release") {
            storeFile = rootProject.file(releaseStore)
            storePassword = raw("release.storePassword", "TRANSIT_RELEASE_STORE_PASSWORD")
            keyAlias = raw("release.keyAlias", "TRANSIT_RELEASE_KEY_ALIAS")
            keyPassword = raw("release.keyPassword", "TRANSIT_RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
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
