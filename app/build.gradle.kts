plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ytcache.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ytcache.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.2.0"
        vectorDrawables.useSupportLibrary = true
    }

    val releaseKeystore = rootProject.file("keystore/ytcache.jks")
    signingConfigs {
        if (releaseKeystore.exists()) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = "ytcache"
                keyAlias = "ytcache"
                keyPassword = "ytcache"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ""
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/*.version", "META-INF/LICENSE*", "kotlin/**")
    }

    testOptions {
        unitTests.all {
            it.testLogging {
                events("passed", "failed", "skipped")
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.webkit:webkit:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
