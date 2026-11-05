plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "be.florent.soleil"
    compileSdk = 34

    defaultConfig {
        applicationId = "be.florent.soleil"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // Clé de signature fixe : chaque nouvelle version s'installe par-dessus l'ancienne
    signingConfigs {
        create("perso") {
            storeFile = file("perso.keystore")
            storePassword = "soleil123"
            keyAlias = "soleil"
            keyPassword = "soleil123"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("perso")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("perso")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
