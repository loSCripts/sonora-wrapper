// Appli de test de Sensor Maps : un seul module, fabriqué par GitHub Actions.
plugins {
    id("com.android.application") version "9.4.1"
}

// Sur GitHub, le numéro du passage sert de numéro de version : chaque nouvel APK s'installe par-dessus l'ancien.
val numero = (System.getenv("NUMERO_VERSION") ?: "1").toInt()

android {
    namespace = "io.github.loscripts.sensormaps.test"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.loscripts.sensormaps.test"
        minSdk = 26
        targetSdk = 36
        versionCode = numero
        versionName = "test-$numero"
    }

    // Clé de test publique : elle ne protège rien, elle permet seulement d'installer
    // chaque nouvelle version par-dessus l'ancienne. La vraie appli aura sa propre clé, secrète.
    signingConfigs {
        create("test") {
            storeFile = file("cle-de-test-publique.jks")
            storePassword = "sensormaps"
            keyAlias = "test"
            keyPassword = "sensormaps"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("test")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.work:work-runtime:2.12.0")
    implementation("com.google.android.gms:play-services-location:21.4.0")
}
