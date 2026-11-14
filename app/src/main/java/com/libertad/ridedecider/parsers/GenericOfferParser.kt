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

    // Monto: "$450", "$ 1.234,50", "ARS 450", "450 ARS". Acepta separador de miles '.' y
    // decimal ',' (formato argentino) o viceversa; nos quedamos con el entero más grande
    // razonable como tarifa (ignoramos centavos).
    private val moneyPattern = Regex("""(?:\$|ARS|AR\$)\s*(\d{1,3}(?:[.,]\d{3})*(?:[.,]\d{1,2})?)""", RegexOption.IGNORE_CASE)
    private val kmPattern = Regex("""(\d+(?:[.,]\d+)?)\s*km\b""", RegexOption.IGNORE_CASE)
    private val minPattern = Regex("""(\d+)\s*min\b""", RegexOption.IGNORE_CASE)

    // Palabras clave que, si aparecen como texto propio (label), ayudan a confirmar que
    // estamos en una pantalla de solicitud de viaje y no en otra pantalla cualquiera de
    // la app (menú, configuración, etc). No son obligatorias, solo suman confianza.
    private val contextKeywords = listOf(
        "distancia", "distance", "tiempo", "time", "duración", "duration",
        "tarifa", "fare", "ganancia", "earnings", "viaje", "trip", "ride",
        "aceptar", "accept", "rechazar", "decline"
    )

    data class ParsedOffer(
        val fareGross: Double?,
        val distanceKm: Double?,
        val etaMinutes: Double?,
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

        val fare = moneyPattern.find(joined)?.groupValues?.get(1)?.let { normalizeNumber(it) }
        val km = kmPattern.find(joined)?.groupValues?.get(1)?.let { normalizeNumber(it) }
        val min = minPattern.find(joined)?.groupValues?.get(1)?.toDoubleOrNull()

        val hasKeyword = contextKeywords.any { kw -> texts.any { it.contains(kw, ignoreCase = true) } }

        // Umbral mínimo para considerar "esto es una oferta real" y no ruido de otra
        // pantalla: necesitamos al menos el monto, y al menos uno de (distancia, tiempo),
        // O el monto más una palabra clave de contexto.
        val looksLikeOffer = fare != null && (km != null || min != null || hasKeyword)
        if (!looksLikeOffer) return null

        return ParsedOffer(
            fareGross = fare,
            distanceKm = km,
            etaMinutes = min,
            rawText = joined
        )
    }

    /** "1.234,50" -> 1234.50 ; "1,234.50" -> 1234.50 ; "450" -> 450.0 */
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
            else -> raw // solo punto o solo dígitos: Double.toDoubleOrNull lo interpreta bien
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
