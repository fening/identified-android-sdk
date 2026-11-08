plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("maven-publish")
    id("signing")
}
// Coordinates a developer puts in Gradle: cc.identified:identified-sdk:<version>. The git tag for a
// release must match `version`. `cc.identified` is the Central Portal namespace verified through
// the identified.cc DNS TXT record. JitPack ignores `group` and serves com.github.fening instead.
group = "cc.identified"
version = "0.1.1"
android {
    namespace = "cc.identified.sdk"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
    lint { abortOnError = true }
    publishing { singleVariant("release") { withSourcesJar(); withJavadocJar() } }
}
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("org.opencv:opencv:4.14.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "identified-sdk"
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Identified Android SDK")
                description.set("Native Android identity verification flow for the Identified API")
                url.set("https://identified.cc")
                inceptionYear.set("2026")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/license/mit")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("fening")
                        name.set("Kwadwo Fening Okai")
                        url.set("https://github.com/fening")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/fening/identified-android-sdk.git")
                    developerConnection.set("scm:git:ssh://git@github.com/fening/identified-android-sdk.git")
                    url.set("https://github.com/fening/identified-android-sdk")
                }
                issueManagement {
                    system.set("GitHub")
                    url.set("https://github.com/fening/identified-android-sdk/issues")
                }
            }
        }
    }
    repositories {
        // Local staging only. Nothing here uploads anywhere: scripts/publish-central.sh zips this
        // directory and uploads it to the Central Portal as a bundle the owner then releases.
        maven {
            name = "releaseStaging"
            url = uri(layout.buildDirectory.dir("release-repository"))
        }
    }
}

// Signing is opt-in so JitPack and `publishToMavenLocal` keep working without keys.
// Enable with `-Psigning.gnupg.keyName=<key id>` (or the same line in ~/.gradle/gradle.properties).
// Signs through the local gpg agent, so the passphrase is only ever typed into pinentry.
val signingKeyName = providers.gradleProperty("signing.gnupg.keyName")
if (signingKeyName.isPresent) {
    signing {
        useGpgCmd()
        sign(publishing.publications["release"])
    }
}
