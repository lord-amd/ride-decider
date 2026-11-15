package com.libertad.ridedecider

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Lista todas las apps instaladas (con ícono de lanzador propio, osea apps de usuario
 * normales, no servicios del sistema) para que elijas hasta 4 — las que quieras tener
 * abiertas en multiventana. No hay ninguna lista fija de Uber/Cabify/DiDi/inDrive acá:
 * podés elegir cualquier combinación, incluida una app nueva que todavía no probamos.
 */
class AppPickerActivity : AppCompatActivity() {

    private data class AppEntry(val packageName: String, val label: String)

    private val checkBoxes = mutableListOf<Pair<CheckBox, AppEntry>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val apps = loadLaunchableApps()
        val alreadySelected = AppSelectionStore.getSelected(this).toSet()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        root.addView(TextView(this).apply {
            text = "Elegí hasta ${AppSelectionStore.MAX_APPS} apps para abrir en multiventana " +
                "(el orden en que las toques es el orden en que se van a acomodar en pantalla)."
            textSize = 14f
            setPadding(0, 0, 0, 24)
        })

        val listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        for (app in apps) {
            val checkBox = CheckBox(this).apply {
                text = "${app.label}  (${app.packageName})"
                isChecked = app.packageName in alreadySelected
            }
            checkBox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked && checkBoxes.count { it.first.isChecked } > AppSelectionStore.MAX_APPS) {
                    checkBox.isChecked = false
                    Toast.makeText(this, "Como máximo ${AppSelectionStore.MAX_APPS} apps", Toast.LENGTH_SHORT).show()
                }
            }
            checkBoxes += checkBox to app
            listContainer.addView(checkBox)
        }

        val scroll = ScrollView(this).apply {
            addView(listContainer)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(scroll)

        root.addView(Button(this).apply {
            text = "Guardar selección"
            setOnClickListener {
                val selected = checkBoxes.filter { it.first.isChecked }.map { it.second.packageName }
                if (selected.isEmpty()) {
                    Toast.makeText(this@AppPickerActivity, "Elegí al menos una app", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                AppSelectionStore.setSelected(this@AppPickerActivity, selected)
                setResult(RESULT_OK)
                finish()
            }
        })

        setContentView(root)
    }

    /** Apps de usuario con ícono propio de lanzador (excluye la nuestra y apps puramente del sistema). */
    private fun loadLaunchableApps(): List<AppEntry> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val resolveInfos = packageManager.queryIntentActivities(launcherIntent, 0)

        return resolveInfos
            .asSequence()
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != packageName } // no listarnos a nosotros mismos
            .map { appInfo: ApplicationInfo ->
                AppEntry(
                    packageName = appInfo.packageName,
                    label = packageManager.getApplicationLabel(appInfo).toString()
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }
}
