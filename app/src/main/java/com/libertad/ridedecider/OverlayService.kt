package com.libertad.ridedecider

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
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

    // Un badge de vista por packageName actualmente visible en pantalla.
    private val badges = mutableMapOf<String, TextView>()

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
        if (intent?.action == ACTION_REFRESH) refresh()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        badges.values.forEach { runCatching { windowManager.removeView(it) } }
        badges.clear()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun refresh() {
        val offers = RideOfferStore.snapshot()
        val bestPackage = currentEngine().pickBest(offers)?.offer?.appPackage

        val seenPackages = mutableSetOf<String>()

        for (offer in offers) {
            seenPackages += offer.appPackage
            val evaluation = currentEngine().evaluate(offer)
            val badge = badges.getOrPut(offer.appPackage) { createBadge(offer) }
            positionBadge(badge, offer)

            val marker = if (offer.appPackage == bestPackage) "★ " else ""
            badge.text = "$marker${offer.appLabel}\n" +
                "$${"%.0f".format(offer.fareGross ?: 0.0)} · ${"%.1f".format(evaluation.netPerKm ?: 0.0)}/km neto\n" +
                "score ${evaluation.score?.toInt() ?: "-"}"
            badge.setBackgroundColor(colorForLight(evaluation.light))
        }

        // Sacar badges de apps que ya no están siendo detectadas (cambiaron de pantalla, etc).
        val stale = badges.keys - seenPackages
        for (pkg in stale) {
            badges.remove(pkg)?.let { runCatching { windowManager.removeView(it) } }
        }
    }

    private fun createBadge(offer: RideOffer): TextView {
        val view = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(20, 12, 20, 12)
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
        windowManager.addView(view, params)
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
