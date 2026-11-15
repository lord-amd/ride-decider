package com.libertad.ridedecider

/**
 * Configuración que el usuario fija desde SettingsActivity. Pensado para persistirse con
 * DataStore (ver SettingsActivity.kt) — este objeto es solo el modelo en memoria.
 */
data class UserWeights(
    // Pesos relativos (deberían sumar ~1.0, pero el DecisionEngine normaliza igual).
    val weightNetPerKm: Double = 0.4,
    val weightNetPerHour: Double = 0.35,
    val weightDistance: Double = 0.15,   // penaliza viajes muy largos si el usuario prefiere viajes cortos
    val weightPickupDistance: Double = 0.10, // penaliza tener que ir muy lejos a buscar al pasajero
    // Preferencia de viajes cortos vs largos: si es true, distancias cortas puntúan mejor.
    val preferShortTrips: Boolean = false,
    // Umbrales absolutos mínimos para considerar que un viaje es "aceptable" sin importar el score relativo.
    val minNetEarnings: Double = 0.0,
    val minNetPerKm: Double = 0.0,
    val maxPickupDistanceKm: Double = 5.0
)

/**
 * Comisión que cada app retiene (porcentaje, ej. 0.25 = 25%).
 *
 * Como las apps ya no se identifican con un enum fijo (ver RideOffer.appPackage), la
 * comisión tampoco es un campo por app hardcodeado: hay un valor por defecto que se
 * aplica a cualquier app nueva que aparezca, más overrides opcionales por packageName
 * para las apps que ya conocés y cuya comisión real querés afinar.
 *
 * Ejemplo de uso en SettingsActivity: overrides["com.ubercab.driver"] = 0.25
 */
data class CommissionConfig(
    val defaultCommission: Double = 0.20,
    val overrides: Map<String, Double> = emptyMap()
)

fun CommissionConfig.forApp(packageName: String): Double =
    overrides[packageName] ?: defaultCommission

/**
 * Costo operativo propio por km (combustible/GNC, desgaste, seguro prorrateado) que se
 * resta para calcular la ganancia neta real, no solo neta-de-comisión.
 */
data class OperatingCost(
    val costPerKm: Double = 0.0 // en la moneda local; 0.0 = no descontar costo propio, solo comisión de la app
)

/**
 * Qué información mostrar en el cartel flotante de cada app, y cuánto tiempo dejarlo
 * en pantalla antes de ocultarse solo. IMPORTANTE: esto solo controla qué se MUESTRA.
 * El cálculo del semáforo (DecisionEngine) siempre usa todos los datos disponibles
 * sin importar qué campos estén tildados acá — apagar un campo no cambia el color
 * resultante, solo si ese número aparece escrito en el cartel.
 */
data class DisplayConfig(
    val showFare: Boolean = true,
    val showDistance: Boolean = true,
    val showNetPerKm: Boolean = true,
    val showNetPerHour: Boolean = false,
    val showScore: Boolean = true,
    val badgeDurationSeconds: Int = 10 // 0 = no ocultar nunca
)
