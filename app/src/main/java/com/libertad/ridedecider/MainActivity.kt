package com.libertad.ridedecider

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Pantalla principal: pide los permisos necesarios (overlay + accesibilidad), deja
 * elegir qué apps abrir (AppPickerActivity, hasta 4) y, si el dispositivo soporta
 * freeform (tablet / modo escritorio), las lanza en ventanas separadas que se
 * acomodan solas para llenar toda la pantalla según cuántas hayas elegido.
 *
 * IMPORTANTE (ver nota de arquitectura): en celulares comunes sin freeform habilitado,
 * Android ignora setLaunchBounds y abre cada app en pantalla completa normal — en ese
 * caso usá el botón de cada app para ir abriéndolas y cambiando entre ellas, el overlay
 * del semáforo sigue funcionando igual porque lee lo que esté en foco en cada momento.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        root.addView(TextView(this).apply {
            text = "Ride Decider\n\n" +
                "1) Dale permiso de 'Mostrar sobre otras apps'\n" +
                "2) Activá el Accessibility Service 'Ride Decider'\n" +
                "3) Elegí qué apps abrir y lanzalas (freeform si tu dispositivo lo soporta)\n"
            textSize = 14f
        })

        root.addView(Button(this).apply {
            text = "1. Permiso de superposición"
            setOnClickListener { requestOverlayPermission() }
        })

        root.addView(Button(this).apply {
            text = "2. Activar Accessibility Service"
            setOnClickListener { openAccessibilitySettings() }
        })

        root.addView(Button(this).apply {
            text = "3. Elegir apps a abrir"
            setOnClickListener { startActivity(Intent(this@MainActivity, AppPickerActivity::class.java)) }
        })

        root.addView(Button(this).apply {
            text = "4. Iniciar overlay (semáforo)"
            setOnClickListener { startService(Intent(this@MainActivity, OverlayService::class.java)) }
        })

        root.addView(Button(this).apply {
            text = "5. Abrir las apps elegidas (freeform si disponible)"
            setOnClickListener { launchSelectedInFreeform() }
        })

        root.addView(Button(this).apply {
            text = "Cerrar las apps elegidas"
            setOnClickListener { closeSelectedApps() }
        })

        root.addView(Button(this).apply {
            text = "Configuración (pesos / comisiones)"
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        })

        root.addView(Button(this).apply {
            text = "Buscar actualización"
            setOnClickListener { checkForUpdate(showNoUpdateMessage = true) }
        })

        root.addView(Button(this).apply {
            text = "Cerrar Ride Decider"
            setOnClickListener { closeThisApp() }
        })

        setContentView(root)

        // Chequeo automático y silencioso cada vez que se abre la app; si no hay
        // nada nuevo no interrumpe con ningún cartel.
        checkForUpdate(showNoUpdateMessage = false)
    }

    private fun checkForUpdate(showNoUpdateMessage: Boolean) {
        UpdateChecker.checkForUpdate { info ->
            if (info != null && UpdateChecker.isNewer(info)) {
                AlertDialog.Builder(this)
                    .setTitle("Hay una versión nueva")
                    .setMessage("${info.releaseLabel}\n\nSe va a descargar sola; cuando termine, confirmá la instalación con un toque.")
                    .setPositiveButton("Descargar") { _, _ -> UpdateChecker.downloadAndInstall(this, info) }
                    .setNegativeButton("Ahora no", null)
                    .show()
            } else if (showNoUpdateMessage) {
                AlertDialog.Builder(this)
                    .setTitle("Ride Decider")
                    .setMessage("Ya tenés la última versión (o no se pudo consultar GitHub ahora).")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    /**
     * Lanza las apps que elegiste en AppPickerActivity, cada una en una región que se
     * calcula sola para que entre 1 y 4 apps llenen SIEMPRE toda la pantalla (no es un
     * 2x2 fijo): 1 app = pantalla completa, 2 = mitades, 3 = una grande + dos chicas,
     * 4 = grilla 2x2. Esto SOLO tiene efecto visual real si el dispositivo soporta
     * freeform (tablets grandes, Android 12L+, o Samsung DeX en modo escritorio); en
     * un celular común, Android abre cada app en pantalla completa igual.
     */
    private fun launchSelectedInFreeform() {
        val selected = AppSelectionStore.getSelected(this)
        if (selected.isEmpty()) {
            Toast.makeText(this, "Primero elegí las apps (paso 3)", Toast.LENGTH_SHORT).show()
            return
        }

        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val bounds = computeBounds(selected.size, screenW, screenH)

        var abrioAlguna = false
        selected.forEachIndexed { index, pkg ->
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg) ?: return@forEachIndexed
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)

            val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                ActivityOptions.makeBasic().apply { launchBounds = bounds[index] }
            } else null

            try {
                startActivity(launchIntent, options?.toBundle())
                abrioAlguna = true
            } catch (_: Exception) {
                // app desinstalada desde que la elegiste, o no permite estas flags; se omite.
            }
        }
        if (!abrioAlguna) {
            Toast.makeText(this, "No se pudo abrir ninguna de las apps elegidas", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Calcula los rectángulos para que `count` apps (1 a 4) llenen toda la pantalla,
     * sin importar cuántas sean. Para 3, la primera ocupa una franja completa y las
     * otras dos se reparten el resto, eligiendo filas o columnas según la orientación.
     */
    private fun computeBounds(count: Int, w: Int, h: Int): List<Rect> {
        val landscape = w > h
        return when (count.coerceAtMost(4)) {
            1 -> listOf(Rect(0, 0, w, h))

            2 -> if (landscape) {
                val halfW = w / 2
                listOf(Rect(0, 0, halfW, h), Rect(halfW, 0, w, h))
            } else {
                val halfH = h / 2
                listOf(Rect(0, 0, w, halfH), Rect(0, halfH, w, h))
            }

            3 -> if (landscape) {
                val halfW = w / 2
                val halfH = h / 2
                listOf(
                    Rect(0, 0, halfW, h),
                    Rect(halfW, 0, w, halfH),
                    Rect(halfW, halfH, w, h)
                )
            } else {
                val halfW = w / 2
                val halfH = h / 2
                listOf(
                    Rect(0, 0, w, halfH),
                    Rect(0, halfH, halfW, h),
                    Rect(halfW, halfH, w, h)
                )
            }

            else -> { // 4
                val halfW = w / 2
                val halfH = h / 2
                listOf(
                    Rect(0, 0, halfW, halfH),
                    Rect(halfW, 0, w, halfH),
                    Rect(0, halfH, halfW, h),
                    Rect(halfW, halfH, w, h)
                )
            }
        }
    }

    /**
     * "Cerrar" las apps elegidas. OJO con la limitación real de Android acá: una app
     * normal NO tiene forma de forzar el cierre de otra app que esté en primer plano
     * (es una restricción de seguridad del sistema, no algo que se pueda evitar sin
     * ser el dueño del dispositivo). killBackgroundProcesses() es lo único disponible
     * sin privilegios especiales, y solo tiene efecto si esa app ya pasó a segundo
     * plano — si la estás viendo activa en la pantalla dividida, puede no hacer nada.
     * En ese caso, Android te va a pedir que la cierres vos deslizándola en "Recientes".
     */
    private fun closeSelectedApps() {
        val selected = AppSelectionStore.getSelected(this)
        if (selected.isEmpty()) {
            Toast.makeText(this, "No elegiste ninguna app todavía", Toast.LENGTH_SHORT).show()
            return
        }
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        selected.forEach { pkg -> am.killBackgroundProcesses(pkg) }

        Toast.makeText(
            this,
            "Se pidió cerrar las apps elegidas. Si alguna seguía activa en pantalla, " +
                "Android no deja forzarla desde otra app — cerrala deslizándola en Recientes.",
            Toast.LENGTH_LONG
        ).show()
    }

    /** Cierra el panel flotante y esta pantalla. El Accessibility Service sigue activo
     *  en segundo plano (es lo esperado: para apagarlo del todo hay que desactivarlo
     *  en Ajustes de Accesibilidad, igual que hoy). */
    private fun closeThisApp() {
        stopService(Intent(this, OverlayService::class.java))
        finishAndRemoveTask()
    }
}
