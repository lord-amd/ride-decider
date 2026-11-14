package com.libertad.ridedecider

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Button
import android.widget.TextView

/**
 * Pantalla simple para configurar los parámetros que pediste: distancia, tiempo,
 * dinero/ganancia y precio por km (vía los pesos de UserWeights), más la comisión
 * estimada para calcular la ganancia neta real.
 *
 * Como las apps ya no se identifican con un enum fijo (ahora es "cualquier app que
 * detecte en pantalla"), la comisión por plataforma se reemplazó por UNA comisión
 * por defecto (aplica a cualquier app nueva que aparezca) + overrides opcionales por
 * packageName para las apps que ya conocés. El campo de texto de overrides acepta
 * líneas "packageName=comisión", por ejemplo:
 *   com.ubercab.driver=0.25
 *   com.cabify.driver=0.20
 *
 * TODO: persistir estos valores con androidx.datastore (ya está en las dependencias)
 * en vez de solo mantenerlos en memoria — dejado simple a propósito para que puedas
 * ajustar rápido la UI sin pelear con DataStore todavía.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        fun labeledField(label: String, default: String, lines: Int = 1): EditText {
            root.addView(TextView(this).apply { text = label })
            val field = EditText(this).apply {
                setText(default)
                minLines = lines
                maxLines = lines
            }
            root.addView(field)
            return field
        }

        val wNetKm = labeledField("Peso: ganancia neta por km (0-1)", "0.4")
        val wNetHour = labeledField("Peso: ganancia neta por hora (0-1)", "0.35")
        val wDistance = labeledField("Peso: preferencia de distancia (0-1)", "0.15")
        val wPickup = labeledField("Peso: distancia hasta el pasajero (0-1)", "0.10")

        val defaultCommission = labeledField("Comisión por defecto (0-1, ej 0.20 = 20%)", "0.20")
        val overridesField = labeledField(
            "Comisiones por app conocida (una por línea: packageName=comisión)",
            "com.ubercab.driver=0.25\ncom.cabify.driver=0.20\ncom.didiglobal.driver=0.20\nsinet.startup.inDriver=0.10",
            lines = 4
        )

        val minNet = labeledField("Ganancia neta mínima aceptable ($)", "0")
        val minNetKm = labeledField("Neto por km mínimo aceptable", "0")
        val maxPickup = labeledField("Distancia máxima a buscar al pasajero (km)", "5")

        val costPerKm = labeledField("Costo propio por km (combustible/desgaste)", "0")

        root.addView(Button(this).apply {
            text = "Guardar"
            setOnClickListener {
                val weights = UserWeights(
                    weightNetPerKm = wNetKm.text.toString().toDoubleOrNull() ?: 0.4,
                    weightNetPerHour = wNetHour.text.toString().toDoubleOrNull() ?: 0.35,
                    weightDistance = wDistance.text.toString().toDoubleOrNull() ?: 0.15,
                    weightPickupDistance = wPickup.text.toString().toDoubleOrNull() ?: 0.10,
                    minNetEarnings = minNet.text.toString().toDoubleOrNull() ?: 0.0,
                    minNetPerKm = minNetKm.text.toString().toDoubleOrNull() ?: 0.0,
                    maxPickupDistanceKm = maxPickup.text.toString().toDoubleOrNull() ?: 5.0
                )

                val overrides = overridesField.text.toString()
                    .lines()
                    .mapNotNull { line ->
                        val parts = line.split("=")
                        if (parts.size == 2) {
                            val pkg = parts[0].trim()
                            val value = parts[1].trim().toDoubleOrNull()
                            if (pkg.isNotEmpty() && value != null) pkg to value else null
                        } else null
                    }
                    .toMap()

                val commissions = CommissionConfig(
                    defaultCommission = defaultCommission.text.toString().toDoubleOrNull() ?: 0.20,
                    overrides = overrides
                )
                val cost = OperatingCost(costPerKm = costPerKm.text.toString().toDoubleOrNull() ?: 0.0)

                // TODO: persistir weights/commissions/cost con DataStore y que
                // OverlayService los lea de ahí en vez de usar los valores default.
                AppConfigHolder.weights = weights
                AppConfigHolder.commissions = commissions
                AppConfigHolder.operatingCost = cost

                finish()
            }
        })

        setContentView(root)
    }
}

/** Puente simple en memoria entre SettingsActivity y OverlayService (reemplazar por DataStore). */
object AppConfigHolder {
    var weights: UserWeights = UserWeights()
    var commissions: CommissionConfig = CommissionConfig()
    var operatingCost: OperatingCost = OperatingCost()
}
