package com.libertad.ridedecider.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.libertad.ridedecider.parsers.GenericOfferParser

/**
 * Fallback de OCR local (Google ML Kit, on-device, sin red) para cuando el árbol de
 * accesibilidad de una ventana no dio nada útil. Confirmado en uso real: la pantalla de
 * oferta de DiDi no expone NADA de texto por Accessibility (el árbol queda vacío), así
 * que para esa app este es el único camino posible.
 *
 * Recibe directamente la región de pantalla (Rect) de la ventana que falló, recorta SOLO
 * esa porción del último frame capturado por ScreenCaptureService, y le pasa el texto
 * reconocido al mismo GenericOfferParser universal que usa el camino de accesibilidad —
 * así la lógica de "qué es una oferta" vive en un solo lugar.
 *
 * Requiere que ScreenCaptureService esté activo (el usuario tiene que otorgar el permiso
 * de captura de pantalla una vez por sesión desde MainActivity, botón "Activar lectura de
 * pantalla (OCR)"). Si todavía no se otorgó o el servicio no está corriendo, no hay frame
 * disponible y esto simplemente no encuentra nada (se sigue mostrando el cartel gris).
 */
object ScreenOcrHelper {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun requestFallbackParse(bounds: Rect, onResult: (GenericOfferParser.ParsedOffer?) -> Unit) {
        val cropped = latestCapturedFrameCroppedTo(bounds)
        if (cropped == null) {
            onResult(null)
            return
        }
        val image = InputImage.fromBitmap(cropped, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                onResult(GenericOfferParser.tryParseOcrText(visionText.text))
            }
            .addOnFailureListener {
                Log.e("ScreenOcrHelper", "Error de ML Kit al reconocer texto", it)
                onResult(null)
            }
    }

    private fun latestCapturedFrameCroppedTo(bounds: Rect): Bitmap? {
        val frame = ScreenCaptureHolder.latestFrame ?: return null
        if (frame.isRecycled) return null

        // Los bounds vienen de getBoundsInScreen(), que puede pasarse un pixel del
        // borde real del frame (redondeos) -> los ajustamos (clamp) para nunca pedirle
        // a createBitmap una región fuera de la imagen, lo que tiraría una excepción.
        val left = bounds.left.coerceIn(0, frame.width - 1)
        val top = bounds.top.coerceIn(0, frame.height - 1)
        val right = bounds.right.coerceIn(left + 1, frame.width)
        val bottom = bounds.bottom.coerceIn(top + 1, frame.height)
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return null

        return try {
            Bitmap.createBitmap(frame, left, top, width, height)
        } catch (e: Exception) {
            Log.e("ScreenOcrHelper", "No se pudo recortar el frame de pantalla", e)
            null
        }
    }
}
