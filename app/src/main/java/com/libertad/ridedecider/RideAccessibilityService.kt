package com.libertad.ridedecider

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent
import com.libertad.ridedecider.ocr.ScreenOcrHelper
import com.libertad.ridedecider.parsers.GenericOfferParser
import com.libertad.ridedecider.parsers.screenBounds

/**
 * Ya NO filtra por una lista fija de packageNames (ver accessibility_service_config.xml,
 * que ahora no restringe apps). En cada evento relevante, recorre TODAS las ventanas
 * visibles en pantalla (`windows`) — esto es lo que permite que, con la pantalla dividida
 * en 2, 3 o 4 porciones, cada una se lea de forma independiente sin importar qué app sea.
 *
 * Para cada ventana:
 *  1. Identifica el packageName real de esa ventana (y resuelve el nombre visible de la
 *     app con PackageManager, no con una lista hardcodeada).
 *  2. Corre el GenericOfferParser universal sobre el árbol de esa ventana.
 *  3. Si no encuentra nada por accesibilidad (árbol vacío/bloqueado), guarda la región
 *     (bounds) de esa ventana para que el fallback de OCR recorte justo esa porción de
 *     pantalla — así tampoco el OCR necesita saber qué app es, solo qué parte leer.
 *  4. Guarda el resultado en RideOfferStore, con el packageName como clave (no un enum
 *     fijo de 4 apps), y pide al OverlayService que refresque.
 */
class RideAccessibilityService : AccessibilityService() {

    // No tiene sentido evaluar nuestra propia ventana ni la del sistema (barra de estado, etc.)
    private val ignoredPackages = setOf("com.libertad.ridedecider", "com.android.systemui")

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // No importa de qué ventana vino el evento puntual: ante cualquier cambio,
        // re-escaneamos TODAS las ventanas visibles, porque cualquiera de las apps en
        // pantalla pudo haber actualizado su contenido.
        scanAllVisibleWindows()
    }

    private fun scanAllVisibleWindows() {
        val visibleWindows = windows ?: return
        for (window in visibleWindows) {
            val root = window.root ?: continue
            try {
                val pkg = root.packageName?.toString() ?: continue
                if (pkg in ignoredPackages) continue

                val bounds = root.screenBounds()
                val parsed = GenericOfferParser.tryParse(root)

                if (parsed != null) {
                    RideOfferStore.update(
                        RideOffer(
                            appPackage = pkg,
                            appLabel = resolveAppLabel(pkg),
                            fareGross = parsed.fareGross,
                            distanceKm = parsed.distanceKm,
                            etaMinutes = parsed.etaMinutes,
                            windowBounds = bounds,
                            rawText = parsed.rawText,
                            sourceConfidence = Confidence.HIGH
                        )
                    )
                } else {
                    // Árbol de accesibilidad no dio nada útil -> probar OCR recortando
                    // justo esta región de pantalla (puede ser que la app bloquee el
                    // árbol con FLAG_SECURE o use vistas custom tipo Canvas/OpenGL).
                    ScreenOcrHelper.requestFallbackParse(bounds) { ocrParsed ->
                        if (ocrParsed != null) {
                            RideOfferStore.update(
                                RideOffer(
                                    appPackage = pkg,
                                    appLabel = resolveAppLabel(pkg),
                                    fareGross = ocrParsed.fareGross,
                                    distanceKm = ocrParsed.distanceKm,
                                    etaMinutes = ocrParsed.etaMinutes,
                                    windowBounds = bounds,
                                    rawText = ocrParsed.rawText,
                                    sourceConfidence = Confidence.LOW
                                )
                            )
                            notifyOverlay()
                        }
                    }
                }
            } finally {
                root.recycle()
            }
        }
        notifyOverlay()
    }

    private fun resolveAppLabel(packageName: String): String = try {
        val pm: PackageManager = packageManager
        val appInfo = pm.getApplicationInfo(packageName, 0)
        pm.getApplicationLabel(appInfo).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName // si no se puede resolver, mostramos el packageName tal cual
    }

    override fun onInterrupt() { /* no-op */ }

    private fun notifyOverlay() {
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_REFRESH
        }
        startService(intent)
    }
}

/**
 * Última oferta conocida por app (clave = packageName real, no un enum fijo), en memoria.
 * Si más adelante querés historial, cambiar por una tabla Room.
 */
object RideOfferStore {
    private val latest = mutableMapOf<String, RideOffer>()

    @Synchronized
    fun update(offer: RideOffer) {
        latest[offer.appPackage] = offer
    }

    @Synchronized
    fun snapshot(): List<RideOffer> = latest.values.toList()

    @Synchronized
    fun clear(appPackage: String) {
        latest.remove(appPackage)
    }
}
