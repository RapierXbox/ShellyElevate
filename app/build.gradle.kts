import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoField

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

// Dynamic versioning: Major.YearDayOfYear.HourMinute (e.g., 3.26111.1430)
fun generateVersionCode(): Int {
    val now = LocalDateTime.now()
    val year = now.year % 100  // Last 2 digits of year
    val dayOfYear = now.dayOfYear
    // Format: 3YYDDD (e.g., 326111 for year 2026, day 111)
    return 3_00_000 + (year * 1000) + dayOfYear
}

fun generateVersionName(): String {
    val now = LocalDateTime.now()
    val year = now.year % 100  // Last 2 digits of year
    val dayOfYear = now.dayOfYear
    val hourMin = now.format(DateTimeFormatter.ofPattern("HHmm"))
    return "3.${year}${dayOfYear.toString().padStart(3, '0')}.${hourMin}"
}

android {
    namespace = "me.rapierxbox.shellyelevatev2"
    compileSdk = 35

    defaultConfig {
        applicationId = "me.rapierxbox.shellyelevatev2"
        minSdk = 24
        //noinspection ExpiredTargetSdkVersion
        targetSdk = 24
        versionCode = generateVersionCode()
        versionName = generateVersionName()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Use the default debug signing key for release to simplify installs
            signingConfig = signingConfigs.getByName("debug")
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

    implementation(platform(libs.okhttpbom))
    implementation(libs.okhttp)
    implementation(libs.appcompat)

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}