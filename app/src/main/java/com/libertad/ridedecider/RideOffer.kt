package com.libertad.ridedecider

import android.graphics.Rect

/**
 * Semáforo de conveniencia calculado por el DecisionEngine.
 */
enum class Light { GREEN, YELLOW, RED, UNKNOWN }

/**
 * Una solicitud de viaje leída de pantalla (vía Accessibility Service u OCR).
 *
 * Ya NO se identifica la app por un enum fijo (Platform): se identifica por el
 * packageName real de la ventana donde se detectó la oferta, y el nombre visible
 * (appLabel) se resuelve en tiempo real con PackageManager. Así la app funciona con
 * cualquier app de viajes que el usuario tenga en pantalla, sin tocar código cuando
 * una app cambia de versión o se agrega una nueva.
 *
 * Todos los campos numéricos son nullable porque, según la app y el método de
 * lectura (nodo de accesibilidad vs OCR), puede que no se logre extraer alguno.
 * El DecisionEngine debe tolerar datos parciales y marcar baja confianza.
 */
data class RideOffer(
    val appPackage: String,       // packageName real de la app donde se detectó la oferta (ej. com.ubercab.driver)
    val appLabel: String,         // nombre visible de la app, resuelto con PackageManager (ej. "Uber Driver")
    val fareGross: Double?,       // lo que paga el pasajero / tarifa bruta mostrada
    val distanceKm: Double?,      // distancia total estimada del viaje
    val etaMinutes: Double?,      // tiempo estimado total del viaje
    val pickupDistanceKm: Double? = null, // distancia hasta recoger al pasajero (si se detecta separada)
    val windowBounds: Rect? = null, // región de la pantalla que ocupaba esa app cuando se leyó (para ubicar el badge y recortar el OCR)
    val rawText: String = "",     // texto crudo capturado, útil para debug/logging
    val capturedAtMillis: Long = System.currentTimeMillis(),
    val sourceConfidence: Confidence = Confidence.MEDIUM
)

enum class Confidence { HIGH, MEDIUM, LOW } // HIGH = accessibility node exacto, LOW = OCR con texto parcial

/**
 * Resultado de evaluar una oferta: cuánto conviene, en términos netos y de score.
 */
data class RideEvaluation(
    val offer: RideOffer,
    val netEarnings: Double?,     // ganancia neta estimada después de comisión de la plataforma
    val pricePerKm: Double?,      // tarifa bruta / distancia total (incluye ida a buscar si se configuró así)
    val netPerKm: Double?,        // ganancia neta / distancia total -> el dato más importante para decidir
    val netPerHour: Double?,      // ganancia neta / (eta en horas) -> para comparar contra "tiempo perdido"
    val score: Double?,           // score ponderado 0-100 según los pesos configurados por el usuario
    val light: Light,
    val reasons: List<String>     // explicación corta de por qué quedó en ese score/semáforo (para mostrar en el overlay)
)
