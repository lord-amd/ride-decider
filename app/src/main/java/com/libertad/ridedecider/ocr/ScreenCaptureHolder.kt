package com.libertad.ridedecider.ocr

import android.graphics.Bitmap

/**
 * Guarda el último frame de pantalla capturado por ScreenCaptureService, para que
 * ScreenOcrHelper lo recorte y lea sin que cada parte del código tenga que saber cómo
 * funciona MediaProjection. Un solo frame "vivo" alcanza: no hace falta guardar
 * historial, cada vez que se pide un recorte se usa el más reciente disponible.
 */
object ScreenCaptureHolder {
    @Volatile
    var latestFrame: Bitmap? = null
        private set

    @Volatile
    var latestFrameAtMillis: Long = 0L
        private set

    @Synchronized
    fun update(frame: Bitmap) {
        // Reciclamos el anterior para no acumular bitmaps grandes en memoria: esto
        // captura toda la pantalla varias veces por segundo.
        if (latestFrame !== frame) {
            latestFrame?.takeIf { !it.isRecycled }?.recycle()
        }
        latestFrame = frame
        latestFrameAtMillis = System.currentTimeMillis()
    }

    @Synchronized
    fun clear() {
        latestFrame?.takeIf { !it.isRecycled }?.recycle()
        latestFrame = null
        latestFrameAtMillis = 0L
    }

    /** true si hay un frame capturado hace poco (si es muy viejo, el servicio puede haberse caído). */
    fun hasRecentFrame(maxAgeMillis: Long = 5000L): Boolean =
        latestFrame != null && (System.currentTimeMillis() - latestFrameAtMillis) < maxAgeMillis
}
