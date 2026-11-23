plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Firebase configuration is optional for local builds and forks.
if (file("google-services.json").isFile) apply(plugin = "com.google.gms.google-services")

android {
    namespace = "dev.cpa.android"
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "dev.cpa.android"
        minSdk = 33
        targetSdk = 37
        versionCode = providers.gradleProperty("cpaVersionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("cpaVersionName").orNull ?: "0.1.0"
        testInstrumentationRunner = "dev.cpa.android.LocalizedTestRunner"
    }
    if (providers.gradleProperty("cpaSplitArmApks").orNull == "true") {
        splits.abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = false
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("com.google.firebase:firebase-messaging:26.0.2")
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    // Full 25 MiB attachments exceed OkHttp WebSocket's fixed 16 MiB outbound queue.
    implementation("org.java-websocket:Java-WebSocket:1.6.0")
    implementation("org.commonmark:commonmark:0.32.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.32.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.5.1")
    testImplementation("com.squareup.okhttp3:okhttp-tls:5.5.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    // Compose's transitive 3.5.0 reflects a removed InputManager API on Android 17.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// A source-service fixture is ephemeral. Never reuse a previous skipped (or passed) live contract result.
tasks.withType<Test>().configureEach {
    val liveContract = providers.environmentVariable("CPA_CONTRACT_FIXTURE").map { it == "source-service" }.orElse(false)
    val liveTunnel = providers.environmentVariable("CPA_TUNNEL_FIXTURE").map { it == "source-services" }.orElse(false)
    inputs.property("liveTunnelContract", liveTunnel)
    inputs.property("liveDesktopContract", liveContract)
    outputs.upToDateWhen { !liveContract.get() && !liveTunnel.get() }
}
