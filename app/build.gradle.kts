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

        // Número de build inyectado por CI (-PbuildNumber=<github.run_number>).
        // Es lo que la app compara contra el último release de GitHub para saber
        // si hay una versión más nueva. En un build local (Android Studio) queda
        // en 0, así que nunca se va a "autoactualizar" a algo más viejo.
        buildConfigField("int", "BUILD_NUMBER", "${project.findProperty("buildNumber") ?: 0}")
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // Clave de firma FIJA para el build de debug (versionada en /keystore/debug.keystore).
    // Sin esto, cada corrida de GitHub Actions arranca de una VM nueva sin ningún
    // debug.keystore previo, Gradle genera uno random, y cada APK queda firmado con
    // una clave distinta -> Android rechaza instalar la versión nueva encima de la
    // vieja ("error interno" / conflicto de firma). Con una clave fija, todos los
    // builds (locales o de CI) firman igual y las actualizaciones instalan limpio.
    signingConfigs {
        getByName("debug") {
            storeFile = file("${rootProject.projectDir}/keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
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
