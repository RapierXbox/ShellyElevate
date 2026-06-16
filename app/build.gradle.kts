import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoField
import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

// Dynamic versioning: Major.YearDayOfYear.HourMinute (e.g., 3.26111.1430)
fun generateVersionCode(): Int {
    val now = LocalDateTime.now()
    val year = now.year % 100  // Last 2 digits of year
    val dayOfYear = now.dayOfYear
    val minuteOfDay = now.hour * 60 + now.minute  // 0..1439
    // (3YYDDD) * 1440 + minuteOfDay: monotonic intraday, fits a 32-bit int
    return ((3_00_000 + (year * 1000) + dayOfYear) * 1440) + minuteOfDay
}

fun generateVersionName(): String {
    val now = LocalDateTime.now()
    val year = now.year % 100  // Last 2 digits of year
    val dayOfYear = now.dayOfYear
    val hourMin = now.format(DateTimeFormatter.ofPattern("HHmm"))
    return "3.${year}${dayOfYear.toString().padStart(3, '0')}.${hourMin}"
}

// on tag builds the workflow sets SE_RELEASE_VERSION so the apk version equals the tag
val releaseVersion: String? = System.getenv("SE_RELEASE_VERSION")?.trim()?.removePrefix("v")?.ifEmpty { null }

fun versionNameForBuild(): String = releaseVersion ?: generateVersionName()

fun versionCodeForBuild(): Int {
    val v = releaseVersion ?: return generateVersionCode()
    return try {
        val parts = v.split(".")
        val mid = parts[1].toInt()              // YYDDD
        val hhmm = parts[2]
        val minuteOfDay = hhmm.substring(0, 2).toInt() * 60 + hhmm.substring(2).toInt()
        ((3_00_000 + mid) * 1440) + minuteOfDay
    } catch (e: Exception) {
        generateVersionCode()
    }
}

android {
    namespace = "me.rapierxbox.shellyelevatev2"
    compileSdk = 35

    defaultConfig {
        applicationId = "me.rapierxbox.shellyelevatev2"
        minSdk = 24
        //noinspection ExpiredTargetSdkVersion
        targetSdk = 24
        versionCode = versionCodeForBuild()
        versionName = versionNameForBuild()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
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
                        // Owner-only read/write (createTempFile already restricts, but be explicit)
                        it.setReadable(true, true)
                        it.setWritable(true, true)
                        it.deleteOnExit()
                    }
                // Register cleanup before decode/write so the file is always removed on build finish
                gradle.buildFinished { keystoreFile.delete() }
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
    kotlinOptions {
        jvmTarget = "17"
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

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.preference)
    implementation(libs.nanohttpd)
    implementation(libs.org.eclipse.paho.mqttv5.client)
    implementation(libs.webkit)
    implementation(libs.tensorflow.lite)
    implementation(libs.serialport)

    implementation(platform(libs.okhttpbom))
    implementation(libs.okhttp)
    implementation(libs.appcompat)

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}