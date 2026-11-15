package com.libertad.ridedecider

import android.content.Context

/**
 * Guarda qué apps elegiste (hasta 4) para abrir en multiventana. Reemplaza la lista
 * fija de Uber/Cabify/DiDi/inDrive que había antes: ahora la elegís vos desde
 * AppPickerActivity, de cualquier app instalada en el dispositivo.
 *
 * Simple SharedPreferences (no hace falta DataStore para esto); el orden de la lista
 * es el orden en que se van a acomodar en pantalla (primera elegida = primer cuadrante).
 */
object AppSelectionStore {
    private const val PREFS = "app_selection"
    private const val KEY_PACKAGES = "selected_packages"
    const val MAX_APPS = 4

    fun getSelected(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_PACKAGES, "") ?: ""
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun setSelected(context: Context, packages: List<String>) {
        prefs(context).edit()
            .putString(KEY_PACKAGES, packages.take(MAX_APPS).joinToString(","))
            .apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
