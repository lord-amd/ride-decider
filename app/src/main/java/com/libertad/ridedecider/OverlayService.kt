package com.libertad.ridedecider

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat

/**
 * Dibuja UN badge de color (semáforo) por cada app/región detectada en pantalla,
 * posicionado sobre el borde superior de esa región (usando RideOffer.windowBounds),
 * en vez de un solo panel fijo en una esquina. Así, con la pantalla dividida en 2, 3 o
 * 4 partes, cada porción tiene su propio indicador verde/amarillo/rojo encima.
 *
 * Es un foreground service (requerido en Android 8+) para que el sistema no lo mate.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_REFRESH = "com.libertad.ridedecider.action.REFRESH"
        private const val NOTIF_CHANNEL_ID = "ride_decider_overlay"
        private const val NOTIF_ID = 1
    }

    private lateinit var windowManager: WindowManager
    private val uiHandler = Handler(Looper.getMainLooper())

    // Un badge de vista por packageName actualmente visible en pantalla.
    private val badges = mutableMapOf<String, TextView>()

    // Un Runnable de auto-ocultado pendiente por packageName, para poder cancelar y
    // reprogramar cada vez que llega un dato nuevo de esa app (ver DisplayConfig).
    private val hideTimers = mutableMapOf<String, Runnable>()

    // Último texto mostrado por packageName, para notar si realmente cambió algo.
    // Sin esto, el reescaneo periódico (cada 1.5s) reiniciaba el timer de
    // auto-ocultado en cada pasada aunque no hubiera ningún dato nuevo, y el cartel
    // nunca llegaba a desaparecer.
    private val lastTexts = mutableMapOf<String, String>()

    private fun currentEngine() = DecisionEngine(
        weights = AppConfigHolder.weights,
        commissions = AppConfigHolder.commissions,
        operatingCost = AppConfigHolder.operatingCost
    )

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Chequeo defensivo: si por lo que sea el permiso de superposición no está
        // activo en este momento, NO seguimos para no crashear (addView tiraría una
        // excepción sin capturar). Antes esto no se chequeaba acá y un crash de este
        // servicio tumbaba TODO el proceso de la app -> el Accessibility Service,
        // que corre en el mismo proceso, se caía con él y Android lo mostraba como
        // "desactivado" sin que vos lo hubieras tocado.
        if (!Settings.canDrawOverlays(this)) {
            Log.w("OverlayService", "Falta el permiso de superposición, no se dibuja nada")
            Toast.makeText(this, "Ride Decider: falta el permiso de superposición", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_REFRESH) {
            try {
                refresh()
            } catch (e: Exception) {
                // Cualquier error al dibujar un badge no debe tumbar el proceso entero.
                Log.e("OverlayService", "Error al refrescar el overlay", e)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        hideTimers.values.forEach { uiHandler.removeCallbacks(it) }
        hideTimers.clear()
        badges.values.forEach { runCatching { windowManager.removeView(it) } }
        badges.clear()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun refresh() {
        val offers = RideOfferStore.snapshot()
        val display = AppConfigHolder.displayConfig
        // El semáforo (color) SIEMPRE se calcula con todos los datos disponibles acá
        // abajo, sin importar qué campos eligió mostrar el usuario en Settings — lo
        // único que DisplayConfig afecta es el texto que se arma más abajo.
        val bestPackage = currentEngine().pickBest(offers)?.offer?.appPackage

        val seenPackages = mutableSetOf<String>()

        for (offer in offers) {
            seenPackages += offer.appPackage
            val evaluation = currentEngine().evaluate(offer)
            val badge = badges.getOrPut(offer.appPackage) { createBadge(offer) }
            positionBadge(badge, offer)

            val newText = buildBadgeText(offer, evaluation, display, isBest = offer.appPackage == bestPackage)
            badge.text = newText
            badge.setBackgroundColor(colorForLight(evaluation.light))

            // Solo reiniciamos el cartel (mostrarlo + reprogramar el auto-ocultado) si
            // el contenido realmente cambió. Si es el mismo texto que la pasada
            // anterior (reescaneo de rutina sin novedades), no tocamos nada: si ya
            // estaba oculto, sigue oculto; si está visible, el timer sigue corriendo
            // sin reiniciarse.
            if (lastTexts[offer.appPackage] != newText) {
                lastTexts[offer.appPackage] = newText
                badge.visibility = android.view.View.VISIBLE
                if (offer.fareGross != null) {
                    // Hay una oferta real detectada: este es el dato que importa leer
                    // rápido, así que se oculta solo después del tiempo configurado.
                    scheduleAutoHide(offer.appPackage, badge, display.badgeDurationSeconds)
                } else {
                    // Todavía no hay ninguna oferta en esta app ("Esperando datos…").
                    // Este cartel NO se autooculta: se queda como confirmación
                    // permanente de que la app sigue siendo monitoreada, hasta que
                    // aparezca una oferta real o la app deje de estar en pantalla.
                    hideTimers.remove(offer.appPackage)?.let { uiHandler.removeCallbacks(it) }
                }
            }
        }

        // Sacar badges de apps que ya no están siendo detectadas (cambiaron de pantalla, etc).
        val stale = badges.keys - seenPackages
        for (pkg in stale) {
            hideTimers.remove(pkg)?.let { uiHandler.removeCallbacks(it) }
            lastTexts.remove(pkg)
            badges.remove(pkg)?.let { runCatching { windowManager.removeView(it) } }
        }
    }

    /** Arma el texto del cartel solo con los campos que el usuario eligió mostrar. */
    private fun buildBadgeText(offer: RideOffer, evaluation: RideEvaluation, display: DisplayConfig, isBest: Boolean): String {
        if (offer.fareGross == null) {
            // Todavía no se detectó ninguna oferta en esta app — cartel neutro de
            // confirmación de que esta región SÍ está siendo leída.
            return "${offer.appLabel}\nEsperando datos…"
        }

        val marker = if (isBest) "★ " else ""
        val lines = mutableListOf("$marker${offer.appLabel}")

        val datos = mutableListOf<String>()
        if (display.showFare) datos += "$${"%.0f".format(offer.fareGross)}"
        if (display.showDistance && offer.distanceKm != null) datos += "${"%.1f".format(offer.distanceKm)} km"
        if (datos.isNotEmpty()) lines += datos.joinToString(" · ")

        val netos = mutableListOf<String>()
        if (display.showNetPerKm) netos += "${"%.0f".format(evaluation.netPerKm ?: 0.0)}/km neto"
        if (display.showNetPerHour) netos += "${"%.0f".format(evaluation.netPerHour ?: 0.0)}/hora neto"
        if (netos.isNotEmpty()) lines += netos.joinToString(" · ")

        if (display.showScore) {
            // "Score" explicado en criollo en vez de solo el número: 0 a 100, cuanto
            // más alto más conviene este viaje según tus pesos configurados.
            lines += "Conviene: ${evaluation.score?.toInt() ?: "-"}/100"
        }

        return lines.joinToString("\n")
    }

    /** Oculta el cartel solo (no lo borra del todo, por si llega un dato nuevo antes). */
    private fun scheduleAutoHide(pkg: String, badge: TextView, durationSeconds: Int) {
        hideTimers.remove(pkg)?.let { uiHandler.removeCallbacks(it) }
        if (durationSeconds <= 0) return // 0 = nunca ocultar

        val hideRunnable = Runnable { badge.visibility = android.view.View.GONE }
        hideTimers[pkg] = hideRunnable
        uiHandler.postDelayed(hideRunnable, durationSeconds * 1000L)
    }

    private fun createBadge(offer: RideOffer): TextView {
        val view = TextView(this).apply {
            // 3 veces más grande que el tamaño original (12sp / padding 20,12).
            textSize = 36f
            setTextColor(Color.WHITE)
            setPadding(60, 36, 60, 36)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            Log.e("OverlayService", "No se pudo agregar el badge flotante", e)
        }
        return view
    }

    /** Ubica el badge pegado al borde superior de la región que ocupa esa app. */
    private fun positionBadge(badge: TextView, offer: RideOffer) {
        val params = badge.layoutParams as? WindowManager.LayoutParams ?: return
        val bounds = offer.windowBounds
        if (bounds != null) {
            params.x = bounds.left + 8
            params.y = bounds.top + 8
        }
        runCatching { windowManager.updateViewLayout(badge, params) }
    }

    private fun colorForLight(light: Light): Int = when (light) {
        Light.GREEN -> Color.parseColor("#DD2E7D32")
        Light.YELLOW -> Color.parseColor("#DDF9A825")
        Light.RED -> Color.parseColor("#DDC62828")
        Light.UNKNOWN -> Color.parseColor("#DD222222")
    }

    private fun buildNotification(): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                NOTIF_CHANNEL_ID, "Ride Decider", android.app.NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("Ride Decider activo")
            .setContentText("Monitoreando solicitudes de viaje")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }
}
