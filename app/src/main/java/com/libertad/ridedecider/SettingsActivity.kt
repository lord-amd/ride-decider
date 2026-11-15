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
 * Los valores se guardan en SharedPreferences (ver AppConfigHolder.kt), así que
 * sobreviven cerrar la app, reiniciar el celular, y actualizar Ride Decider a una
 * versión nueva — los datos de la app no se borran al instalar una actualización
 * encima, solo si la desinstalás del todo. Al abrir esta pantalla, los campos se
 * precargan con lo último que guardaste (antes siempre arrancaban en los valores
 * por defecto, incluso si ya habías guardado algo distinto).
 *
 * Como las apps ya no se identifican con un enum fijo (ahora es "cualquier app que
 * detecte en pantalla"), la comisión por plataforma se reemplazó por UNA comisión
 * por defecto (aplica a cualquier app nueva que aparezca) + overrides opcionales por
 * packageName para las apps que ya conocés. El campo de texto de overrides acepta
 * líneas "packageName=comisión", por ejemplo:
 *   com.ubercab.driver=0.25
 *   com.cabify.driver=0.20
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val savedWeights = AppConfigHolder.weights
        val savedCommissions = AppConfigHolder.commissions
        val savedCost = AppConfigHolder.operatingCost

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

        val wNetKm = labeledField("Peso: ganancia neta por km (0-1)", savedWeights.weightNetPerKm.toString())
        val wNetHour = labeledField("Peso: ganancia neta por hora (0-1)", savedWeights.weightNetPerHour.toString())
        val wDistance = labeledField("Peso: preferencia de distancia (0-1)", savedWeights.weightDistance.toString())
        val wPickup = labeledField("Peso: distancia hasta el pasajero (0-1)", savedWeights.weightPickupDistance.toString())

        val defaultCommission = labeledField(
            "Comisión por defecto (0-1, ej 0.20 = 20%)",
            savedCommissions.defaultCommission.toString()
        )
        val overridesText = savedCommissions.overrides.entries
            .joinToString("\n") { "${it.key}=${it.value}" }
            .ifBlank {
                "com.ubercab.driver=0.25\ncom.cabify.driver=0.20\ncom.didiglobal.driver=0.20\nsinet.startup.inDriver=0.10"
            }
        val overridesField = labeledField(
            "Comisiones por app conocida (una por línea: packageName=comisión)",
            overridesText,
            lines = 4
        )

        val minNet = labeledField("Ganancia neta mínima aceptable ($)", savedWeights.minNetEarnings.toString())
        val minNetKm = labeledField("Neto por km mínimo aceptable", savedWeights.minNetPerKm.toString())
        val maxPickup = labeledField("Distancia máxima a buscar al pasajero (km)", savedWeights.maxPickupDistanceKm.toString())

        val costPerKm = labeledField("Costo propio por km (combustible/desgaste)", savedCost.costPerKm.toString())

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

                // Esto ahora escribe directo a SharedPreferences (ver AppConfigHolder.kt),
                // así que queda guardado de verdad, no solo en memoria.
                AppConfigHolder.weights = weights
                AppConfigHolder.commissions = commissions
                AppConfigHolder.operatingCost = cost

                finish()
            }
        })

        setContentView(root)
    }
}
