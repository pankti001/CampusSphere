import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val releaseApiUrl = providers.gradleProperty("campusSphereReleaseApiUrl")
    .orElse("https://campussphere.invalid/")
    .map { value ->
        val uri = URI(value)
        require(uri.scheme == "https" && uri.host != null && uri.userInfo == null &&
                uri.query == null && uri.fragment == null && uri.path.endsWith("/")) {
            "campusSphereReleaseApiUrl must be an HTTPS URL ending in /"
        }
        uri.toASCIIString()
    }

android {
    namespace = "com.campussphere.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.campussphere.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            // Phone or emulator: run "adb reverse tcp:8080 tcp:8080" so 127.0.0.1:8080 reaches the laptop's backend.
            buildConfigField("String", "API_BASE_URL", "\"http://127.0.0.1:8080/\"")
        }
        release {
            buildConfigField("String", "API_BASE_URL", "\"${releaseApiUrl.get()}\"")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    // Jetpack Compose Runtime
    implementation("androidx.compose.runtime:runtime")
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    // ViewModel unit tests: replaces the Android main dispatcher with a test dispatcher.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // Retrofit - API communication
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")

// OkHttp - HTTP client and logging
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

// DataStore - Secure token storage
    implementation("androidx.datastore:datastore-preferences:1.1.7")

// Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

// Lifecycle ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("com.squareup.retrofit2:converter-scalars:2.11.0")
}
