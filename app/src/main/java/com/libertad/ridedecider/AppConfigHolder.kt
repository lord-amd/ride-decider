package com.libertad.ridedecider

import android.content.Context
import android.content.SharedPreferences

/**
 * Guarda la configuración de SettingsActivity (pesos, comisiones, costo propio) en
 * SharedPreferences, no en memoria. Esto es lo que hace que sobreviva a cerrar la
 * app, reiniciar el celular, o actualizar Ride Decider a una versión nueva: los datos
 * de la app (incluida esta configuración) NO se borran al instalar una actualización
 * encima de la anterior — solo se perderían si desinstalás la app del todo.
 *
 * API idéntica a la versión anterior (solo en memoria) para no tener que tocar
 * OverlayService ni SettingsActivity más que para leer/escribir estos mismos campos.
 */
object AppConfigHolder {
    private const val PREFS = "app_config"

    private fun prefs(): SharedPreferences =
        RideDeciderApp.appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var weights: UserWeights
        get() = prefs().let { p ->
            UserWeights(
                weightNetPerKm = p.getFloat("w_net_km", 0.4f).toDouble(),
                weightNetPerHour = p.getFloat("w_net_hour", 0.35f).toDouble(),
                weightDistance = p.getFloat("w_distance", 0.15f).toDouble(),
                weightPickupDistance = p.getFloat("w_pickup", 0.10f).toDouble(),
                minNetEarnings = p.getFloat("min_net", 0f).toDouble(),
                minNetPerKm = p.getFloat("min_net_km", 0f).toDouble(),
                maxPickupDistanceKm = p.getFloat("max_pickup", 5f).toDouble()
            )
        }
        set(value) {
            prefs().edit()
                .putFloat("w_net_km", value.weightNetPerKm.toFloat())
                .putFloat("w_net_hour", value.weightNetPerHour.toFloat())
                .putFloat("w_distance", value.weightDistance.toFloat())
                .putFloat("w_pickup", value.weightPickupDistance.toFloat())
                .putFloat("min_net", value.minNetEarnings.toFloat())
                .putFloat("min_net_km", value.minNetPerKm.toFloat())
                .putFloat("max_pickup", value.maxPickupDistanceKm.toFloat())
                .apply()
        }

    var commissions: CommissionConfig
        get() = prefs().let { p ->
            val defaultCommission = p.getFloat("default_commission", 0.20f).toDouble()
            val overridesRaw = p.getString("commission_overrides", "") ?: ""
            val overrides = overridesRaw.split(";")
                .mapNotNull { entry ->
                    val parts = entry.split("=")
                    if (parts.size == 2) {
                        val value = parts[1].toDoubleOrNull()
                        if (value != null) parts[0] to value else null
                    } else null
                }
                .toMap()
            CommissionConfig(defaultCommission = defaultCommission, overrides = overrides)
        }
        set(value) {
            prefs().edit()
                .putFloat("default_commission", value.defaultCommission.toFloat())
                .putString("commission_overrides", value.overrides.entries.joinToString(";") { "${it.key}=${it.value}" })
                .apply()
        }

    var operatingCost: OperatingCost
        get() = OperatingCost(costPerKm = prefs().getFloat("cost_per_km", 0f).toDouble())
        set(value) {
            prefs().edit().putFloat("cost_per_km", value.costPerKm.toFloat()).apply()
        }

    var displayConfig: DisplayConfig
        get() = prefs().let { p ->
            DisplayConfig(
                showFare = p.getBoolean("show_fare", true),
                showDistance = p.getBoolean("show_distance", true),
                showNetPerKm = p.getBoolean("show_net_km", true),
                showNetPerHour = p.getBoolean("show_net_hour", false),
                showScore = p.getBoolean("show_score", true),
                badgeDurationSeconds = p.getInt("badge_duration_seconds", 10)
            )
        }
        set(value) {
            prefs().edit()
                .putBoolean("show_fare", value.showFare)
                .putBoolean("show_distance", value.showDistance)
                .putBoolean("show_net_km", value.showNetPerKm)
                .putBoolean("show_net_hour", value.showNetPerHour)
                .putBoolean("show_score", value.showScore)
                .putInt("badge_duration_seconds", value.badgeDurationSeconds)
                .apply()
        }
}
