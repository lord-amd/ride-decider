package com.libertad.ridedecider

import android.app.ActivityOptions
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Pantalla principal: pide los permisos necesarios (overlay + accesibilidad) y, si el
 * dispositivo soporta freeform (tablet / modo escritorio), intenta lanzar las 4 apps
 * de conductor en ventanas separadas usando ActivityOptions.setLaunchBounds.
 *
 * IMPORTANTE (ver nota de arquitectura): en celulares comunes sin freeform habilitado,
 * Android ignora setLaunchBounds y abre cada app en pantalla completa normal — en ese
 * caso usá el botón de cada app para ir abriéndolas y cambiando entre ellas, el overlay
 * del semáforo sigue funcionando igual porque lee lo que esté en foco en cada momento.
 */
class MainActivity : AppCompatActivity() {

    private val targets = listOf(
        "com.ubercab.driver" to "Uber",
        "com.cabify.driver" to "Cabify",
        "com.didiglobal.driver" to "DiDi",
        "sinet.startup.inDriver" to "inDrive"
    )

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
                "3) Lanzá las apps (freeform si tu dispositivo lo soporta)\n"
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
            text = "3. Iniciar overlay (semáforo)"
            setOnClickListener { startService(Intent(this@MainActivity, OverlayService::class.java)) }
        })

        root.addView(Button(this).apply {
            text = "4. Lanzar las 4 apps (freeform si disponible)"
            setOnClickListener { launchAllInFreeform() }
        })

        root.addView(Button(this).apply {
            text = "Configuración (pesos / comisiones)"
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        })

        root.addView(Button(this).apply {
            text = "Buscar actualización"
            setOnClickListener { checkForUpdate(showNoUpdateMessage = true) }
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
     * Intenta lanzar cada app en un cuadrante de la pantalla usando freeform launch
     * bounds. Esto SOLO tiene efecto real si:
     *  - el dispositivo soporta android.software.freeform_window_management
     *    (tablets grandes, Android 12L+, o Samsung DeX en modo escritorio), y
     *  - la app destino está instalada (si no, se la omite silenciosamente — podés
     *    cambiar esto por un Toast si preferís feedback visible).
     * En un celular común sin esas condiciones, el sistema simplemente abre cada app
     * en pantalla completa (comportamiento normal), así que no rompe nada probarlo.
     */
    private fun launchAllInFreeform() {
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val halfW = screenW / 2
        val halfH = screenH / 2

        val quadrants = listOf(
            Rect(0, 0, halfW, halfH),
            Rect(halfW, 0, screenW, halfH),
            Rect(0, halfH, halfW, screenH),
            Rect(halfW, halfH, screenW, screenH)
        )

        targets.forEachIndexed { index, (pkg, _) ->
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg) ?: return@forEachIndexed
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)

            val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                ActivityOptions.makeBasic().apply {
                    launchBounds = quadrants[index]
                }
            } else null

            try {
                startActivity(launchIntent, options?.toBundle())
            } catch (_: Exception) {
                // app no instalada o no permite estas flags; se omite.
            }
        }
    }
}
