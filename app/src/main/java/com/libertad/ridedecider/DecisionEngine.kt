package com.libertad.ridedecider

import kotlin.math.max

/**
 * Evalúa una o varias RideOffer y decide cuál conviene más, devolviendo un score y un
 * semáforo (verde/amarillo/rojo) por oferta para que el OverlayService los muestre.
 *
 * La lógica es intencionalmente simple y transparente (no "caja negra") para que puedas
 * ajustarla viendo viajes reales: todo pasa por netEarnings -> netPerKm / netPerHour -> score.
 */
class DecisionEngine(
    private val weights: UserWeights,
    private val commissions: CommissionConfig,
    private val operatingCost: OperatingCost
) {

    fun evaluate(offer: RideOffer): RideEvaluation {
        val reasons = mutableListOf<String>()

        val fare = offer.fareGross
        val distance = offer.distanceKm
        val etaMin = offer.etaMinutes
        val pickupKm = offer.pickupDistanceKm ?: 0.0

        if (fare == null) reasons += "Sin tarifa detectada (lectura incompleta)"
        if (distance == null) reasons += "Sin distancia detectada (lectura incompleta)"

        // 1. Ganancia neta = tarifa bruta - comisión de la plataforma - costo operativo propio.
        val netEarnings: Double? = fare?.let { f ->
            val commissionPct = commissions.forApp(offer.appPackage)
            val afterCommission = f * (1.0 - commissionPct)
            val totalKm = (distance ?: 0.0) + pickupKm
            val afterOwnCost = afterCommission - (operatingCost.costPerKm * totalKm)
            reasons += "Bruto ${"%.0f".format(f)} - comisión ${(commissionPct * 100).toInt()}%" +
                if (operatingCost.costPerKm > 0) " - costo propio" else ""
            afterOwnCost
        }

        // 2. Normalizadores clave: $/km y $/hora netos. Estos son los que realmente
        // importan para comparar viajes de distinta duración/distancia entre apps.
        val totalKmForRatio = (distance ?: 0.0) + pickupKm
        val netPerKm: Double? = netEarnings?.let { ne ->
            if (totalKmForRatio > 0) ne / totalKmForRatio else null
        }
        val pricePerKm: Double? = fare?.let { f -> if (totalKmForRatio > 0) f / totalKmForRatio else null }

        val netPerHour: Double? = netEarnings?.let { ne ->
            etaMin?.let { m -> if (m > 0) ne / (m / 60.0) else null }
        }

        // 3. Chequeo de umbrales duros del usuario -> si no los cumple, es ROJO sin importar el score.
        val failsHardThreshold =
            (netEarnings != null && netEarnings < weights.minNetEarnings) ||
            (netPerKm != null && netPerKm < weights.minNetPerKm) ||
            (pickupKm > weights.maxPickupDistanceKm)

        if (failsHardThreshold) {
            reasons += "No cumple mínimos configurados"
        }

        // 4. Score ponderado 0-100, usando funciones de utilidad simples por componente.
        //    netPerKm y netPerHour son los componentes dominantes; distancia y pickup
        //    son penalizaciones/bonificaciones según preferShortTrips.
        val score: Double? = if (netEarnings == null || distance == null) {
            null // no hay suficiente info para comparar con confianza
        } else {
            val uNetKm = utilityFromRatio(netPerKm, refGood = 300.0, refBad = 0.0)      // ajustar refGood a tu zona/moneda
            val uNetHour = utilityFromRatio(netPerHour, refGood = 4000.0, refBad = 500.0) // idem
            val uDistance = distanceUtility(distance, weights.preferShortTrips)
            val uPickup = pickupUtility(pickupKm, weights.maxPickupDistanceKm)

            val totalWeight = weights.weightNetPerKm + weights.weightNetPerHour +
                weights.weightDistance + weights.weightPickupDistance
            val raw = (uNetKm * weights.weightNetPerKm +
                uNetHour * weights.weightNetPerHour +
                uDistance * weights.weightDistance +
                uPickup * weights.weightPickupDistance) / max(totalWeight, 0.0001)

            (raw * 100).coerceIn(0.0, 100.0)
        }

        val light = when {
            failsHardThreshold -> Light.RED
            score == null -> Light.UNKNOWN
            score >= 66 -> Light.GREEN
            score >= 40 -> Light.YELLOW
            else -> Light.RED
        }

        return RideEvaluation(
            offer = offer,
            netEarnings = netEarnings,
            pricePerKm = pricePerKm,
            netPerKm = netPerKm,
            netPerHour = netPerHour,
            score = score,
            light = light,
            reasons = reasons
        )
    }

    /** Dado varias ofertas simultáneas (una por app), devuelve la mejor evaluada. */
    fun pickBest(offers: List<RideOffer>): RideEvaluation? =
        offers.map(::evaluate)
            .filter { it.light != Light.RED }
            .maxByOrNull { it.score ?: -1.0 }
            ?: offers.map(::evaluate).maxByOrNull { it.score ?: -1.0 }

    /** Utilidad 0..1 lineal entre refBad (->0) y refGood (->1). */
    private fun utilityFromRatio(value: Double?, refGood: Double, refBad: Double): Double {
        if (value == null) return 0.5 // info faltante -> neutral, no penaliza ni premia
        if (refGood == refBad) return 0.5
        val t = (value - refBad) / (refGood - refBad)
        return t.coerceIn(0.0, 1.0)
    }

    private fun distanceUtility(distanceKm: Double, preferShort: Boolean): Double {
        // Viajes muy cortos (<1.5km) suelen ser poco convenientes por tiempos muertos de espera/cobro;
        // viajes muy largos (>25km) pueden ser buenos en $/km pero arriesgados por tráfico de vuelta vacío.
        val sweetSpotUtility = when {
            distanceKm < 1.5 -> 0.3
            distanceKm in 1.5..20.0 -> 1.0
            else -> 0.6
        }
        return if (preferShort) {
            // invierte un poco la preferencia hacia distancias cortas dentro del sweet spot
            if (distanceKm <= 8.0) sweetSpotUtility else sweetSpotUtility * 0.8
        } else sweetSpotUtility
    }

    private fun pickupUtility(pickupKm: Double, maxAllowed: Double): Double {
        if (maxAllowed <= 0) return 1.0
        val t = 1.0 - (pickupKm / maxAllowed)
        return t.coerceIn(0.0, 1.0)
    }
}
