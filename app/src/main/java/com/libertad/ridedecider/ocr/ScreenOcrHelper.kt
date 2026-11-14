package com.libertad.ridedecider.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.libertad.ridedecider.parsers.GenericOfferParser

/**
 * Fallback de OCR local (Google ML Kit, on-device, sin red) para cuando el árbol de
 * accesibilidad de una ventana no dio nada útil (FLAG_SECURE, vistas custom, etc.).
 *
 * Ya no recibe una "plataforma": recibe directamente la región de pantalla (Rect) de la
 * ventana que falló, recorta SOLO esa porción del screenshot completo, y le pasa el
 * texto reconocido al mismo GenericOfferParser universal que usa el camino de
 * accesibilidad — así la lógica de "qué es una oferta" vive en un solo lugar.
 *
 * TODO (pendiente de cablear con el proyecto abierto en Android Studio):
 *  1. Pedir permiso de MediaProjection una sola vez desde MainActivity
 *     (`MediaProjectionManager.createScreenCaptureIntent()`).
 *  2. Mantener una captura continua liviana (VirtualDisplay + ImageReader) y, cuando
 *     se llame requestFallbackParse(bounds), tomar el frame más reciente y
 *     Bitmap.createBitmap(frame, bounds.left, bounds.top, bounds.width(), bounds.height())
 *     para recortar exactamente la región de esa ventana.
 */
object ScreenOcrHelper {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun requestFallbackParse(bounds: Rect, onResult: (GenericOfferParser.ParsedOffer?) -> Unit) {
        val cropped = latestCapturedFrameCroppedTo(bounds) // TODO: reemplazar por captura real, ver arriba
        if (cropped == null) {
            onResult(null)
            return
        }
        val image = InputImage.fromBitmap(cropped, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                onResult(GenericOfferParser.tryParseOcrText(visionText.text))
            }
            .addOnFailureListener { onResult(null) }
    }

    private fun latestCapturedFrameCroppedTo(bounds: Rect): Bitmap? = null // placeholder, ver TODO arriba
}
