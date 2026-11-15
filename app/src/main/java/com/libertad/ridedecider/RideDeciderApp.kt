package com.libertad.ridedecider

import android.app.Application
import android.content.Context

/**
 * Application propia solo para tener un Context disponible desde objetos que no son
 * Activity (como AppConfigHolder, que necesita leer/escribir SharedPreferences desde
 * OverlayService). Es el Application Context de toda la vida de la app, no una
 * Activity — no hay riesgo de memory leak guardándolo estático.
 */
class RideDeciderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = this
    }

    companion object {
        lateinit var appContext: Context
            private set
    }
}
