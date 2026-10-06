import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    // kotlin support is built into agp 9 so the external kotlin plugin is dropped
}

// dynamic versioning: major.yeardayofyear.hourminute (example 3.26111.1430)
fun localVersionName(): String {
    val now = LocalDateTime.now()
    return "3.%02d%03d.%s".format(now.year % 100, now.dayOfYear, now.format(DateTimeFormatter.ofPattern("HHmm")))
}

// (3YYDDD) * 1440 + minute of day: monotonic intraday and fits a 32-bit int
fun versionCodeOf(name: String): Int {
    val (_, mid, hhmm) = name.split(".")
    val minuteOfDay = hhmm.substring(0, 2).toInt() * 60 + hhmm.substring(2).toInt()
    return ((3_00_000 + mid.toInt()) * 1440) + minuteOfDay
}

// on tag builds the workflow sets SE_RELEASE_VERSION so the apk version equals the tag
val releaseVersion: String? = System.getenv("SE_RELEASE_VERSION")?.trim()?.removePrefix("v")?.ifEmpty { null }
val buildVersionName = releaseVersion ?: localVersionName()
val buildVersionCode = runCatching { versionCodeOf(buildVersionName) }.getOrElse { versionCodeOf(localVersionName()) }

android {
    namespace = "me.rapierxbox.shellyelevatev2"
    compileSdk = 36

    defaultConfig {
        applicationId = "me.rapierxbox.shellyelevatev2"
        minSdk = 24
        //noinspection ExpiredTargetSdkVersion
        targetSdk = 28
        versionCode = buildVersionCode
        versionName = buildVersionName

        // the wall display is armeabi-v7a and arm64 devices can run it too
        ndk { abiFilters += "armeabi-v7a" }
        externalNativeBuild {
            cmake { cppFlags("-std=c++17") }
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    val hasReleaseKey = !System.getenv("SIGNING_KEYSTORE_BASE64").isNullOrEmpty()

    if (hasReleaseKey) {
        signingConfigs {
            create("release") {
                val keystoreFile = File.createTempFile("release_keystore_", ".keystore")
                    .also {
                        // owner-only read/write: createTempFile already restricts this but be explicit
                        it.setReadable(true, true)
                        it.setWritable(true, true)
                        // deleteOnExit covers cleanup; buildFinished is deprecated and
                        // breaks with the configuration cache
                        it.deleteOnExit()
                    }
                try {
                    keystoreFile.writeBytes(
                        Base64.getDecoder()
                            .decode(System.getenv("SIGNING_KEYSTORE_BASE64"))
                    )
                } catch (e: Exception) {
                    keystoreFile.delete()
                    throw e
                }
                storeFile = keystoreFile
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                    ?: error("SIGNING_STORE_PASSWORD is required when SIGNING_KEYSTORE_BASE64 is set")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                    ?: error("SIGNING_KEY_ALIAS is required when SIGNING_KEYSTORE_BASE64 is set")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
                    ?: error("SIGNING_KEY_PASSWORD is required when SIGNING_KEYSTORE_BASE64 is set")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

// kotlinOptions was removed in agp 9 so the jvm target moves to the kotlin dsl
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.lifecycle.runtime.ktx)
    // was transitive before the androidx bumps; declare it since we use it directly
    implementation(libs.localbroadcastmanager)
    implementation(libs.nanohttpd)
    implementation(libs.org.eclipse.paho.mqttv5.client)
    implementation(libs.webkit)
    implementation(libs.recyclerview)
    implementation(libs.dynamicanimation)
    implementation(libs.tensorflow.lite)
    implementation(libs.serialport)

    implementation(platform(libs.okhttpbom))
    implementation(libs.okhttp)

    testImplementation(libs.junit)
}