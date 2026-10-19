plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Chaque construction sur GitHub augmente le numéro de version,
// pour que la nouvelle version s'installe par-dessus l'ancienne (sans perdre la progression).
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.thomas.bibisaya"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.thomas.bibisaya"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        create("bibisaya") {
            storeFile = file("bibisaya.jks")
            storePassword = "bibisaya"
            keyAlias = "bibisaya"
            keyPassword = "bibisaya"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("bibisaya")
        }
        debug {
            signingConfig = signingConfigs.getByName("bibisaya")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
