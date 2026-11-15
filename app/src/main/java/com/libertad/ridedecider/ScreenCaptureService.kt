package com.libertad.ridedecider

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import com.libertad.ridedecider.ocr.ScreenCaptureHolder

/**
 * Mantiene viva una captura de pantalla continua (MediaProjection) para que
 * ScreenOcrHelper pueda recortar y leer con OCR la región de una app que NO expone
 * texto por Accessibility (confirmado que esto pasa con la pantalla de oferta de DiDi:
 * el árbol de accesibilidad queda completamente vacío ahí).
 *
 * El permiso de captura de pantalla lo tiene que otorgar el usuario UNA VEZ por sesión
 * (lo pide MainActivity con MediaProjectionManager.createScreenCaptureIntent()); Android
 * no deja guardar ese permiso de forma permanente entre reinicios de la app, así que hay
 * que volver a tocar "Activar lectura de pantalla (OCR)" si se cierra del todo Ride
 * Decider o se reinicia el celular.
 */
class ScreenCaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val NOTIF_CHANNEL_ID = "ride_decider_screen_capture"
        private const val NOTIF_ID = 2
        private const val TAG = "ScreenCaptureService"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "MediaProjection detenida (el usuario revocó el permiso o el sistema lo cortó)")
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultData == null || mediaProjection != null) {
            // Ya está andando, o nos llamaron sin los datos del permiso -> no hacemos nada.
            return START_STICKY
        }

        try {
            setUpProjection(resultCode, resultData)
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo iniciar la captura de pantalla para OCR", e)
            stopSelf()
        }
        return START_STICKY
    }

    private fun setUpProjection(resultCode: Int, resultData: Intent) {
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        val projection = projectionManager.getMediaProjection(resultCode, resultData)
        mediaProjection = projection
        projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager().defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val thread = HandlerThread("RideDeciderScreenCapture").apply { start() }
        handlerThread = thread
        val bgHandler = Handler(thread.looper)
        handler = bgHandler

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        reader.setOnImageAvailableListener({ r ->
            try {
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                val bitmap = imageToBitmap(image, width, height)
                image.close()
                if (bitmap != null) ScreenCaptureHolder.update(bitmap)
            } catch (e: Exception) {
                Log.e(TAG, "Error leyendo un frame de pantalla", e)
            }
        }, bgHandler)

        virtualDisplay = projection.createVirtualDisplay(
            "RideDeciderCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, bgHandler
        )

        Log.d(TAG, "Captura de pantalla para OCR activa (${width}x${height})")
    }

    /** Convierte el Image crudo (con padding de fila) a un Bitmap recortado al tamaño real. */
    private fun imageToBitmap(image: android.media.Image, width: Int, height: Int): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return if (rowPadding == 0) {
            bitmap
        } else {
            // Recortamos el padding extra de cada fila para que el bitmap final tenga
            // exactamente el ancho real de la pantalla (si no, los recortes por región
            // quedarían corridos).
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
            bitmap.recycle()
            cropped
        }
    }

    private fun windowManager() = getSystemService(WINDOW_SERVICE) as android.view.WindowManager

    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        handlerThread?.quitSafely()
        ScreenCaptureHolder.clear()
        mediaProjection = null
        virtualDisplay = null
        imageReader = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                NOTIF_CHANNEL_ID, "Ride Decider (lectura de pantalla)", android.app.NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("Ride Decider: lectura de pantalla activa")
            .setContentText("Usado para leer ofertas que no se detectan por accesibilidad")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }
}
