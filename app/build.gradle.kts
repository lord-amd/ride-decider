plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.libertad.ridedecider"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.libertad.ridedecider"
        minSdk = 29          // multi-window / freeform APIs necesitan API 29+
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-skeleton"
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // OCR local (on-device, sin red) para el fallback cuando el Accessibility
    // Service no puede leer el árbol de nodos de una app (vistas custom / FLAG_SECURE).
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Para persistir configuración de pesos/comisiones del usuario.
    implementation("androidx.datastore:datastore-preferences:1.1.1")
}
