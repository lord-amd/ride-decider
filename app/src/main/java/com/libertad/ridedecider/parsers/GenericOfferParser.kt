package com.libertad.ridedecider.parsers

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Parser ÚNICO y universal: no sabe nada de Uber, Cabify, DiDi ni inDrive en particular.
 * Mira el texto visible dentro de UNA ventana (una app en una porción de la pantalla,
 * identificada por sus bounds) y busca patrones genéricos de "esto parece una oferta de
 * viaje": un monto de dinero, una distancia en km y/o un tiempo en minutos.
 *
 * Como no depende de resource-ids ni de la estructura visual de ninguna app específica,
 * sigue funcionando aunque una app cambie de versión, cambie de diseño, o aparezca una
 * app nueva que el usuario todavía no probó — exactamente lo que se pidió: que se adapte
 * solo, sin tener que escribir un parser nuevo por app.
 *
 * La identidad de la app (qué paquete es, qué nombre mostrar) NO la decide este parser:
 * la decide quien lo llama (RideAccessibilityService), que ya sabe de qué ventana/paquete
 * viene el texto. Este parser solo dice "hay o no hay una oferta acá, y estos son sus
 * números", sin importar cuál app sea.
 */
object GenericOfferParser {

    // Monto con el símbolo ANTES del número: "$450", "$ 1.234,50", "ARS 450" (formato de
    // DiDi, por ejemplo).
    private val moneyPrefixPattern = Regex("""(?:\$|ARS|AR\$)\s*(\d{1,3}(?:[.,]\d{3})*(?:[.,]\d{1,2})?)""", RegexOption.IGNORE_CASE)

    // Monto con el símbolo DESPUÉS del número: "3.256 ARS" (formato de Uber). El
    // "(?!\s*/)" evita confundir esto con una tarifa POR KILÓMETRO como "693 ARS/km",
    // que es una referencia de la zona, no el monto total ofrecido.
    private val moneySuffixPattern = Regex("""(\d{1,3}(?:[.,]\d{3})*(?:[.,]\d{1,2})?)\s*(?:ARS|AR\$)\b(?!\s*/)""", RegexOption.IGNORE_CASE)

    // Distancia y tiempo casi siempre aparecen juntos como "X min (Y km)" tanto para la
    // distancia hasta el pasajero ("A 3 min (0,9 km) de distancia") como para el viaje
    // completo ("Viaje de 10 min (3,8 km)" / "14 min (6,5 km)"). Buscamos ese patrón
    // combinado en vez de "km" y "min" sueltos, así no mezclamos un número de km con un
    // tiempo de otra parte de la pantalla que no tiene nada que ver.
    private val minKmPairPattern = Regex("""(\d+(?:[.,]\d+)?)\s*min\.?\s*\(\s*(\d+(?:[.,]\d+)?)\s*km\s*\)""", RegexOption.IGNORE_CASE)

    // Respaldo si una app no usa el formato "min (km)" de arriba: km y min sueltos.
    private val kmPattern = Regex("""(\d+(?:[.,]\d+)?)\s*km\b""", RegexOption.IGNORE_CASE)
    private val minPattern = Regex("""(\d+(?:[.,]\d+)?)\s*min\b""", RegexOption.IGNORE_CASE)

    // Palabras FUERTES: solo aparecen en la tarjeta de aceptar/rechazar un viaje
    // concreto, nunca en la pantalla principal/"online" de la app (que también tiene
    // plata y tiempos — la ganancia del día, las franjas de demanda del mapa — y por
    // eso NO sirven como señal de "esto es una oferta").
    private val strongContextKeywords = listOf(
        "aceptar", "accept", "rechazar", "decline", "ignorar", "ignore"
    )

    data class ParsedOffer(
        val fareGross: Double?,
        val distanceKm: Double?,
        val etaMinutes: Double?,
        val pickupDistanceKm: Double? = null,
        val pickupEtaMinutes: Double? = null,
        val rawText: String
    )

    /** Punto de entrada principal: recorre el árbol de accesibilidad de una ventana. */
    fun tryParse(root: AccessibilityNodeInfo): ParsedOffer? {
        val texts = collectTexts(root)
        return tryParseTexts(texts)
    }

    /** Variante para el fallback de OCR, que entrega directamente un bloque de texto plano. */
    fun tryParseOcrText(fullText: String): ParsedOffer? {
        val texts = fullText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return tryParseTexts(texts)
    }

    private fun tryParseTexts(texts: List<String>): ParsedOffer? {
        if (texts.isEmpty()) return null
        val joined = texts.joinToString(" ")

        // El monto puede venir con el símbolo antes ("$4.200", DiDi) o después
        // ("3.256 ARS", Uber). Probamos los dos formatos.
        val fare = (moneyPrefixPattern.find(joined) ?: moneySuffixPattern.find(joined))
            ?.groupValues?.get(1)?.let { normalizeNumber(it) }

        // Buscamos TODAS las apariciones de "X min (Y km)". Cuando hay dos (caso típico:
        // una app muestra primero cuánto falta para llegar a buscar al pasajero, y
        // después la distancia/tiempo del viaje en sí), la PRIMERA es la de recogida y la
        // ÚLTIMA es la del viaje completo. Cuando solo hay una, la tratamos como el viaje
        // completo (no se detectó una distancia de recogida separada).
        val pairs = minKmPairPattern.findAll(joined).toList()
        var tripKm: Double? = null
        var tripMin: Double? = null
        var pickupKm: Double? = null
        var pickupMin: Double? = null
        when {
            pairs.size >= 2 -> {
                pickupMin = pairs.first().groupValues[1].let { normalizeNumber(it) }
                pickupKm = pairs.first().groupValues[2].let { normalizeNumber(it) }
                tripMin = pairs.last().groupValues[1].let { normalizeNumber(it) }
                tripKm = pairs.last().groupValues[2].let { normalizeNumber(it) }
            }
            pairs.size == 1 -> {
                tripMin = pairs.first().groupValues[1].let { normalizeNumber(it) }
                tripKm = pairs.first().groupValues[2].let { normalizeNumber(it) }
            }
            else -> {
                // Respaldo: ninguna app usa el formato "min (km)" acá, buscamos sueltos.
                tripKm = kmPattern.find(joined)?.groupValues?.get(1)?.let { normalizeNumber(it) }
                tripMin = minPattern.find(joined)?.groupValues?.get(1)?.let { normalizeNumber(it) }
            }
        }

        val hasStrongKeyword = strongContextKeywords.any { kw -> texts.any { it.contains(kw, ignoreCase = true) } }

        // Umbral mínimo para considerar "esto es una oferta real" y no ruido de la
        // pantalla principal de la app (que también muestra plata -la ganancia del día-
        // y tiempos -franjas de demanda del mapa- sin que haya ninguna oferta). Para
        // evitar confundir eso con una oferta, exigimos UNA señal fuerte:
        //  - el patrón completo "X min (Y km)" (la forma en que casi todas las apps
        //    muestran una oferta real), o
        //  - un botón de aceptar/rechazar visible (solo existe en la tarjeta de oferta), o
        //  - km Y min sueltos los dos juntos (que los dos coincidan por separado es mucho
        //    menos probable que sea ruido que uno solo).
        val looksLikeOffer = fare != null && (pairs.isNotEmpty() || hasStrongKeyword || (tripKm != null && tripMin != null))
        if (!looksLikeOffer) return null

        return ParsedOffer(
            fareGross = fare,
            distanceKm = tripKm,
            etaMinutes = tripMin,
            pickupDistanceKm = pickupKm,
            pickupEtaMinutes = pickupMin,
            rawText = joined
        )
    }

    /**
     * "1.234,50" -> 1234.50 ; "1,234.50" -> 1234.50 ; "450" -> 450.0 ; "4.200" -> 4200.0
     * (formato argentino: "." como separador de miles, sin centavos) ; "4.20" -> 4.20
     * (un solo punto seguido de 1 o 2 dígitos se interpreta como decimal, no como miles).
     */
    private fun normalizeNumber(raw: String): Double? {
        val hasComma = raw.contains(',')
        val hasDot = raw.contains('.')
        val cleaned = when {
            hasComma && hasDot -> {
                // El último separador que aparece es el decimal; el otro es de miles.
                if (raw.lastIndexOf(',') > raw.lastIndexOf('.')) {
                    raw.replace(".", "").replace(",", ".")
                } else {
                    raw.replace(",", "")
                }
            }
            hasComma -> raw.replace(",", ".") // formato "450,50" -> decimal
            hasDot -> {
                // Un solo punto: si va seguido de exactamente 3 dígitos es separador de
                // miles argentino ("4.200" = 4200), no un decimal; si va seguido de 1 o 2
                // dígitos es un decimal normal ("4.20" = 4.20).
                val afterLastDot = raw.substringAfterLast('.')
                if (afterLastDot.length == 3) raw.replace(".", "") else raw
            }
            else -> raw // solo dígitos: Double.toDoubleOrNull lo interpreta bien
        }
        return cleaned.toDoubleOrNull()
    }
}

/** Recorre el árbol y junta todos los textos visibles, en orden. */
fun collectTexts(node: AccessibilityNodeInfo?, out: MutableList<String> = mutableListOf()): List<String> {
    if (node == null) return out
    val text = node.text?.toString()?.trim()
    if (!text.isNullOrEmpty()) out.add(text)
    val desc = node.contentDescription?.toString()?.trim()
    if (!desc.isNullOrEmpty() && desc != text) out.add(desc)
    for (i in 0 until node.childCount) {
        collectTexts(node.getChild(i), out)
    }
    return out
}

/** Bounds en pantalla de una ventana completa, usado para posicionar el badge y recortar el OCR. */
fun AccessibilityNodeInfo.screenBounds(): Rect {
    val r = Rect()
    getBoundsInScreen(r)
    return r
}
