plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}

val separateInstall = providers.gradleProperty("saoreoSeparateInstall").orNull == "true"
val selectedAbi = providers.gradleProperty("saoreoAbi").orNull
val packagedAbis = selectedAbi?.let(::listOf) ?: listOf("arm64-v8a", "x86_64")
require(selectedAbi == null || selectedAbi in setOf("arm64-v8a", "x86_64")) { "Unsupported SAOREO ABI" }

android {
    namespace = "com.codetic.saoreo"
    compileSdk = 36
    defaultConfig {
        applicationId = if (separateInstall) "com.codetic.saoreo.music" else "com.codetic.saoreo"
        manifestPlaceholders["saoreoLabel"] = "SAOREO"
        minSdk = 33
        targetSdk = 36
        versionCode = 9
        versionName = "2.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += packagedAbis }
        javaCompileOptions {
            annotationProcessorOptions {
                arguments += mapOf("room.schemaLocation" to "$projectDir/schemas")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { jniLibs.useLegacyPackaging = true }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    doFirst {
        for (abi in packagedAbis) {
            check(file("src/main/jniLibs/$abi/libsaoreomp3.so").isFile) {
                "Offline encoder for $abi is missing. Run tools/build-offline-encoder.sh ANDROID_NDK_HOME $abi."
            }
            check(file("src/main/jniLibs/$abi/libsaoreowhisper.so").isFile) {
                "Whisper for $abi is missing. Run tools/build-whisper.sh ANDROID_NDK_HOME $abi."
            }
        }
    }
}

kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas") } }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.room:room-runtime:2.8.3")
    implementation("androidx.room:room-ktx:2.8.3")
    kapt("androidx.room:room-compiler:2.8.3")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.work:work-runtime-ktx:2.11.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250107")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.3.0")
}
